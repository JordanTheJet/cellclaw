package com.cellclaw.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptExecInstrumentedTest {

    private lateinit var tool: ScriptExecTool

    @Before
    fun setup() {
        tool = ScriptExecTool()
    }

    @Test
    fun executeEchoCommand() = runTest {
        val params = buildJsonObject {
            put("command", "echo hello_cellclaw")
        }
        val result = tool.execute(params)
        assertTrue("Should succeed", result.success)
        val data = result.data?.jsonObject!!
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
        assertTrue(data["output"]?.jsonPrimitive?.content?.contains("hello_cellclaw") == true)
    }

    @Test
    fun executePwdCommand() = runTest {
        val params = buildJsonObject {
            put("command", "pwd")
        }
        val result = tool.execute(params)
        assertTrue("Command should succeed", result.success)
        assertEquals(0, result.data?.jsonObject?.get("exit_code")?.jsonPrimitive?.int)
        val output = result.data?.jsonObject?.get("output")?.jsonPrimitive?.content ?: ""
        assertTrue("Should have some output", output.isNotEmpty())
    }

    @Test
    fun executeDateCommand() = runTest {
        val params = buildJsonObject {
            put("command", "date")
        }
        val result = tool.execute(params)
        assertTrue(result.success)
        assertEquals(0, result.data?.jsonObject?.get("exit_code")?.jsonPrimitive?.int)
    }

    @Test
    fun executeFailingCommand() = runTest {
        val params = buildJsonObject {
            put("command", "ls /nonexistent_directory_12345")
        }
        val result = tool.execute(params)
        assertTrue("Should still return a result", result.success)
        assertNotEquals(0, result.data?.jsonObject?.get("exit_code")?.jsonPrimitive?.int)
    }

    @Test
    fun missingCommandParam() = runTest {
        val result = tool.execute(JsonObject(emptyMap()))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing"))
    }

    @Test
    fun executeWithCustomTimeout() = runTest {
        val params = buildJsonObject {
            put("command", "echo fast")
            put("timeout_seconds", 5)
        }
        val result = tool.execute(params)
        assertTrue(result.success)
    }

    @Test
    fun executePipedCommands() = runTest {
        val params = buildJsonObject {
            put("command", "echo 'line1\nline2\nline3' | wc -l")
        }
        val result = tool.execute(params)
        assertTrue(result.success)
        assertEquals(0, result.data?.jsonObject?.get("exit_code")?.jsonPrimitive?.int)
    }

    @Test
    fun executeIdCommand() = runTest {
        val params = buildJsonObject {
            put("command", "id")
        }
        val result = tool.execute(params)
        assertTrue(result.success)
        val output = result.data?.jsonObject?.get("output")?.jsonPrimitive?.content ?: ""
        assertTrue("Should show uid", output.contains("uid="))
    }
}
