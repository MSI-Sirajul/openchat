package com.msi.openchat.data.repository

import android.util.Log
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.security.FirebaseAuthManager
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.AuthSession
import com.msi.openchat.data.model.AuthUser
import com.msi.openchat.data.model.Profile
import com.msi.openchat.data.remote.firebase.FirebaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * AuthRepository
 * Orchestrates user authentication (Email/Password, Phone/SMS, and Google Sign-In)
 * using Firebase Auth, combined with Realtime Database profile indexing.
 */
class AuthRepository(
    private val sessionManager: SessionManager,
    private val authManager: FirebaseAuthManager,
    private val userRepository: UserRepository
) {
    private val tag = "OpenChat_AuthRepo"
    private val auth: FirebaseAuth get() = FirebaseManager.auth

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null || sessionManager.isAuthenticated()
    }

    suspend fun signUp(
        email: String,
        password: String,
        username: String,
        displayName: String,
        phoneNumber: String? = null
    ): Resource<AuthUser> = withContext(Dispatchers.IO) {
        try {
            val authResult = suspendCancellableCoroutine { continuation ->
                auth.createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            continuation.resume(task.result?.user)
                        } else {
                            continuation.resume(null)
                        }
                    }
            }

            val user = authResult ?: return@withContext Resource.Error("Sign up failed. Please try a different email or password.")
            val userId = user.uid

            val cleanUsername = username.trim()
            val dName = displayName.trim().ifBlank { cleanUsername }

            val profile = Profile(
                id = userId,
                username = cleanUsername,
                displayName = dName,
                name = dName,
                email = email.trim(),
                phoneNumber = phoneNumber?.trim(),
                statusMessage = "Available",
                onlineStatus = "online"
            )

            // Execute database profile creation and credential storage in NonCancellable
            // to ensure completion even if ViewModel coroutine scope is cancelled on navigation
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                userRepository.saveProfileToFirebase(profile)

                sessionManager.saveSession(
                    accessToken = userId,
                    refreshToken = null,
                    userId = userId,
                    email = email.trim()
                )

                sessionManager.saveFullCredentials(
                    identifier = cleanUsername,
                    password = password,
                    email = email.trim(),
                    userId = userId,
                    username = cleanUsername
                )
            }

            Resource.Success(AuthUser(id = userId, email = email.trim()))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Rethrow cancellation so standard coroutine cancellation semantics are respected
            throw ce
        } catch (e: Exception) {
            Log.e(tag, "Sign up error: ${e.message}", e)
            Resource.Error(e.message ?: "Sign up failed", e)
        }
    }

    suspend fun signIn(
        identifier: String,
        password: String,
        rememberMe: Boolean = true
    ): Resource<AuthSession> = withContext(Dispatchers.IO) {
        val trimmed = identifier.trim()
        try {
            val cleanInput = if (trimmed.startsWith("@")) trimmed.substring(1).trim() else trimmed
            val isEmail = cleanInput.contains("@") &&
                    cleanInput.substringBefore("@").isNotBlank() &&
                    cleanInput.substringAfter("@").contains(".")

            val emailToUse = if (isEmail) {
                cleanInput
            } else {
                val resolved = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    resolveEmailFromIdentifier(cleanInput)
                }
                if (resolved.isNullOrBlank() || !resolved.contains("@")) {
                    // Check if offline cached credentials match
                    val cachedIdentifier = sessionManager.secureStorage.getCachedIdentifier()
                    val cachedPass = sessionManager.secureStorage.getCachedPassword()
                    val cachedUid = sessionManager.secureStorage.getCachedUserId()
                    if (!cachedUid.isNullOrBlank() &&
                        (cachedIdentifier.equals(cleanInput, ignoreCase = true) ||
                         cachedIdentifier.equals(trimmed, ignoreCase = true)) &&
                        cachedPass == password
                    ) {
                        sessionManager.markSessionValid()
                        return@withContext Resource.Success(
                            AuthSession(
                                accessToken = cachedUid,
                                user = AuthUser(id = cachedUid, email = sessionManager.getUserEmail())
                            )
                        )
                    }
                    return@withContext Resource.Error("No account found with username or phone '$trimmed'. Please enter your registered email address or sign up.")
                }
                resolved
            }

            var taskException: Exception? = null
            val authResult = suspendCancellableCoroutine<FirebaseUser?> { continuation ->
                auth.signInWithEmailAndPassword(emailToUse, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            continuation.resume(task.result?.user)
                        } else {
                            taskException = task.exception
                            continuation.resume(null)
                        }
                    }
            }

            val user = authResult
            if (user == null) {
                // Check if offline cached credentials match
                val cachedIdentifier = sessionManager.secureStorage.getCachedIdentifier()
                val cachedPass = sessionManager.secureStorage.getCachedPassword()
                val cachedUid = sessionManager.secureStorage.getCachedUserId()
                if (!cachedUid.isNullOrBlank() &&
                    (cachedIdentifier.equals(trimmed, ignoreCase = true) ||
                     cachedIdentifier.equals(emailToUse, ignoreCase = true) ||
                     sessionManager.getUserEmail().equals(emailToUse, ignoreCase = true)) &&
                    cachedPass == password
                ) {
                    sessionManager.markSessionValid()
                    return@withContext Resource.Success(
                        AuthSession(
                            accessToken = cachedUid,
                            user = AuthUser(id = cachedUid, email = sessionManager.getUserEmail())
                        )
                    )
                }

                val friendlyMessage = when (taskException) {
                    is com.google.firebase.auth.FirebaseAuthInvalidUserException ->
                        "No account found with this email. Please check your spelling or sign up."
                    is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException ->
                        "Incorrect password or email. Please check your credentials and try again."
                    is com.google.firebase.FirebaseTooManyRequestsException ->
                        "Too many unsuccessful login attempts. Please wait a few minutes before trying again."
                    is com.google.firebase.FirebaseNetworkException ->
                        "Network connection error. Please check your internet connection."
                    else -> {
                        val msg = taskException?.localizedMessage.orEmpty()
                        if (msg.contains("credential is incorrect", ignoreCase = true) ||
                            msg.contains("malformed", ignoreCase = true) ||
                            msg.contains("expired", ignoreCase = true)
                        ) {
                            "Incorrect password or email. Please check your credentials and try again."
                        } else if (msg.isNotBlank()) {
                            msg
                        } else {
                            "Invalid login credentials. Please check your username, email, and password."
                        }
                    }
                }
                return@withContext Resource.Error(friendlyMessage)
            }

            val userId = user.uid
            val userEmail = user.email ?: emailToUse

            sessionManager.saveSession(
                accessToken = userId,
                refreshToken = null,
                userId = userId,
                email = userEmail
            )

            if (rememberMe) {
                sessionManager.saveFullCredentials(
                    identifier = trimmed,
                    password = password,
                    email = userEmail,
                    userId = userId,
                    username = trimmed
                )
            }

            // Ensure profile exists in Realtime Database
            userRepository.getUserProfile(userId)

            Resource.Success(
                AuthSession(
                    accessToken = userId,
                    user = AuthUser(id = userId, email = userEmail)
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "Sign in error: ${e.message}", e)
            Resource.Error(e.message ?: "Sign in failed", e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Resource<AuthSession> = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            signInWithCredentialInternal(credential)
        } catch (e: Exception) {
            Log.e(tag, "Google sign in error: ${e.message}", e)
            Resource.Error(e.message ?: "Google Sign In failed", e)
        }
    }

    suspend fun signInWithPhoneCredential(credential: PhoneAuthCredential): Resource<AuthSession> = withContext(Dispatchers.IO) {
        try {
            signInWithCredentialInternal(credential)
        } catch (e: Exception) {
            Log.e(tag, "Phone sign in error: ${e.message}", e)
            Resource.Error(e.message ?: "Phone verification failed", e)
        }
    }

    private suspend fun signInWithCredentialInternal(credential: AuthCredential): Resource<AuthSession> {
        val user = suspendCancellableCoroutine<FirebaseUser?> { continuation ->
            auth.signInWithCredential(credential)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) continuation.resume(task.result?.user)
                    else continuation.resume(null)
                }
        } ?: return Resource.Error("Authentication failed")

        val userId = user.uid
        val userEmail = user.email
        val userName = user.displayName ?: userEmail?.substringBefore("@") ?: "User"

        sessionManager.saveSession(
            accessToken = userId,
            refreshToken = null,
            userId = userId,
            email = userEmail
        )

        val profile = Profile(
            id = userId,
            username = userEmail?.substringBefore("@") ?: "user_${userId.take(6)}",
            displayName = userName,
            name = userName,
            email = userEmail,
            phoneNumber = user.phoneNumber,
            onlineStatus = "online"
        )
        userRepository.saveProfileToFirebase(profile)

        return Resource.Success(
            AuthSession(
                accessToken = userId,
                user = AuthUser(id = userId, email = userEmail)
            )
        )
    }

    private suspend fun resolveEmailFromIdentifier(identifier: String): String? = withContext(Dispatchers.IO) {
        val trimmed = identifier.trim()
        val cleanUsername = trimmed.lowercase()

        // 1. Check usernames index (/usernames/{username})
        val fromUsername = suspendCancellableCoroutine<String?> { cont ->
            FirebaseManager.usernamesRef.child(cleanUsername).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val resolvedUid = snapshot.getValue(String::class.java)
                    if (!resolvedUid.isNullOrBlank()) {
                        FirebaseManager.usersRef.child(resolvedUid).child("email")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(emailSnap: DataSnapshot) {
                                    if (cont.isActive) cont.resume(emailSnap.getValue(String::class.java))
                                }
                                override fun onCancelled(error: DatabaseError) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            })
                    } else {
                        if (cont.isActive) cont.resume(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
        if (!fromUsername.isNullOrBlank()) return@withContext fromUsername

        // 2. Check phone_numbers index (/phone_numbers/{phone})
        val cleanPhone = trimmed.replace(Regex("[^0-9+]"), "").replace("+", "p_")
        if (cleanPhone.isNotBlank()) {
            val fromPhone = suspendCancellableCoroutine<String?> { cont ->
                FirebaseManager.phoneNumbersRef.child(cleanPhone).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val resolvedUid = snapshot.getValue(String::class.java)
                        if (!resolvedUid.isNullOrBlank()) {
                            FirebaseManager.usersRef.child(resolvedUid).child("email")
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(emailSnap: DataSnapshot) {
                                        if (cont.isActive) cont.resume(emailSnap.getValue(String::class.java))
                                    }
                                    override fun onCancelled(error: DatabaseError) {
                                        if (cont.isActive) cont.resume(null)
                                    }
                                })
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
            if (!fromPhone.isNullOrBlank()) return@withContext fromPhone
        }

        // 3. Fallback scan on /users to match phone_number or username
        suspendCancellableCoroutine<String?> { cont ->
            FirebaseManager.usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val digitsOnly = trimmed.replace(Regex("[^0-9]"), "")
                    for (child in snapshot.children) {
                        val uName = child.child("username").getValue(String::class.java)
                        val pNum = child.child("phone_number").getValue(String::class.java)
                        val email = child.child("email").getValue(String::class.java)

                        if (!email.isNullOrBlank()) {
                            if (uName.equals(trimmed, ignoreCase = true)) {
                                if (cont.isActive) cont.resume(email)
                                return
                            }
                            if (!pNum.isNullOrBlank()) {
                                if (pNum == trimmed || (digitsOnly.length >= 7 && pNum.replace(Regex("[^0-9]"), "").endsWith(digitsOnly))) {
                                    if (cont.isActive) cont.resume(email)
                                    return
                                }
                            }
                        }
                    }
                    if (cont.isActive) cont.resume(null)
                }

                override fun onCancelled(error: DatabaseError) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    suspend fun resetPassword(email: String): Resource<Unit> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(Resource.Success(Unit))
                } else {
                    continuation.resume(Resource.Error(task.exception?.message ?: "Failed to send reset email"))
                }
            }
        }
    }

    suspend fun checkEmailConfirmed(email: String): Resource<Boolean> = withContext(Dispatchers.IO) {
        val user = auth.currentUser
        if (user != null) {
            suspendCancellableCoroutine { continuation ->
                user.reload().addOnCompleteListener {
                    continuation.resume(Resource.Success(user.isEmailVerified))
                }
            }
        } else {
            Resource.Success(true)
        }
    }

    suspend fun resendVerificationEmail(email: String): Resource<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser
        if (user != null) {
            suspendCancellableCoroutine<Resource<Unit>> { continuation ->
                user.sendEmailVerification().addOnCompleteListener { task ->
                    if (task.isSuccessful) continuation.resume(Resource.Success(Unit))
                    else continuation.resume(Resource.Error(task.exception?.message ?: "Failed to resend"))
                }
            }
        } else {
            Resource.Success(Unit)
        }
    }

    suspend fun verifyCredentials(identifier: String, password: String): Resource<Boolean> = withContext(Dispatchers.IO) {
        when (val res = signIn(identifier, password, rememberMe = false)) {
            is Resource.Success -> Resource.Success(true)
            is Resource.Error -> Resource.Error(res.message, res.cause)
            else -> Resource.Error("Verification failed")
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            userRepository.updateOnlineStatus("offline")
            auth.signOut()
            sessionManager.clearSession()
        } catch (e: Exception) {
            Log.e(tag, "Sign out error: ${e.message}")
        }
    }

    fun updateAppPin(pin: String): Resource<Unit> {
        sessionManager.secureStorage.setAppPin(pin)
        sessionManager.secureStorage.setAppLockEnabled(true)
        return Resource.Success(Unit)
    }

    fun updateBiometricEnabled(enabled: Boolean): Resource<Unit> {
        sessionManager.secureStorage.setBiometricEnabled(enabled)
        return Resource.Success(Unit)
    }

    fun disableAppLock(): Resource<Unit> {
        sessionManager.secureStorage.setAppLockEnabled(false)
        sessionManager.secureStorage.setBiometricEnabled(false)
        return Resource.Success(Unit)
    }

    fun syncAppLockFromRemote(userId: String) {
        // App lock settings are securely device-bound in SecureStorageManager
    }
}
