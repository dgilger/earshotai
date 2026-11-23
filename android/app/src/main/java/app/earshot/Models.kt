package app.earshot

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model files the app downloads once and keeps in files/models.
 * Speech models come from the sherpa-onnx GitHub releases (some as .tar.bz2, unpacked into
 * models/<id>/). Downloads run in the background; [state]
 * reports progress to whichever screen is open.
 */
object Models {
    enum class Kind { VAD, ASR, SEGMENTATION, EMBEDDING }

    /**
     * [archive] models unpack into models/<id>/ and [files] are paths inside it; single-file
     * models are stored as models/<file> and [files] holds just that name.
     */
    data class Model(
        val id: String, val kind: Kind, val label: String, val mb: Int, val url: String,
        val archive: Boolean, val files: List<String>,
    )

    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val VAD = Model("silero-vad", Kind.VAD, "Voice detector (Silero)", 1,
        "$GH/asr-models/silero_vad.onnx", false, listOf("silero_vad.onnx"))

    /** Speech-to-text models (sherpa-onnx, int8). */
    val ASR = listOf(
        Model("parakeet-tdt-0.6b-v2", Kind.ASR, "Parakeet 0.6B (NVIDIA), English", 480,
            "$GH/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", true,
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")),
    )

    /** Speaker labels: pyannote finds who-spoke-when, TitaNet tells voices apart. */
    val SEGMENTATION = Model("pyannote-seg-3.0", Kind.SEGMENTATION, "Speaker turns (pyannote 3.0)", 7,
        "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", true,
        listOf("model.int8.onnx"))
    val EMBEDDING = Model("titanet-small", Kind.EMBEDDING, "Voice prints (NVIDIA TitaNet small)", 40,
        "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx", false, listOf("nemo_en_titanet_small.onnx"))

    const val DEFAULT_ASR = "parakeet-tdt-0.6b-v2"

    fun all() = listOf(VAD) + ASR + listOf(SEGMENTATION, EMBEDDING)
    fun asr(id: String) = ASR.firstOrNull { it.id == id } ?: ASR.first()

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    /** Path of one of the model's files, inside the models folder [dir]. */
    fun path(dir: File, m: Model, name: String = m.files.first()): String =
        (if (m.archive) File(dir, "${m.id}/$name") else File(dir, name)).path
    fun path(ctx: Context, m: Model, name: String = m.files.first()) = path(dir(ctx), m, name)

    fun file(ctx: Context, m: Model) = File(path(ctx, m))
    fun has(dir: File, m: Model) = m.files.all { File(path(dir, m, it)).exists() }
    fun has(ctx: Context, m: Model) = has(dir(ctx), m)

    fun sizeMb(ctx: Context, m: Model): Int {
        val f = if (m.archive) File(dir(ctx), m.id) else file(ctx, m)
        return (f.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000).toInt()
    }

    /** Models needed to record and transcribe with the current settings. */
    fun needed(s: Settings): List<Model> {
        if (Server.ready(s)) return listOf(VAD)   // your server transcribes; the phone only finds speech
        val l = mutableListOf(VAD, asr(s.asrModel))
        if (s.diarize) l += listOf(SEGMENTATION, EMBEDDING)
        return l
    }

    fun ready(ctx: Context, s: Settings) = has(ctx, VAD) && (Server.ready(s) || has(ctx, asr(s.asrModel)))

    fun delete(ctx: Context, m: Model) {
        if (m.archive) File(dir(ctx), m.id).deleteRecursively() else file(ctx, m).delete()
        File(dir(ctx), "${m.id}.part").delete()
    }

    /** Whisper and summary-model files from earlier versions of the app (up to 2 GB). */
    fun removeOld(ctx: Context) {
        dir(ctx).listFiles { f -> (f.name.startsWith("ggml-") && f.name.endsWith(".bin")) || f.name.endsWith(".gguf") ||
            f.name.startsWith("qwen") }?.forEach { it.delete() }
        // Moonshine speech models (only Parakeet is offered now).
        dir(ctx).listFiles { f -> f.name.startsWith("moonshine") }?.forEach { it.deleteRecursively() }
    }

    // ---- background downloads ----

    /** Human-readable download state, or "" when idle. */
    @Volatile var state = ""
        private set
    @Volatile var lastError: String? = null
        private set
    private val queue = ArrayDeque<Model>()
    private var worker: Thread? = null

    val busy get() = synchronized(queue) { worker != null }

    /** Queues any of [models] that are missing; returns right away. [done] runs on the worker thread. */
    fun ensure(ctx: Context, models: List<Model>, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        synchronized(queue) {
            models.filter { !has(app, it) && it !in queue }.forEach { queue.addLast(it) }
            if (worker != null) return
            worker = Thread({
                lastError = null
                while (true) {
                    val m = synchronized(queue) { queue.removeFirstOrNull() ?: run { worker = null; null } } ?: break
                    try {
                        install(dir(app), m)
                    } catch (e: Exception) {
                        lastError = "${m.label}: ${e.message}"
                    }
                }
                state = ""
                done?.invoke()
            }, "downloads").apply { start() }
        }
    }

    internal fun install(dir: File, m: Model) {
        val part = File(dir, "${m.id}.part")
        fetch(m.url, part) { pct -> state = "Downloading ${m.label}: $pct%" }
        if (!m.archive) {
            if (!part.renameTo(File(path(dir, m)))) throw RuntimeException("could not save ${m.files.first()}")
            return
        }
        state = "Unpacking ${m.label}…"
        val tmp = File(dir, "${m.id}.tmp").apply { deleteRecursively(); mkdirs() }
        // Count the compressed bytes read so the screen can show how far unpacking has got.
        val size = part.length().coerceAtLeast(1)
        var lastPct = -1
        val counted = object : java.io.FilterInputStream(part.inputStream().buffered(1 shl 16)) {
            var n = 0L
            fun tick() {
                val pct = (n * 100 / size).toInt()
                if (pct != lastPct) { lastPct = pct; state = "Unpacking ${m.label}: $pct% (a few minutes for big models)" }
            }
            override fun read(): Int = super.read().also { if (it >= 0) { n++; tick() } }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { n += it; tick() } }
        }
        TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                // Archives hold one top folder (sherpa-onnx-.../); keep files flat under models/<id>/.
                val rel = e.name.removePrefix("./").substringAfter('/')
                if (rel.startsWith("test_wavs") || rel.contains("..")) continue
                val out = File(tmp, rel)
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
        part.delete()
        val final = File(dir, m.id)
        final.deleteRecursively()
        if (!tmp.renameTo(final)) throw RuntimeException("could not save ${m.id}")
        if (!has(dir, m)) throw RuntimeException("archive is missing files")
    }

    private fun fetch(url: String, dest: File, progress: (Int) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        var hops = 0
        while (conn.responseCode in 301..308 && hops++ < 5) {  // GitHub redirects to their CDNs
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
        }
        if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; progress(pct) }
                }
            }
        }
        conn.disconnect()
    }
}
