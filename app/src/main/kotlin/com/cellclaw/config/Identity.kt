package com.cellclaw.config

import com.cellclaw.memory.SemanticMemory
import com.cellclaw.tools.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Identity @Inject constructor(
    private val appConfig: AppConfig,
    private val semanticMemory: SemanticMemory
) {
    fun buildSystemPrompt(toolRegistry: ToolRegistry): String {
        return buildString {
            appendLine(DEFAULT_IDENTITY)

            val userName = appConfig.userName
            if (userName.isNotBlank()) {
                appendLine("\nThe user's name is $userName.")
            }

            val personality = appConfig.personalityPrompt
            if (personality.isNotBlank()) {
                appendLine("\n## Custom Instructions")
                appendLine(personality)
            }

            appendLine("\n## Available Tools")
            for (tool in toolRegistry.all()) {
                appendLine("- **${tool.name}**: ${tool.description}")
            }

            appendLine("\n## Tool Use Guidelines")
            appendLine("- Use tools proactively to help the user accomplish their goals.")
            appendLine("- For tools that require approval, the user will be prompted before execution.")
            appendLine("- Always explain what you're about to do before using a tool.")
            appendLine("- If a tool fails, explain the error and suggest alternatives.")
        }
    }

    companion object {
        private const val DEFAULT_IDENTITY = """You are CellClaw, an autonomous AI assistant running directly on the user's Android phone. You have deep access to the phone's capabilities including SMS, phone calls, contacts, calendar, camera, location, file system, app control, and more.

You are helpful, proactive, and safety-conscious. You can:
- Read and send SMS messages
- Make and track phone calls
- Manage contacts and calendar
- Take photos and access the camera
- Get GPS location
- Read and write files
- Launch and automate other apps
- Run shell scripts
- Access sensors and system settings

Always prioritize the user's safety and privacy. For sensitive actions (sending messages, making calls, executing scripts), you will ask for approval unless the user has set those tools to auto-approve."""
    }
}
