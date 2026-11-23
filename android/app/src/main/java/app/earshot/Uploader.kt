package app.earshot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Saves finished transcripts as .txt files into the folder the user picked (Android's folder
 * picker, so any folder on the phone, or one a sync app such as Autosync for Google Drive watches).
 */
object Uploader {
    private const val JOB_CALLS = 11
    private val LOCK = Any()

    /** Every 15 minutes (Android's shortest), look for new call recordings. Cancelled when no folder is set. */
    fun scheduleCalls(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.cancel(10)   // Drive upload job from an earlier version
        if (Settings(ctx).callFolder.isEmpty()) { js.cancel(JOB_CALLS); return }
        if (js.getPendingJob(JOB_CALLS) != null) return
        js.schedule(JobInfo.Builder(JOB_CALLS, ComponentName(ctx, WorkJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build())
    }

    /** Copies waiting transcripts into the chosen folder. Quick, no network; safe from any thread. */
    fun run(ctx: Context): Unit = synchronized(LOCK) {
        val s = Settings(ctx)
        val store = Store(ctx)
        val files = store.waiting()
        if (files.isEmpty()) return
        if (!s.outReady) { s.uploadError = "Not saved yet: choose a transcript folder in Settings"; return }
        try {
            val tree = Uri.parse(s.outFolder)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (f in files) {
                val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, "text/plain", store.title(f) + ".txt")
                    ?: throw RuntimeException("the folder refused a new file")
                ctx.contentResolver.openOutputStream(doc, "w")!!.use { it.write(f.readBytes()) }
                store.markUploaded(f)
            }
            s.uploadError = ""
        } catch (e: Exception) {
            Log.w("earshot", "save failed", e)
            s.uploadError = "Could not save to the folder (choose it again in Settings): ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Runs the call-recording check in the background. */
class WorkJob : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread({
            try {
                // Find new call files and transcribe them; finished transcripts are saved to the folder.
                CallRecordings.scan(this) { !stopped }
                Processor.drain(this, { ListenService.sessionId }, { !stopped })
            } catch (t: Throwable) {
                Log.e("earshot", "job failed", t)
            }
            jobFinished(params, false)
        }, "job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }
}
