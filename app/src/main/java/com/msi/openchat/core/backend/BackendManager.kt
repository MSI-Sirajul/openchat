package com.msi.openchat.core.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.msi.openchat.core.backend.inbox.ConversationInboxProcessor
import com.msi.openchat.core.backend.outbox.ConversationOutboxQueue
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.local.OpenChatDatabase
import com.msi.openchat.data.local.entity.MessageEntity
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.Message
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Future plug-in interface for bucket-only Nhost Storage.
 * Kept here as a clean placeholder for future bucket-only storage without auth/GraphQL.
 */
interface NhostStorageClient {
    suspend fun uploadFile(file: File, mimeType: String, bucket: String): Resource<String>
    suspend fun uploadBytes(bytes: ByteArray, fileName: String, mimeType: String, bucket: String): Resource<String>
    fun getFileUrl(fileIdOrPath: String, bucket: String? = null): String
}

/**
 * BackendManager
 *
 * WhatsApp/Telegram-Grade Unified Backend Layer for OpenChat:
 * - Firebase is the ONLY backend (Auth, Database/Firestore, FCM/push, and Realtime listeners).
 * - Safe, idempotent initialization with offline persistence enabled.
 * - Single source of truth: StateFlow<ConnectionState> (Connected, Reconnecting, Offline).
 * - Exposes high-level typed messaging, call session coordination, presence, and profile sync.
 * - Media uploads locked with "Media uploads are temporarily unavailable".
 * - WhatsApp-grade outbox/inbox processing with strict (serverTimestamp, seq) deterministic ordering.
 * - Manages persistent connection heartbeat, token refresh, and automatic listener re-subscription.
 */
object BackendManager {
    private const val TAG = "BackendManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var appContext: Context? = null
    private var isInitialized = false

    // Single unified connection state observable across the entire app
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Auth State observable
    private val _currentFirebaseUser = MutableStateFlow<FirebaseUser?>(null)
    val currentFirebaseUser: StateFlow<FirebaseUser?> = _currentFirebaseUser.asStateFlow()

    // Subsystems
    private var outboxQueue: ConversationOutboxQueue? = null
    private var inboxProcessor: ConversationInboxProcessor? = null

    // Active conversation listeners cache for seamless re-subscription upon reconnect
    private val activeConversationListeners = ConcurrentHashMap<String, DatabaseReference>()
    private var firebaseConnectedListener: ValueEventListener? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var heartbeatJob: Job? = null
    private var isNetworkAvailable = false
    private var isFirebaseConnected = false

    // Facade access to underlying Firebase subsystems
    val auth: FirebaseAuth get() = FirebaseManager.auth
    val database: FirebaseDatabase get() = FirebaseManager.database
    val storage: FirebaseStorage get() = FirebaseManager.storage

