package com.msi.openchat.feature.chat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.databinding.FragmentNewChatBinding
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.ui.adapter.FriendAdapter
import com.msi.openchat.data.model.Friendship
import com.msi.openchat.feature.home.HomeViewModel
import kotlinx.coroutines.launch

class NewChatFragment : Fragment() {

    private var _binding: FragmentNewChatBinding? = null
    private val binding get() = _binding!!
    private val homeViewModel: HomeViewModel by activityViewModels()

    private lateinit var friendAdapter: FriendAdapter
    private var allFriends: List<Friendship> = emptyList()
    private var searchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupActions()
        observeFriends()
    }

    private fun setupToolbar() {
        binding.toolbarNewChat.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSearchToggle.setOnClickListener {
            val isVisible = binding.layoutSearchBox.visibility == View.VISIBLE
            binding.layoutSearchBox.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearchContacts.requestFocus()
            } else {
                binding.etSearchContacts.setText("")
                searchQuery = ""
                filterList()
            }
        }

        binding.etSearchContacts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase()
                filterList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

        friendAdapter = FriendAdapter(isDark = isDark) { friendship ->
            val friend = friendship.friendProfile
            if (friend != null) {
                val currentUid = ServiceLocator.sessionManager.getUserId().orEmpty()
                val convId = if (currentUid < friend.id) "direct_${currentUid}_${friend.id}" else "direct_${friend.id}_${currentUid}"
                val name = friend.displayName.ifBlank { friend.username ?: "Friend" }

                // Pop NewChatFragment from backstack then navigate to chat
                parentFragmentManager.popBackStack()
                (activity as? MainActivity)?.navigateToChat(convId, name, friend.id)
            }
        }

        binding.recyclerNewChatContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = friendAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupActions() {
        binding.btnActionNewContact.setOnClickListener {
            (activity as? MainActivity)?.navigateToAddFriends()
        }

        binding.btnEmptyFindFriends.setOnClickListener {
            (activity as? MainActivity)?.navigateToAddFriends()
        }

        binding.btnActionNewGroup.setOnClickListener {
            (activity as? MainActivity)?.navigateToAddFriends()
        }
    }

    private fun observeFriends() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.uiState.collect { state ->
                    allFriends = state.friends
                    filterList()
                }
            }
        }
    }

    private fun filterList() {
        val filtered = if (searchQuery.isBlank()) {
            allFriends
        } else {
            allFriends.filter {
                val name = it.friendProfile?.displayName.orEmpty().lowercase()
                val user = it.friendProfile?.username.orEmpty().lowercase()
                name.contains(searchQuery) || user.contains(searchQuery)
            }
        }

        friendAdapter.submitList(filtered)
        val count = allFriends.size
        binding.textContactCount.text = if (count == 1) "1 contact" else "$count contacts"

        val isEmpty = filtered.isEmpty()
        binding.layoutEmptyContacts.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerNewChatContacts.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): NewChatFragment = NewChatFragment()
    }
}
