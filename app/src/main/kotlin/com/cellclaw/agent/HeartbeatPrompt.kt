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
            appendLine("Continue this task NOW. Use screen.read to check the current screen state, then take the next action(s) to make progress.")
            appendLine()
            appendLine("For repetitive tasks (swiping, scrolling, browsing), perform multiple actions in this batch — don't stop after one action. Keep going until you've done a substantial amount of work or the task is complete.")
            appendLine()
            appendLine("Only respond with HEARTBEAT_OK if you are genuinely waiting on something external (e.g., a download, another person's response, a timer). If the task requires YOU to keep acting, keep acting.")
        } else {
            appendLine("This is a periodic check-in. Look at the current screen to see if anything needs your attention.")
            appendLine()
            appendLine("If you see something that requires action based on prior conversation context, take appropriate action.")
        }

        appendLine()
        appendLine("If the task is blocked waiting on something external, reply with: HEARTBEAT_OK - <brief reason>")
        appendLine("If the task is fully complete, reply with: HEARTBEAT_OK - task complete")
        appendLine()
        appendLine("Rules:")
        appendLine("- Do NOT repeat or summarize previous actions")
        appendLine("- Do NOT invent tasks that weren't previously discussed")
        appendLine("- Keep responses minimal — this is a background check, not a conversation")
        appendLine("- Default to ACTION over HEARTBEAT_OK — if in doubt, read the screen and do something")
        appendLine("- If screen.read returns system_dialog=true, a system permission dialog is blocking the screen.")
        appendLine("  Use app.automate with action=handle_dialog and button=\"Allow\" (or \"Deny\") to dismiss it, then continue your task.")
    }
}
