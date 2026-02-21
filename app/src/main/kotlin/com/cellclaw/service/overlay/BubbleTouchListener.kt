package com.cellclaw.service.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class BubbleTouchListener(
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onTap: () -> Unit
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true
                }
                if (isDragging) {
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onTap()
                }
                return true
            }
        }
        return false
    }

    companion object {
        private const val DRAG_THRESHOLD = 10f // dp-equivalent at mdpi; good enough
    }
}
