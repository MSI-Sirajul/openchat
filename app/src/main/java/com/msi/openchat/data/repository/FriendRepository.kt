package com.msi.openchat.data.repository

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.FriendRequest
import com.msi.openchat.data.model.Friendship
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * FriendRepository
 * Encapsulates friend relationship workflows: sending, accepting, declining, and cancelling
 * friend requests, and managing friendships via Firebase Realtime Database.
 */
open class FriendRepository(
    private val sessionManager: SessionManager,
    private val userRepository: UserRepository
) {
    private val tag = "OpenChat_FriendRepo"
    private val friendsRef get() = FirebaseManager.friendsRef
    private val friendRequestsRef get() = FirebaseManager.friendRequestsRef
    private val conversationsRef get() = FirebaseManager.conversationsRef

    private fun getCurrentIsoTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        return sdf.format(Date())
    }

    private suspend fun getSingleSnapshot(query: com.google.firebase.database.Query): DataSnapshot? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (continuation.isActive) continuation.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            query.addListenerForSingleValueEvent(listener)
            continuation.invokeOnCancellation {
                query.removeEventListener(listener)
            }
        }

    private suspend fun Task<*>.awaitTask(): Boolean =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(task.isSuccessful)
                }
            }
        }

    suspend fun sendFriendRequest(receiverId: String): Resource<FriendRequest> = withContext(Dispatchers.IO) {
        val senderId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        if (senderId == receiverId) {
            return@withContext Resource.Error("Cannot send friend request to yourself")
        }

        try {
            val requestId = friendRequestsRef.child(receiverId).push().key
                ?: UUID.randomUUID().toString()
            val now = getCurrentIsoTime()

            val reqData = HashMap<String, Any?>()
            reqData["id"] = requestId
            reqData["sender_id"] = senderId
            reqData["receiver_id"] = receiverId
            reqData["status"] = "pending"
            reqData["created_at"] = now
            reqData["updated_at"] = now

            val updates = HashMap<String, Any?>()
            updates["/friend_requests/$receiverId/$requestId"] = reqData
            updates["/friend_requests/$senderId/$requestId"] = reqData

            val success = FirebaseManager.database.reference.updateChildren(updates).awaitTask()
            if (success) {
                Resource.Success(
                    FriendRequest(
                        id = requestId,
                        senderId = senderId,
                        receiverId = receiverId,
                        status = "pending",
                        createdAt = now
                    )
                )
            } else {
                Resource.Error("Failed to send friend request")
            }
        } catch (e: Exception) {
            Log.e(tag, "sendFriendRequest error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to send friend request", e)
        }
    }

    suspend fun acceptFriendRequest(requestId: String): Resource<String> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val reqSnapshot = getSingleSnapshot(friendRequestsRef.child(currentUserId).child(requestId))
                ?: return@withContext Resource.Error("Friend request not found")

            val senderId = reqSnapshot.child("sender_id").getValue(String::class.java).orEmpty()
            if (senderId.isBlank()) return@withContext Resource.Error("Invalid request data")

            val now = getCurrentIsoTime()
            val conversationId = if (currentUserId < senderId) {
                "direct_${currentUserId}_${senderId}"
            } else {
                "direct_${senderId}_${currentUserId}"
            }

            // Create initial conversation nodes
            val convData1 = HashMap<String, Any?>()
            convData1["id"] = conversationId
            convData1["type"] = "direct"
            convData1["participant1_id"] = currentUserId
            convData1["participant2_id"] = senderId
            convData1["other_user_id"] = senderId
            convData1["last_message_text"] = "You are now connected!"
            convData1["last_message_at"] = now
            convData1["unread_count"] = 0

            val convData2 = HashMap<String, Any?>()
            convData2["id"] = conversationId
            convData2["type"] = "direct"
            convData2["participant1_id"] = senderId
            convData2["participant2_id"] = currentUserId
            convData2["other_user_id"] = currentUserId
            convData2["last_message_text"] = "You are now connected!"
            convData2["last_message_at"] = now
            convData2["unread_count"] = 0

            val updates = HashMap<String, Any?>()
            // Mark friendship bidirectionally
            updates["/friends/$currentUserId/$senderId"] = true
            updates["/friends/$senderId/$currentUserId"] = true
            // Update request status
            updates["/friend_requests/$currentUserId/$requestId/status"] = "accepted"
            updates["/friend_requests/$senderId/$requestId/status"] = "accepted"
            // Set up conversations
            updates["/conversations/$currentUserId/$senderId"] = convData1
            updates["/conversations/$senderId/$currentUserId"] = convData2

            val success = FirebaseManager.database.reference.updateChildren(updates).awaitTask()
            if (success) {
                Resource.Success(conversationId)
            } else {
                Resource.Error("Failed to accept friend request")
            }
        } catch (e: Exception) {
            Log.e(tag, "acceptFriendRequest error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to accept friend request", e)
        }
    }

    suspend fun declineFriendRequest(requestId: String): Resource<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val success = friendRequestsRef.child(currentUserId).child(requestId).child("status")
                .setValue("declined").awaitTask()
            if (success) Resource.Success(Unit)
            else Resource.Error("Failed to decline friend request")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to decline friend request", e)
        }
    }

    suspend fun cancelFriendRequest(requestId: String): Resource<Unit> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val success = friendRequestsRef.child(currentUserId).child(requestId).removeValue().awaitTask()
            if (success) Resource.Success(Unit)
            else Resource.Error("Failed to cancel friend request")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to cancel friend request", e)
        }
    }

    suspend fun getFriends(): Resource<List<Friendship>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val snapshot = getSingleSnapshot(friendsRef.child(currentUserId))
                ?: return@withContext Resource.Success(emptyList())

            val friendIds = mutableListOf<String>()
            for (child in snapshot.children) {
                val fid = child.key ?: continue
                if (child.getValue(Boolean::class.java) == true) {
                    friendIds.add(fid)
                }
            }

            if (friendIds.isEmpty()) {
                return@withContext Resource.Success(emptyList())
            }

            val profiles = userRepository.getUserProfiles(friendIds)
            val list = friendIds.map { fid ->
                Friendship(
                    id = "friend_${currentUserId}_${fid}",
                    user1Id = currentUserId,
                    user2Id = fid,
                    friendProfile = profiles[fid]
                )
            }
            Resource.Success(list)
        } catch (e: Exception) {
            Log.e(tag, "getFriends error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to load friends", e)
        }
    }

    suspend fun getReceivedRequests(): Resource<List<FriendRequest>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val snapshot = getSingleSnapshot(friendRequestsRef.child(currentUserId))
                ?: return@withContext Resource.Success(emptyList())

            val requests = mutableListOf<FriendRequest>()
            val senderIds = mutableListOf<String>()

            for (child in snapshot.children) {
                val id = child.child("id").getValue(String::class.java) ?: child.key.orEmpty()
                val senderId = child.child("sender_id").getValue(String::class.java).orEmpty()
                val receiverId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                val status = child.child("status").getValue(String::class.java) ?: "pending"
                val createdAt = child.child("created_at").getValue(String::class.java)

                if (receiverId == currentUserId && status == "pending") {
                    requests.add(
                        FriendRequest(
                            id = id,
                            senderId = senderId,
                            receiverId = receiverId,
                            status = status,
                            createdAt = createdAt
                        )
                    )
                    senderIds.add(senderId)
                }
            }

            if (requests.isEmpty()) {
                return@withContext Resource.Success(emptyList())
            }

            val profiles = userRepository.getUserProfiles(senderIds)
            val populated = requests.map { req ->
                req.copy(sender = profiles[req.senderId])
            }
            Resource.Success(populated)
        } catch (e: Exception) {
            Log.e(tag, "getReceivedRequests error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to load received requests", e)
        }
    }

    suspend fun getSentRequests(): Resource<List<FriendRequest>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val snapshot = getSingleSnapshot(friendRequestsRef.child(currentUserId))
                ?: return@withContext Resource.Success(emptyList())

            val requests = mutableListOf<FriendRequest>()
            val receiverIds = mutableListOf<String>()

            for (child in snapshot.children) {
                val id = child.child("id").getValue(String::class.java) ?: child.key.orEmpty()
                val senderId = child.child("sender_id").getValue(String::class.java).orEmpty()
                val receiverId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                val status = child.child("status").getValue(String::class.java) ?: "pending"
                val createdAt = child.child("created_at").getValue(String::class.java)

                if (senderId == currentUserId && status == "pending") {
                    requests.add(
                        FriendRequest(
                            id = id,
                            senderId = senderId,
                            receiverId = receiverId,
                            status = status,
                            createdAt = createdAt
                        )
                    )
                    receiverIds.add(receiverId)
                }
            }

            if (requests.isEmpty()) {
                return@withContext Resource.Success(emptyList())
            }

            val profiles = userRepository.getUserProfiles(receiverIds)
            val populated = requests.map { req ->
                req.copy(receiver = profiles[req.receiverId])
            }
            Resource.Success(populated)
        } catch (e: Exception) {
            Log.e(tag, "getSentRequests error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to load sent requests", e)
        }
    }
}
