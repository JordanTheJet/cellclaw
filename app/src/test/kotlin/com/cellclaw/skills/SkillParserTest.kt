package com.cellclaw.skills

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SkillParserTest {

    private lateinit var parser: SkillParser

    @Before
    fun setup() {
        parser = SkillParser()
    }

    @Test
    fun `parse complete skill`() {
        val md = """
            # Daily Briefing
            Get a morning summary of your day.

            ## Trigger
            daily briefing

            ## Steps
            1. Check the current time
            2. Read today's calendar events
            3. Compile a summary

            ## Tools
            - calendar.query
            - sms.read
        """.trimIndent()

        val skill = parser.parse(md)
        assertNotNull(skill)
        assertEquals("Daily Briefing", skill!!.name)
        assertEquals("Get a morning summary of your day.", skill.description)
        assertEquals("daily briefing", skill.trigger)
        assertEquals(3, skill.steps.size)
        assertEquals("Check the current time", skill.steps[0])
        assertEquals(2, skill.tools.size)
        assertEquals("calendar.query", skill.tools[0])
    }

    @Test
    fun `parse minimal skill`() {
        val md = """
            # Simple Skill
            Just a basic skill.
        """.trimIndent()

        val skill = parser.parse(md)
        assertNotNull(skill)
        assertEquals("Simple Skill", skill!!.name)
        assertTrue(skill.steps.isEmpty())
        assertTrue(skill.tools.isEmpty())
    }

    @Test
    fun `parse empty returns null`() {
        assertNull(parser.parse(""))
    }

    @Test
    fun `parse no heading returns null`() {
        assertNull(parser.parse("Just some text without a heading"))
    }

    @Test
    fun `parse skill with dash-style steps`() {
        val md = """
            # Test
            Description

            ## Steps
            - Do this
            - Do that
        """.trimIndent()

        val skill = parser.parse(md)
        assertNotNull(skill)
        assertEquals(2, skill!!.steps.size)
        assertEquals("Do this", skill.steps[0])
        assertEquals("Do that", skill.steps[1])
    }
}
