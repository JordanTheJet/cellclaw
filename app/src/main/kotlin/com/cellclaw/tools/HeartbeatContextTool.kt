package com.cellclaw.tools

import com.cellclaw.agent.HeartbeatManager
import kotlinx.serialization.json.*
import javax.inject.Inject

class HeartbeatContextTool @Inject constructor(
    private val heartbeatManager: HeartbeatManager
) : Tool {
    override val name = "heartbeat.context"
    override val description = """Set or clear the active task context for the heartbeat system.
When you start a long-running task that requires periodic monitoring (games, waiting for responses, watching for changes, etc.), call this with action "set" and a brief description.
The heartbeat will periodically wake you up to check on the task.
Call with action "clear" when the task is complete."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action: 'set' to activate monitoring, 'clear' to deactivate",
                enum = listOf("set", "clear")),
            "context" to ParameterProperty("string", "Brief description of the active task (required for 'set')")
        ),
        required = listOf("action")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'action' parameter")

        return when (action) {
            "set" -> {
                val context = params["context"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.error("Missing 'context' for set action")
                heartbeatManager.setActiveTaskContext(context)
                ToolResult.success(buildJsonObject {
                    put("active", true)
                    put("context", context)
                    put("message", "Heartbeat monitoring activated. Will periodically check on: $context")
                })
            }
            "clear" -> {
                heartbeatManager.clearActiveTaskContext()
                ToolResult.success(buildJsonObject {
                    put("active", false)
                    put("message", "Heartbeat monitoring deactivated.")
                })
            }
            else -> ToolResult.error("Unknown action: $action. Use 'set' or 'clear'.")
        }
    }
}
