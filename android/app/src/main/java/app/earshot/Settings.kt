package app.earshot

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in SharedPreferences. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("earshot", Context.MODE_PRIVATE)

    /** Speech-to-text model id (see [Models.ASR]). */
    var asrModel: String
        get() = p.getString("asr_model", Models.DEFAULT_ASR)!!
        set(v) = p.edit().putString("asr_model", v).apply()

    /** Label who is speaking ("Speaker 1: ..."). */
    var diarize: Boolean
        get() = p.getBoolean("diarize", true)
        set(v) = p.edit().putBoolean("diarize", v).apply()

    /** Leave out voices much quieter than the conversation (TV, people nearby). */
    var skipBackground: Boolean
        get() = p.getBoolean("skip_background", true)
        set(v) = p.edit().putBoolean("skip_background", v).apply()

    /** Start recording as soon as the app is opened (from the launcher or a gesture). */
    var autoStart: Boolean
        get() = p.getBoolean("auto_start", false)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    /** Silero speech probability needed to start a segment. */
    var vadThreshold: Float
        get() = p.getFloat("vad_threshold", 0.5f)
        set(v) = p.edit().putFloat("vad_threshold", v).apply()

    /** Android audio source: "mic" (normal recording, with auto gain), "voice" (speech
     *  recognition tuning, no gain control) or "raw" (unprocessed, where the phone supports it). */
    var micSource: String
        get() = p.getString("mic_source", "mic")!!
        set(v) = p.edit().putString("mic_source", v).apply()

    /** Start time (epoch ms) of the recording in progress, 0 when not recording. */
    var sessionId: Long
        get() = p.getLong("session_id", 0L)
        set(v) = p.edit().putLong("session_id", v).commit().let { }

    /** Folder (SAF tree URI) that finished transcripts are saved to ("" = not chosen yet). */
    var outFolder: String
        get() = p.getString("out_folder", "")!!
        set(v) = p.edit().putString("out_folder", v).apply()

    var outFolderName: String
        get() = p.getString("out_folder_name", "")!!
        set(v) = p.edit().putString("out_folder_name", v).apply()

    /** Last problem saving to the folder, shown on the main screen ("" = none). */
    var uploadError: String
        get() = p.getString("upload_error", "")!!
        set(v) = p.edit().putString("upload_error", v).apply()

    /** Folder (SAF tree URI) where the phone's call recorder saves files ("" = off). */
    var callFolder: String
        get() = p.getString("call_folder", "")!!
        set(v) = p.edit().putString("call_folder", v).apply()

    var callFolderName: String
        get() = p.getString("call_folder_name", "")!!
        set(v) = p.edit().putString("call_folder_name", v).apply()

    /** Only call recordings changed after this (epoch ms) are transcribed. */
    var callsSince: Long
        get() = p.getLong("calls_since", 0L)
        set(v) = p.edit().putLong("calls_since", v).apply()

    /** Call recordings already transcribed (document ids). */
    var callsSeen: Set<String>
        get() = p.getStringSet("calls_seen", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("calls_seen", v).commit().let { }

    val outReady get() = outFolder.isNotEmpty()
}
