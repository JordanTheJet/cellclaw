package com.cellclaw.provider

import org.junit.Assert.*
import org.junit.Test

class StreamEventTest {

    @Test
    fun `text delta event`() {
        val event = StreamEvent.TextDelta("Hello")
        assertEquals("Hello", event.text)
    }

    @Test
    fun `tool use start event`() {
        val event = StreamEvent.ToolUseStart("tool_1", "sms.send")
        assertEquals("tool_1", event.id)
        assertEquals("sms.send", event.name)
    }

    @Test
    fun `tool use input delta event`() {
        val event = StreamEvent.ToolUseInputDelta("{\"to\":\"123\"}")
        assertEquals("{\"to\":\"123\"}", event.delta)
    }

    @Test
    fun `complete event wraps response`() {
        val response = CompletionResponse(
            content = listOf(ContentBlock.Text("Done")),
            stopReason = StopReason.END_TURN,
            usage = Usage(100, 50)
        )
        val event = StreamEvent.Complete(response)
        assertEquals(StopReason.END_TURN, event.response.stopReason)
        assertEquals(100, event.response.usage?.inputTokens)
        assertEquals(50, event.response.usage?.outputTokens)
    }

    @Test
    fun `error event`() {
        val event = StreamEvent.Error("API rate limited")
        assertEquals("API rate limited", event.message)
    }

    @Test
    fun `stop reason values`() {
        assertEquals(4, StopReason.values().size)
    }

    @Test
    fun `completion response with null usage`() {
        val response = CompletionResponse(
            content = emptyList(),
            stopReason = StopReason.MAX_TOKENS
        )
        assertNull(response.usage)
    }
}
