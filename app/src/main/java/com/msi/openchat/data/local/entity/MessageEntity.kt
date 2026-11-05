package com.msi.openchat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.msi.openchat.data.model.Message
import java.time.Instant

/**
 * Room entity for local persistence of chat messages with WhatsApp-grade
 * offline-first, per-conversation monotonic ordering, and outbox reconciliation.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"]),
        Index(value = ["timestamp_ms"]),
        Index(value = ["conversation_id", "seq"]),
        Index(value = ["outbox_status"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String, // Client-generated UUID

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "type")
    val type: String = "text",

    @ColumnInfo(name = "content")
    val content: String? = null,

    @ColumnInfo(name = "media_url")
    val mediaUrl: String? = null,

    @ColumnInfo(name = "local_file_path")
    val localFilePath: String? = null,

    @ColumnInfo(name = "media_duration_seconds")
    val mediaDurationSeconds: Int = 0,

    @ColumnInfo(name = "media_file_size")
    val mediaFileSize: Long = 0L,

    @ColumnInfo(name = "media_mime_type")
    val mediaMimeType: String? = null,

    @ColumnInfo(name = "status")
    val status: String = "sent", // "pending", "sending", "sent", "delivered", "read", "failed"

    @ColumnInfo(name = "outbox_status")
    val outboxStatus: String = "sent", // "pending", "uploading", "sending", "sent", "failed"

    @ColumnInfo(name = "seq")
    val seq: Long = 0L, // Monotonic sequence strictly per-conversation

    @ColumnInfo(name = "server_timestamp_ms")
    val serverTimestampMs: Long = 0L, // Reconciled server timestamp

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_attempt_ms")
    val lastAttemptMs: Long = 0L,

    @ColumnInfo(name = "upload_progress")
    val uploadProgress: Float = 1.0f,

    @ColumnInfo(name = "created_at")
    val createdAt: String? = null,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        type = type,
        content = content,
        mediaUrl = mediaUrl,
        mediaDurationSeconds = mediaDurationSeconds,
        mediaFileSize = mediaFileSize,
        status = status,
        createdAt = createdAt,
        localFilePath = localFilePath,
        seq = seq,
        serverTimestampMs = serverTimestampMs,
        outboxStatus = outboxStatus,
        uploadProgress = uploadProgress
    )

    companion object {
        fun fromDomain(domain: Message, monotonicSeq: Long = 0L): MessageEntity {
            val parsedMs = parseIsoToEpochMs(domain.createdAt) ?: System.currentTimeMillis()
            val effectiveSeq = if (domain.seq > 0L) domain.seq else monotonicSeq
            return MessageEntity(
                id = domain.id,
                conversationId = domain.conversationId,
                senderId = domain.senderId,
                type = domain.type,
                content = domain.content,
                mediaUrl = domain.mediaUrl,
                localFilePath = domain.localFilePath,
                mediaDurationSeconds = domain.mediaDurationSeconds,
                mediaFileSize = domain.mediaFileSize,
                mediaMimeType = null,
                status = domain.status,
                outboxStatus = domain.outboxStatus,
                seq = effectiveSeq,
                serverTimestampMs = domain.serverTimestampMs,
                retryCount = 0,
                lastAttemptMs = 0L,
                uploadProgress = domain.uploadProgress,
                createdAt = domain.createdAt,
                timestampMs = parsedMs
            )
        }

        private fun parseIsoToEpochMs(isoString: String?): Long? {
            if (isoString.isNullOrBlank()) return null
            return try {
                Instant.parse(isoString).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
