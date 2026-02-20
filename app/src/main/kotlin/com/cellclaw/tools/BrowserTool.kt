package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
    override val description = "Perform a web search and return the results as text. Returns titles, URLs, and snippets from search results."
    override val parameters = ToolParameters(
        properties = mapOf(
            "query" to ParameterProperty("string", "Search query")
        ),
        required = listOf("query")
    )
    override val requiresApproval = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(params: JsonObject): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'query' parameter")

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = Uri.encode(query)
                val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                val results = parseSearchResults(html)

                ToolResult.success(buildJsonObject {
                    put("query", query)
                    put("result_count", results.size)
                    putJsonArray("results") {
                        results.take(8).forEach { add(it) }
                    }
                })
            } catch (e: Exception) {
                ToolResult.error("Search failed: ${e.message}")
            }
        }
    }

    private fun parseSearchResults(html: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        // DuckDuckGo HTML results are in <a class="result__a"> tags with <a class="result__snippet">
        val resultPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in resultPattern.findAll(html)) {
            val url = match.groupValues[1].let { raw ->
                // DuckDuckGo wraps URLs in a redirect; extract the actual URL
                Regex("""uddg=([^&]+)""").find(raw)?.groupValues?.get(1)?.let { Uri.decode(it) } ?: raw
            }
            val title = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val snippet = match.groupValues[3].replace(Regex("<[^>]*>"), "").trim()
            if (title.isNotBlank()) {
                results.add(buildJsonObject {
                    put("title", title)
                    put("url", url)
                    put("snippet", snippet)
                })
            }
        }
        return results
    }
}
