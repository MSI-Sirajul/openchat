package com.msi.openchat.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.NetworkMonitor
import com.msi.openchat.core.common.NetworkStatus
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.Conversation
import com.msi.openchat.data.model.Friendship
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.data.repository.CallRepository
import com.msi.openchat.data.repository.ChatRepository
import com.msi.openchat.data.repository.FriendsRepository
import com.msi.openchat.data.repository.UserRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val selectedTab: Int = 0, // 0 = Chats, 1 = Calls
    val conversations: List<Conversation> = emptyList(),
    val callHistory: List<CallSession> = emptyList(),
    val friends: List<Friendship> = emptyList(),
    val pendingRequestCount: Int = 0,
    val isLoadingChats: Boolean = false,
    val isLoadingCalls: Boolean = false,
    val errorMessage: String? = null,
    val isNewChatDialogOpen: Boolean = false,
    val searchQuery: String = "",
    val networkStatus: NetworkStatus = NetworkStatus()
)

class HomeViewModel(
    private val chatRepository: ChatRepository = ServiceLocator.chatRepository,
    private val callRepository: CallRepository = ServiceLocator.callRepository,
    private val friendsRepository: FriendsRepository = ServiceLocator.friendsRepository,
    private val userRepository: UserRepository = ServiceLocator.userRepository,
    private val sessionManager: SessionManager = ServiceLocator.sessionManager,
    private val networkMonitor: NetworkMonitor = ServiceLocator.networkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var observedProfilesJob: Job? = null

    init {
        observeLocalConversations()
        loadData()
        observeRealtime()
        observeNetwork()
        updatePresence()
    }

    private fun observeLocalConversations() {
        viewModelScope.launch {
            chatRepository.observeLocalConversations().collect { localChats ->
                _uiState.value = _uiState.value.copy(
                    conversations = localChats,
                    isLoadingChats = false
                )
                if (localChats.isNotEmpty()) {
                    refreshObservedContactProfiles()
                }
            }
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tabIndex)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setNewChatDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isNewChatDialogOpen = open)
    }

    fun loadData() {
        loadConversations()
        loadCalls()
        loadFriends()
        loadPendingRequests()
    }

    private fun updatePresence() {
        viewModelScope.launch {
            userRepository.updateOnlineStatus("online")
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.status.collect { status ->
                _uiState.value = _uiState.value.copy(networkStatus = status)
            }
        }
    }

    fun loadConversations() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChats = true, errorMessage = null)
            when (val res = chatRepository.getConversations()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        conversations = res.data,
                        isLoadingChats = false
                    )
                    refreshObservedContactProfiles()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingChats = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    fun loadCalls() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingCalls = true)
            when (val res = callRepository.getCallHistory()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        callHistory = res.data,
                        isLoadingCalls = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoadingCalls = false)
                }
                else -> Unit
            }
        }
    }

    fun loadFriends() {
        viewModelScope.launch {
            when (val res = friendsRepository.getFriends()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(friends = res.data)
                    refreshObservedContactProfiles()
                }
                else -> Unit
            }
        }
    }

    fun loadPendingRequests() {
        viewModelScope.launch {
            when (val res = friendsRepository.getReceivedRequests()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(pendingRequestCount = res.data.size)
                }
                else -> Unit
            }
        }
    }

    private fun refreshObservedContactProfiles() {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid.orEmpty()
        val convUserIds = _uiState.value.conversations.mapNotNull { conv ->
            val otherId = if (conv.participant1Id == currentUserId) conv.participant2Id else conv.participant1Id
            otherId.takeIf { it.isNotBlank() && it != currentUserId }
        }
        val friendUserIds = _uiState.value.friends.mapNotNull { friendship ->
            val fid = if (friendship.user1Id == currentUserId) friendship.user2Id else friendship.user1Id
            fid.takeIf { it.isNotBlank() && it != currentUserId }
        }
        val allUserIds = (convUserIds + friendUserIds).distinct()
        if (allUserIds.isEmpty()) return

        observedProfilesJob?.cancel()
        observedProfilesJob = viewModelScope.launch {
            userRepository.observeUserProfiles(allUserIds).collect { profileMap ->
                if (profileMap.isNotEmpty()) {
                    val updatedConvs = _uiState.value.conversations.map { conv ->
                        val otherId = if (conv.participant1Id == currentUserId) conv.participant2Id else conv.participant1Id
                        val newProfile = profileMap[otherId]
                        if (newProfile != null) conv.copy(otherUser = newProfile) else conv
                    }
                    val updatedFriends = _uiState.value.friends.map { friendship ->
                        val fid = if (friendship.user1Id == currentUserId) friendship.user2Id else friendship.user1Id
                        val newProfile = profileMap[fid]
                        if (newProfile != null) friendship.copy(friendProfile = newProfile) else friendship
                    }
                    _uiState.value = _uiState.value.copy(
                        conversations = updatedConvs,
                        friends = updatedFriends
                    )
                }
            }
        }
    }

    private var attachedUserId: String? = null
    private var convListener: ValueEventListener? = null
    private var friendListener: ValueEventListener? = null
    private var reqListener: ValueEventListener? = null
    private var callListener: ValueEventListener? = null

    private fun observeRealtime() {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid.orEmpty()
        if (currentUserId.isBlank()) return

        removeFirebaseListeners()
        attachedUserId = currentUserId

        // 1. Observe real-time conversation changes
        val cl = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadConversations()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        convListener = cl
        FirebaseManager.conversationsRef.child(currentUserId).addValueEventListener(cl)

        // 2. Observe real-time friend requests and friendships
        val fl = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadFriends()
                loadPendingRequests()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        friendListener = fl
        FirebaseManager.friendsRef.child(currentUserId).addValueEventListener(fl)

        val rl = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadFriends()
                loadPendingRequests()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        reqListener = rl
        FirebaseManager.friendRequestsRef.child(currentUserId).addValueEventListener(rl)

        // 3. Observe real-time incoming call signaling alerts
        val callL = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val callId = snapshot.child("call_id").getValue(String::class.java).orEmpty()
                    val callerId = snapshot.child("caller_id").getValue(String::class.java).orEmpty()
                    val callerName = snapshot.child("caller_name").getValue(String::class.java) ?: "Friend"
                    if (callId.isNotBlank()) {
                        com.msi.openchat.service.notification.OpenChatNotificationManager.showIncomingCallNotification(
                            context = ServiceLocator.context,
                            callId = callId,
                            callerId = callerId,
                            callerName = callerName
                        )
                    }
                } else {
                    com.msi.openchat.service.notification.OpenChatNotificationManager.cancelIncomingCallNotification(ServiceLocator.context)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        callListener = callL
        FirebaseManager.usersRef.child(currentUserId).child("incoming_call").addValueEventListener(callL)
    }

    private fun removeFirebaseListeners() {
        val uid = attachedUserId
        if (!uid.isNullOrBlank()) {
            convListener?.let { FirebaseManager.conversationsRef.child(uid).removeEventListener(it) }
            friendListener?.let { FirebaseManager.friendsRef.child(uid).removeEventListener(it) }
            reqListener?.let { FirebaseManager.friendRequestsRef.child(uid).removeEventListener(it) }
            callListener?.let { FirebaseManager.usersRef.child(uid).child("incoming_call").removeEventListener(it) }
            convListener = null
            friendListener = null
            reqListener = null
            callListener = null
            attachedUserId = null
        }
    }

    fun startConversationWithFriend(friendId: String, onConversationReady: (String, String) -> Unit) {
        viewModelScope.launch {
            when (val res = chatRepository.getOrCreateConversation(friendId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(isNewChatDialogOpen = false)
                    val conv = res.data
                    val contactName = conv.otherUser?.displayName ?: "Friend"
                    onConversationReady(conv.id, contactName)
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message)
                }
                else -> Unit
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        observedProfilesJob?.cancel()
        removeFirebaseListeners()
    }
}
