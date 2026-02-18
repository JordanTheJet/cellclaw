package com.cellclaw.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts/decrypts API keys using the Android Keystore.
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("cellclaw_secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun storeApiKey(provider: String, apiKey: String) {
        val secretKey = getOrCreateKey(provider)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        prefs.edit()
            .putString("${provider}_key", Base64.encodeToString(encrypted, Base64.DEFAULT))
            .putString("${provider}_iv", Base64.encodeToString(iv, Base64.DEFAULT))
            .apply()
    }

    fun getApiKey(provider: String): String? {
        val encryptedB64 = prefs.getString("${provider}_key", null) ?: return null
        val ivB64 = prefs.getString("${provider}_iv", null) ?: return null

        val encrypted = Base64.decode(encryptedB64, Base64.DEFAULT)
        val iv = Base64.decode(ivB64, Base64.DEFAULT)

        val secretKey = getOrCreateKey(provider)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    fun hasApiKey(provider: String): Boolean {
        return prefs.contains("${provider}_key")
    }

    fun deleteApiKey(provider: String) {
        prefs.edit()
            .remove("${provider}_key")
            .remove("${provider}_iv")
            .apply()
        if (keyStore.containsAlias(keyAlias(provider))) {
            keyStore.deleteEntry(keyAlias(provider))
        }
    }

    private fun getOrCreateKey(provider: String): SecretKey {
        val alias = keyAlias(provider)
        if (keyStore.containsAlias(alias)) {
            return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun keyAlias(provider: String) = "cellclaw_${provider}_key"

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
}
