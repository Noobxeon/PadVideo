package com.padvideo.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.padvideo.MainActivity
import com.padvideo.PadVideoApplication
import com.padvideo.data.model.BatchSubtitleTaskProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight Foreground Service that keeps directory-wide batch subtitle generation alive
 * when the tablet screen turns off or user switches apps, showing live progress in the status bar.
 */
class BatchSubtitleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            (application as? PadVideoApplication)?.container?.batchSubtitleGenerator?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val app = application as? PadVideoApplication
        val generator = app?.container?.batchSubtitleGenerator

        val initialProgress = generator?.progress?.value ?: BatchSubtitleTaskProgress(
            isRunning = true,
            currentStageText = "正在后台生成视频字幕…"
        )
        startForegroundCompat(buildNotification(initialProgress))

        if (observerJob == null && generator != null) {
            observerJob = serviceScope.launch {
                generator.progress.collect { state ->
                    if (!state.isRunning) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        nm?.notify(NOTIFICATION_ID, buildNotification(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(state: BatchSubtitleTaskProgress): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, BatchSubtitleService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (state.totalVideos > 0) {
            "PadVideo 批量生成字幕 (${state.processedVideos}/${state.totalVideos})"
        } else {
            "PadVideo 正在准备批量生成字幕"
        }
        val body = buildString {
            if (state.currentVideoName.isNotBlank()) {
                append(state.currentVideoName)
                append(" · ")
            }
            append(state.currentStageText.ifBlank { "处理中…" })
        }

        val pct = (state.overallFraction * 100f).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, state.totalVideos <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消任务", cancelPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "批量字幕生成进度",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "后台为目录中的视频提取音轨、识别并生成双语字幕"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "padvideo_batch_subtitle"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_CANCEL = "com.padvideo.action.CANCEL_BATCH_SUBTITLE"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, BatchSubtitleService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BatchSubtitleService::class.java))
            }
        }
    }
}
