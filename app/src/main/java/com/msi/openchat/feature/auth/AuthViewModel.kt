package com.msi.openchat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLogin: Boolean = true,
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val displayName: String = "",
    val phoneNumber: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val isCheckingConfirmation: Boolean = false,
    val showEmailConfirmDialog: Boolean = false,
    val showEmailNotConfirmedAlert: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isAuthenticated: Boolean = false,
    val isForgotPasswordOpen: Boolean = false,
    val hasOfflineCachedSession: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository = ServiceLocator.authRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(
            isAuthenticated = authRepository.isUserLoggedIn(),
            rememberMe = ServiceLocator.sessionManager.secureStorage.isRememberMeEnabled(),
            hasOfflineCachedSession = ServiceLocator.sessionManager.secureStorage.hasCachedCredentials()
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ServiceLocator.authManager.authState.collect { state ->
                when (state) {
                    is com.msi.openchat.core.security.AuthState.SessionExpired -> {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Your session expired. Please sign in to continue.",
                            isAuthenticated = false,
                            isLogin = true
                        )
                    }
                    is com.msi.openchat.core.security.AuthState.Authenticated -> {
                        if (!_uiState.value.isLoading) {
                            _uiState.value = _uiState.value.copy(isAuthenticated = true)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun toggleAuthMode() {
        _uiState.value = _uiState.value.copy(
            isLogin = !_uiState.value.isLogin,
            errorMessage = null,
            successMessage = null
        )
    }

    fun onEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(email = email, errorMessage = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun onUsernameChanged(username: String) {
        _uiState.value = _uiState.value.copy(username = username, errorMessage = null)
    }

    fun onDisplayNameChanged(displayName: String) {
        _uiState.value = _uiState.value.copy(displayName = displayName, errorMessage = null)
    }

    fun onPhoneNumberChanged(phoneNumber: String) {
        _uiState.value = _uiState.value.copy(phoneNumber = phoneNumber, errorMessage = null)
    }

    fun onRememberMeChanged(checked: Boolean) {
        _uiState.value = _uiState.value.copy(rememberMe = checked)
        ServiceLocator.sessionManager.secureStorage.setRememberMeEnabled(checked)
    }

    fun setForgotPasswordOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isForgotPasswordOpen = open)
    }

    fun dismissEmailConfirmDialog() {
        _uiState.value = _uiState.value.copy(showEmailConfirmDialog = false)
    }

    fun dismissEmailNotConfirmedAlert() {
        _uiState.value = _uiState.value.copy(showEmailNotConfirmedAlert = false)
    }

    fun loginOffline() {
        if (ServiceLocator.sessionManager.secureStorage.hasCachedCredentials()) {
            ServiceLocator.sessionManager.secureStorage.setAppUnlocked(true)
            _uiState.value = _uiState.value.copy(isAuthenticated = true)
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "No offline credentials saved. Please log in online first.")
        }
    }

    fun checkEmailConfirmationRealtime() {
        val emailToCheck = _uiState.value.email.trim()
        if (emailToCheck.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingConfirmation = true)
            when (val res = authRepository.checkEmailConfirmed(emailToCheck)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(isCheckingConfirmation = false)
                    if (res.data) {
                        _uiState.value = _uiState.value.copy(showEmailConfirmDialog = false)
                        // Attempt automatic sign-in now that email is confirmed
                        submit()
                    } else {
                        _uiState.value = _uiState.value.copy(showEmailNotConfirmedAlert = true)
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCheckingConfirmation = false,
                        showEmailNotConfirmedAlert = true
                    )
                }
                else -> Unit
            }
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLogin) {
            if (state.email.isBlank()) {
                _uiState.value = state.copy(errorMessage = "Please enter your email, username, or phone number")
                return
            }
        } else {
            if (state.email.isBlank() || !state.email.contains("@")) {
                _uiState.value = state.copy(errorMessage = "Please enter a valid email address")
                return
            }
        }
        if (state.password.length < 6) {
            _uiState.value = state.copy(errorMessage = "Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            if (state.isLogin) {
                when (val result = authRepository.signIn(state.email.trim(), state.password, state.rememberMe)) {
                    is Resource.Success -> {
                        ServiceLocator.sessionManager.secureStorage.setAppUnlocked(true)
                        registerFcmDevice()
                        _uiState.value = _uiState.value.copy(isLoading = false, isAuthenticated = true)
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                    }
                    else -> Unit
                }
            } else {
                if (state.username.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Username is required")
                    return@launch
                }
                val dName = if (state.displayName.isNotBlank()) state.displayName.trim() else state.username.trim()
                when (val result = authRepository.signUp(
                    email = state.email.trim(),
                    password = state.password,
                    username = state.username.trim(),
                    displayName = dName,
                    phoneNumber = state.phoneNumber.trim().ifBlank { null }
                )) {
                    is Resource.Success -> {
                        registerFcmDevice()
                        val currentToken = ServiceLocator.sessionManager.getAccessToken()
                        if (!currentToken.isNullOrBlank()) {
                            // Instant login when email verification is disabled in Nhost Console
                            ServiceLocator.sessionManager.secureStorage.setAppUnlocked(true)
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isAuthenticated = true,
                                successMessage = "Welcome to OpenChat!"
                            )
                        } else {
                            // Email confirmation is required
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                showEmailConfirmDialog = true,
                                successMessage = "Account created! Please check your Inbox and Spam folder for the verification link."
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun resendVerificationEmail() {
        val targetEmail = _uiState.value.email.trim()
        if (targetEmail.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingConfirmation = true, errorMessage = null)
            when (val res = authRepository.resendVerificationEmail(targetEmail)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isCheckingConfirmation = false,
                        successMessage = "Verification email resent! Please check your Inbox and Spam/Junk folder."
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isCheckingConfirmation = false,
                        errorMessage = res.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isCheckingConfirmation = false)
                }
            }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = authRepository.signInWithGoogle(idToken)) {
                is Resource.Success -> {
                    ServiceLocator.sessionManager.secureStorage.setAppUnlocked(true)
                    registerFcmDevice()
                    _uiState.value = _uiState.value.copy(isLoading = false, isAuthenticated = true)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> Unit
            }
        }
    }

    fun signInWithPhoneCredential(credential: com.google.firebase.auth.PhoneAuthCredential) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = authRepository.signInWithPhoneCredential(credential)) {
                is Resource.Success -> {
                    ServiceLocator.sessionManager.secureStorage.setAppUnlocked(true)
                    registerFcmDevice()
                    _uiState.value = _uiState.value.copy(isLoading = false, isAuthenticated = true)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> Unit
            }
        }
    }

    private fun registerFcmDevice() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    val uid = ServiceLocator.sessionManager.getUserId()
                    if (!token.isNullOrBlank() && !uid.isNullOrBlank()) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                ServiceLocator.userRepository.updateFcmToken(uid, token)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = authRepository.resetPassword(email.trim())) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isForgotPasswordOpen = false,
                        successMessage = "Password reset instructions sent to $email"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }
}
