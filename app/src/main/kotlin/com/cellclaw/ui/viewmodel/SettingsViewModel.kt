package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.agent.AutonomyPolicy
import com.cellclaw.agent.PermissionProfile
import com.cellclaw.agent.ToolApprovalPolicy
import com.cellclaw.agent.AccessMode
import com.cellclaw.config.AppConfig
import com.cellclaw.provider.ProviderInfo
import com.cellclaw.provider.ProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val providerManager: ProviderManager,
    private val autonomyPolicy: AutonomyPolicy
) : ViewModel() {

    private val _permissionProfile = MutableStateFlow(
        try { PermissionProfile.valueOf(appConfig.permissionProfile) }
        catch (_: Exception) { PermissionProfile.FULL_AUTO }
    )
    val permissionProfile: StateFlow<PermissionProfile> = _permissionProfile.asStateFlow()

    private val _activeProvider = MutableStateFlow(providerManager.activeType())
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    private val _providers = MutableStateFlow(providerManager.availableProviders())
    val providers: StateFlow<List<ProviderInfo>> = _providers.asStateFlow()

    private val _model = MutableStateFlow(appConfig.model)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _userName = MutableStateFlow(appConfig.userName)
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _autoStartOnBoot = MutableStateFlow(appConfig.autoStartOnBoot)
    val autoStartOnBoot: StateFlow<Boolean> = _autoStartOnBoot.asStateFlow()

    private val _policies = MutableStateFlow(autonomyPolicy.allPolicies())
    val policies: StateFlow<Map<String, ToolApprovalPolicy>> = _policies.asStateFlow()

    private val _voiceEnabled = MutableStateFlow(appConfig.voiceEnabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    private val _autoSpeakResponses = MutableStateFlow(appConfig.autoSpeakResponses)
    val autoSpeakResponses: StateFlow<Boolean> = _autoSpeakResponses.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(appConfig.overlayEnabled)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _maxIterations = MutableStateFlow(appConfig.maxIterations)
    val maxIterations: StateFlow<Int> = _maxIterations.asStateFlow()

    private val _wakeWordEnabled = MutableStateFlow(appConfig.wakeWordEnabled)
    val wakeWordEnabled: StateFlow<Boolean> = _wakeWordEnabled.asStateFlow()

    private val _autoInstallApps = MutableStateFlow(appConfig.autoInstallApps)
    val autoInstallApps: StateFlow<Boolean> = _autoInstallApps.asStateFlow()

    private val _availableModels = MutableStateFlow(modelsForProvider(_activeProvider.value))
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    val appAccessModeLabel: String
        get() = AccessMode.fromKey(appConfig.appAccessMode).displayName

    private fun modelsForProvider(type: String): List<String> =
        providerManager.availableProviders().find { it.type == type }?.models ?: emptyList()

    fun switchProvider(type: String) {
        providerManager.switchProvider(type)
        _activeProvider.value = type
        // Update model to the default for this provider
        val defaultModel = providerManager.availableProviders()
            .find { it.type == type }?.defaultModel ?: ""
        if (defaultModel.isNotEmpty()) {
            appConfig.model = defaultModel
            _model.value = defaultModel
        }
        _providers.value = providerManager.availableProviders()
        _availableModels.value = modelsForProvider(type)
    }

    fun saveApiKey(providerType: String, apiKey: String) {
        providerManager.setApiKey(providerType, apiKey)
        _providers.value = providerManager.availableProviders()
    }

    fun removeApiKey(providerType: String) {
        providerManager.removeApiKey(providerType)
        _providers.value = providerManager.availableProviders()
    }

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

    fun setVoiceEnabled(enabled: Boolean) {
        appConfig.voiceEnabled = enabled
        _voiceEnabled.value = enabled
    }

    fun setAutoSpeakResponses(enabled: Boolean) {
        appConfig.autoSpeakResponses = enabled
        _autoSpeakResponses.value = enabled
    }

    fun setOverlayEnabled(enabled: Boolean) {
        appConfig.overlayEnabled = enabled
        _overlayEnabled.value = enabled
    }

    fun setMaxIterations(value: Int) {
        appConfig.maxIterations = value
        _maxIterations.value = value
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        appConfig.wakeWordEnabled = enabled
        _wakeWordEnabled.value = enabled
    }

    fun setAutoInstallApps(enabled: Boolean) {
        appConfig.autoInstallApps = enabled
        _autoInstallApps.value = enabled
    }

    fun setPermissionProfile(profile: PermissionProfile) {
        appConfig.permissionProfile = profile.name
        autonomyPolicy.applyProfile(profile)
        _permissionProfile.value = profile
        _policies.value = autonomyPolicy.allPolicies()
        // Sync auto-install: on for Full Auto, off for others
        val install = profile == PermissionProfile.FULL_AUTO
        appConfig.autoInstallApps = install
        _autoInstallApps.value = install
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
