package com.msi.openchat.feature.friends

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.example.databinding.FragmentAddFriendBinding
import com.msi.openchat.core.ui.adapter.UserSearchAdapter
import com.msi.openchat.core.ui.adapter.UserSearchItem
import kotlinx.coroutines.launch

class AddFriendFragment : Fragment() {

    private var _binding: FragmentAddFriendBinding? = null
    private val binding get() = _binding!!

    private val addFriendViewModel: AddFriendViewModel by viewModels()
    private lateinit var searchAdapter: UserSearchAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddFriendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarAddFriend.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        searchAdapter = UserSearchAdapter { profile ->
            addFriendViewModel.sendFriendRequest(profile)
        }

        binding.recyclerUserSearch.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
            setHasFixedSize(true)
        }

        binding.etSearchUsers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                addFriendViewModel.onSearchQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    addFriendViewModel.uiState.collect { state ->
                        binding.progressUserSearch.visibility = if (state.isSearching) View.VISIBLE else View.GONE

                        searchAdapter.submitList(state.searchResults)

                        binding.textEmptySearch.visibility =
                            if (!state.isSearching && state.searchResults.isEmpty()) View.VISIBLE else View.GONE

                        state.errorMessage?.let { error ->
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                        }
                        state.successMessage?.let { success ->
                            Toast.makeText(requireContext(), success, Toast.LENGTH_SHORT).show()
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
        fun newInstance(): AddFriendFragment = AddFriendFragment()
    }
}
