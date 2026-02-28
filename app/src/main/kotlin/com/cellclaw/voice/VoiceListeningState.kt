package com.cellclaw.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ListeningPhase {
    IDLE,
    ACTIVATED,
    LISTENING,
    PROCESSING,
}

@Singleton
class VoiceListeningState @Inject constructor() {
    private val _phase = MutableStateFlow(ListeningPhase.IDLE)
    val phase: StateFlow<ListeningPhase> = _phase.asStateFlow()

    private val _displayText = MutableStateFlow("")
    val displayText: StateFlow<String> = _displayText.asStateFlow()

    fun setPhase(phase: ListeningPhase) {
        _phase.value = phase
    }

    fun setDisplayText(text: String) {
        _displayText.value = text
    }

    fun reset() {
        _phase.value = ListeningPhase.IDLE
        _displayText.value = ""
    }
}
