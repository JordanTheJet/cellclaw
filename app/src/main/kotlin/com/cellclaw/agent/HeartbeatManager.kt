package com.cellclaw.agent

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.cellclaw.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class HeartbeatManager @Inject constructor(
    private val agentLoopProvider: Provider<AgentLoop>,
    private val appConfig: AppConfig
) {
    private val agentLoop: AgentLoop get() = agentLoopProvider.get()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Backoff state
    private var currentIntervalMs: Long = BASE_INTERVAL_MS
    private var consecutiveOkCount: Int = 0

    // Observable state for notification/UI
    private val _state = MutableStateFlow(HeartbeatState.STOPPED)
    val state: StateFlow<HeartbeatState> = _state.asStateFlow()

    private val _lastHeartbeatMs = MutableStateFlow(0L)
    val lastHeartbeatMs: StateFlow<Long> = _lastHeartbeatMs.asStateFlow()

    // Active task context — set by agent via heartbeat.context tool
    private val _activeTaskContext = MutableStateFlow<String?>(null)
    val activeTaskContext: StateFlow<String?> = _activeTaskContext.asStateFlow()

    private val tickRunnable = Runnable { onTick() }

    fun start(wakeLock: PowerManager.WakeLock?) {
        if (!appConfig.heartbeatEnabled) return
        this.wakeLock = wakeLock
        running = true
        resetBackoff()
        _state.value = HeartbeatState.ACTIVE
        scheduleNext()
        Log.i(TAG, "Heartbeat started, interval=${currentIntervalMs}ms")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        _state.value = HeartbeatState.STOPPED
        _activeTaskContext.value = null
        Log.i(TAG, "Heartbeat stopped")
    }

    fun setActiveTaskContext(context: String?) {
        _activeTaskContext.value = context
        if (context != null && running) {
            // New task context — reset backoff to poll aggressively
            resetBackoff()
            handler.removeCallbacks(tickRunnable)
            scheduleNext()
            Log.d(TAG, "Task context set: $context, reset to base interval")
        }
    }

    fun clearActiveTaskContext() {
        _activeTaskContext.value = null
        // Without active context, slow down to max interval
        currentIntervalMs = MAX_INTERVAL_MS
        Log.d(TAG, "Task context cleared, interval -> ${currentIntervalMs}ms")
    }

    /**
     * Called by AgentLoop when a heartbeat run completes.
     */
    fun onHeartbeatResult(result: HeartbeatResult) {
        _lastHeartbeatMs.value = System.currentTimeMillis()
        _state.value = HeartbeatState.ACTIVE

        when (result) {
            HeartbeatResult.OK_NOTHING_TO_DO -> {
                consecutiveOkCount++
                // Exponential backoff: 5s -> 10s -> 30s -> 60s
                currentIntervalMs = BACKOFF_SCHEDULE.getOrElse(consecutiveOkCount - 1) {
                    MAX_INTERVAL_MS
                }
                Log.d(TAG, "HEARTBEAT_OK #$consecutiveOkCount, next interval=${currentIntervalMs}ms")
            }
            HeartbeatResult.ACTED -> {
                // Agent took action — reset to aggressive polling
                resetBackoff()
                Log.d(TAG, "Agent acted, reset to base interval")
            }
            HeartbeatResult.SKIPPED_BUSY -> {
                // Agent was busy — retry soon but don't reset backoff
                Log.d(TAG, "Agent busy, will retry at current interval")
            }
            HeartbeatResult.ERROR -> {
                // Error — back off
                currentIntervalMs = (currentIntervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
                Log.w(TAG, "Heartbeat error, backoff to ${currentIntervalMs}ms")
            }
        }

        renewWakeLock()

        if (running) {
            scheduleNext()
        }
    }

    private fun onTick() {
        if (!running) return
        if (!appConfig.heartbeatEnabled) {
            stop()
            return
        }

        val agentState = agentLoop.state.value
        if (agentState != AgentState.IDLE) {
            // Agent is actively working — skip this heartbeat
            onHeartbeatResult(HeartbeatResult.SKIPPED_BUSY)
            return
        }

        val taskContext = _activeTaskContext.value
        if (taskContext == null && !appConfig.heartbeatAlwaysPoll) {
            // No active task and not configured to always poll — use long interval
            currentIntervalMs = MAX_INTERVAL_MS
            scheduleNext()
            return
        }

        _state.value = HeartbeatState.POLLING

        val prompt = buildHeartbeatPrompt(taskContext)
        agentLoop.submitHeartbeat(prompt)
        // AgentLoop will call onHeartbeatResult() when done
    }

    private fun scheduleNext() {
        handler.removeCallbacks(tickRunnable)
        val interval = currentIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        handler.postDelayed(tickRunnable, interval)
    }

    private fun resetBackoff() {
        consecutiveOkCount = 0
        currentIntervalMs = BASE_INTERVAL_MS
    }

    private fun renewWakeLock() {
        wakeLock?.let { wl ->
            try {
                if (wl.isHeld) {
                    wl.release()
                }
                wl.acquire(WAKE_LOCK_DURATION_MS)
                Log.d(TAG, "Wake lock renewed for ${WAKE_LOCK_DURATION_MS / 1000}s")
            } catch (e: Exception) {
                Log.w(TAG, "Wake lock renewal failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HeartbeatManager"

        const val MIN_INTERVAL_MS = 3_000L           // 3 seconds absolute floor
        const val BASE_INTERVAL_MS = 5_000L           // 5 seconds
        const val MAX_INTERVAL_MS = 60_000L            // 60 seconds
        const val WAKE_LOCK_DURATION_MS = 5 * 60_000L  // 5 minutes, renewable

        // Backoff schedule matching openclaw: 5s -> 10s -> 30s -> 60s
        private val BACKOFF_SCHEDULE = listOf(
            5_000L,   // after 1st OK
            10_000L,  // after 2nd OK
            30_000L,  // after 3rd OK
            60_000L   // after 4th+ OK
        )
    }
}

enum class HeartbeatState {
    STOPPED,    // Not running
    ACTIVE,     // Running, waiting for next tick
    POLLING     // Currently executing a heartbeat
}

enum class HeartbeatResult {
    OK_NOTHING_TO_DO,   // Agent responded with HEARTBEAT_OK
    ACTED,              // Agent took actions (tool calls)
    SKIPPED_BUSY,       // Agent was busy, heartbeat skipped
    ERROR               // Something went wrong
}
