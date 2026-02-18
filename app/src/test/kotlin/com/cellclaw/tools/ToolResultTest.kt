package com.cellclaw.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ToolResultTest {

    @Test
    fun `success result`() {
        val data = buildJsonObject { put("key", "value") }
        val result = ToolResult.success(data)

        assertTrue(result.success)
        assertNotNull(result.data)
        assertNull(result.error)
    }

    @Test
    fun `error result`() {
        val result = ToolResult.error("Something failed")

        assertFalse(result.success)
        assertNull(result.data)
        assertEquals("Something failed", result.error)
    }
}

class ToolParametersTest {

    @Test
    fun `default parameters are empty object type`() {
        val params = ToolParameters()
        assertEquals("object", params.type)
        assertTrue(params.properties.isEmpty())
        assertTrue(params.required.isEmpty())
    }

    @Test
    fun `parameters with properties`() {
        val params = ToolParameters(
            properties = mapOf(
                "name" to ParameterProperty("string", "User name"),
                "age" to ParameterProperty("integer", "User age")
            ),
            required = listOf("name")
        )

        assertEquals(2, params.properties.size)
        assertEquals("string", params.properties["name"]?.type)
        assertEquals(1, params.required.size)
    }

    @Test
    fun `parameter with enum`() {
        val prop = ParameterProperty(
            type = "string",
            description = "Direction",
            enum = listOf("up", "down", "left", "right")
        )

        assertEquals(4, prop.enum?.size)
        assertTrue(prop.enum!!.contains("up"))
    }
}
