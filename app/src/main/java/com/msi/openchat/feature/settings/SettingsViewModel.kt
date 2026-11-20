package com.msi.openchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.repository.AuthRepository
import com.msi.openchat.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    val statusMessage: String = "",
    val avatarUrl: String? = null,
    val showLastSeen: Boolean = true,
    val showReadReceipts: Boolean = true,
    
    // App Lock & Biometrics
    val isAppLockEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val showPinDialog: Boolean = false,
    val pinInput: String = "",
    val confirmPinInput: String = "",
    val pinDialogError: String? = null,
    val isSavingPin: Boolean = false,

    // Theme & Appearance
    val currentThemeMode: com.msi.openchat.core.theme.AppThemeMode = com.msi.openchat.core.theme.AppThemeMode.SYSTEM,

    // OpenChat AI
    val isOpenChatAiEnabled: Boolean = true,

    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isLoggedOut: Boolean = false,
    val isAccountDeleted: Boolean = false,
    val message: String? = null,
    val isDeleteDialogOpen: Boolean = false
)

class SettingsViewModel(
    private val userRepository: UserRepository = ServiceLocator.userRepository,
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val sessionManager: com.msi.openchat.core.security.SessionManager = ServiceLocator.sessionManager,
    private val themeManager: com.msi.openchat.core.theme.ThemeManager = ServiceLocator.themeManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            isAppLockEnabled = sessionManager.secureStorage.isAppLockEnabled(),
            isBiometricEnabled = sessionManager.secureStorage.isBiometricEnabled(),
            currentThemeMode = themeManager.themeMode.value,
            isOpenChatAiEnabled = sessionManager.isOpenChatAiEnabled()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentProfile()
        observeTheme()
        observeOpenChatAiPreference()
    }

    private fun observeTheme() {
        viewModelScope.launch {
            themeManager.themeMode.collect { mode ->
                _uiState.value = _uiState.value.copy(currentThemeMode = mode)
            }
        }
    }

    private fun observeOpenChatAiPreference() {
        viewModelScope.launch {
            sessionManager.openChatAiEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(isOpenChatAiEnabled = enabled)
            }
        }
    }

    fun selectThemeMode(mode: com.msi.openchat.core.theme.AppThemeMode, activity: android.app.Activity? = null) {
        themeManager.setThemeMode(mode, activity)
    }

    fun onToggleOpenChatAi(enabled: Boolean) {
        sessionManager.setOpenChatAiEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            isOpenChatAiEnabled = enabled,
            message = ServiceLocator.getString(
                if (enabled) R.string.settings_openchat_ai_enabled_toast else R.string.settings_openchat_ai_disabled_toast
            )
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun loadCurrentProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val res = userRepository.getCurrentUserProfile()) {
                is Resource.Success -> {
                    val p = res.data
                    _uiState.value = _uiState.value.copy(
                        displayName = p.displayName,
                        username = p.username.orEmpty(),
                        bio = p.bio.orEmpty(),
                        statusMessage = p.statusMessage ?: "Available",
                        avatarUrl = p.avatarUrl ?: p.avatar,
                        showLastSeen = p.showLastSeen,
                        showReadReceipts = p.showReadReceipts,
                        isAppLockEnabled = sessionManager.secureStorage.isAppLockEnabled(),
                        isBiometricEnabled = sessionManager.secureStorage.isBiometricEnabled(),
                        isLoading = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, message = res.message)
                }
                else -> Unit
            }
        }
    }

    fun onDisplayNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name)
    }

    fun onUsernameChanged(uname: String) {
        _uiState.value = _uiState.value.copy(username = uname)
    }

    fun onBioChanged(bio: String) {
        _uiState.value = _uiState.value.copy(bio = bio)
    }

    fun onStatusMessageChanged(status: String) {
        _uiState.value = _uiState.value.copy(statusMessage = status)
    }

    fun onToggleLastSeen(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showLastSeen = enabled)
    }

    fun onToggleReadReceipts(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showReadReceipts = enabled)
    }

    // -------------------------------------------------------------------------
    // App Lock & Biometrics
    // -------------------------------------------------------------------------

    fun onToggleAppLock(enabled: Boolean) {
        if (enabled) {
            // Must set 4-digit PIN first
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinInput = "",
                confirmPinInput = "",
                pinDialogError = null
            )
        } else {
            viewModelScope.launch {
                authRepository.disableAppLock()
                _uiState.value = _uiState.value.copy(
                    isAppLockEnabled = false,
                    isBiometricEnabled = false,
                    message = ServiceLocator.getString(R.string.settings_app_lock_disabled)
                )
            }
        }
    }

    fun openSetPinDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = true,
            pinInput = "",
            confirmPinInput = "",
            pinDialogError = null
        )
    }

    fun dismissSetPinDialog() {
        _uiState.value = _uiState.value.copy(showPinDialog = false, pinDialogError = null)
    }

    fun onPinInputChanged(pin: String) {
        if (pin.length <= 4 && pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(pinInput = pin, pinDialogError = null)
        }
    }

    fun onConfirmPinInputChanged(pin: String) {
        if (pin.length <= 4 && pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(confirmPinInput = pin, pinDialogError = null)
        }
    }

    fun savePin() {
        val pin = _uiState.value.pinInput
        val confirm = _uiState.value.confirmPinInput
        if (pin.length != 4) {
            _uiState.value = _uiState.value.copy(pinDialogError = ServiceLocator.getString(R.string.settings_pin_length_exact_error))
            return
        }
        if (pin != confirm) {
            _uiState.value = _uiState.value.copy(pinDialogError = ServiceLocator.getString(R.string.settings_pin_mismatch_error))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingPin = true, pinDialogError = null)
            when (val res = authRepository.updateAppPin(pin)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSavingPin = false,
                        showPinDialog = false,
                        isAppLockEnabled = true,
                        message = ServiceLocator.getString(R.string.settings_pin_set_success)
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSavingPin = false,
                        pinDialogError = res.message ?: ServiceLocator.getString(R.string.settings_pin_save_failed)
                    )
                }
                else -> Unit
            }
        }
    }

    fun onToggleBiometric(enabled: Boolean) {
        viewModelScope.launch {
            authRepository.updateBiometricEnabled(enabled)
            _uiState.value = _uiState.value.copy(
                isBiometricEnabled = enabled,
                message = if (enabled) {
                    ServiceLocator.getString(R.string.settings_fingerprint_startup_enabled)
                } else {
                    ServiceLocator.getString(R.string.settings_fingerprint_disabled)
                }
            )
        }
    }

    fun setDeleteDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isDeleteDialogOpen = open)
    }

    fun saveProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            when (val res = userRepository.updateProfile(
                displayName = _uiState.value.displayName.trim(),
                username = _uiState.value.username.trim(),
                bio = _uiState.value.bio.trim(),
                statusMessage = _uiState.value.statusMessage.trim()
            )) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        message = ServiceLocator.getString(R.string.profile_updated_success)
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        message = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = _uiState.value.copy(isLoggedOut = true)
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, isDeleteDialogOpen = false)
            authRepository.logout()
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                isAccountDeleted = true,
                isLoggedOut = true
            )
        }
    }
}
