package com.cellclaw.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.cellclaw.agent.AgentLoop
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles voice activation triggered by the hardware hotkey (double-tap volume down).
 *
 * Listens for ACTION_ACTIVATE broadcasts from the accessibility service (which runs
 * in a separate process) and orchestrates: chime → speech recognition → agent submission.
 */
@Singleton
class VoiceActivationHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceManager: VoiceManager,
    private val voiceListeningState: VoiceListeningState,
    private val agentLoop: AgentLoop,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activationJob: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_ACTIVATE) {
                Log.d(TAG, "Voice activation broadcast received")
                activate()
            }
        }
    }

    fun register() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_ACTIVATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(ACTION_ACTIVATE))
        }
        voiceManager.initialize()
        registered = true
        Log.d(TAG, "VoiceActivationHandler registered")
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        activationJob?.cancel()
        registered = false
    }

    fun activate() {
        if (activationJob?.isActive == true) {
            Log.d(TAG, "Already listening, ignoring")
            return
        }
        activationJob = scope.launch { handleActivation() }
    }

    private suspend fun handleActivation() {
        // 1. Signal overlay: ACTIVATED
        voiceListeningState.setPhase(ListeningPhase.ACTIVATED)
        voiceListeningState.setDisplayText("Listening...")

        // 2. Vibrate + chime
        vibrate()
        playActivationChime()

        // 3. Short delay for chime to finish
        delay(300)

        // 4. Signal overlay: LISTENING, start speech recognition
        voiceListeningState.setPhase(ListeningPhase.LISTENING)
        voiceManager.startListening()

        // 5. Forward partial results to overlay
        val partialJob = scope.launch {
            voiceManager.partialText.collect { text ->
                if (text.isNotBlank()) {
                    voiceListeningState.setDisplayText(text)
                }
            }
        }

        // 6. Wait for final result — race between success, error, and timeout
        val text = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            select<String?> {
                async { voiceManager.recognizedText.first() }.onAwait { it }
                async { voiceManager.recognitionFailed.first(); null }.onAwait { it }
            }
        }

        partialJob.cancel()

        // Use final result, or fall back to last partial text if recognizer never finalized
        val command = text?.takeIf { it.isNotBlank() }
            ?: voiceManager.partialText.value.takeIf { it.isNotBlank() }

        if (command != null) {
            Log.d(TAG, "Command received: $command (final=${text != null})")
            voiceListeningState.setPhase(ListeningPhase.PROCESSING)
            voiceListeningState.setDisplayText(command)
            agentLoop.submitMessage(command)
            delay(500)
        } else {
            Log.d(TAG, "No command received (error or timeout)")
            voiceListeningState.setDisplayText("No speech detected")
            delay(500)
        }

        // 7. Reset overlay
        voiceListeningState.reset()
    }

    private fun playActivationChime() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            scope.launch {
                delay(200)
                toneGen.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chime failed: ${e.message}")
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_ACTIVATE = "com.cellclaw.voice.ACTIVATE"
        private const val COMMAND_TIMEOUT_MS = 8_000L
        private const val TAG = "VoiceActivation"
    }
}
