package com.cellclaw.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for all CellClaw tools that the AI agent can invoke.
 */
interface Tool {
    /** Unique tool name (e.g. "sms.read", "phone.call") */
    val name: String

    /** Human-readable description for the AI to understand when to use this tool */
    val description: String

    /** JSON Schema describing the tool's parameters */
    val parameters: ToolParameters

    /** Whether this tool requires user approval before execution */
    val requiresApproval: Boolean

    /** Execute the tool with the given parameters */
    suspend fun execute(params: JsonObject): ToolResult
}

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ParameterProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

@Serializable
data class ParameterProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

@Serializable
data class ToolResult(
    val success: Boolean,
    val data: JsonElement? = null,
    val error: String? = null
) {
    companion object {
        fun success(data: JsonElement): ToolResult = ToolResult(success = true, data = data)
        fun error(message: String): ToolResult = ToolResult(success = false, error = message)
    }
}
