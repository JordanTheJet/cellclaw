package com.cellclaw.provider

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

class GeminiProvider @Inject constructor() : Provider {

    override val name = "gemini"

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

    fun configure(apiKey: String, model: String = DEFAULT_MODEL) {
        this.apiKey = apiKey
        this.model = model
    }

    override suspend fun complete(request: CompletionRequest): CompletionResponse =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(request)
            val url = "$API_URL/$model:generateContent?key=$apiKey"
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: throw ProviderException("Empty response body")

            if (!response.isSuccessful) {
                throw ProviderException("Gemini API error ${response.code}: $responseBody")
            }

            parseResponse(json.parseToJsonElement(responseBody).jsonObject)
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        // Use non-streaming fallback
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
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            // System instruction
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", request.systemPrompt) })
                }
            }

            // Contents (conversation messages)
            putJsonArray("contents") {
                for (msg in request.messages) {
                    add(buildJsonObject {
                        put("role", if (msg.role == Role.USER) "user" else "model")
                        putJsonArray("parts") {
                            for (block in msg.content) {
                                when (block) {
                                    is ContentBlock.Text -> add(buildJsonObject {
                                        put("text", block.text)
                                    })
                                    is ContentBlock.ToolUse -> add(buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", block.name)
                                            put("args", block.input)
                                        }
                                    })
                                    is ContentBlock.ToolResult -> add(buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", block.toolUseId)
                                            putJsonObject("response") {
                                                put("content", block.content)
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    })
                }
            }

            // Tools (function declarations)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("function_declarations") {
                            for (tool in request.tools) {
                                add(buildJsonObject {
                                    put("name", tool.name.replace(".", "_"))
                                    put("description", tool.description)
                                    putJsonObject("parameters") {
                                        put("type", "OBJECT")
                                        putJsonObject("properties") {
                                            for ((key, prop) in tool.inputSchema.properties) {
                                                putJsonObject(key) {
                                                    put("type", prop.type.uppercase())
                                                    put("description", prop.description)
                                                    prop.enum?.let { enumValues ->
                                                        putJsonArray("enum") {
                                                            enumValues.forEach { add(it) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (tool.inputSchema.required.isNotEmpty()) {
                                            putJsonArray("required") {
                                                tool.inputSchema.required.forEach { add(it) }
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    })
                }
            }

            // Generation config
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens)
            }
        })
    }

    private fun parseResponse(obj: JsonObject): CompletionResponse {
        val candidates = obj["candidates"]?.jsonArray
            ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val candidate = candidates.firstOrNull()?.jsonObject
            ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val content = candidate["content"]?.jsonObject
        val parts = content?.get("parts")?.jsonArray ?: JsonArray(emptyList())

        val blocks = mutableListOf<ContentBlock>()
        var hasToolCalls = false

        for (partElement in parts) {
            val part = partElement.jsonObject

            // Text part
            part["text"]?.jsonPrimitive?.contentOrNull?.let {
                if (it.isNotBlank()) blocks.add(ContentBlock.Text(it))
            }

            // Function call part
            part["functionCall"]?.jsonObject?.let { fc ->
                hasToolCalls = true
                val fcName = fc["name"]?.jsonPrimitive?.content ?: ""
                // Convert back from underscore to dot notation
                val toolName = fcName.replace("_", ".")
                val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                blocks.add(ContentBlock.ToolUse(
                    id = "gemini_${System.currentTimeMillis()}_${fcName}",
                    name = toolName,
                    input = args
                ))
            }
        }

        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val stopReason = when {
            hasToolCalls -> StopReason.TOOL_USE
            finishReason == "MAX_TOKENS" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        return CompletionResponse(blocks, stopReason)
    }

    companion object {
        const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val DEFAULT_MODEL = "gemini-2.0-flash"
    }
}
