package com.aster.app.data.repository

import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.dao.FriendRequestDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.FriendRequestEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.domain.model.User
import com.aster.app.domain.model.sanitizeDisplayName
import com.aster.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val authStorage: AsterAuthStorage? = null,
    private val friendRequestDao: FriendRequestDao? = null,
    private val webSocketClient: AsterWebSocketClient? = null,
    private val apiClient: AsterApiClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UserRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val presenceTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        webSocketClient?.let { ws ->
            scope.launch {
                ws.presenceUpdates.collect { update ->
                    updatePresence(update.userId, update.isOnline, update.lastSeen, update.timestamp)
                }
            }
            scope.launch {
                ws.userDeletedEvents.collect { del ->
                    markUserDeleted(del.userId)
                }
            }
            scope.launch {
                ws.friendRequestReceivedEvents.collect { event ->
                    val entity = FriendRequestEntity.fromSummary(event.request)
                    friendRequestDao?.insertOrUpdate(entity)
                }
            }
            scope.launch {
                ws.friendRequestUpdatedEvents.collect { update ->
                    if (update.status == "ACCEPTED" || update.status == "REJECTED" || update.status == "CANCELLED") {
                        friendRequestDao?.deleteById(update.requestId)
                    } else {
                        friendRequestDao?.updateStatus(update.requestId, update.status)
                    }
                }
            }
            scope.launch {
                ws.connectionState.collect { connState ->
                    if (connState == ConnectionState.CONNECTED) {
                        syncFriendRequests()
                        syncBlockedUsers()
                        val knownUsers = userDao.getAllUsersOnce()
                            .filter { !it.isLocalUser && !it.isDeleted }
                            .map { it.id }
                        if (knownUsers.isNotEmpty()) {
                            syncPresence(knownUsers)
                        }
                    }
                }
            }
        }
    }

    override fun getLocalUser(): Flow<User?> {
        return userDao.getLocalUser().map { it?.toDomain() }
    }

    override suspend fun getLocalUserOnce(): User? {
        return userDao.getLocalUserOnce()?.toDomain()
    }

    override fun getUser(userId: String): Flow<User?> {
        return userDao.getUserById(userId).map { it?.toDomain() }
    }

    override fun getUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveUser(user: User) {
        val existing = userDao.getUserByIdOnce(user.id)
        if (existing == null) {
            userDao.insertUser(UserEntity.fromDomain(user))
        } else {
            // Preserve existing presence unless caller explicitly provides newer presence
            val isOnline = if (user.lastSeen > 0L && user.lastSeen >= existing.lastSeen) {
                user.isOnline
            } else if (user.isOnline && !existing.isOnline && user.lastSeen == 0L && existing.lastSeen == 0L) {
                user.isOnline
            } else {
                existing.isOnline
            }
            val lastSeen = maxOf(user.lastSeen, existing.lastSeen)
            val isDeleted = user.isDeleted || existing.isDeleted
            val isBlocked = user.isBlocked || existing.isBlocked
            userDao.updateUser(
                existing.copy(
                    username = user.username.ifBlank { existing.username },
                    displayName = user.displayName.ifBlank { existing.displayName },
                    isOnline = isOnline,
                    lastSeen = lastSeen,
                    isDeleted = isDeleted,
                    isBlocked = isBlocked
                )
            )
        }
    }

    override suspend fun setAuthenticatedUser(userId: String) {
        setAuthenticatedUser(userId, null)
    }

    override suspend fun setAuthenticatedUser(userId: String, displayName: String?) {
        val existing = userDao.getUserByIdOnce(userId)
        val rawPrefixStripped = userId.removePrefix("user_")
        val fallbackName = if (!com.aster.app.domain.model.isInternalIdentifier(rawPrefixStripped)) {
            rawPrefixStripped.replaceFirstChar { it.uppercase() }
        } else {
            ""
        }
        val resolvedDisplayName = sanitizeDisplayName(displayName, userId)
            ?: sanitizeDisplayName(authStorage?.getDisplayName(), userId)
            ?: sanitizeDisplayName(existing?.displayName, userId)
            ?: fallbackName
        if (existing == null) {
            val username = userId.removePrefix("user_")
            userDao.insertUser(
                UserEntity(
                    id = userId,
                    username = username,
                    displayName = resolvedDisplayName,
                    isLocalUser = true,
                    isOnline = true
                )
            )
        } else if (resolvedDisplayName.isNotBlank() && existing.displayName != resolvedDisplayName) {
            userDao.updateDisplayName(userId, resolvedDisplayName)
        }
        userDao.setLocalUser(userId)
    }

    override suspend fun clearAuthenticatedUser() {
        userDao.clearLocalUser()
    }

    override suspend fun updatePresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        updatePresence(userId, isOnline, lastSeen, 0L)
    }

    override suspend fun updatePresence(userId: String, isOnline: Boolean, lastSeen: Long, timestamp: Long) {
        if (timestamp > 0L) {
            val lastTs = presenceTimestamps[userId] ?: 0L
            if (timestamp < lastTs) {
                return // Discard out-of-order stale presence update
            }
            presenceTimestamps[userId] = timestamp
        }
        val existing = userDao.getUserByIdOnce(userId)
        if (existing != null) {
            if (existing.isDeleted) return // Deleted accounts NEVER display presence
            userDao.updatePresence(userId, isOnline, lastSeen)
        } else {
            val rawPrefixStripped = userId.removePrefix("user_")
            val fallbackName = if (!com.aster.app.domain.model.isInternalIdentifier(rawPrefixStripped)) {
                rawPrefixStripped.replaceFirstChar { it.uppercase() }
            } else {
                "User"
            }
            userDao.insertUser(
                UserEntity(
                    id = userId,
                    username = rawPrefixStripped,
                    displayName = fallbackName,
                    isLocalUser = false,
                    isOnline = isOnline,
                    lastSeen = lastSeen,
                    isDeleted = false
                )
            )
        }
    }

    override suspend fun markUserDeleted(userId: String) {
        val existing = userDao.getUserByIdOnce(userId)
        if (existing != null) {
            userDao.markUserDeleted(userId)
        } else {
            userDao.insertUser(
                UserEntity(
                    id = userId,
                    username = "deleted",
                    displayName = "Deleted User",
                    isLocalUser = false,
                    isOnline = false,
                    lastSeen = 0L,
                    isDeleted = true
                )
            )
        }
    }

    override suspend fun syncPresence(userIds: List<String>) {
        if (apiClient == null || userIds.isEmpty()) return
        val res = apiClient.getPresence(userIds)
        if (res is com.aster.app.data.network.ApiResult.Success) {
            val now = System.currentTimeMillis()
            for ((uid, summary) in res.data) {
                if (summary.isDeleted) {
                    markUserDeleted(uid)
                } else {
                    updatePresence(uid, summary.isOnline, summary.lastSeen, now)
                }
            }
        }
    }

    override suspend fun blockUser(userId: String): Boolean {
        userDao.updateBlockedStatus(userId, true)
        if (apiClient == null) return true
        val res = apiClient.blockUser(targetUserId = userId)
        return res is ApiResult.Success
    }

    override suspend fun unblockUser(userId: String): Boolean {
        userDao.updateBlockedStatus(userId, false)
        if (apiClient == null) return true
        val res = apiClient.unblockUser(userId)
        return res is ApiResult.Success
    }

    override suspend fun syncBlockedUsers() {
        if (apiClient == null) return
        when (val res = apiClient.getBlockedUsers()) {
            is ApiResult.Success -> {
                val blockedIds = res.data.map { it.userId }
                for (id in blockedIds) {
                    userDao.updateBlockedStatus(id, true)
                }
                if (blockedIds.isNotEmpty()) {
                    userDao.unmarkUnblockedUsers(blockedIds)
                } else {
                    userDao.clearAllBlocked()
                }
            }
            is ApiResult.Error -> {
                // Keep existing Room cache if network fails
            }
        }
    }

    override fun getIncomingPendingRequests(): Flow<List<FriendRequestSummary>> {
        return friendRequestDao?.getIncomingPendingRequestsFlow()?.map { entities ->
            entities.map { it.toSummary() }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    override suspend fun syncFriendRequests() {
        if (apiClient == null) return
        when (val res = apiClient.getFriendRequests()) {
            is ApiResult.Success -> {
                val incomingEntities = res.data.incoming.map { FriendRequestEntity.fromSummary(it) }
                friendRequestDao?.clearAll()
                friendRequestDao?.insertOrUpdateAll(incomingEntities)
            }
            is ApiResult.Error -> {
                // Keep existing Room cache if network fails
            }
        }
    }

    override suspend fun respondToFriendRequest(requestId: String, accept: Boolean): Boolean {
        friendRequestDao?.deleteById(requestId)
        if (apiClient == null) return true
        return when (val res = apiClient.respondFriendRequest(requestId, accept)) {
            is ApiResult.Success -> true
            is ApiResult.Error -> {
                syncFriendRequests()
                false
            }
        }
    }
}
