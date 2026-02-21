package com.cellclaw.tools

import com.cellclaw.service.CellClawNotificationListener
import kotlinx.serialization.json.*
import javax.inject.Inject

class NotificationListenTool @Inject constructor() : Tool {
    override val name = "notification.listen"
    override val description = """Monitor and query device notifications.
Actions:
- query: Get recent notifications, optionally filtered by app_package, since_minutes, limit
- add_trigger: Add an app package to auto-trigger agent when notification arrives
- remove_trigger: Remove an app from trigger list
- list_triggers: Show all trigger apps"""
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform",
                enum = listOf("query", "add_trigger", "remove_trigger", "list_triggers")),
            "app_package" to ParameterProperty("string", "Package name to filter or add/remove as trigger"),
            "since_minutes" to ParameterProperty("integer", "Only return notifications from the last N minutes"),
            "limit" to ParameterProperty("integer", "Max number of notifications to return (default 20)")
        ),
        required = listOf("action")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'action' parameter")

        return when (action) {
            "query" -> queryNotifications(params)
            "add_trigger" -> addTrigger(params)
            "remove_trigger" -> removeTrigger(params)
            "list_triggers" -> listTriggers()
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    private fun queryNotifications(params: JsonObject): ToolResult {
        val appPackage = params["app_package"]?.jsonPrimitive?.contentOrNull
        val sinceMinutes = params["since_minutes"]?.jsonPrimitive?.intOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20

        val cutoff = if (sinceMinutes != null) {
            System.currentTimeMillis() - sinceMinutes * 60_000L
        } else 0L

        val notifications = synchronized(CellClawNotificationListener.recentNotifications) {
            CellClawNotificationListener.recentNotifications.toList()
        }

        val filtered = notifications
            .filter { n -> appPackage == null || n.packageName == appPackage }
            .filter { n -> n.timestamp >= cutoff }
            .take(limit)

        return ToolResult.success(buildJsonObject {
            put("count", filtered.size)
            put("total_stored", notifications.size)
            putJsonArray("notifications") {
                for (n in filtered) {
                    add(buildJsonObject {
                        put("package", n.packageName)
                        put("title", n.title)
                        put("text", n.text)
                        put("timestamp", n.timestamp)
                    })
                }
            }
        })
    }

    private fun addTrigger(params: JsonObject): ToolResult {
        val pkg = params["app_package"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'app_package' for add_trigger")

        CellClawNotificationListener.triggerApps.add(pkg)
        return ToolResult.success(buildJsonObject {
            put("added", pkg)
            putJsonArray("triggers") {
                CellClawNotificationListener.triggerApps.forEach { add(it) }
            }
        })
    }

    private fun removeTrigger(params: JsonObject): ToolResult {
        val pkg = params["app_package"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'app_package' for remove_trigger")

        val removed = CellClawNotificationListener.triggerApps.remove(pkg)
        return ToolResult.success(buildJsonObject {
            put("removed", removed)
            put("package", pkg)
            putJsonArray("triggers") {
                CellClawNotificationListener.triggerApps.forEach { add(it) }
            }
        })
    }

    private fun listTriggers(): ToolResult {
        return ToolResult.success(buildJsonObject {
            putJsonArray("triggers") {
                CellClawNotificationListener.triggerApps.forEach { add(it) }
            }
        })
    }
}
