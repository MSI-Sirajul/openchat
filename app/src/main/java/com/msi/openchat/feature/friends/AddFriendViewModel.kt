package com.msi.openchat.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.repository.FriendsRepository
import com.msi.openchat.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddFriendUiState(
    val searchQuery: String = "",
    val searchResults: List<Profile> = emptyList(),
    val sentRequestUserIds: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class AddFriendViewModel(
    private val userRepository: UserRepository = ServiceLocator.userRepository,
    private val friendsRepository: FriendsRepository = ServiceLocator.friendsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddFriendUiState())
    val uiState: StateFlow<AddFriendUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    init {
        // Load initial directory
        searchUsers("")
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            searchUsers(query)
        }
    }

    fun searchUsers(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, errorMessage = null)
            when (val res = userRepository.searchUsers(query)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        searchResults = res.data,
                        isSearching = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    fun sendFriendRequest(targetProfile: Profile) {
        viewModelScope.launch {
            when (val res = friendsRepository.sendFriendRequest(targetProfile.id)) {
                is Resource.Success -> {
                    val updatedSet = _uiState.value.sentRequestUserIds + targetProfile.id
                    _uiState.value = _uiState.value.copy(
                        sentRequestUserIds = updatedSet,
                        successMessage = "Friend request sent to ${targetProfile.displayName}"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message)
                }
                else -> Unit
            }
        }
    }
}
