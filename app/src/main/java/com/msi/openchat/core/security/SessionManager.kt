package com.msi.openchat.core.security

import android.content.Context
import android.content.SharedPreferences
import com.msi.openchat.core.common.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager(val context: Context) {
    val secureStorage = SecureStorageManager(context)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentUserId = MutableStateFlow<String?>(
        prefs.getString(Constants.KEY_USER_ID, null) ?: secureStorage.getCachedUserId()
    )
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(
        prefs.getString(Constants.KEY_ACCESS_TOKEN, null) ?: secureStorage.getCachedAccessToken()
    )
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    private val _isSessionValid = MutableStateFlow(isAuthenticated())
    val isSessionValid: StateFlow<Boolean> = _isSessionValid.asStateFlow()

    fun saveSession(accessToken: String, refreshToken: String?, userId: String, email: String?) {
        prefs.edit()
            .putString(Constants.KEY_ACCESS_TOKEN, accessToken)
            .putString(Constants.KEY_REFRESH_TOKEN, refreshToken)
            .putString(Constants.KEY_USER_ID, userId)
            .putString(Constants.KEY_USER_EMAIL, email)
            .apply()
        secureStorage.saveTokens(accessToken, refreshToken)

        _accessToken.value = accessToken
        _currentUserId.value = userId
        _isSessionValid.value = true
    }

    fun saveFullCredentials(
        identifier: String,
        password: String,
        email: String?,
        userId: String,
        username: String? = null
    ) {
        secureStorage.saveUserCredentials(
            identifier = identifier,
            password = password,
            email = email,
            userId = userId,
            username = username
        )
    }

    fun updateAccessToken(newAccessToken: String) {
        prefs.edit()
            .putString(Constants.KEY_ACCESS_TOKEN, newAccessToken)
            .apply()
        secureStorage.saveTokens(newAccessToken, getRefreshToken())
        _accessToken.value = newAccessToken
        _isSessionValid.value = true
    }

    fun getAccessToken(): String? =
        prefs.getString(Constants.KEY_ACCESS_TOKEN, null) ?: secureStorage.getCachedAccessToken()

    fun getRefreshToken(): String? =
        prefs.getString(Constants.KEY_REFRESH_TOKEN, null) ?: secureStorage.getCachedRefreshToken()

    fun getUserId(): String? =
        prefs.getString(Constants.KEY_USER_ID, null) ?: secureStorage.getCachedUserId()

    fun getUserEmail(): String? =
        prefs.getString(Constants.KEY_USER_EMAIL, null) ?: secureStorage.getCachedEmail()

    fun getAccessTokenExpiration(): Long? {
        val token = getAccessToken() ?: return null
        return parseJwtExpiration(token)
    }

    private fun parseJwtExpiration(token: String): Long? {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
                val json = org.json.JSONObject(payload)
                if (json.has("exp")) {
                    return json.getLong("exp")
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun isTokenExpiring(bufferSeconds: Long = 60): Boolean {
        val exp = getAccessTokenExpiration() ?: return true
        val now = System.currentTimeMillis() / 1000
        return (exp - bufferSeconds) <= now
    }

    fun invalidateSession() {
        _isSessionValid.value = false
    }

    fun markSessionValid() {
        _isSessionValid.value = true
    }

    fun getUserScopedPrefs(userId: String?): SharedPreferences {
        val safeId = if (userId.isNullOrBlank()) "guest" else userId.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        return context.getSharedPreferences("openchat_user_${safeId}_prefs", Context.MODE_PRIVATE)
    }

    fun clearUserScopedPrefs(userId: String) {
        val safeId = userId.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        try {
            context.getSharedPreferences("openchat_user_${safeId}_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        } catch (_: Exception) {}
    }

    fun clearSession() {
        val uid = getUserId()
        if (!uid.isNullOrBlank()) {
            clearUserScopedPrefs(uid)
        }
        val onboardingCompleted = isPermissionOnboardingCompleted()
        prefs.edit()
            .remove(Constants.KEY_ACCESS_TOKEN)
            .remove(Constants.KEY_REFRESH_TOKEN)
            .remove(Constants.KEY_USER_ID)
            .remove(Constants.KEY_USER_EMAIL)
            .putBoolean(Constants.KEY_PERMISSION_ONBOARDING_COMPLETED, onboardingCompleted)
            .commit()
        secureStorage.clearAllAuthData()
        _accessToken.value = null
        _currentUserId.value = null
        _isSessionValid.value = false
    }

    /**
     * User is authenticated if they have active tokens OR secure cached credentials in storage.
     * When relaunching, if secureStorage.hasCachedCredentials() is true, the user is kept logged in!
     */
    fun isAuthenticated(): Boolean {
        val hasActiveToken = !getAccessToken().isNullOrBlank() && !getUserId().isNullOrBlank()
        val hasCachedCreds = secureStorage.hasCachedCredentials()
        return hasActiveToken || hasCachedCreds
    }

    fun hasSecureCredentials(): Boolean = secureStorage.hasCachedCredentials()

    fun isPermissionOnboardingCompleted(): Boolean {
        return prefs.getBoolean(Constants.KEY_PERMISSION_ONBOARDING_COMPLETED, false)
    }

    fun setPermissionOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(Constants.KEY_PERMISSION_ONBOARDING_COMPLETED, completed).apply()
    }
}
