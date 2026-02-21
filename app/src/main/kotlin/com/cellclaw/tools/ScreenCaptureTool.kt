package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.cellclaw.service.AccessibilityBridge
import com.cellclaw.service.CellClawAccessibility
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject

class ScreenCaptureTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "screen.capture"
    override val description = """Capture a screenshot of the current screen.
Returns the file path to the saved PNG image. Optionally returns the base64-encoded image data.
Use vision.analyze to analyze the screenshot content with AI."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "include_base64" to ParameterProperty("boolean", "If true, include base64-encoded image data in the response (default false)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val includeBase64 = params["include_base64"]?.jsonPrimitive?.booleanOrNull ?: false

            val (receiver, deferred) = AccessibilityBridge.createReceiver()

            val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", receiver)
                putExtra("action", "screenshot")
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            val result = AccessibilityBridge.awaitResult(deferred, 15_000)
            val success = result["success"]?.jsonPrimitive?.booleanOrNull ?: false

            if (!success) {
                return ToolResult.error(result["error"]?.jsonPrimitive?.contentOrNull ?: "Screenshot failed")
            }

            val filePath = result["file_path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.error("No file path in screenshot result")

            ToolResult.success(buildJsonObject {
                put("file_path", filePath)
                put("message", "Screenshot saved")
                if (includeBase64) {
                    val file = File(filePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        put("size_bytes", bytes.size)
                    }
                }
            })
        } catch (e: Exception) {
            ToolResult.error("Screenshot failed: ${e.message}")
        }
    }
}
