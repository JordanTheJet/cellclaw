package com.cellclaw.agent

import android.util.Log
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.config.Identity
import com.cellclaw.memory.ConversationStore
import com.cellclaw.provider.*
import com.cellclaw.provider.ProviderManager
import com.cellclaw.tools.ToolRegistry
import com.cellclaw.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentLoop @Inject constructor(
    private val providerManager: ProviderManager,
    private val toolRegistry: ToolRegistry,
    private val approvalQueue: ApprovalQueue,
    private val conversationStore: ConversationStore,
    private val identity: Identity,
    private val autonomyPolicy: AutonomyPolicy
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val conversationHistory = mutableListOf<Message>()
    private var currentJob: Job? = null

    fun submitMessage(text: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                _state.value = AgentState.THINKING
                conversationHistory.add(Message.user(text))
                conversationStore.addMessage("user", text)
                _events.emit(AgentEvent.UserMessage(text))
                runAgentLoop()
            } catch (e: CancellationException) {
                _state.value = AgentState.IDLE
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e::class.simpleName}: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                _state.value = AgentState.ERROR
                _events.emit(AgentEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        _state.value = AgentState.IDLE
    }

    fun pause() {
        _state.value = AgentState.PAUSED
    }

    fun resume() {
        if (_state.value == AgentState.PAUSED) {
            _state.value = AgentState.THINKING
            currentJob = scope.launch { runAgentLoop() }
        }
    }

    private suspend fun runAgentLoop() {
        var iterations = 0
        val maxIterations = 40

        while (iterations < maxIterations && _state.value == AgentState.THINKING) {
            iterations++

            val request = CompletionRequest(
                systemPrompt = identity.buildSystemPrompt(toolRegistry),
                messages = conversationHistory.toList(),
                tools = toolRegistry.toApiSchema(),
                maxTokens = 4096
            )

            val response = providerManager.activeProvider().complete(request)

            // Process response content
            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ContentBlock.ToolUse>()

            for (block in response.content) {
                when (block) {
                    is ContentBlock.Text -> {
                        if (!block.thought) {
                            textParts.add(block.text)
                            _events.emit(AgentEvent.AssistantText(block.text))
                        }
                    }
                    is ContentBlock.ToolUse -> toolCalls.add(block)
                    else -> {}
                }
            }

            // Add assistant message to history
            conversationHistory.add(Message(Role.ASSISTANT, response.content))

            if (textParts.isNotEmpty()) {
                conversationStore.addMessage("assistant", textParts.joinToString(""))
            }

            // If no tool calls, we're done
            Log.d(TAG, "Response: stopReason=${response.stopReason}, " +
                "toolCalls=${toolCalls.size}, blocks=${response.content.size}, " +
                "types=${response.content.map { it::class.simpleName }}")
            if (response.stopReason != StopReason.TOOL_USE || toolCalls.isEmpty()) {
                _state.value = AgentState.IDLE
                return
            }

            // Execute tool calls
            _state.value = AgentState.EXECUTING_TOOLS
            val toolResults = mutableListOf<ContentBlock>()

            for (call in toolCalls) {
                _events.emit(AgentEvent.ToolCallStart(call.name, call.input))

                val tool = toolRegistry.get(call.name)
                if (tool == null) {
                    toolResults.add(ContentBlock.ToolResult(
                        call.id, "Error: Unknown tool '${call.name}'", isError = true
                    ))
                    continue
                }

                // Check approval
                val approved = checkApproval(call.name, call.input)
                if (!approved) {
                    toolResults.add(ContentBlock.ToolResult(
                        call.id, "Tool execution denied by user", isError = true
                    ))
                    _events.emit(AgentEvent.ToolCallDenied(call.name))
                    continue
                }

                // Execute
                val result = try {
                    tool.execute(call.input)
                } catch (e: Exception) {
                    ToolResult.error("Execution failed: ${e.message}")
                }

                val resultStr = if (result.success) {
                    result.data?.toString() ?: "Success"
                } else {
                    result.error ?: "Unknown error"
                }

                toolResults.add(ContentBlock.ToolResult(
                    call.id, resultStr, isError = !result.success
                ))
                _events.emit(AgentEvent.ToolCallResult(call.name, result))
            }

            // Add tool results to history
            conversationHistory.add(Message(Role.USER, toolResults))
            _state.value = AgentState.THINKING
        }

        if (iterations >= maxIterations) {
            _events.emit(AgentEvent.Error("Max iterations ($maxIterations) reached"))
            _state.value = AgentState.IDLE
        }
    }

    private suspend fun checkApproval(toolName: String, params: JsonObject): Boolean {
        val policy = autonomyPolicy.getPolicy(toolName)
        return when (policy) {
            ToolApprovalPolicy.AUTO -> true
            ToolApprovalPolicy.DENY -> false
            ToolApprovalPolicy.ASK -> {
                _state.value = AgentState.WAITING_APPROVAL
                val request = ApprovalRequest(
                    toolName = toolName,
                    parameters = params,
                    description = "Allow $toolName?"
                )
                val result = approvalQueue.request(request)
                _state.value = AgentState.EXECUTING_TOOLS
                result == ApprovalResult.APPROVED
            }
        }
    }

    fun loadHistory() {
        scope.launch {
            conversationHistory.clear()
            val stored = conversationStore.getRecentMessages(50)
            for (msg in stored) {
                val role = if (msg.role == "user") Role.USER else Role.ASSISTANT
                conversationHistory.add(Message(role, listOf(ContentBlock.Text(msg.content))))
            }
        }
    }

    companion object {
        private const val TAG = "AgentLoop"
    }
}

enum class AgentState {
    IDLE, THINKING, EXECUTING_TOOLS, WAITING_APPROVAL, PAUSED, ERROR
}

sealed class AgentEvent {
    data class UserMessage(val text: String) : AgentEvent()
    data class AssistantText(val text: String) : AgentEvent()
    data class ToolCallStart(val name: String, val params: JsonObject) : AgentEvent()
    data class ToolCallResult(val name: String, val result: ToolResult) : AgentEvent()
    data class ToolCallDenied(val name: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}
