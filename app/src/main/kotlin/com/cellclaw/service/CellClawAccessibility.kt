package com.cellclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*

class CellClawAccessibility : AccessibilityService() {

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "com.cellclaw.ACCESSIBILITY_ACTION") return

            when (intent.getStringExtra("action")) {
                "tap" -> handleTap(
                    intent.getIntExtra("x", 0),
                    intent.getIntExtra("y", 0),
                    intent.getStringExtra("text")
                )
                "type" -> handleType(intent.getStringExtra("text") ?: "")
                "scroll" -> handleScroll(intent.getStringExtra("direction") ?: "down")
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "read_screen" -> readScreen()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter("com.cellclaw.ACCESSIBILITY_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are monitored but not actively processed unless needed
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterReceiver(actionReceiver)
        super.onDestroy()
    }

    private fun handleTap(x: Int, y: Int, text: String?) {
        if (text != null) {
            // Find and click node by text
            val rootNode = rootInActiveWindow ?: return
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            nodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Tap at coordinates
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleType(text: String) {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun handleScroll(direction: String) {
        val rootNode = rootInActiveWindow ?: return
        val scrollableNode = findScrollableNode(rootNode) ?: return

        when (direction) {
            "down" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "up" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }

    private fun readScreen(): JsonObject {
        val rootNode = rootInActiveWindow ?: return buildJsonObject {
            put("error", "No active window")
        }

        return buildJsonObject {
            put("package", rootNode.packageName?.toString() ?: "")
            putJsonArray("elements") {
                traverseNode(rootNode, this)
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, array: JsonArrayBuilder, depth: Int = 0) {
        if (depth > 10) return // Limit depth

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()

        if (text != null || contentDesc != null) {
            array.add(buildJsonObject {
                put("class", className ?: "")
                text?.let { put("text", it) }
                contentDesc?.let { put("content_description", it) }
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
            })
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, array, depth + 1)
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }
}
