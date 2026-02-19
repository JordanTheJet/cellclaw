package com.cellclaw.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryEntityTest {

    @Test
    fun `message entity defaults`() {
        val msg = MessageEntity(role = "user", content = "Hello")
        assertEquals(0L, msg.id)
        assertEquals("user", msg.role)
        assertEquals("Hello", msg.content)
        assertEquals("default", msg.conversationId)
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `message entity with custom conversation id`() {
        val msg = MessageEntity(role = "assistant", content = "Hi", conversationId = "conv_42")
        assertEquals("conv_42", msg.conversationId)
    }

    @Test
    fun `memory fact entity defaults`() {
        val fact = MemoryFactEntity(key = "name", value = "Jordan")
        assertEquals(0L, fact.id)
        assertEquals("name", fact.key)
        assertEquals("Jordan", fact.value)
        assertEquals("general", fact.category)
        assertEquals(1.0f, fact.confidence)
        assertTrue(fact.createdAt > 0)
        assertTrue(fact.updatedAt > 0)
    }

    @Test
    fun `memory fact entity with category`() {
        val fact = MemoryFactEntity(key = "city", value = "NYC", category = "location")
        assertEquals("location", fact.category)
    }

    @Test
    fun `memory fact entity with confidence`() {
        val fact = MemoryFactEntity(key = "guess", value = "maybe", confidence = 0.5f)
        assertEquals(0.5f, fact.confidence)
    }

    @Test
    fun `message entity equality`() {
        val t = System.currentTimeMillis()
        val msg1 = MessageEntity(id = 1, role = "user", content = "Hi", timestamp = t)
        val msg2 = MessageEntity(id = 1, role = "user", content = "Hi", timestamp = t)
        assertEquals(msg1, msg2)
    }

    @Test
    fun `message entity copy`() {
        val original = MessageEntity(role = "user", content = "Hello")
        val copy = original.copy(content = "Modified")
        assertEquals("Modified", copy.content)
        assertEquals("user", copy.role)
    }
}
