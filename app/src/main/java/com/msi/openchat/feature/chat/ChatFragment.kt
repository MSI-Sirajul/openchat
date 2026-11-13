package com.msi.openchat.feature.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private var autoSendHi: Boolean = false
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
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            com.msi.openchat.core.media.PersistableUriManager.takePersistablePermission(requireContext(), it)
            chatViewModel.sendImageUri(requireContext(), it)
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            com.msi.openchat.core.media.PersistableUriManager.takePersistablePermission(requireContext(), it)
            chatViewModel.sendVideoUri(requireContext(), it)
        }
    }

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            com.msi.openchat.core.media.PersistableUriManager.takePersistablePermission(requireContext(), it)
            chatViewModel.sendAudioUri(requireContext(), it)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            com.msi.openchat.core.media.PersistableUriManager.takePersistablePermission(requireContext(), it)
            chatViewModel.sendFileUri(requireContext(), it)
        }
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
            com.msi.openchat.feature.calls.OutgoingAudioCallActivity.start(
                context = requireContext(),
                contactId = contactId,
                contactName = contactName.ifBlank { "Contact" }
            )
        } else {
            Toast.makeText(requireContext(), "Microphone permission is required to start a call", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingMediaAction: (() -> Unit)? = null

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMediaAction?.invoke()
        } else {
            Toast.makeText(
                requireContext(),
                "Storage and media permission is needed to select attachments",
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingMediaAction = null
    }

    private fun checkAndRequestMediaPermission(permission: String, onGranted: () -> Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(
            requireContext(),
            permission
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            onGranted()
        } else {
            pendingMediaAction = onGranted
            mediaPermissionLauncher.launch(permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            conversationId = it.getString(ARG_CONVERSATION_ID, "")
            contactName = it.getString(ARG_CONTACT_NAME, "Contact")
            contactId = it.getString(ARG_CONTACT_ID, "")
            autoSendHi = it.getBoolean(ARG_AUTO_SEND_HI, false)
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

        if (autoSendHi) {
            autoSendHi = false
            chatViewModel.sendDirectMessage("Hi")
        }
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

        // Initialize contact name and avatar immediately so header is never blank
        val initialName = contactName.ifBlank { "Contact" }
        binding.textChatContactName.text = initialName
        binding.textChatContactStatus.text = "Offline"
        com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
            imageView = binding.imgChatAvatar,
            letterTextView = binding.textChatAvatar,
            avatarUrl = null,
            displayName = initialName
        )

        binding.btnChatVideoCall.setOnClickListener {
            if (contactId.isBlank()) {
                Toast.makeText(requireContext(), "Contact information unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (com.msi.openchat.service.call.CallSessionCoordinator.isActive()) {
                Toast.makeText(requireContext(), "Cannot start video call while voice call is active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (com.msi.openchat.feature.videocall.VideoCallManager.isCallActive()) {
                Toast.makeText(requireContext(), "A video call is already active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.msi.openchat.feature.videocall.OutgoingVideoCallActivity.start(
                context = requireContext(),
                peerId = contactId,
                peerName = contactName.ifBlank { "Contact" }
            )
        }

        binding.btnChatCall.setOnClickListener {
            if (contactId.isBlank()) {
                Toast.makeText(requireContext(), "Contact information unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (com.msi.openchat.feature.videocall.VideoCallManager.isCallActive()) {
                Toast.makeText(requireContext(), "Cannot start voice call while video call is active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (com.msi.openchat.service.call.CallSessionCoordinator.isActive()) {
                Toast.makeText(requireContext(), "A voice call is already active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hasMicPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                com.msi.openchat.feature.calls.OutgoingAudioCallActivity.start(
                    context = requireContext(),
                    contactId = contactId,
                    contactName = contactName.ifBlank { "Contact" }
                )
            } else {
                micCallLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnChatMore.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menu.add(0, 1, 0, "View Profile")
            popup.menu.add(0, 2, 1, "Search in Chat")
            popup.menu.add(0, 3, 2, "Media, Links & Docs")
            popup.menu.add(0, 4, 3, "Mute Notifications")
            popup.menu.add(0, 5, 4, "Clear Chat")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        (activity as? MainActivity)?.navigateToProfile(contactId)
                        true
                    }
                    2 -> {
                        Toast.makeText(requireContext(), "Search in conversation", Toast.LENGTH_SHORT).show()
                        true
                    }
                    3 -> {
                        Toast.makeText(requireContext(), "Media & documents stored in chat", Toast.LENGTH_SHORT).show()
                        true
                    }
                    4 -> {
                        Toast.makeText(requireContext(), "Notifications muted", Toast.LENGTH_SHORT).show()
                        true
                    }
                    5 -> {
                        Toast.makeText(requireContext(), "Chat history cleared", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
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
                showMediaPreview(message, resolvedUrl)
            },
            onAttachmentClick = { message ->
                openAttachmentWithCache(message)
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
                binding.btnChatSendOrMic.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.color_composer_action_icon)
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnChatSendOrMic.setColorFilter(
            ContextCompat.getColor(requireContext(), R.color.color_composer_action_icon)
        )

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
            popup.menu.add(0, 3, 2, "Audio")
            popup.menu.add(0, 4, 3, "Document")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        checkAndRequestMediaPermission(perm) {
                            photoPickerLauncher.launch(arrayOf("image/*"))
                        }
                    }
                    2 -> {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_VIDEO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        checkAndRequestMediaPermission(perm) {
                            videoPickerLauncher.launch(arrayOf("video/*"))
                        }
                    }
                    3 -> {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        checkAndRequestMediaPermission(perm) {
                            audioPickerLauncher.launch(arrayOf("audio/*"))
                        }
                    }
                    4 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        } else {
                            checkAndRequestMediaPermission(Manifest.permission.READ_EXTERNAL_STORAGE) {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        }
                    }
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

    private var activePreviewJob: kotlinx.coroutines.Job? = null
    private var activePreviewDownloadFile: java.io.File? = null
    private var activePreviewSourceUri: Uri? = null
    private var activePreviewMessage: Message? = null

    private fun setupPreviewOverlay() {
        binding.btnClosePreview.setOnClickListener {
            closePreviewOverlay()
        }
        binding.layoutFullscreenPreview.setOnClickListener {
            closePreviewOverlay()
        }
        binding.btnDownloadPreview.setOnClickListener {
            handleDownloadAction()
        }
    }

    private fun closePreviewOverlay() {
        activePreviewJob?.cancel()
        activePreviewJob = null
        try { binding.videoPreviewPlayer.stopPlayback() } catch (_: Exception) {}
        binding.layoutFullscreenPreview.visibility = View.GONE
        binding.imgPreviewPhoto.visibility = View.GONE
        binding.videoPreviewPlayer.visibility = View.GONE
        binding.progressPreviewLoading.visibility = View.GONE
        binding.textPreviewError.visibility = View.GONE
        activePreviewDownloadFile = null
        activePreviewSourceUri = null
        activePreviewMessage = null
    }

    private fun handleDownloadAction() {
        val msg = activePreviewMessage ?: return
        val context = requireContext()

        val sourceFile = activePreviewDownloadFile
        if (sourceFile != null && sourceFile.exists()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = com.msi.openchat.core.media.MediaDownloadManager.saveToExternalStorage(
                    context = context,
                    sourceFile = sourceFile,
                    displayName = msg.content,
                    type = msg.type
                )
                if (result.isSuccess) {
                    val subfolder = com.msi.openchat.core.media.MediaDownloadManager.getCacheSubfolder(msg.type).replaceFirstChar { it.uppercase() }
                    Toast.makeText(context, "Saved to OpenChat/$subfolder", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val sourceUri = activePreviewSourceUri
        if (sourceUri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val tempFile = com.msi.openchat.core.media.MediaCompressionManager.copyUriToTempFile(
                    context = context,
                    uriString = sourceUri.toString(),
                    mimeType = when (msg.type) {
                        "video" -> "video/mp4"
                        "audio", "voice" -> "audio/mp4"
                        "file" -> "application/octet-stream"
                        else -> "image/jpeg"
                    }
                )
                if (tempFile != null && tempFile.exists()) {
                    val result = com.msi.openchat.core.media.MediaDownloadManager.saveToExternalStorage(
                        context = context,
                        sourceFile = tempFile,
                        displayName = msg.content,
                        type = msg.type
                    )
                    tempFile.delete()
                    if (result.isSuccess) {
                        val subfolder = com.msi.openchat.core.media.MediaDownloadManager.getCacheSubfolder(msg.type).replaceFirstChar { it.uppercase() }
                        Toast.makeText(context, "Saved to OpenChat/$subfolder", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Cannot export file", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        Toast.makeText(context, "Media is still loading, please wait...", Toast.LENGTH_SHORT).show()
    }

    private fun showMediaPreview(message: Message, resolvedUrl: String?) {
        activePreviewJob?.cancel()
        activePreviewMessage = message
        activePreviewDownloadFile = null
        activePreviewSourceUri = null

        binding.layoutFullscreenPreview.visibility = View.VISIBLE
        val isVideo = message.type == "video"
        val titleText = message.content?.takeIf { it.isNotBlank() && !it.startsWith("📷") && !it.startsWith("📹") }
            ?: if (isVideo) "Video Preview" else "Photo Preview"
        binding.textPreviewTitle.text = titleText

        // Reset views
        binding.imgPreviewPhoto.visibility = View.GONE
        binding.videoPreviewPlayer.visibility = View.GONE
        binding.textPreviewError.visibility = View.GONE
        binding.progressPreviewLoading.visibility = View.VISIBLE

        val context = requireContext()

        // 1. Check sender local content:// URI (zero copy)
        val localPath = message.localFilePath
        if (!localPath.isNullOrBlank() && localPath.startsWith("content://")) {
            val uri = Uri.parse(localPath)
            if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(context, uri)) {
                activePreviewSourceUri = uri
                renderPreviewFromUri(uri, isVideo)
                return
            }
        }

        // 2. Check receiver cache lookup
        val cached = com.msi.openchat.core.media.MediaDownloadManager.getCachedFile(
            context = context,
            messageId = message.id,
            remoteUrl = message.mediaUrl ?: resolvedUrl,
            type = message.type,
            originalName = message.content
        )
        if (cached != null && cached.exists() && cached.length() > 0) {
            activePreviewDownloadFile = cached
            renderPreviewFromFile(cached, isVideo)
            return
        }

        // 3. Fallback: Download to cache first, then render from cache
        val effectiveUrl = message.mediaUrl?.takeIf { it.isNotBlank() } ?: resolvedUrl
        if (effectiveUrl.isNullOrBlank()) {
            binding.progressPreviewLoading.visibility = View.GONE
            binding.textPreviewError.visibility = View.VISIBLE
            binding.textPreviewError.text = "Media file is unavailable"
            return
        }

        activePreviewJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = com.msi.openchat.core.media.MediaDownloadManager.downloadToCache(
                context = context,
                messageId = message.id,
                remoteUrl = effectiveUrl,
                type = message.type,
                originalName = message.content
            )
            val file = result.getOrNull()
            if (file != null && file.exists()) {
                activePreviewDownloadFile = file
                renderPreviewFromFile(file, isVideo)
            } else {
                binding.progressPreviewLoading.visibility = View.GONE
                binding.textPreviewError.visibility = View.VISIBLE
                binding.textPreviewError.text = "Failed to load media: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            }
        }
    }

    private fun renderPreviewFromUri(uri: Uri, isVideo: Boolean) {
        binding.progressPreviewLoading.visibility = View.GONE
        if (isVideo) {
            binding.videoPreviewPlayer.visibility = View.VISIBLE
            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(binding.videoPreviewPlayer)
            binding.videoPreviewPlayer.setMediaController(mediaController)
            binding.videoPreviewPlayer.setVideoURI(uri)
            binding.videoPreviewPlayer.setOnPreparedListener { mp ->
                mp.isLooping = true
                binding.videoPreviewPlayer.start()
            }
        } else {
            binding.imgPreviewPhoto.visibility = View.VISIBLE
            binding.imgPreviewPhoto.load(uri) {
                crossfade(true)
            }
        }
    }

    private fun renderPreviewFromFile(file: java.io.File, isVideo: Boolean) {
        binding.progressPreviewLoading.visibility = View.GONE
        if (isVideo) {
            binding.videoPreviewPlayer.visibility = View.VISIBLE
            val mediaController = MediaController(requireContext())
            mediaController.setAnchorView(binding.videoPreviewPlayer)
            binding.videoPreviewPlayer.setMediaController(mediaController)
            binding.videoPreviewPlayer.setVideoURI(Uri.fromFile(file))
            binding.videoPreviewPlayer.setOnPreparedListener { mp ->
                mp.isLooping = true
                binding.videoPreviewPlayer.start()
            }
        } else {
            binding.imgPreviewPhoto.visibility = View.VISIBLE
            binding.imgPreviewPhoto.load(file) {
                crossfade(true)
            }
        }
    }

    private fun openAttachmentWithCache(message: Message) {
        val context = requireContext()
        val localPath = message.localFilePath
        if (!localPath.isNullOrBlank() && localPath.startsWith("content://")) {
            val uri = Uri.parse(localPath)
            if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(context, uri)) {
                com.msi.openchat.core.media.PersistableUriManager.openDocument(
                    context = context,
                    uri = uri,
                    mimeType = null,
                    displayName = message.content.orEmpty()
                )
                return
            }
        }

        val cached = com.msi.openchat.core.media.MediaDownloadManager.getCachedFile(
            context = context,
            messageId = message.id,
            remoteUrl = message.mediaUrl,
            type = "file",
            originalName = message.content
        )
        if (cached != null && cached.exists() && cached.length() > 0) {
            openLocalFile(context, cached, message.content.orEmpty())
            return
        }

        val remoteUrl = message.mediaUrl
        if (remoteUrl.isNullOrBlank()) {
            Toast.makeText(context, "Attachment file is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Downloading attachment...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = com.msi.openchat.core.media.MediaDownloadManager.downloadToCache(
                context = context,
                messageId = message.id,
                remoteUrl = remoteUrl,
                type = "file",
                originalName = message.content
            )
            val file = result.getOrNull()
            if (file != null && file.exists()) {
                openLocalFile(context, file, message.content.orEmpty())
            } else {
                Toast.makeText(context, "Failed to download attachment: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLocalFile(context: Context, file: java.io.File, displayName: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
        com.msi.openchat.core.media.PersistableUriManager.openDocument(
            context = context,
            uri = uri,
            mimeType = null,
            displayName = displayName
        )
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
        com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
            imageView = binding.imgChatAvatar,
            letterTextView = binding.textChatAvatar,
            avatarUrl = state.contactProfile?.avatarUrl ?: state.contactProfile?.avatar,
            displayName = state.contactName
        )

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
            binding.textChatContactStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_toolbar_subtitle))
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
        private const val ARG_AUTO_SEND_HI = "auto_send_hi"

        fun newInstance(
            conversationId: String,
            contactName: String,
            contactId: String,
            autoSendHi: Boolean = false
        ): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONVERSATION_ID, conversationId)
                    putString(ARG_CONTACT_NAME, contactName)
                    putString(ARG_CONTACT_ID, contactId)
                    putBoolean(ARG_AUTO_SEND_HI, autoSendHi)
                }
            }
        }
    }
}
