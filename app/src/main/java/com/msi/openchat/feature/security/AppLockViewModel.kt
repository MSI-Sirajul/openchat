package com.msi.openchat.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppLockUiState(
    val enteredPin: String = "",
    val errorMessage: String? = null,
    val failedAttempts: Int = 0,
    val isLockedOut: Boolean = false,
    val isUnlocked: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val hasPinSet: Boolean = true,
    
    // Reset PIN Modal state
    val showResetModal: Boolean = false,
    val resetStep: Int = 1, // 1: Verify Credentials, 2: Enter New PIN
    val resetEmail: String = "",
    val resetPassword: String = "",
    val newPin: String = "",
    val confirmNewPin: String = "",
    val isResetting: Boolean = false,
    val resetError: String? = null
)

class AppLockViewModel(
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val sessionManager: com.msi.openchat.core.security.SessionManager = ServiceLocator.sessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppLockUiState(
            failedAttempts = sessionManager.secureStorage.getFailedAttempts(),
            isLockedOut = sessionManager.secureStorage.isLockedOut(),
            isBiometricEnabled = sessionManager.secureStorage.isBiometricEnabled(),
            hasPinSet = !sessionManager.secureStorage.getAppPin().isNullOrBlank(),
            resetEmail = sessionManager.getUserEmail() ?: sessionManager.secureStorage.getCachedEmail().orEmpty()
        )
    )
    val uiState: StateFlow<AppLockUiState> = _uiState.asStateFlow()

    fun hasPinSet(): Boolean = !sessionManager.secureStorage.getAppPin().isNullOrBlank()

    fun reloadSecurityState() {
        _uiState.value = _uiState.value.copy(
            failedAttempts = sessionManager.secureStorage.getFailedAttempts(),
            isLockedOut = sessionManager.secureStorage.isLockedOut(),
            isBiometricEnabled = sessionManager.secureStorage.isBiometricEnabled(),
            hasPinSet = hasPinSet()
        )
    }

    fun onDigitEntered(digit: Char) {
        val current = _uiState.value.enteredPin
        if (current.length >= 4 || _uiState.value.isLockedOut) return

        val updated = current + digit
        _uiState.value = _uiState.value.copy(enteredPin = updated, errorMessage = null)

        if (updated.length == 4) {
            verifyPin(updated)
        }
    }

    fun onBackspace() {
        val current = _uiState.value.enteredPin
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                enteredPin = current.dropLast(1),
                errorMessage = null
            )
        }
    }

    fun onClear() {
        _uiState.value = _uiState.value.copy(enteredPin = "", errorMessage = null)
    }

    private fun verifyPin(pin: String) {
        val storedPin = sessionManager.secureStorage.getAppPin()
        if (storedPin == pin) {
            sessionManager.secureStorage.resetFailedAttempts()
            sessionManager.secureStorage.setAppUnlocked(true)
            _uiState.value = _uiState.value.copy(
                isUnlocked = true,
                failedAttempts = 0,
                errorMessage = null
            )
        } else {
            val attempts = sessionManager.secureStorage.incrementFailedAttempts()
            val lockedOut = attempts >= com.msi.openchat.core.security.SecureStorageManager.MAX_FAILED_ATTEMPTS
            _uiState.value = _uiState.value.copy(
                enteredPin = "",
                failedAttempts = attempts,
                isLockedOut = lockedOut,
                errorMessage = if (lockedOut) {
                    ServiceLocator.getString(R.string.applock_too_many_attempts_count)
                } else {
                    ServiceLocator.getString(R.string.applock_incorrect_pin_attempt, attempts)
                }
            )
        }
    }

    fun onBiometricSuccess() {
        sessionManager.secureStorage.resetFailedAttempts()
        sessionManager.secureStorage.setAppUnlocked(true)
        _uiState.value = _uiState.value.copy(
            isUnlocked = true,
            failedAttempts = 0,
            errorMessage = null
        )
    }

    fun onBiometricFailed() {
        val attempts = sessionManager.secureStorage.incrementFailedAttempts()
        val lockedOut = attempts >= com.msi.openchat.core.security.SecureStorageManager.MAX_FAILED_ATTEMPTS
        _uiState.value = _uiState.value.copy(
            failedAttempts = attempts,
            isLockedOut = lockedOut,
            errorMessage = if (lockedOut) {
                ServiceLocator.getString(R.string.applock_biometric_failed_limit)
            } else {
                ServiceLocator.getString(R.string.applock_fingerprint_attempt, attempts)
            }
        )
    }

    fun openResetPinModal() {
        val email = sessionManager.getUserEmail() ?: sessionManager.secureStorage.getCachedEmail().orEmpty()
        _uiState.value = _uiState.value.copy(
            showResetModal = true,
            resetStep = 1,
            resetEmail = email,
            resetPassword = "",
            newPin = "",
            confirmNewPin = "",
            resetError = null
        )
    }

    fun dismissResetPinModal() {
        _uiState.value = _uiState.value.copy(showResetModal = false, resetError = null)
    }

    fun onResetEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(resetEmail = email, resetError = null)
    }

    fun onResetPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(resetPassword = password, resetError = null)
    }

    fun onNewPinChanged(pin: String) {
        if (pin.length <= 4 && pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(newPin = pin, resetError = null)
        }
    }

    fun onConfirmPinChanged(pin: String) {
        if (pin.length <= 4 && pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(confirmNewPin = pin, resetError = null)
        }
    }

    fun verifyCredentialsForReset() {
        val state = _uiState.value
        if (state.resetEmail.isBlank()) {
            _uiState.value = state.copy(resetError = ServiceLocator.getString(R.string.applock_enter_email_or_username))
            return
        }
        if (state.resetPassword.isBlank()) {
            _uiState.value = state.copy(resetError = ServiceLocator.getString(R.string.applock_enter_password))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResetting = true, resetError = null)
            when (val res = authRepository.verifyCredentials(state.resetEmail.trim(), state.resetPassword)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isResetting = false,
                        resetStep = 2,
                        resetError = null
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isResetting = false,
                        resetError = res.message ?: ServiceLocator.getString(R.string.applock_invalid_credentials)
                    )
                }
                else -> Unit
            }
        }
    }

    fun saveNewPinAndUnlock() {
        val state = _uiState.value
        if (state.newPin.length != 4) {
            _uiState.value = state.copy(resetError = ServiceLocator.getString(R.string.settings_pin_length_exact_error))
            return
        }
        if (state.newPin != state.confirmNewPin) {
            _uiState.value = state.copy(resetError = ServiceLocator.getString(R.string.settings_pin_mismatch_error))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResetting = true, resetError = null)
            when (val res = authRepository.updateAppPin(state.newPin)) {
                is Resource.Success -> {
                    sessionManager.secureStorage.resetFailedAttempts()
                    sessionManager.secureStorage.setAppUnlocked(true)
                    _uiState.value = _uiState.value.copy(
                        isResetting = false,
                        showResetModal = false,
                        isLockedOut = false,
                        failedAttempts = 0,
                        isUnlocked = true,
                        errorMessage = null
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isResetting = false,
                        resetError = res.message ?: ServiceLocator.getString(R.string.applock_pin_update_failed)
                    )
                }
                else -> Unit
            }
        }
    }
}
