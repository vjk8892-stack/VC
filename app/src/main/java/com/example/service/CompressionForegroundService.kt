package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.data.model.CompressionItemState
import com.example.data.model.VideoQueueItem
import com.example.engine.CompressionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process foreground - protected from the OS reclaiming it under memory pressure -
 * for as long as [CompressionEngine] has a batch running, and shows live progress. All actual
 * download/encode work happens in the engine (a plain singleton, not tied to this service's
 * lifecycle either); this service only observes it and starts/stops itself around the engine's
 * own run, so it survives being started fresh, rebound, or the process being relaunched mid-batch.
 */
class CompressionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private lateinit var engine: CompressionEngine
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        engine = CompressionEngine.getInstance(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        // Must be called within a few seconds of the service starting, before we know any real
        // progress - a generic "starting" notification, replaced by the observer below.
        startForegroundSafely(buildNotification("Starting batch..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (observerJob == null) {
            observerJob = serviceScope.launch {
                combine(engine.queue, engine.isBatchRunning, engine.isBatchPaused) { queue, running, paused ->
                    Triple(queue, running, paused)
                }.collectLatest { (queue, running, paused) ->
                    if (!running) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    notifySafely(buildNotification(progressText(queue, paused)))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun progressText(queue: List<VideoQueueItem>, paused: Boolean): String {
        val total = queue.size
        val completed = queue.count { it.status == CompressionItemState.COMPLETED }
        val active = queue.firstOrNull {
            it.status == CompressionItemState.PROCESSING || it.status == CompressionItemState.DOWNLOADING
        }
        return when {
            paused -> "Paused - $completed/$total done"
            active != null -> "${active.title.take(30)} - ${(active.progress * 100).toInt()}% ($completed/$total done)"
            else -> "$completed/$total done"
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compressing videos")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compression Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing video compression/download progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // The service must keep running (and the batch keeps encoding) even on a device/OS combo
    // that blocks the notification itself - e.g. POST_NOTIFICATIONS denied on API 33+, or an
    // OEM quirk - so a failure here must never crash the batch.
    private fun startForegroundSafely(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun notifySafely(notification: Notification) {
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL_ID = "compression_progress"
        private const val NOTIFICATION_ID = 4201
    }
}
