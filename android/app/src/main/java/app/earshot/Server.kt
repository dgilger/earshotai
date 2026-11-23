package app.earshot

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Your own Earshot server (server/ in the repo): the phone sends each finished chunk of speech
 * there and gets the transcript back. The audio stays on the phone until its text is back, so
 * nothing is lost when the server can't be reached: the chunk is simply sent again later.
 */
object Server {
    /** The server can't be reached right now: keep the audio, try again later. */
    class Unreachable(msg: String) : IOException(msg)

    fun ready(s: Settings) = s.serverUrl.isNotEmpty() && s.serverToken.isNotEmpty()

    private val device: String get() = (Build.MODEL ?: "phone").replace(Regex("[^A-Za-z0-9_.-]"), "").ifEmpty { "phone" }

    /** Cleans up what the user typed: adds https:// if missing, drops a trailing slash. */
    fun normalize(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    private fun open(base: String, token: String, path: String, method: String = "GET"): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.body(): String =
        (if (responseCode < 400) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun failure(c: HttpURLConnection): Exception {
        val code = c.responseCode
        val detail = try { JSONObject(c.body()).optString("detail") } catch (_: Exception) { "" }
        return when (code) {
            401 -> RuntimeException("The server did not accept the token. Connect again in Settings → Server.")
            in 500..599 -> Unreachable("Server error $code; the audio is kept and sent again later.")
            else -> RuntimeException("Server answered $code${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
    }

    /** Checks address and token. Returns a short description ("large-v3 on GPU") or throws with a plain message. */
    fun check(url: String, token: String): String {
        val c = try {
            open(url, token, "/api/status").also { it.responseCode }
        } catch (e: IOException) {
            throw RuntimeException("Can't reach $url (${e.message ?: e.javaClass.simpleName}). Check the address and the phone's internet.")
        }
        if (c.responseCode != 200) throw failure(c)
        val j = JSONObject(c.body())
        val where = if (j.optString("device") == "cuda") "GPU" else "CPU"
        return "${j.optString("model")} on $where"
    }

    /**
     * Sends one chunk (unless it was sent already) and waits for its transcript. Returns null when
     * [keepGoing] turns false while waiting (the server keeps working; the next call picks it up).
     */
    fun transcribe(s: Settings, session: Long, idx: File, call: Boolean, keepGoing: () -> Boolean,
                   progress: (String) -> Unit): List<Speech.Out>? {
        val base = s.serverUrl
        val token = s.serverToken
        val pcm = File(idx.path.removeSuffix(".idx") + ".pcm")
        val jobFile = File(idx.path.removeSuffix(".idx") + ".job")
        var job = jobFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        while (keepGoing()) {
            try {
                if (job == null) {
                    progress("Sending ${pcm.length() / 32_000} s of speech to the server…")
                    job = upload(base, token, session, idx, pcm, call)
                    jobFile.writeText(job.toString())
                }
                val c = open(base, token, "/api/jobs/$job")
                when (c.responseCode) {
                    200 -> {}
                    404 -> { jobFile.delete(); job = null; continue }   // the server lost it: send it again
                    else -> throw failure(c)
                }
                val j = JSONObject(c.body())
                when (j.optString("state")) {
                    "done" -> {
                        val arr = j.getJSONArray("lines")
                        return (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            Speech.Out(o.getLong("t"), o.optInt("speaker"), o.getString("text"))
                        }
                    }
                    "failed" -> {
                        jobFile.delete()   // sending it again makes the server try again
                        throw RuntimeException("The server could not transcribe it: ${j.optString("error")}. It tries again later.")
                    }
                    else -> {
                        val q = j.optInt("queue")
                        progress(if (q > 1) "Server is transcribing ($q pieces waiting)…" else "Server is transcribing…")
                    }
                }
            } catch (e: IOException) {
                throw if (e is Unreachable) e else
                    Unreachable("Server not reachable (${e.message ?: e.javaClass.simpleName}). The audio is kept on the phone and sent later.")
            }
            for (i in 0 until 6) { if (!keepGoing()) return null; Thread.sleep(500) }
        }
        return null
    }

    private fun upload(base: String, token: String, session: Long, idx: File, pcm: File, call: Boolean): Long {
        val boundary = "earshot-" + UUID.randomUUID()
        val fields = linkedMapOf(
            "recording" to session.toString(),
            "start_ms" to idx.nameWithoutExtension,
            "index" to idx.readText(),
            "device" to device,
            "source" to if (call) "call" else "phone",
        )
        val head = StringBuilder()
        for ((k, v) in fields) {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                .append(v).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.pcm\"\r\n")
            .append("Content-Type: application/octet-stream\r\n\r\n")
        val headBytes = head.toString().toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open(base, token, "/api/chunks", "POST").apply {
            doOutput = true
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(headBytes.size + pcm.length() + tail.size)
        }
        DataOutputStream(c.outputStream.buffered(1 shl 16)).use { out ->
            out.write(headBytes)
            pcm.inputStream().use { it.copyTo(out, 1 shl 16) }
            out.write(tail)
        }
        if (c.responseCode != 200) throw failure(c)
        return JSONObject(c.body()).getLong("job")
    }

    /** Tells the server a recording is over (sets its end time and title). Kept and retried if offline. */
    fun finish(ctx: Context, session: Long, endMs: Long, label: String) {
        val s = Settings(ctx)
        if (!ready(s)) return
        s.serverFinishPending = s.serverFinishPending + "$session\t$endMs\t${label.replace('\t', ' ')}"
        sendFinishes(ctx)
    }

    fun sendFinishes(ctx: Context) {
        val s = Settings(ctx)
        if (!ready(s)) return
        for (item in s.serverFinishPending) {
            val p = item.split('\t')
            if (p.size < 2) { s.serverFinishPending = s.serverFinishPending - item; continue }
            try {
                val c = open(s.serverUrl, s.serverToken, "/api/recordings/${p[0]}/finish", "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("end_ms", p[1].toLong()).put("label", p.getOrElse(2) { "" }).put("device", device)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode in 200..499) s.serverFinishPending = s.serverFinishPending - item
            } catch (_: Exception) {
                return   // offline: try again next time
            }
        }
    }
}
