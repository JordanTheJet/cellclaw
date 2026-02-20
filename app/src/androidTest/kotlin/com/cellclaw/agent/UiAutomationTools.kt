package com.cellclaw.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.cellclaw.tools.*
import kotlinx.serialization.json.*

/**
 * Test-only ScreenReadTool that uses the instrumentation's UiAutomation API directly.
 *
 * `am instrument` force-stops the app (killing the accessibility service), and `uiautomator dump`
 * can't run because the test already holds the sole UiAutomation connection. So we use
 * `InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow` directly,
 * which works because the test process has its own UiAutomation connection to the system.
 */
class UiAutomationScreenReadTool : Tool {
    override val name = "screen.read"
    override val description = """Read the current screen content of whatever app is in the foreground.
Returns all visible text, buttons, and UI elements with their positions.
Use this to understand what's on screen before deciding what action to take."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "wait_ms" to ParameterProperty("integer", "Optional delay in ms before reading (default 0)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val waitMs = params["wait_ms"]?.jsonPrimitive?.longOrNull ?: 0
            if (waitMs > 0) {
                kotlinx.coroutines.delay(waitMs)
            }

            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

            // Retry getting rootInActiveWindow — may be null briefly during transitions
            var rootNode: AccessibilityNodeInfo? = null
            for (attempt in 1..10) {
                rootNode = uiAutomation.rootInActiveWindow
                if (rootNode != null) break
                Log.d(TAG, "rootInActiveWindow null, retry $attempt/10...")
                kotlinx.coroutines.delay(500)
            }

            if (rootNode == null) {
                return ToolResult.error("No active window found after retries")
            }

            val elements = mutableListOf<JsonObject>()
            traverseNode(rootNode, elements)

            val packageName = rootNode.packageName?.toString() ?: "unknown"

            ToolResult.success(buildJsonObject {
                put("package", packageName)
                put("element_count", elements.size)
                putJsonArray("elements") {
                    elements.forEach { add(it) }
                }
            })
        } catch (e: Exception) {
            ToolResult.error("Screen read failed: ${e.message}")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 15) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text != null || contentDesc != null || node.isClickable || node.isEditable) {
            elements.add(buildJsonObject {
                put("type", className)
                if (text != null) put("text", text)
                if (contentDesc != null) put("desc", contentDesc)
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("checked", node.isChecked)
                putJsonObject("bounds") {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                    put("centerX", bounds.centerX())
                    put("centerY", bounds.centerY())
                }
            })
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements, depth + 1)
        }
    }

    companion object {
        private const val TAG = "UiAutoTools"
    }
}

/**
 * Test-only AppAutomateTool that uses shell commands for gestures.
 * For tap-by-text and find_element, it reads the screen via UiAutomation API.
 * For input actions (tap, swipe, type, keyevent), it uses `input` shell commands.
 */
class UiAutomationAutomateTool : Tool {
    override val name = "app.automate"
    override val description = """Automate actions in other apps.
Actions: tap (by text or coordinates), type (into focused field), swipe (left/right/up/down), scroll (up/down), back, home, find_element."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform",
                enum = listOf("tap", "type", "swipe", "scroll", "back", "home", "recents", "find_element")),
            "text" to ParameterProperty("string", "Text to type, or text of element to tap/find"),
            "x" to ParameterProperty("integer", "X coordinate for tap"),
            "y" to ParameterProperty("integer", "Y coordinate for tap"),
            "direction" to ParameterProperty("string", "Direction for swipe/scroll",
                enum = listOf("left", "right", "up", "down"))
        ),
        required = listOf("action")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'action' parameter")

        return try {
            when (action) {
                "tap" -> {
                    val text = params["text"]?.jsonPrimitive?.contentOrNull
                    val x = params["x"]?.jsonPrimitive?.intOrNull
                    val y = params["y"]?.jsonPrimitive?.intOrNull

                    if (text != null) {
                        val screenRead = UiAutomationScreenReadTool()
                        val readResult = screenRead.execute(buildJsonObject {})
                        if (!readResult.success) return readResult

                        val elements = readResult.data?.jsonObject?.get("elements")?.jsonArray
                        val match = elements?.firstOrNull { el ->
                            val elText = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            val elDesc = el.jsonObject["desc"]?.jsonPrimitive?.contentOrNull ?: ""
                            elText.contains(text, ignoreCase = true) || elDesc.contains(text, ignoreCase = true)
                        }

                        if (match == null) return ToolResult.error("Element not found: '$text'")

                        val cx = match.jsonObject["bounds"]?.jsonObject?.get("centerX")?.jsonPrimitive?.int ?: 0
                        val cy = match.jsonObject["bounds"]?.jsonObject?.get("centerY")?.jsonPrimitive?.int ?: 0
                        shellExec("input", "tap", cx.toString(), cy.toString())
                        ToolResult.success(buildJsonObject {
                            put("success", true)
                            put("action", "tap")
                            put("message", "Tapped on '$text' at ($cx, $cy)")
                        })
                    } else if (x != null && y != null) {
                        shellExec("input", "tap", x.toString(), y.toString())
                        ToolResult.success(buildJsonObject {
                            put("success", true)
                            put("action", "tap")
                            put("message", "Tapped at ($x, $y)")
                        })
                    } else {
                        ToolResult.error("Tap requires 'text' or 'x'+'y' coordinates")
                    }
                }
                "swipe" -> {
                    val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "right"
                    val sw = 1080; val sh = 2640
                    val cx = sw / 2; val cy = sh / 2
                    val dist = (sw * 0.6).toInt()
                    val (sx, sy, ex, ey) = when (direction) {
                        "left" -> listOf(cx + 100, cy, cx - dist, cy - 50)
                        "right" -> listOf(cx - 100, cy, cx + dist, cy - 50)
                        "up" -> listOf(cx, cy + 200, cx, cy - dist)
                        "down" -> listOf(cx, cy - 200, cx, cy + dist)
                        else -> return ToolResult.error("Invalid direction: $direction")
                    }
                    shellExec("input", "swipe", sx.toString(), sy.toString(), ex.toString(), ey.toString(), "300")
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "swipe")
                        put("message", "Swiped $direction")
                    })
                }
                "type" -> {
                    val text = params["text"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.error("Missing 'text' for type action")
                    shellExec("input", "text", text.replace(" ", "%s"))
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "type")
                        put("message", "Typed '${text.take(50)}'")
                    })
                }
                "back" -> {
                    shellExec("input", "keyevent", "KEYCODE_BACK")
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "back")
                        put("message", "Pressed back")
                    })
                }
                "home" -> {
                    shellExec("input", "keyevent", "KEYCODE_HOME")
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "home")
                        put("message", "Pressed home")
                    })
                }
                "recents" -> {
                    shellExec("input", "keyevent", "KEYCODE_APP_SWITCH")
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "recents")
                        put("message", "Opened recents")
                    })
                }
                "scroll" -> {
                    val direction = params["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
                    val cx = 540; val cy = 1320
                    val dist = 600
                    val (sy, ey) = when (direction) {
                        "down" -> Pair(cy + dist / 2, cy - dist / 2)
                        "up" -> Pair(cy - dist / 2, cy + dist / 2)
                        else -> return ToolResult.error("Invalid scroll direction")
                    }
                    shellExec("input", "swipe", cx.toString(), sy.toString(), cx.toString(), ey.toString(), "500")
                    ToolResult.success(buildJsonObject {
                        put("success", true)
                        put("action", "scroll")
                        put("message", "Scrolled $direction")
                    })
                }
                "find_element" -> {
                    val text = params["text"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.error("Missing 'text' for find_element")
                    val screenRead = UiAutomationScreenReadTool()
                    val readResult = screenRead.execute(buildJsonObject {})
                    if (!readResult.success) return readResult

                    val elements = readResult.data?.jsonObject?.get("elements")?.jsonArray
                    val matches = elements?.filter { el ->
                        val elText = el.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val elDesc = el.jsonObject["desc"]?.jsonPrimitive?.contentOrNull ?: ""
                        elText.contains(text, ignoreCase = true) || elDesc.contains(text, ignoreCase = true)
                    } ?: emptyList()

                    ToolResult.success(buildJsonObject {
                        put("found", matches.isNotEmpty())
                        put("query", text)
                        put("count", matches.size)
                        putJsonArray("matches") {
                            matches.take(10).forEach { add(it) }
                        }
                    })
                }
                else -> ToolResult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.error("Automation failed: ${e.message}")
        }
    }

    private fun shellExec(vararg args: String) {
        Runtime.getRuntime().exec(args).waitFor()
    }
}
