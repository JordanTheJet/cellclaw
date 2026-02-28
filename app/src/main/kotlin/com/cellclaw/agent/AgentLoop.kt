package com.cellclaw.agent

import android.content.Context
import android.util.Log
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.config.AppConfig
import com.cellclaw.config.Identity
import com.cellclaw.memory.ConversationStore
import com.cellclaw.provider.*
import com.cellclaw.provider.ProviderManager
import com.cellclaw.service.AccessibilityBridge
import com.cellclaw.tools.ToolRegistry
import com.cellclaw.tools.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AgentLoop @Inject constructor(
    private val providerManager: ProviderManager,
    private val toolRegistry: ToolRegistry,
    private val approvalQueue: ApprovalQueue,
    private val conversationStore: ConversationStore,
    private val identity: Identity,
    private val autonomyPolicy: AutonomyPolicy,
    private val appAccessPolicy: AppAccessPolicy,
    private val appConfig: AppConfig,
    private val heartbeatManagerProvider: Provider<HeartbeatManager>,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val conversationHistory = mutableListOf<Message>()
    private var currentJob: Job? = null
    private var isHeartbeatRun = false

    fun submitMessage(text: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                isHeartbeatRun = false
                _state.value = AgentState.THINKING
                conversationHistory.add(Message.user(text))
                conversationStore.addMessage("user", text)
                _events.emit(AgentEvent.UserMessage(text))
                val result = runAgentLoop()

                // Auto-activate heartbeat monitoring after any action-producing run
                if (result.hadToolCalls) {
                    val taskSummary = text.trim().take(120)
                    heartbeatManagerProvider.get().setActiveTaskContext(taskSummary)
                    Log.d(TAG, "Auto-activated heartbeat for: $taskSummary")
                }
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

    /**
     * Submit a heartbeat prompt. Unlike submitMessage(), this does NOT cancel the
     * current job. If the agent is busy, it reports SKIPPED_BUSY to the heartbeat manager.
     */
    fun submitHeartbeat(prompt: String) {
        if (_state.value != AgentState.IDLE) {
            heartbeatManagerProvider.get().onHeartbeatResult(HeartbeatResult.SKIPPED_BUSY)
            return
        }

        currentJob = scope.launch {
            try {
                isHeartbeatRun = true
                _state.value = AgentState.THINKING
                // Add to in-memory history but do NOT persist to ConversationStore
                conversationHistory.add(Message.user(prompt))
                _events.emit(AgentEvent.HeartbeatStart)
                runAgentLoop()
            } catch (e: CancellationException) {
                _state.value = AgentState.IDLE
                heartbeatManagerProvider.get().onHeartbeatResult(HeartbeatResult.SKIPPED_BUSY)
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e::class.simpleName}: ${e.message}")
                _state.value = AgentState.ERROR
                heartbeatManagerProvider.get().onHeartbeatResult(HeartbeatResult.ERROR)
            } finally {
                isHeartbeatRun = false
            }
        }
    }

    fun stop() {
        currentJob?.cancel()
        _state.value = AgentState.IDLE
    }

    fun clearContext() {
        stop()
        conversationHistory.clear()
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

    private data class LoopResult(val hadToolCalls: Boolean, val iterations: Int)

    private suspend fun runAgentLoop(): LoopResult {
        var iterations = 0
        val maxIterations = appConfig.maxIterations
        var hadToolCallsThisRun = false

        while ((maxIterations == 0 || iterations < maxIterations) && _state.value == AgentState.THINKING) {
            iterations++

            val request = CompletionRequest(
                systemPrompt = identity.buildSystemPrompt(toolRegistry),
                messages = pruneToolResults(conversationHistory),
                tools = toolRegistry.toApiSchema(),
                maxTokens = 4096
            )

            val response = providerManager.completeWithFailover(request)

            // Notify UI if a cross-provider failover occurred
            providerManager.lastFailoverEvent?.let { failover ->
                _events.emit(AgentEvent.ProviderFailover(failover.fromProvider, failover.toProvider, failover.reason))
            }

            // Process response content
            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ContentBlock.ToolUse>()

            for (block in response.content) {
                when (block) {
                    is ContentBlock.Text -> {
                        if (block.thought) {
                            if (!isHeartbeatRun) _events.emit(AgentEvent.ThinkingText(block.text))
                        } else {
                            textParts.add(block.text)
                            if (!isHeartbeatRun) _events.emit(AgentEvent.AssistantText(block.text))
                        }
                    }
                    is ContentBlock.ToolUse -> toolCalls.add(block)
                    else -> {}
                }
            }

            // Add assistant message to history
            conversationHistory.add(Message(Role.ASSISTANT, response.content))

            // Only persist to ConversationStore for non-heartbeat runs,
            // or for heartbeat runs where the agent took action
            if (textParts.isNotEmpty() && (!isHeartbeatRun || hadToolCallsThisRun)) {
                conversationStore.addMessage("assistant", textParts.joinToString(""))
            }

            // If no tool calls, we're done
            Log.d(TAG, "Response: stopReason=${response.stopReason}, " +
                "toolCalls=${toolCalls.size}, blocks=${response.content.size}, " +
                "types=${response.content.map { it::class.simpleName }}")
            if (response.stopReason != StopReason.TOOL_USE || toolCalls.isEmpty()) {
                // Handle heartbeat detection before transitioning to IDLE
                if (isHeartbeatRun) {
                    val detection = HeartbeatDetector.analyze(textParts, hadToolCallsThisRun)
                    _events.emit(AgentEvent.HeartbeatComplete(detection))

                    if (detection.isTaskComplete) {
                        heartbeatManagerProvider.get().clearActiveTaskContext()
                    }

                    // Prune HEARTBEAT_OK exchanges from history to avoid context pollution
                    if (detection.heartbeatResult == HeartbeatResult.OK_NOTHING_TO_DO) {
                        pruneLastHeartbeatExchange()
                    }

                    heartbeatManagerProvider.get().onHeartbeatResult(detection.heartbeatResult)
                }
                _state.value = AgentState.IDLE
                return LoopResult(hadToolCallsThisRun, iterations)
            }

            // Execute tool calls
            hadToolCallsThisRun = true
            _state.value = AgentState.EXECUTING_TOOLS
            val toolResults = mutableListOf<ContentBlock>()

            for (call in toolCalls) {
                if (!isHeartbeatRun) _events.emit(AgentEvent.ToolCallStart(call.name, call.input))

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
                    if (!isHeartbeatRun) _events.emit(AgentEvent.ToolCallDenied(call.name))
                    continue
                }

                // Check app access policy
                val appAccessError = checkAppAccess(call.name, call.input)
                if (appAccessError != null) {
                    toolResults.add(ContentBlock.ToolResult(
                        call.id, appAccessError, isError = true
                    ))
                    if (!isHeartbeatRun) _events.emit(AgentEvent.ToolCallDenied(call.name))
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
                if (!isHeartbeatRun) _events.emit(AgentEvent.ToolCallResult(call.name, result))
            }

            // Add tool results to history
            conversationHistory.add(Message(Role.USER, toolResults))
            _state.value = AgentState.THINKING
        }

        if (iterations >= maxIterations) {
            _events.emit(AgentEvent.Error("Max iterations ($maxIterations) reached"))
            if (isHeartbeatRun) {
                heartbeatManagerProvider.get().onHeartbeatResult(HeartbeatResult.ERROR)
            }
            _state.value = AgentState.IDLE
        }
        return LoopResult(hadToolCallsThisRun, iterations)
    }

    /**
     * Prune bulky tool results (screen.read, vision.analyze) from older messages
     * to reduce input tokens. Keeps the last [KEEP_FULL_RESULTS] full results and
     * replaces older ones with a short summary.
     */
    private fun pruneToolResults(history: List<Message>): List<Message> {
        // Find indices of USER messages that contain screen.read / vision.analyze results
        // by checking for ToolResult blocks with large content that look like screen data.
        val bulkyTools = setOf("screen.read", "screen.capture", "vision.analyze")

        // First pass: find which messages contain bulky tool results by matching
        // ToolResult blocks to preceding ToolUse blocks in assistant messages.
        data class BulkyLocation(val msgIndex: Int, val blockIndex: Int)
        val bulkyLocations = mutableListOf<BulkyLocation>()

        // Collect tool names from assistant ToolUse calls so we can map ToolResult IDs
        val toolIdToName = mutableMapOf<String, String>()
        for (msg in history) {
            if (msg.role == Role.ASSISTANT) {
                for (block in msg.content) {
                    if (block is ContentBlock.ToolUse && block.name in bulkyTools) {
                        toolIdToName[block.id] = block.name
                    }
                }
            }
        }

        // Find all ToolResult blocks that correspond to bulky tools
        for ((msgIndex, msg) in history.withIndex()) {
            if (msg.role != Role.USER) continue
            for ((blockIndex, block) in msg.content.withIndex()) {
                if (block is ContentBlock.ToolResult && block.toolUseId in toolIdToName) {
                    bulkyLocations.add(BulkyLocation(msgIndex, blockIndex))
                }
            }
        }

        // Keep the last KEEP_FULL_RESULTS; summarize everything older
        if (bulkyLocations.size <= KEEP_FULL_RESULTS) return history

        val toSummarize = bulkyLocations.dropLast(KEEP_FULL_RESULTS).toSet()
        val summarizeByMsg = toSummarize.groupBy({ it.msgIndex }, { it.blockIndex })

        return history.mapIndexed { msgIndex, msg ->
            val blocksToSummarize = summarizeByMsg[msgIndex] ?: return@mapIndexed msg
            val newContent = msg.content.mapIndexed { blockIndex, block ->
                if (blockIndex in blocksToSummarize && block is ContentBlock.ToolResult) {
                    val toolName = toolIdToName[block.toolUseId] ?: "tool"
                    ContentBlock.ToolResult(
                        toolUseId = block.toolUseId,
                        content = summarizeToolResult(toolName, block.content),
                        isError = block.isError
                    )
                } else {
                    block
                }
            }
            Message(msg.role, newContent)
        }
    }

    private fun summarizeToolResult(toolName: String, content: String): String {
        return when (toolName) {
            "screen.read" -> {
                // Extract package and element_count from the JSON if possible
                try {
                    val obj = json.parseToJsonElement(content) as? JsonObject
                    val pkg = obj?.get("package")?.jsonPrimitive?.content ?: "unknown"
                    val count = obj?.get("element_count")?.jsonPrimitive?.intOrNull ?: 0
                    "[screen.read: $pkg, $count elements — pruned to save tokens]"
                } catch (_: Exception) {
                    "[screen.read result pruned — ${content.length} chars]"
                }
            }
            "vision.analyze" -> {
                // Keep first 200 chars of the analysis as a summary
                val preview = content.take(200)
                if (content.length > 200) "$preview... [pruned]" else content
            }
            "screen.capture" -> {
                "[screen.capture result pruned]"
            }
            else -> "[tool result pruned — ${content.length} chars]"
        }
    }

    /**
     * Remove the last heartbeat prompt + response pair from conversation history.
     * This mirrors OpenClaw's transcript pruning for HEARTBEAT_OK responses.
     */
    private fun pruneLastHeartbeatExchange() {
        // Walk backwards and remove the heartbeat user message + assistant response.
        // There may be multiple messages if the agent did tool calls before responding.
        // For a simple HEARTBEAT_OK (no tools), it's just 2 messages: user + assistant.
        if (conversationHistory.size >= 2) {
            val last = conversationHistory.last()
            val secondLast = conversationHistory[conversationHistory.size - 2]
            if (last.role == Role.ASSISTANT && secondLast.role == Role.USER) {
                conversationHistory.removeAt(conversationHistory.size - 1)
                conversationHistory.removeAt(conversationHistory.size - 1)
            }
        }
    }

    /**
     * Check if the tool is allowed to interact with its target app.
     * Returns an error message if blocked, null if allowed.
     */
    private suspend fun checkAppAccess(toolName: String, params: JsonObject): String? {
        if (!appAccessPolicy.isAppTargetingTool(toolName)) return null

        val targetPackage = appAccessPolicy.resolvePackageFromParams(toolName, params)
            ?: if (appAccessPolicy.isForegroundTool(toolName)) {
                AccessibilityBridge.getForegroundPackage(context)
            } else null

        if (targetPackage == null) return null // Can't determine target, allow

        if (!appAccessPolicy.isAppAllowed(targetPackage)) {
            return "Access to $targetPackage is blocked by app access policy. " +
                "The user has restricted CellClaw from interacting with this app."
        }
        return null
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
        /** Number of recent screen.read/vision.analyze results to keep in full. */
        private const val KEEP_FULL_RESULTS = 2
    }
}

enum class AgentState {
    IDLE, THINKING, EXECUTING_TOOLS, WAITING_APPROVAL, PAUSED, ERROR
}

sealed class AgentEvent {
    data class UserMessage(val text: String) : AgentEvent()
    data class AssistantText(val text: String) : AgentEvent()
    data class ThinkingText(val text: String) : AgentEvent()
    data class ToolCallStart(val name: String, val params: JsonObject) : AgentEvent()
    data class ToolCallResult(val name: String, val result: ToolResult) : AgentEvent()
    data class ToolCallDenied(val name: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data object HeartbeatStart : AgentEvent()
    data class HeartbeatComplete(val detection: HeartbeatDetector.DetectionResult) : AgentEvent()
    data class ProviderFailover(val fromProvider: String, val toProvider: String, val reason: String) : AgentEvent()
}
