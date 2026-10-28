package com.aster.app.domain.repository

import com.aster.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user identity and contacts.
 */
interface UserRepository {
    fun getLocalUser(): Flow<User?>
    suspend fun getLocalUserOnce(): User?
    fun getUser(userId: String): Flow<User?>
    fun getUsers(): Flow<List<User>>
    suspend fun saveUser(user: User)
    suspend fun setAuthenticatedUser(userId: String)
    suspend fun setAuthenticatedUser(userId: String, displayName: String?) {
        setAuthenticatedUser(userId)
    }
    suspend fun clearAuthenticatedUser()
    suspend fun updatePresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        updatePresence(userId, isOnline, lastSeen, 0L)
    }
    suspend fun updatePresence(userId: String, isOnline: Boolean, lastSeen: Long, timestamp: Long = 0L) {}
    suspend fun markUserDeleted(userId: String) {}
    suspend fun syncPresence(userIds: List<String>) {}

    suspend fun blockUser(userId: String): Boolean = false
    suspend fun unblockUser(userId: String): Boolean = false
    suspend fun syncBlockedUsers() {}

    fun getIncomingPendingRequests(): Flow<List<com.aster.app.data.network.FriendRequestSummary>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun syncFriendRequests() {}
    suspend fun respondToFriendRequest(requestId: String, accept: Boolean): Boolean = false
}
