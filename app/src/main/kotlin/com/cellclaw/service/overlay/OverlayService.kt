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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.tools.ScreenCaptureTool
import com.cellclaw.tools.VisionAnalyzeTool
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

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var panelView: LinearLayout? = null
    private var panelVisible = false
    private var serviceScope: CoroutineScope? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    // Panel child views for dynamic updates
    private var approveBtn: TextView? = null
    private var denyBtn: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(OVERLAY_NOTIFICATION_ID, buildOverlayNotification())
        createBubble()
        createPanel()
        observeState()
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        bubbleView?.let { windowManager.removeView(it) }
        panelView?.let { if (panelVisible) windowManager.removeView(it) }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(200)
        }

        bubble.setOnTouchListener(BubbleTouchListener(windowManager, bubbleParams) {
            togglePanel()
        })

        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble
    }

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

        // "Explain Screen" button
        val explainBtn = createPanelButton("Explain Screen").apply {
            setOnClickListener {
                togglePanel()
                handleExplainScreen()
            }
        }
        panel.addView(explainBtn)

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
            panelVisible = false
        } else {
            // Position panel next to bubble
            panelParams.x = bubbleParams.x + dpToPx(56)
            panelParams.y = bubbleParams.y
            panelView?.let { windowManager.addView(it, panelParams) }
            panelVisible = true
        }
    }

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

    private fun observeState() {
        serviceScope?.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope?.launch {
            combine(agentLoop.state, approvalQueue.requests) { state, requests ->
                Pair(state, requests)
            }.collect { (state, requests) ->
                // Tint bubble based on state
                val color = when (state) {
                    AgentState.IDLE -> Color.parseColor("#6200EE")
                    AgentState.THINKING -> Color.parseColor("#FF9800")
                    AgentState.EXECUTING_TOOLS -> Color.parseColor("#2196F3")
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
        const val OVERLAY_NOTIFICATION_ID = 2
        private const val EXPLAIN_NOTIFICATION_ID = 101
    }
}
