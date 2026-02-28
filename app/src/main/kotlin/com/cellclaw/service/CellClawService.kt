package com.cellclaw.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.agent.HeartbeatManager
import com.cellclaw.agent.HeartbeatState
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.service.receivers.NotificationActionReceiver
import com.cellclaw.ui.MainActivity
import com.cellclaw.voice.ShakeDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@AndroidEntryPoint
class CellClawService : Service() {

    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var approvalQueue: ApprovalQueue
    @Inject lateinit var heartbeatManager: HeartbeatManager
    @Inject lateinit var shakeDetector: ShakeDetector

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("CellClaw is running", AgentState.IDLE, false, HeartbeatState.STOPPED, null))
                agentLoop.loadHistory()
                observeState()
                heartbeatManager.start(wakeLock)
                shakeDetector.start()
            }
            ACTION_STOP -> {
                heartbeatManager.stop()
                shakeDetector.stop()
                agentLoop.stop()
                serviceScope?.cancel()
                serviceScope = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                agentLoop.pause()
            }
            ACTION_RESUME -> {
                agentLoop.resume()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatManager.stop()
        shakeDetector.stop()
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun observeState() {
        serviceScope?.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope?.launch {
            combine(
                agentLoop.state,
                approvalQueue.requests,
                heartbeatManager.state,
                heartbeatManager.activeTaskContext
            ) { agentState, requests, heartbeatState, taskContext ->
                StateSnapshot(agentState, requests, heartbeatState, taskContext)
            }.collect { snapshot ->
                val hasPending = snapshot.requests.isNotEmpty()
                val text = when (snapshot.agentState) {
                    AgentState.IDLE -> {
                        when (snapshot.heartbeatState) {
                            HeartbeatState.ACTIVE -> {
                                val ctx = snapshot.taskContext
                                if (ctx != null) "Monitoring: $ctx" else "Idle"
                            }
                            HeartbeatState.POLLING -> "Checking..."
                            HeartbeatState.STOPPED -> "Idle"
                        }
                    }
                    AgentState.THINKING -> "Thinking..."
                    AgentState.EXECUTING_TOOLS -> "Executing tools..."
                    AgentState.WAITING_APPROVAL -> "Waiting for approval (${snapshot.requests.size} pending)"
                    AgentState.PAUSED -> "Paused"
                    AgentState.ERROR -> "Error occurred"
                }
                val notification = buildNotification(text, snapshot.agentState, hasPending, snapshot.heartbeatState, snapshot.taskContext)
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private data class StateSnapshot(
        val agentState: AgentState,
        val requests: List<Any>,
        val heartbeatState: HeartbeatState,
        val taskContext: String?
    )

    private fun buildNotification(text: String, state: AgentState, hasPendingApprovals: Boolean, heartbeatState: HeartbeatState, taskContext: String?): Notification {
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

        // Pause/Resume toggle
        val isPaused = state == AgentState.PAUSED
        val toggleAction = if (isPaused) {
            PendingIntent.getService(
                this, 2,
                Intent(this, CellClawService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2,
                Intent(this, CellClawService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Reply action via RemoteInput (shows as text field, not a button)
        val replyIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel("Ask CellClaw...")
            .build()
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", replyIntent)
            .addRemoteInput(remoteInput)
            .build()


        val builder = NotificationCompat.Builder(this, CellClawApp.CHANNEL_SERVICE)
            .setContentTitle("CellClaw")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)

        // Android shows max 3 action buttons — prioritize by state
        if (hasPendingApprovals) {
            val approveIntent = PendingIntent.getBroadcast(
                this, 5,
                Intent(this, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_APPROVE_ALL
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            val denyIntent = PendingIntent.getBroadcast(
                this, 6,
                Intent(this, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_DENY_ALL
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Approve All", approveIntent)
            builder.addAction(0, "Deny All", denyIntent)
            builder.addAction(0, if (isPaused) "Resume" else "Pause", toggleAction)
        } else {
            builder.addAction(0, "Stop", stopIntent)
            builder.addAction(0, if (isPaused) "Resume" else "Pause", toggleAction)
        }
        builder.addAction(replyAction)

        return builder.build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CellClaw::AgentWakeLock"
        ).apply {
            acquire(HeartbeatManager.WAKE_LOCK_DURATION_MS) // 5 min, renewable by heartbeat
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
