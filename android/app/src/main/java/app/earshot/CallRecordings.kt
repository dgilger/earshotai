package app.earshot

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Phone calls: Android doesn't let apps hear calls, but many phones have a call recorder that saves
 * a file per call. The user picks that folder once; new files in it are decoded, cut to speech and
 * turned into a session like a live recording (speaker labels, transcript, saved to the folder).
 * The call file itself is only read, never changed or copied.
 */
object CallRecordings {
    /** Progress line for the main screen; empty when idle. */
    @Volatile var state = ""
        private set
    private val LOCK = Any()
    private val AUDIO_EXT = setOf("m4a", "mp3", "amr", "wav", "ogg", "opus", "aac", "3gp", "3ga", "awb", "flac", "webm")

    data class Doc(val id: String, val uri: Uri, val name: String, val modified: Long)

    /** Folder chosen: only calls recorded from now on are transcribed. */
    fun choose(ctx: Context, tree: Uri, name: String) {
        val s = Settings(ctx)
        ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        s.callFolder = tree.toString()
        s.callFolderName = name
        s.callsSince = System.currentTimeMillis() - 10 * 60_000L   // a call that just ended still counts
        s.callsSeen = emptySet()
        Uploader.scheduleCalls(ctx)
    }

    fun clear(ctx: Context) {
        val s = Settings(ctx)
        try {
            if (s.callFolder.isNotEmpty()) ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s.callFolder),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        s.callFolder = ""
        s.callFolderName = ""
        Uploader.scheduleCalls(ctx)
    }

