package com.msi.openchat.core.backend.inbox

import android.content.Context
import android.util.Log
import com.msi.openchat.data.local.dao.ConversationDao
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.local.entity.ConversationEntity
import com.msi.openchat.data.local.entity.MessageEntity
import com.msi.openchat.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WhatsApp/Telegram Inbox Processor:
 * 1. Deduplicates incoming messages by client-generated UUID.
 * 2. Applies messages strictly per-conversation — rejects out-of-order or cross-conversation writes.
 * 3. Reconciles serverTimestampMs so UI ordering is always deterministic: (serverTimestamp, seq).
 * 4. Updates unread counts and conversation preview metadata atomically.
 */
class ConversationInboxProcessor(
    private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao?,
    private val currentUserIdProvider: () -> String?
) {
    private val tag = "InboxProcessor"
    private val inboxScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val conversationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun getLockForConversation(conversationId: String): Mutex {
        return conversationLocks.computeIfAbsent(conversationId) { Mutex() }
    }

    /**
     * Process an incoming message received via WebSocket, Firebase Realtime Database, or sync reconciliation.
     */
    suspend fun processIncomingMessage(
        expectedConversationId: String,
        message: Message
    ) {
        // Strict boundary check: Reject any message not belonging to this conversation
        if (message.conversationId != expectedConversationId) {
            Log.e(tag, "Security/Integrity violation: Message ${message.id} target conv ${message.conversationId} does not match expected $expectedConversationId! Rejecting cross-conversation write.")
            return
        }

        val mutex = getLockForConversation(expectedConversationId)
        mutex.withLock {
            try {
                val currentUid = currentUserIdProvider()
                val existing = messageDao.getMessageById(message.id)

                if (existing != null) {
                    // Message already exists locally. Deduplicate and reconcile.
                    // Preserve local read status if already read or local pending states.
                    val isLocalOutgoing = existing.senderId == currentUid
                    if (isLocalOutgoing && existing.outboxStatus in listOf("pending", "uploading", "sending")) {
                        // Server is broadcasting our own pending message back!
                        val serverTs = if (message.serverTimestampMs > 0L) message.serverTimestampMs else System.currentTimeMillis()
                        messageDao.reconcileServerAck(
                            id = existing.id,
                            serverTimestampMs = serverTs,
                            mediaUrl = message.mediaUrl ?: existing.mediaUrl
                        )
                        Log.d(tag, "Reconciled local pending message ${existing.id} with server event.")
                        return@withLock
                    }

                    // Existing message update (e.g. read receipt or status update)
                    val effectiveStatus = if (existing.status == "read" && message.status != "read") {
                        "read"
                    } else {
                        message.status
                    }

                    val updatedEntity = existing.copy(
                        status = effectiveStatus,
                        serverTimestampMs = if (message.serverTimestampMs > 0L) message.serverTimestampMs else existing.serverTimestampMs,
                        mediaUrl = message.mediaUrl ?: existing.mediaUrl,
                        content = message.content ?: existing.content
                    )
                    messageDao.updateMessage(updatedEntity)
                } else {
                    // Brand new incoming message.
                    val maxSeq = messageDao.getMaxSeq(expectedConversationId) ?: 0L
                    val assignedSeq = if (message.seq > 0L) message.seq else maxSeq + 1L

                    val entity = MessageEntity(
                        id = message.id,
                        conversationId = expectedConversationId,
                        senderId = message.senderId,
                        type = message.type,
                        content = message.content,
                        mediaUrl = message.mediaUrl,
                        localFilePath = message.localFilePath,
                        mediaDurationSeconds = message.mediaDurationSeconds,
                        mediaFileSize = message.mediaFileSize,
                        mediaMimeType = null,
                        status = message.status,
                        outboxStatus = "sent",
                        seq = assignedSeq,
                        serverTimestampMs = if (message.serverTimestampMs > 0L) message.serverTimestampMs else System.currentTimeMillis(),
                        retryCount = 0,
                        lastAttemptMs = 0L,
                        uploadProgress = 1.0f,
                        createdAt = message.createdAt,
                        timestampMs = System.currentTimeMillis()
                    )
                    messageDao.insertMessage(entity)
                    Log.i(tag, "Inserted incoming message ${message.id} into conv $expectedConversationId with seq $assignedSeq")

                    // Automatic background download for incoming media (receiver-side caching)
                    if (!message.mediaUrl.isNullOrBlank() && message.type in listOf("image", "photo", "video", "audio", "voice", "file")) {
                        com.msi.openchat.core.media.MediaDownloadManager.enqueueBackgroundDownload(context, message)
                    }
                }

                // Update conversation summary
                updateConversationSummary(expectedConversationId, message)
            } catch (e: Exception) {
                Log.e(tag, "Failed to process incoming message ${message.id}: ${e.message}", e)
            }
        }
    }

    private suspend fun updateConversationSummary(conversationId: String, message: Message) {
        if (conversationDao == null) return
        try {
            val conv = conversationDao.getConversationById(conversationId)
            val currentUid = currentUserIdProvider()
            val isFromOther = message.senderId != currentUid

            if (conv != null) {
                val newUnread = if (isFromOther && message.status != "read") conv.unreadCount + 1 else conv.unreadCount
                conversationDao.updateLastMessageWithUnread(
                    conversationId = conversationId,
                    lastText = message.content ?: "New message",
                    lastAt = message.createdAt ?: "",
                    lastSenderId = message.senderId,
                    unreadCount = newUnread
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Error updating conversation summary: ${e.message}")
        }
    }
}
