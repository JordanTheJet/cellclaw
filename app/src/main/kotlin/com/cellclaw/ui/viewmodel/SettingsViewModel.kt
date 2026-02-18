package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.agent.AutonomyPolicy
import com.cellclaw.agent.ToolApprovalPolicy
import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore,
    private val autonomyPolicy: AutonomyPolicy
) : ViewModel() {

    private val _model = MutableStateFlow(appConfig.model)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _userName = MutableStateFlow(appConfig.userName)
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _autoStartOnBoot = MutableStateFlow(appConfig.autoStartOnBoot)
    val autoStartOnBoot: StateFlow<Boolean> = _autoStartOnBoot.asStateFlow()

    private val _policies = MutableStateFlow(autonomyPolicy.allPolicies())
    val policies: StateFlow<Map<String, ToolApprovalPolicy>> = _policies.asStateFlow()

    private val _hasApiKey = MutableStateFlow(secureKeyStore.hasApiKey("anthropic"))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    fun setModel(model: String) {
        appConfig.model = model
        _model.value = model
    }

    fun setUserName(name: String) {
        appConfig.userName = name
        _userName.value = name
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        appConfig.autoStartOnBoot = enabled
        _autoStartOnBoot.value = enabled
    }

    fun togglePolicy(toolName: String) {
        val current = autonomyPolicy.getPolicy(toolName)
        val next = when (current) {
            ToolApprovalPolicy.AUTO -> ToolApprovalPolicy.ASK
            ToolApprovalPolicy.ASK -> ToolApprovalPolicy.DENY
            ToolApprovalPolicy.DENY -> ToolApprovalPolicy.AUTO
        }
        autonomyPolicy.setPolicy(toolName, next)
        _policies.value = autonomyPolicy.allPolicies()
    }
}
