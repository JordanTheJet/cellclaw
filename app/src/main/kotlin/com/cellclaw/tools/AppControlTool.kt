package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            "package_name" to ParameterProperty("string", "Package name (e.g. com.whatsapp)"),
            "app_name" to ParameterProperty("string", "App name to search for (e.g. WhatsApp)")
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
    override val description = "Automate actions in other apps using AccessibilityService. Requires accessibility permission to be enabled."
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform",
                enum = listOf("tap", "type", "scroll", "back", "home", "recents", "read_screen")),
            "text" to ParameterProperty("string", "Text to type (for 'type' action), or text of element to tap"),
            "x" to ParameterProperty("integer", "X coordinate for tap"),
            "y" to ParameterProperty("integer", "Y coordinate for tap"),
            "direction" to ParameterProperty("string", "Scroll direction (for 'scroll' action)",
                enum = listOf("up", "down", "left", "right"))
        ),
        required = listOf("action")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'action' parameter")

        // The actual execution is delegated to CellClawAccessibility service
        return try {
            val intent = Intent("com.cellclaw.ACCESSIBILITY_ACTION").apply {
                putExtra("action", action)
                params["text"]?.jsonPrimitive?.contentOrNull?.let { putExtra("text", it) }
                params["x"]?.jsonPrimitive?.intOrNull?.let { putExtra("x", it) }
                params["y"]?.jsonPrimitive?.intOrNull?.let { putExtra("y", it) }
                params["direction"]?.jsonPrimitive?.contentOrNull?.let { putExtra("direction", it) }
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            ToolResult.success(buildJsonObject {
                put("action", action)
                put("status", "dispatched")
            })
        } catch (e: Exception) {
            ToolResult.error("Automation failed: ${e.message}")
        }
    }
}
