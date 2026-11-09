package com.msi.openchat.feature.profile

import android.content.Context
import android.net.Uri
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
    val pendingAvatarUri: Uri? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val avatarUploadProgress: Float = 0f,
    val isEditMode: Boolean = false,
    val isSelf: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ProfileViewModel(
    val userId: String,
    private val userRepository: UserRepository = ServiceLocator.userRepository,
    private val sessionManager: com.msi.openchat.core.security.SessionManager = ServiceLocator.sessionManager
) : ViewModel() {

    private val currentUserId: String get() = sessionManager.getUserId().orEmpty()
    private val isSelfUser: Boolean get() = userId.isBlank() || userId == currentUserId

    private val _uiState = MutableStateFlow(
        ProfileUiState(isSelf = isSelfUser)
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile(forceRefresh = true)
    }

    /**
     * Loads user profile data. When forceRefresh is true, fetches fresh data directly from the backend
     * without relying on stale cache.
     */
    fun loadProfile(forceRefresh: Boolean = true) {
        val targetId = if (userId.isNotBlank()) userId else currentUserId
        if (targetId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "User not identified")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            // 1. Direct fresh backend fetch
            when (val res = userRepository.getUserProfile(targetId, forceRefresh = forceRefresh)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        profile = res.data,
                        isLoading = false,
                        isSelf = res.data.id == currentUserId
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = res.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }

            // 2. Realtime listener for subsequent updates
            userRepository.observeUserProfile(targetId).collect { realtimeProfile ->
                if (realtimeProfile != null && !_uiState.value.isEditMode) {
                    _uiState.value = _uiState.value.copy(
                        profile = realtimeProfile,
                        isSelf = realtimeProfile.id == currentUserId
                    )
                }
            }
        }
    }

    fun setEditMode(enabled: Boolean) {
        if (!_uiState.value.isSelf && enabled) return
        _uiState.value = _uiState.value.copy(
            isEditMode = enabled,
            pendingAvatarUri = if (!enabled) null else _uiState.value.pendingAvatarUri
        )
    }

    fun toggleEditMode() {
        if (!_uiState.value.isSelf) return
        val current = _uiState.value.isEditMode
        _uiState.value = _uiState.value.copy(
            isEditMode = !current,
            pendingAvatarUri = if (current) null else _uiState.value.pendingAvatarUri
        )
    }

    /**
     * Stores selected avatar locally for preview during editing.
     * Upload happens strictly when tapping the Save button.
     */
    fun setPendingAvatar(uri: Uri?) {
        _uiState.value = _uiState.value.copy(pendingAvatarUri = uri)
    }

    fun clearPendingAvatar() {
        _uiState.value = _uiState.value.copy(pendingAvatarUri = null)
    }

    /**
     * Uploads the chosen avatar image to the 'avatars' storage bucket via BackendManager,
     * updates the profile's avatarUrl, and reflects upload progress.
     */
    fun uploadAvatar(context: Context, imageUri: Uri) {
        setPendingAvatar(imageUri)
    }

    /**
     * Saves all inline-edited profile fields (name, bio, phone, avatar) to Firebase.
     * If an avatar was selected, uploads to the 'avatars' bucket first, updates the returned public URL in the DB,
     * updates local cache and credentials, and exits edit mode.
     */
    fun saveProfile(
        displayName: String,
        username: String,
        bio: String,
        phoneNumber: String,
        context: Context? = null
    ) {
        val current = _uiState.value.profile ?: return
        val newName = displayName.trim()
        if (newName.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Name cannot be empty")
            return
        }

        val newUsername = username.trim().removePrefix("@")
        val newBio = bio.trim()
        val newPhone = phoneNumber.trim()
        val pendingUri = _uiState.value.pendingAvatarUri

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)

            var resolvedAvatarUrl = current.avatarUrl

            // 1. Upload pending avatar image ONLY on Save
            if (pendingUri != null && context != null) {
                _uiState.value = _uiState.value.copy(isUploadingAvatar = true, avatarUploadProgress = 0.1f)
                val uploadResult = userRepository.uploadAvatar(context, pendingUri) { progress ->
                    _uiState.value = _uiState.value.copy(avatarUploadProgress = progress)
                }

                when (uploadResult) {
                    is Resource.Success -> {
                        resolvedAvatarUrl = uploadResult.data
                        _uiState.value = _uiState.value.copy(
                            isUploadingAvatar = false,
                            avatarUploadProgress = 1.0f
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            isUploadingAvatar = false,
                            errorMessage = uploadResult.message ?: "Failed to upload avatar"
                        )
                        return@launch
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(isSaving = false, isUploadingAvatar = false)
                        return@launch
                    }
                }
            }

            // 2. Persist updated profile to Firebase Realtime Database and local caches
            val result = userRepository.updateProfile(
                displayName = newName,
                username = if (newUsername.isNotBlank()) newUsername else current.username.orEmpty(),
                bio = newBio,
                statusMessage = current.statusMessage ?: "Available",
                phoneNumber = newPhone,
                avatarUrl = resolvedAvatarUrl
            )

            when (result) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        profile = result.data,
                        pendingAvatarUri = null,
                        isSaving = false,
                        isUploadingAvatar = false,
                        isEditMode = false,
                        successMessage = "Profile updated successfully"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        isUploadingAvatar = false,
                        errorMessage = result.message ?: "Failed to save profile"
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isSaving = false, isUploadingAvatar = false)
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
