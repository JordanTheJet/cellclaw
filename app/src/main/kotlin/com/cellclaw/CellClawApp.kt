package com.cellclaw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory

@HiltAndroidApp
class CellClawApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "CellClaw background service status"
            setShowBadge(false)
        }

        val approvalChannel = NotificationChannel(
            CHANNEL_APPROVALS,
            getString(R.string.notification_channel_approvals),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Tool execution approval requests"
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "General alerts and notifications"
        }

        manager.createNotificationChannels(listOf(serviceChannel, approvalChannel, alertChannel))
    }

    companion object {
        const val CHANNEL_SERVICE = "cellclaw_service"
        const val CHANNEL_APPROVALS = "cellclaw_approvals"
        const val CHANNEL_ALERTS = "cellclaw_alerts"
    }
}
