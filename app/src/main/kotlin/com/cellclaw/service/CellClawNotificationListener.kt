package com.cellclaw.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Collections
import java.util.LinkedList

class CellClawNotificationListener : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationListenerEntryPoint {
        fun agentLoop(): AgentLoop
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Filter out our own notifications to avoid recursion
        if (sbn.packageName == "com.cellclaw") return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val captured = CapturedNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            text = extras.getCharSequence("android.text")?.toString() ?: "",
            timestamp = sbn.postTime,
            key = sbn.key
        )

        synchronized(recentNotifications) {
            recentNotifications.add(0, captured)
            while (recentNotifications.size > MAX_NOTIFICATIONS) {
                recentNotifications.removeAt(recentNotifications.size - 1)
            }
        }

        Log.d(TAG, "Notification from ${sbn.packageName}: ${captured.title}")

        // Check if this app is a trigger
        if (sbn.packageName in triggerApps) {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext, NotificationListenerEntryPoint::class.java
                )
                val agentLoop = entryPoint.agentLoop()
                if (agentLoop.state.value == AgentState.IDLE) {
                    val msg = "[Notification from ${sbn.packageName}] ${captured.title}: ${captured.text}"
                    agentLoop.submitMessage(msg)
                    Log.d(TAG, "Auto-submitted notification trigger: $msg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger agent from notification: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op
    }

    companion object {
        private const val TAG = "NotifListener"
        private const val MAX_NOTIFICATIONS = 50

        val recentNotifications: MutableList<CapturedNotification> =
            Collections.synchronizedList(LinkedList<CapturedNotification>())

        val triggerApps: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    }
}

data class CapturedNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val key: String
)
