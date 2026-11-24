package app.earshot

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The speech pipeline, the way Plaud-style recorders do it: keep the speech (silence is dropped
 * by the voice detector), collect it into chunks, and only when a chunk is finished find who
 * spoke when and transcribe it. Working on whole chunks is what makes speaker labels possible.
 *
 * A chunk on disk is <ms>.pcm (16 kHz 16-bit mono speech, segments separated by short gaps) plus
 * <ms>.idx ("<sample offset>\t<epoch ms>\t<samples>" per speech segment). The .idx appears last,
 * so its presence means the chunk is complete.
 */
object Speech {
    const val RATE = 16_000
    const val BLOCK = 4096                        // samples per mic read (256 ms)
    private const val GAP = RATE * 3 / 10         // 0.3 s of silence between segments

    fun threads(): Int {
        val n = allowedCpus().takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
        return (n - 1).coerceIn(1, 4)
    }

    /** CPUs this process may use (Android limits background apps to fewer, slower cores). */
    private fun allowedCpus(): Int = try {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
        line?.substringAfter(':')?.trim()?.split(',')?.sumOf { r ->
            val p = r.split('-').map { it.trim().toInt() }
            if (p.size == 2) p[1] - p[0] + 1 else 1
        } ?: 0
    } catch (_: Exception) { 0 }

    fun vad(ctx: Context, threshold: Float) = vad(Models.dir(ctx), threshold)

