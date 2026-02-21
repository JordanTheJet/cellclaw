package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class PhoneCallTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "phone.call"
    override val description = "Initiate a phone call to a given number."
    override val parameters = ToolParameters(
        properties = mapOf(
            "number" to ParameterProperty("string", "Phone number to call")
        ),
        required = listOf("number")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val number = params["number"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'number' parameter")

        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult.success(buildJsonObject {
                put("calling", number)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to initiate call: ${e.message}")
        }
    }
}

class PhoneLogTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "phone.log"
    override val description = "Read recent call history (incoming, outgoing, missed)."
    override val parameters = ToolParameters(
        properties = mapOf(
            "limit" to ParameterProperty("integer", "Maximum number of entries (default 20)"),
            "type" to ParameterProperty("string", "Filter by call type: incoming, outgoing, missed, or all",
                enum = listOf("incoming", "outgoing", "missed", "all"))
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "all"

        return try {
            var selection: String? = null
            when (type) {
                "incoming" -> selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
                "outgoing" -> selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
                "missed" -> selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}"
            }

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME
                ),
                selection, null,
                "${CallLog.Calls.DATE} DESC"
            )

            val logs = buildJsonArray {
                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        val callType = when (it.getInt(1)) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "unknown"
                        }
                        add(buildJsonObject {
                            put("number", it.getString(0) ?: "unknown")
                            put("type", callType)
                            put("date", it.getLong(2))
                            put("duration_seconds", it.getLong(3))
                            put("name", it.getString(4) ?: "")
                        })
                        count++
                    }
                }
            }

            ToolResult.success(logs)
        } catch (e: Exception) {
            ToolResult.error("Failed to read call log: ${e.message}")
        }
    }
}
