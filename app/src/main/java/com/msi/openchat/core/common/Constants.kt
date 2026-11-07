package com.msi.openchat.core.common

object Constants {
    val NHOST_SUBDOMAIN: String get() = com.msi.openchat.core.security.NativeCredentialsManager.subdomain
    val NHOST_REGION: String get() = com.msi.openchat.core.security.NativeCredentialsManager.region
    val NHOST_AUTH_URL: String get() = com.msi.openchat.core.security.NativeCredentialsManager.authUrl
    val NHOST_GRAPHQL_URL: String get() = com.msi.openchat.core.security.NativeCredentialsManager.graphqlUrl
    val NHOST_STORAGE_URL: String get() = com.msi.openchat.core.security.NativeCredentialsManager.storageUrl
    val NHOST_FUNCTIONS_URL: String get() = com.msi.openchat.core.security.NativeCredentialsManager.functionsUrl

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
    const val CHANNEL_ACTIVE_CALLS = "openchat_active_calls"
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
