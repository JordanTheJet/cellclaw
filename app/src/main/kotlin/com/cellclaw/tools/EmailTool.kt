package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class EmailSendTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "email.send"
    override val description = "Send an email. Opens Gmail with the composed message. After calling this tool, use screen.read and app.automate (action=tap, text=\"Send\") to tap the Send button in Gmail to complete the send."
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
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                setPackage("com.google.android.gm")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("sent", true)
                put("to", to)
                put("subject", subject)
                put("note", "Gmail compose opened — use screen.read + app.automate to tap Send")
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to open email: ${e.message}")
        }
    }
}
