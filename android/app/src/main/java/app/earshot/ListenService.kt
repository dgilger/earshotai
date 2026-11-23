package app.earshot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Records while the user wants it (Record to Stop), as a microphone foreground service (Android
 * requires the visible notification). Keeps only speech and collects it into chunks that
 * [Processor] transcribes after each pause in talk. After Stop the service stays up, mic off,
 * until the last chunk is transcribed and the transcript is saved, then ends itself.
 */
class ListenService : Service() {
    companion object {
        const val ACTION_RECORD = "app.earshot.RECORD"
        const val ACTION_STOP = "app.earshot.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        /** Service is up (recording, or finishing transcripts after a stop). */
        @Volatile var running = false
            private set
        /** Start time of the recording in progress, 0 when not recording. */
        @Volatile var sessionId = 0L
            private set
        val recording get() = sessionId != 0L
        @Volatile var speaking = false
            private set
        /** Mic loudness in dBFS, for the level meter. */
        @Volatile var levelDb = -90f
            private set
        /** Seconds of speech in the chunk being recorded (not yet transcribed). */
        @Volatile var chunkSpeechSec = 0
            private set
        @Volatile var status = ""
            internal set

        fun record(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ListenService::class.java).setAction(ACTION_RECORD))
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }

        fun channels(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private lateinit var settings: Settings
    private lateinit var store: Store
    private var audioThread: Thread? = null
    @Volatile private var capture = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var processor: Processor? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = Store(this)
        channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECORD -> {
                if (!goForeground()) return START_NOT_STICKY
                startRecording()
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            ACTION_STOP -> {
                stopRecording()
                if (!running) stopSelf()
            }
            // Restarted by Android after the process was killed: the mic can't come back on its
            // own, so only finish what is on disk.
            else -> {
                if (!goForeground()) return START_NOT_STICKY
                startProcessor()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NO_AUTOSTART, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
        if (recording) {
            val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.setContentTitle("Earshot is recording")
                .setContentText("Speech is transcribed on this phone; audio is deleted after.")
                .setUsesChronometer(true)
                .setWhen(sessionId)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        } else {
            b.setContentTitle("Earshot is finishing the transcript")
                .setContentText("Microphone off. Saved to your folder when done.")
        }
        return b.build()
    }

    private fun showNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun goForeground(): Boolean = try {
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: Exception) {
        // Android 12+ refuses to start a mic service from the background.
        Log.w("earshot", "cannot start foreground service", e)
        stopSelf()
        false
    }

    private fun startProcessor() {
        if (processor != null) return
        running = true
        store.migrate()
        processor = Processor(this).also {
            it.onIdle = { main.post { if (!recording) finish() } }
            it.start()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startRecording() {
        if (recording) { showNotification(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission missing"
            return
        }
        if (!Models.ready(this, settings)) {
            status = "Models not downloaded"
            return
        }
        startProcessor()
        val id = store.newSessionId(System.currentTimeMillis())
        store.sessionDir(id)
        settings.sessionId = id
        sessionId = id
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earshot:record").apply { acquire() }
        capture = true
        audioThread = Thread({ captureLoop(id) }, "mic").apply { priority = Thread.MAX_PRIORITY; start() }
        status = ""
        showNotification()
    }

    /** Mic off; the speech already captured is still transcribed, then saved. */
    private fun stopRecording() {
        val id = sessionId
        if (id == 0L) return
        capture = false
        audioThread?.join(3_000)
        audioThread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        store.markStopped(id, System.currentTimeMillis())
        settings.sessionId = 0L
        sessionId = 0L
        showNotification()
        processor?.wake()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(id: Long) {
        val vad = try { Speech.vad(this, settings.vadThreshold) } catch (e: Exception) {
            status = "Voice detector failed to load: ${e.message}"
            return
        }
        val rate = Speech.RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = when (settings.micSource) {
            "voice" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "raw" -> if (getSystemService(android.media.AudioManager::class.java)
                    .getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Speech.BLOCK * 2 * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            status = "Microphone unavailable"
            rec.release()
            vad.release()
            return
        }
        val writer = Speech.ChunkWriter(store.sessionDir(id))
        val block = ShortArray(Speech.BLOCK)
        val floats = FloatArray(Speech.BLOCK)
        rec.startRecording()
        val col = Speech.Collector(vad, writer, System.currentTimeMillis())
        fun closeChunk() {
            if (writer.open) { writer.close(); processor?.wake() }
            chunkSpeechSec = 0
        }
        try {
            while (capture) {
                var got = 0
                while (got < block.size && capture) {
                    val n = rec.read(block, got, block.size - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                    got += n
                }
                if (got < block.size) break
                var sum = 0.0
                for (i in block.indices) { val x = block[i] / 32768f; floats[i] = x; sum += x * x }
                levelDb = (10 * kotlin.math.log10(sum / block.size + 1e-9)).toFloat().coerceAtLeast(-90f)
                col.feed(floats)
                speaking = col.speaking
                chunkSpeechSec = (writer.speech / rate).toInt()
                if (writer.due(System.currentTimeMillis())) closeChunk()
            }
        } catch (e: Exception) {
            Log.e("earshot", "capture stopped", e)
            status = "Microphone error: ${e.message}"
        } finally {
            try { col.flush() } catch (e: Exception) { Log.w("earshot", "flush failed", e) }
            closeChunk()
            rec.stop()
            rec.release()
            vad.release()
            speaking = false
            levelDb = -90f
        }
    }

    /** Everything transcribed and queued for upload: the service ends. */
    private fun finish() {
        processor?.stop()
        processor = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        processor?.stop()
        processor = null
        running = false
        super.onDestroy()
    }
}