    /**
     * Initialize Firebase & BackendManager once (safe, idempotent).
     * Safe to invoke from Application, Activity, or Service.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            appContext = context.applicationContext
            isInitialized = true

            // 1. Ensure FirebaseManager singleton is initialized with persistence
            FirebaseManager.init()

            val database = OpenChatDatabase.getInstance(context)
            val sessionManager = SessionManager(context)

            // 2. Setup Inbox Processor
            inboxProcessor = ConversationInboxProcessor(
                context = context,
                messageDao = database.messageDao(),
                conversationDao = database.conversationDao(),
                currentUserIdProvider = { sessionManager.getUserId() ?: FirebaseManager.auth?.currentUser?.uid }
            )

            // 3. Setup Per-Conversation Outbox Queue
            outboxQueue = ConversationOutboxQueue(
                context = context,
                messageDao = database.messageDao(),
                currentUserIdProvider = { sessionManager.getUserId() ?: FirebaseManager.auth?.currentUser?.uid },
                fileUploader = { file, mimeType, bucket, onProgress ->
                    uploadFileInternal(file, mimeType, bucket, onProgress)
                },
                serverDispatcher = { messageEntity ->
                    dispatchMessageToServer(messageEntity)
                }
            )

            // 4. Setup Auth State Observer
            setupAuthStateObserver()

            // 5. Setup Network & Firebase Connection Observers
            setupNetworkMonitoring(context)
            setupFirebaseConnectionObserver()
            startHeartbeatMonitor()

            // 6. Refresh FCM Token in background
            refreshFcmToken()

            Log.i(TAG, "BackendManager initialized successfully with Firebase")
        }
    }

    private fun setupAuthStateObserver() {
        try {
            val listener = FirebaseAuth.AuthStateListener { fbAuth ->
                val user = fbAuth.currentUser
                _currentFirebaseUser.value = user
                Log.d(TAG, "Firebase AuthState changed: uid=${user?.uid}")
            }
            FirebaseManager.auth?.addAuthStateListener(listener)
            authStateListener = listener
            _currentFirebaseUser.value = FirebaseManager.auth?.currentUser
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching AuthStateListener: ${e.message}")
        }
    }

    fun refreshFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    val ctx = appContext
                    val uid = if (ctx != null) SessionManager(ctx).getUserId() else FirebaseManager.auth?.currentUser?.uid
                    if (!uid.isNullOrBlank() && !token.isNullOrBlank()) {
                        FirebaseManager.usersRef.child(uid).child("fcm_token").setValue(token)
                        Log.d(TAG, "FCM token updated successfully: $token")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token refresh notice: ${e.message}")
        }
    }

    fun onUserLoggedOut() {
        synchronized(this) {
            activeConversationListeners.clear()
            outboxQueue?.clearAllQueues()
            _connectionState.value = ConnectionState.Offline
            _currentFirebaseUser.value = null
            Log.i(TAG, "BackendManager listeners, outbox, and session cleared on user logout")
        }
    }

    fun onUserLoggedIn(context: Context, newUserId: String) {
        synchronized(this) {
            val database = OpenChatDatabase.getInstance(context, newUserId)
            val sessionManager = SessionManager(context)

            inboxProcessor = ConversationInboxProcessor(
                context = context,
                messageDao = database.messageDao(),
                conversationDao = database.conversationDao(),
                currentUserIdProvider = { sessionManager.getUserId() ?: FirebaseManager.auth?.currentUser?.uid }
            )

            outboxQueue = ConversationOutboxQueue(
                context = context,
                messageDao = database.messageDao(),
                currentUserIdProvider = { sessionManager.getUserId() ?: FirebaseManager.auth?.currentUser?.uid },
                fileUploader = { file, mimeType, bucket, onProgress ->
                    uploadFileInternal(file, mimeType, bucket, onProgress)
                },
                serverDispatcher = { messageEntity ->
                    dispatchMessageToServer(messageEntity)
                }
            )

            refreshFcmToken()
            recomputeConnectionState()
            Log.i(TAG, "BackendManager re-bound to user: $newUserId")
        }
    }

    fun cancelAllSync() {
        onUserLoggedOut()
    }

    val outbox: ConversationOutboxQueue
        get() = outboxQueue ?: throw IllegalStateException("BackendManager not initialized. Call initialize(context) first.")

    val inbox: ConversationInboxProcessor
        get() = inboxProcessor ?: throw IllegalStateException("BackendManager not initialized. Call initialize(context) first.")

    // =========================================================================
    // 1. Unified Connectivity & Heartbeat
    // =========================================================================

    private fun setupNetworkMonitoring(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network interface connected")
                isNetworkAvailable = true
                recomputeConnectionState()
                onNetworkRestored()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network interface lost")
                isNetworkAvailable = false
                recomputeConnectionState()
            }
        })
    }

    private fun setupFirebaseConnectionObserver() {
        try {
            val connectedRef = FirebaseManager.database.getReference(".info/connected")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    isFirebaseConnected = connected
                    Log.d(TAG, "Firebase .info/connected updated: $connected")
                    recomputeConnectionState()
                    if (connected) {
                        onNetworkRestored()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Firebase connection observer cancelled: ${error.message}")
                }
            }
            connectedRef.addValueEventListener(listener)
            firebaseConnectedListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching Firebase connection observer: ${e.message}")
        }
    }

    private fun recomputeConnectionState() {
        val newState = when {
            !isNetworkAvailable -> ConnectionState.Offline
            isFirebaseConnected -> ConnectionState.Connected
            else -> ConnectionState.Reconnecting("Reconnecting to backend...")
        }
        _connectionState.value = newState
    }

    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(25000L) // 25-second heartbeat
                if (isNetworkAvailable && !isFirebaseConnected) {
                    Log.d(TAG, "Heartbeat: Reconnecting Firebase socket...")
                    restoreSessionIfNeeded()
                }
            }
        }
    }

    private fun onNetworkRestored() {
        scope.launch {
            try {
                restoreSessionIfNeeded()
                // Drain any pending outbox messages across all conversations
                outboxQueue?.drainAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error in onNetworkRestored: ${e.message}")
            }
        }
    }

    private suspend fun restoreSessionIfNeeded() = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        val sessionManager = SessionManager(ctx)
        val uid = sessionManager.getUserId()
        if (uid.isNullOrBlank()) return@withContext

        try {
            FirebaseManager.database.goOnline()
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 2. High-Level Typed Messaging API (WhatsApp/Telegram-Grade)
    // =========================================================================

    /**
     * Optimistically sends a message or media attachment with strict per-conversation
     * monotonic ordering.
     */
    suspend fun sendMessage(
        conversationId: String,
        type: String = "text",
        content: String? = null,
        mediaUrl: String? = null,
        localFilePath: String? = null,
        mediaDurationSeconds: Int = 0,
        mediaFileSize: Long = 0L,
        mediaMimeType: String? = null,
        recipientId: String? = null
    ): MessageEntity {
        return outbox.enqueueMessage(
            conversationId = conversationId,
            type = type,
            content = content,
            mediaUrl = mediaUrl,
            localFilePath = localFilePath,
            mediaDurationSeconds = mediaDurationSeconds,
            mediaFileSize = mediaFileSize,
            mediaMimeType = mediaMimeType,
            recipientId = recipientId
        )
    }

