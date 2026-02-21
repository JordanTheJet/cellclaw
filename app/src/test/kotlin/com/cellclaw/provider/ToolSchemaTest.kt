package com.cellclaw.provider

import com.cellclaw.tools.ParameterProperty
import com.cellclaw.tools.ToolApiDefinition
import com.cellclaw.tools.ToolParameters
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolSchemaTest {

    private val testTool = ToolApiDefinition(
        name = "sms.send",
        description = "Send an SMS message",
        inputSchema = ToolParameters(
            properties = mapOf(
                "to" to ParameterProperty("string", "Recipient phone number"),
                "message" to ParameterProperty("string", "Message text")
            ),
            required = listOf("to", "message")
        )
    )

    @Test
    fun `toAnthropicFormat produces correct structure`() {
        val result = ToolSchema.toAnthropicFormat(listOf(testTool))

        assertEquals(1, result.size)
        val tool = result[0].jsonObject
        assertEquals("sms.send", tool["name"]?.jsonPrimitive?.content)
        assertEquals("Send an SMS message", tool["description"]?.jsonPrimitive?.content)

        val schema = tool["input_schema"]?.jsonObject!!
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)

        val props = schema["properties"]?.jsonObject!!
        assertTrue(props.containsKey("to"))
        assertTrue(props.containsKey("message"))

        val required = schema["required"]?.jsonArray!!
        assertEquals(2, required.size)
    }

    @Test
    fun `toOpenAIFormat produces function calling structure`() {
        val result = ToolSchema.toOpenAIFormat(listOf(testTool))

        assertEquals(1, result.size)
        val tool = result[0].jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)

        val fn = tool["function"]?.jsonObject!!
        assertEquals("sms.send", fn["name"]?.jsonPrimitive?.content)
        assertEquals("Send an SMS message", fn["description"]?.jsonPrimitive?.content)

        val params = fn["parameters"]?.jsonObject!!
        assertTrue(params.containsKey("properties"))
        assertTrue(params.containsKey("required"))
    }

    @Test
    fun `empty tools list produces empty array`() {
        assertEquals(0, ToolSchema.toAnthropicFormat(emptyList()).size)
        assertEquals(0, ToolSchema.toOpenAIFormat(emptyList()).size)
    }

    @Test
    fun `tool with enum parameter`() {
        val tool = ToolApiDefinition(
            name = "test",
            description = "Test",
            inputSchema = ToolParameters(
                properties = mapOf(
                    "direction" to ParameterProperty("string", "Dir", enum = listOf("up", "down"))
                )
            )
        )

        val result = ToolSchema.toAnthropicFormat(listOf(tool))
        val props = result[0].jsonObject["input_schema"]?.jsonObject
            ?.get("properties")?.jsonObject!!
        val enumValues = props["direction"]?.jsonObject?.get("enum")?.jsonArray!!
        assertEquals(2, enumValues.size)
        assertEquals("up", enumValues[0].jsonPrimitive.content)
    }
}
