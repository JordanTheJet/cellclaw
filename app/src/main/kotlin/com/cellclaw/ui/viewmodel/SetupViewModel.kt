package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.config.AppConfig
import com.cellclaw.provider.ProviderManager
import com.cellclaw.provider.ProviderInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val providerManager: ProviderManager
) : ViewModel() {

    private val _selectedProvider = MutableStateFlow(providerManager.activeType())
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    val availableProviders: List<ProviderInfo> = providerManager.availableProviders()

    fun selectProvider(type: String) {
        _selectedProvider.value = type
        providerManager.switchProvider(type)
    }

    fun saveApiKey(provider: String, apiKey: String) {
        providerManager.setApiKey(provider, apiKey)
    }

    fun saveUserName(name: String) {
        appConfig.userName = name
    }

    fun defaultModel(providerType: String): String {
        return availableProviders.find { it.type == providerType }?.defaultModel ?: ""
    }
}
