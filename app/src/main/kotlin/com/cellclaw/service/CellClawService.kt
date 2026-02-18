package com.cellclaw.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentLoop
import com.cellclaw.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CellClawService : Service() {

    @Inject lateinit var agentLoop: AgentLoop

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("CellClaw is running"))
                agentLoop.loadHistory()
            }
            ACTION_STOP -> {
                agentLoop.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                agentLoop.pause()
                updateNotification("CellClaw is paused")
            }
            ACTION_RESUME -> {
                agentLoop.resume()
                updateNotification("CellClaw is running")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CellClawService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CellClawService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CellClawApp.CHANNEL_SERVICE)
            .setContentTitle("CellClaw")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Pause", pauseIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CellClaw::AgentWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.cellclaw.START"
        const val ACTION_STOP = "com.cellclaw.STOP"
        const val ACTION_PAUSE = "com.cellclaw.PAUSE"
        const val ACTION_RESUME = "com.cellclaw.RESUME"
        const val NOTIFICATION_ID = 1
    }
}
