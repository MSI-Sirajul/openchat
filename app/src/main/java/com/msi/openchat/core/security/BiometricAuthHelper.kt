package com.msi.openchat.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * BiometricAuthHelper
 *
 * Encapsulates the AndroidX BiometricPrompt API for fingerprint and biometric authentication on startup
 * and app unlock, offering device capability detection and customizable dialog presentation.
 */
object BiometricAuthHelper {

    enum class BiometricStatus {
        AVAILABLE,
        NONE_ENROLLED,
        NO_HARDWARE,
        HARDWARE_UNAVAILABLE,
        SECURITY_UPDATE_REQUIRED,
        UNSUPPORTED
    }

    /**
     * Checks if biometric authentication can be performed on this device.
     */
    fun isBiometricAvailable(context: Context): Boolean {
        return getBiometricStatus(context) == BiometricStatus.AVAILABLE
    }

    /**
     * Returns detailed status for biometric hardware and enrollment.
     */
    fun getBiometricStatus(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
            else -> BiometricStatus.UNSUPPORTED
        }
    }

    /**
     * Returns a user-friendly descriptive message for the current biometric status.
     */
    fun getBiometricStatusMessage(status: BiometricStatus): String {
        return when (status) {
            BiometricStatus.AVAILABLE -> "Biometric sensor is ready"
            BiometricStatus.NONE_ENROLLED -> "No fingerprint or biometric enrolled on this device. Please add one in Android Settings."
            BiometricStatus.NO_HARDWARE -> "This device does not have biometric hardware."
            BiometricStatus.HARDWARE_UNAVAILABLE -> "Biometric hardware is currently busy or unavailable. Please try again."
            BiometricStatus.SECURITY_UPDATE_REQUIRED -> "Security update required for biometric authentication."
            BiometricStatus.UNSUPPORTED -> "Biometric authentication is not supported on this device."
        }
    }

    /**
     * Displays the system BiometricPrompt dialog using the AndroidX Biometric library.
     */
    fun promptBiometricAuthentication(
        activity: androidx.activity.ComponentActivity,
        title: String = "OpenChat Authentication",
        subtitle: String = "Scan your fingerprint to unlock",
        description: String? = null,
        negativeButtonText: String = "Use PIN",
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit,
        onFailed: () -> Unit
    ): BiometricPrompt? {
        if (activity !is FragmentActivity) {
            onError(-1, "Activity must be a FragmentActivity")
            return null
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errorCode, errString)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onFailed()
            }
        }

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setConfirmationRequired(false)

        description?.let { promptInfoBuilder.setDescription(it) }

        return try {
            val promptInfo = promptInfoBuilder.build()
            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            biometricPrompt.authenticate(promptInfo)
            biometricPrompt
        } catch (e: Exception) {
            onError(-2, e.message ?: "Failed to initialize biometric prompt")
            null
        }
    }
}
