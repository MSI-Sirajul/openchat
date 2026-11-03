package com.msi.openchat.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: Profile? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ProfileViewModel(
    val userId: String,
    private val userRepository: UserRepository = ServiceLocator.userRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            userRepository.observeUserProfile(userId).collect { profile ->
                if (profile != null) {
                    _uiState.value = _uiState.value.copy(profile = profile, isLoading = false)
                }
            }
        }
    }
}
