package com.cellclaw.skills

import javax.inject.Inject
import javax.inject.Singleton

data class Skill(
    val name: String,
    val description: String,
    val trigger: String,
    val steps: List<String>,
    val tools: List<String> = emptyList()
)

@Singleton
class SkillParser @Inject constructor() {

    /**
     * Parse a SKILL.md-format string into a Skill object.
     *
     * Expected format:
     * ```
     * # Skill Name
     * Description text
     *
     * ## Trigger
     * trigger phrase or pattern
     *
     * ## Steps
     * 1. Step one
     * 2. Step two
     *
     * ## Tools
     * - tool.name
     * - other.tool
     * ```
     */
    fun parse(content: String): Skill? {
        val lines = content.lines()
        if (lines.isEmpty()) return null

        var name = ""
        var description = StringBuilder()
        var trigger = ""
        val steps = mutableListOf<String>()
        val tools = mutableListOf<String>()

        var section = "header"

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("# ") && !trimmed.startsWith("## ") -> {
                    name = trimmed.removePrefix("# ").trim()
                    section = "description"
                }
                trimmed == "## Trigger" || trimmed == "## trigger" -> section = "trigger"
                trimmed == "## Steps" || trimmed == "## steps" -> section = "steps"
                trimmed == "## Tools" || trimmed == "## tools" -> section = "tools"
                trimmed.startsWith("## ") -> section = "other"
                trimmed.isNotEmpty() -> when (section) {
                    "description" -> description.appendLine(trimmed)
                    "trigger" -> trigger = trimmed
                    "steps" -> {
                        val step = trimmed.removePrefix("- ").let {
                            if (it.matches(Regex("^\\d+\\.\\s.*"))) it.replaceFirst(Regex("^\\d+\\.\\s"), "")
                            else it
                        }
                        if (step.isNotBlank()) steps.add(step)
                    }
                    "tools" -> {
                        val tool = trimmed.removePrefix("- ").removePrefix("* ").trim()
                        if (tool.isNotBlank()) tools.add(tool)
                    }
                }
            }
        }

        if (name.isBlank()) return null

        return Skill(
            name = name,
            description = description.toString().trim(),
            trigger = trigger,
            steps = steps,
            tools = tools
        )
    }
}
