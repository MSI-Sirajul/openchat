package com.msi.openchat.feature.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.MainActivity
import com.example.R
import com.example.databinding.DialogSelectFriendBinding
import com.example.databinding.FragmentHomeBinding
import com.google.android.material.tabs.TabLayout
import com.msi.openchat.core.ui.adapter.CallHistoryAdapter
import com.msi.openchat.core.ui.adapter.ConversationAdapter
import com.msi.openchat.core.ui.adapter.FriendAdapter
import com.msi.openchat.data.model.Conversation
import com.msi.openchat.data.model.Friendship
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by activityViewModels()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private lateinit var friendAdapter: FriendAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupRecyclerViews()
        setupActions()
        observeState()
    }

    private var isUpdatingTabs = false

    private fun setupTabs() {
        // Material 3 Bottom Navigation View
        binding.bottomNavHome.setOnItemSelectedListener { item ->
            if (isUpdatingTabs) return@setOnItemSelectedListener true
            val tabIndex = when (item.itemId) {
                R.id.nav_chats -> 0
                R.id.nav_calls -> 1
                R.id.nav_friends -> 2
                else -> 0
            }
            viewModel.selectTab(tabIndex)
            applyTabVisibility(tabIndex)
            true
        }
    }

    private fun applyTabVisibility(tabIndex: Int) {
        binding.viewTabChats.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.viewTabCalls.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.viewTabFriends.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        when (tabIndex) {
            0 -> {
                binding.fabHomeAction.setImageResource(R.drawable.ic_chat_24)
                binding.fabHomeAction.contentDescription = "New Chat"
                binding.fabHomeAction.visibility = View.VISIBLE
            }
            1 -> {
                binding.fabHomeAction.setImageResource(R.drawable.ic_call_24)
                binding.fabHomeAction.contentDescription = "New Call"
                binding.fabHomeAction.visibility = View.VISIBLE
            }
            2 -> {
                binding.fabHomeAction.setImageResource(R.drawable.ic_person_add_24)
                binding.fabHomeAction.contentDescription = "Add Friend"
                binding.fabHomeAction.visibility = View.VISIBLE
            }
        }
    }

    private fun setupRecyclerViews() {
        val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // 1. Conversations
        conversationAdapter = ConversationAdapter(isDark = isDark) { conv ->
            val other = conv.otherUser
            val name = other?.displayName?.ifBlank { other.username ?: "User" } ?: "Chat"
            val otherId = other?.id ?: if (conv.participant1Id == viewModel.uiState.value.conversations.firstOrNull()?.participant1Id) conv.participant2Id else conv.participant1Id
            (activity as? MainActivity)?.navigateToChat(conv.id, name, otherId)
        }
        binding.recyclerConversations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = conversationAdapter
            setHasFixedSize(true)
        }

        // 2. Call History
        callHistoryAdapter = CallHistoryAdapter(isDark = isDark) { contactId, contactName ->
            (activity as? MainActivity)?.navigateToCall(contactId, contactName)
        }
        binding.recyclerCalls.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callHistoryAdapter
            setHasFixedSize(true)
        }

        // 3. Friends
        friendAdapter = FriendAdapter(isDark = isDark) { friendship ->
            val friend = friendship.friendProfile
            if (friend != null) {
                val currentUid = com.msi.openchat.core.di.ServiceLocator.sessionManager.getUserId().orEmpty()
                val convId = if (currentUid < friend.id) "direct_${currentUid}_${friend.id}" else "direct_${friend.id}_${currentUid}"
                val name = friend.displayName.ifBlank { friend.username ?: "Friend" }
                (activity as? MainActivity)?.navigateToChat(convId, name, friend.id)
            }
        }
        binding.recyclerFriends.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = friendAdapter
            setHasFixedSize(true)
        }

        // Pull to refresh
        binding.swipeRefreshChats.setOnRefreshListener {
            viewModel.loadData()
            binding.swipeRefreshChats.isRefreshing = false
        }
        binding.swipeRefreshCalls.setOnRefreshListener {
            viewModel.loadData()
            binding.swipeRefreshCalls.isRefreshing = false
        }
        binding.swipeRefreshFriends.setOnRefreshListener {
            viewModel.loadData()
            binding.swipeRefreshFriends.isRefreshing = false
        }
    }

    private fun setupActions() {
        binding.btnHomeProfile.setOnClickListener {
            val currentUid = com.msi.openchat.core.di.ServiceLocator.sessionManager.getUserId().orEmpty()
            (activity as? MainActivity)?.navigateToProfile(currentUid)
        }

        binding.btnHomeSearch.setOnClickListener {
            val isCurrentlyVisible = binding.layoutSearchBox.visibility == View.VISIBLE
            binding.layoutSearchBox.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            if (!isCurrentlyVisible) {
                binding.etSearchFilter.requestFocus()
            } else {
                binding.etSearchFilter.setText("")
                viewModel.setSearchQuery("")
            }
        }

        binding.etSearchFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnHomeFriendRequests.setOnClickListener {
            (activity as? MainActivity)?.navigateToFriendRequests()
        }

        binding.btnHomeSettings.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        binding.fabHomeAction.setOnClickListener {
            val tab = viewModel.uiState.value.selectedTab
            when (tab) {
                0 -> (activity as? MainActivity)?.navigateToNewChat()
                1 -> (activity as? MainActivity)?.navigateToNewChat()
                2 -> (activity as? MainActivity)?.navigateToAddFriends()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val query = state.searchQuery.trim().lowercase()

                    // Filtered Conversations
                    val filteredChats = if (query.isBlank()) {
                        state.conversations
                    } else {
                        state.conversations.filter {
                            val name = it.otherUser?.displayName.orEmpty().lowercase()
                            val user = it.otherUser?.username.orEmpty().lowercase()
                            val text = it.lastMessageText.orEmpty().lowercase()
                            name.contains(query) || user.contains(query) || text.contains(query)
                        }
                    }
                    conversationAdapter.submitList(filteredChats)
                    binding.layoutEmptyChatsContainer.visibility = if (filteredChats.isEmpty()) View.VISIBLE else View.GONE
                    binding.textEmptyChats.visibility = if (filteredChats.isEmpty()) View.VISIBLE else View.GONE

                    // Filtered Calls
                    val filteredCalls = if (query.isBlank()) {
                        state.callHistory
                    } else {
                        state.callHistory.filter {
                            val otherName = (it.callerProfile?.displayName ?: it.receiverProfile?.displayName).orEmpty()
                            otherName.lowercase().contains(query)
                        }
                    }
                    callHistoryAdapter.submitList(filteredCalls)
                    binding.layoutEmptyCallsContainer.visibility = if (filteredCalls.isEmpty()) View.VISIBLE else View.GONE
                    binding.textEmptyCalls.visibility = if (filteredCalls.isEmpty()) View.VISIBLE else View.GONE

                    // Filtered Friends
                    val filteredFriends = if (query.isBlank()) {
                        state.friends
                    } else {
                        state.friends.filter {
                            val name = it.friendProfile?.displayName.orEmpty().lowercase()
                            val user = it.friendProfile?.username.orEmpty().lowercase()
                            name.contains(query) || user.contains(query)
                        }
                    }
                    friendAdapter.submitList(filteredFriends)
                    binding.layoutEmptyFriendsContainer.visibility = if (filteredFriends.isEmpty()) View.VISIBLE else View.GONE
                    binding.textEmptyFriends.visibility = if (filteredFriends.isEmpty()) View.VISIBLE else View.GONE

                    // User Profile Avatar Initial
                    val currentUid = com.msi.openchat.core.di.ServiceLocator.sessionManager.getUserId()
                    if (!currentUid.isNullOrBlank()) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val res = com.msi.openchat.core.di.ServiceLocator.userRepository.getCurrentUserProfile()
                            if (res is com.msi.openchat.core.common.Resource.Success) {
                                val name = res.data.displayName.ifBlank { res.data.username ?: "U" }
                                binding.textHomeProfileAvatar.text = name.take(1).uppercase()
                            }
                        }
                    }

                    // Pending Requests Badge
                    if (state.pendingRequestCount > 0) {
                        binding.badgeRequestCount.text = state.pendingRequestCount.toString()
                        binding.badgeRequestCount.visibility = View.VISIBLE
                    } else {
                        binding.badgeRequestCount.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): HomeFragment = HomeFragment()
    }
}
