package com.cellclaw.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cellclaw.BuildConfig
import com.cellclaw.agent.*
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.config.AppConfig
import com.cellclaw.config.Identity
import com.cellclaw.config.SecureKeyStore
import com.cellclaw.memory.*
import com.cellclaw.provider.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmailToolInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Mock tests (parameter validation, no UI) ──

    @Test
    fun missingToReturnsError() = runBlocking {
        val tool = EmailSendTool(context)
        val result = tool.execute(buildJsonObject {
            put("subject", "Test")
            put("body", "Test body")
        })
        assertFalse("Should fail without 'to'", result.success)
        assertTrue(result.error?.contains("to") == true)
    }

    @Test
    fun missingSubjectReturnsError() = runBlocking {
        val tool = EmailSendTool(context)
        val result = tool.execute(buildJsonObject {
            put("to", "test@example.com")
            put("body", "Test body")
        })
        assertFalse("Should fail without 'subject'", result.success)
        assertTrue(result.error?.contains("subject") == true)
    }

    @Test
    fun missingBodyReturnsError() = runBlocking {
        val tool = EmailSendTool(context)
        val result = tool.execute(buildJsonObject {
            put("to", "test@example.com")
            put("subject", "Test")
        })
        assertFalse("Should fail without 'body'", result.success)
        assertTrue(result.error?.contains("body") == true)
    }

    @Test
    fun validParamsReturnsSuccess() = runBlocking {
        val tool = EmailSendTool(context)
        val result = tool.execute(buildJsonObject {
            put("to", "test@example.com")
            put("subject", "Test Subject")
            put("body", "Test body text")
        })
        assertTrue("Should succeed with valid params", result.success)
        val data = result.data?.jsonObject
        assertNotNull("Should have data", data)
        assertTrue(data!!.containsKey("sent"))
        assertTrue(data.containsKey("to"))
        assertTrue(data.containsKey("subject"))
        assertTrue(data.containsKey("note"))
    }

    // ── Real send test (full intent + UI automation) ──

    @Test
    fun realEmailSendViaGmail() = runBlocking {
        // Wake screen + unlock
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
        delay(500)
        Runtime.getRuntime().exec(arrayOf("input", "swipe", "540", "2000", "540", "800", "300")).waitFor()
        delay(1000)

        // Send email via EmailSendTool (opens Gmail compose)
        val tool = EmailSendTool(context)
        val timestamp = System.currentTimeMillis()
        val result = tool.execute(buildJsonObject {
            put("to", "morepencils@gmail.com")
            put("subject", "CellClaw Test $timestamp")
            put("body", "Automated test email")
        })
        assertTrue("EmailSendTool should succeed", result.success)
        Log.d(TAG, "EmailSendTool result: ${result.data}")

        // Wait for intent to resolve
        delay(2000)

        val screenRead = UiAutomationScreenReadTool()
        val automate = UiAutomationAutomateTool()

        // Check if a chooser dialog appeared (Samsung may show one despite setPackage)
        val chooserRead = screenRead.execute(buildJsonObject {})
        assertTrue("Screen read should succeed", chooserRead.success)
        val chooserElements = chooserRead.data?.jsonObject?.get("elements")?.jsonArray
        Log.d(TAG, "Initial screen: ${chooserElements?.toString()?.take(2000)}")

        val hasChooser = chooserElements?.any { el ->
            val text = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            text.contains("Send using", ignoreCase = true) ||
                text.contains("Just once", ignoreCase = true)
        } ?: false

        if (hasChooser) {
            Log.d(TAG, "Chooser detected — tapping Gmail then Just once")
            automate.execute(buildJsonObject {
                put("action", "tap")
                put("text", "Gmail")
            })
            delay(500)
            automate.execute(buildJsonObject {
                put("action", "tap")
                put("text", "Just once")
            })
            delay(3000)
        } else {
            // Already in Gmail compose, just wait a bit more
            delay(1000)
        }

        // Read screen — verify compose view is visible
        val readResult = screenRead.execute(buildJsonObject {})
        assertTrue("Screen read should succeed", readResult.success)
        val elements = readResult.data?.jsonObject?.get("elements")?.jsonArray
        Log.d(TAG, "Compose screen: ${elements?.toString()?.take(2000)}")

        val composeVisible = elements?.any { el ->
            val text = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val desc = el.jsonObject["desc"]?.jsonPrimitive?.contentOrNull ?: ""
            text.contains("CellClaw Test", ignoreCase = true) ||
                text.contains("Compose", ignoreCase = true) ||
                desc.contains("Compose", ignoreCase = true) ||
                desc.contains("Send", ignoreCase = true)
        } ?: false
        assertTrue("Gmail compose should be visible", composeVisible)

        // Tap Send button
        val tapResult = automate.execute(buildJsonObject {
            put("action", "tap")
            put("text", "Send")
        })
        assertTrue("Tap Send should succeed", tapResult.success)
        Log.d(TAG, "Tap Send result: ${tapResult.data}")

        // Wait and verify compose dismissed
        delay(2000)
        val afterRead = screenRead.execute(buildJsonObject {})
        assertTrue("Post-send screen read should succeed", afterRead.success)
        val afterElements = afterRead.data?.jsonObject?.get("elements")?.jsonArray
        Log.d(TAG, "After-send elements: ${afterElements?.toString()?.take(2000)}")

        val composeDismissed = afterElements?.none { el ->
            val text = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            text.contains("CellClaw Test $timestamp", ignoreCase = true)
        } ?: true
        assertTrue("Compose should be dismissed after sending", composeDismissed)
    }

    // ── Agent loop test (CellClaw sends the email via Gemini) ──

    @Test
    fun agentSendsEmail() = runBlocking {
        assumeTrue("Gemini API key must be set", BuildConfig.GEMINI_API_KEY.isNotBlank())

        // Wake screen + unlock
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
        delay(500)
        Runtime.getRuntime().exec(arrayOf("input", "swipe", "540", "2000", "540", "800", "300")).waitFor()
        delay(1000)

        // Set up agent loop with email + UI automation tools
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
            EmailSendTool(context),
            UiAutomationScreenReadTool(),
            UiAutomationAutomateTool()
        )

        val approvalQueue = ApprovalQueue()
        val autonomyPolicy = AutonomyPolicy()
        autonomyPolicy.setPolicy("email.send", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("screen.read", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("app.automate", ToolApprovalPolicy.AUTO)

        val conversationStore = ConversationStore(StubMessageDao())
        val semanticMemory = SemanticMemory(StubMemoryFactDao())
        val identity = Identity(appConfig, semanticMemory)

        val agentLoop = AgentLoop(
            providerManager = providerManager,
            toolRegistry = toolRegistry,
            approvalQueue = approvalQueue,
            conversationStore = conversationStore,
            identity = identity,
            autonomyPolicy = autonomyPolicy
        )

        val events = mutableListOf<AgentEvent>()
        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        val timestamp = System.currentTimeMillis()
        agentLoop.submitMessage(
            """Send an email to morepencils@gmail.com with subject "CellClaw Agent Test $timestamp" and body "Hello from CellClaw agent!".
Use the email.send tool to open Gmail compose.
After it opens, use screen.read to see the Gmail compose screen.
If you see a chooser dialog (e.g. "Send using"), tap "Gmail" then "Just once" using app.automate.
Once you see the compose view, use app.automate to tap the "Send" button.
Then use screen.read to confirm the compose view is dismissed."""
        )

        withTimeout(60_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()

        // Log all events
        Log.d(TAG, "=== agentSendsEmail ===")
        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> Log.d(TAG, "USER: ${event.text.take(200)}")
                is AgentEvent.AssistantText -> Log.d(TAG, "ASSISTANT: ${event.text}")
                is AgentEvent.ToolCallStart -> Log.d(TAG, "TOOL CALL: ${event.name} params=${event.params.toString().take(500)}")
                is AgentEvent.ToolCallResult -> Log.d(TAG, "TOOL RESULT: ${event.name} success=${event.result.success} data=${event.result.data?.toString()?.take(1000)}")
                is AgentEvent.ToolCallDenied -> Log.d(TAG, "TOOL DENIED: ${event.name}")
                is AgentEvent.Error -> Log.d(TAG, "ERROR: ${event.message}")
            }
        }

        // Verify agent used email.send
        val emailResult = events.filterIsInstance<AgentEvent.ToolCallResult>()
            .firstOrNull { it.name == "email.send" }
        assertNotNull("Agent should have called email.send", emailResult)
        assertTrue("email.send should have succeeded", emailResult!!.result.success)

        // Verify agent used screen.read
        val screenReadResults = events.filterIsInstance<AgentEvent.ToolCallResult>()
            .filter { it.name == "screen.read" }
        assertTrue("Agent should have called screen.read", screenReadResults.isNotEmpty())

        // Verify agent tapped Send
        val tapResults = events.filterIsInstance<AgentEvent.ToolCallResult>()
            .filter { it.name == "app.automate" }
        assertTrue("Agent should have used app.automate", tapResults.isNotEmpty())

        assertTrue("Should have assistant response",
            events.any { it is AgentEvent.AssistantText })
    }

    companion object {
        private const val TAG = "EmailToolTest"
    }
}
