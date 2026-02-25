package com.cellclaw.service.overlay

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class BubbleTouchListener(
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onDoubleTap: (() -> Unit)? = null,
    private val onDrag: ((x: Int, y: Int) -> Unit)? = null,
    private val onLongPress: (() -> Unit)? = null
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var longPressTriggered = false

    private var lastTapTime = 0L
    private var pendingSingleTap: Runnable = Runnable {}
    private var pendingLongPress: Runnable = Runnable {}

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressTriggered = false

                pendingLongPress = Runnable {
                    longPressTriggered = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress?.invoke()
                }
                view.postDelayed(pendingLongPress, LONG_PRESS_TIMEOUT)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true
                    view.removeCallbacks(pendingLongPress)
                }
                if (isDragging) {
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                    onDrag?.invoke(layoutParams.x, layoutParams.y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(pendingLongPress)
                if (!isDragging && !longPressTriggered) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                        lastTapTime = 0
                        view.removeCallbacks(pendingSingleTap)
                        onDoubleTap?.invoke()
                    } else {
                        lastTapTime = now
                        pendingSingleTap = Runnable { onTap() }
                        view.postDelayed(pendingSingleTap, DOUBLE_TAP_TIMEOUT)
                    }
                }
                return true
            }
        }
        return false
    }

    companion object {
        private const val DRAG_THRESHOLD = 10f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val LONG_PRESS_TIMEOUT = 500L
    }
}
