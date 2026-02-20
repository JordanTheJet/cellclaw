package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.cellclaw.service.AccessibilityBridge
import com.cellclaw.service.CellClawAccessibility
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class AppLaunchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "app.launch"
    override val description = "Launch an installed app by package name or app name."
    override val parameters = ToolParameters(
        properties = mapOf(
            "package_name" to ParameterProperty("string", "Package name (e.g. com.tinder)"),
            "app_name" to ParameterProperty("string", "App name to search for (e.g. Tinder)")
        )
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
        val appName = params["app_name"]?.jsonPrimitive?.contentOrNull

        val targetPackage = packageName ?: findPackageByName(appName ?: "")
        ?: return ToolResult.error("App not found. Provide a valid package_name or app_name.")

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: return ToolResult.error("Cannot launch app: $targetPackage")

            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("launched", true)
                put("package", targetPackage)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to launch app: ${e.message}")
        }
    }

    private fun findPackageByName(name: String): String? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
        }?.packageName
    }
}

class AppAutomateTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "app.automate"
    override val description = """Automate actions in other apps using AccessibilityService.
Actions: tap (by text or coordinates), type (into focused field), swipe (left/right/up/down for card swiping), scroll (up/down), back, home, find_element.
Requires accessibility permission enabled in system settings."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform",
                enum = listOf("tap", "type", "swipe", "scroll", "back", "home", "recents", "find_element")),
            "text" to ParameterProperty("string", "Text to type, or text of element to tap/find"),
            "x" to ParameterProperty("integer", "X coordinate for tap"),
            "y" to ParameterProperty("integer", "Y coordinate for tap"),
            "direction" to ParameterProperty("string", "Direction for swipe/scroll",
                enum = listOf("left", "right", "up", "down"))
        ),
        required = listOf("action")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        if (!AccessibilityBridge.isServiceConnected) {
            return ToolResult.error("Accessibility service not connected. Enable CellClaw in Settings > Accessibility.")
        }

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'action' parameter")

        return try {
            val requestId = AccessibilityBridge.createRequest()

            val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("request_id", requestId)
                putExtra("action", action)
                params["text"]?.jsonPrimitive?.contentOrNull?.let { putExtra("text", it) }
                params["x"]?.jsonPrimitive?.intOrNull?.let { putExtra("x", it) }
                params["y"]?.jsonPrimitive?.intOrNull?.let { putExtra("y", it) }
                params["direction"]?.jsonPrimitive?.contentOrNull?.let { putExtra("direction", it) }
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            val result = AccessibilityBridge.awaitResult(requestId)
            val success = result["success"]?.jsonPrimitive?.booleanOrNull ?: false

            if (success) {
                ToolResult.success(result)
            } else {
                ToolResult.error(result["error"]?.jsonPrimitive?.contentOrNull ?: "Action failed")
            }
        } catch (e: Exception) {
            ToolResult.error("Automation failed: ${e.message}")
        }
    }
}

/**
 * Separate read-only tool for reading screen content.
 * AUTO policy — Gemini can freely read what's on screen without approval.
 */
class ScreenReadTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "screen.read"
    override val description = """Read the current screen content of whatever app is in the foreground.
Returns all visible text, buttons, and UI elements with their positions.
Use this to understand what's on screen before deciding what action to take."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "wait_ms" to ParameterProperty("integer", "Optional delay in ms before reading (useful after screen transitions, default 0)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        if (!AccessibilityBridge.isServiceConnected) {
            return ToolResult.error("Accessibility service not connected. Enable CellClaw in Settings > Accessibility.")
        }

        return try {
            val requestId = AccessibilityBridge.createRequest()
            val waitMs = params["wait_ms"]?.jsonPrimitive?.longOrNull ?: 0

            val action = if (waitMs > 0) "wait_and_read" else "read_screen"
            val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("request_id", requestId)
                putExtra("action", action)
                if (waitMs > 0) putExtra("delay_ms", waitMs)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            val result = AccessibilityBridge.awaitResult(requestId, timeoutMs = if (waitMs > 0) waitMs + 5000 else 10_000)

            if (result.containsKey("error") && !result.containsKey("elements")) {
                ToolResult.error(result["error"]?.jsonPrimitive?.contentOrNull ?: "Read failed")
            } else {
                ToolResult.success(result)
            }
        } catch (e: Exception) {
            ToolResult.error("Screen read failed: ${e.message}")
        }
    }
}
