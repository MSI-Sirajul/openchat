package com.msi.openchat.data.repository

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * UserRepository
 * Manages user profile retrieval, caching, updates, directory search, and presence
 * using Firebase Realtime Database.
 */
class UserRepository(
    private val sessionManager: SessionManager
) {
    private val tag = "OpenChat_UserRepo"
    private val profileCache = ConcurrentHashMap<String, Profile>()

    private val usersRef get() = FirebaseManager.usersRef
    private val usernamesRef get() = FirebaseManager.usernamesRef
    private val phoneNumbersRef get() = FirebaseManager.phoneNumbersRef

    suspend fun getCurrentUserProfile(): Resource<Profile> = withContext(Dispatchers.IO) {
        val uid = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            return@withContext Resource.Error("User not logged in")
        }
        getUserProfile(uid)
    }

    suspend fun getUserProfile(userId: String): Resource<Profile> = withContext(Dispatchers.IO) {
        profileCache[userId]?.let { return@withContext Resource.Success(it) }

        try {
            val profile = fetchProfileFromFirebase(userId)
            if (profile != null) {
                profileCache[userId] = profile
                Resource.Success(profile)
            } else {
                // If this is the current logged in user, automatically bootstrap profile
                val currentUid = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
                if (userId == currentUid) {
                    val bootstrapped = ensureCurrentUserProfile(userId)
                    profileCache[userId] = bootstrapped
                    Resource.Success(bootstrapped)
                } else {
                    val fallback = Profile(
                        id = userId,
                        displayName = "User",
                        username = "user_${userId.take(6)}",
                        statusMessage = "Available",
                        onlineStatus = "offline"
                    )
                    Resource.Success(fallback)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching user profile $userId: ${e.message}")
            Resource.Error(e.message ?: "Failed to fetch profile", e)
        }
    }

    private suspend fun fetchProfileFromFirebase(userId: String): Profile? =
        suspendCancellableCoroutine { continuation ->
            usersRef.child(userId).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val profile = parseProfileSnapshot(snapshot, userId)
                        continuation.resume(profile)
                    } else {
                        continuation.resume(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(null)
                }
            })
        }

    private suspend fun ensureCurrentUserProfile(userId: String): Profile = withContext(Dispatchers.IO) {
        val email = sessionManager.getUserEmail() ?: FirebaseManager.auth.currentUser?.email
        val fbUser = FirebaseManager.auth.currentUser
        val fbName = fbUser?.displayName
        val username = sessionManager.secureStorage.getCachedUsername()
            ?: email?.substringBefore("@")
            ?: "user_${userId.take(6)}"
        val displayName = fbName?.takeIf { it.isNotBlank() }
            ?: sessionManager.secureStorage.getCachedDisplayName()
            ?: username

        val newProfile = Profile(
            id = userId,
            username = username,
            displayName = displayName,
            name = displayName,
            email = email,
            phoneNumber = fbUser?.phoneNumber,
            statusMessage = "Available",
            onlineStatus = "online",
            showLastSeen = true,
            showReadReceipts = true
        )

        saveProfileToFirebase(newProfile)
        newProfile
    }

    suspend fun saveProfileToFirebase(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val data = HashMap<String, Any?>()
            data["id"] = profile.id
            data["username"] = profile.username
            data["display_name"] = profile.displayName
            data["name"] = profile.name ?: profile.displayName
            data["email"] = profile.email
            data["phone_number"] = profile.phoneNumber
            data["avatar_url"] = profile.avatarUrl ?: profile.avatar
            data["bio"] = profile.bio
            data["status_message"] = profile.statusMessage ?: "Available"
            data["online_status"] = profile.onlineStatus
            data["show_last_seen"] = profile.showLastSeen
            data["show_read_receipts"] = profile.showReadReceipts

            usersRef.child(profile.id).updateChildren(data).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    profile.username?.let { u ->
                        usernamesRef.child(u.lowercase()).setValue(profile.id)
                    }
                    profile.phoneNumber?.let { p ->
                        val cleanPhone = p.replace(Regex("[^0-9+]"), "").replace("+", "p_")
                        if (cleanPhone.isNotBlank()) {
                            phoneNumbersRef.child(cleanPhone).setValue(profile.id)
                        }
                    }
                    continuation.resume(true)
                } else {
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun getUserProfiles(userIds: List<String>): Map<String, Profile> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Profile>()
        for (id in userIds) {
            val p = fetchProfileFromFirebase(id) ?: profileCache[id]
            if (p != null) {
                profileCache[id] = p
                result[id] = p
            }
        }
        result
    }

    suspend fun searchUsers(query: String): Resource<List<Profile>> = withContext(Dispatchers.IO) {
        val currentUserId = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid.orEmpty()
        val q = query.trim().lowercase()

        suspendCancellableCoroutine { continuation ->
            usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<Profile>()
                    for (child in snapshot.children) {
                        val uid = child.key ?: continue
                        if (uid == currentUserId) continue // Exclude self

                        val profile = parseProfileSnapshot(child, uid)
                        if (q.isBlank()) {
                            list.add(profile)
                        } else {
                            val matchesName = profile.displayName.lowercase().contains(q)
                            val matchesUsername = profile.username?.lowercase()?.contains(q) == true
                            val matchesEmail = profile.email?.lowercase()?.contains(q) == true
                            val matchesPhone = profile.phoneNumber?.contains(q) == true

                            if (matchesName || matchesUsername || matchesEmail || matchesPhone) {
                                list.add(profile)
                            }
                        }
                    }
                    continuation.resume(Resource.Success(list))
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(Resource.Error(error.message))
                }
            })
        }
    }

    suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String,
        statusMessage: String
    ): Resource<Profile> = withContext(Dispatchers.IO) {
        val uid = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
        if (uid.isNullOrBlank()) return@withContext Resource.Error("Not authenticated")

        val currentProfile = when (val p = getUserProfile(uid)) {
            is Resource.Success -> p.data
            else -> Profile(id = uid)
        }

        val updated = currentProfile.copy(
            displayName = displayName,
            name = displayName,
            username = username,
            bio = bio,
            statusMessage = statusMessage
        )

        val success = saveProfileToFirebase(updated)
        if (success) {
            profileCache[uid] = updated
            sessionManager.secureStorage.saveUserCredentials(
                identifier = username,
                password = "",
                email = updated.email,
                userId = uid,
                username = username
            )
            Resource.Success(updated)
        } else {
            Resource.Error("Failed to update profile")
        }
    }

    suspend fun updateOnlineStatus(status: String) = withContext(Dispatchers.IO) {
        val uid = sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid ?: return@withContext
        val userNode = usersRef.child(uid)

        val updates = HashMap<String, Any?>()
        updates["online_status"] = status
        updates["last_seen"] = ServerValue.TIMESTAMP

        userNode.updateChildren(updates)

        if (status == "online") {
            // Configure automatic disconnect handling
            val disconnectUpdates = HashMap<String, Any?>()
            disconnectUpdates["online_status"] = "offline"
            disconnectUpdates["last_seen"] = ServerValue.TIMESTAMP
            userNode.onDisconnect().updateChildren(disconnectUpdates)
        }
    }

    fun observeUserProfile(userId: String): Flow<Profile?> = callbackFlow {
        // Emit cached immediately to avoid lag
        profileCache[userId]?.let { trySend(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val p = parseProfileSnapshot(snapshot, userId)
                    profileCache[userId] = p
                    trySend(p)
                } else {
                    trySend(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(tag, "observeUserProfile error for $userId: ${error.message}")
            }
        }
        usersRef.child(userId).addValueEventListener(listener)
        awaitClose { usersRef.child(userId).removeEventListener(listener) }
    }

    fun observeUserProfiles(userIds: List<String>): Flow<Map<String, Profile>> = callbackFlow {
        if (userIds.isEmpty()) {
            trySend(emptyMap())
            awaitClose {}
            return@callbackFlow
        }

        val map = mutableMapOf<String, Profile>()
        userIds.forEach { id -> profileCache[id]?.let { map[id] = it } }
        if (map.isNotEmpty()) {
            trySend(map.toMap())
        }

        val listeners = mutableMapOf<String, ValueEventListener>()
        userIds.distinct().forEach { uid ->
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val p = parseProfileSnapshot(snapshot, uid)
                        profileCache[uid] = p
                        map[uid] = p
                        trySend(map.toMap())
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            listeners[uid] = l
            usersRef.child(uid).addValueEventListener(l)
        }

        awaitClose {
            listeners.forEach { (uid, l) -> usersRef.child(uid).removeEventListener(l) }
        }
    }

    fun updateFcmToken(userId: String, token: String) {
        if (userId.isNotBlank() && token.isNotBlank()) {
            usersRef.child(userId).child("fcm_token").setValue(token)
        }
    }

    private fun parseProfileSnapshot(snapshot: DataSnapshot, fallbackId: String): Profile {
        val id = snapshot.child("id").getValue(String::class.java) ?: fallbackId
        val username = snapshot.child("username").getValue(String::class.java)
        val displayName = snapshot.child("display_name").getValue(String::class.java)
            ?: snapshot.child("name").getValue(String::class.java)
            ?: username
            ?: "User"
        val name = snapshot.child("name").getValue(String::class.java)
        val email = snapshot.child("email").getValue(String::class.java)
        val phoneNumber = snapshot.child("phone_number").getValue(String::class.java)
        val avatarUrl = snapshot.child("avatar_url").getValue(String::class.java)
        val bio = snapshot.child("bio").getValue(String::class.java)
        val statusMessage = snapshot.child("status_message").getValue(String::class.java) ?: "Available"
        val onlineStatus = snapshot.child("online_status").getValue(String::class.java) ?: "offline"
        val lastSeen = snapshot.child("last_seen").value?.toString()
        val showLastSeen = snapshot.child("show_last_seen").getValue(Boolean::class.java) ?: true
        val showReadReceipts = snapshot.child("show_read_receipts").getValue(Boolean::class.java) ?: true

        return Profile(
            id = id,
            username = username,
            displayName = displayName,
            name = name,
            email = email,
            phoneNumber = phoneNumber,
            avatarUrl = avatarUrl,
            avatar = avatarUrl,
            bio = bio,
            statusMessage = statusMessage,
            onlineStatus = onlineStatus,
            lastSeen = lastSeen,
            showLastSeen = showLastSeen,
            showReadReceipts = showReadReceipts
        )
    }
}
