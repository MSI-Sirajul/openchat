package com.msi.openchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msi.openchat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local chat messages.
 * Supports per-conversation sequence tracking, outbox dispatch, and server timestamp reconciliation.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY CASE WHEN server_timestamp_ms > 0 THEN server_timestamp_ms ELSE timestamp_ms END ASC, seq ASC, timestamp_ms ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY CASE WHEN server_timestamp_ms > 0 THEN server_timestamp_ms ELSE timestamp_ms END ASC, seq ASC, timestamp_ms ASC")
    suspend fun getMessagesListForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT MAX(seq) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMaxSeq(conversationId: String): Long?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND outbox_status IN ('pending', 'uploading', 'sending', 'failed') ORDER BY seq ASC, timestamp_ms ASC")
    suspend fun getPendingOutboxMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE outbox_status IN ('pending', 'uploading', 'sending', 'failed') ORDER BY conversation_id ASC, seq ASC, timestamp_ms ASC")
    suspend fun getAllPendingOutboxMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String)

    @Query("UPDATE messages SET outbox_status = :outboxStatus, status = :status, retry_count = :retryCount, last_attempt_ms = :lastAttemptMs WHERE id = :id")
    suspend fun updateOutboxStatus(
        id: String,
        outboxStatus: String,
        status: String,
        retryCount: Int,
        lastAttemptMs: Long
    )

    @Query("UPDATE messages SET outbox_status = 'sent', status = 'sent', server_timestamp_ms = :serverTimestampMs, media_url = COALESCE(:mediaUrl, media_url) WHERE id = :id")
    suspend fun reconcileServerAck(
        id: String,
        serverTimestampMs: Long,
        mediaUrl: String? = null
    )

    @Query("UPDATE messages SET upload_progress = :progress, outbox_status = :outboxStatus WHERE id = :id")
    suspend fun updateUploadProgress(id: String, progress: Float, outboxStatus: String)

    @Query("UPDATE messages SET local_file_path = :localPath WHERE id = :id")
    suspend fun updateLocalFilePath(id: String, localPath: String)

    @Query("SELECT * FROM messages WHERE local_file_path IS NOT NULL")
    suspend fun getAllMessagesWithLocalPath(): List<MessageEntity>

    @Query("UPDATE messages SET media_url = :mediaUrl WHERE id = :id")
    suspend fun updateMediaUrl(id: String, mediaUrl: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE user_id = :userId")
    suspend fun clearMessagesForUser(userId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND content LIKE '%' || :query || '%' ORDER BY timestamp_ms DESC")
    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    @Query("UPDATE messages SET status = 'read' WHERE conversation_id = :conversationId AND sender_id != :currentUserId AND status != 'read'")
    suspend fun markMessagesAsRead(conversationId: String, currentUserId: String)
}
