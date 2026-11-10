package com.msi.openchat.core.di

import android.content.Context
import com.msi.openchat.core.security.FirebaseAuthManager
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.data.repository.AuthRepository
import com.msi.openchat.data.repository.CallRepository
import com.msi.openchat.data.repository.ChatRepository
import com.msi.openchat.data.repository.FriendRepository
import com.msi.openchat.data.repository.FriendsRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.feature.voice.AudioPlayerHelper
import com.msi.openchat.feature.voice.AudioRecorderHelper

object ServiceLocator {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        com.msi.openchat.service.notification.OpenChatNotificationManager.initChannels(context.applicationContext)
    }

    val context: Context
        get() = appContext ?: throw IllegalStateException("ServiceLocator not initialized")

    val networkMonitor: com.msi.openchat.core.common.NetworkMonitor by lazy {
        com.msi.openchat.core.common.NetworkMonitor(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val themeManager: com.msi.openchat.core.theme.ThemeManager by lazy {
        com.msi.openchat.core.theme.ThemeManager(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val authManager: FirebaseAuthManager by lazy {
        FirebaseAuthManager(
            context = appContext ?: throw IllegalStateException("ServiceLocator not initialized"),
            sessionManager = sessionManager
        )
    }

    val firebaseManager: FirebaseManager by lazy {
        FirebaseManager.getInstance()
    }

    val userRepository: UserRepository by lazy {
        UserRepository(sessionManager)
    }

    val presenceManager: com.msi.openchat.core.presence.PresenceManager by lazy {
        com.msi.openchat.core.presence.PresenceManager(
            userRepository = userRepository,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(sessionManager, authManager, userRepository)
    }

    val friendRepository: FriendRepository by lazy {
        friendsRepository
    }

    val friendsRepository: FriendsRepository by lazy {
        FriendsRepository(sessionManager, userRepository)
    }

    @Volatile
    private var activeUserId: String? = null
    @Volatile
    private var activeDatabase: com.msi.openchat.data.local.OpenChatDatabase? = null
    @Volatile
    private var activeChatRepository: ChatRepository? = null

    val database: com.msi.openchat.data.local.OpenChatDatabase
        get() {
            val uid = try { sessionManager.getUserId() } catch (_: Exception) { null }
            if (activeDatabase == null || activeUserId != uid) {
                synchronized(this) {
                    if (activeDatabase == null || activeUserId != uid) {
                        activeUserId = uid
                        activeDatabase = com.msi.openchat.data.local.OpenChatDatabase.getInstance(context, uid)
                        activeChatRepository = null
                    }
                }
            }
            return activeDatabase!!
        }

    val messageDao: com.msi.openchat.data.local.dao.MessageDao
        get() = database.messageDao()

    val conversationDao: com.msi.openchat.data.local.dao.ConversationDao
        get() = database.conversationDao()

    val chatRepository: ChatRepository
        get() {
            val uid = try { sessionManager.getUserId() } catch (_: Exception) { null }
            val db = database
            if (activeChatRepository == null || activeUserId != uid) {
                synchronized(this) {
                    if (activeChatRepository == null || activeUserId != uid) {
                        activeUserId = uid
                        activeChatRepository = ChatRepository(
                            sessionManager = sessionManager,
                            userRepository = userRepository,
                            messageDao = db.messageDao(),
                            conversationDao = db.conversationDao()
                        )
                    }
                }
            }
            return activeChatRepository!!
        }

    fun onUserLoggedIn(userId: String) {
        synchronized(this) {
            activeUserId = userId
            activeDatabase = com.msi.openchat.data.local.OpenChatDatabase.getInstance(context, userId)
            activeChatRepository = ChatRepository(
                sessionManager = sessionManager,
                userRepository = userRepository,
                messageDao = activeDatabase!!.messageDao(),
                conversationDao = activeDatabase!!.conversationDao()
            )
            com.msi.openchat.core.backend.BackendManager.onUserLoggedIn(context, userId)
        }
    }

    fun onUserLoggedOut() {
        val oldUserId = activeUserId
        synchronized(this) {
            activeUserId = null
            activeChatRepository = null
            activeDatabase = null
            if (!oldUserId.isNullOrBlank()) {
                com.msi.openchat.data.local.OpenChatDatabase.deleteDatabaseForUser(context, oldUserId)
            }
            userRepository.clearCache()
            com.msi.openchat.core.backend.BackendManager.onUserLoggedOut()
            try {
                val imageLoader = coil.Coil.imageLoader(context)
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            } catch (_: Exception) {}
        }
    }

    fun clearAllInMemoryCaches() {
        userRepository.clearCache()
        try {
            coil.Coil.imageLoader(context).memoryCache?.clear()
        } catch (_: Exception) {}
    }

    val callRepository: CallRepository by lazy {
        CallRepository(sessionManager, userRepository)
    }

    val audioRecorderHelper: AudioRecorderHelper by lazy {
        AudioRecorderHelper(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val audioPlayerHelper: AudioPlayerHelper by lazy {
        AudioPlayerHelper()
    }
}
