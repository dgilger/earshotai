package app.earshot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    companion object {
        private const val REQ_OUT_FOLDER = 1
        private const val REQ_CALL_FOLDER = 2
    }

    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())
    private val refreshers = mutableListOf<() -> Unit>()

    private val tick = object : Runnable {
        override fun run() {
            refreshers.forEach { it() }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.header("Settings", "Earshot setup", back = { finish() })

        serverCard(root)

        // ---- Transcription ----
        root.card {
            label("Transcription on this phone")
            text(14f) { text = "Parakeet 0.6B (NVIDIA), English" }
            text(12f, color = R.color.muted) {
                text = "Used when no server is connected."
                visibility = if (Server.ready(s)) View.VISIBLE else View.GONE
            }
            val st = text(12f, color = R.color.accent)
            refreshers += {
                st.text = when {
                    Models.state.isNotEmpty() -> Models.state
                    Models.lastError != null && !Models.busy -> "Download failed: ${Models.lastError}"
                    Models.needed(s).all { Models.has(this@SettingsActivity, it) } -> "READY · models downloaded"
                    else -> "Downloading soon…"
                }
            }
            space(6)
            text(12f, color = R.color.muted) {
                text = "Speech is turned into text on the phone after each pause in talk (a minute of quiet) " +
                    "and when you stop. The audio is deleted once the text is saved."
            }
            space(10)
            addView(Switch(context).apply {
                text = "Label speakers (Speaker 1, Speaker 2…)"
                textSize = 14f
                typeface = MONO
                setTextColor(col(R.color.text))
                isChecked = s.diarize
                setOnCheckedChangeListener { _, on ->
                    s.diarize = on
                    Models.ensure(this@SettingsActivity, Models.needed(s))
                }
            })
            addView(Switch(context).apply {
                text = "Leave out far-away background voices (TV across the room)"
                textSize = 14f
                typeface = MONO
                setTextColor(col(R.color.text))
                isChecked = s.skipBackground
                setOnCheckedChangeListener { _, on -> s.skipBackground = on }
            })
        }

        driveCard(root)
        callsCard(root)

        // ---- Microphone + voice detector ----
        lateinit var vad: EditText
        lateinit var autoStart: Switch
        var micSource = s.micSource
        root.card {
            label("Recording")
            autoStart = Switch(context).apply {
                text = "Start recording when Earshot opens"
                textSize = 15f
                typeface = MONO
                setTextColor(col(R.color.text))
                isChecked = s.autoStart
                setOnCheckedChangeListener { _, on -> s.autoStart = on }   // applies right away, no Save needed
            }
            addView(autoStart)
            text(13f, color = R.color.muted) { text = "Off by default. Turn on to record as soon as you open Earshot, for example with a gesture." }
            space(10)
            text(13f, color = R.color.muted) { text = "Microphone" }
            val micButtons = mutableListOf<RadioButton>()
            listOf(
                "mic" to "Standard (auto volume; best for most phones)",
                "voice" to "Speech recognition (no auto volume)",
                "raw" to "Unprocessed (if the phone supports it)",
            ).forEach { (k, labelText) ->
                val rb = RadioButton(context).apply {
                    text = labelText
                    textSize = 15f
                    typeface = MONO
                    setTextColor(col(R.color.text))
                    isChecked = k == micSource
                    setOnClickListener {
                        micSource = k
                        micButtons.forEach { b -> b.isChecked = b === this }
                    }
                }
                micButtons += rb
                addView(rb)
            }
            text(13f, color = R.color.muted) { text = "Takes effect the next time you record." }
            space(10)
            vad = field(this, "Sensitivity threshold 0.1–0.9 (higher ignores more background noise)", s.vadThreshold.toString())
            val st = text(13f, color = R.color.muted)
            refreshers += { st.text = if (Models.has(this@SettingsActivity, Models.VAD)) "Silero VAD downloaded" else "Silero VAD not downloaded yet (1 MB)" }
        }

        // ---- Save ----
        root.card {
            row {
                pill("Save", primary = true, weight = 1f) {
                    s.micSource = micSource
                    s.vadThreshold = vad.text.toString().toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f
                    Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                    if (ListenService.running) Toast.makeText(this@SettingsActivity,
                        "Microphone and voice detector changes apply to the next recording.", Toast.LENGTH_LONG).show()
                }
            }
        }

        showScreen(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ---- Your own server ----

    private lateinit var serverStatus: TextView
    private lateinit var serverUrlIn: EditText
    private lateinit var serverTokenIn: EditText
    private lateinit var serverOff: Button

    private fun serverCard(root: LinearLayout) {
        root.card(R.color.accent) {
            label("Server")
            serverStatus = text(14f)
            space(4)
            text(13f, color = R.color.muted) {
                text = "Your own Earshot server makes better transcripts (Whisper large-v3, speaker labels) and " +
                    "keeps them where AI tools can read them. If the server can't be reached, recordings wait on " +
                    "this phone and are sent later.\n\nFastest way: open the server's web page, go to Connect, and " +
                    "scan the code with the phone camera. Or type the address and token here."
            }
            serverUrlIn = field(this, "Address (for example https://earshot.example.com)", s.serverUrl)
            serverTokenIn = field(this, "Access token", s.serverToken, secret = true)
            space(10)
            row {
                pill("Connect", primary = true) { connect(serverUrlIn.text.toString(), serverTokenIn.text.toString()) }
                serverOff = pill("Disconnect", color = R.color.live) {
                    s.serverUrl = ""; s.serverToken = ""; s.serverInfo = ""
                    serverUrlIn.setText(""); serverTokenIn.setText("")
                    Models.ensure(this@SettingsActivity, Models.needed(s))   // the phone transcribes again
                    showServer()
                }
            }
        }
        showServer()
    }

    private fun showServer() {
        val on = Server.ready(s)
        serverStatus.text = if (on) "Connected: ${s.serverUrl}${if (s.serverInfo.isNotEmpty()) "\n" + s.serverInfo else ""}"
            else "Not connected. Transcription runs on this phone."
        serverStatus.setTextColor(col(if (on) R.color.accent else R.color.text))
        serverOff.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun connect(rawUrl: String, rawToken: String) {
        val url = Server.normalize(rawUrl)
        val token = rawToken.trim()
        if (url.isEmpty() || token.isEmpty()) { toast("Type the address and the token."); return }
        serverStatus.text = "Checking…"
        Thread {
            val result = try { Result.success(Server.check(url, token)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                result.onSuccess { info ->
                    s.serverUrl = url; s.serverToken = token; s.serverInfo = info
                    serverUrlIn.setText(url)
                    toast("Connected to your server")
                }.onFailure { toast(it.message ?: "Could not connect") }
                showServer()
            }
        }.start()
    }

    // ---- Transcript folder ----

    private lateinit var outStatus: TextView

    private fun driveCard(root: LinearLayout) {
        root.card(R.color.accent) {
            label("Transcript folder")
            outStatus = text(14f)
            text(13f, color = R.color.muted) {
                text = "Optional when a server is connected. Each finished transcript is saved here as a .txt file. Make a new folder in the picker if you " +
                    "want, e.g. Documents/Earshot.\n\nGoogle Drive: if Drive shows in the picker, pick a Drive folder " +
                    "directly. On most phones it doesn't; then install \"Autosync for Google Drive\" (MetaCtrl) from " +
                    "the Play Store and sync this folder to a Drive folder. No Google Cloud setup needed."
            }
            space(10)
            row { pill("Choose folder", primary = true) { pickOutFolder() } }
        }
        showOut()
    }

    private fun showOut() {
        outStatus.text = if (s.outReady) "Saving to: ${s.outFolderName}" else "No folder chosen yet."
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun pickOutFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OUT_FOLDER)
        } catch (_: Exception) {
            toast("No folder picker found on this phone.")
        }
    }

    /** Display name of a folder picked with the system picker. */
    private fun treeName(tree: android.net.Uri): String = try {
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
            android.provider.DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: tree.lastPathSegment ?: "folder"

    // ---- Call recordings ----

    private lateinit var callStatus: TextView
    private lateinit var callOff: Button

    private fun callsCard(root: LinearLayout) {
        root.card(R.color.yellow) {
            label("Phone calls", R.color.yellow)
            text(13f, color = R.color.muted) {
                text = "Android doesn't let apps hear calls. If your phone's call recorder saves calls as files, " +
                    "choose that folder: each new call is transcribed (checked every 15 minutes and whenever Earshot " +
                    "is open) and saved like a recording. Only calls recorded after you choose the folder are used."
            }
            space(6)
            callStatus = text(14f)
            space(10)
            row {
                pill("Choose folder", color = R.color.yellow) { pickCallFolder() }
                callOff = pill("Off", color = R.color.live) { CallRecordings.clear(this@SettingsActivity); showCalls() }
            }
        }
        showCalls()
    }

    private fun showCalls() {
        val on = s.callFolder.isNotEmpty()
        callStatus.text = if (on) "Watching: ${s.callFolderName}" else "Off"
        callOff.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun pickCallFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_CALL_FOLDER)
        } catch (_: Exception) {
            toast("No folder picker found on this phone.")
        }
    }

    @Deprecated("Activity result API needs androidx; this app uses the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OUT_FOLDER -> {
                val tree = data?.data ?: return
                if (resultCode != RESULT_OK) return
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(tree, flags)
                    if (s.outFolder.isNotEmpty() && s.outFolder != tree.toString()) try {
                        contentResolver.releasePersistableUriPermission(android.net.Uri.parse(s.outFolder), flags)
                    } catch (_: Exception) {
                    }
                    s.outFolder = tree.toString()
                    s.outFolderName = treeName(tree)
                    s.uploadError = ""
                    Thread { Uploader.run(this) }.start()
                } catch (e: Exception) {
                    toast("Can't use that folder: ${e.message}")
                }
                showOut()
            }
            REQ_CALL_FOLDER -> {
                val tree = data?.data ?: return
                if (resultCode != RESULT_OK) return
                val name = treeName(tree)
                try {
                    CallRecordings.choose(this, tree, name)
                } catch (e: Exception) {
                    toast("Can't use that folder: ${e.message}")
                }
                showCalls()
            }
        }
    }

    private fun LinearLayout.column(init: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; init() }
            .also { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

    private fun field(parent: LinearLayout, hint: String, value: String, secret: Boolean = false, number: Boolean = false): EditText {
        parent.text(13f, color = R.color.muted) { text = hint; setPadding(0, dp(8), 0, 0) }
        return EditText(this).apply {
            setText(value)
            isSingleLine = true
            setTextColor(col(R.color.text))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }.also { parent.addView(it) }
    }
}
