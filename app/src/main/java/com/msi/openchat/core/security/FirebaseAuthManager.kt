package com.msi.openchat.core.security

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    object Initializing : AuthState()
    data class Authenticated(val userId: String, val email: String?, val accessToken: String) : AuthState()
    object Unauthenticated : AuthState()
    object LoggedOut : AuthState()
    data class SessionExpired(val reason: String) : AuthState()
    data class NetworkError(val message: String) : AuthState()
}

data class AuthUserSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val userId: String,
    val email: String,
    val displayName: String? = null
)

class FirebaseAuthManager(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    private val auth: FirebaseAuth
        get() = FirebaseManager.auth

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                _authState.value = AuthState.Authenticated(
                    userId = user.uid,
                    email = user.email,
                    accessToken = user.uid
                )
            } else {
                if (sessionManager.isAuthenticated() && !sessionManager.getUserId().isNullOrBlank()) {
                    _authState.value = AuthState.Authenticated(
                        userId = sessionManager.getUserId().orEmpty(),
                        email = sessionManager.getUserEmail(),
                        accessToken = sessionManager.getAccessToken().orEmpty()
                    )
                } else {
                    _authState.value = AuthState.LoggedOut
                }
            }
        }
    }

    fun setLoggedOut() {
        _authState.value = AuthState.LoggedOut
    }

    fun getValidAccessToken(): String? {
        val user = auth.currentUser
        return user?.uid ?: sessionManager.getAccessToken()
    }
}
