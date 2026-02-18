package com.cellclaw.approval

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricGate @Inject constructor() {

    fun isAvailable(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticate(activity: FragmentActivity, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                deferred.complete(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                deferred.complete(false)
            }

            override fun onAuthenticationFailed() {
                // Don't complete yet — user can retry
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("CellClaw Authentication")
            .setSubtitle(reason)
            .setNegativeButtonText("Cancel")
            .build()

        activity.runOnUiThread { prompt.authenticate(info) }
        return deferred.await()
    }
}
