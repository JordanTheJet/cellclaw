package com.cellclaw.agent

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cellclaw.BuildConfig
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.config.AppConfig
import com.cellclaw.config.Identity
import com.cellclaw.config.SecureKeyStore
import com.cellclaw.memory.*
import com.cellclaw.provider.*
import com.cellclaw.tools.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test that runs the full agent loop:
 * User message -> Gemini API -> Tool call -> Tool execution on device -> Response
 */
@RunWith(AndroidJUnit4::class)
class AgentLoopE2ETest {

    private lateinit var agentLoop: AgentLoop
    private lateinit var approvalQueue: ApprovalQueue
    private val events = mutableListOf<AgentEvent>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        assumeTrue("Gemini API key must be set", BuildConfig.GEMINI_API_KEY.isNotBlank())

        val geminiProvider = GeminiProvider()
        geminiProvider.configure(BuildConfig.GEMINI_API_KEY, "gemini-2.5-flash")

        val appConfig = AppConfig(context)
        appConfig.providerType = "gemini"
        appConfig.model = "gemini-2.5-flash"

        val secureKeyStore = SecureKeyStore(context)
        secureKeyStore.storeApiKey("gemini", BuildConfig.GEMINI_API_KEY)

        val providerManager = ProviderManager(
            appConfig = appConfig,
            secureKeyStore = secureKeyStore,
            anthropicProvider = AnthropicProvider(),
            openAIProvider = OpenAIProvider(),
            geminiProvider = geminiProvider
        )

        val toolRegistry = ToolRegistry()
        toolRegistry.register(
            BrowserSearchTool(context),
            BrowserOpenTool(context),
            EmailSendTool(context),
            ClipboardWriteTool(context),
            ClipboardReadTool(context)
        )

        approvalQueue = ApprovalQueue()

        val autonomyPolicy = AutonomyPolicy()
        autonomyPolicy.setPolicy("browser.search", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("browser.open", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("clipboard.write", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("clipboard.read", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("email.send", ToolApprovalPolicy.AUTO)

        val conversationStore = ConversationStore(StubMessageDao())
        val semanticMemory = SemanticMemory(StubMemoryFactDao())
        val identity = Identity(appConfig, semanticMemory)

        agentLoop = AgentLoop(
            providerManager = providerManager,
            toolRegistry = toolRegistry,
            approvalQueue = approvalQueue,
            conversationStore = conversationStore,
            identity = identity,
            autonomyPolicy = autonomyPolicy
        )

        events.clear()
    }

    @Test
    fun browserSearchE2E() = runBlocking {
        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        agentLoop.submitMessage("Search the web for best date spots in Boston")

        withTimeout(30_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()
        logEvents("browserSearchE2E")

        assertTrue("Should have user message",
            events.any { it is AgentEvent.UserMessage })

        val toolCalls = events.filterIsInstance<AgentEvent.ToolCallStart>()
        assertTrue("Should have tool calls", toolCalls.isNotEmpty())
        assertTrue("Should call browser tool",
            toolCalls.any { it.name.contains("browser") })

        val results = events.filterIsInstance<AgentEvent.ToolCallResult>()
        assertTrue("Should have tool results", results.isNotEmpty())
        assertTrue("Browser tool should succeed",
            results.any { it.name.contains("browser") && it.result.success })

        assertTrue("Should have assistant response",
            events.any { it is AgentEvent.AssistantText })
    }

    @Test
    fun emailSendE2E() = runBlocking {
        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        agentLoop.submitMessage(
            "Send an email to morepencils@gmail.com with subject 'Hello from CellClaw' " +
            "and body 'This is a live end-to-end test from the CellClaw agent loop running on a real phone!'"
        )

        withTimeout(30_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()
        logEvents("emailSendE2E")

        val toolCalls = events.filterIsInstance<AgentEvent.ToolCallStart>()
        assertTrue("Should have tool calls", toolCalls.isNotEmpty())
        assertTrue("Should call email tool",
            toolCalls.any { it.name.contains("email") })

        val results = events.filterIsInstance<AgentEvent.ToolCallResult>()
        assertTrue("Should have tool results", results.isNotEmpty())
        assertTrue("Email tool should succeed",
            results.any { it.name.contains("email") && it.result.success })
    }

    @Test
    fun clipboardWriteE2E() = runBlocking {
        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        agentLoop.submitMessage("Copy the text 'CellClaw is alive!' to my clipboard")

        withTimeout(30_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()
        logEvents("clipboardWriteE2E")

        val toolCalls = events.filterIsInstance<AgentEvent.ToolCallStart>()
        assertTrue("Should have tool calls", toolCalls.isNotEmpty())
        assertTrue("Should call clipboard tool",
            toolCalls.any { it.name.contains("clipboard") })

        val results = events.filterIsInstance<AgentEvent.ToolCallResult>()
        assertTrue("Should have tool results", results.isNotEmpty())
    }

    private fun logEvents(testName: String) {
        Log.d("E2E", "=== $testName ===")
        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> Log.d("E2E", "USER: ${event.text}")
                is AgentEvent.AssistantText -> Log.d("E2E", "ASSISTANT: ${event.text}")
                is AgentEvent.ToolCallStart -> Log.d("E2E", "TOOL CALL: ${event.name} ${event.params}")
                is AgentEvent.ToolCallResult -> Log.d("E2E", "TOOL RESULT: ${event.name} success=${event.result.success}")
                is AgentEvent.ToolCallDenied -> Log.d("E2E", "TOOL DENIED: ${event.name}")
                is AgentEvent.Error -> Log.d("E2E", "ERROR: ${event.message}")
            }
        }
    }
}

// Stub DAOs for test (no Room DB needed)
private class StubMessageDao : MessageDao {
    override suspend fun insert(message: MessageEntity): Long = 1
    override suspend fun getRecent(conversationId: String, limit: Int): List<MessageEntity> = emptyList()
    override suspend fun getAll(conversationId: String): List<MessageEntity> = emptyList()
    override suspend fun clearConversation(conversationId: String) {}
    override suspend fun count(conversationId: String): Int = 0
}

private class StubMemoryFactDao : MemoryFactDao {
    override suspend fun upsert(fact: MemoryFactEntity): Long = 1
    override suspend fun getByCategory(category: String): List<MemoryFactEntity> = emptyList()
    override suspend fun getAll(): List<MemoryFactEntity> = emptyList()
    override suspend fun search(query: String): List<MemoryFactEntity> = emptyList()
    override suspend fun delete(id: Long) {}
}
