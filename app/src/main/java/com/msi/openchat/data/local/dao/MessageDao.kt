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
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp_ms ASC, created_at ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp_ms ASC, created_at ASC")
    suspend fun getMessagesListForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: String, status: String)

    @Query("UPDATE messages SET local_file_path = :localPath WHERE id = :id")
    suspend fun updateLocalFilePath(id: String, localPath: String)

    @Query("UPDATE messages SET media_url = :mediaUrl WHERE id = :id")
    suspend fun updateMediaUrl(id: String, mediaUrl: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND content LIKE '%' || :query || '%' ORDER BY timestamp_ms DESC")
    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int

    @Query("UPDATE messages SET status = 'read' WHERE conversation_id = :conversationId AND sender_id != :currentUserId AND status != 'read'")
    suspend fun markMessagesAsRead(conversationId: String, currentUserId: String)
}
