package com.cellclaw.tools

import android.util.Base64
import com.cellclaw.provider.*
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject

class VisionAnalyzeTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerManager: ProviderManager
) : Tool {
    override val name = "vision.analyze"
    override val description = """Analyze an image using AI vision capabilities.
Takes a file path to an image and a question about it.
Returns the AI's analysis of the image content.
Useful for understanding screenshots, photos, or any visual content."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "file_path" to ParameterProperty("string", "Path to the image file to analyze"),
            "question" to ParameterProperty("string", "Question or prompt about the image (e.g. 'describe what you see', 'what app is open?')")
        ),
        required = listOf("file_path", "question")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'file_path' parameter")
        val question = params["question"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'question' parameter")

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult.error("File not found: $filePath")
        }

        return try {
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mediaType = when {
                filePath.endsWith(".png", ignoreCase = true) -> "image/png"
                filePath.endsWith(".jpg", ignoreCase = true) || filePath.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                filePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
                else -> "image/png"
            }

            val request = CompletionRequest(
                systemPrompt = "You are a vision analysis assistant. Analyze the provided image and answer the user's question concisely.",
                messages = listOf(
                    Message(
                        role = Role.USER,
                        content = listOf(
                            ContentBlock.Image(base64Data = base64, mediaType = mediaType),
                            ContentBlock.Text(question)
                        )
                    )
                ),
                maxTokens = 1024
            )

            val response = providerManager.activeProvider().complete(request)
            val text = response.content
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("") { it.text }

            ToolResult.success(buildJsonObject {
                put("analysis", text)
                put("file_path", filePath)
            })
        } catch (e: Exception) {
            ToolResult.error("Vision analysis failed: ${e.message}")
        }
    }
}
