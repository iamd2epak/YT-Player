package com.iamd2epak.ytplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/** Foreground service that runs the download queue and shows progress notifications. */
class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_JOB = "job"
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val PROGRESS_ID = 7001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var worker: Thread? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
        // Leftovers from a download that was killed with the app.
        if (!Downloads.hasActive()) DownloadEngine.tempRoot(this).deleteRecursively()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground right away when started with startForegroundService().
        goForeground(progressNotification(null))
        if (intent?.action == ACTION_CANCEL) Downloads.cancel(intent.getIntExtra(EXTRA_JOB, -1))
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(PROGRESS_ID, n)
        } catch (_: Exception) {
            // Not allowed right now (background start limits): keep working as a normal service.
        }
    }

    private fun ensureWorker() {
        if (worker != null) return
        if (Downloads.nextQueued() == null) return stopAll()
        worker = Thread({
            while (true) {
                val job = Downloads.nextQueued() ?: break
                runJob(job)
            }
            main.post {
                worker = null
                if (Downloads.nextQueued() != null) ensureWorker() else stopAll()
            }
        }, "downloads").apply { isDaemon = true; start() }
    }

    private fun stopAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun runJob(job: Downloads.Job) {
        job.status = Downloads.Status.RUNNING
        update(job, force = true)
        try {
            DownloadEngine(this, job) { update(job, force = false) }.run()
            job.status = Downloads.Status.DONE
            notifyFinished(job, null)
        } catch (_: DownloadEngine.CancelledException) {
            job.status = Downloads.Status.CANCELLED
        } catch (e: Throwable) {
            if (job.cancelled) {
                job.status = Downloads.Status.CANCELLED
            } else {
                job.status = Downloads.Status.FAILED
                job.error = when (e) {
                    is DownloadEngine.DownloadException, is YouTubeStreams.StreamException -> e.message.orEmpty()
                    is OutOfMemoryError -> "Not enough memory to finish this download."
                    else -> "Download failed. Please try again."
                }
                notifyFinished(job, job.error)
            }
        } finally {
            job.call = null
            try { File(DownloadEngine.tempRoot(this), job.id.toString()).deleteRecursively() } catch (_: Exception) {}
            Downloads.changed(job)
        }
    }

    /** Throttled: progress notification + in-app listeners. */
    private fun update(job: Downloads.Job, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        Downloads.changed(job)
        try { nm.notify(PROGRESS_ID, progressNotification(job)) } catch (_: Exception) {}
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(job: Downloads.Job?): Notification {
        val b = builder(CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        if (job == null) return b.setContentTitle("Preparing download...").setProgress(0, 0, true).build()

        val pct = job.percent
        val detail = when (job.status) {
            Downloads.Status.SAVING -> "Saving..."
            else -> buildString {
                append(job.option.label)
                if (pct >= 0) append(" · $pct%")
                if (job.total > 0) append(" · ${Downloads.formatSize(job.done)} / ${Downloads.formatSize(job.total)}")
            }
        }
        val cancel = PendingIntent.getService(
            this, job.id,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val queued = Downloads.queuedCount()
        return b.setContentTitle(job.title.ifBlank { "Downloading" })
            .setContentText(detail)
            .apply { if (queued > 0) setSubText("$queued more queued") }
            .setProgress(100, pct.coerceAtLeast(0), pct < 0 || job.status == Downloads.Status.SAVING)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun notifyFinished(job: Downloads.Job, error: String?) {
        val uri = job.resultUri?.takeIf { it.scheme == "content" }
        val content = if (error == null && uri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (job.option.audioOnly) "audio/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(this, 10_000 + job.id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else openAppIntent()

        val n = builder(CHANNEL_DONE)
            .setSmallIcon(if (error == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (error == null) "Downloaded · ${job.option.label}" else "Download failed")
            .setContentText(if (error == null) job.title.ifBlank { "Saved to ${job.location}" } else error)
            .setStyle(Notification.BigTextStyle().bigText(
                if (error == null) "${job.title}\nSaved to ${job.location}" else "${job.title}\n$error"
            ))
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        try { nm.notify(20_000 + job.id, n) } catch (_: Exception) {}
    }

    /** Android 15: data-sync services get a time limit. Stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.cancelAll()
        stopAll()
    }

    override fun onDestroy() {
        if (worker != null) Downloads.cancelAll()
        try { nm.cancel(PROGRESS_ID) } catch (_: Exception) {}
        super.onDestroy()
    }
}
