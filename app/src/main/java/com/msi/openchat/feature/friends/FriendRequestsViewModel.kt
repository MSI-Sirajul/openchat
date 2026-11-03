package com.msi.openchat.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.FriendRequest
import com.msi.openchat.data.repository.FriendsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FriendRequestsUiState(
    val selectedTab: Int = 0, // 0 = Received, 1 = Sent
    val receivedRequests: List<FriendRequest> = emptyList(),
    val sentRequests: List<FriendRequest> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val acceptedConversationId: String? = null
)

class FriendRequestsViewModel(
    private val friendsRepository: FriendsRepository = ServiceLocator.friendsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendRequestsUiState())
    val uiState: StateFlow<FriendRequestsUiState> = _uiState.asStateFlow()

    init {
        loadRequests()
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun loadRequests() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val received = friendsRepository.getReceivedRequests()
            val sent = friendsRepository.getSentRequests()

            _uiState.value = _uiState.value.copy(
                receivedRequests = if (received is Resource.Success) received.data else emptyList(),
                sentRequests = if (sent is Resource.Success) sent.data else emptyList(),
                isLoading = false
            )
        }
    }

    fun acceptRequest(request: FriendRequest, onAccepted: (conversationId: String, friendName: String) -> Unit) {
        viewModelScope.launch {
            when (val res = friendsRepository.acceptFriendRequest(request.id)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        message = "Accepted friend request from ${request.sender?.displayName}"
                    )
                    loadRequests()
                    val friendName = request.sender?.displayName ?: "Friend"
                    onAccepted(res.data, friendName)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(message = res.message)
                }
                else -> Unit
            }
        }
    }

    fun declineRequest(requestId: String) {
        viewModelScope.launch {
            when (val res = friendsRepository.declineFriendRequest(requestId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(message = "Request declined")
                    loadRequests()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(message = res.message)
                }
                else -> Unit
            }
        }
    }

    fun cancelRequest(requestId: String) {
        viewModelScope.launch {
            when (val res = friendsRepository.cancelFriendRequest(requestId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(message = "Request cancelled")
                    loadRequests()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(message = res.message)
                }
                else -> Unit
            }
        }
    }
}
