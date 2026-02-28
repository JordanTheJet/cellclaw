package com.cellclaw.voice

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects intentional phone shakes to trigger voice activation.
 *
 * Requires 3 distinct shakes within 1.5 seconds, each exceeding 13 m/s²
 * acceleration (well above walking ~2-5 or picking up phone ~5-8).
 * After triggering, enforces a 3-second cooldown to prevent re-triggers.
 */
@Singleton
class ShakeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val shakeTimestamps = mutableListOf<Long>()
    private var lastTriggerTime = 0L
    private var running = false

    fun start() {
        if (running) return
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer available")
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        running = true
        Log.d(TAG, "Shake detection started")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        shakeTimestamps.clear()
        running = false
        Log.d(TAG, "Shake detection stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Compute acceleration magnitude minus gravity
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        if (magnitude < SHAKE_THRESHOLD) return

        val now = System.currentTimeMillis()

        // Cooldown check
        if (now - lastTriggerTime < COOLDOWN_MS) return

        // Record this shake
        shakeTimestamps.add(now)

        // Prune old timestamps outside the detection window
        shakeTimestamps.removeAll { now - it > SHAKE_WINDOW_MS }

        if (shakeTimestamps.size >= REQUIRED_SHAKES) {
            Log.d(TAG, "Shake detected! (${shakeTimestamps.size} shakes in window)")
            shakeTimestamps.clear()
            lastTriggerTime = now
            triggerActivation()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerActivation() {
        context.sendBroadcast(
            Intent(VoiceActivationHandler.ACTION_ACTIVATE).apply {
                setPackage(context.packageName)
            }
        )
    }

    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_THRESHOLD = 13.0   // m/s² above gravity
        private const val SHAKE_WINDOW_MS = 1500L   // 3 shakes must occur within this window
        private const val REQUIRED_SHAKES = 3
        private const val COOLDOWN_MS = 3000L       // Ignore shakes for 3s after triggering
    }
}
