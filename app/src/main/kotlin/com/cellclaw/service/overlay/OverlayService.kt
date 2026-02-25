package com.cellclaw.service.overlay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentEvent
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.tools.ScreenCaptureTool
import com.cellclaw.tools.VisionAnalyzeTool
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var approvalQueue: ApprovalQueue
    @Inject lateinit var screenCaptureTool: ScreenCaptureTool
    @Inject lateinit var visionAnalyzeTool: VisionAnalyzeTool
    @Inject lateinit var visibilityController: OverlayVisibilityController

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var panelView: LinearLayout? = null
    private var backdropView: View? = null
    private var panelVisible = false
    private var serviceScope: CoroutineScope? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var backdropParams: WindowManager.LayoutParams
    private var statusView: TextView? = null
    private lateinit var statusParams: WindowManager.LayoutParams
    private var statusVisible = false
    private var fadeJob: Job? = null

    // Track whether overlay is temporarily hidden
    private var overlayHidden = false
    private var restoreJob: Job? = null

    // Stop button shown on long-press
    private var stopButtonView: TextView? = null
    private var stopBackdropView: View? = null
    private var stopButtonVisible = false
    private var stopDismissJob: Job? = null

    // Response card for showing assistant text
    private var responseCard: LinearLayout? = null
    private var responseText: TextView? = null
    private lateinit var responseParams: WindowManager.LayoutParams
    private var responseVisible = false
    private var responseFadeJob: Job? = null

    // Panel child views for dynamic updates
    private var approveBtn: TextView? = null
    private var denyBtn: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(OVERLAY_NOTIFICATION_ID, buildOverlayNotification())
        createBubble()
        createStatusView()
        createResponseCard()
        createPanel()
        observeState()
        observeVisibility()
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        hideStopButton()
        bubbleView?.let { windowManager.removeView(it) }
        panelView?.let { if (panelVisible) windowManager.removeView(it) }
        backdropView?.let { if (panelVisible) windowManager.removeView(it) }
        statusView?.let { if (statusVisible) windowManager.removeView(it) }
        responseCard?.let { if (responseVisible) windowManager.removeView(it) }
        bubbleView = null
        panelView = null
        backdropView = null
        statusView = null
        responseCard = null
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    // ── Visibility hide/show ─────────────────────────────────────────────

    private fun observeVisibility() {
        serviceScope?.launch {
            visibilityController.hideRequests.collect { request ->
                hideOverlayTemporarily(request.durationMs)
            }
        }
    }

    private fun hideOverlayTemporarily(durationMs: Long) {
        restoreJob?.cancel()
        if (!overlayHidden) {
            overlayHidden = true
            bubbleView?.visibility = View.INVISIBLE
            if (statusVisible) statusView?.visibility = View.INVISIBLE
            if (panelVisible) panelView?.visibility = View.INVISIBLE
            if (panelVisible) backdropView?.visibility = View.INVISIBLE
            if (responseVisible) responseCard?.visibility = View.INVISIBLE
            if (stopButtonVisible) {
                stopButtonView?.visibility = View.INVISIBLE
                stopBackdropView?.visibility = View.INVISIBLE
            }
        }
        restoreJob = serviceScope?.launch {
            delay(durationMs)
            restoreOverlay()
        }
    }

    private fun restoreOverlay() {
        if (overlayHidden) {
            overlayHidden = false
            bubbleView?.visibility = View.VISIBLE
            if (statusVisible) statusView?.visibility = View.VISIBLE
            if (panelVisible) panelView?.visibility = View.VISIBLE
            if (panelVisible) backdropView?.visibility = View.VISIBLE
            if (responseVisible) responseCard?.visibility = View.VISIBLE
            if (stopButtonVisible) {
                stopButtonView?.visibility = View.VISIBLE
                stopBackdropView?.visibility = View.VISIBLE
            }
        }
    }

    // ── Bubble ───────────────────────────────────────────────────────────

    private fun createBubble() {
        val size = dpToPx(48)

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_notification)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
            }
            background = bg
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            setColorFilter(Color.WHITE)
        }

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(200)
        }

        bubble.setOnTouchListener(BubbleTouchListener(
            windowManager, bubbleParams,
            onTap = { togglePanel() },
            onDoubleTap = { openApp() },
            onDrag = { x, y -> updateStatusPosition(x, y) },
            onLongPress = { showStopButton() }
        ))

        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble
    }

    // ── Status label ─────────────────────────────────────────────────────

    private fun createStatusView() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1E1E2E"))
                cornerRadius = dpToPx(12).toFloat()
            }
            background = bg
        }

        statusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x + dpToPx(56)
            y = bubbleParams.y + dpToPx(8)
            width = dpToPx(220)
        }

        statusView = tv
    }

    private fun showStatus(text: String) {
        statusView?.let { tv ->
            tv.text = text
            statusParams.x = bubbleParams.x + dpToPx(56)
            statusParams.y = bubbleParams.y + dpToPx(8)
            if (!statusVisible) {
                windowManager.addView(tv, statusParams)
                statusVisible = true
            } else {
                windowManager.updateViewLayout(tv, statusParams)
            }
            // Respect current hide state
            if (overlayHidden) tv.visibility = View.INVISIBLE
        }
    }

    private fun hideStatus() {
        if (statusVisible) {
            statusView?.let { windowManager.removeView(it) }
            statusVisible = false
        }
    }

    private fun updateStatusPosition(bubbleX: Int, bubbleY: Int) {
        if (statusVisible) {
            statusParams.x = bubbleX + dpToPx(56)
            statusParams.y = bubbleY + dpToPx(8)
            statusView?.let { windowManager.updateViewLayout(it, statusParams) }
        }
    }

    // ── Response card ─────────────────────────────────────────────────────

    private fun createResponseCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E2E"))
                cornerRadius = dpToPx(12).toFloat()
            }
            background = bg
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
        }

        val label = TextView(this).apply {
            text = "CellClaw"
            setTextColor(Color.parseColor("#BB86FC"))
            textSize = 11f
        }
        card.addView(label)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(120) // max height
            )
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
        }
        scroll.addView(tv)
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(4) })

        // Tap to dismiss
        card.setOnClickListener { hideResponse() }

        responseParams = WindowManager.LayoutParams(
            dpToPx(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x + dpToPx(56)
            y = bubbleParams.y + dpToPx(30)
        }

        responseCard = card
        responseText = tv
    }

    private fun showResponse(text: String) {
        responseFadeJob?.cancel()
        responseText?.text = text
        responseParams.x = bubbleParams.x + dpToPx(56)
        responseParams.y = bubbleParams.y + dpToPx(30)
        if (!responseVisible) {
            responseCard?.let { windowManager.addView(it, responseParams) }
            responseVisible = true
        } else {
            responseCard?.let { windowManager.updateViewLayout(it, responseParams) }
        }
        if (overlayHidden) responseCard?.visibility = View.INVISIBLE

        // Auto-dismiss after 8 seconds
        responseFadeJob = serviceScope?.launch {
            delay(8000)
            hideResponse()
        }
    }

    private fun hideResponse() {
        responseFadeJob?.cancel()
        if (responseVisible) {
            responseCard?.let { windowManager.removeView(it) }
            responseVisible = false
        }
    }

    // ── Panel ────────────────────────────────────────────────────────────

    private fun createPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dpToPx(16).toFloat()
            }
            background = bg
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }

        // Quick Ask row (input + send button)
        val askRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val askInput = EditText(this).apply {
            hint = "Quick ask..."
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A3E"))
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO) {
                    submitQuickAsk(v as EditText)
                    true
                } else false
            }
        }
        askRow.addView(askInput, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        val sendBtn = TextView(this).apply {
            text = ">"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#6200EE"))
                cornerRadius = dpToPx(4).toFloat()
            }
            background = bg
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setOnClickListener { submitQuickAsk(askInput) }
        }
        val sendLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { marginStart = dpToPx(4) }
        askRow.addView(sendBtn, sendLp)

        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(8) }
        panel.addView(askRow, rowParams)

        // Approve button (hidden by default)
        approveBtn = createPanelButton("Approve All").apply {
            visibility = View.GONE
            setOnClickListener {
                approvalQueue.respondAll(ApprovalResult.APPROVED)
            }
        }
        val approveLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(8) }
        panel.addView(approveBtn, approveLp)

        // Deny button (hidden by default)
        denyBtn = createPanelButton("Deny All").apply {
            visibility = View.GONE
            setOnClickListener {
                approvalQueue.respondAll(ApprovalResult.DENIED)
            }
        }
        val denyLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(4) }
        panel.addView(denyBtn, denyLp)

        panelParams = WindowManager.LayoutParams(
            dpToPx(240),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(72)
            y = dpToPx(200)
        }

        panelView = panel

        // Fullscreen transparent backdrop to catch outside touches
        backdropView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { togglePanel() }
        }
        backdropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun createPanelButton(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A5E"))
                cornerRadius = dpToPx(8).toFloat()
            }
            background = bg
        }
    }

    private fun openApp() {
        if (panelVisible) togglePanel()
        val intent = Intent(this, com.cellclaw.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun submitQuickAsk(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) {
            agentLoop.submitMessage(text)
            input.text.clear()
            togglePanel()
        }
    }

    private fun togglePanel() {
        if (panelVisible) {
            panelView?.let { windowManager.removeView(it) }
            backdropView?.let { windowManager.removeView(it) }
            panelVisible = false
        } else {
            // Add backdrop first (behind panel) to catch outside taps
            backdropView?.let { windowManager.addView(it, backdropParams) }
            // Position panel next to bubble
            panelParams.x = bubbleParams.x + dpToPx(56)
            panelParams.y = bubbleParams.y
            panelView?.let { windowManager.addView(it, panelParams) }
            panelVisible = true
        }
    }

    // ── Stop button (long-press) ────────────────────────────────────────

    private fun showStopButton() {
        if (stopButtonVisible) return
        // Close panel if open
        if (panelVisible) togglePanel()

        val size = dpToPx(48)

        // Backdrop to dismiss on outside tap
        val backdrop = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideStopButton() }
        }
        val bdParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(backdrop, bdParams)
        stopBackdropView = backdrop

        // Red stop button
        val btn = TextView(this).apply {
            text = "\u2716"  // ✖ symbol
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
            }
            background = bg
            setOnClickListener { stopEverything() }
        }
        val btnParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x + dpToPx(56)
            y = bubbleParams.y
        }
        windowManager.addView(btn, btnParams)
        stopButtonView = btn
        stopButtonVisible = true

        // Auto-dismiss after 3 seconds
        stopDismissJob?.cancel()
        stopDismissJob = serviceScope?.launch {
            delay(3000)
            hideStopButton()
        }
    }

    private fun hideStopButton() {
        stopDismissJob?.cancel()
        if (stopButtonVisible) {
            stopButtonView?.let { windowManager.removeView(it) }
            stopBackdropView?.let { windowManager.removeView(it) }
            stopButtonView = null
            stopBackdropView = null
            stopButtonVisible = false
        }
    }

    private fun stopEverything() {
        hideStopButton()
        // Send stop intent to CellClawService
        val intent = Intent(this, com.cellclaw.service.CellClawService::class.java).apply {
            action = com.cellclaw.service.CellClawService.ACTION_STOP
        }
        startService(intent)
        // Also stop this overlay service
        stopSelf()
    }

    // ── Explain Screen ───────────────────────────────────────────────────

    private fun handleExplainScreen() {
        serviceScope?.launch {
            try {
                val captureResult = screenCaptureTool.execute(
                    buildJsonObject { put("include_base64", false) }
                )
                if (!captureResult.success) return@launch

                val filePath = captureResult.data?.let { data ->
                    (data as? JsonObject)?.get("file_path")
                        ?.let { (it as? JsonPrimitive)?.content }
                } ?: return@launch

                val analyzeResult = visionAnalyzeTool.execute(buildJsonObject {
                    put("file_path", filePath)
                    put("question", "Describe what you see on the screen. What app is open? What is the user looking at?")
                })

                if (analyzeResult.success) {
                    val analysis = analyzeResult.data?.let { data ->
                        (data as? JsonObject)?.get("analysis")
                            ?.let { (it as? JsonPrimitive)?.content }
                    } ?: "Analysis complete"
                    postResultNotification(analysis)
                }
            } catch (_: Exception) {}
        }
    }

    private fun postResultNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CellClawApp.CHANNEL_ALERTS)
            .setContentTitle("Screen Analysis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(EXPLAIN_NOTIFICATION_ID, notification)
    }

    // ── State observation ────────────────────────────────────────────────

    private fun observeState() {
        serviceScope?.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope?.launch {
            combine(agentLoop.state, approvalQueue.requests) { state, requests ->
                Pair(state, requests)
            }.collect { (state, requests) ->
                // Tint bubble based on state
                val color = when (state) {
                    AgentState.IDLE -> Color.parseColor("#4CAF50")
                    AgentState.THINKING -> Color.parseColor("#2196F3")
                    AgentState.EXECUTING_TOOLS -> Color.parseColor("#FF9800")
                    AgentState.WAITING_APPROVAL -> Color.parseColor("#F44336")
                    AgentState.PAUSED -> Color.parseColor("#9E9E9E")
                    AgentState.ERROR -> Color.parseColor("#B00020")
                }
                bubbleView?.let { bubble ->
                    val bg = bubble.background as? GradientDrawable
                    bg?.setColor(color)
                }

                // Show/hide approve/deny buttons
                val hasPending = requests.isNotEmpty()
                approveBtn?.visibility = if (hasPending) View.VISIBLE else View.GONE
                denyBtn?.visibility = if (hasPending) View.VISIBLE else View.GONE

                // Auto-hide status when idle
                if (state == AgentState.IDLE) {
                    fadeJob?.cancel()
                    hideStatus()
                }
            }
        }

        // Observe agent events for status text
        serviceScope?.launch {
            Log.d(TAG, "Starting event collector")
            agentLoop.events.collect { event ->
                Log.d(TAG, "Event: $event")
                fadeJob?.cancel()
                when (event) {
                    is AgentEvent.ThinkingText -> showStatus("Thinking\u2026")
                    is AgentEvent.ToolCallStart -> showStatus("Calling ${event.name}\u2026")
                    is AgentEvent.ToolCallResult -> {
                        showStatus("Done: ${event.name}")
                        fadeJob = launch {
                            delay(2000)
                            hideStatus()
                        }
                    }
                    is AgentEvent.AssistantText -> {
                        Log.d(TAG, "AssistantText: ${event.text.take(80)}")
                        showStatus("Responding\u2026")
                        showResponse(event.text)
                    }
                    is AgentEvent.Error -> showStatus("Error: ${event.message}")
                    else -> {}
                }
            }
        }
    }

    private fun buildOverlayNotification(): Notification {
        return NotificationCompat.Builder(this, CellClawApp.CHANNEL_SERVICE)
            .setContentTitle("CellClaw")
            .setContentText("Overlay active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "OverlayService"
        const val OVERLAY_NOTIFICATION_ID = 2
        private const val EXPLAIN_NOTIFICATION_ID = 101
    }
}
