package com.cellclaw.approval

import com.cellclaw.agent.AutonomyPolicy
import com.cellclaw.agent.ToolApprovalPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the mapping between approval results and autonomy policy updates.
 * When a user selects "Always Allow", this updates the autonomy policy.
 */
@Singleton
class ApprovalPolicyManager @Inject constructor(
    private val autonomyPolicy: AutonomyPolicy
) {
    fun handleResult(toolName: String, result: ApprovalResult) {
        if (result == ApprovalResult.ALWAYS_ALLOW) {
            autonomyPolicy.setPolicy(toolName, ToolApprovalPolicy.AUTO)
        }
    }
}
