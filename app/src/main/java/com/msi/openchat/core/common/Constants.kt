package com.msi.openchat.core.common

object Constants {
    // Storage buckets
    const val BUCKET_AVATARS = "avatars"
    const val BUCKET_IMAGES = "images"
    const val BUCKET_VIDEOS = "videos"
    const val BUCKET_VOICE_CHAT = "voice-chat"
    const val BUCKET_AUDIO = "audio"
    const val BUCKET_ATTACHMENTS = "attachments"
    const val BUCKET_VOICE_MESSAGES = "voice-chat"
    const val BUCKET_CHAT_ATTACHMENTS = "attachments"

    // STUN / TURN
    const val DEFAULT_STUN_SERVER = "stun:stun.l.google.com:19302"
    const val DEFAULT_STUN_SERVER_2 = "stun:stun1.l.google.com:19302"

    // Preferences & Cache
    const val PREFS_NAME = "openchat_secure_prefs"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_USER_EMAIL = "user_email"
    const val KEY_PERMISSION_ONBOARDING_COMPLETED = "permission_onboarding_completed"

    // Notification channels
    const val CHANNEL_INCOMING_CALLS = "openchat_incoming_calls"
    const val CHANNEL_INCOMING_VIDEO_CALLS = "video_call_incoming"
    const val CHANNEL_ACTIVE_CALLS = "openchat_active_calls"
    const val CHANNEL_ACTIVE_VIDEO_CALLS = "video_call_ongoing"
    const val CHANNEL_MISSED_CALLS = "openchat_missed_calls"
    const val CHANNEL_MESSAGES = "openchat_messages"
    const val CHANNEL_CALLS = "openchat_calls"
    const val CHANNEL_FRIENDS = "openchat_friends"
    const val CHANNEL_FRIEND_REQUESTS = "openchat_friend_requests"
    const val CHANNEL_FRIEND_ACCEPTED = "openchat_friend_accepted"
    const val CHANNEL_NETWORK = "openchat_network"

    const val NOTIFICATION_ID_CALL = 2001
    const val NOTIFICATION_ID_ACTIVE_CALL = 2002
    const val NOTIFICATION_ID_NETWORK = 2003
    const val NOTIFICATION_ID_VIDEO_CALL = 2004

    // Strictly isolated PendingIntent request-code namespaces (Audio vs Video vs PiP)
    private const val AUDIO_ACCEPT_REQ_BASE = 10_000
    private const val AUDIO_DECLINE_REQ_BASE = 15_000
    private const val AUDIO_FULLSCREEN_REQ_BASE = 20_000
    const val REQ_AUDIO_ONGOING_CONTENT = 25_001
    const val REQ_AUDIO_ONGOING_END = 25_002
    const val REQ_AUDIO_ONGOING_MUTE = 25_003
    const val REQ_AUDIO_ONGOING_SPEAKER = 25_004

    private const val VIDEO_ACCEPT_REQ_BASE = 30_000
    private const val VIDEO_DECLINE_REQ_BASE = 35_000
    private const val VIDEO_FULLSCREEN_REQ_BASE = 40_000
    const val REQ_VIDEO_ONGOING_CONTENT = 45_001
    const val REQ_VIDEO_ONGOING_END = 45_002
    const val REQ_VIDEO_ONGOING_MUTE = 45_003
    const val REQ_VIDEO_ONGOING_SWITCH_CAM = 45_004
    const val REQ_VIDEO_PIP_MUTE = 46_001
    const val REQ_VIDEO_PIP_SWITCH_CAM = 46_002
    const val REQ_VIDEO_PIP_END = 46_003

    fun audioAcceptRequestCode(callId: String): Int = AUDIO_ACCEPT_REQ_BASE + (callId.hashCode() and 0x0FFF)
    fun audioDeclineRequestCode(callId: String): Int = AUDIO_DECLINE_REQ_BASE + (callId.hashCode() and 0x0FFF)
    fun audioFullScreenRequestCode(callId: String): Int = AUDIO_FULLSCREEN_REQ_BASE + (callId.hashCode() and 0x0FFF)

    fun videoAcceptRequestCode(callId: String): Int = VIDEO_ACCEPT_REQ_BASE + (callId.hashCode() and 0x0FFF)
    fun videoDeclineRequestCode(callId: String): Int = VIDEO_DECLINE_REQ_BASE + (callId.hashCode() and 0x0FFF)
    fun videoFullScreenRequestCode(callId: String): Int = VIDEO_FULLSCREEN_REQ_BASE + (callId.hashCode() and 0x0FFF)

    // Deep link scheme and hosts
    const val DEEP_LINK_SCHEME = "openchat"
    const val DEEP_LINK_HOST_CHAT = "chat"
    const val DEEP_LINK_HOST_CALL = "call"
    const val WEB_DEEP_LINK_HOST = "openchat.app"
}

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()
    object Loading : Resource<Nothing>()
}
