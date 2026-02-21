package com.cellclaw.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import javax.inject.Inject

class ScriptExecTool @Inject constructor() : Tool {
    override val name = "script.exec"
    override val description = "Execute a shell command on the device. Use with caution."
    override val parameters = ToolParameters(
        properties = mapOf(
            "command" to ParameterProperty("string", "Shell command to execute"),
            "timeout_seconds" to ParameterProperty("integer", "Execution timeout in seconds (default 30, max 120)")
        ),
        required = listOf("command")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val command = params["command"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'command' parameter")
        val timeoutSeconds = (params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30)
            .coerceIn(1, 120)

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutSeconds * 1000L) {
                    val process = ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()

                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    ToolResult.success(buildJsonObject {
                        put("exit_code", exitCode)
                        put("output", output.take(MAX_OUTPUT_LENGTH))
                        put("truncated", output.length > MAX_OUTPUT_LENGTH)
                    })
                }
            } catch (e: Exception) {
                ToolResult.error("Script execution failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_LENGTH = 10_000
    }
}