    /** New call files, oldest first. Looks one folder level down too (some recorders sort by contact or date). */
    fun newFiles(ctx: Context): List<Doc> {
        val s = Settings(ctx)
        if (s.callFolder.isEmpty()) return emptyList()
        val tree = Uri.parse(s.callFolder)
        val seen = s.callsSeen
        val out = mutableListOf<Doc>()
        fun list(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val mod = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 1) list(id, depth + 1); continue }
                    val audio = mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
                    // Skip files still being written (changed in the last 30 s).
                    if (audio && mod > s.callsSince && mod < System.currentTimeMillis() - 30_000 && id !in seen) {
                        out += Doc(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mod)
                    }
                }
            }
        }
        return try {
            list(DocumentsContract.getTreeDocumentId(tree), 0)
            out.sortedBy { it.modified }
        } catch (e: Exception) {
            Log.w("earshot", "call folder unreadable", e)
            state = ""
            emptyList()
        }
    }

    /**
     * Turns each new call file into a stopped session for [Processor.drain]. Returns true when
     * it added one. [keepGoing] false stops between files.
     */
    fun scan(ctx: Context, keepGoing: () -> Boolean): Boolean = synchronized(LOCK) {
        val docs = newFiles(ctx)
        var added = false
        for (d in docs) {
            if (!keepGoing()) break
            try {
                prepare(ctx, d)
                added = true
            } catch (t: Throwable) {
                Log.e("earshot", "call import failed for ${d.name}", t)
            }
            // Mark it done even if it failed, so a broken file isn't retried forever.
            val s = Settings(ctx)
            s.callsSeen = (s.callsSeen + d.id).toList().takeLast(500).toSet()
        }
        state = ""
        return added
    }

    private fun prepare(ctx: Context, d: Doc) {
        val settings = Settings(ctx)
        val store = Store(ctx)
        val ex = MediaExtractor()
        ex.setDataSource(ctx, d.uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); return }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val start = fromName(d.name) ?: (d.modified - durationUs / 1000)

        // Chunks go to a temporary folder; it becomes a session only when the whole file is read.
        val tmp = File(store.chunkRoot, "call.tmp").apply { deleteRecursively(); mkdirs() }
        val vad = Speech.vad(ctx, settings.vadThreshold)
        try {
            val writer = Speech.ChunkWriter(tmp)
            val col = Speech.Collector(vad, writer, start)
            val block = FloatArray(Speech.BLOCK)
            var fill = 0
            val resampler = Resampler { s ->
                block[fill++] = s / 32768f
                if (fill == block.size) {
                    col.feed(block.copyOf())
                    fill = 0
                    if (writer.speech >= Speech.RATE * 600L) { col.cut(); writer.close() }   // 10-minute chunks
                }
            }
            decode(ex, fmt, resampler) { posUs ->
                val pct = if (durationUs > 0) (posUs * 100 / durationUs).coerceIn(0, 99) else 0
                state = "Reading call ${d.name}: $pct%"
            }
            if (fill > 0) col.feed(block.copyOf(fill))
            col.flush()
            writer.close()
        } finally {
            vad.release()
        }
        if (Speech.ChunkWriter.ready(tmp).isEmpty()) { tmp.deleteRecursively(); return }
        val id = store.newSessionId(start)
        val dir = File(store.chunkRoot, id.toString())
        if (!tmp.renameTo(dir)) { tmp.deleteRecursively(); throw RuntimeException("could not save chunks") }
        store.markStopped(id, start + durationUs / 1000, d.name)
    }

    /** Decodes the selected track and pushes mono samples into [out]. */
    private fun decode(ex: MediaExtractor, fmt: MediaFormat, out: Resampler, progress: (Long) -> Unit) {
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var channels = fmt.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        var rate = fmt.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 16_000
        var float = false
        out.setRate(rate)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        rate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        float = f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        out.setRate(rate)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.limit(info.offset + info.size)
                        buf.position(info.offset)
                        if (float) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                out.push(sum / channels)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var sum = 0
                                repeat(channels) { sum += sb.get() }
                                out.push(sum / channels / 32768f)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        progress(info.presentationTimeUs)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String) = if (containsKey(key)) getInteger(key) else null

    /** Start time from a date and time in the file name (most call recorders write one). */
    private val NAME_PATTERNS = listOf(
        Regex("""(20\d\d)[-_.]?(\d\d)[-_.]?(\d\d)[ _T-]?(\d\d)[-_.:h]?(\d\d)[-_.:m]?(\d\d)""") to "yyyyMMddHHmmss",
        Regex("""(?<!\d)(\d\d)(\d\d)(\d\d)[ _-](\d\d)(\d\d)(\d\d)(?!\d)""") to "yyMMddHHmmss",
    )

    internal fun fromName(name: String): Long? {
        for ((re, pattern) in NAME_PATTERNS) {
            val m = re.find(name) ?: continue
            val digits = m.groupValues.drop(1).joinToString("")
            val t = try {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(digits)?.time
            } catch (_: Exception) { null } ?: continue
            // Ignore nonsense such as a phone number that happens to parse as a date.
            if (t in 946_684_800_000L..System.currentTimeMillis() + 86_400_000L) return t
        }
        return null
    }

    /** Streaming mono resampler to 16 kHz: box-average when reducing the rate, linear when raising it. */
    private class Resampler(private val emit: (Short) -> Unit) {
        private var ratio = 1.0   // input samples per output sample
        private var inIndex = 0L
        private var outIndex = 0L
        private var sum = 0.0
        private var count = 0
        private var prev = 0f

        fun setRate(rate: Int) {
            val r = rate.toDouble() / Speech.RATE
            if (r != ratio) { ratio = r; inIndex = 0; outIndex = 0; sum = 0.0; count = 0 }
        }

        fun push(x: Float) {
            if (ratio >= 1.0) {
                sum += x; count++
                inIndex++
                if (inIndex >= ((outIndex + 1) * ratio).toLong()) {
                    out(sum / count); sum = 0.0; count = 0; outIndex++
                }
            } else {
                // Outputs at input positions t in (inIndex-1, inIndex].
                while (outIndex * ratio <= inIndex) {
                    val t = outIndex * ratio - (inIndex - 1)
                    out(prev + (x - prev) * t)
                    outIndex++
                }
                prev = x
                inIndex++
            }
        }

        private fun out(v: Double) = emit((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
    }
}
