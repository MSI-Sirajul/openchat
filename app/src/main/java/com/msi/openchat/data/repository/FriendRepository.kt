package com.msi.openchat.data.repository

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ServerValue
import com.msi.openchat.core.backend.BackendManager
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.core.ui.adapter.RelationshipStatus
import com.msi.openchat.core.ui.adapter.UserSearchItem
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

    suspend fun getFriendIds(userId: String): Set<String> = withContext(Dispatchers.IO) {
        try {
            val snapshot = getSingleSnapshot(friendsRef.child(userId)) ?: return@withContext emptySet()
            val ids = mutableSetOf<String>()
            for (child in snapshot.children) {
                val fid = child.key ?: continue
                if (child.getValue(Boolean::class.java) == true) {
                    ids.add(fid)
                }
            }
            ids
        } catch (e: Exception) {
            Log.e(tag, "getFriendIds error: ${e.message}", e)
            emptySet()
        }
    }

    suspend fun getPendingRequestUserIds(userId: String): Pair<Set<String>, Set<String>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = getSingleSnapshot(friendRequestsRef.child(userId)) ?: return@withContext Pair(emptySet(), emptySet())
            val sentPending = mutableSetOf<String>()
            val receivedPending = mutableSetOf<String>()
            for (child in snapshot.children) {
                val senderId = child.child("sender_id").getValue(String::class.java).orEmpty()
                val receiverId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                val status = child.child("status").getValue(String::class.java) ?: "pending"
                if (status == "pending") {
                    if (senderId == userId && receiverId.isNotBlank()) {
                        sentPending.add(receiverId)
                    } else if (receiverId == userId && senderId.isNotBlank()) {
                        receivedPending.add(senderId)
                    }
                }
            }
            Pair(sentPending, receivedPending)
        } catch (e: Exception) {
            Log.e(tag, "getPendingRequestUserIds error: ${e.message}", e)
            Pair(emptySet(), emptySet())
        }
    }

    /**
     * Search users excluding current friends and current user.
     * Enforces backend exclusion of already-friends from search results.
     */
    suspend fun searchNonFriendUsers(query: String): Resource<List<UserSearchItem>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        try {
            val friendIds = getFriendIds(currentUserId)
            val (sentPending, receivedPending) = getPendingRequestUserIds(currentUserId)

            val searchRes = userRepository.searchUsers(query)
            if (searchRes is Resource.Error) {
                return@withContext Resource.Error(searchRes.message)
            }
            val allUsers = (searchRes as? Resource.Success)?.data ?: emptyList()

            // Backend exclusion: Already-friends and current user are completely excluded from results!
            val nonFriends = allUsers.filter { user ->
                user.id != currentUserId && !friendIds.contains(user.id)
            }

            val items = nonFriends.map { user ->
                val status = when {
                    sentPending.contains(user.id) -> RelationshipStatus.REQUESTED
                    receivedPending.contains(user.id) -> RelationshipStatus.REQUESTED
                    else -> RelationshipStatus.NONE
                }
                UserSearchItem(profile = user, status = status)
            }
            Resource.Success(items)
        } catch (e: Exception) {
            Log.e(tag, "searchNonFriendUsers error: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to search users", e)
        }
    }

    suspend fun sendFriendRequest(receiverId: String): Resource<FriendRequest> = withContext(Dispatchers.IO) {
        val senderId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
            ?: return@withContext Resource.Error("Not authenticated")

        if (senderId == receiverId) {
            return@withContext Resource.Error("Cannot send friend request to yourself")
        }

        try {
            // 1. Backend check: reject if already friends
            val friendSnapshot = getSingleSnapshot(friendsRef.child(senderId).child(receiverId))
            if (friendSnapshot != null && friendSnapshot.exists() && friendSnapshot.getValue(Boolean::class.java) == true) {
                return@withContext Resource.Error("Already friends")
            }

            // 2. Backend check: reject duplicate requests server-side (idempotent, return existing status)
            val requestsSnapshot = getSingleSnapshot(friendRequestsRef.child(senderId))
            if (requestsSnapshot != null && requestsSnapshot.exists()) {
                for (child in requestsSnapshot.children) {
                    val sId = child.child("sender_id").getValue(String::class.java).orEmpty()
                    val rId = child.child("receiver_id").getValue(String::class.java).orEmpty()
                    val reqStatus = child.child("status").getValue(String::class.java) ?: "pending"

                    if (sId == senderId && rId == receiverId && reqStatus == "pending") {
                        // Idempotent: return existing pending request
                        return@withContext Resource.Success(
                            FriendRequest(
                                id = child.key ?: child.child("id").getValue(String::class.java).orEmpty(),
                                senderId = sId,
                                receiverId = rId,
                                status = reqStatus,
                                createdAt = child.child("created_at").getValue(String::class.java)
                            )
                        )
                    }

                    if (sId == receiverId && rId == senderId && reqStatus == "pending") {
                        return@withContext Resource.Error("This user has already sent you a friend request")
                    }

                    if ((sId == senderId && rId == receiverId || sId == receiverId && rId == senderId) && reqStatus == "accepted") {
                        return@withContext Resource.Error("Already friends")
                    }
                }
            }

            // 3. Create fresh request
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

            val senderProfile = when (val p = userRepository.getUserProfile(senderId)) {
                is Resource.Success -> p.data
                else -> null
            }
            val senderName = senderProfile?.displayName?.ifBlank { senderProfile.username ?: "Someone" } ?: "Someone"

            val updates = HashMap<String, Any?>()
            updates["/friend_requests/$receiverId/$requestId"] = reqData
            updates["/friend_requests/$senderId/$requestId"] = reqData

            // Realtime push notification node for receiver
            val notifData = HashMap<String, Any?>()
            notifData["type"] = "friend_request"
            notifData["request_id"] = requestId
            notifData["sender_id"] = senderId
            notifData["sender_name"] = senderName
            notifData["timestamp"] = ServerValue.TIMESTAMP
            updates["/notifications/$receiverId/$requestId"] = notifData

            val success = FirebaseManager.database.reference.updateChildren(updates).awaitTask()
            if (success) {
                // Dispatch FCM Push Notification (arrives even when app is closed)
                BackendManager.sendPushNotification(
                    recipientId = receiverId,
                    title = "Friend Request",
                    body = "$senderName sent you a friend request",
                    data = mapOf(
                        "type" to "friend_request",
                        "request_id" to requestId,
                        "sender_id" to senderId,
                        "sender_name" to senderName
                    )
                )

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

            val currentUserProfile = when (val p = userRepository.getUserProfile(currentUserId)) {
                is Resource.Success -> p.data
                else -> null
            }
            val currentUserName = currentUserProfile?.displayName?.ifBlank { currentUserProfile.username ?: "Friend" } ?: "Friend"

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

            // Realtime push notification node for original sender (Channel B: Friend Accepted)
            val notifData = HashMap<String, Any?>()
            notifData["type"] = "friend_accepted"
            notifData["conversation_id"] = conversationId
            notifData["accepter_id"] = currentUserId
            notifData["accepter_name"] = currentUserName
            notifData["timestamp"] = ServerValue.TIMESTAMP
            updates["/notifications/$senderId/${requestId}_accepted"] = notifData

            val success = FirebaseManager.database.reference.updateChildren(updates).awaitTask()
            if (success) {
                // Dispatch FCM Push Notification (Channel B: Friend Accepted, arrives even when app is closed)
                BackendManager.sendPushNotification(
                    recipientId = senderId,
                    title = "Friend Request Accepted",
                    body = "$currentUserName accepted your friend request",
                    data = mapOf(
                        "type" to "friend_accepted",
                        "conversation_id" to conversationId,
                        "accepter_id" to currentUserId,
                        "accepter_name" to currentUserName
                    )
                )

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
