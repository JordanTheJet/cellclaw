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
 * Live test: launches Tinder, reads the screen, and performs actions
 * through the full CellClaw agent loop with Gemini.
 *
 * Note: `am instrument` force-stops the app, which kills the accessibility
 * service. We use UiAutomation shell tools (uiautomator dump, input commands)
 * instead, which work without the accessibility service running.
 */
@RunWith(AndroidJUnit4::class)
class TinderLiveTest {

    private lateinit var agentLoop: AgentLoop
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
            AppLaunchTool(context),
            UiAutomationAutomateTool(),  // Uses UiAutomation (works cross-process)
            UiAutomationScreenReadTool(), // Uses UiAutomation (works cross-process)
            BrowserSearchTool(context),
            BrowserOpenTool(context)
        )

        val approvalQueue = ApprovalQueue()

        // All tools AUTO for live testing
        val autonomyPolicy = AutonomyPolicy()
        autonomyPolicy.setPolicy("app.launch", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("app.automate", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("screen.read", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("browser.search", ToolApprovalPolicy.AUTO)
        autonomyPolicy.setPolicy("browser.open", ToolApprovalPolicy.AUTO)

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
    fun launchTinderAndReadScreen() = runBlocking {
        // Wake screen + unlock via shell
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
        delay(500)
        Runtime.getRuntime().exec(arrayOf("input", "swipe", "540", "2000", "540", "800", "300")).waitFor()
        delay(1000)

        // Launch Tinder via shell am start (avoids package visibility issues)
        Runtime.getRuntime().exec(arrayOf(
            "am", "start", "-n", "com.tinder/com.tinder.launch.internal.activities.LoginActivity"
        )).waitFor()

        // Wait for Tinder to load
        delay(5000)

        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        // Ask the agent to read the screen
        agentLoop.submitMessage("Read what's currently on the screen and describe what you see. Use the screen.read tool.")

        withTimeout(30_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()

        Log.d(TAG, "=== launchTinderAndReadScreen ===")
        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> Log.d(TAG, "USER: ${event.text}")
                is AgentEvent.AssistantText -> Log.d(TAG, "ASSISTANT: ${event.text}")
                is AgentEvent.ToolCallStart -> Log.d(TAG, "TOOL CALL: ${event.name} params=${event.params.toString().take(500)}")
                is AgentEvent.ToolCallResult -> Log.d(TAG, "TOOL RESULT: ${event.name} success=${event.result.success} data=${event.result.data?.toString()?.take(500)}")
                is AgentEvent.ToolCallDenied -> Log.d(TAG, "TOOL DENIED: ${event.name}")
                is AgentEvent.Error -> Log.d(TAG, "ERROR: ${event.message}")
            }
        }

        // Should have some events
        assertTrue("Should have events", events.isNotEmpty())
        assertTrue("Should have assistant response",
            events.any { it is AgentEvent.AssistantText })

        // Verify screen.read actually returned data
        val screenReadResult = events.filterIsInstance<AgentEvent.ToolCallResult>()
            .firstOrNull { it.name == "screen.read" }
        assertTrue("screen.read should have succeeded",
            screenReadResult?.result?.success == true)
    }

    @Test
    fun tinderSwipeLoop() = runBlocking {
        // Wake + unlock
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
        delay(500)
        Runtime.getRuntime().exec(arrayOf("input", "swipe", "540", "2000", "540", "800", "300")).waitFor()
        delay(1000)

        // Launch Tinder via shell (bypasses package visibility restrictions)
        Runtime.getRuntime().exec(arrayOf(
            "am", "start", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            "-n", "com.tinder/.launch.internal.activities.LoginActivity"
        )).waitFor()

        // Give Tinder time to fully load
        delay(8000)

        val collectorJob = launch {
            agentLoop.events.collect { events.add(it) }
        }

        // Tell the agent to swipe through several profiles with both left and right
        agentLoop.submitMessage(
            """You are a Tinder swiping assistant. Do the following steps:
1. Use screen.read to see what's on screen
2. If you're NOT on a Tinder profile card (e.g. you see a home screen or popup), dismiss it or navigate to the main swiping view
3. Once on a profile, tell me the person's name from the card description
4. Swipe RIGHT (like) on the first profile using app.automate with action=swipe direction=right
5. Use screen.read, tell me the next profile name, then swipe LEFT (nope) using app.automate with action=swipe direction=left
6. Use screen.read, tell me the next profile name, then swipe RIGHT
7. Use screen.read, tell me the next profile name, then swipe LEFT
8. Use screen.read, tell me the final profile name

After each swipe, wait briefly then screen.read. Report a summary of all profiles and which direction you swiped on each."""
        )

        withTimeout(120_000) {
            while (agentLoop.state.value != AgentState.IDLE &&
                   agentLoop.state.value != AgentState.ERROR) {
                delay(200)
            }
        }

        collectorJob.cancel()

        Log.d(TAG, "=== tinderSwipeLoop ===")
        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> Log.d(TAG, "USER: ${event.text.take(100)}")
                is AgentEvent.AssistantText -> Log.d(TAG, "ASSISTANT: ${event.text}")
                is AgentEvent.ToolCallStart -> Log.d(TAG, "TOOL CALL: ${event.name} params=${event.params.toString().take(500)}")
                is AgentEvent.ToolCallResult -> Log.d(TAG, "TOOL RESULT: ${event.name} success=${event.result.success} data=${event.result.data?.toString()?.take(1000)}")
                is AgentEvent.ToolCallDenied -> Log.d(TAG, "TOOL DENIED: ${event.name}")
                is AgentEvent.Error -> Log.d(TAG, "ERROR: ${event.message}")
            }
        }

        assertTrue("Should have events", events.isNotEmpty())
    }

    companion object {
        private const val TAG = "TinderLive"
    }
}
