package com.msi.openchat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.msi.openchat.data.model.Message
import java.time.Instant

/**
 * Room entity for local persistence of chat messages.
 * Enables instant offline loading and offline message history viewing.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"]),
        Index(value = ["timestamp_ms"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,

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

    @ColumnInfo(name = "status")
    val status: String = "sent",

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
        localFilePath = localFilePath
    )

    companion object {
        fun fromDomain(domain: Message): MessageEntity {
            val parsedMs = parseIsoToEpochMs(domain.createdAt) ?: System.currentTimeMillis()
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
                status = domain.status,
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
