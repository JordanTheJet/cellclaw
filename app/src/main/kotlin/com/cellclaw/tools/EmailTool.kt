package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cellclaw.service.AccessibilityBridge
import com.cellclaw.service.CellClawAccessibility
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import javax.inject.Inject

class EmailSendTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "email.send"
    override val description = "Send an email via Gmail. Opens Gmail compose, fills in the fields, and automatically taps Send."
    override val parameters = ToolParameters(
        properties = mapOf(
            "to" to ParameterProperty("string", "Recipient email address"),
            "subject" to ParameterProperty("string", "Email subject line"),
            "body" to ParameterProperty("string", "Email body text")
        ),
        required = listOf("to", "subject", "body")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val to = params["to"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'to' parameter")
        val subject = params["subject"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'subject' parameter")
        val body = params["body"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'body' parameter")

        return try {
            // Open Gmail compose with pre-filled fields
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                setPackage("com.google.android.gm")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // Wait for Gmail to fully load
            delay(3000)

            // Tap the Send button via accessibility service
            val sendResult = tapSendButton()

            if (sendResult) {
                // Wait a moment then go back to CellClaw
                delay(1000)
                bringBackCellClaw()

                ToolResult.success(buildJsonObject {
                    put("sent", true)
                    put("to", to)
                    put("subject", subject)
                })
            } else {
                ToolResult.success(buildJsonObject {
                    put("sent", false)
                    put("to", to)
                    put("subject", subject)
                    put("error", "Gmail opened but could not tap Send button. Accessibility service may not be enabled.")
                })
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to send email: ${e.message}")
        }
    }

    private suspend fun tapSendButton(): Boolean {
        if (!AccessibilityBridge.isServiceConnected) return false

        // Try tapping the Send button by content description (Gmail uses a send icon)
        val attempts = listOf("Send", "send")
        for (buttonText in attempts) {
            val (receiver, deferred) = AccessibilityBridge.createReceiver()
            val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", receiver)
                putExtra("action", "tap")
                putExtra("text", buttonText)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            val result = AccessibilityBridge.awaitResult(deferred, 5000)
            val success = result["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (success) return true
        }
        return false
    }

    private fun bringBackCellClaw() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(it)
        }
    }
}
