package com.msi.openchat.feature.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.MainActivity
import com.example.databinding.FragmentProfileBinding
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.presence.PresenceHelper
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var userId: String

    private val profileViewModel: ProfileViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProfileViewModel(userId) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userId = arguments?.getString(ARG_USER_ID, "").orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarProfile.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    profileViewModel.uiState.collect { state ->
                        binding.progressProfile.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                        state.profile?.let { profile ->
                            val name = profile.displayName.ifBlank { profile.username ?: "User" }
                            binding.textProfileName.text = name
                            binding.textProfileAvatar.text = name.take(1).uppercase()
                            binding.textProfileUsername.text = if (!profile.username.isNullOrBlank()) "@${profile.username}" else ""
                            binding.textProfileBio.text = profile.bio?.takeIf { it.isNotBlank() } ?: "Hey there! I am using OpenChat."
                            binding.textProfilePhone.text = profile.phoneNumber?.takeIf { it.isNotBlank() } ?: "Not provided"

                            val isOnline = PresenceHelper.isUserOnline(profile)
                            binding.textProfileStatus.text = if (isOnline) "Online" else PresenceHelper.formatPresenceStatus(profile)

                            val currentUid = ServiceLocator.sessionManager.getUserId().orEmpty()
                            val isSelf = profile.id == currentUid
                            binding.dotProfileOnline.visibility = if (isOnline && !isSelf) View.VISIBLE else View.GONE

                            if (isSelf) {
                                binding.btnProfileChat.visibility = View.GONE
                                binding.btnProfileCall.visibility = View.GONE
                                binding.btnProfileEditToolbar.visibility = View.VISIBLE
                                binding.btnProfileEditToolbar.setOnClickListener {
                                    showEditProfileDialog(profile)
                                }
                            } else {
                                binding.btnProfileChat.visibility = View.VISIBLE
                                binding.btnProfileCall.visibility = View.VISIBLE
                                binding.btnProfileEditToolbar.visibility = View.GONE

                                binding.btnProfileChat.setOnClickListener {
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        when (val res = ServiceLocator.chatRepository.getOrCreateConversation(profile.id)) {
                                            is Resource.Success -> {
                                                (activity as? MainActivity)?.navigateToChat(
                                                    conversationId = res.data.id,
                                                    contactName = name,
                                                    contactId = profile.id
                                                )
                                            }
                                            is Resource.Error -> {
                                                Toast.makeText(requireContext(), res.message, Toast.LENGTH_SHORT).show()
                                            }
                                            else -> Unit
                                        }
                                    }
                                }

                                binding.btnProfileCall.setOnClickListener {
                                    (activity as? MainActivity)?.navigateToCall(profile.id, name)
                                }
                            }
                        }

                        state.errorMessage?.let { error ->
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showEditProfileDialog(currentProfile: com.msi.openchat.data.model.Profile) {
        val dialogBinding = com.example.databinding.DialogEditProfileBinding.inflate(layoutInflater)
        dialogBinding.etEditDisplayName.setText(currentProfile.displayName)
        dialogBinding.etEditBio.setText(currentProfile.bio.orEmpty())

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val newName = dialogBinding.etEditDisplayName.text?.toString().orEmpty().trim()
                val newBio = dialogBinding.etEditBio.text?.toString().orEmpty().trim()
                if (newName.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        ServiceLocator.userRepository.updateProfile(
                            displayName = newName,
                            username = currentProfile.username.orEmpty(),
                            bio = newBio,
                            statusMessage = currentProfile.statusMessage ?: "Available"
                        )
                        binding.textProfileName.text = newName
                        binding.textProfileAvatar.text = newName.take(1).uppercase()
                        binding.textProfileBio.text = newBio.ifBlank { "Hey there! I am using OpenChat." }
                        Toast.makeText(requireContext(), "Profile updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_USER_ID = "user_id"

        fun newInstance(userId: String): ProfileFragment {
            return ProfileFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, userId)
                }
            }
        }
    }
}
