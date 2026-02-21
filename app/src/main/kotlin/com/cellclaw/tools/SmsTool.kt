package com.cellclaw.tools

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class SmsReadTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "sms.read"
    override val description = "Read SMS messages from the inbox. Can filter by sender or limit count."
    override val parameters = ToolParameters(
        properties = mapOf(
            "limit" to ParameterProperty("integer", "Maximum number of messages to return (default 10)"),
            "from" to ParameterProperty("string", "Filter by sender phone number or contact name"),
            "unread_only" to ParameterProperty("boolean", "Only return unread messages")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10
        val from = params["from"]?.jsonPrimitive?.contentOrNull
        val unreadOnly = params["unread_only"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val uri = Uri.parse("content://sms/inbox")
            var selection: String? = null
            var selectionArgs: Array<String>? = null

            if (from != null) {
                selection = "address LIKE ?"
                selectionArgs = arrayOf("%$from%")
            }
            if (unreadOnly) {
                selection = (selection?.let { "$it AND " } ?: "") + "read = 0"
            }

            val cursor = context.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date", "read"),
                selection, selectionArgs, "date DESC"
            )

            val messages = buildJsonArray {
                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        add(buildJsonObject {
                            put("id", it.getLong(0))
                            put("from", it.getString(1) ?: "unknown")
                            put("body", it.getString(2) ?: "")
                            put("date", it.getLong(3))
                            put("read", it.getInt(4) == 1)
                        })
                        count++
                    }
                }
            }

            ToolResult.success(messages)
        } catch (e: Exception) {
            ToolResult.error("Failed to read SMS: ${e.message}")
        }
    }
}

class SmsSendTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "sms.send"
    override val description = "Send an SMS text message to a phone number."
    override val parameters = ToolParameters(
        properties = mapOf(
            "to" to ParameterProperty("string", "Recipient phone number"),
            "message" to ParameterProperty("string", "Message text to send")
        ),
        required = listOf("to", "message")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val to = params["to"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'to' parameter")
        val message = params["message"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'message' parameter")

        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)

            ToolResult.success(buildJsonObject {
                put("sent", true)
                put("to", to)
                put("message_length", message.length)
                put("parts", parts.size)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to send SMS: ${e.message}")
        }
    }
}
