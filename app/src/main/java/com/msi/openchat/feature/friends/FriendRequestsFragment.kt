package com.msi.openchat.feature.friends

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.databinding.FragmentFriendRequestsBinding
import com.google.android.material.tabs.TabLayout
import com.msi.openchat.core.ui.adapter.FriendRequestAdapter
import kotlinx.coroutines.launch

class FriendRequestsFragment : Fragment() {

    private var _binding: FragmentFriendRequestsBinding? = null
    private val binding get() = _binding!!

    private val friendRequestsViewModel: FriendRequestsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarFriendRequests.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.recyclerFriendRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFriendRequests.setHasFixedSize(true)

        binding.tabsFriendRequests.addTab(binding.tabsFriendRequests.newTab().setText("Received"))
        binding.tabsFriendRequests.addTab(binding.tabsFriendRequests.newTab().setText("Sent"))

        binding.tabsFriendRequests.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                friendRequestsViewModel.selectTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    friendRequestsViewModel.uiState.collect { state ->
                        binding.progressFriendRequests.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                        val isReceived = state.selectedTab == 0
                        val currentList = if (isReceived) state.receivedRequests else state.sentRequests

                        val adapter = FriendRequestAdapter(
                            isReceivedTab = isReceived,
                            onAccept = { req ->
                                friendRequestsViewModel.acceptRequest(req) { convId, name ->
                                    (activity as? MainActivity)?.navigateToChat(
                                        conversationId = convId,
                                        contactName = name,
                                        contactId = req.senderId
                                    )
                                }
                            },
                            onDecline = { req -> friendRequestsViewModel.declineRequest(req.id) },
                            onCancel = { req -> friendRequestsViewModel.cancelRequest(req.id) }
                        )
                        binding.recyclerFriendRequests.adapter = adapter
                        adapter.submitList(currentList)

                        binding.textEmptyRequests.visibility =
                            if (!state.isLoading && currentList.isEmpty()) View.VISIBLE else View.GONE

                        state.message?.let { msg ->
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        }
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
        fun newInstance(): FriendRequestsFragment = FriendRequestsFragment()
    }
}
