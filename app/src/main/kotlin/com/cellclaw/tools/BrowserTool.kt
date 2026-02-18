package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class BrowserOpenTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "browser.open"
    override val description = "Open a URL in the device's default browser."
    override val parameters = ToolParameters(
        properties = mapOf(
            "url" to ParameterProperty("string", "URL to open")
        ),
        required = listOf("url")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val url = params["url"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'url' parameter")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("opened", true)
                put("url", url)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to open browser: ${e.message}")
        }
    }
}

class BrowserSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "browser.search"
    override val description = "Perform a web search using the default browser."
    override val parameters = ToolParameters(
        properties = mapOf(
            "query" to ParameterProperty("string", "Search query")
        ),
        required = listOf("query")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'query' parameter")

        return try {
            val encodedQuery = Uri.encode(query)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedQuery")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("searched", true)
                put("query", query)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to search: ${e.message}")
        }
    }
}
