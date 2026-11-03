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
                    localList.forEach { msg ->
                        if (msg.localFilePath.isNullOrBlank() && msg.type in listOf("image", "video", "audio", "voice", "file")) {
                            com.msi.openchat.data.local.OfflineMediaManager.cacheMessageMediaProactively(
                                ServiceLocator.context,
                                msg,
                                ServiceLocator.messageDao
                            )
                        }
                    }
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

                // Proactively cache media locally for offline access
                if (msg.type in listOf("image", "video", "audio", "voice", "file")) {
                    com.msi.openchat.data.local.OfflineMediaManager.cacheMessageMediaProactively(
                        ServiceLocator.context,
                        msg,
                        ServiceLocator.messageDao
                    )
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

        // 1. Check local file or offline cache first for instant playback
        val localFile = com.msi.openchat.data.local.OfflineMediaManager.resolveMessageMediaFile(ServiceLocator.context, message)
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            audioPlayerHelper.playAudio(message.id, localFile.absolutePath)
            return
        }

        val storagePath = message.mediaUrl ?: return
        if (storagePath.startsWith("data:")) {
            val cachedFile = com.msi.openchat.data.local.OfflineMediaManager.saveBase64Locally(
                ServiceLocator.context,
                storagePath,
                "${message.id}_voice.m4a"
            )
            if (cachedFile != null && cachedFile.exists()) {
                audioPlayerHelper.playAudio(message.id, cachedFile.absolutePath)
                return
            }
        }

        viewModelScope.launch {
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

    private fun getMediaSize(context: Context, uri: Uri): Long {
        var size: Long = -1
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}
        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }
        return size
    }

    fun sendImage(context: Context, uri: Uri) {
        // No size restrictions on photos
        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot read selected image")

                val preGeneratedId = UUID.randomUUID().toString()
                val persistentImage = com.msi.openchat.data.local.OfflineMediaManager.saveInputStreamLocally(
                    context,
                    inputStream,
                    "${preGeneratedId}_photo.jpg"
                )

                compressImageFileIfNeeded(persistentImage)

                when (val res = chatRepository.sendImageMessage(conversationId, persistentImage, mimeType)) {
                    is Resource.Success -> {
                        val messageWithLocal = res.data.copy(localFilePath = persistentImage.absolutePath)
                        ServiceLocator.messageDao?.updateLocalFilePath(res.data.id, persistentImage.absolutePath)
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + messageWithLocal,
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

    fun sendVideo(context: Context, uri: Uri) {
        val size = getMediaSize(context, uri)
        val maxVideoSize = 100 * 1024 * 1024L // 100 MB limit
        if (size > maxVideoSize) {
            val sizeMb = size / (1024.0 * 1024.0)
            _uiState.value = _uiState.value.copy(
                errorMessage = "Video size exceeds 100 MB limit (Selected: %.1f MB). Please choose a video up to 100 MB.".format(sizeMb)
            )
            return
        }

        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot read selected video")

                val preGeneratedId = UUID.randomUUID().toString()
                val persistentVideo = com.msi.openchat.data.local.OfflineMediaManager.saveInputStreamLocally(
                    context,
                    inputStream,
                    "${preGeneratedId}_video.mp4"
                )

                when (val res = chatRepository.sendVideoMessage(conversationId, persistentVideo, mimeType)) {
                    is Resource.Success -> {
                        val messageWithLocal = res.data.copy(localFilePath = persistentVideo.absolutePath)
                        ServiceLocator.messageDao?.updateLocalFilePath(res.data.id, persistentVideo.absolutePath)
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + messageWithLocal,
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

    fun sendFile(context: Context, uri: Uri) {
        _uiState.value = _uiState.value.copy(isUploadingMedia = true)
        viewModelScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                var fileName = "Document_${System.currentTimeMillis()}"

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot read selected file")

                val preGeneratedId = UUID.randomUUID().toString()
                val persistentFile = com.msi.openchat.data.local.OfflineMediaManager.saveInputStreamLocally(
                    context,
                    inputStream,
                    "${preGeneratedId}_$fileName"
                )

                when (val res = chatRepository.sendFileMessage(conversationId, persistentFile, fileName, mimeType)) {
                    is Resource.Success -> {
                        val messageWithLocal = res.data.copy(localFilePath = persistentFile.absolutePath)
                        ServiceLocator.messageDao?.updateLocalFilePath(res.data.id, persistentFile.absolutePath)
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + messageWithLocal,
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

    fun resolveMediaUrl(mediaUrl: String, onResolved: (String) -> Unit) {
        // 1. Check if media is available locally on device for offline playback/viewing
        val matchingMessage = _uiState.value.messages.firstOrNull { it.mediaUrl == mediaUrl || it.id == mediaUrl }
        val localFile = com.msi.openchat.data.local.OfflineMediaManager.getLocalFileIfAvailable(matchingMessage?.localFilePath)
        if (localFile != null) {
            onResolved("file://${localFile.absolutePath}")
            return
        }

        if (mediaUrl.startsWith("file://") || mediaUrl.startsWith("content://")) {
            onResolved(mediaUrl)
            return
        }

        if (mediaUrl.startsWith("data:") || mediaUrl.startsWith("http")) {
            onResolved(mediaUrl)
            if (matchingMessage != null) {
                val ext = when (matchingMessage.type) {
                    "image" -> "jpg"
                    "video" -> "mp4"
                    "audio", "voice" -> "m4a"
                    else -> "bin"
                }
                com.msi.openchat.data.local.OfflineMediaManager.cacheRemoteMediaAsync(
                    context = ServiceLocator.context,
                    messageId = matchingMessage.id,
                    remoteUrl = mediaUrl,
                    preferredFileName = "offline_${matchingMessage.id}.$ext",
                    messageDao = ServiceLocator.messageDao
                )
            }
            return
        }

        viewModelScope.launch {
            when (val res = chatRepository.getMediaPlayableUrl(mediaUrl)) {
                is Resource.Success -> {
                    onResolved(res.data)
                    if (matchingMessage != null) {
                        val ext = when (matchingMessage.type) {
                            "image" -> "jpg"
                            "video" -> "mp4"
                            "audio", "voice" -> "m4a"
                            else -> "bin"
                        }
                        com.msi.openchat.data.local.OfflineMediaManager.cacheRemoteMediaAsync(
                            context = ServiceLocator.context,
                            messageId = matchingMessage.id,
                            remoteUrl = res.data,
                            preferredFileName = "offline_${matchingMessage.id}.$ext",
                            messageDao = ServiceLocator.messageDao
                        )
                    }
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
