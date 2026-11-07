package com.msi.openchat.feature.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentProfileBinding
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.presence.PresenceHelper
import com.msi.openchat.data.model.Profile
import kotlinx.coroutines.launch

/**
 * ProfileFragment
 *
 * Implements modern left-aligned Material 3 layout:
 * - Top: left-aligned row with avatar on the left, name and username to its right, and edit pencil button.
 * - Details: left-aligned About/Bio, Phone number, and read-only locked Email.
 * - Inline edit mode: editing directly on layout with toolbar Save button.
 * - Fresh data loading on open (bypassing stale cache).
 * - Custom avatar photo picker with upload to NHost 'avatars' bucket (with Firebase fallback).
 */
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

    // Zero-permission modern Android Photo Picker
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            profileViewModel.uploadAvatar(requireContext(), uri)
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
            if (profileViewModel.uiState.value.isEditMode) {
                profileViewModel.setEditMode(false)
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        setupButtons()
        observeViewModel()
    }

    private fun setupButtons() {
        // Toolbar Save button (visible only in edit mode)
        binding.btnProfileSave.setOnClickListener {
            val name = binding.etEditName.text?.toString().orEmpty()
            val username = binding.etEditUsername.text?.toString().orEmpty()
            val bio = binding.etEditBio.text?.toString().orEmpty()
            val phone = binding.etEditPhone.text?.toString().orEmpty()

            profileViewModel.saveProfile(
                displayName = name,
                username = username,
                bio = bio,
                phoneNumber = phone
            )
        }

        // Edit pencil icon on the right side of the profile header
        binding.btnProfileEdit.setOnClickListener {
            profileViewModel.toggleEditMode()
        }

        // Avatar click to pick photo in edit mode
        val avatarClickListener = View.OnClickListener {
            if (profileViewModel.uiState.value.isEditMode) {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        binding.layoutAvatarContainer.setOnClickListener(avatarClickListener)
        binding.badgeEditAvatar.setOnClickListener(avatarClickListener)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    profileViewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: ProfileUiState) {
        // Loading state
        binding.progressProfile.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.btnProfileSave.isEnabled = !state.isSaving

        // Avatar upload progress overlay
        binding.layoutAvatarUploadProgress.visibility = if (state.isUploadingAvatar) View.VISIBLE else View.GONE

        state.profile?.let { profile ->
            renderProfileData(profile, state)
        }

        // Error and success messages
        state.errorMessage?.let { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            profileViewModel.clearMessages()
        }
        state.successMessage?.let { success ->
            Toast.makeText(requireContext(), success, Toast.LENGTH_SHORT).show()
            profileViewModel.clearMessages()
        }
    }

    private fun renderProfileData(profile: Profile, state: ProfileUiState) {
        val name = profile.displayName.ifBlank { profile.username ?: "User" }
        val username = profile.username.orEmpty()
        val bio = profile.bio?.takeIf { it.isNotBlank() } ?: "Hey there! I am using OpenChat."
        val phone = profile.phoneNumber?.takeIf { it.isNotBlank() } ?: "Not provided"
        val email = profile.email?.takeIf { it.isNotBlank() }
            ?: (ServiceLocator.sessionManager.getUserEmail() ?: "Not provided")

        // 1. Avatar rendering (with image loading & fallback to initial letter)
        com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
            imageView = binding.imgProfileAvatar,
            letterTextView = binding.textProfileAvatar,
            avatarUrl = profile.avatarUrl ?: profile.avatar,
            displayName = name
        )

        // Presence indicator
        val isOnline = PresenceHelper.isUserOnline(profile)
        binding.dotProfileOnline.visibility = if (isOnline && !state.isSelf) View.VISIBLE else View.GONE
        binding.textProfileStatus.text = if (isOnline) "Online" else PresenceHelper.formatPresenceStatus(profile)

        // 2. Read-only locked email
        binding.textProfileEmail.text = email

        // 3. Edit vs View mode rendering
        if (state.isSelf) {
            binding.btnProfileEdit.visibility = View.VISIBLE
            binding.layoutOtherUserActions.visibility = View.GONE

            if (state.isEditMode) {
                // Toolbar & Edit controls
                binding.btnProfileSave.visibility = View.VISIBLE
                binding.badgeEditAvatar.visibility = View.VISIBLE
                binding.btnProfileEdit.contentDescription = "Cancel editing"

                // Switch top header to edit text fields
                binding.layoutHeaderViewMode.visibility = View.GONE
                binding.layoutHeaderEditMode.visibility = View.VISIBLE

                if (binding.etEditName.text.isNullOrBlank()) {
                    binding.etEditName.setText(profile.displayName)
                }
                if (binding.etEditUsername.text.isNullOrBlank()) {
                    binding.etEditUsername.setText(username)
                }

                // Switch details to edit text fields
                binding.textProfileBio.visibility = View.GONE
                binding.tilEditBio.visibility = View.VISIBLE
                if (binding.etEditBio.text.isNullOrBlank()) {
                    binding.etEditBio.setText(profile.bio.orEmpty())
                }

                binding.textProfilePhone.visibility = View.GONE
                binding.tilEditPhone.visibility = View.VISIBLE
                if (binding.etEditPhone.text.isNullOrBlank()) {
                    binding.etEditPhone.setText(profile.phoneNumber.orEmpty())
                }
            } else {
                // View mode for current user
                binding.btnProfileSave.visibility = View.GONE
                binding.badgeEditAvatar.visibility = View.GONE
                binding.btnProfileEdit.contentDescription = "Edit profile"

                // Top header view
                binding.layoutHeaderViewMode.visibility = View.VISIBLE
                binding.layoutHeaderEditMode.visibility = View.GONE
                binding.textProfileName.text = name
                binding.textProfileUsername.text = if (username.isNotBlank()) "@$username" else ""

                // Details view
                binding.textProfileBio.visibility = View.VISIBLE
                binding.tilEditBio.visibility = View.GONE
                binding.textProfileBio.text = bio

                binding.textProfilePhone.visibility = View.VISIBLE
                binding.tilEditPhone.visibility = View.GONE
                binding.textProfilePhone.text = phone
            }
        } else {
            // View mode for other users
            binding.btnProfileEdit.visibility = View.GONE
            binding.btnProfileSave.visibility = View.GONE
            binding.badgeEditAvatar.visibility = View.GONE
            binding.layoutOtherUserActions.visibility = View.VISIBLE

            binding.layoutHeaderViewMode.visibility = View.VISIBLE
            binding.layoutHeaderEditMode.visibility = View.GONE
            binding.textProfileName.text = name
            binding.textProfileUsername.text = if (username.isNotBlank()) "@$username" else ""

            binding.textProfileBio.visibility = View.VISIBLE
            binding.tilEditBio.visibility = View.GONE
            binding.textProfileBio.text = bio

            binding.textProfilePhone.visibility = View.VISIBLE
            binding.tilEditPhone.visibility = View.GONE
            binding.textProfilePhone.text = phone

            // Other user action buttons: Message & Call
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
