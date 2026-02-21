package com.cellclaw.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import javax.inject.Inject
import kotlin.coroutines.resume

class SensorTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "sensor.read"
    override val description = "Read device sensor data (accelerometer, gyroscope, light, proximity, etc.)."
    override val parameters = ToolParameters(
        properties = mapOf(
            "sensor" to ParameterProperty("string", "Sensor type to read",
                enum = listOf("accelerometer", "gyroscope", "light", "proximity", "pressure", "all"))
        ),
        required = listOf("sensor")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val sensorType = params["sensor"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'sensor' parameter")

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        return try {
            if (sensorType == "all") {
                val result = buildJsonObject {
                    putJsonArray("available_sensors") {
                        sensorManager.getSensorList(Sensor.TYPE_ALL).forEach {
                            add(it.name)
                        }
                    }
                }
                return ToolResult.success(result)
            }

            val androidSensorType = when (sensorType) {
                "accelerometer" -> Sensor.TYPE_ACCELEROMETER
                "gyroscope" -> Sensor.TYPE_GYROSCOPE
                "light" -> Sensor.TYPE_LIGHT
                "proximity" -> Sensor.TYPE_PROXIMITY
                "pressure" -> Sensor.TYPE_PRESSURE
                else -> return ToolResult.error("Unknown sensor type: $sensorType")
            }

            val sensor = sensorManager.getDefaultSensor(androidSensorType)
                ?: return ToolResult.error("Sensor '$sensorType' not available on this device")

            val values = withTimeout(5000) {
                suspendCancellableCoroutine<FloatArray> { cont ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            sensorManager.unregisterListener(this)
                            cont.resume(event.values.clone())
                        }
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
                }
            }

            val result = buildJsonObject {
                put("sensor", sensorType)
                putJsonArray("values") { values.forEach { add(it.toDouble()) } }
                when (sensorType) {
                    "accelerometer" -> {
                        put("x", values[0].toDouble())
                        put("y", values[1].toDouble())
                        put("z", values[2].toDouble())
                        put("unit", "m/s²")
                    }
                    "gyroscope" -> {
                        put("x", values[0].toDouble())
                        put("y", values[1].toDouble())
                        put("z", values[2].toDouble())
                        put("unit", "rad/s")
                    }
                    "light" -> {
                        put("lux", values[0].toDouble())
                        put("unit", "lux")
                    }
                    "proximity" -> {
                        put("distance_cm", values[0].toDouble())
                        put("unit", "cm")
                    }
                    "pressure" -> {
                        put("hpa", values[0].toDouble())
                        put("unit", "hPa")
                    }
                }
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("Sensor read error: ${e.message}")
        }
    }
}
