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
import com.cellclaw.voice.ListeningPhase
import com.cellclaw.voice.VoiceListeningState
import android.net.Uri
import android.provider.Settings
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
    @Inject lateinit var voiceListeningState: VoiceListeningState

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

    // Voice listening overlay
    private var voiceOverlay: LinearLayout? = null
    private var voiceMicIcon: View? = null
    private var voiceStatusText: TextView? = null
    private var voiceTranscriptText: TextView? = null
    private lateinit var voiceOverlayParams: WindowManager.LayoutParams
    private var voiceOverlayVisible = false
    private var micPulseAnimator: ObjectAnimator? = null

    // Help guide overlay
    private var helpGuideCard: ScrollView? = null
    private var helpGuideBackdrop: View? = null
    private lateinit var helpGuideParams: WindowManager.LayoutParams
    private var helpGuideVisible = false

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
        createVoiceOverlay()
        createHelpGuide()
        observeState()
        observeVisibility()
        observeVoiceListeningState()
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
        hideHelpGuide()
        hideVoiceOverlay()
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
            if (helpGuideVisible) {
                helpGuideCard?.visibility = View.INVISIBLE
                helpGuideBackdrop?.visibility = View.INVISIBLE
            }
            if (voiceOverlayVisible) voiceOverlay?.visibility = View.INVISIBLE
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
            if (helpGuideVisible) {
                helpGuideCard?.visibility = View.VISIBLE
                helpGuideBackdrop?.visibility = View.VISIBLE
            }
            if (voiceOverlayVisible) voiceOverlay?.visibility = View.VISIBLE
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
                dpToPx(200) // max height
            )
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
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

        // Icon button row (book, star, cogs)
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iconSize = dpToPx(36)
        val iconMargin = dpToPx(8)

        // Book button – show help guide
        val bookBtn = TextView(this).apply {
            text = "\u2139" // ℹ
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3A5E"))
            }
            background = bg
            setOnClickListener {
                togglePanel()
                showHelpGuide()
            }
        }
        iconRow.addView(bookBtn, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = iconMargin
        })

        // Star button – open the app
        val starBtn = TextView(this).apply {
            text = "\u2605" // ★
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3A5E"))
            }
            background = bg
            setOnClickListener { openApp() }
        }
        iconRow.addView(starBtn, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = iconMargin
        })

        // Cogs button – open app settings
        val cogsBtn = TextView(this).apply {
            text = "\u2699" // ⚙
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3A5E"))
            }
            background = bg
            setOnClickListener { openAppSettings() }
        }
        iconRow.addView(cogsBtn, LinearLayout.LayoutParams(iconSize, iconSize))

        panel.addView(iconRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

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

    private var hideButtonView: View? = null

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

        // Red X button – stop the AI agent
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
            setOnClickListener { stopAgent() }
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

        // Eye button – hide overlay only
        val eyeBtn = TextView(this).apply {
            text = "\uD83D\uDC41"  // 👁 eye symbol
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3A5E"))
            }
            background = bg
            setOnClickListener { hideOverlay() }
        }
        val eyeParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x + dpToPx(56 + 52)
            y = bubbleParams.y
        }
        windowManager.addView(eyeBtn, eyeParams)
        hideButtonView = eyeBtn

        stopButtonVisible = true

        // Auto-dismiss after 5 seconds
        stopDismissJob?.cancel()
        stopDismissJob = serviceScope?.launch {
            delay(5000)
            hideStopButton()
        }
    }

    private fun hideStopButton() {
        stopDismissJob?.cancel()
        if (stopButtonVisible) {
            stopButtonView?.let { windowManager.removeView(it) }
            stopBackdropView?.let { windowManager.removeView(it) }
            hideButtonView?.let { windowManager.removeView(it) }
            stopButtonView = null
            stopBackdropView = null
            hideButtonView = null
            stopButtonVisible = false
        }
    }

    private fun stopAgent() {
        hideStopButton()
        agentLoop.stop()
    }

    private fun hideOverlay() {
        hideStopButton()
        stopSelf()
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

    private fun openAppSettings() {
        if (panelVisible) togglePanel()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    // ── Help guide overlay ─────────────────────────────────────────────

    private fun createHelpGuide() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1E2E"))
                cornerRadius = dpToPx(16).toFloat()
            }
            background = bg
            setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
        }

        // Title
        content.addView(TextView(this).apply {
            text = "CellClaw Guide"
            setTextColor(Color.parseColor("#BB86FC"))
            textSize = 16f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Divider
        content.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A5E"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        ).apply { topMargin = dpToPx(8); bottomMargin = dpToPx(10) })

        // -- Shake shortcut section --
        addGuideSection(content, "\u26A1 Shake Shortcut",
            "Shake your phone 3 times quickly (within 1.5s) to activate voice commands. " +
            "A 3-second cooldown prevents accidental re-triggers.")

        // -- Color system section --
        addGuideSection(content, "\uD83C\uDFA8 Overlay Bubble Colors", null)
        addColorRow(content, "#4CAF50", "Green", "Ready & idle")
        addColorRow(content, "#FF9800", "Orange", "Thinking or working")
        addColorRow(content, "#FFC107", "Yellow", "Needs your approval")
        addColorRow(content, "#9E9E9E", "Grey", "Paused")
        addColorRow(content, "#B00020", "Dark Red", "Error occurred")

        // Spacing
        content.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(6)))

        // -- Logo section --
        addGuideSection(content, "\uD83D\uDD35 Logo",
            "The floating bubble uses the CellClaw target icon. " +
            "Its background color changes in real-time to reflect the current agent state shown above.")

        // -- Panel icons section --
        addGuideSection(content, "\uD83D\uDD27 Panel Icons", null)
        addActionRow(content, "\u2139 Info", "Open this guide")
        addActionRow(content, "\u2605 Star", "Open the full CellClaw app")
        addActionRow(content, "\u2699 Gear", "Open app settings")

        // -- Overlay actions section --
        addGuideSection(content, "\uD83D\uDC46 Overlay Actions", null)
        addActionRow(content, "Single Tap", "Open quick-reply text panel")
        addActionRow(content, "Double Tap", "Open the full CellClaw app")
        addActionRow(content, "Long Press", "Show stop / hide buttons")
        addActionRow(content, "Drag", "Move the bubble anywhere on screen")

        // Close hint
        content.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A5E"))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        ).apply { topMargin = dpToPx(10); bottomMargin = dpToPx(6) })

        content.addView(TextView(this).apply {
            text = "Tap anywhere outside to close"
            setTextColor(Color.parseColor("#888888"))
            textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this).apply {
            addView(content)
        }

        helpGuideParams = WindowManager.LayoutParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        helpGuideCard = scroll
    }

    private fun addGuideSection(parent: LinearLayout, title: String, body: String?) {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(4) })

        if (body != null) {
            parent.addView(TextView(this).apply {
                text = body
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2) })
        }
    }

    private fun addColorRow(parent: LinearLayout, hexColor: String, label: String, desc: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Color dot
        val dot = View(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hexColor))
            }
            background = bg
        }
        row.addView(dot, LinearLayout.LayoutParams(dpToPx(12), dpToPx(12)).apply {
            marginEnd = dpToPx(8)
        })

        // Label + description
        row.addView(TextView(this).apply {
            text = "$label — $desc"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
        })

        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(3); marginStart = dpToPx(8) })
    }

    private fun addActionRow(parent: LinearLayout, action: String, desc: String) {
        parent.addView(TextView(this).apply {
            text = "\u2022 $action: $desc"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(3); marginStart = dpToPx(8) })
    }

    private fun showHelpGuide() {
        if (helpGuideVisible) return

        // Backdrop to dismiss
        val backdrop = View(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { hideHelpGuide() }
        }
        val bdParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(backdrop, bdParams)
        helpGuideBackdrop = backdrop

        helpGuideCard?.let { windowManager.addView(it, helpGuideParams) }
        helpGuideVisible = true

        if (overlayHidden) {
            helpGuideCard?.visibility = View.INVISIBLE
            helpGuideBackdrop?.visibility = View.INVISIBLE
        }
    }

    private fun hideHelpGuide() {
        if (helpGuideVisible) {
            helpGuideCard?.let { windowManager.removeView(it) }
            helpGuideBackdrop?.let { windowManager.removeView(it) }
            helpGuideBackdrop = null
            helpGuideVisible = false
        }
    }

    // ── Voice listening overlay ────────────────────────────────────────────

    private fun createVoiceOverlay() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E2E"))
                cornerRadius = dpToPx(16).toFloat()
            }
            background = bg
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        // Pulsing mic circle
        val micSize = dpToPx(48)
        val mic = View(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#7C4DFF"))
            }
            background = bg
        }
        val micLp = LinearLayout.LayoutParams(micSize, micSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(mic, micLp)
        voiceMicIcon = mic

        // Status text
        val status = TextView(this).apply {
            text = "Listening..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val statusLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(10)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(status, statusLp)
        voiceStatusText = status

        // Transcript text
        val transcript = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
        }
        val transcriptLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(6)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(transcript, transcriptLp)
        voiceTranscriptText = transcript

        voiceOverlayParams = WindowManager.LayoutParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(100)
        }

        voiceOverlay = panel
    }

    private fun showVoiceOverlay() {
        if (!voiceOverlayVisible) {
            voiceOverlay?.let { windowManager.addView(it, voiceOverlayParams) }
            voiceOverlayVisible = true
            startMicPulse()
        }
        if (overlayHidden) voiceOverlay?.visibility = View.INVISIBLE
    }

    private fun hideVoiceOverlay() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        if (voiceOverlayVisible) {
            voiceOverlay?.let { windowManager.removeView(it) }
            voiceOverlayVisible = false
        }
    }

    private fun startMicPulse() {
        voiceMicIcon?.let { mic ->
            micPulseAnimator = ObjectAnimator.ofFloat(mic, "alpha", 1f, 0.4f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun observeVoiceListeningState() {
        serviceScope?.launch {
            voiceListeningState.phase.collect { phase ->
                when (phase) {
                    ListeningPhase.ACTIVATED -> {
                        voiceStatusText?.text = "Activated"
                        voiceTranscriptText?.text = ""
                        showVoiceOverlay()
                    }
                    ListeningPhase.LISTENING -> {
                        voiceStatusText?.text = "Listening..."
                    }
                    ListeningPhase.PROCESSING -> {
                        voiceStatusText?.text = "Processing..."
                        micPulseAnimator?.cancel()
                        voiceMicIcon?.alpha = 1f
                    }
                    ListeningPhase.IDLE -> {
                        hideVoiceOverlay()
                    }
                }
            }
        }
        serviceScope?.launch {
            voiceListeningState.displayText.collect { text ->
                if (text.isNotBlank()) {
                    voiceTranscriptText?.text = text
                }
            }
        }
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
                    AgentState.THINKING -> Color.parseColor("#FF9800")
                    AgentState.EXECUTING_TOOLS -> Color.parseColor("#FF9800")
                    AgentState.WAITING_APPROVAL -> Color.parseColor("#FFC107")
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
                    is AgentEvent.ThinkingText -> {
                        showStatus("Thinking\u2026")
                        showResponse(event.text)
                    }
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
        const val OVERLAY_NOTIFICATION_ID = 3
    }
}
