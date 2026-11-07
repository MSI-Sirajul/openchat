package com.msi.openchat.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.repository.FriendsRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.core.ui.adapter.RelationshipStatus
import com.msi.openchat.core.ui.adapter.UserSearchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddFriendUiState(
    val searchQuery: String = "",
    val searchResults: List<UserSearchItem> = emptyList(),
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
            when (val res = friendsRepository.searchNonFriendUsers(query)) {
                is Resource.Success -> {
                    // Enforce frontend filter: already-friends must NOT appear in search list
                    val filtered = res.data.filter { it.status != RelationshipStatus.FRIENDS }
                    _uiState.value = _uiState.value.copy(
                        searchResults = filtered,
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
                    // Update frontend state immediately to Requested
                    val updatedResults = _uiState.value.searchResults.map { item ->
                        if (item.profile.id == targetProfile.id) {
                            item.copy(status = RelationshipStatus.REQUESTED)
                        } else {
                            item
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        searchResults = updatedResults,
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
