package com.msi.openchat.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AuthSession(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "token_type") val tokenType: String? = "bearer",
    val user: AuthUser? = null
)

@JsonClass(generateAdapter = true)
data class AuthUser(
    val id: String,
    val email: String? = null,
    @Json(name = "user_metadata") val userMetadata: Map<String, Any>? = null
)

@JsonClass(generateAdapter = true)
data class Profile(
    val id: String,
    val username: String? = null,
    @Json(name = "display_name") val displayName: String = "",
    val name: String? = null,
    val email: String? = null,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    val phone: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    val avatar: String? = null,
    val bio: String? = null,
    @Json(name = "status_message") val statusMessage: String? = "Available",
    @Json(name = "online_status") val onlineStatus: String = "offline",
    @Json(name = "last_seen") val lastSeen: String? = null,
    @Json(name = "show_last_seen") val showLastSeen: Boolean = true,
    @Json(name = "show_read_receipts") val showReadReceipts: Boolean = true,
    @Json(name = "email_confirmed") val emailConfirmed: Boolean = true,
    @Json(name = "created_at") val createdAt: String? = null
) {
    fun getResolvedName(): String = when {
        !name.isNullOrBlank() -> name
        displayName.isNotBlank() -> displayName
        !username.isNullOrBlank() -> username
        else -> "User"
    }

    fun getResolvedAvatar(): String? = avatar?.takeIf { it.isNotBlank() } ?: avatarUrl?.takeIf { it.isNotBlank() }
    fun getResolvedPhone(): String? = phone?.takeIf { it.isNotBlank() } ?: phoneNumber?.takeIf { it.isNotBlank() }
}

enum class FriendRequestStatus {
    PENDING, ACCEPTED, DECLINED, CANCELLED
}

@JsonClass(generateAdapter = true)
data class FriendRequest(
    val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "receiver_id") val receiverId: String,
    val status: String = "pending",
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    // Populated profiles for UI presentation
    val sender: Profile? = null,
    val receiver: Profile? = null
)

@JsonClass(generateAdapter = true)
data class Friendship(
    val id: String,
    @Json(name = "user1_id") val user1Id: String,
    @Json(name = "user2_id") val user2Id: String,
    @Json(name = "created_at") val createdAt: String? = null,
    val friendProfile: Profile? = null
)

@JsonClass(generateAdapter = true)
data class Conversation(
    val id: String,
    val type: String = "direct",
    @Json(name = "participant1_id") val participant1Id: String,
    @Json(name = "participant2_id") val participant2Id: String,
    @Json(name = "last_message_text") val lastMessageText: String? = null,
    @Json(name = "last_message_at") val lastMessageAt: String? = null,
    @Json(name = "last_message_sender_id") val lastMessageSenderId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    // Transient UI fields
    val otherUser: Profile? = null,
    val unreadCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class Message(
    val id: String,
    @Json(name = "conversation_id") val conversationId: String,
    @Json(name = "sender_id") val senderId: String,
    val type: String = "text", // "text", "audio", "image", "system"
    val content: String? = null,
    @Json(name = "media_url") val mediaUrl: String? = null,
    @Json(name = "media_duration_seconds") val mediaDurationSeconds: Int = 0,
    @Json(name = "media_file_size") val mediaFileSize: Long = 0,
    val status: String = "sent", // "sending", "sent", "delivered", "read"
    @Json(name = "created_at") val createdAt: String? = null,
    // Offline local caching field
    @Json(name = "local_file_path") val localFilePath: String? = null,
    val seq: Long = 0L,
    @Json(name = "server_timestamp_ms") val serverTimestampMs: Long = 0L,
    @Json(name = "outbox_status") val outboxStatus: String = "sent",
    val uploadProgress: Float = 1.0f,
    // Transient fields for local recording/playback
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f
)

@JsonClass(generateAdapter = true)
data class CallSession(
    val id: String,
    @Json(name = "caller_id") val callerId: String,
    @Json(name = "receiver_id") val receiverId: String,
    @Json(name = "call_type") val callType: String = "audio",
    val status: String = "calling", // calling, ringing, connecting, connected, declined, busy, missed, ended, failed
    @Json(name = "started_at") val startedAt: String? = null,
    @Json(name = "connected_at") val connectedAt: String? = null,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int = 0,
    @Json(name = "end_reason") val endReason: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    val callerProfile: Profile? = null,
    val receiverProfile: Profile? = null
)

@JsonClass(generateAdapter = true)
data class CallSignalingMessage(
    val id: String? = null,
    @Json(name = "call_id") val callId: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "receiver_id") val receiverId: String,
    val type: String, // "offer", "answer", "ice_candidate", "bye"
    val payload: String, // JSON payload representing SDP or ICE Candidate
    @Json(name = "created_at") val createdAt: String? = null
)

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)
