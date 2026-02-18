package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class SettingsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "settings.get"
    override val description = "Get device settings and status: battery, WiFi, screen brightness, etc."
    override val parameters = ToolParameters(
        properties = mapOf(
            "info" to ParameterProperty("string", "What to query",
                enum = listOf("battery", "wifi", "brightness", "all"))
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val info = params["info"]?.jsonPrimitive?.contentOrNull ?: "all"

        return try {
            val result = buildJsonObject {
                if (info == "battery" || info == "all") {
                    val batteryIntent = context.registerReceiver(null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val pct = (level * 100) / scale

                    putJsonObject("battery") {
                        put("percentage", pct)
                        put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL)
                        put("status", when (status) {
                            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                            BatteryManager.BATTERY_STATUS_FULL -> "full"
                            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                            else -> "unknown"
                        })
                    }
                }

                if (info == "wifi" || info == "all") {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = cm.activeNetwork
                    val caps = network?.let { cm.getNetworkCapabilities(it) }

                    putJsonObject("network") {
                        put("connected", network != null)
                        put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false)
                        put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false)
                    }
                }

                if (info == "brightness" || info == "all") {
                    val brightness = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS, 128
                    )
                    putJsonObject("display") {
                        put("brightness", brightness)
                        put("brightness_pct", (brightness * 100) / 255)
                    }
                }
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("Failed to get settings: ${e.message}")
        }
    }
}
