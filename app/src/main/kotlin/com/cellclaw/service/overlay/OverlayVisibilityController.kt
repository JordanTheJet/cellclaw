package com.cellclaw.service.overlay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates overlay visibility across tools and the OverlayService.
 * Tools call [requestHide] before performing screenshots or taps so the
 * overlay bubble doesn't appear in captured images or interfere with gestures.
 * The OverlayService observes [hideRequests] and auto-restores after the delay.
 */
@Singleton
class OverlayVisibilityController @Inject constructor() {

    data class HideRequest(val durationMs: Long)

    private val _hideRequests = MutableSharedFlow<HideRequest>(extraBufferCapacity = 4)
    val hideRequests = _hideRequests.asSharedFlow()

    fun requestHide(durationMs: Long = 400) {
        _hideRequests.tryEmit(HideRequest(durationMs))
    }
}
