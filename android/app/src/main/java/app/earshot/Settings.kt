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

    /** Leave out voices far quieter than the conversation (a TV across the room). Off by default:
     *  it also dropped people a few steps away. New key, so the old default (on) is not kept. */
    var skipBackground: Boolean
        get() = p.getBoolean("skip_background_v2", false)
        set(v) = p.edit().putBoolean("skip_background_v2", v).apply()

    /** Start recording as soon as the app is opened (from the launcher or a gesture). */
    var autoStart: Boolean
        get() = p.getBoolean("auto_start", false)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    /** Silero speech probability needed to start a segment. Lower hears quieter, farther voices.
     *  New key (was 0.5 under "vad_threshold"), so saved old values do not stay. */
    var vadThreshold: Float
        get() = p.getFloat("vad_threshold_v2", 0.35f)
        set(v) = p.edit().putFloat("vad_threshold_v2", v).apply()

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

    /** Transcribe on this phone (Parakeet) when no server is connected. Off: recordings wait on
     *  the phone until a server is connected. The server is the main way; this is the fallback. */
    var onDevice: Boolean
        get() = p.getBoolean("on_device", false)
        set(v) = p.edit().putBoolean("on_device", v).apply()

    /** No server and no on-phone transcription chosen: recordings wait for a server. */
    val waitingForServer get() = !Server.ready(this) && !onDevice

    /** Your own Earshot server (https://…), "" = none. */
    var serverUrl: String
        get() = p.getString("server_url", "")!!
        set(v) = p.edit().putString("server_url", v).apply()

    var serverToken: String
        get() = p.getString("server_token", "")!!
        set(v) = p.edit().putString("server_token", v).apply()

    /** Short description of the server from the last check ("large-v3 on GPU"). */
    var serverInfo: String
        get() = p.getString("server_info", "")!!
        set(v) = p.edit().putString("server_info", v).apply()

    /** "<session>\t<end ms>\t<label>" for recordings the server hasn't been told are over yet. */
    var serverFinishPending: Set<String>
        get() = p.getStringSet("server_finish", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("server_finish", v).commit().let { }

    val outReady get() = outFolder.isNotEmpty()
}
