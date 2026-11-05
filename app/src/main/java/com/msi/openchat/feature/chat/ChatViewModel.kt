package com.msi.openchat.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.Message
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.repository.ChatRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.feature.voice.AudioPlayerHelper
import com.msi.openchat.feature.voice.AudioRecorderHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.msi.openchat.core.security.SessionManager

import android.net.Uri
import android.provider.OpenableColumns
import com.msi.openchat.core.common.NetworkMonitor
import com.msi.openchat.core.common.NetworkStatus
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ChatUiState(
    val conversationId: String = "",
    val contactName: String = "",
    val contactId: String = "",
    val contactProfile: Profile? = null,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isUploadingMedia: Boolean = false,
    val isContactTyping: Boolean = false,
    val errorMessage: String? = null,
    val isRecordingVoice: Boolean = false,
    val recordingDurationSeconds: Int = 0,
    val currentlyPlayingMessageId: String? = null,
    val playbackProgress: Float = 0f,
    val networkStatus: NetworkStatus = NetworkStatus()
)

class ChatViewModel(
    val conversationId: String,
    val contactName: String,
    val contactId: String,
    private val chatRepository: ChatRepository = ServiceLocator.chatRepository,
    private val userRepository: UserRepository = ServiceLocator.userRepository,
    private val sessionManager: SessionManager = ServiceLocator.sessionManager,
    private val audioRecorderHelper: AudioRecorderHelper = ServiceLocator.audioRecorderHelper,
    private val audioPlayerHelper: AudioPlayerHelper = ServiceLocator.audioPlayerHelper,
    private val networkMonitor: NetworkMonitor = ServiceLocator.networkMonitor
) : ViewModel() {

    private var typingStopJob: Job? = null
    private var contactTypingTimeoutJob: Job? = null
    private var lastTypingSentTime: Long = 0L

    private val _uiState = MutableStateFlow(
        ChatUiState(
            conversationId = conversationId,
            contactName = contactName,
            contactId = contactId
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeContactProfile()
        observeLocalMessages()
        loadMessages()
        markMessagesAsRead()
        observeRealtimeMessages()
        observeTypingIndicators()
        observeAudioState()
        observeNetwork()
    }

    fun markMessagesAsRead() {
        viewModelScope.launch {
            chatRepository.markMessagesAsRead(conversationId)
        }
    }

    private fun observeLocalMessages() {
        viewModelScope.launch {
            chatRepository.observeLocalMessages(conversationId).collect { localList ->
                if (localList.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        messages = localList,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.status.collect { status ->
                _uiState.value = _uiState.value.copy(networkStatus = status)
            }
        }
    }

    private fun observeContactProfile() {
        viewModelScope.launch {
            userRepository.observeUserProfile(contactId).collect { profile ->
                if (profile != null) {
                    _uiState.value = _uiState.value.copy(
                        contactProfile = profile,
                        contactName = profile.displayName.ifBlank { profile.username ?: "User" }
                    )
                }
            }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val res = chatRepository.getMessages(conversationId)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        messages = res.data,
                        isLoading = false
                    )
                    val currentUid = sessionManager.getUserId()
                    val hasUnreadIncoming = res.data.any { it.senderId != currentUid && it.status != "read" }
                    if (hasUnreadIncoming) {
                        markMessagesAsRead()
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    private fun observeRealtimeMessages() {
        viewModelScope.launch {
            chatRepository.observeRealtimeMessages(conversationId).collect { msg ->
                val currentUid = sessionManager.getUserId()
                if (msg.senderId != currentUid && msg.status != "read") {
                    markMessagesAsRead()
                }

                val currentList = _uiState.value.messages
                val index = currentList.indexOfFirst { it.id == msg.id }
                val localPath = if (index != -1) {
                    currentList[index].localFilePath ?: msg.localFilePath
                } else {
                    msg.localFilePath
                }
                val msgWithLocal = msg.copy(localFilePath = localPath)
                val updated = if (index != -1) {
                    val list = currentList.toMutableList()
                    list[index] = msgWithLocal
                    list
                } else {
                    currentList + msgWithLocal
                }
                _uiState.value = _uiState.value.copy(messages = updated)
            }
        }
    }

    private fun observeTypingIndicators() {
        viewModelScope.launch {
            chatRepository.observeTypingStatus(conversationId, contactId).collect { isTyping ->
                _uiState.value = _uiState.value.copy(isContactTyping = isTyping)
                if (isTyping) {
                    contactTypingTimeoutJob?.cancel()
                    contactTypingTimeoutJob = viewModelScope.launch {
                        delay(4000)
                        _uiState.value = _uiState.value.copy(isContactTyping = false)
                    }
                }
            }
        }
    }

    private fun observeAudioState() {
        viewModelScope.launch {
            audioRecorderHelper.isRecording.collect { rec ->
                _uiState.value = _uiState.value.copy(isRecordingVoice = rec)
            }
        }
        viewModelScope.launch {
            audioRecorderHelper.recordingDurationSeconds.collect { sec ->
                _uiState.value = _uiState.value.copy(recordingDurationSeconds = sec)
            }
        }
        viewModelScope.launch {
            audioPlayerHelper.currentlyPlayingId.collect { id ->
                _uiState.value = _uiState.value.copy(currentlyPlayingMessageId = id)
            }
        }
        viewModelScope.launch {
            audioPlayerHelper.playbackProgress.collect { prog ->
                _uiState.value = _uiState.value.copy(playbackProgress = prog)
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
        if (text.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastTypingSentTime > 2000L) {
                lastTypingSentTime = now
                chatRepository.setTypingStatus(conversationId, true)
            }
            typingStopJob?.cancel()
            typingStopJob = viewModelScope.launch {
                delay(3000)
                chatRepository.setTypingStatus(conversationId, false)
            }
        } else {
            typingStopJob?.cancel()
            chatRepository.setTypingStatus(conversationId, false)
        }
    }

    fun sendTextMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        typingStopJob?.cancel()
        chatRepository.setTypingStatus(conversationId, false)

        _uiState.value = _uiState.value.copy(inputText = "", isSending = true)
        viewModelScope.launch {
            when (val res = chatRepository.sendTextMessage(conversationId, text)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + res.data,
                        isSending = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    fun startVoiceRecording() {
        audioRecorderHelper.startRecording()
    }

    fun cancelVoiceRecording() {
        audioRecorderHelper.stopRecording(discard = true)
    }

    private fun compressImageFileIfNeeded(file: File) {
        try {
            if (!file.exists() || file.length() < 300 * 1024) return
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDim = 1600
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        } catch (_: Throwable) {}
    }

    fun finishAndSendVoiceRecording() {
        val duration = _uiState.value.recordingDurationSeconds
        val audioFile = audioRecorderHelper.stopRecording(discard = false) ?: return

        if (duration < 1) {
            audioFile.delete()
            return
        }

        // Cache permanently in offline media directory before sending
        val persistentAudio = com.msi.openchat.data.local.OfflineMediaManager.saveMediaLocally(
            ServiceLocator.context,
            audioFile,
            "${System.currentTimeMillis()}_voice.m4a"
        )
        audioFile.delete()

        _uiState.value = _uiState.value.copy(isSending = true)
        viewModelScope.launch {
            when (val res = chatRepository.sendVoiceMessage(conversationId, persistentAudio, duration)) {
                is Resource.Success -> {
                    val messageWithLocal = res.data.copy(localFilePath = persistentAudio.absolutePath)
                    ServiceLocator.messageDao?.updateLocalFilePath(res.data.id, persistentAudio.absolutePath)
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + messageWithLocal,
                        isSending = false
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        errorMessage = res.message
                    )
                }
                else -> Unit
            }
        }
    }

    fun playVoiceMessage(message: Message) {
        if (_uiState.value.currentlyPlayingMessageId == message.id) {
            audioPlayerHelper.stopAudio()
            return
        }

        val localPath = message.localFilePath
        if (!localPath.isNullOrBlank()) {
            if (localPath.startsWith("content://")) {
                val uri = Uri.parse(localPath)
                if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(ServiceLocator.context, uri)) {
                    audioPlayerHelper.playAudio(message.id, localPath)
                    return
                } else {
                    _uiState.value = _uiState.value.copy(errorMessage = "Audio file is no longer accessible")
                    return
                }
            } else {
                val localFile = File(localPath)
                if (localFile.exists() && localFile.length() > 0) {
                    audioPlayerHelper.playAudio(message.id, localFile.absolutePath)
                    return
                }
            }
        }

        // Cache lookup: check if audio is already cached locally
        val cached = com.msi.openchat.core.media.MediaDownloadManager.getCachedFile(
            context = ServiceLocator.context,
            messageId = message.id,
            remoteUrl = message.mediaUrl,
            type = "audio",
            originalName = message.content
        )
        if (cached != null && cached.exists() && cached.length() > 0) {
            audioPlayerHelper.playAudio(message.id, cached.absolutePath)
            return
        }

        val storagePath = message.mediaUrl ?: return
        viewModelScope.launch {
            // Download to cache first, then play strictly from cache
            val downloadRes = com.msi.openchat.core.media.MediaDownloadManager.downloadToCache(
                context = ServiceLocator.context,
                messageId = message.id,
                remoteUrl = storagePath,
                type = "audio",
                originalName = message.content
            )
            val downloadedFile = downloadRes.getOrNull()
            if (downloadedFile != null && downloadedFile.exists()) {
                audioPlayerHelper.playAudio(message.id, downloadedFile.absolutePath)
            } else if (storagePath.startsWith("data:") || storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
                audioPlayerHelper.playAudio(message.id, storagePath)
            } else {
                when (val res = chatRepository.getAudioPlayableUrl(storagePath)) {
                    is Resource.Success -> {
                        audioPlayerHelper.playAudio(message.id, res.data)
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(errorMessage = res.message)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun sendImageUri(context: Context, uri: Uri) {
        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                val meta = com.msi.openchat.core.media.PersistableUriManager.extractMetadata(context, uri)
                when (val res = chatRepository.sendMediaFromUri(
                    context = context,
                    conversationId = conversationId,
                    uri = uri,
                    fileName = meta.displayName,
                    mimeType = meta.mimeType.ifBlank { "image/jpeg" },
                    type = "image",
                    fileSize = meta.size
                )) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + res.data,
                            isUploadingMedia = false
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingMedia = false,
                            errorMessage = res.message
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(isUploadingMedia = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingMedia = false,
                    errorMessage = e.message ?: "Failed to upload image"
                )
            }
        }
    }

    fun sendVideoUri(context: Context, uri: Uri) {
        val meta = com.msi.openchat.core.media.PersistableUriManager.extractMetadata(context, uri)
        val maxVideoSize = 100 * 1024 * 1024L // 100 MB limit
        if (meta.size > maxVideoSize) {
            val sizeMb = meta.size / (1024.0 * 1024.0)
            _uiState.value = _uiState.value.copy(
                errorMessage = "Video size exceeds 100 MB limit (Selected: %.1f MB). Please choose a video up to 100 MB.".format(sizeMb)
            )
            return
        }

        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                when (val res = chatRepository.sendMediaFromUri(
                    context = context,
                    conversationId = conversationId,
                    uri = uri,
                    fileName = meta.displayName,
                    mimeType = meta.mimeType.ifBlank { "video/mp4" },
                    type = "video",
                    fileSize = meta.size,
                    durationSeconds = meta.durationSeconds
                )) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + res.data,
                            isUploadingMedia = false
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingMedia = false,
                            errorMessage = res.message
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(isUploadingMedia = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingMedia = false,
                    errorMessage = e.message ?: "Failed to upload video"
                )
            }
        }
    }

    fun sendAudioUri(context: Context, uri: Uri) {
        val meta = com.msi.openchat.core.media.PersistableUriManager.extractMetadata(context, uri)
        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                when (val res = chatRepository.sendMediaFromUri(
                    context = context,
                    conversationId = conversationId,
                    uri = uri,
                    fileName = meta.displayName,
                    mimeType = meta.mimeType.ifBlank { "audio/mpeg" },
                    type = "audio",
                    fileSize = meta.size,
                    durationSeconds = meta.durationSeconds
                )) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + res.data,
                            isUploadingMedia = false
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingMedia = false,
                            errorMessage = res.message
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(isUploadingMedia = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingMedia = false,
                    errorMessage = e.message ?: "Failed to upload audio"
                )
            }
        }
    }

    fun sendFileUri(context: Context, uri: Uri) {
        val meta = com.msi.openchat.core.media.PersistableUriManager.extractMetadata(context, uri)
        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                when (val res = chatRepository.sendMediaFromUri(
                    context = context,
                    conversationId = conversationId,
                    uri = uri,
                    fileName = meta.displayName,
                    mimeType = meta.mimeType.ifBlank { "application/octet-stream" },
                    type = "file",
                    fileSize = meta.size
                )) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + res.data,
                            isUploadingMedia = false
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isUploadingMedia = false,
                            errorMessage = res.message
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(isUploadingMedia = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingMedia = false,
                    errorMessage = e.message ?: "Failed to upload file"
                )
            }
        }
    }

    fun sendImage(context: Context, uri: Uri) {
        sendImageUri(context, uri)
    }

    fun sendVideo(context: Context, uri: Uri) {
        sendVideoUri(context, uri)
    }

    fun sendFile(context: Context, uri: Uri) {
        sendFileUri(context, uri)
    }

    fun resolveMediaUrl(mediaUrl: String, onResolved: (String) -> Unit) {
        val matchingMessage = _uiState.value.messages.firstOrNull { it.mediaUrl == mediaUrl || it.id == mediaUrl }
        val localPath = matchingMessage?.localFilePath
        if (!localPath.isNullOrBlank()) {
            if (localPath.startsWith("content://")) {
                val uri = Uri.parse(localPath)
                if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(ServiceLocator.context, uri)) {
                    onResolved(localPath)
                    return
                }
            } else {
                val localFile = File(localPath)
                if (localFile.exists() && localFile.length() > 0) {
                    onResolved("file://${localFile.absolutePath}")
                    return
                }
            }
        }

        if (mediaUrl.startsWith("file://") || mediaUrl.startsWith("content://")) {
            onResolved(mediaUrl)
            return
        }

        if (mediaUrl.startsWith("data:") || mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
            onResolved(mediaUrl)
            return
        }

        viewModelScope.launch {
            when (val res = chatRepository.getMediaPlayableUrl(mediaUrl)) {
                is Resource.Success -> {
                    onResolved(res.data)
                }
                else -> Unit
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        contactTypingTimeoutJob?.cancel()
        typingStopJob?.cancel()
        val currentUid = sessionManager.getUserId()
        if (!currentUid.isNullOrBlank()) {
            chatRepository.setTypingStatus(conversationId, false)
        }
        audioPlayerHelper.stopAudio()
    }
}
