package com.cellclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*

class CellClawAccessibility : AccessibilityService() {

    private var screenWidth = 1080
    private var screenHeight = 2400

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_COMMAND) return

            val requestId = intent.getStringExtra("request_id") ?: ""
            val action = intent.getStringExtra("action") ?: return

            Log.d(TAG, "Received action: $action (requestId=$requestId)")

            val result = when (action) {
                "tap" -> handleTap(
                    intent.getIntExtra("x", 0),
                    intent.getIntExtra("y", 0),
                    intent.getStringExtra("text")
                )
                "type" -> handleType(intent.getStringExtra("text") ?: "")
                "swipe" -> handleSwipe(intent.getStringExtra("direction") ?: "right")
                "scroll" -> handleScroll(intent.getStringExtra("direction") ?: "down")
                "back" -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    successResult("back", "Pressed back button")
                }
                "home" -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    successResult("home", "Pressed home button")
                }
                "recents" -> {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    successResult("recents", "Opened recents")
                }
                "read_screen" -> readScreen()
                "find_element" -> findElement(intent.getStringExtra("text") ?: "")
                "wait_and_read" -> {
                    // Small delay then read — useful after transitions
                    android.os.Handler(mainLooper).postDelayed({
                        val r = readScreen()
                        if (requestId.isNotBlank()) {
                            AccessibilityBridge.postResult(requestId, r)
                        }
                    }, intent.getLongExtra("delay_ms", 500))
                    return // Don't post result immediately
                }
                else -> buildJsonObject {
                    put("error", "Unknown action: $action")
                }
            }

            if (requestId.isNotBlank()) {
                AccessibilityBridge.postResult(requestId, result)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateScreenDimensions()

        val filter = IntentFilter(ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        AccessibilityBridge.onServiceConnected()
        Log.d(TAG, "Accessibility service connected (${screenWidth}x${screenHeight})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        AccessibilityBridge.onServiceDisconnected()
        unregisterReceiver(actionReceiver)
        super.onDestroy()
    }

    private fun updateScreenDimensions() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
    }

    // ── Tap ──────────────────────────────────────────────────────────────

    private fun handleTap(x: Int, y: Int, text: String?): JsonObject {
        if (text != null) {
            val rootNode = rootInActiveWindow ?: return errorResult("No active window")
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNullOrEmpty()) return errorResult("Element not found: '$text'")

            val node = nodes.first()
            // Try clicking the node directly, or find a clickable parent
            val clickable = findClickableParent(node) ?: node
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return successResult("tap", "Tapped on '$text'")
        }

        // Tap at coordinates
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        return successResult("tap", "Tapped at ($x, $y)")
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        while (true) {
            if (current.isClickable) return current
            current = current.parent ?: return null
        }
    }

    // ── Type ─────────────────────────────────────────────────────────────

    private fun handleType(text: String): JsonObject {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return errorResult("No focused input field")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return successResult("type", "Typed '${text.take(50)}...'")
    }

    // ── Swipe (for dating apps, card swiping, etc.) ──────────────────────

    private fun handleSwipe(direction: String): JsonObject {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val swipeDistance = screenWidth * 0.6f // 60% of screen width
        val duration = 300L // Fast enough to register as swipe

        val (startX, startY, endX, endY) = when (direction) {
            "left" -> listOf(centerX + 100f, centerY, centerX - swipeDistance, centerY - 50f)
            "right" -> listOf(centerX - 100f, centerY, centerX + swipeDistance, centerY - 50f)
            "up" -> listOf(centerX, centerY + 200f, centerX, centerY - swipeDistance)
            "down" -> listOf(centerX, centerY - 200f, centerX, centerY + swipeDistance)
            else -> return errorResult("Invalid swipe direction: $direction")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe $direction completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe $direction cancelled")
            }
        }, null)

        return successResult("swipe", "Swiped $direction")
    }

    // ── Scroll ───────────────────────────────────────────────────────────

    private fun handleScroll(direction: String): JsonObject {
        val rootNode = rootInActiveWindow ?: return errorResult("No active window")
        val scrollableNode = findScrollableNode(rootNode)

        if (scrollableNode != null) {
            when (direction) {
                "down" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                "up" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                "left", "right" -> {
                    // For horizontal scroll, use gesture-based approach
                    return handleSwipe(direction)
                }
            }
            return successResult("scroll", "Scrolled $direction")
        }

        // Fallback: gesture-based scroll
        val centerX = screenWidth / 2f
        val scrollDist = screenHeight * 0.3f
        val (startY, endY) = when (direction) {
            "down" -> Pair(centerX + scrollDist, centerX - scrollDist)
            "up" -> Pair(centerX - scrollDist, centerX + scrollDist)
            else -> return errorResult("Invalid scroll direction")
        }

        val path = Path().apply {
            moveTo(centerX, screenHeight / 2f + if (direction == "down") scrollDist / 2 else -scrollDist / 2)
            lineTo(centerX, screenHeight / 2f + if (direction == "down") -scrollDist / 2 else scrollDist / 2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
        return successResult("scroll", "Scrolled $direction (gesture)")
    }

    // ── Read Screen ──────────────────────────────────────────────────────

    private fun readScreen(): JsonObject {
        val rootNode = rootInActiveWindow ?: return buildJsonObject {
            put("error", "No active window")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
        }

        val elements = mutableListOf<JsonObject>()
        traverseNode(rootNode, elements)

        return buildJsonObject {
            put("package", rootNode.packageName?.toString() ?: "unknown")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("element_count", elements.size)
            putJsonArray("elements") {
                elements.forEach { add(it) }
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 15) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Include elements that have text, content description, or are interactive
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

    // ── Find Element ─────────────────────────────────────────────────────

    private fun findElement(text: String): JsonObject {
        val rootNode = rootInActiveWindow ?: return errorResult("No active window")
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)

        if (nodes.isNullOrEmpty()) return buildJsonObject {
            put("found", false)
            put("query", text)
        }

        return buildJsonObject {
            put("found", true)
            put("query", text)
            put("count", nodes.size)
            putJsonArray("matches") {
                for (node in nodes.take(10)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    add(buildJsonObject {
                        put("text", node.text?.toString() ?: "")
                        put("desc", node.contentDescription?.toString() ?: "")
                        put("clickable", node.isClickable)
                        put("centerX", bounds.centerX())
                        put("centerY", bounds.centerY())
                    })
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    private fun successResult(action: String, message: String) = buildJsonObject {
        put("success", true)
        put("action", action)
        put("message", message)
    }

    private fun errorResult(message: String) = buildJsonObject {
        put("success", false)
        put("error", message)
    }

    companion object {
        private const val TAG = "CellClawA11y"
        const val ACTION_COMMAND = "com.cellclaw.ACCESSIBILITY_ACTION"
    }
}
