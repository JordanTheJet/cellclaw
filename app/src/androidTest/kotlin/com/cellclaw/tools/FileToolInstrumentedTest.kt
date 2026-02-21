package com.cellclaw.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileToolInstrumentedTest {

    private lateinit var readTool: FileReadTool
    private lateinit var writeTool: FileWriteTool
    private lateinit var listTool: FileListTool

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        readTool = FileReadTool(context)
        writeTool = FileWriteTool(context)
        listTool = FileListTool(context)
    }

    @Test
    fun writeAndReadFile() = runTest {
        // Write a file
        val writeParams = buildJsonObject {
            put("path", "test_file.txt")
            put("content", "Hello from CellClaw!")
        }
        val writeResult = writeTool.execute(writeParams)
        assertTrue("Write should succeed", writeResult.success)
        assertTrue(writeResult.data.toString().contains("\"written\":true"))

        // Read it back
        val readParams = buildJsonObject {
            put("path", "test_file.txt")
        }
        val readResult = readTool.execute(readParams)
        assertTrue("Read should succeed", readResult.success)
        assertTrue(readResult.data.toString().contains("Hello from CellClaw!"))
    }

    @Test
    fun writeAppendMode() = runTest {
        val writeParams = buildJsonObject {
            put("path", "append_test.txt")
            put("content", "Line 1\n")
        }
        writeTool.execute(writeParams)

        val appendParams = buildJsonObject {
            put("path", "append_test.txt")
            put("content", "Line 2\n")
            put("append", true)
        }
        writeTool.execute(appendParams)

        val readParams = buildJsonObject {
            put("path", "append_test.txt")
        }
        val result = readTool.execute(readParams)
        assertTrue(result.success)
        val content = result.data?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
        assertTrue("Should contain both lines", content.contains("Line 1") && content.contains("Line 2"))
    }

    @Test
    fun readNonexistentFile() = runTest {
        val params = buildJsonObject {
            put("path", "does_not_exist_12345.txt")
        }
        val result = readTool.execute(params)
        assertFalse("Should fail for missing file", result.success)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun readMissingPathParam() = runTest {
        val result = readTool.execute(JsonObject(emptyMap()))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing"))
    }

    @Test
    fun listFiles() = runTest {
        // Write a file first to ensure directory isn't empty
        writeTool.execute(buildJsonObject {
            put("path", "list_test.txt")
            put("content", "test")
        })

        val result = listTool.execute(JsonObject(emptyMap()))
        assertTrue("List should succeed", result.success)
        assertNotNull(result.data)
    }

    @Test
    fun readWithMaxLines() = runTest {
        val lines = (1..50).joinToString("\n") { "Line $it" }
        writeTool.execute(buildJsonObject {
            put("path", "lines_test.txt")
            put("content", lines)
        })

        val result = readTool.execute(buildJsonObject {
            put("path", "lines_test.txt")
            put("max_lines", 5)
        })
        assertTrue(result.success)
        val data = result.data?.jsonObject!!
        assertEquals(true, data["truncated"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun writeCreatesSubdirectories() = runTest {
        val result = writeTool.execute(buildJsonObject {
            put("path", "subdir/nested/file.txt")
            put("content", "nested content")
        })
        assertTrue("Should create nested dirs", result.success)

        val readResult = readTool.execute(buildJsonObject {
            put("path", "subdir/nested/file.txt")
        })
        assertTrue(readResult.success)
    }
}
