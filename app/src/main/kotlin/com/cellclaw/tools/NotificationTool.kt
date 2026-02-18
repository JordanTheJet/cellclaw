package com.cellclaw.tools

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class NotificationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "notification.send"
    override val description = "Send a local notification to the user."
    override val parameters = ToolParameters(
        properties = mapOf(
            "title" to ParameterProperty("string", "Notification title"),
            "message" to ParameterProperty("string", "Notification body text"),
            "priority" to ParameterProperty("string", "Priority: low, default, high",
                enum = listOf("low", "default", "high"))
        ),
        required = listOf("title", "message")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'title'")
        val message = params["message"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'message'")
        val priority = when (params["priority"]?.jsonPrimitive?.contentOrNull) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        return try {
            val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val notification = NotificationCompat.Builder(context, CellClawApp.CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)

            ToolResult.success(buildJsonObject {
                put("sent", true)
                put("notification_id", notificationId)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to send notification: ${e.message}")
        }
    }
}
