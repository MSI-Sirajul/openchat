package com.msi.openchat.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import coil.load
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentChatBinding
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.presence.PresenceHelper
import com.msi.openchat.core.ui.adapter.ChatMessageAdapter
import com.msi.openchat.data.local.OfflineMediaManager
import com.msi.openchat.data.model.Message
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var conversationId: String
    private lateinit var contactName: String
    private lateinit var contactId: String
    private var typingAnimator: AnimatorSet? = null

    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(conversationId, contactName, contactId) as T
            }
        }
    }

    private lateinit var chatMessageAdapter: ChatMessageAdapter

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { chatViewModel.sendImage(requireContext(), it) }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { chatViewModel.sendVideo(requireContext(), it) }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { chatViewModel.sendFile(requireContext(), it) }
    }

    private val micRecordingLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            chatViewModel.startVoiceRecording()
        } else {
            Toast.makeText(requireContext(), "Microphone permission is required to record voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    private val micCallLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            (activity as? MainActivity)?.navigateToCall(contactId, contactName)
        } else {
            Toast.makeText(requireContext(), "Microphone permission is required to start a call", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            conversationId = it.getString(ARG_CONVERSATION_ID, "")
            contactName = it.getString(ARG_CONTACT_NAME, "Contact")
            contactId = it.getString(ARG_CONTACT_ID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupInputBar()
        setupRecordingBar()
        setupPreviewOverlay()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbarChat.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.layoutContactHeader.setOnClickListener {
            if (contactId.isNotBlank()) {
                (activity as? MainActivity)?.navigateToProfile(contactId)
            }
        }

        binding.btnChatCall.setOnClickListener {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                (activity as? MainActivity)?.navigateToCall(contactId, contactName)
            } else {
                micCallLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnChatMore.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menu.add("View Profile")
            popup.setOnMenuItemClickListener {
                (activity as? MainActivity)?.navigateToProfile(contactId)
                true
            }
            popup.show()
        }
    }

    private fun setupRecyclerView() {
        val currentUserId = ServiceLocator.sessionManager.getUserId()
        val isDark = ServiceLocator.themeManager.isDarkMode(requireContext())

        chatMessageAdapter = ChatMessageAdapter(
            currentUserId = currentUserId,
            isDark = isDark,
            onPlayVoice = { message -> chatViewModel.playVoiceMessage(message) },
            onResolveUrl = { ref, callback -> chatViewModel.resolveMediaUrl(ref, callback) },
            onMediaClick = { message, resolvedUrl ->
                val localFile = OfflineMediaManager.resolveMessageMediaFile(requireContext(), message)
                val finalUrl = localFile?.let { "file://${it.absolutePath}" } ?: resolvedUrl
                showMediaPreview(finalUrl, message.type == "video", message.content)
            },
            onAttachmentClick = { message ->
                OfflineMediaManager.openAttachment(requireContext(), message)
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatMessageAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupInputBar() {
        binding.etMessageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                chatViewModel.onInputTextChanged(text)
                if (text.isNotBlank()) {
                    binding.btnChatSendOrMic.setImageResource(R.drawable.ic_send_24)
                    binding.btnChatSendOrMic.contentDescription = "Send Message"
                } else {
                    binding.btnChatSendOrMic.setImageResource(R.drawable.ic_mic_24)
                    binding.btnChatSendOrMic.contentDescription = "Record Voice"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnChatSendOrMic.setOnClickListener {
            val text = binding.etMessageInput.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                chatViewModel.sendTextMessage()
                binding.etMessageInput.setText("")
            } else {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) {
                    chatViewModel.startVoiceRecording()
                } else {
                    micRecordingLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnChatAttach.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menu.add(0, 1, 0, "Photo")
            popup.menu.add(0, 2, 1, "Video")
            popup.menu.add(0, 3, 2, "Document")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> photoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    2 -> videoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly
                        )
                    )
                    3 -> filePickerLauncher.launch("*/*")
                }
                true
            }
            popup.show()
        }
    }

    private fun setupRecordingBar() {
        binding.btnCancelRecording.setOnClickListener {
            chatViewModel.cancelVoiceRecording()
        }

        binding.btnSendRecording.setOnClickListener {
            chatViewModel.finishAndSendVoiceRecording()
        }
    }

    private fun setupPreviewOverlay() {
        binding.btnClosePreview.setOnClickListener {
            binding.videoPreviewPlayer.stopPlayback()
            binding.layoutFullscreenPreview.visibility = View.GONE
        }
        binding.layoutFullscreenPreview.setOnClickListener {
            binding.videoPreviewPlayer.stopPlayback()
            binding.layoutFullscreenPreview.visibility = View.GONE
        }
    }

    private fun showMediaPreview(url: String, isVideo: Boolean, title: String?) {
        binding.layoutFullscreenPreview.visibility = View.VISIBLE
        binding.textPreviewTitle.text = title?.takeIf { it.isNotBlank() } ?: (if (isVideo) "Video Preview" else "Photo Preview")

        if (isVideo) {
            binding.imgPreviewPhoto.visibility = View.GONE
            binding.videoPreviewPlayer.visibility = View.VISIBLE
            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(binding.videoPreviewPlayer)
            binding.videoPreviewPlayer.setMediaController(mediaController)

            val videoUri = if (url.startsWith("file://") || url.startsWith("content://") || url.startsWith("http://") || url.startsWith("https://")) {
                Uri.parse(url)
            } else {
                Uri.fromFile(java.io.File(url))
            }
            binding.videoPreviewPlayer.setVideoURI(videoUri)
            binding.videoPreviewPlayer.setOnPreparedListener { mp ->
                mp.isLooping = true
                binding.videoPreviewPlayer.start()
            }
        } else {
            binding.videoPreviewPlayer.stopPlayback()
            binding.videoPreviewPlayer.visibility = View.GONE
            binding.imgPreviewPhoto.visibility = View.VISIBLE
            if (url.startsWith("data:")) {
                try {
                    val raw = if (url.contains(",")) url.substringAfter(",") else url
                    val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp != null) {
                        binding.imgPreviewPhoto.setImageBitmap(bmp)
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("ChatFragment", "Failed to decode preview image: ${t.message}")
                }
            } else if (url.startsWith("/") && !url.startsWith("file://")) {
                binding.imgPreviewPhoto.load(java.io.File(url)) {
                    crossfade(true)
                }
            } else {
                binding.imgPreviewPhoto.load(url) {
                    crossfade(true)
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatViewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: ChatUiState) {
        // Toolbar header info
        binding.textChatContactName.text = state.contactName
        binding.textChatAvatar.text = state.contactName.take(1).uppercase()

        val isOnline = PresenceHelper.isUserOnline(state.contactProfile)
        binding.dotChatOnline.visibility = if (isOnline) View.VISIBLE else View.GONE

        val statusText = if (state.isContactTyping) {
            "typing..."
        } else {
            PresenceHelper.formatPresenceStatus(state.contactProfile)
        }
        binding.textChatContactStatus.text = statusText
        if (state.isContactTyping) {
            binding.textChatContactStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_primary))
        } else if (isOnline) {
            binding.textChatContactStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_online))
        } else {
            binding.textChatContactStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_secondary_text))
        }

        // Messages list
        val prevSize = chatMessageAdapter.currentList.size
        chatMessageAdapter.submitList(state.messages) {
            if (state.messages.size > prevSize && state.messages.isNotEmpty()) {
                binding.recyclerMessages.smoothScrollToPosition(state.messages.size - 1)
            }
        }

        // Playback progress update in adapter
        chatMessageAdapter.updatePlaybackState(
            state.currentlyPlayingMessageId,
            state.playbackProgress
        )

        // Real-time animated typing indicator
        if (state.isContactTyping) {
            binding.textTypingLabel.text = "${state.contactName} is typing..."
            if (binding.layoutTypingIndicator.visibility != View.VISIBLE) {
                binding.layoutTypingIndicator.visibility = View.VISIBLE
                binding.layoutTypingIndicator.alpha = 0f
                binding.layoutTypingIndicator.animate().alpha(1f).setDuration(220).start()
                startTypingAnimation()
                binding.recyclerMessages.post {
                    if (chatMessageAdapter.itemCount > 0) {
                        binding.recyclerMessages.smoothScrollToPosition(chatMessageAdapter.itemCount - 1)
                    }
                }
            }
        } else {
            if (binding.layoutTypingIndicator.visibility == View.VISIBLE) {
                binding.layoutTypingIndicator.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.layoutTypingIndicator.visibility = View.GONE
                    stopTypingAnimation()
                }.start()
            } else {
                binding.layoutTypingIndicator.visibility = View.GONE
                stopTypingAnimation()
            }
        }

        // Media uploading progress
        binding.layoutUploadProgress.visibility = if (state.isUploadingMedia) View.VISIBLE else View.GONE

        // Recording bar
        if (state.isRecordingVoice) {
            binding.layoutRecordingBar.visibility = View.VISIBLE
            binding.layoutInputBar.visibility = View.GONE
            val min = state.recordingDurationSeconds / 60
            val sec = state.recordingDurationSeconds % 60
            binding.textRecordingDuration.text = "Recording %02d:%02d".format(min, sec)
        } else {
            binding.layoutRecordingBar.visibility = View.GONE
            binding.layoutInputBar.visibility = View.VISIBLE
        }

        // Error message
        state.errorMessage?.let { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
            chatViewModel.clearErrorMessage()
        }
    }

    private fun startTypingAnimation() {
        if (typingAnimator != null) return
        val d1 = binding.dotTyping1
        val d2 = binding.dotTyping2
        val d3 = binding.dotTyping3

        val a1 = ObjectAnimator.ofFloat(d1, "translationY", 0f, -8f, 0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val a2 = ObjectAnimator.ofFloat(d2, "translationY", 0f, -8f, 0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            startDelay = 150
            interpolator = AccelerateDecelerateInterpolator()
        }
        val a3 = ObjectAnimator.ofFloat(d3, "translationY", 0f, -8f, 0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            startDelay = 300
            interpolator = AccelerateDecelerateInterpolator()
        }

        typingAnimator = AnimatorSet().apply {
            playTogether(a1, a2, a3)
            start()
        }
    }

    private fun stopTypingAnimation() {
        typingAnimator?.cancel()
        typingAnimator = null
        _binding?.dotTyping1?.translationY = 0f
        _binding?.dotTyping2?.translationY = 0f
        _binding?.dotTyping3?.translationY = 0f
    }

    override fun onDestroyView() {
        stopTypingAnimation()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_CONVERSATION_ID = "conversation_id"
        private const val ARG_CONTACT_NAME = "contact_name"
        private const val ARG_CONTACT_ID = "contact_id"

        fun newInstance(
            conversationId: String,
            contactName: String,
            contactId: String
        ): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONVERSATION_ID, conversationId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_CONTACT_ID, contactId)
                }
            }
        }
    }
}
