package com.cellclaw.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cellclaw.tools.ToolResult
import org.junit.Assert.*
import org.junit.Test

class AgentStateTest {

    @Test
    fun `all agent states exist`() {
        val states = AgentState.values()
        assertEquals(6, states.size)
        assertTrue(states.contains(AgentState.IDLE))
        assertTrue(states.contains(AgentState.THINKING))
        assertTrue(states.contains(AgentState.EXECUTING_TOOLS))
        assertTrue(states.contains(AgentState.WAITING_APPROVAL))
        assertTrue(states.contains(AgentState.PAUSED))
        assertTrue(states.contains(AgentState.ERROR))
    }

    @Test
    fun `agent event user message`() {
        val event = AgentEvent.UserMessage("hello")
        assertEquals("hello", event.text)
    }

    @Test
    fun `agent event assistant text`() {
        val event = AgentEvent.AssistantText("response")
        assertEquals("response", event.text)
    }

    @Test
    fun `agent event tool call start`() {
        val params = buildJsonObject { put("key", "value") }
        val event = AgentEvent.ToolCallStart("sms.send", params)
        assertEquals("sms.send", event.name)
        assertNotNull(event.params)
    }

    @Test
    fun `agent event tool call result`() {
        val result = ToolResult.success(buildJsonObject { put("sent", true) })
        val event = AgentEvent.ToolCallResult("sms.send", result)
        assertEquals("sms.send", event.name)
        assertTrue(event.result.success)
    }

    @Test
    fun `agent event tool call denied`() {
        val event = AgentEvent.ToolCallDenied("script.exec")
        assertEquals("script.exec", event.name)
    }

    @Test
    fun `agent event error`() {
        val event = AgentEvent.Error("Something went wrong")
        assertEquals("Something went wrong", event.message)
    }
}

class TaskPriorityTest {

    @Test
    fun `priorities are ordered correctly`() {
        assertTrue(TaskPriority.HIGH.ordinal < TaskPriority.NORMAL.ordinal)
        assertTrue(TaskPriority.NORMAL.ordinal < TaskPriority.LOW.ordinal)
    }

    @Test
    fun `task status values`() {
        assertEquals(4, TaskStatus.values().size)
        assertTrue(TaskStatus.values().contains(TaskStatus.PENDING))
        assertTrue(TaskStatus.values().contains(TaskStatus.IN_PROGRESS))
        assertTrue(TaskStatus.values().contains(TaskStatus.COMPLETED))
        assertTrue(TaskStatus.values().contains(TaskStatus.FAILED))
    }
}
