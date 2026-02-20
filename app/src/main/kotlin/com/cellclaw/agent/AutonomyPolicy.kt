package com.cellclaw.agent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutonomyPolicy @Inject constructor() {

    private val policies = mutableMapOf<String, ToolApprovalPolicy>()

    init {
        // Defaults: read ops are auto, write/send ops require approval
        setDefaults()
    }

    private fun setDefaults() {
        // Safe read operations
        setPolicy("sms.read", ToolApprovalPolicy.AUTO)
        setPolicy("contacts.search", ToolApprovalPolicy.AUTO)
        setPolicy("calendar.query", ToolApprovalPolicy.AUTO)
        setPolicy("location.get", ToolApprovalPolicy.AUTO)
        setPolicy("clipboard.read", ToolApprovalPolicy.AUTO)
        setPolicy("file.read", ToolApprovalPolicy.AUTO)
        setPolicy("file.list", ToolApprovalPolicy.AUTO)
        setPolicy("settings.get", ToolApprovalPolicy.AUTO)
        setPolicy("sensor.read", ToolApprovalPolicy.AUTO)
        setPolicy("phone.log", ToolApprovalPolicy.AUTO)
        setPolicy("notification.send", ToolApprovalPolicy.AUTO)
        setPolicy("browser.search", ToolApprovalPolicy.AUTO)
        setPolicy("browser.open", ToolApprovalPolicy.AUTO)
        setPolicy("screen.read", ToolApprovalPolicy.AUTO)

        // Write/send operations need approval
        setPolicy("sms.send", ToolApprovalPolicy.ASK)
        setPolicy("phone.call", ToolApprovalPolicy.ASK)
        setPolicy("contacts.add", ToolApprovalPolicy.ASK)
        setPolicy("calendar.create", ToolApprovalPolicy.ASK)
        setPolicy("camera.snap", ToolApprovalPolicy.ASK)
        setPolicy("camera.record", ToolApprovalPolicy.ASK)
        setPolicy("clipboard.write", ToolApprovalPolicy.ASK)
        setPolicy("file.write", ToolApprovalPolicy.ASK)
        setPolicy("app.launch", ToolApprovalPolicy.ASK)
        setPolicy("app.automate", ToolApprovalPolicy.ASK)
        setPolicy("script.exec", ToolApprovalPolicy.ASK)
        setPolicy("email.send", ToolApprovalPolicy.ASK)
    }

    fun getPolicy(toolName: String): ToolApprovalPolicy {
        return policies[toolName] ?: ToolApprovalPolicy.ASK
    }

    fun setPolicy(toolName: String, policy: ToolApprovalPolicy) {
        policies[toolName] = policy
    }

    fun allPolicies(): Map<String, ToolApprovalPolicy> = policies.toMap()
}

enum class ToolApprovalPolicy {
    AUTO,  // Always allow
    ASK,   // Prompt user
    DENY   // Never allow
}
