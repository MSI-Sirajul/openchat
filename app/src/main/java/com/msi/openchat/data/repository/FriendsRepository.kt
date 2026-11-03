package com.msi.openchat.data.repository

import com.msi.openchat.core.security.SessionManager

/**
 * FriendsRepository
 * Subclass of FriendRepository maintained for backward compatibility.
 */
class FriendsRepository(
    sessionManager: SessionManager,
    userRepository: UserRepository
) : FriendRepository(sessionManager, userRepository)
