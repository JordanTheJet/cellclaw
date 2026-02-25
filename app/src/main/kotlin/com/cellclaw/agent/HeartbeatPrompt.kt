package com.cellclaw.agent

/**
 * Builds the heartbeat prompt sent to the agent on each tick.
 * The prompt instructs the agent to:
 *   1. Check the current screen state
 *   2. Decide if action is needed for the active task
 *   3. Reply HEARTBEAT_OK if nothing needs attention
 */
fun buildHeartbeatPrompt(taskContext: String?): String {
    return buildString {
        appendLine("[Heartbeat Check]")
        appendLine()

        if (taskContext != null) {
            appendLine("You are currently working on: $taskContext")
            appendLine()
            appendLine("Check the current screen state using screen.read (or screen.capture + vision.analyze for visual/game content). Determine if any action is needed to continue this task.")
            appendLine()
            appendLine("If you need to take action (tap, swipe, type, etc.), do so now using the appropriate tools.")
        } else {
            appendLine("This is a periodic check-in. Look at the current screen to see if anything needs your attention.")
            appendLine()
            appendLine("If you see something that requires action based on prior conversation context, take appropriate action.")
        }

        appendLine()
        appendLine("If nothing needs attention right now, reply with exactly: HEARTBEAT_OK")
        appendLine("You may include a brief status note after HEARTBEAT_OK (e.g., \"HEARTBEAT_OK - chess opponent hasn't moved yet\").")
        appendLine()
        appendLine("If the task appears to be complete, say: HEARTBEAT_OK - task complete")
        appendLine()
        appendLine("Rules:")
        appendLine("- Do NOT repeat or summarize previous actions")
        appendLine("- Do NOT invent tasks that weren't previously discussed")
        appendLine("- Keep responses minimal — this is a background check, not a conversation")
    }
}
