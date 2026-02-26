package com.cellclaw.wakeword

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentLoop
import com.cellclaw.ui.MainActivity
import com.cellclaw.voice.VoiceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Foreground service for always-on "CellClaw" wake word detection.
 *
 * Continuously records audio using a sliding window (1.5s window, 0.75s hop),
 * runs MFCC extraction + TFLite inference, and triggers voice recognition
 * when the wake word is detected with high confidence.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var wakeWordDetector: WakeWordDetector
    @Inject lateinit var voiceManager: VoiceManager
    @Inject lateinit var agentLoop: AgentLoop

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope: CoroutineScope? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Listening for wake word..."))
                startDetection()
            }
            ACTION_ACTIVATE -> {
                // Triggered by hardware hotkey — start voice listening immediately
                Log.d(TAG, "Hotkey activation received")
                if (serviceScope == null) {
                    // Service not fully started yet — start it first
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification("Listening for command..."))
                        startDetection()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Cannot start foreground service: ${e.message}")
                        stopSelf()
                        return START_STICKY
                    }
                }
                serviceScope?.launch { handleWakeWordDetected() }
            }
            ACTION_STOP -> {
                stopDetection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopDetection()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startDetection() {
        if (serviceScope != null) return

        wakeWordDetector.initialize()
        if (!wakeWordDetector.isInitialized) {
            Log.e(TAG, "WakeWordDetector failed to initialize, stopping")
            stopSelf()
            return
        }

        voiceManager.initialize()

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope?.launch {
            runDetectionLoop()
        }
    }

    private fun stopDetection() {
        serviceScope?.cancel()
        serviceScope = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        wakeWordDetector.release()
    }

    private suspend fun runDetectionLoop() {
        val sampleRate = 16000
        val windowSamples = sampleRate * 3 / 2  // 1.5s = 24000 samples
        val hopSamples = windowSamples / 2       // 0.75s = 12000 samples

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            windowSamples * 2  // At least one full window in bytes
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted: ${e.message}")
            withContext(Dispatchers.Main) { stopSelf() }
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            withContext(Dispatchers.Main) { stopSelf() }
            return
        }

        audioRecord?.startRecording()
        Log.d(TAG, "Wake word detection loop started")

        // Circular buffer holding the latest audio
        val ringBuffer = ShortArray(windowSamples)
        var ringPos = 0
        var samplesRead = 0
        val readChunk = ShortArray(hopSamples)

        while (coroutineContext.isActive) {
            // Read a hop-sized chunk
            val read = audioRecord?.read(readChunk, 0, hopSamples) ?: -1
            if (read <= 0) {
                delay(10)
                continue
            }

            // Write into ring buffer
            for (i in 0 until read) {
                ringBuffer[ringPos] = readChunk[i]
                ringPos = (ringPos + 1) % windowSamples
            }
            samplesRead += read

            // Need at least one full window before detecting
            if (samplesRead < windowSamples) continue

            // Linearize ring buffer from current position
            val window = ShortArray(windowSamples)
            for (i in 0 until windowSamples) {
                window[i] = ringBuffer[(ringPos + i) % windowSamples]
            }

            // Run detection
            val confidence = wakeWordDetector.detect(window)

            if (confidence >= DETECTION_THRESHOLD) {
                Log.d(TAG, "Wake word detected! Confidence: $confidence")
                handleWakeWordDetected()
            }
        }

        audioRecord?.stop()
        Log.d(TAG, "Wake word detection loop stopped")
    }

    private suspend fun handleWakeWordDetected() {
        withContext(Dispatchers.Main) {
            // Vibrate
            vibrate()

            // Wake screen
            wakeScreen()

            // Update notification
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification("Wake word detected! Listening..."))

            // Start voice recognition
            voiceManager.startListening()
        }

        // Wait for recognized text with timeout
        val text = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            voiceManager.recognizedText.first()
        }

        if (!text.isNullOrBlank()) {
            Log.d(TAG, "Command received: $text")
            agentLoop.submitMessage(text)
        } else {
            Log.d(TAG, "No command received within timeout")
        }

        // Return to passive listening
        withContext(Dispatchers.Main) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification("Listening for wake word..."))
        }
    }

    private fun vibrate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "CellClaw::WakeScreenLock"
            )
            screenLock.acquire(5000L)
            screenLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake screen failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CellClawApp.CHANNEL_WAKE_WORD)
            .setContentTitle("CellClaw Wake Word")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CellClaw::WakeWordWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.cellclaw.wakeword.START"
        const val ACTION_ACTIVATE = "com.cellclaw.wakeword.ACTIVATE"
        const val ACTION_STOP = "com.cellclaw.wakeword.STOP"
        const val NOTIFICATION_ID = 2
        private const val DETECTION_THRESHOLD = 0.85f
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val TAG = "WakeWordService"
    }
}
