package com.cellclaw.tools

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject

class FileReadTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "file.read"
    override val description = "Read contents of a file."
    override val parameters = ToolParameters(
        properties = mapOf(
            "path" to ParameterProperty("string", "File path (relative to app storage or absolute)"),
            "max_lines" to ParameterProperty("integer", "Maximum lines to read (default 100)")
        ),
        required = listOf("path")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'path' parameter")
        val maxLines = params["max_lines"]?.jsonPrimitive?.intOrNull ?: 100

        return try {
            val file = resolvePath(path)
            if (!file.exists()) return ToolResult.error("File not found: $path")
            if (!file.canRead()) return ToolResult.error("Cannot read file: $path")
            if (file.length() > 10 * 1024 * 1024) return ToolResult.error("File too large (>10MB)")

            val lines = file.readLines()
            val content = lines.take(maxLines).joinToString("\n")

            ToolResult.success(buildJsonObject {
                put("content", content)
                put("lines", lines.size)
                put("truncated", lines.size > maxLines)
                put("size_bytes", file.length())
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to read file: ${e.message}")
        }
    }

    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) File(path)
        else File(context.filesDir, path)
    }
}

class FileWriteTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "file.write"
    override val description = "Write content to a file."
    override val parameters = ToolParameters(
        properties = mapOf(
            "path" to ParameterProperty("string", "File path (relative to app storage or absolute)"),
            "content" to ParameterProperty("string", "Content to write"),
            "append" to ParameterProperty("boolean", "Append to file instead of overwriting (default false)")
        ),
        required = listOf("path", "content")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'path' parameter")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'content' parameter")
        val append = params["append"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val file = if (path.startsWith("/")) File(path)
            else File(context.filesDir, path)

            file.parentFile?.mkdirs()

            if (append) file.appendText(content)
            else file.writeText(content)

            ToolResult.success(buildJsonObject {
                put("written", true)
                put("path", file.absolutePath)
                put("size_bytes", file.length())
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to write file: ${e.message}")
        }
    }
}

class FileListTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "file.list"
    override val description = "List files and directories at a given path."
    override val parameters = ToolParameters(
        properties = mapOf(
            "path" to ParameterProperty("string", "Directory path (default: app storage)"),
            "recursive" to ParameterProperty("boolean", "List recursively (default false)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val path = params["path"]?.jsonPrimitive?.contentOrNull
        val recursive = params["recursive"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val dir = if (path != null) {
                if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            } else {
                context.filesDir
            }

            if (!dir.exists()) return ToolResult.error("Directory not found: $path")
            if (!dir.isDirectory) return ToolResult.error("Not a directory: $path")

            val files = if (recursive) dir.walkTopDown().toList() else dir.listFiles()?.toList() ?: emptyList()

            val entries = buildJsonArray {
                for (file in files) {
                    if (file == dir) continue
                    add(buildJsonObject {
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("is_directory", file.isDirectory)
                        put("size_bytes", if (file.isFile) file.length() else 0)
                        put("last_modified", file.lastModified())
                    })
                }
            }

            ToolResult.success(entries)
        } catch (e: Exception) {
            ToolResult.error("Failed to list files: ${e.message}")
        }
    }
}
