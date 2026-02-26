package com.cellclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

class CellClawAccessibility : AccessibilityService() {

    private var screenWidth = 1080
    private var screenHeight = 2400

    // Volume hotkey tracking: long-press both volume buttons to activate
    private var volumeUpDown = false
    private var volumeDownDown = false
    private var hotKeyHandler: android.os.Handler? = null
    private var hotKeyPending: Runnable? = null

    private val actionReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_COMMAND) return

            val resultReceiver = intent.getParcelableExtra<ResultReceiver>("result_receiver")
            val action = intent.getStringExtra("action") ?: return

            Log.d(TAG, "Received action: $action")

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
                "get_foreground_package" -> buildJsonObject {
                    put("package", rootInActiveWindow?.packageName?.toString() ?: "unknown")
                }
                "find_element" -> findElement(intent.getStringExtra("text") ?: "")
                "screenshot" -> {
                    handleScreenshot(resultReceiver)
                    return // Result sent asynchronously
                }
                "wait_and_read" -> {
                    // Small delay then read — useful after transitions
                    android.os.Handler(mainLooper).postDelayed({
                        val r = readScreen()
                        sendResult(resultReceiver, r)
                    }, intent.getLongExtra("delay_ms", 500))
                    return // Don't send result immediately
                }
                else -> buildJsonObject {
                    put("error", "Unknown action: $action")
                }
            }

            sendResult(resultReceiver, result)
        }
    }

    private fun sendResult(receiver: ResultReceiver?, result: JsonObject) {
        receiver?.send(0, Bundle().apply {
            putString("result", result.toString())
        })
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateScreenDimensions()
        hotKeyHandler = android.os.Handler(mainLooper)

        val filter = IntentFilter(ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_EXPORTED so broadcasts from the test process (different PID) can reach us.
            // Safety: we only act on intents with our specific action + setPackage.
            registerReceiver(actionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        AccessibilityBridge.onServiceConnected()
        Log.d(TAG, "Accessibility service connected (${screenWidth}x${screenHeight})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpDown = true
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volumeDownDown = true

                    if (volumeUpDown && volumeDownDown && hotKeyPending == null) {
                        // Both buttons held — start 1-second timer
                        val runnable = Runnable {
                            hotKeyPending = null
                            if (volumeUpDown && volumeDownDown) {
                                Log.d(TAG, "Hotkey activated: both volume buttons held")
                                triggerVoiceActivation()
                            }
                        }
                        hotKeyPending = runnable
                        hotKeyHandler?.postDelayed(runnable, HOTKEY_HOLD_MS)
                    }
                    // Consume the event so volume doesn't change
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpDown = false
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volumeDownDown = false

                    // Cancel pending hotkey if a button was released too early
                    hotKeyPending?.let {
                        hotKeyHandler?.removeCallbacks(it)
                        hotKeyPending = null
                    }
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerVoiceActivation() {
        val intent = Intent(this, com.cellclaw.wakeword.WakeWordService::class.java).apply {
            action = com.cellclaw.wakeword.WakeWordService.ACTION_ACTIVATE
        }
        startService(intent)
    }

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

    // ── Screenshot ─────────────────────────────────────────────────────

    private fun handleScreenshot(resultReceiver: ResultReceiver?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 26-29: fallback to screencap shell command
            try {
                val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", file.absolutePath))
                val exitCode = proc.waitFor()
                if (exitCode == 0 && file.exists()) {
                    sendResult(resultReceiver, buildJsonObject {
                        put("success", true)
                        put("file_path", file.absolutePath)
                    })
                } else {
                    sendResult(resultReceiver, errorResult("screencap failed with exit code $exitCode"))
                }
            } catch (e: Exception) {
                sendResult(resultReceiver, errorResult("Screenshot failed: ${e.message}"))
            }
            return
        }

        // API 30+: use AccessibilityService.takeScreenshot
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (bitmap == null) {
                        sendResult(resultReceiver, errorResult("Failed to create bitmap from screenshot"))
                        result.hardwareBuffer.close()
                        return
                    }

                    // Downscale to 540px width to keep base64 size reasonable
                    val scale = 540f / bitmap.width
                    val scaledWidth = 540
                    val scaledHeight = (bitmap.height * scale).toInt()
                    val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val scaled = Bitmap.createScaledBitmap(softBitmap, scaledWidth, scaledHeight, true)

                    val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                    val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
                    }

                    if (scaled !== softBitmap) scaled.recycle()
                    softBitmap.recycle()
                    bitmap.recycle()
                    result.hardwareBuffer.close()

                    sendResult(resultReceiver, buildJsonObject {
                        put("success", true)
                        put("file_path", file.absolutePath)
                    })
                } catch (e: Exception) {
                    sendResult(resultReceiver, errorResult("Screenshot save failed: ${e.message}"))
                }
            }

            override fun onFailure(errorCode: Int) {
                sendResult(resultReceiver, errorResult("Screenshot failed with error code $errorCode"))
            }
        })
    }

    companion object {
        private const val TAG = "CellClawA11y"
        const val ACTION_COMMAND = "com.cellclaw.ACCESSIBILITY_ACTION"
        private const val HOTKEY_HOLD_MS = 800L  // Hold both volume buttons for 800ms
    }
}
