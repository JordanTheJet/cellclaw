package com.cellclaw.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        registry = ToolRegistry()
    }

    @Test
    fun `register and retrieve tool`() {
        val tool = FakeTool("test.tool", "A test tool")
        registry.register(tool)

        assertEquals(tool, registry.get("test.tool"))
    }

    @Test
    fun `get returns null for unknown tool`() {
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `register multiple tools`() {
        val tool1 = FakeTool("tool.one", "First")
        val tool2 = FakeTool("tool.two", "Second")
        registry.register(tool1, tool2)

        assertEquals(2, registry.all().size)
        assertEquals(setOf("tool.one", "tool.two"), registry.names())
    }

    @Test
    fun `toApiSchema converts all tools`() {
        registry.register(
            FakeTool("sms.read", "Read SMS"),
            FakeTool("sms.send", "Send SMS")
        )

        val schema = registry.toApiSchema()
        assertEquals(2, schema.size)
        assertEquals("sms.read", schema[0].name)
        assertEquals("Read SMS", schema[0].description)
    }

    @Test
    fun `register overwrites duplicate names`() {
        registry.register(FakeTool("tool", "original"))
        registry.register(FakeTool("tool", "updated"))

        assertEquals(1, registry.all().size)
        assertEquals("updated", registry.get("tool")?.description)
    }
}

class FakeTool(
    override val name: String,
    override val description: String,
    override val requiresApproval: Boolean = false
) : Tool {
    override val parameters = ToolParameters()
    override suspend fun execute(params: JsonObject): ToolResult {
        return ToolResult.success(buildJsonObject { put("fake", true) })
    }
}
