package com.cellclaw.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all available tools. Tools register themselves here
 * and the agent loop queries this to know what tools are available.
 */
@Singleton
class ToolRegistry @Inject constructor() {

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun register(vararg toolList: Tool) {
        toolList.forEach { register(it) }
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    /** Convert all registered tools to the format needed by AI provider APIs */
    fun toApiSchema(): List<ToolApiDefinition> = tools.values.map { tool ->
        ToolApiDefinition(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.parameters
        )
    }
}

data class ToolApiDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolParameters
)
