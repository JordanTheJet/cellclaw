package com.cellclaw.provider

import com.cellclaw.tools.ToolApiDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class OpenAIProvider @Inject constructor() : Provider {

    override val name = "openai"

    private var apiKey: String = ""
    private var model: String = DEFAULT_MODEL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // Maps sanitized names (dots replaced with underscores) back to original tool names
    private val toolNameMap = mutableMapOf<String, String>()

    fun configure(apiKey: String, model: String = DEFAULT_MODEL) {
        this.apiKey = apiKey
        this.model = model
    }

    override suspend fun complete(request: CompletionRequest): CompletionResponse =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(request)

            // Retry up to 3 times for transient errors (network + 5xx server errors)
            var lastException: Exception? = null
            for (attempt in 1..3) {
                try {
                    val httpRequest = Request.Builder()
                        .url(API_URL)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(httpRequest).execute()
                    val responseBody = response.body?.string()
                        ?: throw ProviderException("Empty response body")

                    // Retry on 5xx or 429
                    if (response.code in 500..599 || response.code == 429) {
                        lastException = ProviderException("API error ${response.code}: $responseBody")
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(1000L * attempt)
                            continue
                        }
                        throw lastException
                    }

                    if (!response.isSuccessful) {
                        throw ProviderException("API error ${response.code}: $responseBody")
                    }

                    return@withContext parseResponse(json.parseToJsonElement(responseBody).jsonObject)
                } catch (e: java.io.IOException) {
                    lastException = e
                    if (attempt < 3) {
                        kotlinx.coroutines.delay(1000L * attempt)
                        continue
                    }
                }
            }
            throw ProviderException("OpenAI API failed after 3 retries: ${lastException?.message}", lastException)
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        // Simplified non-streaming fallback
        try {
            val response = complete(request)
            for (block in response.content) {
                when (block) {
                    is ContentBlock.Text -> emit(StreamEvent.TextDelta(block.text))
                    is ContentBlock.ToolUse -> {
                        emit(StreamEvent.ToolUseStart(block.id, block.name))
                    }
                    else -> {}
                }
            }
            emit(StreamEvent.Complete(response))
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: CompletionRequest): String {
        toolNameMap.clear()
        val messages = buildJsonArray {
            // System message
            add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
            // Conversation messages
            for (msg in request.messages) {
                val toolResults = msg.content.filterIsInstance<ContentBlock.ToolResult>()
                val toolCalls = msg.content.filterIsInstance<ContentBlock.ToolUse>()

                if (toolResults.isNotEmpty()) {
                    // OpenAI expects each tool result as a separate message with role: "tool"
                    for (result in toolResults) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.toolUseId)
                            put("content", result.content)
                        })
                    }
                } else if (toolCalls.isNotEmpty()) {
                    // Assistant message with tool calls
                    add(buildJsonObject {
                        put("role", "assistant")
                        val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                            .joinToString("") { it.text }
                        if (textContent.isNotBlank()) {
                            put("content", textContent)
                        } else {
                            put("content", JsonNull)
                        }
                        putJsonArray("tool_calls") {
                            for (call in toolCalls) {
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name.replace(".", "_"))
                                        put("arguments", json.encodeToString(JsonObject.serializer(), call.input))
                                    }
                                })
                            }
                        }
                    })
                } else {
                    // Regular user or assistant message
                    add(buildJsonObject {
                        put("role", msg.role.name.lowercase())
                        val hasImages = msg.content.any { it is ContentBlock.Image }
                        if (hasImages) {
                            putJsonArray("content") {
                                for (block in msg.content) {
                                    when (block) {
                                        is ContentBlock.Text -> add(buildJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        })
                                        is ContentBlock.Image -> add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${block.mediaType};base64,${block.base64Data}")
                                            }
                                        })
                                        else -> {}
                                    }
                                }
                            }
                        } else {
                            val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                                .joinToString("") { it.text }
                            put("content", textContent)
                        }
                    })
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("max_tokens", request.maxTokens)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                val sanitized = tool.name.replace(".", "_")
                                toolNameMap[sanitized] = tool.name
                                put("name", sanitized)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", tool.inputSchema.type)
                                    putJsonObject("properties") {
                                        for ((key, prop) in tool.inputSchema.properties) {
                                            putJsonObject(key) {
                                                put("type", prop.type)
                                                put("description", prop.description)
                                            }
                                        }
                                    }
                                    if (tool.inputSchema.required.isNotEmpty()) {
                                        putJsonArray("required") {
                                            tool.inputSchema.required.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        })
    }

    private fun parseResponse(obj: JsonObject): CompletionResponse {
        val choices = obj["choices"]?.jsonArray ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val choice = choices.firstOrNull()?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val message = choice["message"]?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)

        val blocks = mutableListOf<ContentBlock>()

        // Text content
        message["content"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) blocks.add(ContentBlock.Text(it))
        }

        // Tool calls
        message["tool_calls"]?.jsonArray?.forEach { toolCallElement ->
            val toolCall = toolCallElement.jsonObject
            val function = toolCall["function"]?.jsonObject ?: return@forEach
            val input = try {
                json.parseToJsonElement(function["arguments"]?.jsonPrimitive?.content ?: "{}").jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }

            val rawName = function["name"]?.jsonPrimitive?.content ?: ""
            val toolName = toolNameMap[rawName] ?: rawName.replace("_", ".")
            blocks.add(ContentBlock.ToolUse(
                id = toolCall["id"]?.jsonPrimitive?.content ?: "",
                name = toolName,
                input = input
            ))
        }

        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
        val stopReason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        return CompletionResponse(blocks, stopReason)
    }

    companion object {
        const val API_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-5.2"
    }
}
