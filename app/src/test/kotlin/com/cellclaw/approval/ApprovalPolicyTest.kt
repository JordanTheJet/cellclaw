package com.cellclaw.approval

import com.cellclaw.agent.AutonomyPolicy
import com.cellclaw.agent.ToolApprovalPolicy
import org.junit.Assert.*
import org.junit.Test

class ApprovalPolicyTest {

    @Test
    fun `approval request generates unique id`() {
        val r1 = ApprovalRequest(
            toolName = "sms.send",
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            description = "Send SMS?"
        )
        val r2 = ApprovalRequest(
            toolName = "sms.send",
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            description = "Send SMS?"
        )
        assertNotEquals(r1.id, r2.id)
    }

    @Test
    fun `approval request with custom id`() {
        val r = ApprovalRequest(
            id = "custom-id",
            toolName = "test",
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            description = "Test?"
        )
        assertEquals("custom-id", r.id)
    }

    @Test
    fun `approval result values`() {
        assertEquals(3, ApprovalResult.values().size)
        assertTrue(ApprovalResult.values().contains(ApprovalResult.APPROVED))
        assertTrue(ApprovalResult.values().contains(ApprovalResult.DENIED))
        assertTrue(ApprovalResult.values().contains(ApprovalResult.ALWAYS_ALLOW))
    }

    @Test
    fun `autonomy policy batch overrides`() {
        val policy = AutonomyPolicy()

        // Override multiple tools
        policy.setPolicy("sms.send", ToolApprovalPolicy.AUTO)
        policy.setPolicy("phone.call", ToolApprovalPolicy.DENY)
        policy.setPolicy("script.exec", ToolApprovalPolicy.AUTO)

        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("sms.send"))
        assertEquals(ToolApprovalPolicy.DENY, policy.getPolicy("phone.call"))
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("script.exec"))

        // Read ops should still be AUTO
        assertEquals(ToolApprovalPolicy.AUTO, policy.getPolicy("sms.read"))
    }
}