    /**
     * Subscribes to real-time incoming messages for a conversation.
     * Routes automatically through ConversationInboxProcessor for deduplication and strict ordering.
     */
    fun subscribeConversation(conversationId: String): Flow<Message> = callbackFlow {
        val messagesRef = FirebaseManager.messagesRef.child(conversationId)
        activeConversationListeners[conversationId] = messagesRef

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    val msg = parseMessageSnapshot(snapshot, conversationId)
                    inbox.processIncomingMessage(conversationId, msg)
                    trySend(msg)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                scope.launch {
                    val msg = parseMessageSnapshot(snapshot, conversationId)
                    inbox.processIncomingMessage(conversationId, msg)
                    trySend(msg)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Conversation subscription cancelled: ${error.message}")
            }
        }

        messagesRef.addChildEventListener(listener)

        awaitClose {
            messagesRef.removeEventListener(listener)
            activeConversationListeners.remove(conversationId)
        }
    }

    // =========================================================================
    // 3. Media Upload Lockdown (Disabled without UI removal)
    // =========================================================================

    /**
     * Media uploads locked: Returns clear "temporarily unavailable" state.
     * UI and feature code remain fully intact.
     */
    suspend fun uploadFile(
        file: File,
        mimeType: String,
        bucket: String = Constants.BUCKET_IMAGES,
        onProgress: (Float) -> Unit = {}
    ): Resource<String> = withContext(Dispatchers.IO) {
        Resource.Error("Media uploads are temporarily unavailable")
    }

    private suspend fun uploadFileInternal(
        file: File,
        mimeType: String,
        bucket: String,
        onProgress: (Float) -> Unit
    ): String? {
        return null
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        bucket: String = Constants.BUCKET_IMAGES
    ): Resource<String> = withContext(Dispatchers.IO) {
        Resource.Error("Media uploads are temporarily unavailable")
    }

    /**
     * Resolves a storage path or URL to a playable/downloadable URL.
     */
    fun getFileUrl(fileIdOrPath: String, bucket: String? = null): String {
        if (fileIdOrPath.startsWith("http://") || fileIdOrPath.startsWith("https://")) {
            return fileIdOrPath
        }
        val targetBucket = bucket ?: Constants.BUCKET_IMAGES
        return "https://firebasestorage.googleapis.com/v0/b/${FirebaseManager.STORAGE_BUCKET}/o/${targetBucket}%2F${fileIdOrPath}?alt=media"
    }

    // =========================================================================
    // 4. Low-Level Server Dispatch & Acks
    // =========================================================================

    private suspend fun dispatchMessageToServer(message: MessageEntity): ConversationOutboxQueue.ServerAck? =
        withContext(Dispatchers.IO) {
            val conversationId = message.conversationId
            val messageId = message.id
            val senderId = message.senderId
            val nowIso = DateTimeUtils.formatIsoCurrent()

            val targetRecipientId = extractRecipientId(conversationId, senderId)

            val displaySummary = when (message.type) {
                "image" -> "📷 Photo"
                "audio" -> "🎤 Voice message"
                "video" -> "📹 Video"
                "file" -> "📎 Document"
                else -> message.content ?: "Message"
            }

            val bucketId = when (message.type) {
                "image" -> Constants.BUCKET_IMAGES
                "audio" -> Constants.BUCKET_AUDIO
                "video" -> Constants.BUCKET_VIDEOS
                else -> Constants.BUCKET_ATTACHMENTS
            }

            val msgData = HashMap<String, Any?>()
            msgData["id"] = messageId
            msgData["conversation_id"] = conversationId
            msgData["sender_id"] = senderId
            msgData["type"] = message.type
            msgData["content"] = message.content ?: displaySummary
            msgData["media_url"] = message.mediaUrl
            msgData["media_bucket"] = bucketId
            msgData["media_duration_seconds"] = message.mediaDurationSeconds
            msgData["media_file_size"] = message.mediaFileSize
            msgData["seq"] = message.seq
            msgData["status"] = "sent"
            msgData["created_at"] = nowIso
            msgData["server_timestamp"] = ServerValue.TIMESTAMP

            val updates = HashMap<String, Any?>()
            updates["/messages/$conversationId/$messageId"] = msgData

            // Update user conversation nodes
            updates["/conversations/$senderId/$targetRecipientId/last_message_text"] = displaySummary
            updates["/conversations/$senderId/$targetRecipientId/last_message_at"] = nowIso
            updates["/conversations/$senderId/$targetRecipientId/last_message_sender_id"] = senderId

            updates["/conversations/$targetRecipientId/$senderId/last_message_text"] = displaySummary
            updates["/conversations/$targetRecipientId/$senderId/last_message_at"] = nowIso
            updates["/conversations/$targetRecipientId/$senderId/last_message_sender_id"] = senderId
            updates["/conversations/$targetRecipientId/$senderId/unread_count"] = ServerValue.increment(1)

            val success = updateChildrenSuspend(FirebaseManager.database.reference, updates)
            if (success) {
                ConversationOutboxQueue.ServerAck(
                    messageId = messageId,
                    serverTimestampMs = System.currentTimeMillis(),
                    mediaUrl = message.mediaUrl
                )
            } else {
                null
            }
        }

    private suspend fun updateChildrenSuspend(ref: DatabaseReference, updates: Map<String, Any?>): Boolean =
        suspendCancellableCoroutine { cont ->
            ref.updateChildren(updates) { error, _ ->
                if (cont.isActive) {
                    cont.resume(error == null)
                }
            }
        }

    private fun extractRecipientId(conversationId: String, currentUserId: String): String {
        return if (conversationId.startsWith("direct_")) {
            val parts = conversationId.removePrefix("direct_").split("_")
            parts.firstOrNull { it != currentUserId } ?: conversationId
        } else {
            conversationId
        }
    }

    private fun parseMessageSnapshot(snapshot: DataSnapshot, conversationId: String): Message {
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key.orEmpty()
        val senderId = snapshot.child("sender_id").getValue(String::class.java).orEmpty()
        val type = snapshot.child("type").getValue(String::class.java) ?: "text"
        val content = snapshot.child("content").getValue(String::class.java)
        val mediaUrl = snapshot.child("media_url").getValue(String::class.java)
        val mediaDuration = snapshot.child("media_duration_seconds").getValue(Int::class.java) ?: 0
        val mediaSize = snapshot.child("media_file_size").getValue(Long::class.java) ?: 0L
        val status = snapshot.child("status").getValue(String::class.java) ?: "sent"
        val createdAt = snapshot.child("created_at").getValue(String::class.java)
        val seq = snapshot.child("seq").getValue(Long::class.java) ?: 0L
        val serverTs = snapshot.child("server_timestamp").getValue(Long::class.java) ?: 0L

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            type = type,
            content = content,
            mediaUrl = mediaUrl,
            mediaDurationSeconds = mediaDuration,
            mediaFileSize = mediaSize,
            status = status,
            createdAt = createdAt,
            seq = seq,
            serverTimestampMs = serverTs,
            outboxStatus = "sent"
        )
    }

    /**
     * Send push notification via Firebase Realtime Database notifications queue.
     * Triggers recipient's FirebaseMessaging / Notification listener even when offline/closed.
     */
    suspend fun sendPushNotification(
        recipientId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val notifId = UUID.randomUUID().toString()
            val notifData = HashMap<String, Any?>()
            notifData["id"] = notifId
            notifData["title"] = title
            notifData["body"] = body
            notifData["data"] = data
            notifData["timestamp"] = ServerValue.TIMESTAMP

            val updates = mapOf<String, Any?>("/notifications/$recipientId/$notifId" to notifData)
            updateChildrenSuspend(FirebaseManager.database.reference, updates)
        } catch (e: Exception) {
            Log.w(TAG, "Push notification dispatch notice: ${e.message}")
            false
        }
    }

    /**
     * Typed facade API to initiate or update a WebRTC call session.
     */
    suspend fun startCall(
        receiverId: String,
        callType: String = "audio"
    ): Resource<CallSession> {
        return if (callType == "video") {
            com.msi.openchat.feature.videocall.VideoCallSignaling(
                com.msi.openchat.core.di.ServiceLocator.sessionManager
            ).createVideoCall(receiverId, "OpenChat Contact")
        } else {
            com.msi.openchat.core.di.ServiceLocator.callRepository.initiateAudioCall(receiverId)
        }
    }

    /**
     * Resolves avatar URL for a profile or user, returning null if missing so UI falls back to initials.
     */
    fun getAvatarUrl(profile: Profile?): String? {
        return profile?.avatarUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: profile?.avatar?.trim()?.takeIf { it.isNotBlank() }
    }
}
