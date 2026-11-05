package com.msi.openchat.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.StorageReference
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.local.dao.ConversationDao
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.local.entity.ConversationEntity
import com.msi.openchat.data.local.entity.MessageEntity
import com.msi.openchat.data.model.Conversation
import com.msi.openchat.data.model.Message
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * ChatRepository
 * Realtime 1-to-1 messaging, media uploads (images, voice notes, video, documents),
 * conversation indexing, typing indicators, and message status tracking via Firebase.
 */
class ChatRepository(
    private val sessionManager: SessionManager,
    private val userRepository: UserRepository,
    private val messageDao: MessageDao? = null,
    private val conversationDao: ConversationDao? = null,
    private val storageClient: com.msi.openchat.data.remote.storage.NhostStorageClient = com.msi.openchat.data.remote.storage.NhostStorageClient()
) {
    private val tag = "OpenChat_ChatRepo"
    private val conversationsRef get() = FirebaseManager.conversationsRef
    private val messagesRef get() = FirebaseManager.messagesRef
    private val typingRef get() = FirebaseManager.typingRef

    private fun getCurrentIsoTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        return sdf.format(Date())
    }

    private suspend fun getSingleSnapshot(query: com.google.firebase.database.Query): DataSnapshot? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (continuation.isActive) continuation.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            query.addListenerForSingleValueEvent(listener)
            continuation.invokeOnCancellation {
                query.removeEventListener(listener)
            }
        }

    private suspend fun Task<*>.awaitTask(): Boolean =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(task.isSuccessful)
                }
            }
        }

    fun observeLocalMessages(conversationId: String): Flow<List<Message>> {
        return messageDao?.getMessagesForConversation(conversationId)?.map { list ->
            list.map { it.toDomain() }
        } ?: emptyFlow()
    }

    fun observeLocalConversations(): Flow<List<Conversation>> {
        return conversationDao?.getAllConversations()?.map { list ->
            list.map { it.toDomain() }
        } ?: emptyFlow()
    }

    suspend fun getConversations(): Resource<List<Conversation>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val snapshot = getSingleSnapshot(conversationsRef.child(currentUserId))
                ?: return@withContext Resource.Success(emptyList())

            val rawList = mutableListOf<Conversation>()
            val otherUserIds = mutableListOf<String>()

            for (child in snapshot.children) {
                val otherUserId = child.child("other_user_id").getValue(String::class.java)
                    ?: child.key.orEmpty()
                val convId = child.child("id").getValue(String::class.java)
                    ?: "direct_${currentUserId}_${otherUserId}"
                val p1 = child.child("participant1_id").getValue(String::class.java) ?: currentUserId
                val p2 = child.child("participant2_id").getValue(String::class.java) ?: otherUserId
                val lastText = child.child("last_message_text").getValue(String::class.java)
                val lastAt = child.child("last_message_at").getValue(String::class.java)
                val lastSender = child.child("last_message_sender_id").getValue(String::class.java)
                val unread = child.child("unread_count").getValue(Int::class.java) ?: 0

                rawList.add(
                    Conversation(
                        id = convId,
                        type = "direct",
                        participant1Id = p1,
                        participant2Id = p2,
                        lastMessageText = lastText,
                        lastMessageAt = lastAt,
                        lastMessageSenderId = lastSender,
                        unreadCount = unread
                    )
                )
                otherUserIds.add(otherUserId)
            }

            val profiles = userRepository.getUserProfiles(otherUserIds)
            val populated = rawList.map { conv ->
                val otherId = if (conv.participant1Id == currentUserId) conv.participant2Id else conv.participant1Id
                conv.copy(otherUser = profiles[otherId])
            }

            // Cache to Room
            try {
                val entities = populated.map { ConversationEntity.fromDomain(it, currentUserId) }
                conversationDao?.insertConversations(entities)
            } catch (e: Exception) {
                Log.w(tag, "Room caching notice: ${e.message}")
            }

            Resource.Success(populated)
        } catch (e: Exception) {
            Log.e(tag, "getConversations error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to get conversations", e)
        }
    }

    suspend fun getOrCreateConversation(otherUserId: String): Resource<Conversation> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        val convId = if (currentUserId < otherUserId) "direct_${currentUserId}_${otherUserId}" else "direct_${otherUserId}_${currentUserId}"
        val now = getCurrentIsoTime()

        val convData = HashMap<String, Any?>()
        convData["id"] = convId
        convData["type"] = "direct"
        convData["participant1_id"] = currentUserId
        convData["participant2_id"] = otherUserId
        convData["other_user_id"] = otherUserId
        convData["last_message_at"] = now
        convData["unread_count"] = 0

        conversationsRef.child(currentUserId).child(otherUserId).updateChildren(convData)

        val otherProfile = when (val p = userRepository.getUserProfile(otherUserId)) {
            is Resource.Success -> p.data
            else -> null
        }

        Resource.Success(
            Conversation(
                id = convId,
                type = "direct",
                participant1Id = currentUserId,
                participant2Id = otherUserId,
                otherUser = otherProfile
            )
        )
    }

    suspend fun getMessages(conversationId: String, limit: Int = 100): Resource<List<Message>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = getSingleSnapshot(messagesRef.child(conversationId).limitToLast(limit))
                ?: return@withContext Resource.Success(emptyList())

            val messages = mutableListOf<Message>()
            for (child in snapshot.children) {
                val msg = parseMessageSnapshot(child, conversationId)
                messages.add(msg)
            }

            // Cache to Room
            try {
                val entities = messages.map { MessageEntity.fromDomain(it) }
                messageDao?.insertMessages(entities)
            } catch (_: Exception) {}

            Resource.Success(messages)
        } catch (e: Exception) {
            Log.e(tag, "getMessages error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to load messages", e)
        }
    }

    fun observeRealtimeMessages(conversationId: String): Flow<Message> {
        return com.msi.openchat.core.backend.BackendManager.subscribeConversation(conversationId)
    }

    suspend fun sendTextMessage(
        conversationId: String,
        content: String,
        recipientId: String? = null
    ): Resource<Message> = withContext(Dispatchers.IO) {
        try {
            val entity = com.msi.openchat.core.backend.BackendManager.sendMessage(
                conversationId = conversationId,
                type = "text",
                content = content,
                recipientId = recipientId
            )
            Resource.Success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(tag, "sendTextMessage error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to send message", e)
        }
    }

    suspend fun sendMediaMessage(
        conversationId: String,
        type: String,
        mediaUrl: String,
        content: String? = null,
        mediaDurationSeconds: Int? = null,
        mediaFileSize: Long? = null,
        recipientId: String? = null,
        localFilePath: String? = null
    ): Resource<Message> = withContext(Dispatchers.IO) {
        try {
            val entity = com.msi.openchat.core.backend.BackendManager.sendMessage(
                conversationId = conversationId,
                type = type,
                content = content,
                mediaUrl = mediaUrl,
                localFilePath = localFilePath,
                mediaDurationSeconds = mediaDurationSeconds ?: 0,
                mediaFileSize = mediaFileSize ?: 0L,
                recipientId = recipientId
            )
            Resource.Success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(tag, "sendMediaMessage error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to send media message", e)
        }
    }

    suspend fun sendMediaFromUri(
        context: Context,
        conversationId: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        type: String,
        fileSize: Long,
        durationSeconds: Int = 0
    ): Resource<Message> = withContext(Dispatchers.IO) {
        try {
            val entity = com.msi.openchat.core.backend.BackendManager.sendMessage(
                conversationId = conversationId,
                type = type,
                content = fileName,
                localFilePath = uri.toString(),
                mediaDurationSeconds = durationSeconds,
                mediaFileSize = fileSize,
                mediaMimeType = mimeType
            )
            Resource.Success(entity.toDomain())
        } catch (e: Exception) {
            Log.e(tag, "sendMediaFromUri error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to send media", e)
        }
    }

    suspend fun uploadMedia(file: File, type: String, conversationId: String): Resource<String> = withContext(Dispatchers.IO) {
        try {
            val bucketId = com.msi.openchat.data.remote.storage.NhostStorageClient.getBucketForType(type)
            val ext = file.extension.lowercase()
            val mimeType = when (type) {
                "image" -> when (ext) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                "audio" -> when (ext) {
                    "m4a", "mp4" -> "audio/mp4"
                    "aac" -> "audio/aac"
                    "ogg" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    else -> "audio/mp4"
                }
                "video" -> when (ext) {
                    "webm" -> "video/webm"
                    "3gp" -> "video/3gpp"
                    else -> "video/mp4"
                }
                else -> "application/octet-stream"
            }

            Log.i(tag, "Uploading $type to Nhost bucket '$bucketId' (${file.length()} bytes)")

            // Upload directly to Nhost Cloud Storage with dedicated bucket
            val nhostRes = storageClient.uploadFile(file, mimeType, bucketId)
            if (nhostRes is Resource.Success) {
                Log.i(tag, "Media uploaded successfully via Nhost storage: ${nhostRes.data}")
                return@withContext nhostRes
            }

            val errorMsg = (nhostRes as? Resource.Error)?.message ?: "Media upload failed"
            Log.e(tag, "Nhost upload failed for bucket '$bucketId': $errorMsg")
            Resource.Error(errorMsg)
        } catch (e: Exception) {
            Log.e(tag, "Upload error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to upload file", e)
        }
    }

    suspend fun sendVoiceMessage(conversationId: String, audioFile: File, durationSeconds: Int): Resource<Message> = withContext(Dispatchers.IO) {
        when (val uploadRes = uploadMedia(audioFile, "audio", conversationId)) {
            is Resource.Success -> {
                sendMediaMessage(
                    conversationId = conversationId,
                    type = "audio",
                    mediaUrl = uploadRes.data,
                    mediaDurationSeconds = durationSeconds,
                    mediaFileSize = audioFile.length()
                )
            }
            is Resource.Error -> Resource.Error(uploadRes.message)
            else -> Resource.Error("Upload failed")
        }
    }

    suspend fun sendImageMessage(conversationId: String, imageFile: File, mimeType: String): Resource<Message> = withContext(Dispatchers.IO) {
        when (val uploadRes = uploadMedia(imageFile, "image", conversationId)) {
            is Resource.Success -> {
                sendMediaMessage(
                    conversationId = conversationId,
                    type = "image",
                    mediaUrl = uploadRes.data,
                    mediaFileSize = imageFile.length()
                )
            }
            is Resource.Error -> Resource.Error(uploadRes.message)
            else -> Resource.Error("Upload failed")
        }
    }

    suspend fun sendVideoMessage(conversationId: String, videoFile: File, mimeType: String): Resource<Message> = withContext(Dispatchers.IO) {
        when (val uploadRes = uploadMedia(videoFile, "video", conversationId)) {
            is Resource.Success -> {
                sendMediaMessage(
                    conversationId = conversationId,
                    type = "video",
                    mediaUrl = uploadRes.data,
                    mediaFileSize = videoFile.length()
                )
            }
            is Resource.Error -> Resource.Error(uploadRes.message)
            else -> Resource.Error("Upload failed")
        }
    }

    suspend fun sendFileMessage(conversationId: String, file: File, originalFileName: String, mimeType: String): Resource<Message> = withContext(Dispatchers.IO) {
        when (val uploadRes = uploadMedia(file, "file", conversationId)) {
            is Resource.Success -> {
                sendMediaMessage(
                    conversationId = conversationId,
                    type = "file",
                    mediaUrl = uploadRes.data,
                    content = originalFileName,
                    mediaFileSize = file.length()
                )
            }
            is Resource.Error -> Resource.Error(uploadRes.message)
            else -> Resource.Error("Upload failed")
        }
    }

    suspend fun getAudioPlayableUrl(storagePath: String): Resource<String> = withContext(Dispatchers.IO) {
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
            return@withContext Resource.Success(storagePath)
        }
        val nhostUrl = storageClient.getFileUrl(storagePath)
        Resource.Success(nhostUrl)
    }

    suspend fun getMediaPlayableUrl(urlOrPath: String): Resource<String> = getAudioPlayableUrl(urlOrPath)

    /**
     * Query indexed media metadata for a user ("grep metadata") across all buckets or a specific bucket.
     */
    fun getUserMediaMetadata(userId: String, bucket: String? = null): Flow<List<Map<String, Any?>>> = callbackFlow {
        val queryNode = if (!bucket.isNullOrBlank()) {
            FirebaseManager.mediaMetadataRef.child(userId).child(bucket)
        } else {
            FirebaseManager.mediaMetadataRef.child(userId)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Map<String, Any?>>()
                if (!bucket.isNullOrBlank()) {
                    for (item in snapshot.children) {
                        val map = item.value as? Map<String, Any?>
                        if (map != null) list.add(map)
                    }
                } else {
                    for (bkt in snapshot.children) {
                        for (item in bkt.children) {
                            val map = item.value as? Map<String, Any?>
                            if (map != null) list.add(map)
                        }
                    }
                }
                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }

        queryNode.addValueEventListener(listener)
        awaitClose { queryNode.removeEventListener(listener) }
    }

    suspend fun markMessagesAsRead(conversationId: String) = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid ?: return@withContext
        val recipientId = extractRecipientId(conversationId, currentUserId)
        conversationsRef.child(currentUserId).child(recipientId).child("unread_count").setValue(0)

        try {
            val snapshot = getSingleSnapshot(messagesRef.child(conversationId).limitToLast(60)) ?: return@withContext
            val updates = HashMap<String, Any?>()
            for (child in snapshot.children) {
                val sender = child.child("sender_id").getValue(String::class.java)
                val status = child.child("status").getValue(String::class.java)
                if (sender != null && sender != currentUserId && status != "read") {
                    updates["${child.key}/status"] = "read"
                }
            }
            if (updates.isNotEmpty()) {
                messagesRef.child(conversationId).updateChildren(updates)
            }
        } catch (e: Exception) {
            Log.w(tag, "markMessagesAsRead notice: ${e.message}")
        }
    }

    suspend fun markMessagesAsDelivered(conversationId: String) = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid ?: return@withContext
        try {
            val snapshot = getSingleSnapshot(messagesRef.child(conversationId).limitToLast(60)) ?: return@withContext
            val updates = HashMap<String, Any?>()
            for (child in snapshot.children) {
                val sender = child.child("sender_id").getValue(String::class.java)
                val status = child.child("status").getValue(String::class.java)
                if (sender != null && sender != currentUserId && (status == "sent" || status == "sending" || status == null)) {
                    updates["${child.key}/status"] = "delivered"
                }
            }
            if (updates.isNotEmpty()) {
                messagesRef.child(conversationId).updateChildren(updates)
            }
        } catch (e: Exception) {
            Log.w(tag, "markMessagesAsDelivered notice: ${e.message}")
        }
    }

    fun setTypingStatus(conversationId: String, isTyping: Boolean) {
        val uid = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid ?: return
        val node = typingRef.child(conversationId).child(uid)
        if (isTyping) {
            node.setValue(true)
            node.onDisconnect().removeValue()
        } else {
            node.removeValue()
        }
    }

    fun observeTypingStatus(conversationId: String, otherUserId: String): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Boolean::class.java) == true)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val node = typingRef.child(conversationId).child(otherUserId)
        node.addValueEventListener(listener)
        awaitClose { node.removeEventListener(listener) }
    }

    private fun extractRecipientId(conversationId: String, senderId: String): String {
        val parts = conversationId.removePrefix("direct_").split("_")
        return if (parts.size >= 2) {
            if (parts[0] == senderId) parts[1] else parts[0]
        } else {
            conversationId
        }
    }

    private fun parseMessageSnapshot(snapshot: DataSnapshot, conversationId: String): Message {
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key.orEmpty()
        val senderId = snapshot.child("sender_id").getValue(String::class.java).orEmpty()
        val type = snapshot.child("type").getValue(String::class.java) ?: "text"
        val content = snapshot.child("content").getValue(String::class.java)
        val mediaUrl = snapshot.child("media_url").getValue(String::class.java)
        val duration = snapshot.child("media_duration_seconds").getValue(Int::class.java) ?: 0
        val size = snapshot.child("media_file_size").getValue(Long::class.java) ?: 0L
        val status = snapshot.child("status").getValue(String::class.java) ?: "sent"
        val createdAt = snapshot.child("created_at").getValue(String::class.java)

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            type = type,
            content = content,
            mediaUrl = mediaUrl,
            mediaDurationSeconds = duration,
            mediaFileSize = size,
            status = status,
            createdAt = createdAt
        )
    }
}
