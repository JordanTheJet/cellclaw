package com.cellclaw.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: SkillParser
) {
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun loadBundledSkills() {
        val bundled = mutableListOf<Skill>()

        // Load from assets
        try {
            val assetFiles = context.assets.list("skills") ?: emptyArray()
            for (file in assetFiles) {
                if (file.endsWith(".md")) {
                    val content = context.assets.open("skills/$file").bufferedReader().readText()
                    parser.parse(content)?.let { bundled.add(it) }
                }
            }
        } catch (_: Exception) {
            // No bundled skills directory
        }

        // Load from app-private storage (user-created skills)
        val skillsDir = File(context.filesDir, "skills")
        if (skillsDir.exists()) {
            skillsDir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                parser.parse(file.readText())?.let { bundled.add(it) }
            }
        }

        _skills.value = bundled
    }

    fun addSkill(skill: Skill) {
        _skills.value = _skills.value + skill
        // Persist to disk
        val skillsDir = File(context.filesDir, "skills")
        skillsDir.mkdirs()
        val file = File(skillsDir, "${skill.name.lowercase().replace(" ", "_")}.md")
        file.writeText(buildSkillMarkdown(skill))
    }

    fun removeSkill(name: String) {
        _skills.value = _skills.value.filter { it.name != name }
        val skillsDir = File(context.filesDir, "skills")
        val file = File(skillsDir, "${name.lowercase().replace(" ", "_")}.md")
        file.delete()
    }

    fun findByTrigger(input: String): Skill? {
        return _skills.value.firstOrNull { skill ->
            input.contains(skill.trigger, ignoreCase = true)
        }
    }

    /** Build system prompt section describing available skills */
    fun buildSkillsPrompt(): String {
        val skillList = _skills.value
        if (skillList.isEmpty()) return ""

        return buildString {
            appendLine("\n## Available Skills")
            for (skill in skillList) {
                appendLine("### ${skill.name}")
                appendLine(skill.description)
                appendLine("Trigger: \"${skill.trigger}\"")
                if (skill.steps.isNotEmpty()) {
                    appendLine("Steps:")
                    skill.steps.forEachIndexed { i, step ->
                        appendLine("  ${i + 1}. $step")
                    }
                }
                appendLine()
            }
        }
    }

    private fun buildSkillMarkdown(skill: Skill): String = buildString {
        appendLine("# ${skill.name}")
        appendLine(skill.description)
        appendLine()
        appendLine("## Trigger")
        appendLine(skill.trigger)
        appendLine()
        if (skill.steps.isNotEmpty()) {
            appendLine("## Steps")
            skill.steps.forEachIndexed { i, step ->
                appendLine("${i + 1}. $step")
            }
            appendLine()
        }
        if (skill.tools.isNotEmpty()) {
            appendLine("## Tools")
            skill.tools.forEach { appendLine("- $it") }
        }
    }
}
