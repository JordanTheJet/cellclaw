package com.cellclaw.skills

import org.junit.Assert.*
import org.junit.Test

class SkillModelTest {

    @Test
    fun `skill with all fields`() {
        val skill = Skill(
            name = "Morning Briefing",
            description = "Get your morning summary",
            trigger = "good morning",
            steps = listOf("Check weather", "Read unread SMS", "Check calendar"),
            tools = listOf("settings.get", "sms.read", "calendar.query")
        )

        assertEquals("Morning Briefing", skill.name)
        assertEquals(3, skill.steps.size)
        assertEquals(3, skill.tools.size)
    }

    @Test
    fun `skill defaults to empty tools`() {
        val skill = Skill(
            name = "Test",
            description = "Test skill",
            trigger = "test",
            steps = listOf("Do something")
        )
        assertTrue(skill.tools.isEmpty())
    }

    @Test
    fun `parser handles multi-line description`() {
        val parser = SkillParser()
        val content = """
            # My Skill
            This is line one.
            This is line two.
            And line three.

            ## Trigger
            activate skill

            ## Steps
            1. First step
            2. Second step
        """.trimIndent()

        val skill = parser.parse(content)
        assertNotNull(skill)
        assertTrue(skill!!.description.contains("line one"))
        assertTrue(skill.description.contains("line two"))
        assertTrue(skill.description.contains("line three"))
    }

    @Test
    fun `parser handles tools with asterisk bullets`() {
        val parser = SkillParser()
        val content = """
            # Tool Test
            Description

            ## Trigger
            go

            ## Steps
            1. Step

            ## Tools
            * sms.read
            * calendar.query
        """.trimIndent()

        val skill = parser.parse(content)
        assertNotNull(skill)
        assertEquals(2, skill!!.tools.size)
        assertEquals("sms.read", skill.tools[0])
        assertEquals("calendar.query", skill.tools[1])
    }

    @Test
    fun `parser ignores unknown sections`() {
        val parser = SkillParser()
        val content = """
            # Skill
            Desc

            ## Trigger
            go

            ## Notes
            These are extra notes that should be ignored

            ## Steps
            1. Do thing
        """.trimIndent()

        val skill = parser.parse(content)
        assertNotNull(skill)
        assertEquals(1, skill!!.steps.size)
        assertEquals("Do thing", skill.steps[0])
    }
}
