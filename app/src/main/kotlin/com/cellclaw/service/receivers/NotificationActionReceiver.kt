package com.cellclaw.service.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.cellclaw.agent.AgentLoop
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class NotificationActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun agentLoop(): AgentLoop
        fun approvalQueue(): ApprovalQueue
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, ReceiverEntryPoint::class.java
        )

        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent, entryPoint)
            ACTION_APPROVE_ALL -> {
                entryPoint.approvalQueue().respondAll(ApprovalResult.APPROVED)
                Log.d(TAG, "Approved all pending requests")
            }
            ACTION_DENY_ALL -> {
                entryPoint.approvalQueue().respondAll(ApprovalResult.DENIED)
                Log.d(TAG, "Denied all pending requests")
            }
        }
    }

    private fun handleReply(context: Context, intent: Intent, entryPoint: ReceiverEntryPoint) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val text = remoteInput?.getCharSequence(KEY_REPLY)?.toString()
        if (text.isNullOrBlank()) return

        Log.d(TAG, "Reply received: $text")
        entryPoint.agentLoop().submitMessage(text)
    }

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_REPLY = "com.cellclaw.NOTIFICATION_REPLY"
        const val ACTION_APPROVE_ALL = "com.cellclaw.APPROVE_ALL"
        const val ACTION_DENY_ALL = "com.cellclaw.DENY_ALL"
        const val KEY_REPLY = "key_reply_text"
    }
}
