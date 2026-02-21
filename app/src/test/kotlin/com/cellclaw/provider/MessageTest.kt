package com.cellclaw.provider

import org.junit.Assert.*
import org.junit.Test

class MessageTest {

    @Test
    fun `user message creates correct structure`() {
        val msg = Message.user("Hello")
        assertEquals(Role.USER, msg.role)
        assertEquals(1, msg.content.size)
        assertTrue(msg.content[0] is ContentBlock.Text)
        assertEquals("Hello", (msg.content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `assistant message creates correct structure`() {
        val msg = Message.assistant("Hi there")
        assertEquals(Role.ASSISTANT, msg.role)
        assertEquals("Hi there", (msg.content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `tool result message is user role`() {
        val msg = Message.toolResult("tool_123", "result data")
        assertEquals(Role.USER, msg.role)
        val block = msg.content[0] as ContentBlock.ToolResult
        assertEquals("tool_123", block.toolUseId)
        assertEquals("result data", block.content)
        assertFalse(block.isError)
    }

    @Test
    fun `tool result error defaults to false`() {
        val block = ContentBlock.ToolResult("id", "content")
        assertFalse(block.isError)
    }

    @Test
    fun `tool result with error`() {
        val block = ContentBlock.ToolResult("id", "failed", isError = true)
        assertTrue(block.isError)
    }

    @Test
    fun `completion request defaults`() {
        val req = CompletionRequest(
            systemPrompt = "test",
            messages = emptyList()
        )
        assertEquals(emptyList<Any>(), req.tools)
        assertEquals(4096, req.maxTokens)
    }

    @Test
    fun `usage defaults to zero`() {
        val usage = Usage()
        assertEquals(0, usage.inputTokens)
        assertEquals(0, usage.outputTokens)
    }
}
