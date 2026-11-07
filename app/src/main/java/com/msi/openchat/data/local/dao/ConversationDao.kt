package com.msi.openchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.msi.openchat.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local conversation list.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY last_message_at DESC, created_at DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE user_id = :userId OR user_id = '' ORDER BY last_message_at DESC, created_at DESC")
    fun getConversationsForUser(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY last_message_at DESC, created_at DESC")
    suspend fun getAllConversationsList(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE user_id = :userId OR user_id = '' ORDER BY last_message_at DESC, created_at DESC")
    suspend fun getConversationsForUserList(userId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET last_message_text = :lastText, last_message_at = :lastAt, last_message_sender_id = :lastSenderId WHERE id = :conversationId")
    suspend fun updateLastMessage(conversationId: String, lastText: String?, lastAt: String?, lastSenderId: String?)

    @Query("UPDATE conversations SET last_message_text = :lastText, last_message_at = :lastAt, last_message_sender_id = :lastSenderId, unread_count = :unreadCount WHERE id = :conversationId")
    suspend fun updateLastMessageWithUnread(conversationId: String, lastText: String?, lastAt: String?, lastSenderId: String?, unreadCount: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("DELETE FROM conversations WHERE user_id = :userId")
    suspend fun clearConversationsForUser(userId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations()
}
