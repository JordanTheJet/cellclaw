package com.cellclaw.tools

import com.cellclaw.skills.SkillRegistry
import kotlinx.serialization.json.*
import javax.inject.Inject

class SkillReadTool @Inject constructor(
    private val skillRegistry: SkillRegistry
) : Tool {
    override val name = "skill.read"
    override val description = "Read the full instructions for an installed skill. Use this when a user's request matches a skill trigger to get detailed steps before executing."
    override val parameters = ToolParameters(
        properties = mapOf(
            "name" to ParameterProperty("string", "The name of the skill to read (e.g. \"Weather Check\", \"Find Places\")")
        ),
        required = listOf("name")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val skillName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'name' parameter")

        // Try exact match first, then case-insensitive
        val content = skillRegistry.getSkillContent(skillName)
            ?: skillRegistry.skills.value
                .firstOrNull { it.name.equals(skillName, ignoreCase = true) }
                ?.let { skillRegistry.getSkillContent(it.name) }
            ?: return ToolResult.error("Skill not found: \"$skillName\". Use the skill names from the Available Skills list.")

        return ToolResult.success(buildJsonObject {
            put("skill", skillName)
            put("content", content)
        })
    }
}
