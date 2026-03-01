package com.cellclaw.skills

import javax.inject.Inject
import javax.inject.Singleton

enum class SkillSource { BUNDLED, USER_CREATED, COMMUNITY }

data class Skill(
    val name: String,
    val description: String,
    val trigger: String,
    val steps: List<String>,
    val tools: List<String> = emptyList(),
    val category: String = "",
    val apps: List<String> = emptyList(),
    val source: SkillSource = SkillSource.BUNDLED
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
        var category = ""
        val apps = mutableListOf<String>()

        var section = "header"

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("# ") && !trimmed.startsWith("## ") -> {
                    name = trimmed.removePrefix("# ").trim()
                    section = "description"
                }
                trimmed.equals("## Trigger", ignoreCase = true) -> section = "trigger"
                trimmed.equals("## Steps", ignoreCase = true) -> section = "steps"
                trimmed.equals("## Tools", ignoreCase = true) -> section = "tools"
                trimmed.equals("## Category", ignoreCase = true) -> section = "category"
                trimmed.equals("## Apps", ignoreCase = true) -> section = "apps"
                trimmed.startsWith("## ") -> section = "other"
                trimmed.isNotEmpty() -> when (section) {
                    "description" -> description.appendLine(trimmed)
                    "trigger" -> trigger = trimmed
                    "category" -> category = trimmed
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
                    "apps" -> {
                        val app = trimmed.removePrefix("- ").removePrefix("* ").trim()
                        if (app.isNotBlank()) apps.add(app)
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
            tools = tools,
            category = category,
            apps = apps
        )
    }
}
