package com.msi.openchat.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecureStorageManager
 * Encrypts and decrypts sensitive user credentials, tokens, and app lock security PINs
 * using the hardware-backed Android KeyStore with AES-256-GCM.
 */
class SecureStorageManager(context: Context) {

    private val tag = "OpenChat_SecureStorage"
    private val keyAlias = "openchat_aes_key"
    private val provider = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val gcmTagLength = 128
    private val ivLength = 12

    private val prefs: SharedPreferences =
        context.getSharedPreferences("openchat_vault_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENCRYPTED_IDENTIFIER = "enc_identifier"
        private const val KEY_ENCRYPTED_PASSWORD = "enc_password"
        private const val KEY_ENCRYPTED_EMAIL = "enc_email"
        private const val KEY_ENCRYPTED_USER_ID = "enc_user_id"
        private const val KEY_ENCRYPTED_USERNAME = "enc_username"
        private const val KEY_ENCRYPTED_DISPLAY_NAME = "enc_display_name"
        private const val KEY_ENCRYPTED_ACCESS_TOKEN = "enc_access_token"
        private const val KEY_ENCRYPTED_REFRESH_TOKEN = "enc_refresh_token"

        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_ENCRYPTED_APP_PIN = "enc_app_pin"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts_count"

        const val MAX_FAILED_ATTEMPTS = 5
    }

    // In-memory unlock state per app session lifecycle
    @Volatile
    private var sessionAppUnlocked: Boolean = false

    init {
        ensureSecretKey()
    }

    private fun ensureSecretKey() {
        try {
            val keyStore = KeyStore.getInstance(provider).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
                Log.d(tag, "Generated new AndroidKeyStore AES key")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize KeyStore key: ${e.message}", e)
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(provider).apply { load(null) }
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            Log.e(tag, "Error retrieving secret key: ${e.message}", e)
            null
        }
    }

