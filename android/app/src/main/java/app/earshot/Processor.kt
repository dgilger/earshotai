package app.earshot

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Turns recorded speech into transcripts: takes finished chunks, labels speakers, transcribes,
 * appends the text to the session's transcript and deletes the audio. When a session is stopped
 * and all its chunks are done, the transcript is finished and saved to the chosen folder.
 * Chunks survive a crash or reboot, so nothing is lost if the phone dies mid-way.
 */
class Processor(private val ctx: Context) {
    companion object {
        private val DRAIN = Any()

        /** What it's doing now, for the screen ("" when idle). */
        @Volatile var stage = ""
            private set
        @Volatile var lastError: String? = null
            private set

        /**
         * Does all the work waiting on disk. [current] is the session being recorded now (0 = none):
         * its chunks are transcribed but it isn't finished. Returns true when nothing is left except
         * the [current] session. Safe to call from several threads.
         */
        fun drain(ctx: Context, current: () -> Long, keepGoing: () -> Boolean = { true }): Boolean = synchronized(DRAIN) {
            val store = Store(ctx)
            val settings = Settings(ctx)
            var finished = false
            for (id in store.sessions()) {
                if (!keepGoing()) return false
                val dir = store.sessionDir(id)
                val live = id == current()
                // A session left behind by a crash: close its half-written chunk and finish it.
                if (!live && store.stopped(id) == null) {
                    Speech.ChunkWriter.recover(dir)
                    store.markStopped(id, dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis())
                }
                for (idx in Speech.ChunkWriter.ready(dir)) {
                    if (!keepGoing()) return false
                    try {
                        val lines = if (Server.ready(settings)) {
                            // Your own server transcribes; null = stopped while waiting (try again later).
                            Server.transcribe(settings, id, idx, store.stopped(id)?.second?.isNotEmpty() == true,
                                keepGoing) { stage = it } ?: return false
                        } else Speech.process(ctx, settings, idx) { stage = it }
                        store.append(id, lines.map { it.timeMs to Speech.line(it) })
                        idx.delete()
                        File(idx.path.removeSuffix(".idx") + ".pcm").delete()  // audio gone once the text is safe
                        File(idx.path.removeSuffix(".idx") + ".job").delete()
                        lastError = null
                    } catch (t: Throwable) {
                        Log.e("earshot", "processing failed for ${idx.name}", t)
                        lastError = t.message ?: t.javaClass.simpleName
                        return false   // keep the audio, try again later
                    } finally {
                        stage = ""
                    }
                }
                if (id == current()) continue
                val stop = store.stopped(id) ?: continue
                if (dir.listFiles { f -> f.name.endsWith(".pcm.part") }?.isNotEmpty() == true) continue
                if (store.finish(id, stop.first, stop.second)) finished = true
                if (Server.ready(settings)) Server.finish(ctx, id, stop.first, stop.second)
                dir.deleteRecursively()
            }
            if (settings.serverFinishPending.isNotEmpty()) Server.sendFinishes(ctx)
            if (finished) Uploader.run(ctx)
            return store.sessions().all { it == current() }
        }
    }

    private val lock = Object()
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Called on the worker thread when no work is left (nothing waiting, nothing recording). */
    var onIdle: (() -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            loop()
        }, "processor").apply { start() }
    }

    fun stop() {
        running = false
        wake()
        thread?.join(2_000)  // a chunk in progress finishes on its own; its audio stays until then
        thread = null
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun loop() {
        while (running) {
            val done = try {
                drain(ctx, { ListenService.sessionId }, { running })
            } catch (t: Throwable) {
                Log.e("earshot", "drain failed", t)
                false
            }
            if (done && ListenService.sessionId == 0L) {
                // Calls recorded by the phone get transcribed while the app is open anyway.
                if (CallRecordings.scan(ctx) { running }) continue
                onIdle?.invoke()
            }
            synchronized(lock) { lock.wait(if (lastError != null) 60_000 else 30_000) }
        }
    }
}
