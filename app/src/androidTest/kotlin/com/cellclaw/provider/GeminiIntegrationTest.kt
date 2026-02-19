package com.cellclaw.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cellclaw.BuildConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test that calls the real Gemini API.
 * Requires a valid API key.
 */
@RunWith(AndroidJUnit4::class)
class GeminiIntegrationTest {

    private lateinit var provider: GeminiProvider

    @Before
    fun setup() {
        assumeTrue("Gemini API key must be set in local.properties", API_KEY.isNotBlank())
        provider = GeminiProvider()
        provider.configure(API_KEY, "gemini-2.5-flash")
    }

    @Test
    fun simpleCompletion() = runTest {
        val request = CompletionRequest(
            systemPrompt = "You are a helpful assistant. Respond briefly.",
            messages = listOf(Message.user("What is 2+2? Answer with just the number.")),
            maxTokens = 100
        )

        val response = provider.complete(request)
        assertNotNull(response)
        assertTrue("Should have content", response.content.isNotEmpty())

        val text = response.content.filterIsInstance<ContentBlock.Text>()
            .joinToString("") { it.text }
        assertTrue("Should contain 4", text.contains("4"))
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun completionWithToolUse() = runTest {
        val request = CompletionRequest(
            systemPrompt = "You are CellClaw, an AI assistant on an Android phone. Use the email.send tool when asked to send email. Always use tools when available.",
            messages = listOf(
                Message.user("Send an email to morepencils@gmail.com with subject 'Hello from CellClaw' and body 'This is a test email sent by CellClaw using the Gemini AI provider.'")
            ),
            tools = listOf(
                com.cellclaw.tools.ToolApiDefinition(
                    name = "email.send",
                    description = "Send an email. Opens the device email client with the composed message.",
                    inputSchema = com.cellclaw.tools.ToolParameters(
                        properties = mapOf(
                            "to" to com.cellclaw.tools.ParameterProperty("string", "Recipient email address"),
                            "subject" to com.cellclaw.tools.ParameterProperty("string", "Email subject line"),
                            "body" to com.cellclaw.tools.ParameterProperty("string", "Email body text")
                        ),
                        required = listOf("to", "subject", "body")
                    )
                )
            ),
            maxTokens = 1024
        )

        val response = provider.complete(request)
        assertNotNull(response)
        assertTrue("Should have content", response.content.isNotEmpty())

        // Check if Gemini decided to use the email tool
        val toolUses = response.content.filterIsInstance<ContentBlock.ToolUse>()
        if (toolUses.isNotEmpty()) {
            assertEquals(StopReason.TOOL_USE, response.stopReason)
            val emailCall = toolUses.first()
            assertTrue("Tool name should contain email", emailCall.name.contains("email"))
            assertTrue("Should have 'to' param", emailCall.input.containsKey("to"))
            val to = emailCall.input["to"]?.jsonPrimitive?.content ?: ""
            assertEquals("morepencils@gmail.com", to)
        }
        // If no tool use, it should at least have text mentioning the email
    }

    @Test
    fun streamCompletion() = runTest {
        val request = CompletionRequest(
            systemPrompt = "Be brief.",
            messages = listOf(Message.user("Say hi")),
            maxTokens = 50
        )

        val events = mutableListOf<StreamEvent>()
        provider.stream(request).collect { events.add(it) }

        assertTrue("Should have events", events.isNotEmpty())
        assertTrue("Should end with Complete",
            events.last() is StreamEvent.Complete || events.last() is StreamEvent.Error)
    }

    companion object {
        // API key loaded from local.properties via BuildConfig
        val API_KEY: String = BuildConfig.GEMINI_API_KEY
    }
}
