package com.cellclaw.provider

import com.cellclaw.tools.ToolApiDefinition
import com.cellclaw.tools.ToolRegistry
import kotlinx.serialization.json.*

/**
 * Converts tool definitions between different provider formats.
 */
object ToolSchema {

    /** Convert tools to Anthropic's tool format */
    fun toAnthropicFormat(tools: List<ToolApiDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                putJsonObject("input_schema") {
                    put("type", tool.inputSchema.type)
                    putJsonObject("properties") {
                        for ((key, prop) in tool.inputSchema.properties) {
                            putJsonObject(key) {
                                put("type", prop.type)
                                put("description", prop.description)
                                prop.enum?.let { enumValues ->
                                    putJsonArray("enum") { enumValues.forEach { add(it) } }
                                }
                            }
                        }
                    }
                    if (tool.inputSchema.required.isNotEmpty()) {
                        putJsonArray("required") { tool.inputSchema.required.forEach { add(it) } }
                    }
                }
            })
        }
    }

    /** Convert tools to OpenAI's function calling format */
    fun toOpenAIFormat(tools: List<ToolApiDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    putJsonObject("parameters") {
                        put("type", tool.inputSchema.type)
                        putJsonObject("properties") {
                            for ((key, prop) in tool.inputSchema.properties) {
                                putJsonObject(key) {
                                    put("type", prop.type)
                                    put("description", prop.description)
                                    prop.enum?.let { enumValues ->
                                        putJsonArray("enum") { enumValues.forEach { add(it) } }
                                    }
                                }
                            }
                        }
                        if (tool.inputSchema.required.isNotEmpty()) {
                            putJsonArray("required") { tool.inputSchema.required.forEach { add(it) } }
                        }
                    }
                }
            })
        }
    }
}
