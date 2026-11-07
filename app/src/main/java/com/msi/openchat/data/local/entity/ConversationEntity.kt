package com.msi.openchat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.msi.openchat.data.model.Conversation
import com.msi.openchat.data.model.Profile

/**
 * Room entity for local persistence of chat conversations.
 * Allows instant offline rendering of the conversation list.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["participant1_id"]),
        Index(value = ["participant2_id"]),
        Index(value = ["last_message_at"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String = "",

    @ColumnInfo(name = "type")
    val type: String = "direct",

    @ColumnInfo(name = "participant1_id")
    val participant1Id: String,

    @ColumnInfo(name = "participant2_id")
    val participant2Id: String,

    @ColumnInfo(name = "last_message_text")
    val lastMessageText: String? = null,

    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: String? = null,

    @ColumnInfo(name = "last_message_sender_id")
    val lastMessageSenderId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String? = null,

    @ColumnInfo(name = "other_user_id")
    val otherUserId: String = "",

    @ColumnInfo(name = "other_user_name")
    val otherUserName: String? = null,

    @ColumnInfo(name = "other_user_avatar")
    val otherUserAvatar: String? = null,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        type = type,
        participant1Id = participant1Id,
        participant2Id = participant2Id,
        lastMessageText = lastMessageText,
        lastMessageAt = lastMessageAt,
        lastMessageSenderId = lastMessageSenderId,
        createdAt = createdAt,
        otherUser = if (otherUserId.isNotBlank()) {
            Profile(
                id = otherUserId,
                displayName = otherUserName ?: "User",
                avatarUrl = otherUserAvatar
            )
        } else null,
        unreadCount = unreadCount
    )

    companion object {
        fun fromDomain(domain: Conversation, currentUserId: String): ConversationEntity {
            val otherUserId = if (domain.participant1Id == currentUserId) {
                domain.participant2Id
            } else {
                domain.participant1Id
            }

            return ConversationEntity(
                id = domain.id,
                userId = currentUserId,
                type = domain.type,
                participant1Id = domain.participant1Id,
                participant2Id = domain.participant2Id,
                lastMessageText = domain.lastMessageText,
                lastMessageAt = domain.lastMessageAt,
                lastMessageSenderId = domain.lastMessageSenderId,
                createdAt = domain.createdAt,
                otherUserId = otherUserId,
                otherUserName = domain.otherUser?.displayName,
                otherUserAvatar = domain.otherUser?.avatarUrl,
                unreadCount = domain.unreadCount
            )
        }
    }
}