    fun vad(models: File, threshold: Float) = Vad(config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = Models.path(models, Models.VAD),
            threshold = threshold,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 20f,   // long talk is cut into <= 20 s pieces for the recognizer
        ),
        sampleRate = RATE,
        numThreads = 1,
    ))

    /** Collects speech segments into a chunk file. Not thread-safe; owned by one recorder. */
    class ChunkWriter(private val dir: File) {
        private var startMs = 0L
        private var pcm: DataOutputStream? = null
        private var idx: StringBuilder? = null
        private var samples = 0L
        /** Speech in the open chunk, in samples (gaps excluded). */
        var speech = 0L
            private set
        var lastSpeechMs = 0L
            private set
        val open get() = pcm != null

        fun add(timeMs: Long, s: FloatArray) {
            if (pcm == null) {
                startMs = timeMs
                dir.mkdirs()  // the folder must exist; never let a missing folder stop the recording
                pcm = DataOutputStream(File(dir, "$timeMs.pcm.part").outputStream().buffered(1 shl 16))
                idx = StringBuilder()
                samples = 0; speech = 0
            }
            idx!!.append(samples).append('\t').append(timeMs).append('\t').append(s.size).append('\n')
            val out = pcm!!
            val buf = ByteBuffer.allocate((s.size + GAP) * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (x in s) buf.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            out.write(buf.array())  // trailing zeros = the gap
            samples += s.size + GAP
            speech += s.size
            lastSpeechMs = timeMs + s.size * 1000L / RATE
        }

        /** True when the chunk should be handed to the processor. */
        fun due(now: Long) = open && (speech >= RATE * 600L ||           // 10 min of speech
            now - lastSpeechMs >= 60_000 ||                              // a minute of quiet: conversation over
            now - startMs >= 30 * 60_000)

        fun close() {
            val out = pcm ?: return
            out.close()
            dir.mkdirs()
            File(dir, "$startMs.pcm.part").renameTo(File(dir, "$startMs.pcm"))
            File(dir, "$startMs.idx").writeText(idx.toString())
            pcm = null; idx = null
        }

        companion object {
            /** After a crash: finish chunks that were still being written. */
            fun recover(dir: File) {
                dir.listFiles { f -> f.name.endsWith(".pcm.part") }?.forEach { f ->
                    val ms = f.name.removeSuffix(".pcm.part")
                    f.renameTo(File(dir, "$ms.pcm"))
                    // Without its index the chunk can't be timed; give it one segment at its start.
                    val n = File(dir, "$ms.pcm").length() / 2
                    if (!File(dir, "$ms.idx").exists()) File(dir, "$ms.idx").writeText("0\t$ms\t$n\n")
                }
            }

            fun ready(dir: File): List<File> =
                dir.listFiles { f -> f.name.endsWith(".idx") }?.sortedBy { it.name }.orEmpty()
        }
    }

    /**
     * Feeds audio through the voice detector and writes the speech, with 0.3 s of audio kept on
     * each side so first and last words aren't clipped, into a [ChunkWriter].
     */
    class Collector(private val vad: Vad, private val writer: ChunkWriter, private val baseMs: Long) {
        private val ring = FloatArray(RATE * 45)
        private var total = 0L                 // samples fed so far
        private var pendFrom = -1L
        private var pendTo = -1L

        val speaking get() = vad.isSpeechDetected()

        // The voice detector misses quiet, far voices. It gets a louder copy of the audio: the
        // gain lifts the room's noise floor to about -50 dBFS (at most 18 dB). Saved audio is not changed.
        private var floor = 0.003f
        private var boosted = FloatArray(0)

        fun feed(block: FloatArray) {
            for (i in block.indices) ring[((total + i) % ring.size).toInt()] = block[i]
            total += block.size
            var sum = 0.0
            for (x in block) sum += x * x
            val rms = kotlin.math.sqrt(sum / block.size).toFloat().coerceAtLeast(1e-5f)
            floor = if (rms < floor) rms else floor * 1.01f   // falls at once, rises ~4 % a second
            val gain = (0.003f / floor).coerceIn(1f, 8f)
            if (boosted.size != block.size) boosted = FloatArray(block.size)
            for (i in block.indices) boosted[i] = (block[i] * gain).coerceIn(-1f, 1f)
            vad.acceptWaveform(boosted)
            drain()
            if (pendFrom >= 0 && !vad.isSpeechDetected() && total >= pendTo + RATE) emit(pendTo)
        }

        /** Writes out speech still in progress (when listening stops or pauses). */
        fun flush() {
            vad.flush()
            drain()
            if (pendFrom >= 0) emit(minOf(pendTo, total))
        }

        /** Writes out the speech already finished, leaving any in progress for the next chunk. */
        fun cut() {
            if (pendFrom >= 0) emit(minOf(pendTo, total))
        }

        private fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                vad.pop()
                val a = maxOf(s.start - PAD, 0L).coerceAtLeast(total - ring.size + 1)
                val b = s.start + s.samples.size + PAD
                if (pendFrom >= 0 && a <= pendTo && b - pendFrom < RATE * 25) {
                    pendTo = b   // close to the last one: keep as one segment
                } else {
                    if (pendFrom >= 0) emit(minOf(pendTo, total))
                    pendFrom = a; pendTo = b
                }
            }
        }

        private fun emit(to: Long) {
            val from = maxOf(pendFrom, total - ring.size + 1)
            val n = (to - from).toInt()
            if (n > 0) writer.add(baseMs + from * 1000 / RATE, FloatArray(n) { ring[((from + it) % ring.size).toInt()] })
            pendFrom = -1; pendTo = -1
        }

        companion object { private const val PAD = (RATE * 3 / 10).toLong() }
    }

    data class Seg(val offset: Int, val timeMs: Long, val len: Int)

    /** One transcript line from a chunk. [speaker] is 1-based, 0 when only one voice was heard. */
    data class Out(val timeMs: Long, val speaker: Int, val text: String)

    private val LOCK = Any()   // one chunk at a time: the models take hundreds of MB

    /**
     * Speaker labels + transcript for one finished chunk. [progress] gets a status line.
     * Deletes nothing; the caller removes the chunk once the text is saved.
     */
    fun process(ctx: Context, settings: Settings, idxFile: File, progress: (String) -> Unit): List<Out> =
        process(Models.dir(ctx), settings.asrModel, settings.diarize, idxFile, progress, settings.skipBackground)

    fun process(models: File, asrId: String, diarize: Boolean, idxFile: File, progress: (String) -> Unit,
                skipBackground: Boolean = true): List<Out> = synchronized(LOCK) {
        val pcmFile = File(idxFile.path.removeSuffix(".idx") + ".pcm")
        val segs = idxFile.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else Seg(p[0].toInt(), p[1].toLong(), p[2].toInt())
        }
        if (segs.isEmpty() || !pcmFile.exists()) return emptyList()
        val bytes = pcmFile.readBytes()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audio = FloatArray(sb.remaining()) { sb.get() / 32768f }
        val speechSec = segs.sumOf { it.len } / RATE
        val threads = threads()

        // 1. Who spoke when, over the whole chunk.
        var turns: List<OfflineSpeakerDiarizationSegment> = emptyList()
        if (diarize && speechSec >= 3 && Models.has(models, Models.SEGMENTATION) && Models.has(models, Models.EMBEDDING)) {
            progress("Finding speakers in ${minutes(speechSec)} of speech…")
            val d = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(Models.path(models, Models.SEGMENTATION)),
                    numThreads = threads),
                embedding = SpeakerEmbeddingExtractorConfig(model = Models.path(models, Models.EMBEDDING), numThreads = threads),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.8f),  // tested on sample voices: 0.7 split one voice, 0.8–1.0 right
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ))
            try {
                // (process, not processWithCallback: the JNI callback needs a signature Kotlin lambdas don't have)
                turns = d.process(audio).sortedBy { it.start }
            } finally {
                d.release()
            }
        }

        // 2. Split each speech segment where the speaker changes.
        data class Piece(val from: Int, val to: Int, val timeMs: Long, val speaker: Int)
        val pieces = mutableListOf<Piece>()
        for (s in segs) {
            val a = s.offset; val b = s.offset + s.len
            val inside = turns.filter { it.end * RATE > a && it.start * RATE < b }
            if (inside.size <= 1 || inside.map { it.speaker }.distinct().size == 1) {
                pieces += Piece(a, b, s.timeMs, inside.firstOrNull()?.speaker ?: -1)
                continue
            }
            // Cut halfway between consecutive turns of different speakers.
            var from = a
            for (i in inside.indices) {
                val t = inside[i]
                val next = inside.getOrNull(i + 1)
                val to = if (next == null) b else ((t.end + next.start) / 2 * RATE).toInt().coerceIn(from, b)
                if (next != null && next.speaker == t.speaker) continue
                if (to - from >= RATE / 4) pieces += Piece(from, to, s.timeMs + (from - a) * 1000L / RATE, t.speaker)
                from = to
            }
        }
        // 2b. Give the recognizer whole sentences, not fragments: a piece shorter than 0.6 s joins
        // the piece before it (such tiny turns are mostly speaker-model noise), and consecutive
        // pieces of the same speaker are joined up to 25 s. Transcribing 1-3 s fragments on their
        // own was the main cause of wrong words.
        val joined = mutableListOf<Piece>()
        for (p in pieces.sortedBy { it.from }) {
            val last = joined.lastOrNull()
            val tiny = p.to - p.from < RATE * 6 / 10
            val same = last != null && (last.speaker == p.speaker || tiny)
            if (last != null && same && p.to - last.from <= RATE * 25) {
                joined[joined.size - 1] = last.copy(to = p.to)
            } else joined += p
        }
        pieces.clear()
        pieces += joined

        // 2c. Background voices (a TV, people at the next table) reach the mic much quieter than
        // the conversation. Drop a speaker whose typical level is more than 24 dB below the
        // loudest speaker's; pieces without a speaker are judged on their own level.
        if (skipBackground && pieces.size > 1) {
            fun rms(p: Piece): Double {
                var sum = 0.0; var n = 0
                for (i in p.from until minOf(p.to, audio.size)) { val x = audio[i]; if (x != 0f) { sum += x * x; n++ } }
                return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
            }
            val levels = pieces.map { rms(it) }
            fun median(v: List<Double>) = v.sorted()[v.size / 2]
            val bySpeaker = pieces.indices.filter { pieces[it].speaker >= 0 }.groupBy { pieces[it].speaker }
                .mapValues { (_, idx) -> median(idx.map { levels[it] }) }
            val ref = maxOf(bySpeaker.values.maxOrNull() ?: 0.0, median(levels))
            val floor = ref * 0.063   // -24 dB: a person 2 m away is still kept (-14 dB dropped them)
            val keep = pieces.indices.filter { i ->
                val p = pieces[i]
                val level = if (p.speaker >= 0) bySpeaker[p.speaker] ?: levels[i] else levels[i]
                level >= floor
            }
            if (keep.isNotEmpty() && keep.size < pieces.size) {
                val kept = keep.map { pieces[it] }
                pieces.clear()
                pieces += kept
            }
        }

        // Number speakers 1, 2, … in the order they first talk; no labels if only one voice.
        val order = pieces.map { it.speaker }.filter { it >= 0 }.distinct()
        fun label(raw: Int) = if (order.size < 2 || raw < 0) 0 else order.indexOf(raw) + 1

        // 3. Transcribe each piece.
        val rec = recognizer(models, asrId, threads)
        val out = mutableListOf<Out>()
        try {
            pieces.forEachIndexed { i, p ->
                progress("Transcribing ${minutes(speechSec)} of speech: ${i * 100 / pieces.size}%")
                val st = rec.createStream()
                // A little silence after the words helps the models finish the last one.
                val clip = audio.copyOfRange(p.from, minOf(p.to, audio.size)) + FloatArray(RATE / 4)
                st.acceptWaveform(clip, RATE)
                rec.decode(st)
                val text = clean(rec.getResult(st).text)
                st.release()
                if (text.isNotEmpty()) out += Out(p.timeMs, label(p.speaker), text)
            }
        } finally {
            rec.release()
        }
        // Join consecutive lines from the same speaker into one, so it reads like a transcript.
        val merged = mutableListOf<Out>()
        for (o in out) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == o.speaker && o.speaker != 0 && last.text.length < 600) {
                merged[merged.size - 1] = last.copy(text = last.text + " " + o.text)
            } else merged += o
        }
        merged
    }

    private fun recognizer(models: File, asrId: String, threads: Int): OfflineRecognizer {
        val m = Models.asr(asrId)
        if (!Models.has(models, m)) throw IllegalStateException("${m.label} not downloaded")
        fun f(n: String) = Models.path(models, m, n)
        val model = if (m.id.startsWith("parakeet")) OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = f("encoder.int8.onnx"),
                decoder = f("decoder.int8.onnx"), joiner = f("joiner.int8.onnx")),
            tokens = f("tokens.txt"), modelType = "nemo_transducer", numThreads = threads,
        ) else OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(preprocessor = f("preprocess.onnx"), encoder = f("encode.int8.onnx"),
                uncachedDecoder = f("uncached_decode.int8.onnx"), cachedDecoder = f("cached_decode.int8.onnx")),
            tokens = f("tokens.txt"), numThreads = threads,
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
    }

    private fun minutes(sec: Int) = if (sec < 90) "$sec s" else "${(sec + 30) / 60} min"

    private val NOISE = setOf("", ".", "...", "you", "thank you.", "thank you", "bye.", "okay.", "uh", "um", "mm", "hmm")

    fun clean(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.lowercase() in NOISE) return ""
        if (Regex("^[\\[(*].*[\\])*]$").matches(t)) return ""
        return t
    }

    /** Transcript text for a line, with its speaker label. */
    fun line(o: Out) = if (o.speaker > 0) "Speaker ${o.speaker}: ${o.text}" else o.text
}
