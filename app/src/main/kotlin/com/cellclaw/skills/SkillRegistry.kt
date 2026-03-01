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

    /** Raw markdown content keyed by skill name, for lazy-load reads */
    private val skillContent = mutableMapOf<String, String>()

    init {
        loadBundledSkills()
    }

    fun loadBundledSkills() {
        val all = mutableListOf<Skill>()
        skillContent.clear()

        // Load from assets (bundled skills)
        try {
            val assetFiles = context.assets.list("skills") ?: emptyArray()
            for (file in assetFiles) {
                if (file.endsWith(".md")) {
                    val content = context.assets.open("skills/$file").bufferedReader().readText()
                    parser.parse(content)?.let { skill ->
                        all.add(skill.copy(source = SkillSource.BUNDLED))
                        skillContent[skill.name] = content
                    }
                }
            }
        } catch (_: Exception) {
            // No bundled skills directory
        }

        // Load from app-private storage (user-created + community-installed)
        val skillsDir = File(context.filesDir, "skills")
        if (skillsDir.exists()) {
            skillsDir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                val content = file.readText()
                parser.parse(content)?.let { skill ->
                    val source = if (file.name.startsWith("community_")) {
                        SkillSource.COMMUNITY
                    } else {
                        SkillSource.USER_CREATED
                    }
                    all.add(skill.copy(source = source))
                    skillContent[skill.name] = content
                }
            }
        }

        _skills.value = all
    }

    fun addSkill(skill: Skill) {
        _skills.value = _skills.value + skill
        val markdown = buildSkillMarkdown(skill)
        skillContent[skill.name] = markdown
        // Persist to disk
        val skillsDir = File(context.filesDir, "skills")
        skillsDir.mkdirs()
        val prefix = if (skill.source == SkillSource.COMMUNITY) "community_" else ""
        val file = File(skillsDir, "$prefix${skill.name.lowercase().replace(" ", "_")}.md")
        file.writeText(markdown)
    }

    fun removeSkill(name: String) {
        val skill = _skills.value.find { it.name == name } ?: return
        // Don't allow removing bundled skills
        if (skill.source == SkillSource.BUNDLED) return

        _skills.value = _skills.value.filter { it.name != name }
        skillContent.remove(name)
        val skillsDir = File(context.filesDir, "skills")
        val prefix = if (skill.source == SkillSource.COMMUNITY) "community_" else ""
        val file = File(skillsDir, "$prefix${name.lowercase().replace(" ", "_")}.md")
        file.delete()
    }

    fun findByTrigger(input: String): Skill? {
        return _skills.value.firstOrNull { skill ->
            skill.trigger.isNotBlank() && input.contains(skill.trigger, ignoreCase = true)
        }
    }

    /** Get the full markdown content for a skill (for lazy-load reads by the agent) */
    fun getSkillContent(name: String): String? {
        return skillContent[name]
    }

    /**
     * Build a compact system prompt manifest (name + description + trigger only).
     * The agent uses skill.read to get full details on demand.
     */
    fun buildSkillsPrompt(): String {
        val skillList = _skills.value
        if (skillList.isEmpty()) return ""

        return buildString {
            appendLine("\n## Available Skills")
            appendLine("When the user's request matches a skill trigger, use the skill.read tool to get the full skill instructions before executing.")
            appendLine()
            for (skill in skillList) {
                val categoryTag = if (skill.category.isNotBlank()) " [${skill.category}]" else ""
                appendLine("- **${skill.name}**$categoryTag: ${skill.description}. Trigger: \"${skill.trigger}\"")
            }
        }
    }

    fun buildSkillMarkdown(skill: Skill): String = buildString {
        appendLine("# ${skill.name}")
        appendLine(skill.description)
        appendLine()
        if (skill.category.isNotBlank()) {
            appendLine("## Category")
            appendLine(skill.category)
            appendLine()
        }
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
        if (skill.apps.isNotEmpty()) {
            appendLine("## Apps")
            skill.apps.forEach { appendLine("- $it") }
            appendLine()
        }
        if (skill.tools.isNotEmpty()) {
            appendLine("## Tools")
            skill.tools.forEach { appendLine("- $it") }
        }
    }
}
