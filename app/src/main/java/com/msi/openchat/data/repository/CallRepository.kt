package com.msi.openchat.data.repository

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.CallSignalingMessage
import com.msi.openchat.data.model.IceServerConfig
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

/**
 * CallRepository
 * Isolated 1-to-1 AUDIO WebRTC calling session coordination and signaling
 * using dedicated Firebase Realtime Database paths:
 * - /audioCalls/{callId}
 * - /audioCallSignaling/{callId}
 * - /users/{receiverId}/incomingAudioCall
 *
 * Video calls are handled strictly by VideoCallSignaling on /videoCalls and /videoCallSignaling.
 */
class CallRepository(
    private val sessionManager: SessionManager,
    private val userRepository: UserRepository
) {
    private val tag = "OpenChat_AudioCallRepo"
    private val audioCallsRef get() = FirebaseManager.audioCallsRef
    private val audioCallSignalingRef get() = FirebaseManager.audioCallSignalingRef

    suspend fun initiateAudioCall(receiverId: String): Resource<CallSession> =
        initiateCall(receiverId = receiverId, callType = "audio")

    suspend fun initiateCall(receiverId: String, callType: String = "audio"): Resource<CallSession> =
        withContext(Dispatchers.IO) {
            if (callType != "audio") {
                Log.e(tag, "Blocked non-audio call ($callType) in audio CallRepository")
                return@withContext Resource.Error("CallRepository handles audio calls only")
            }

            val callerId = FirebaseManager.auth.currentUser?.uid ?: sessionManager.getUserId()
                ?: return@withContext Resource.Error("Not authenticated")

            try {
                val callId = com.msi.openchat.service.call.CallSessionGuard.generateAudioCallId()
                val nowTime = System.currentTimeMillis().toString()

                val callData = HashMap<String, Any?>()
                callData["id"] = callId
                callData["caller_id"] = callerId
                callData["receiver_id"] = receiverId
                callData["call_type"] = "audio"
                callData["status"] = "calling"
                callData["created_at"] = nowTime
                callData["started_at"] = nowTime

                val callerProfile = when (val p = userRepository.getCurrentUserProfile()) {
                    is Resource.Success -> p.data
                    else -> null
                }
                val callerName = callerProfile?.displayName?.ifBlank { callerProfile.username } ?: "OpenChat"

                val incomingAudioData = HashMap<String, Any?>()
                incomingAudioData["call_id"] = callId
                incomingAudioData["caller_id"] = callerId
                incomingAudioData["caller_name"] = callerName
                incomingAudioData["call_type"] = "audio"
                incomingAudioData["created_at"] = nowTime

                val updates = HashMap<String, Any?>()
                updates["/audioCalls/$callId"] = callData
                updates["/users/$receiverId/incomingAudioCall"] = incomingAudioData

                val receiverProfile = when (val p = userRepository.getUserProfile(receiverId)) {
                    is Resource.Success -> p.data
                    else -> null
                }

                suspendCancellableCoroutine<Resource<CallSession>> { continuation ->
                    FirebaseManager.database.reference.updateChildren(updates).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            FirebaseManager.usersRef.child(receiverId).child("incomingAudioCall")
                                .onDisconnect().removeValue()
                            continuation.resume(
                                Resource.Success(
                                    CallSession(
                                        id = callId,
                                        callerId = callerId,
                                        receiverId = receiverId,
                                        callType = "audio",
                                        status = "calling",
                                        startedAt = nowTime,
                                        callerProfile = callerProfile,
                                        receiverProfile = receiverProfile
                                    )
                                )
                            )
                        } else {
                            continuation.resume(Resource.Error(task.exception?.message ?: "Failed to initiate audio call"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "initiateAudioCall error: ${e.message}", e)
                Resource.Error(e.message ?: "Failed to initiate audio call", e)
            }
        }

    suspend fun updateAudioCallStatus(
        callId: String,
        status: String,
        durationSeconds: Int = 0,
        endReason: String? = null
    ) = updateCallStatus(callId, status, durationSeconds, endReason)

    suspend fun updateCallStatus(
        callId: String,
        status: String,
        durationSeconds: Int = 0,
        endReason: String? = null
    ) = withContext(Dispatchers.IO) {
        if (callId.isBlank() || com.msi.openchat.service.call.CallSessionGuard.isVideoCall(callId, null)) {
            Log.w(tag, "Ignoring updateCallStatus for non-audio callId=$callId")
            return@withContext
        }

        val updates = HashMap<String, Any?>()
        updates["status"] = status
        if (durationSeconds > 0) {
            updates["duration_seconds"] = durationSeconds
        }
        if (!endReason.isNullOrBlank()) {
            updates["end_reason"] = endReason
        }
        if (status in listOf("declined", "missed", "ended", "failed", "busy")) {
            updates["ended_at"] = System.currentTimeMillis().toString()
        }

        audioCallsRef.child(callId).updateChildren(updates)

        if (status in listOf("declined", "missed", "ended", "failed", "busy")) {
            audioCallsRef.child(callId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val receiverId = snapshot.child("receiver_id").getValue(String::class.java)
                    if (!receiverId.isNullOrBlank()) {
                        FirebaseManager.usersRef.child(receiverId).child("incomingAudioCall").removeValue()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    fun clearIncomingAudioCall(userId: String) {
        if (userId.isNotBlank()) {
            FirebaseManager.usersRef.child(userId).child("incomingAudioCall").removeValue()
        }
    }

    suspend fun sendSignaling(
        callId: String,
        receiverId: String,
        type: String,
        payload: String
    ): Resource<Unit> = sendSignalingMessage(callId, receiverId, type, payload)

    suspend fun sendSignalingMessage(
        callId: String,
        receiverId: String,
        type: String,
        payload: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        if (callId.isBlank() || com.msi.openchat.service.call.CallSessionGuard.isVideoCall(callId, null)) {
            return@withContext Resource.Error("Invalid audio callId: $callId")
        }

        val senderId = FirebaseManager.auth.currentUser?.uid ?: sessionManager.getUserId()
            ?: return@withContext Resource.Error("Not authenticated")

        val messageId = audioCallSignalingRef.child(callId).push().key ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis().toString()

        val data = HashMap<String, Any?>()
        data["id"] = messageId
        data["call_id"] = callId
        data["sender_id"] = senderId
        data["receiver_id"] = receiverId
        data["type"] = type
        data["payload"] = payload
        data["created_at"] = now

        suspendCancellableCoroutine<Resource<Unit>> { continuation ->
            audioCallSignalingRef.child(callId).child(messageId).setValue(data).addOnCompleteListener { task ->
                if (task.isSuccessful) continuation.resume(Resource.Success(Unit))
                else continuation.resume(Resource.Error(task.exception?.message ?: "Signaling send failed"))
            }
        }
    }

    suspend fun getSignalingMessages(
        callId: String,
        sinceTimestamp: String? = null
    ): Resource<List<CallSignalingMessage>> = withContext(Dispatchers.IO) {
        if (callId.isBlank() || com.msi.openchat.service.call.CallSessionGuard.isVideoCall(callId, null)) {
            return@withContext Resource.Success(emptyList())
        }
        suspendCancellableCoroutine<Resource<List<CallSignalingMessage>>> { continuation ->
            audioCallSignalingRef.child(callId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<CallSignalingMessage>()
                    for (child in snapshot.children) {
                        val id = child.child("id").getValue(String::class.java) ?: child.key.orEmpty()
                        val senderId = child.child("sender_id").getValue(String::class.java).orEmpty()
                        val receiverId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                        val type = child.child("type").getValue(String::class.java).orEmpty()
                        val payload = child.child("payload").getValue(String::class.java).orEmpty()
                        val createdAt = child.child("created_at").getValue(String::class.java)

                        list.add(
                            CallSignalingMessage(
                                id = id,
                                callId = callId,
                                senderId = senderId,
                                receiverId = receiverId,
                                type = type,
                                payload = payload,
                                createdAt = createdAt
                            )
                        )
                    }
                    continuation.resume(Resource.Success(list))
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(Resource.Error(error.message))
                }
            })
        }
    }

    fun observeSignalingMessages(callId: String): Flow<CallSignalingMessage> = callbackFlow {
        if (callId.isBlank() || com.msi.openchat.service.call.CallSessionGuard.isVideoCall(callId, null)) {
            close()
            return@callbackFlow
        }

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key.orEmpty()
                val senderId = snapshot.child("sender_id").getValue(String::class.java).orEmpty()
                val receiverId = snapshot.child("receiver_id").getValue(String::class.java).orEmpty()
                val type = snapshot.child("type").getValue(String::class.java).orEmpty()
                val payload = snapshot.child("payload").getValue(String::class.java).orEmpty()
                val createdAt = snapshot.child("created_at").getValue(String::class.java)

                trySend(
                    CallSignalingMessage(
                        id = id,
                        callId = callId,
                        senderId = senderId,
                        receiverId = receiverId,
                        type = type,
                        payload = payload,
                        createdAt = createdAt
                    )
                )
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(tag, "observeSignalingMessages cancelled for $callId: ${error.message}")
            }
        }

        val ref = audioCallSignalingRef.child(callId)
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeCallSession(callId: String): Flow<CallSession> = callbackFlow {
        if (callId.isBlank() || com.msi.openchat.service.call.CallSessionGuard.isVideoCall(callId, null)) {
            close()
            return@callbackFlow
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val id = snapshot.child("id").getValue(String::class.java) ?: callId
                    val callerId = snapshot.child("caller_id").getValue(String::class.java).orEmpty()
                    val receiverId = snapshot.child("receiver_id").getValue(String::class.java).orEmpty()
                    val callType = snapshot.child("call_type").getValue(String::class.java) ?: "audio"
                    val status = snapshot.child("status").getValue(String::class.java) ?: "calling"
                    val startedAt = snapshot.child("started_at").getValue(String::class.java)
                    val endedAt = snapshot.child("ended_at").getValue(String::class.java)

                    trySend(
                        CallSession(
                            id = id,
                            callerId = callerId,
                            receiverId = receiverId,
                            callType = callType,
                            status = status,
                            startedAt = startedAt,
                            endedAt = endedAt
                        )
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(tag, "observeCallSession cancelled for $callId: ${error.message}")
            }
        }

        val ref = audioCallsRef.child(callId)
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private suspend fun fetchCallsSnapshot(ref: com.google.firebase.database.DatabaseReference, limit: Int): DataSnapshot? {
        return suspendCancellableCoroutine { continuation ->
            ref.limitToLast(limit).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) = continuation.resume(snap)
                override fun onCancelled(error: DatabaseError) = continuation.resume(null)
            })
        }
    }

    suspend fun getCallHistory(): Resource<List<CallSession>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        val audioSnap = fetchCallsSnapshot(audioCallsRef, 40)
        val videoSnap = fetchCallsSnapshot(FirebaseManager.videoCallsRef, 40)
        val legacySnap = fetchCallsSnapshot(FirebaseManager.callsRef, 20)

        val callsById = LinkedHashMap<String, CallSession>()
        val userIdsToFetch = mutableSetOf<String>()

        fun collectFrom(snap: DataSnapshot?, defaultType: String) {
            if (snap == null) return
            for (child in snap.children) {
                val callerId = child.child("caller_id").getValue(String::class.java).orEmpty()
                val receiverId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                if (callerId == currentUserId || receiverId == currentUserId) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key.orEmpty()
                    val callType = child.child("call_type").getValue(String::class.java) ?: defaultType
                    val status = child.child("status").getValue(String::class.java) ?: "ended"
                    val startedAt = child.child("started_at").getValue(String::class.java)
                    val endedAt = child.child("ended_at").getValue(String::class.java)
                    val duration = child.child("duration_seconds").getValue(Int::class.java) ?: 0

                    callsById[id] = CallSession(
                        id = id,
                        callerId = callerId,
                        receiverId = receiverId,
                        callType = callType,
                        status = status,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        durationSeconds = duration
                    )
                    userIdsToFetch.add(if (callerId == currentUserId) receiverId else callerId)
                }
            }
        }

        collectFrom(audioSnap, "audio")
        collectFrom(videoSnap, "video")
        collectFrom(legacySnap, "audio")

        val profiles = if (userIdsToFetch.isNotEmpty()) {
            userRepository.getUserProfiles(userIdsToFetch.toList())
        } else {
            emptyMap()
        }

        val populated = callsById.values
            .sortedByDescending { it.startedAt?.toLongOrNull() ?: 0L }
            .map { call ->
                val otherId = if (call.callerId == currentUserId) call.receiverId else call.callerId
                call.copy(
                    callerProfile = if (call.callerId == currentUserId) null else profiles[otherId],
                    receiverProfile = if (call.callerId == currentUserId) profiles[otherId] else null
                )
            }

        Resource.Success(populated)
    }

    suspend fun getIceServers(): List<IceServerConfig> = listOf(
        IceServerConfig(urls = listOf("stun:stun.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun1.l.google.com:19302")),
        IceServerConfig(urls = listOf("stun:stun2.l.google.com:19302"))
    )
}
