package com.cellclaw.provider

import android.util.Log
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

    /** Fallback models to try when the primary model returns 404 */
    private val fallbackModels = listOf(
        "gemini-3-flash-preview",
        "gemini-3.1-pro-preview",
        "gemini-2.5-flash",
        "gemini-2.5-pro"
    )

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

            // Build list of models to try: primary first, then fallbacks
            val modelsToTry = mutableListOf(model)
            for (fb in fallbackModels) {
                if (fb != model) modelsToTry.add(fb)
            }

            Log.d(TAG, "Request body size: ${body.length} chars, messages: ${request.messages.size}")

            var lastError: String? = null
            for (tryModel in modelsToTry) {
                val url = "$API_URL/$tryModel:generateContent?key=$apiKey"

                // Retry up to 3 times for transient network errors (DNS, connection reset, etc.)
                var response: okhttp3.Response? = null
                var responseBody: String? = null
                var networkError: Exception? = null

                for (attempt in 1..3) {
                    try {
                        val httpRequest = Request.Builder()
                            .url(url)
                            .addHeader("Content-Type", "application/json")
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()

                        response = client.newCall(httpRequest).execute()
                        responseBody = response.body?.string()
                            ?: throw ProviderException("Empty response body")
                        networkError = null
                        break
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "Network error (attempt $attempt/3) for $tryModel: ${e::class.simpleName}: ${e.message}")
                        networkError = e
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(1000L * attempt)
                        }
                    }
                }

                if (networkError != null) {
                    throw ProviderException("Network error after 3 retries: ${networkError.message}", networkError)
                }

                val resp = response!!
                val respBody = responseBody!!

                if (resp.isSuccessful) {
                    if (tryModel != model) {
                        Log.w(TAG, "Model $model unavailable, fell back to $tryModel (not locking in — may be temporary rate limit)")
                    }
                    Log.d(TAG, "Raw response (truncated): ${respBody.take(6000)}")
                    return@withContext parseResponse(json.parseToJsonElement(respBody).jsonObject)
                }

                // If 404 (model not found) or 429 (rate limited), try next fallback
                if (resp.code == 404 || resp.code == 429) {
                    val reason = if (resp.code == 429) "rate limited" else "not found"
                    Log.w(TAG, "Model $tryModel $reason (${resp.code}), trying next fallback...")
                    lastError = "Gemini API error ${resp.code}: $respBody"
                    continue
                }

                // Other errors (401, 500, etc.) — don't fallback, throw immediately
                Log.e(TAG, "Gemini API error ${resp.code}: ${respBody.take(2000)}")
                throw ProviderException("Gemini API error ${resp.code}: $respBody")
            }

            throw ProviderException("All models unavailable. Last error: $lastError")
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        // Use non-streaming fallback
        try {
            val response = complete(request)
            for (block in response.content) {
                when (block) {
                    is ContentBlock.Text -> if (!block.thought) emit(StreamEvent.TextDelta(block.text))
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

    // Maps sanitized Gemini function names back to original dotted tool names
    private val toolNameMap = mutableMapOf<String, String>()

    private fun buildRequestBody(request: CompletionRequest): String {
        toolNameMap.clear()
        // Debug: log thought signatures in conversation history
        for ((i, msg) in request.messages.withIndex()) {
            for (block in msg.content) {
                when (block) {
                    is ContentBlock.ToolUse -> Log.d(TAG, "Msg[$i] ToolUse: ${block.name}, sig=${block.thoughtSignature?.take(50) ?: "null"}")
                    is ContentBlock.Text -> if (block.thought || block.thoughtSignature != null) {
                        Log.d(TAG, "Msg[$i] Thought: thought=${block.thought}, sig=${block.thoughtSignature?.take(50) ?: "null"}, textLen=${block.text.length}")
                    }
                    else -> {}
                }
            }
        }
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
                                        if (block.thought) {
                                            // Thought parts: echo with thought=true, signature, empty text
                                            put("text", "")
                                            put("thought", true)
                                            block.thoughtSignature?.let { put("thoughtSignature", it) }
                                        } else {
                                            put("text", block.text)
                                            block.thoughtSignature?.let { put("thoughtSignature", it) }
                                        }
                                    })
                                    is ContentBlock.ToolUse -> add(buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", block.name.replace(".", "_"))
                                            put("args", block.input)
                                        }
                                        block.thoughtSignature?.let { put("thoughtSignature", it) }
                                    })
                                    is ContentBlock.ToolResult -> add(buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", block.toolUseId.replace(".", "_"))
                                            putJsonObject("response") {
                                                put("content", block.content)
                                            }
                                        }
                                    })
                                    is ContentBlock.Image -> add(buildJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", block.mediaType)
                                            put("data", block.base64Data)
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
                                val sanitized = tool.name.replace(".", "_")
                                toolNameMap[sanitized] = tool.name
                                add(buildJsonObject {
                                    put("name", sanitized)
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
            val signature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull

            // Text part
            val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull ?: false
            part["text"]?.jsonPrimitive?.contentOrNull?.let {
                // Always include thought parts (even if blank) when they have a signature
                if (it.isNotBlank() || (isThought && signature != null)) {
                    blocks.add(ContentBlock.Text(it, thoughtSignature = signature, thought = isThought))
                }
            }

            // Function call part
            part["functionCall"]?.jsonObject?.let { fc ->
                hasToolCalls = true
                val fcName = fc["name"]?.jsonPrimitive?.content ?: ""
                // Resolve using map first, then try suffix match, then naive replace
                val toolName = toolNameMap[fcName]
                    ?: toolNameMap.values.firstOrNull { it.endsWith(".$fcName") || it.endsWith(".${fcName.replace("_", ".")}") }
                    ?: fcName.replace("_", ".")
                val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                blocks.add(ContentBlock.ToolUse(
                    id = fcName,  // Gemini uses function name as identifier
                    name = toolName,
                    input = args,
                    thoughtSignature = signature
                ))
            }
        }

        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val stopReason = when {
            hasToolCalls -> StopReason.TOOL_USE
            finishReason == "MAX_TOKENS" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        Log.d(TAG, "parseResponse: ${parts.size} parts, ${blocks.size} blocks, " +
            "hasToolCalls=$hasToolCalls, finishReason=$finishReason, stopReason=$stopReason, " +
            "types=${blocks.map { it::class.simpleName }}")

        return CompletionResponse(blocks, stopReason)
    }

    companion object {
        private const val TAG = "GeminiProvider"
        const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val DEFAULT_MODEL = "gemini-3-flash-preview"
    }
}
