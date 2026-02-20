package com.cellclaw.provider

import com.cellclaw.tools.ToolApiDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over cloud AI providers (Anthropic, OpenAI, Google, etc.)
 */
interface Provider {
    val name: String

    /** Send a message and get a complete response (non-streaming) */
    suspend fun complete(request: CompletionRequest): CompletionResponse

    /** Send a message and get a streaming response */
    fun stream(request: CompletionRequest): Flow<StreamEvent>
}

data class CompletionRequest(
    val systemPrompt: String,
    val messages: List<Message>,
    val tools: List<ToolApiDefinition> = emptyList(),
    val maxTokens: Int = 4096
)

@Serializable
data class Message(
    val role: Role,
    val content: List<ContentBlock>
) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))
        fun toolResult(toolUseId: String, result: String) = Message(
            Role.USER,
            listOf(ContentBlock.ToolResult(toolUseId, result))
        )
    }
}

@Serializable
enum class Role {
    USER, ASSISTANT
}

@Serializable
sealed class ContentBlock {
    @Serializable
    data class Text(
        val text: String,
        val thoughtSignature: String? = null,
        val thought: Boolean = false
    ) : ContentBlock()

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
        val thoughtSignature: String? = null
    ) : ContentBlock()

    @Serializable
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    ) : ContentBlock()

    @Serializable
    data class Image(
        val base64Data: String,
        val mediaType: String = "image/png"
    ) : ContentBlock()
}

@Serializable
data class CompletionResponse(
    val content: List<ContentBlock>,
    val stopReason: StopReason,
    val usage: Usage? = null
)

@Serializable
enum class StopReason {
    END_TURN, TOOL_USE, MAX_TOKENS, ERROR
}

@Serializable
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ToolUseStart(val id: String, val name: String) : StreamEvent()
    data class ToolUseInputDelta(val delta: String) : StreamEvent()
    data class Complete(val response: CompletionResponse) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