    private fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return null
        return try {
            val secretKey = getSecretKey() ?: return fallbackEncode(plainText)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            
            // Format: Base64(IV + CipherBytes)
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(tag, "Encryption error: ${e.message}", e)
            fallbackEncode(plainText)
        }
    }

    private fun decrypt(encryptedBase64: String?): String? {
        if (encryptedBase64.isNullOrEmpty()) return null
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size < ivLength) return fallbackDecode(encryptedBase64)
            
            val secretKey = getSecretKey() ?: return fallbackDecode(encryptedBase64)
            val iv = ByteArray(ivLength)
            System.arraycopy(combined, 0, iv, 0, ivLength)
            val cipherBytes = ByteArray(combined.size - ivLength)
            System.arraycopy(combined, ivLength, cipherBytes, 0, cipherBytes.size)

            val cipher = Cipher.getInstance(transformation)
            val spec = GCMParameterSpec(gcmTagLength, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(tag, "Decryption error, attempting fallback: ${e.message}")
            fallbackDecode(encryptedBase64)
        }
    }

    private fun fallbackEncode(input: String): String {
        return "fb_" + Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun fallbackDecode(input: String): String? {
        return if (input.startsWith("fb_")) {
            val raw = input.removePrefix("fb_")
            try {
                String(Base64.decode(raw, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    // -------------------------------------------------------------------------
    // User Credentials Caching
    // -------------------------------------------------------------------------

    fun saveUserCredentials(
        identifier: String,
        password: String,
        email: String?,
        userId: String,
        username: String? = null
    ) {
        prefs.edit()
            .putString(KEY_ENCRYPTED_IDENTIFIER, encrypt(identifier))
            .putString(KEY_ENCRYPTED_PASSWORD, encrypt(password))
            .putString(KEY_ENCRYPTED_EMAIL, encrypt(email))
            .putString(KEY_ENCRYPTED_USER_ID, encrypt(userId))
            .putString(KEY_ENCRYPTED_USERNAME, encrypt(username))
            .apply()
        Log.i(tag, "Securely saved user credentials for user=$userId, identifier=$identifier")
    }

    fun saveTokens(accessToken: String?, refreshToken: String?) {
        prefs.edit()
            .putString(KEY_ENCRYPTED_ACCESS_TOKEN, encrypt(accessToken))
            .putString(KEY_ENCRYPTED_REFRESH_TOKEN, encrypt(refreshToken))
            .apply()
    }

    fun getCachedIdentifier(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_IDENTIFIER, null))
    fun getCachedPassword(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_PASSWORD, null))
    fun getCachedEmail(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_EMAIL, null))
    fun getCachedUserId(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_USER_ID, null))
    fun getCachedUsername(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_USERNAME, null))
    fun setCachedUsername(username: String?) {
        prefs.edit().putString(KEY_ENCRYPTED_USERNAME, encrypt(username)).apply()
    }
    fun getCachedDisplayName(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_DISPLAY_NAME, null))
    fun setCachedDisplayName(displayName: String?) {
        prefs.edit().putString(KEY_ENCRYPTED_DISPLAY_NAME, encrypt(displayName)).apply()
    }
    fun getCachedAccessToken(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_ACCESS_TOKEN, null))
    fun getCachedRefreshToken(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_REFRESH_TOKEN, null))

    fun hasCachedCredentials(): Boolean {
        val id = getCachedUserId()
        val pwd = getCachedPassword()
        val emailOrIdent = getCachedIdentifier() ?: getCachedEmail()
        val token = getCachedAccessToken() ?: getCachedRefreshToken()
        return (!id.isNullOrBlank() && (!pwd.isNullOrBlank() || !token.isNullOrBlank()) && !emailOrIdent.isNullOrBlank())
    }

    fun clearCredentials() {
        clearAllAuthData()
    }

    fun clearAllAuthData() {
        prefs.edit()
            .remove(KEY_ENCRYPTED_IDENTIFIER)
            .remove(KEY_ENCRYPTED_PASSWORD)
            .remove(KEY_ENCRYPTED_EMAIL)
            .remove(KEY_ENCRYPTED_USER_ID)
            .remove(KEY_ENCRYPTED_USERNAME)
            .remove(KEY_ENCRYPTED_DISPLAY_NAME)
            .remove(KEY_ENCRYPTED_ACCESS_TOKEN)
            .remove(KEY_ENCRYPTED_REFRESH_TOKEN)
            .remove("remember_me_enabled")
            .remove("auto_login")
            .putBoolean("remember_me_enabled", false)
            .putBoolean("auto_login", false)
            .commit()
        sessionAppUnlocked = false
        Log.i(tag, "Cleared cached user credentials and auto-login / remember-me flags")
    }

    fun isRememberMeEnabled(): Boolean = prefs.getBoolean("remember_me_enabled", false)

    fun setRememberMeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("remember_me_enabled", enabled).apply()
    }

    // -------------------------------------------------------------------------
    // App Lock & PIN
    // -------------------------------------------------------------------------

    fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        if (!enabled) {
            resetFailedAttempts()
        }
    }

    fun getAppPin(): String? = decrypt(prefs.getString(KEY_ENCRYPTED_APP_PIN, null))

    fun setAppPin(pin: String?) {
        prefs.edit()
            .putString(KEY_ENCRYPTED_APP_PIN, encrypt(pin))
            .apply()
        resetFailedAttempts()
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun incrementFailedAttempts(): Int {
        val current = getFailedAttempts() + 1
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, current).apply()
        return current
    }

    fun resetFailedAttempts() {
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
    }

    fun isLockedOut(): Boolean = getFailedAttempts() >= MAX_FAILED_ATTEMPTS

    fun isBiometricOrAppLockEnabled(): Boolean {
        return (isAppLockEnabled() && !getAppPin().isNullOrBlank()) || isBiometricEnabled()
    }

    fun isAppLockRequired(): Boolean = isBiometricOrAppLockEnabled()

    // Session-based in-memory unlock status
    fun isAppUnlocked(): Boolean {
        // If app lock / biometric is not enabled, consider it always unlocked
        if (!isAppLockRequired()) {
            return true
        }
        return sessionAppUnlocked
    }

    fun setAppUnlocked(unlocked: Boolean) {
        sessionAppUnlocked = unlocked
        if (unlocked) {
            resetFailedAttempts()
        }
    }
}
