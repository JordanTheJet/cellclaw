package com.cellclaw.agent

/**
 * Analyzes agent response text to determine the heartbeat result.
 * Mirrors OpenClaw's HEARTBEAT_OK token detection.
 */
object HeartbeatDetector {

    private val HEARTBEAT_OK_PATTERN = Regex(
        """(?i)\*{0,2}HEARTBEAT_OK\*{0,2}"""
    )

    private val TASK_COMPLETE_PATTERN = Regex(
        """(?i)HEARTBEAT_OK\s*[-\u2014:]\s*task\s+complete"""
    )

    data class DetectionResult(
        val heartbeatResult: HeartbeatResult,
        val isTaskComplete: Boolean,
        val statusNote: String?
    )

    /**
     * Analyze response text blocks and tool call count to determine heartbeat result.
     *
     * @param responseTexts  All text blocks from the agent response
     * @param hadToolCalls   Whether the response included tool use
     */
    fun analyze(responseTexts: List<String>, hadToolCalls: Boolean): DetectionResult {
        val fullText = responseTexts.joinToString(" ")

        // If there were tool calls, the agent acted regardless of text
        if (hadToolCalls) {
            val isComplete = TASK_COMPLETE_PATTERN.containsMatchIn(fullText)
            return DetectionResult(
                heartbeatResult = HeartbeatResult.ACTED,
                isTaskComplete = isComplete,
                statusNote = extractStatusNote(fullText)
            )
        }

        // Check for HEARTBEAT_OK token
        if (HEARTBEAT_OK_PATTERN.containsMatchIn(fullText)) {
            val isComplete = TASK_COMPLETE_PATTERN.containsMatchIn(fullText)
            return DetectionResult(
                heartbeatResult = HeartbeatResult.OK_NOTHING_TO_DO,
                isTaskComplete = isComplete,
                statusNote = extractStatusNote(fullText)
            )
        }

        // Text response without tool calls and without HEARTBEAT_OK —
        // treat as "acted" since the agent chose to respond with substantive text
        return DetectionResult(
            heartbeatResult = HeartbeatResult.ACTED,
            isTaskComplete = false,
            statusNote = null
        )
    }

    /**
     * Extract the status note after HEARTBEAT_OK, if any.
     * e.g., "HEARTBEAT_OK - chess opponent hasn't moved yet" -> "chess opponent hasn't moved yet"
     */
    private fun extractStatusNote(text: String): String? {
        val match = Regex("""(?i)HEARTBEAT_OK\s*[-\u2014:]\s*(.+)""").find(text)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
