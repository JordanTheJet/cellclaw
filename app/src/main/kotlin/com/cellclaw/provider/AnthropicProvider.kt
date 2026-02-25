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

class AnthropicProvider @Inject constructor() : Provider {

    override val name = "anthropic"

    private var apiKey: String = ""
    private var model: String = DEFAULT_MODEL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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
            val body = buildRequestBody(request, stream = false)
            val httpRequest = buildHttpRequest(body)
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
                ?: throw ProviderException("Empty response body")

            if (!response.isSuccessful) {
                throw ProviderException("API error ${response.code}: $responseBody")
            }

            parseCompletionResponse(json.parseToJsonElement(responseBody).jsonObject)
        }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        val body = buildRequestBody(request, stream = true)
        val httpRequest = buildHttpRequest(body)
        val response = client.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            emit(StreamEvent.Error("API error ${response.code}: $errorBody"))
            return@flow
        }

        val reader = response.body?.byteStream()?.bufferedReader()
            ?: throw ProviderException("Empty response stream")

        val contentBlocks = mutableListOf<ContentBlock>()
        var currentToolId = ""
        var currentToolName = ""
        var currentToolInput = StringBuilder()
        var currentText = StringBuilder()
        var stopReason = StopReason.END_TURN

        try {
            var sseEvent = ""
            var sseData = StringBuilder()

            while (true) {
                val line = reader.readLine() ?: break

                when {
                    line.startsWith("event: ") -> sseEvent = line.removePrefix("event: ").trim()
                    line.startsWith("data: ") -> sseData.append(line.removePrefix("data: "))
                    line.isBlank() -> {
                        if (sseEvent.isNotEmpty() && sseData.isNotEmpty()) {
                            val data = sseData.toString()
                            when (sseEvent) {
                                "content_block_start" -> {
                                    val block = json.parseToJsonElement(data).jsonObject
                                    val cb = block["content_block"]?.jsonObject
                                    if (cb != null && cb["type"]?.jsonPrimitive?.content == "tool_use") {
                                        if (currentText.isNotEmpty()) {
                                            contentBlocks.add(ContentBlock.Text(currentText.toString()))
                                            currentText = StringBuilder()
                                        }
                                        currentToolId = cb["id"]?.jsonPrimitive?.content ?: ""
                                        currentToolName = cb["name"]?.jsonPrimitive?.content ?: ""
                                        currentToolInput = StringBuilder()
                                        emit(StreamEvent.ToolUseStart(currentToolId, currentToolName))
                                    }
                                }
                                "content_block_delta" -> {
                                    val block = json.parseToJsonElement(data).jsonObject
                                    val delta = block["delta"]?.jsonObject
                                    if (delta != null) {
                                        when (delta["type"]?.jsonPrimitive?.content) {
                                            "text_delta" -> {
                                                val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                                currentText.append(text)
                                                emit(StreamEvent.TextDelta(text))
                                            }
                                            "input_json_delta" -> {
                                                val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                                currentToolInput.append(partial)
                                                emit(StreamEvent.ToolUseInputDelta(partial))
                                            }
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (currentToolName.isNotEmpty()) {
                                        val inputJson = try {
                                            json.parseToJsonElement(currentToolInput.toString()).jsonObject
                                        } catch (_: Exception) {
                                            JsonObject(emptyMap())
                                        }
                                        contentBlocks.add(
                                            ContentBlock.ToolUse(currentToolId, currentToolName, inputJson)
                                        )
                                        currentToolId = ""
                                        currentToolName = ""
                                        currentToolInput = StringBuilder()
                                    }
                                }
                                "message_delta" -> {
                                    val block = json.parseToJsonElement(data).jsonObject
                                    val delta = block["delta"]?.jsonObject
                                    val reason = delta?.get("stop_reason")?.jsonPrimitive?.content
                                    stopReason = when (reason) {
                                        "tool_use" -> StopReason.TOOL_USE
                                        "max_tokens" -> StopReason.MAX_TOKENS
                                        else -> StopReason.END_TURN
                                    }
                                }
                                "message_stop" -> {
                                    if (currentText.isNotEmpty()) {
                                        contentBlocks.add(ContentBlock.Text(currentText.toString()))
                                    }
                                    emit(StreamEvent.Complete(
                                        CompletionResponse(contentBlocks, stopReason)
                                    ))
                                }
                                "error" -> {
                                    val block = json.parseToJsonElement(data).jsonObject
                                    val error = block["error"]?.jsonObject
                                    val message = error?.get("message")?.jsonPrimitive?.content ?: data
                                    emit(StreamEvent.Error(message))
                                }
                            }
                        }
                        sseEvent = ""
                        sseData = StringBuilder()
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: CompletionRequest, stream: Boolean): String {
        val messagesArray = buildJsonArray {
            for (msg in request.messages) {
                add(buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    putJsonArray("content") {
                        for (block in msg.content) {
                            add(contentBlockToJson(block))
                        }
                    }
                })
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("system", request.systemPrompt)
            put("messages", messagesArray)
            if (stream) put("stream", true)

            // Enable extended thinking
            putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", request.maxTokens.coerceAtMost(8000))
            }

            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(toolToJson(tool))
                    }
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun contentBlockToJson(block: ContentBlock): JsonObject = when (block) {
        is ContentBlock.Text -> if (block.thought) {
            buildJsonObject {
                put("type", "thinking")
                put("thinking", block.text)
                block.thoughtSignature?.let { put("signature", it) }
            }
        } else {
            buildJsonObject {
                put("type", "text")
                put("text", block.text)
            }
        }
        is ContentBlock.ToolUse -> buildJsonObject {
            put("type", "tool_use")
            put("id", block.id)
            put("name", block.name)
            put("input", block.input)
        }
        is ContentBlock.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", block.toolUseId)
            put("content", block.content)
            if (block.isError) put("is_error", true)
        }
        is ContentBlock.Image -> buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", block.mediaType)
                put("data", block.base64Data)
            }
        }
    }

    private fun toolToJson(tool: ToolApiDefinition): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("input_schema", buildJsonObject {
            put("type", tool.inputSchema.type)
            putJsonObject("properties") {
                for ((key, prop) in tool.inputSchema.properties) {
                    putJsonObject(key) {
                        put("type", prop.type)
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
        })
    }

    private fun buildHttpRequest(body: String): Request = Request.Builder()
        .url(API_URL)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", API_VERSION)
        .addHeader("content-type", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    private fun parseCompletionResponse(obj: JsonObject): CompletionResponse {
        val contentArray = obj["content"]?.jsonArray ?: JsonArray(emptyList())
        val blocks = contentArray.map { element ->
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "thinking" -> ContentBlock.Text(
                    text = block["thinking"]?.jsonPrimitive?.content ?: "",
                    thoughtSignature = block["signature"]?.jsonPrimitive?.content,
                    thought = true
                )
                "text" -> ContentBlock.Text(
                    block["text"]?.jsonPrimitive?.content ?: ""
                )
                "tool_use" -> ContentBlock.ToolUse(
                    id = block["id"]?.jsonPrimitive?.content ?: "",
                    name = block["name"]?.jsonPrimitive?.content ?: "",
                    input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                )
                else -> ContentBlock.Text("")
            }
        }

        val stopReason = when (obj["stop_reason"]?.jsonPrimitive?.content) {
            "tool_use" -> StopReason.TOOL_USE
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        val usageObj = obj["usage"]?.jsonObject
        val usage = usageObj?.let {
            Usage(
                inputTokens = it["input_tokens"]?.jsonPrimitive?.int ?: 0,
                outputTokens = it["output_tokens"]?.jsonPrimitive?.int ?: 0
            )
        }

        return CompletionResponse(blocks, stopReason, usage)
    }

    companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2025-04-14"
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
    }
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
