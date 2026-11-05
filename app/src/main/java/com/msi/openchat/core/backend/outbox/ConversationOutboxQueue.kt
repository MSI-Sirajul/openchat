package com.msi.openchat.core.backend.outbox

import android.content.Context
import android.net.Uri
import android.util.Log
import com.msi.openchat.data.local.dao.MessageDao
import com.msi.openchat.data.local.entity.MessageEntity
import com.msi.openchat.data.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WhatsApp/Telegram Per-Conversation Outbox Queue.
 *
 * Guarantees:
 * 1. Strict per-conversation monotonic ordering (NEVER global FIFO ordering).
 * 2. File uploads are sequenced within the conversation lane so media never jumps ahead of accompanying messages.
 * 3. Optimistic Room insertion with client-generated UUID for instant UI display.
 * 4. Idempotent server dispatch with exponential backoff (1s -> 2s -> 4s -> ... -> 30s).
 * 5. Server timestamp reconciliation on ack.
 */
class ConversationOutboxQueue(
    private val context: Context,
    private val messageDao: MessageDao,
    private val currentUserIdProvider: () -> String?,
    private val fileUploader: suspend (file: File, mimeType: String, bucket: String, onProgress: (Float) -> Unit) -> String?,
    private val serverDispatcher: suspend (message: MessageEntity) -> ServerAck?
) {
    private val tag = "ConversationOutbox"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Per-conversation isolation locks and signals
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()
    private val conversationSignals = ConcurrentHashMap<String, Channel<Unit>>()
    private val conversationJobs = ConcurrentHashMap<String, Job>()

    data class ServerAck(
        val messageId: String,
        val serverTimestampMs: Long,
        val mediaUrl: String? = null
    )

    private fun getLockForConversation(conversationId: String): Mutex {
        return conversationLocks.computeIfAbsent(conversationId) { Mutex() }
    }

    private fun getSignalForConversation(conversationId: String): Channel<Unit> {
        return conversationSignals.computeIfAbsent(conversationId) { Channel(Channel.CONFLATED) }
    }

    /**
     * Optimistically enqueues a new outgoing message into Room with a monotonic sequence,
     * client-generated UUID, and immediately triggers the per-conversation outbox lane.
     */
    suspend fun enqueueMessage(
        conversationId: String,
        type: String,
        content: String?,
        mediaUrl: String? = null,
        localFilePath: String? = null,
        mediaDurationSeconds: Int = 0,
        mediaFileSize: Long = 0L,
        mediaMimeType: String? = null,
        recipientId: String? = null
    ): MessageEntity {
        val senderId = currentUserIdProvider() ?: "current_user"
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Monotonic sequence calculation strictly scoped to this conversation
        val maxSeq = messageDao.getMaxSeq(conversationId) ?: 0L
        val assignedSeq = maxSeq + 1L

        val initialOutboxStatus = if (!localFilePath.isNullOrBlank() && mediaUrl.isNullOrBlank()) {
            "uploading"
        } else {
            "pending"
        }

        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            type = type,
            content = content,
            mediaUrl = mediaUrl,
            localFilePath = localFilePath,
            mediaDurationSeconds = mediaDurationSeconds,
            mediaFileSize = mediaFileSize,
            mediaMimeType = mediaMimeType,
            status = "sending",
            outboxStatus = initialOutboxStatus,
            seq = assignedSeq,
            serverTimestampMs = 0L,
            retryCount = 0,
            lastAttemptMs = now,
            uploadProgress = if (initialOutboxStatus == "uploading") 0f else 1f,
            createdAt = com.msi.openchat.core.common.DateTimeUtils.formatIsoCurrent(),
            timestampMs = now
        )

        // 1. Optimistic Room insert (local-first)
        messageDao.insertMessage(entity)
        Log.i(tag, "Enqueued message $messageId in conv $conversationId with monotonic seq $assignedSeq")

        // 2. Trigger isolated per-conversation dispatcher
        triggerConversationOutbox(conversationId)

        return entity
    }

    /**
     * Trigger processing for a specific conversation outbox lane.
     */
    fun triggerConversationOutbox(conversationId: String) {
        val signal = getSignalForConversation(conversationId)
        signal.trySend(Unit)

        conversationJobs.computeIfAbsent(conversationId) {
            scope.launch {
                runConversationLane(conversationId, signal)
            }
        }
    }

    /**
     * Drain all pending outbox messages across all conversations (e.g. after network reconnect).
     */
    fun drainAll() {
        scope.launch {
            try {
                val pending = messageDao.getAllPendingOutboxMessages()
                val distinctConvs = pending.map { it.conversationId }.distinct()
                Log.i(tag, "Draining outboxes for ${distinctConvs.size} active conversations")
                distinctConvs.forEach { convId ->
                    triggerConversationOutbox(convId)
                }
            } catch (e: Exception) {
                Log.w(tag, "drainAll error: ${e.message}")
            }
        }
    }

    /**
     * Dedicated worker loop for a single conversation.
     * Enforces serial in-order dispatch for this conversation while remaining fully concurrent
     * with all other conversations.
     */
    private suspend fun runConversationLane(conversationId: String, signal: Channel<Unit>) {
        val mutex = getLockForConversation(conversationId)

        for (trigger in signal) {
            mutex.withLock {
                processPendingForConversation(conversationId)
            }
        }
    }

    private suspend fun processPendingForConversation(conversationId: String) {
        var backoffMs = 1000L

        while (true) {
            val pendingList = messageDao.getPendingOutboxMessages(conversationId)
            if (pendingList.isEmpty()) {
                break
            }

            // Always take the earliest unsent message in this conversation (ordered by seq ASC)
            val current = pendingList.first()
            Log.d(tag, "Processing conv $conversationId msg ${current.id} seq ${current.seq} outboxStatus=${current.outboxStatus}")

            var resolvedMediaUrl = current.mediaUrl

            // Step 1: Compress & upload file if this is a media message and hasn't been uploaded yet
            if (resolvedMediaUrl.isNullOrBlank() && !current.localFilePath.isNullOrBlank()) {
                val sourcePath = current.localFilePath
                messageDao.updateUploadProgress(current.id, 0.05f, "compressing")

                // Zero-copy: keep original content:// URI untouched; compress to temporary scratch file
                val compressedFile = com.msi.openchat.core.media.MediaCompressionManager.compressMedia(
                    context = context,
                    sourceUriString = sourcePath,
                    type = current.type,
                    mimeType = current.mediaMimeType
                )

                if (compressedFile != null && compressedFile.exists() && compressedFile.length() > 0) {
                    messageDao.updateUploadProgress(current.id, 0.2f, "uploading")
                    val bucket = com.msi.openchat.data.remote.storage.NhostStorageClient.getBucketForType(current.type)
                    val mime = current.mediaMimeType ?: when (current.type) {
                        "image" -> "image/jpeg"
                        "video" -> "video/mp4"
                        "audio" -> "audio/mp4"
                        else -> "application/octet-stream"
                    }

                    var uploadedUrl: String? = null
                    var uploadAttempts = 0
                    while (uploadedUrl == null && uploadAttempts < 3) {
                        uploadAttempts++
                        try {
                            uploadedUrl = fileUploader(compressedFile, mime, bucket) { progress ->
                                scope.launch {
                                    val mappedProgress = 0.2f + (progress * 0.8f)
                                    messageDao.updateUploadProgress(current.id, mappedProgress, "uploading")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "File upload attempt $uploadAttempts failed for ${current.id}: ${e.message}")
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                        }
                    }

                    // Ephemeral cleanup: scratch file is no longer needed
                    try { compressedFile.delete() } catch (_: Exception) {}

                    if (uploadedUrl != null) {
                        resolvedMediaUrl = uploadedUrl
                        messageDao.updateMediaUrl(current.id, uploadedUrl)
                        messageDao.updateUploadProgress(current.id, 1.0f, "pending")
                    } else {
                        // File upload failed after retries; mark failed and pause lane
                        Log.e(tag, "File upload failed for ${current.id}. Halting conv $conversationId lane to preserve ordering.")
                        messageDao.updateOutboxStatus(
                            id = current.id,
                            outboxStatus = "failed",
                            status = "failed",
                            retryCount = current.retryCount + 1,
                            lastAttemptMs = System.currentTimeMillis()
                        )
                        return
                    }
                } else {
                    Log.e(tag, "Failed to resolve or compress media file from $sourcePath for ${current.id}")
                    messageDao.updateOutboxStatus(
                        id = current.id,
                        outboxStatus = "failed",
                        status = "failed",
                        retryCount = current.retryCount + 1,
                        lastAttemptMs = System.currentTimeMillis()
                    )
                    return
                }
            }

            // Step 2: Dispatch message payload to backend
            val messageToDispatch = current.copy(
                mediaUrl = resolvedMediaUrl ?: current.mediaUrl,
                outboxStatus = "sending",
                status = "sending"
            )
            messageDao.updateOutboxStatus(
                id = current.id,
                outboxStatus = "sending",
                status = "sending",
                retryCount = current.retryCount,
                lastAttemptMs = System.currentTimeMillis()
            )

            var serverAck: ServerAck? = null
            var dispatchAttempts = 0
            while (serverAck == null && dispatchAttempts < 3) {
                dispatchAttempts++
                try {
                    serverAck = serverDispatcher(messageToDispatch)
                } catch (e: Exception) {
                    Log.w(tag, "Server dispatch attempt $dispatchAttempts failed for ${current.id}: ${e.message}")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                }
            }

            if (serverAck != null) {
                // Step 3: Reconcile server timestamp and mark sent
                val effectiveTs = if (serverAck.serverTimestampMs > 0L) serverAck.serverTimestampMs else System.currentTimeMillis()
                messageDao.reconcileServerAck(
                    id = current.id,
                    serverTimestampMs = effectiveTs,
                    mediaUrl = serverAck.mediaUrl ?: resolvedMediaUrl
                )
                Log.i(tag, "Message ${current.id} seq ${current.seq} successfully acked by server at $effectiveTs")
                backoffMs = 1000L // Reset backoff on success
            } else {
                // Server push failed after retries; mark failed to prevent out-of-order queue skipping
                Log.e(tag, "Dispatch failed for ${current.id}. Stopping lane to prevent subsequent messages from jumping ahead.")
                messageDao.updateOutboxStatus(
                    id = current.id,
                    outboxStatus = "failed",
                    status = "failed",
                    retryCount = current.retryCount + 1,
                    lastAttemptMs = System.currentTimeMillis()
                )
                return
            }
        }
    }

    private fun resolveFile(path: String): File? {
        return try {
            if (path.startsWith("file://")) {
                File(Uri.parse(path).path.orEmpty())
            } else if (path.startsWith("/")) {
                File(path)
            } else {
                File(path)
            }
        } catch (_: Exception) {
            null
        }
    }
}
