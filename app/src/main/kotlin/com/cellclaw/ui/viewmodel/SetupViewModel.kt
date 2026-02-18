package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore
) : ViewModel() {

    fun saveApiKey(apiKey: String) {
        secureKeyStore.storeApiKey("anthropic", apiKey)
    }

    fun saveUserName(name: String) {
        appConfig.userName = name
    }
}
