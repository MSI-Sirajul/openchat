package com.msi.openchat.feature.videocall

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.CallSignalingMessage
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
 * Isolated signaling wrapper for VIDEO Calling using dedicated Firebase Realtime Database paths:
 * - /videoCalls/{callId}
 * - /videoCallSignaling/{callId}
 * - /users/{receiverId}/incomingVideoCall
 *
 * Audio calls are handled strictly by CallRepository on /audioCalls and /audioCallSignaling.
 */
class VideoCallSignaling(
    private val sessionManager: SessionManager
) {
    private val tag = "OpenChat_VideoSignaling"
    private val videoCallsRef get() = FirebaseManager.videoCallsRef
    private val videoCallSignalingRef get() = FirebaseManager.videoCallSignalingRef
    private val usersRef get() = FirebaseManager.usersRef

    /**
     * Initiates a new video call session strictly under /videoCalls and /users/{receiverId}/incomingVideoCall
     */
    suspend fun createVideoCall(
        receiverId: String,
        receiverName: String
    ): Resource<CallSession> = withContext(Dispatchers.IO) {
        val callerId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("User not authenticated")

        try {
            val pushKey = videoCallsRef.push().key ?: UUID.randomUUID().toString()
            val callId = "vid_$pushKey"
            val nowTime = System.currentTimeMillis().toString()

            val callerProfile = try {
                val p = com.msi.openchat.core.di.ServiceLocator.userRepository.getCurrentUserProfile()
                if (p is Resource.Success) p.data else null
            } catch (_: Exception) { null }

            val callerName = callerProfile?.displayName?.ifBlank { callerProfile.username } ?: "OpenChat User"

            val callData = HashMap<String, Any?>()
            callData["id"] = callId
            callData["caller_id"] = callerId
            callData["receiver_id"] = receiverId
            callData["call_type"] = "video"
            callData["status"] = "calling"
            callData["created_at"] = nowTime
            callData["started_at"] = nowTime

            val incomingVideoData = HashMap<String, Any?>()
            incomingVideoData["call_id"] = callId
            incomingVideoData["caller_id"] = callerId
            incomingVideoData["caller_name"] = callerName
            incomingVideoData["call_type"] = "video"
            incomingVideoData["created_at"] = nowTime

            val updates = HashMap<String, Any?>()
            updates["/videoCalls/$callId"] = callData
            updates["/users/$receiverId/incomingVideoCall"] = incomingVideoData

            suspendCancellableCoroutine<Resource<CallSession>> { continuation ->
                FirebaseManager.database.reference.updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        videoCallsRef.child(callId).child("status").onDisconnect().setValue("ended")
                        usersRef.child(receiverId).child("incomingVideoCall").onDisconnect().removeValue()
                        continuation.resume(
                            Resource.Success(
                                CallSession(
                                    id = callId,
                                    callerId = callerId,
                                    receiverId = receiverId,
                                    callType = "video",
                                    status = "calling",
                                    startedAt = nowTime,
                                    callerProfile = callerProfile
                                )
                            )
                        )
                    } else {
                        continuation.resume(Resource.Error(task.exception?.message ?: "Failed to create video call room"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "createVideoCall error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to initiate video call", e)
        }
    }

    /**
     * Send SDP offer/answer, ICE candidate, or bye signal on /videoCallSignaling/{callId}
     */
    suspend fun sendSignaling(
        callId: String,
        receiverId: String,
        type: String,
        payload: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        if (callId.isBlank() || callId.startsWith("aud_")) {
            return@withContext Resource.Error("Invalid video callId: $callId")
        }

        val senderId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("User not authenticated")

        try {
            val msgId = videoCallSignalingRef.child(callId).push().key ?: UUID.randomUUID().toString()
            val msgData = HashMap<String, Any?>()
            msgData["id"] = msgId
            msgData["call_id"] = callId
            msgData["sender_id"] = senderId
            msgData["receiver_id"] = receiverId
            msgData["type"] = type
            msgData["payload"] = payload
            msgData["created_at"] = System.currentTimeMillis().toString()

            suspendCancellableCoroutine<Resource<Unit>> { continuation ->
                videoCallSignalingRef.child(callId).child(msgId).setValue(msgData)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            continuation.resume(Resource.Success(Unit))
                        } else {
                            continuation.resume(Resource.Error(task.exception?.message ?: "Failed to send signaling"))
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(tag, "sendSignaling error: ${e.message}", e)
            Resource.Error(e.message ?: "Signaling transmission failed", e)
        }
    }

    /**
     * Stream incoming video signaling messages (SDP offer, answer, ICE candidates) for this call
     */
    fun listenSignaling(callId: String, currentUserId: String): Flow<CallSignalingMessage> = callbackFlow {
        if (callId.isBlank() || callId.startsWith("aud_")) {
            close()
            return@callbackFlow
        }

        val query = videoCallSignalingRef.child(callId)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val receiverId = snapshot.child("receiver_id").getValue(String::class.java).orEmpty()
                    val senderId = snapshot.child("sender_id").getValue(String::class.java).orEmpty()
                    if (receiverId == currentUserId && senderId != currentUserId) {
                        val type = snapshot.child("type").getValue(String::class.java).orEmpty()
                        val payload = snapshot.child("payload").getValue(String::class.java).orEmpty()
                        val msg = CallSignalingMessage(
                            id = snapshot.key,
                            callId = callId,
                            senderId = senderId,
                            receiverId = receiverId,
                            type = type,
                            payload = payload,
                            createdAt = snapshot.child("created_at").getValue(String::class.java)
                        )
                        trySend(msg)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing signaling snapshot: ${e.message}")
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }

        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    /**
     * Observe video call session status changes under /videoCalls/{callId}
     */
    fun listenCallSession(callId: String): Flow<CallSession?> = callbackFlow {
        if (callId.isBlank() || callId.startsWith("aud_")) {
            close()
            return@callbackFlow
        }

        val ref = videoCallsRef.child(callId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                try {
                    val session = CallSession(
                        id = snapshot.child("id").getValue(String::class.java) ?: callId,
                        callerId = snapshot.child("caller_id").getValue(String::class.java).orEmpty(),
                        receiverId = snapshot.child("receiver_id").getValue(String::class.java).orEmpty(),
                        callType = snapshot.child("call_type").getValue(String::class.java) ?: "video",
                        status = snapshot.child("status").getValue(String::class.java) ?: "calling",
                        startedAt = snapshot.child("started_at").getValue(String::class.java),
                        connectedAt = snapshot.child("connected_at").getValue(String::class.java),
                        endedAt = snapshot.child("ended_at").getValue(String::class.java),
                        durationSeconds = snapshot.child("duration_seconds").getValue(Int::class.java) ?: 0,
                        endReason = snapshot.child("end_reason").getValue(String::class.java)
                    )
                    trySend(session)
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing call session: ${e.message}")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Updates video call session status and clears /users/{receiverId}/incomingVideoCall on termination
     */
    suspend fun updateCallStatus(
        callId: String,
        status: String,
        endReason: String? = null,
        durationSeconds: Int = 0
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        if (callId.isBlank() || callId.startsWith("aud_")) {
            return@withContext Resource.Error("Ignoring non-video callId: $callId")
        }

        try {
            val updates = HashMap<String, Any?>()
            updates["status"] = status
            if (endReason != null) updates["end_reason"] = endReason
            if (durationSeconds > 0) updates["duration_seconds"] = durationSeconds
            if (status == "connected") updates["connected_at"] = System.currentTimeMillis().toString()
            val isTerminal = status in listOf("ended", "declined", "busy", "missed", "failed")
            if (isTerminal) {
                updates["ended_at"] = System.currentTimeMillis().toString()
            }

            if (isTerminal) {
                videoCallsRef.child(callId).child("receiver_id")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val receiverId = snapshot.getValue(String::class.java)
                            if (!receiverId.isNullOrBlank()) {
                                clearIncomingVideoCall(receiverId)
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }

            suspendCancellableCoroutine<Resource<Unit>> { continuation ->
                videoCallsRef.child(callId).updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) continuation.resume(Resource.Success(Unit))
                    else continuation.resume(Resource.Error(task.exception?.message ?: "Failed to update video call status"))
                }
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to update status", e)
        }
    }

    /**
     * Clears incoming VIDEO call node strictly for a user (never touches audio)
     */
    fun clearIncomingVideoCall(userId: String) {
        if (userId.isNotBlank()) {
            usersRef.child(userId).child("incomingVideoCall").removeValue()
        }
    }

    fun clearIncomingCall(userId: String) = clearIncomingVideoCall(userId)

    /**
     * Auto decline with "busy" reason when user is already in another call
     */
    suspend fun notifyBusy(callId: String, callerId: String) {
        updateCallStatus(callId, "busy", "Receiver is busy on another call")
        sendSignaling(callId, callerId, "bye", "{\"reason\":\"busy\"}")
    }

    /**
     * Notify decline
     */
    suspend fun notifyDecline(callId: String, callerId: String) {
        updateCallStatus(callId, "declined", "Call declined by user")
        sendSignaling(callId, callerId, "bye", "{\"reason\":\"declined\"}")
    }
}
