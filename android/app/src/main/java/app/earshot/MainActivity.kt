package app.earshot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * One screen: a big Record / Stop button, what is happening, and the latest transcripts.
 * With the "Start recording when Earshot opens" setting on (off by default), opening the app
 * (launcher icon or a gesture) starts recording right away, unless a phone call is going on.
 */
class MainActivity : Activity() {
    companion object {
        /** Set on intents (e.g. the notification) that open the app without starting a recording. */
        const val EXTRA_NO_AUTOSTART = "no_autostart"
        private const val REQ_PERMS = 1
    }

    private lateinit var settings: Settings
    private lateinit var store: Store
    private lateinit var dial: RecordDial
    private lateinit var stateView: TextView
    private lateinit var scope: Scope
    private lateinit var detailView: TextView
    private lateinit var callCard: LinearLayout
    private lateinit var connectCard: LinearLayout
    private var modeAtCreate = ""
    private lateinit var workView: TextView
    private lateinit var liveView: TextView
    private lateinit var listBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var startAfterDownload = false
    /** True when the app was just opened by the user, so recording should start. */
    private var armed = false
    private var lastList = ""
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = Store(this)
        ListenService.channels(this)
        Models.removeOld(this)
        Uploader.scheduleCalls(this)
        Models.ensure(this, Models.needed(settings))   // first launch: fetch the speech models right away
        armed = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_NO_AUTOSTART, false) != true &&
            intent?.data?.scheme != "earshot"
        if (savedInstanceState == null) handlePairLink(intent)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeAtCreate = mode()
        root.header("Earshot", when (modeAtCreate) {
            "server" -> "Sends to your server · ${Server.normalize(settings.serverUrl).removePrefix("https://").removePrefix("http://")}"
            "phone" -> "Transcribes on this phone"
            else -> "No server connected yet"
        }, action = "Settings") {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }

        dial = RecordDial(this).apply { setOnClickListener { onBigButton() } }
        root.addView(dial, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.space(6)
        stateView = root.pixel(20f) { gravity = Gravity.CENTER }
        root.space(8)
        detailView = root.text(11f, color = R.color.muted) { gravity = Gravity.CENTER; letterSpacing = 0.06f }
        root.space(12)
        scope = Scope(this)
        root.addView(scope, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(24); marginEnd = dp(24); bottomMargin = dp(20) })

        // Server first: until one is connected (or on-phone transcription is chosen), say how.
        connectCard = root.card(R.color.accent) {
            label("Connect your server")
            text(14f) {
                text = "Recordings are kept on this phone until a server is connected, then sent there."
            }
            space(6)
            text(13f, color = R.color.muted) {
                text = "On the server's web page, open Connect and scan the code with the phone camera. " +
                    "Or run: sudo earshot pair"
            }
            space(10)
            row {
                pill("Enter by hand", primary = true) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                pill("Use this phone instead") {
                    settings.onDevice = true
                    Models.ensure(this@MainActivity, Models.needed(settings))
                    recreate()
                }
            }
        }
        connectCard.visibility = if (settings.waitingForServer) View.VISIBLE else View.GONE

        callCard = root.card(R.color.yellow) {
            label("In a call", R.color.yellow)
            text(14f) {
                text = "Android doesn't let apps hear phone calls, so Earshot didn't start recording. " +
                    "If your phone's call recorder saves calls, choose its folder in Settings: Earshot then " +
                    "transcribes each call file after the call."
            }
        }

        root.card(R.color.magenta) {
            label("Transcripts", R.color.magenta)
            workView = text(12f, color = R.color.muted)
            liveView = text(13f, color = R.color.muted)
            space(6)
            listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(listBox)
        }

        showScreen(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NO_AUTOSTART, false)) armed = false
        if (intent.data?.scheme == "earshot") armed = false
        handlePairLink(intent)
    }

    /**
     * earshot://pair?url=…&token=… comes from the "Connect Earshot" button on the server's pairing
     * page (opened by scanning the QR code on the server's Connect page). Asks first, then checks.
     */
    private fun handlePairLink(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "earshot" || data.host != "pair") return
        i.data = null   // handle once
        val url = Server.normalize(data.getQueryParameter("url") ?: "")
        val token = data.getQueryParameter("token")?.trim() ?: ""
        if (url.isEmpty() || token.isEmpty()) { Toast.makeText(this, "That link is not complete. Scan the code again.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Connect to this server?")
            .setMessage("$url\n\nRecordings will be sent there to be transcribed. Only connect to your own server.")
            .setPositiveButton("Connect") { _, _ ->
                Thread {
                    val msg = try {
                        val info = Server.check(url, token)
                        settings.serverUrl = url; settings.serverToken = token; settings.serverInfo = info
                        "Connected to your server ($info)"
                    } catch (e: Exception) {
                        "Could not connect: ${e.message}"
                    }
                    ui.post {
                        AlertDialog.Builder(this).setMessage(msg)
                            .setPositiveButton("OK") { _, _ -> if (mode() != modeAtCreate) recreate() }.show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The user went to another app or home: the next time Earshot comes back, record again. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        armed = true
    }

    override fun onStart() {
        super.onStart()
        if (armed) {
            armed = false
            if (settings.autoStart && !ListenService.recording && !inCall()) startRecording()
        }
    }

    /** "server", "phone" or "none": what transcribes recordings. */
    private fun mode() = when {
        Server.ready(settings) -> "server"
        settings.onDevice -> "phone"
        else -> "none"
    }

    override fun onResume() {
        super.onResume()
        if (mode() != modeAtCreate) { recreate(); return }   // connected or changed in Settings: new header
        if (Server.ready(settings) && !ListenService.running && store.sessions().isNotEmpty())   // send what waited for a server
            Thread({ Processor.drain(applicationContext, { ListenService.sessionId }) }, "drain").start()
        lastList = ""
        if (store.waiting().isNotEmpty()) Thread { Uploader.run(this) }.start()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun inCall(): Boolean {
        val m = getSystemService(AudioManager::class.java).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun onBigButton() {
        if (ListenService.recording) ListenService.stop(this) else startRecording()
    }

    private fun refresh() {
        val rec = ListenService.recording
        val level = ((ListenService.levelDb + 60f) / 60f).coerceIn(0f, 1f)
        dial.recording = rec
        dial.level = if (rec) level else 0f
        dial.speaking = rec && ListenService.speaking
        dial.contentDescription = if (rec) "Stop recording" else "Record"
        scope.visibility = if (rec) View.VISIBLE else View.GONE
        if (rec) scope.push(level)
        if (rec) {
            val sec = ((System.currentTimeMillis() - ListenService.sessionId) / 1000).coerceAtLeast(0)
            stateView.text = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            stateView.setTextColor(col(R.color.live))
        } else {
            stateView.text = if (ListenService.running) "Saving" else "Ready"
            stateView.setTextColor(col(if (ListenService.running) R.color.yellow else R.color.text))
        }
        // The rest reads files: four times a second is plenty.
        if (ticks++ % 3 != 0) return
        callCard.visibility = if (inCall() && !rec) View.VISIBLE else View.GONE

        detailView.text = buildString {
            val dl = Models.state
            when {
                dl.isNotEmpty() -> append(dl)
                Models.lastError != null && !Models.busy -> append("Download failed: ${Models.lastError}")
                rec && ListenService.speaking -> append("Hearing speech")
                rec -> append("Listening · silence is skipped")
                ListenService.running -> append("Finishing the transcript…")
                else -> append("Tap to record")
            }
            ListenService.status.takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
        }
        if (startAfterDownload && !Models.busy) {
            startAfterDownload = false
            if (Models.ready(this, settings)) ListenService.record(this)
        }

        workView.text = buildString {
            // Only a problem is shown here; the folder itself lives in Settings.
            settings.uploadError.takeIf { it.isNotEmpty() && store.waiting().isNotEmpty() }?.let { append(it) }
            Processor.stage.takeIf { it.isNotEmpty() }?.let { if (isNotEmpty()) append("\n"); append(it) }
            CallRecordings.state.takeIf { it.isNotEmpty() }?.let { if (isNotEmpty()) append("\n"); append(it) }
            Processor.lastError?.let { if (isNotEmpty()) append("\n"); append(if (Server.ready(settings)) it else "Transcription error: $it") }
        }
        workView.visibility = if (workView.text.isEmpty()) View.GONE else View.VISIBLE

        // Text of the recording in progress, newest last.
        liveView.text = if (rec) {
            val lines = store.partText(ListenService.sessionId).trim().lines().filter { it.isNotBlank() }.takeLast(6)
            val pending = ListenService.chunkSpeechSec
            buildString {
                if (lines.isNotEmpty()) append(lines.joinToString("\n")).append("\n")
                if (pending > 0) append("${pending}s of speech is transcribed after a pause in talk.")
                else if (lines.isEmpty()) append("Text shows here after each pause in talk.")
            }
        } else ""
        liveView.visibility = if (rec) View.VISIBLE else View.GONE

        val items = store.recent(15)
        val key = items.joinToString { it.file.name + it.uploaded }
        if (key != lastList) {
            lastList = key
            listBox.removeAllViews()
            if (items.isEmpty()) listBox.text(13f, color = R.color.muted) { text = "No transcripts yet." }
            items.forEachIndexed { i, item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { show(item.file) }
                }
                row.addView(TextView(this).apply {
                    text = "%02d".format(i + 1)
                    typeface = Type.mono
                    textSize = 9f
                    setTextColor(col(R.color.muted))
                }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(this).apply {
                    text = store.title(item.file).removePrefix("Earshot ")
                    typeface = MONO
                    textSize = 13f
                    setTextColor(col(R.color.text))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(chip(if (item.uploaded) "Saved" else "Waiting", if (item.uploaded) R.color.accent else R.color.yellow))
                listBox.addView(row)
                listBox.dots()
            }
        }
    }

    private fun show(f: File) {
        val tv = TextView(this).apply {
            text = if (f.exists()) f.readText() else "(moved; reopen the list)"
            typeface = MONO
            textSize = 13f
            setTextColor(col(R.color.text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle(store.title(f)).setView(ScrollView(this).apply { addView(tv) })
            .setNegativeButton("Share") { _, _ -> share(f) }
            .setPositiveButton("Close", null).show()
    }

    /** Send one transcript to any app (Drive, Claude, email…) with Android's share menu. */
    private fun share(f: File) {
        if (!f.exists()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, store.title(f))
            .putExtra(Intent.EXTRA_TEXT, f.readText())
        startActivity(Intent.createChooser(i, "Share transcript").addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    }

    private fun startRecording() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        askBatteryExemption()
        if (!Models.ready(this, settings)) {
            // Normally downloaded from Settings; fetch here too so REC always works.
            Models.ensure(this, Models.needed(settings))
            startAfterDownload = true
            return
        }
        ListenService.record(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            AlertDialog.Builder(this).setMessage("Earshot needs the microphone permission to record.")
                .setPositiveButton("OK", null).show()
        }
    }

    /** Phone makers kill background apps aggressively; an exemption keeps recording alive. */
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        } catch (_: Exception) {
        }
    }
}
