package com.cellclaw.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class ClipboardReadTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "clipboard.read"
    override val description = "Read the current clipboard contents."
    override val parameters = ToolParameters()
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString() ?: ""

            ToolResult.success(buildJsonObject {
                put("text", text)
                put("has_content", text.isNotEmpty())
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to read clipboard: ${e.message}")
        }
    }
}

class ClipboardWriteTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "clipboard.write"
    override val description = "Write text to the clipboard."
    override val parameters = ToolParameters(
        properties = mapOf(
            "text" to ParameterProperty("string", "Text to copy to clipboard")
        ),
        required = listOf("text")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'text' parameter")

        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CellClaw", text))

            ToolResult.success(buildJsonObject {
                put("copied", true)
                put("length", text.length)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to write clipboard: ${e.message}")
        }
    }
}
