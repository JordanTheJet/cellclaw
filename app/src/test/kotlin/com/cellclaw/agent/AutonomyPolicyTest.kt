package com.cellclaw.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutonomyPolicyTest {

    private lateinit var policy: AutonomyPolicy

    @Before
    fun setup() {
        policy = AutonomyPolicy()
    }

    @Test
    fun `read operations default to AUTO`() {
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("sms.read"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("contacts.search"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("calendar.query"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("location.get"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("file.read"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("settings.get"))
    }

    @Test
    fun `write operations default to ASK`() {
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("sms.send"))
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("phone.call"))
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("script.exec"))
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("app.automate"))
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("file.write"))
    }

    @Test
    fun `unknown tools default to ASK`() {
        assertEquals(ToolApprovalPolicy.ASK, policy.getPolicy("unknown.tool"))
    }

    @Test
    fun `can override policy`() {
        policy.setPolicy("sms.send", ToolApprovalPolicy.AUTO)
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("sms.send"))
    }

    @Test
    fun `can set to DENY`() {
        policy.setPolicy("script.exec", ToolApprovalPolicy.DENY)
        assertEquals(ToolApprovalPolicy.DENY, policy.getPolicy("script.exec"))
    }

    @Test
    fun `allPolicies returns snapshot`() {
        val all = policy.allPolicies()
        assertTrue(all.isNotEmpty())
        assertTrue(all.containsKey("sms.read"))
        assertTrue(all.containsKey("sms.send"))
    }
}
