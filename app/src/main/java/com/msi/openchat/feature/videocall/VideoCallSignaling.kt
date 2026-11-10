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
 * Typed signaling wrapper for Video Calling using Firebase Realtime Database.
 * Handles room creation, offer/answer, ICE candidates, status synchronization, and hangup.
 */
class VideoCallSignaling(
    private val sessionManager: SessionManager
) {
    private val tag = "OpenChat_VideoSignaling"
    private val callsRef get() = FirebaseManager.callsRef
    private val callSignalingRef get() = FirebaseManager.callSignalingRef
    private val usersRef get() = FirebaseManager.usersRef

    /**
     * Initiates a new video call session
     */
    suspend fun createVideoCall(
        receiverId: String,
        receiverName: String
    ): Resource<CallSession> = withContext(Dispatchers.IO) {
        val callerId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("User not authenticated")

        try {
            val callId = callsRef.push().key ?: UUID.randomUUID().toString()
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

            val incomingData = HashMap<String, Any?>()
            incomingData["call_id"] = callId
            incomingData["caller_id"] = callerId
            incomingData["caller_name"] = callerName
            incomingData["call_type"] = "video"

            val updates = HashMap<String, Any?>()
            updates["/calls/$callId"] = callData
            updates["/users/$receiverId/incoming_call"] = incomingData

            suspendCancellableCoroutine<Resource<CallSession>> { continuation ->
                FirebaseManager.database.reference.updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        callsRef.child(callId).child("status").onDisconnect().setValue("ended")
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
     * Send SDP offer/answer, ICE candidate, or bye signal
     */
    suspend fun sendSignaling(
        callId: String,
        receiverId: String,
        type: String,
        payload: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        val senderId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("User not authenticated")

        try {
            val msgId = callSignalingRef.child(callId).push().key ?: UUID.randomUUID().toString()
            val msgData = HashMap<String, Any?>()
            msgData["id"] = msgId
            msgData["call_id"] = callId
            msgData["sender_id"] = senderId
            msgData["receiver_id"] = receiverId
            msgData["type"] = type
            msgData["payload"] = payload
            msgData["created_at"] = System.currentTimeMillis().toString()

            suspendCancellableCoroutine<Resource<Unit>> { continuation ->
                callSignalingRef.child(callId).child(msgId).setValue(msgData)
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
     * Stream incoming signaling messages (SDP offer, answer, ICE candidates) for this call
     */
    fun listenSignaling(callId: String, currentUserId: String): Flow<CallSignalingMessage> = callbackFlow {
        val query = callSignalingRef.child(callId)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val receiverId = snapshot.child("receiver_id").getValue(String::class.java).orEmpty()
                    val senderId = snapshot.child("sender_id").getValue(String::class.java).orEmpty()
                    // Accept if directed to us and not sent by us
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
     * Observe call session status changes (e.g. calling -> ringing -> connected -> ended)
     */
    fun listenCallSession(callId: String): Flow<CallSession?> = callbackFlow {
        val ref = callsRef.child(callId)
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
     * Updates call session status (e.g. ringing, connected, declined, busy, ended)
     */
    suspend fun updateCallStatus(
        callId: String,
        status: String,
        endReason: String? = null,
        durationSeconds: Int = 0
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = HashMap<String, Any?>()
            updates["status"] = status
            if (endReason != null) updates["end_reason"] = endReason
            if (durationSeconds > 0) updates["duration_seconds"] = durationSeconds
            if (status == "connected") updates["connected_at"] = System.currentTimeMillis().toString()
            if (status == "ended" || status == "declined" || status == "busy") {
                updates["ended_at"] = System.currentTimeMillis().toString()
            }

            suspendCancellableCoroutine<Resource<Unit>> { continuation ->
                callsRef.child(callId).updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) continuation.resume(Resource.Success(Unit))
                    else continuation.resume(Resource.Error(task.exception?.message ?: "Failed to update call status"))
                }
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to update status", e)
        }
    }

    /**
     * Clears incoming call node for a user
     */
    fun clearIncomingCall(userId: String) {
        if (userId.isNotBlank()) {
            usersRef.child(userId).child("incoming_call").removeValue()
        }
    }

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
