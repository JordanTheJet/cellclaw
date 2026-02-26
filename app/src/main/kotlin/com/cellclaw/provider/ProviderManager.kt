package com.cellclaw.provider

import android.util.Log
import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages available AI providers and handles provider switching.
 * Reads the active provider from AppConfig and configures it with
 * the encrypted API key from SecureKeyStore.
 *
 * Supports cross-provider failover: if the active provider fails with
 * a transient error, automatically tries other configured providers.
 */
@Singleton
class ProviderManager @Inject constructor(
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore,
    private val anthropicProvider: AnthropicProvider,
    private val openAIProvider: OpenAIProvider,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider
) {
    private val providers = mapOf(
        "anthropic" to anthropicProvider,
        "openai" to openAIProvider,
        "gemini" to geminiProvider,
        "openrouter" to openRouterProvider
    )

    /** Failover order: try these providers when the active one fails */
    private val failoverOrder = listOf("gemini", "openai", "anthropic", "openrouter")

    /** Last failover event for UI visibility */
    var lastFailoverEvent: FailoverEvent? = null
        private set

    /** Get the currently active provider, configured with its API key */
    fun activeProvider(): Provider {
        val type = appConfig.providerType
        val provider = providers[type] ?: anthropicProvider
        configureProvider(type, provider)
        return provider
    }

    /**
     * Complete a request with automatic cross-provider failover.
     * Tries the active provider first, then falls back to other
     * configured providers if the active one fails with a transient error.
     */
    suspend fun completeWithFailover(request: CompletionRequest): CompletionResponse {
        val activeType = appConfig.providerType
        lastFailoverEvent = null

        // Try the active provider first
        try {
            val provider = activeProvider()
            return provider.complete(request)
        } catch (e: ProviderException) {
            Log.w(TAG, "Primary provider '$activeType' failed: ${e.message}")

            // If it's an auth error (401/403), don't try failover — it's a config problem
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                throw e
            }

            // Try failover providers
            val errors = mutableListOf("$activeType: ${e.message}")
            for (fallbackType in failoverOrder) {
                if (fallbackType == activeType) continue
                if (!secureKeyStore.hasApiKey(fallbackType)) continue

                val fallbackProvider = providers[fallbackType] ?: continue
                configureProvider(fallbackType, fallbackProvider)

                try {
                    Log.w(TAG, "Failing over from '$activeType' to '$fallbackType'...")
                    val response = fallbackProvider.complete(request)
                    Log.w(TAG, "Failover to '$fallbackType' succeeded")
                    lastFailoverEvent = FailoverEvent(
                        fromProvider = activeType,
                        toProvider = fallbackType,
                        reason = e.message ?: "Unknown error"
                    )
                    return response
                } catch (fallbackError: Exception) {
                    Log.w(TAG, "Failover provider '$fallbackType' also failed: ${fallbackError.message}")
                    errors.add("$fallbackType: ${fallbackError.message}")
                }
            }

            throw ProviderException(
                "All providers failed. Errors:\n${errors.joinToString("\n")}"
            )
        }
    }

    /** Switch to a different provider */
    fun switchProvider(type: String) {
        if (type in providers) {
            appConfig.providerType = type
        }
    }

    /** Get the active provider type name */
    fun activeType(): String = appConfig.providerType

    /** Get all available provider types */
    fun availableProviders(): List<ProviderInfo> = listOf(
        ProviderInfo("anthropic", "Anthropic (Claude)", "claude-sonnet-4-6",
            secureKeyStore.hasApiKey("anthropic"),
            models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")),
        ProviderInfo("openai", "OpenAI (GPT)", "gpt-5.2",
            secureKeyStore.hasApiKey("openai"),
            models = listOf("gpt-5.2", "gpt-5.2-chat-latest", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini")),
        ProviderInfo("gemini", "Google (Gemini)", "gemini-3-flash-preview",
            secureKeyStore.hasApiKey("gemini"),
            models = listOf("gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-pro")),
        ProviderInfo("openrouter", "OpenRouter", "google/gemini-2.5-flash",
            secureKeyStore.hasApiKey("openrouter"),
            models = listOf("google/gemini-2.5-flash", "google/gemini-2.5-pro", "anthropic/claude-sonnet-4.6", "openai/gpt-5.2"))
    )

    /** Check if a provider has an API key configured */
    fun hasKey(type: String): Boolean = secureKeyStore.hasApiKey(type)

    /** Store an API key for a provider */
    fun setApiKey(type: String, apiKey: String) {
        secureKeyStore.storeApiKey(type, apiKey)
    }

    /** Remove an API key for a provider */
    fun removeApiKey(type: String) {
        secureKeyStore.deleteApiKey(type)
    }

    private fun configureProvider(type: String, provider: Provider) {
        val apiKey = secureKeyStore.getApiKey(type) ?: return
        val model = appConfig.model
        when (provider) {
            is AnthropicProvider -> provider.configure(apiKey, model)
            is OpenAIProvider -> provider.configure(apiKey, model)
            is GeminiProvider -> provider.configure(apiKey, model)
            is OpenRouterProvider -> provider.configure(apiKey, model)
        }
    }

    companion object {
        private const val TAG = "ProviderManager"
    }
}

data class FailoverEvent(
    val fromProvider: String,
    val toProvider: String,
    val reason: String
)

data class ProviderInfo(
    val type: String,
    val displayName: String,
    val defaultModel: String,
    val hasKey: Boolean,
    val models: List<String> = emptyList()
)
