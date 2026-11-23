package app.earshot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files, all in the app's private storage. One recording (Record to Stop) is one session, named by
 * its start time in epoch ms:
 *   files/chunks/<session>/<ms>.pcm|.idx   speech waiting to be transcribed (deleted once it is)
 *   files/chunks/<session>/stopped         written when recording stops
 *   files/transcripts/<session>.part       transcript lines while the session is still going
 *   files/transcripts/<session>.txt        finished transcript, not yet saved to the chosen folder
 *   files/transcripts/uploaded/<session>.txt   saved to the chosen folder (copy kept here)
 */
class Store(private val ctx: Context) {
    companion object {
        private const val STOPPED = "stopped"
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    val transcriptDir get() = File(ctx.filesDir, "transcripts").apply { mkdirs() }
    val uploadedDir get() = File(transcriptDir, "uploaded").apply { mkdirs() }
    val chunkRoot get() = File(ctx.filesDir, "chunks").apply { mkdirs() }

    fun sessionDir(id: Long) = File(chunkRoot, id.toString()).apply { mkdirs() }

    /** Session folders, oldest first. */
    fun sessions(): List<Long> =
        chunkRoot.listFiles { f -> f.isDirectory }?.mapNotNull { it.name.toLongOrNull() }?.sorted().orEmpty()

    /** Recording over: [endMs] for the header, [label] names a call recording ("" for live). */
    fun markStopped(id: Long, endMs: Long, label: String = "") {
        File(sessionDir(id), STOPPED).writeText("$endMs\t${label.replace('\t', ' ').replace('\n', ' ')}")
    }

    /** (end ms, label) once the session is stopped, else null. */
    fun stopped(id: Long): Pair<Long, String>? {
        val f = File(File(chunkRoot, id.toString()), STOPPED)
        if (!f.exists()) return null
        val t = f.readText()
        return (t.substringBefore('\t').toLongOrNull() ?: f.lastModified()) to t.substringAfter('\t', "")
    }

    /** A free session id at or just after [ms]. */
    fun newSessionId(ms: Long): Long {
        var id = ms
        while (File(chunkRoot, id.toString()).exists() || File(transcriptDir, "$id.txt").exists() ||
            File(uploadedDir, "$id.txt").exists()) id++
        return id
    }

    /** Chunk files from older versions (directly in files/chunks) and old day transcripts move out of the way. */
    fun migrate() {
        val loose = chunkRoot.listFiles { f -> f.isFile }.orEmpty()
        if (loose.isNotEmpty()) {
            val first = loose.mapNotNull { it.name.substringBefore('.').toLongOrNull() }.minOrNull() ?: 1L
            val dir = sessionDir(first)
            loose.forEach { it.renameTo(File(dir, it.name)) }
            markStopped(first, System.currentTimeMillis())
        }
        File(ctx.filesDir, "pending").deleteRecursively()
        val old = transcriptDir.listFiles { f -> f.name.endsWith(".tsv") }.orEmpty()
        if (old.isNotEmpty()) {
            val keep = File(transcriptDir, "old").apply { mkdirs() }
            old.forEach { it.renameTo(File(keep, it.name)) }
        }
    }

    private fun part(id: Long) = File(transcriptDir, "$id.part")

    /** Adds one chunk's lines to the session transcript. A blank line separates chunks. */
    @Synchronized
    fun append(id: Long, lines: List<Pair<Long, String>>) {
        val clean = lines.map { (t, s) -> t to s.replace('\n', ' ').trim() }.filter { it.second.isNotEmpty() }
        if (clean.isEmpty()) return
        val f = part(id)
        val sb = StringBuilder()
        if (f.exists() && f.length() > 0) sb.append('\n')
        clean.forEach { (t, s) -> sb.append('[').append(clock.format(Date(t))).append("] ").append(s).append('\n') }
        f.appendText(sb.toString())
    }

    /** Lines so far in a session's transcript (for the screen). */
    fun partText(id: Long): String = part(id).let { if (it.exists()) it.readText() else "" }

    /**
     * Turns the session's lines into a finished transcript with a header. Returns false when
     * nothing was said (no file is made).
     */
    @Synchronized
    fun finish(id: Long, endMs: Long, label: String): Boolean {
        val p = part(id)
        val body = if (p.exists()) p.readText().trim() else ""
        p.delete()
        if (body.isEmpty()) return false
        val call = label.isNotEmpty()
        val header = buildString {
            append(if (call) "Earshot transcript of a phone call recording: $label\n" else "Earshot transcript\n")
            append("Recorded: ").append(stamp.format(Date(id))).append(" to ").append(stamp.format(Date(endMs)))
            append(" (").append(java.util.TimeZone.getDefault().id).append(")\n")
            append("Speaker numbers start again after each blank line.\n\n")
        }
        File(transcriptDir, "$id.txt").writeText(header + body + "\n")
        val name = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(id))
        File(transcriptDir, "$id.title").writeText(if (call) "Earshot call $name" else "Earshot $name")
        return true
    }

    /** Finished transcripts not yet uploaded, oldest first. */
    fun waiting(): List<File> =
        transcriptDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()

    private fun titleFile(f: File) = File(f.parentFile, f.nameWithoutExtension + ".title")

    fun markUploaded(f: File) {
        titleFile(f).renameTo(File(uploadedDir, titleFile(f).name))
        f.renameTo(File(uploadedDir, f.name))
    }

    /** File name in the folder (".txt" added), e.g. "Earshot 2026-11-23 16.52" or "Earshot call 2026-11-23 16.52". */
    fun title(f: File): String {
        titleFile(f).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = f.nameWithoutExtension.toLongOrNull() ?: return f.nameWithoutExtension
        return "Earshot " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(id))
    }

    data class Item(val file: File, val uploaded: Boolean)

    /** Newest transcripts, uploaded or not, for the main screen. */
    fun recent(n: Int): List<Item> =
        (waiting().map { Item(it, false) } + uploadedDir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().map { Item(it, true) })
            .sortedByDescending { it.file.name }.take(n)
}
