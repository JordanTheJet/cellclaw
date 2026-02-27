package com.cellclaw.agent

import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionProfile(val displayName: String, val description: String) {
    FULL_AUTO(
        "Full Auto",
        "All actions auto-approved. Zero interruptions."
    ),
    BALANCED(
        "Balanced",
        "Reads are automatic, writes and sends ask first."
    ),
    CAUTIOUS(
        "Cautious",
        "Only basic reads are automatic. Everything else asks."
    )
}

@Singleton
class AutonomyPolicy @Inject constructor() {

    private val policies = mutableMapOf<String, ToolApprovalPolicy>()

    private val allTools = listOf(
        "sms.read", "contacts.search", "calendar.query", "location.get",
        "clipboard.read", "file.read", "file.list", "settings.get",
        "sensor.read", "phone.log", "notification.send", "notification.listen",
        "browser.search", "browser.open", "screen.read", "screen.capture",
        "vision.analyze", "messaging.read",
        "app.launch", "app.automate", "messaging.open",
        "sms.send", "phone.call", "contacts.add", "calendar.create",
        "camera.snap", "camera.record", "clipboard.write", "file.write",
        "script.exec", "email.send", "messaging.reply", "schedule.manage",
        "heartbeat.context",
        "app.install"
    )

    private val readOps = setOf(
        "sms.read", "contacts.search", "calendar.query", "location.get",
        "clipboard.read", "file.read", "file.list", "settings.get",
        "sensor.read", "phone.log", "notification.send", "notification.listen",
        "browser.search", "browser.open", "screen.read", "screen.capture",
        "vision.analyze", "messaging.read",
        "heartbeat.context"
    )

    private val appControl = setOf(
        "app.launch", "app.automate", "messaging.open"
    )

    private val basicReads = setOf(
        "contacts.search", "calendar.query", "clipboard.read",
        "file.read", "file.list", "settings.get", "sensor.read",
        "browser.search", "browser.open"
    )

    init {
        applyProfile(PermissionProfile.FULL_AUTO)
    }

    fun applyProfile(profile: PermissionProfile) {
        policies.clear()
        when (profile) {
            PermissionProfile.FULL_AUTO -> {
                for (tool in allTools) {
                    policies[tool] = ToolApprovalPolicy.AUTO
                }
            }
            PermissionProfile.BALANCED -> {
                for (tool in allTools) {
                    policies[tool] = when {
                        tool in readOps -> ToolApprovalPolicy.AUTO
                        tool in appControl -> ToolApprovalPolicy.AUTO
                        else -> ToolApprovalPolicy.ASK
                    }
                }
            }
            PermissionProfile.CAUTIOUS -> {
                for (tool in allTools) {
                    policies[tool] = when {
                        tool in basicReads -> ToolApprovalPolicy.AUTO
                        else -> ToolApprovalPolicy.ASK
                    }
                }
            }
        }
    }

    fun getPolicy(toolName: String): ToolApprovalPolicy {
        return policies[toolName] ?: ToolApprovalPolicy.AUTO
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
