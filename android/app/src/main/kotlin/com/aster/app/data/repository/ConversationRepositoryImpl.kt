package com.aster.app.data.repository

import android.util.Log
import com.aster.app.data.local.dao.ConstellationNodePrefDao
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val webSocketClient: AsterWebSocketClient,
    private val constellationNodePrefDao: ConstellationNodePrefDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConversationRepository {

    private val TAG = "ConversationRepo"
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()

    init {
        // Coroutine 1: On connection established, sync groups from server
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    Log.d(TAG, "Connected to server; requesting active groups list")
                    webSocketClient.getGroups()
                }
            }
        }

        // Coroutine 2: Handle group lists from server
        scope.launch {
            webSocketClient.groupLists.collect { groupList ->
                Log.d(TAG, "Syncing ${groupList.groups.size} groups into Room")
                val localUser = userDao.getLocalUserOnce()
                val localUserId = localUser?.id.orEmpty()

                for (g in groupList.groups) {
                    val existing = conversationDao.getConversationByIdOnce(g.id)
                    if (existing == null) {
                        conversationDao.insertConversation(
                            ConversationEntity(
                                id = g.id,
                                title = g.name,
                                isGroup = true,
                                createdAt = g.createdAt,
                                updatedAt = g.updatedAt
                            )
                        )
                        if (localUserId.isNotBlank()) {
                            conversationMemberDao.insertMember(
                                ConversationMemberEntity(
                                    conversationId = g.id,
                                    userId = localUserId,
                                    role = g.role,
                                    joinedAt = g.createdAt
                                )
                            )
                        }
                    } else {
                        conversationDao.updateConversationTitle(g.id, g.name, g.updatedAt)
                    }
                    // Fetch full members for this group
                    webSocketClient.getGroupMembers(g.id)
                }
            }
        }

        // Coroutine 3: Handle group members list from server
        scope.launch {
            webSocketClient.groupMembersLists.collect { membersList ->
                Log.d(TAG, "Syncing ${membersList.members.size} members for group ${membersList.groupId}")
                val currentAuthUserId = webSocketClient.authStorage.getAuthenticatedUserId()
                val currentMemberIds = membersList.members.map { it.userId }.toSet()

                // Prune members locally who were removed on server
                val localMembers = conversationMemberDao.getMembersForConversationOnce(membersList.groupId)
                for (existing in localMembers) {
                    if (existing.id !in currentMemberIds) {
                        conversationMemberDao.removeMember(membersList.groupId, existing.id)
                    }
                }
                if (currentAuthUserId != null && currentAuthUserId !in currentMemberIds) {
                    conversationDao.deleteConversation(membersList.groupId)
                }

                for (m in membersList.members) {
                    if (userDao.getUserByIdOnce(m.userId) == null) {
                        val dName = m.userId.removePrefix("user_").replaceFirstChar { it.uppercase() }
                        userDao.insertUser(
                            UserEntity(
                                id = m.userId,
                                username = m.userId.removePrefix("user_"),
                                displayName = dName,
                                isLocalUser = (m.userId == currentAuthUserId),
                                isOnline = (m.userId == currentAuthUserId)
                            )
                        )
                    }
                    conversationMemberDao.insertMember(
                        ConversationMemberEntity(
                            conversationId = membersList.groupId,
                            userId = m.userId,
                            role = m.role,
                            joinedAt = m.joinedAt
                        )
                    )
                }
            }
        }

        // Coroutine 4: Handle real-time group events from server
        scope.launch {
            webSocketClient.groupEvents.collect { event ->
                Log.i(TAG, "Received group event ${event.eventType} for ${event.groupId}")
                when (event.eventType) {
                    "CREATED" -> {
                        webSocketClient.getGroups()
                    }
                    "RENAMED" -> {
                        event.name?.let { newTitle ->
                            conversationDao.updateConversationTitle(event.groupId, newTitle, event.timestamp)
                        }
                    }
                    "MEMBER_ADDED" -> {
                        event.targetUserId?.let { uid ->
                            val currentAuthUserId = webSocketClient.authStorage.getAuthenticatedUserId()
                            if (userDao.getUserByIdOnce(uid) == null) {
                                val dName = uid.removePrefix("user_").replaceFirstChar { it.uppercase() }
                                userDao.insertUser(
                                    UserEntity(
                                        id = uid,
                                        username = uid.removePrefix("user_"),
                                        displayName = dName,
                                        isLocalUser = (uid == currentAuthUserId),
                                        isOnline = (uid == currentAuthUserId)
                                    )
                                )
                            }
                            conversationMemberDao.insertMember(
                                ConversationMemberEntity(
                                    conversationId = event.groupId,
                                    userId = uid,
                                    role = "MEMBER",
                                    joinedAt = event.timestamp
                                )
                            )
                        }
                    }
                    "MEMBER_REMOVED", "MEMBER_LEFT" -> {
                        val target = event.targetUserId ?: event.actorId
                        conversationMemberDao.removeMember(event.groupId, target)
                        val currentAuthUserId = webSocketClient.authStorage.getAuthenticatedUserId()
                        if (target == currentAuthUserId) {
                            conversationDao.deleteConversation(event.groupId)
                        }
                    }
                }
            }
        }
    }

    override fun getConversations(): Flow<List<Conversation>> {
        val prefsFlow = constellationNodePrefDao?.getAllNodePrefs() ?: flowOf(emptyList())
        return userDao.getLocalUser().flatMapLatest { localUserEntity ->
            val localUserId = localUserEntity?.id.orEmpty()
            if (localUserId.isBlank()) {
                flowOf(emptyList())
            } else {
                combine(
                    conversationDao.getConversationsForUser(localUserId),
                    prefsFlow
                ) { conversationEntities, prefs ->
                    val prefMap = prefs.associateBy { it.conversationId }
                    if (conversationEntities.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        val flows = conversationEntities.map { convEntity ->
                            val pref = prefMap[convEntity.id]
                            combine(
                                messageDao.getLatestMessage(convEntity.id),
                                messageDao.getUnreadCount(convEntity.id, localUserId),
                                conversationMemberDao.getOtherMember(convEntity.id, localUserId),
                                conversationMemberDao.getMembersForConversation(convEntity.id)
                            ) { latestMsg, unreadCount, otherMemberEntity, memberEntities ->
                                Conversation(
                                    id = convEntity.id,
                                    title = if (!convEntity.isGroup && otherMemberEntity != null) {
                                        otherMemberEntity.displayName
                                    } else {
                                        convEntity.title
                                    },
                                    isGroup = convEntity.isGroup,
                                    createdAt = convEntity.createdAt,
                                    lastMessage = latestMsg?.toDomain(),
                                    unreadCount = unreadCount,
                                    otherMember = otherMemberEntity?.toDomain(),
                                    members = memberEntities.map { it.toDomain() },
                                    isHighlighted = pref?.isHighlighted ?: false,
                                    customNormalizedX = pref?.customNormalizedX,
                                    customNormalizedY = pref?.customNormalizedY
                                )
                            }
                        }
                        combine(flows) { it.toList() }
                    }
                }.flatMapLatest { it }
            }
        }
    }

    override fun getConversation(conversationId: String): Flow<Conversation?> {
        val prefFlow = constellationNodePrefDao?.getNodePrefFlow(conversationId) ?: flowOf(null)
        return userDao.getLocalUser().flatMapLatest { localUserEntity ->
            val localUserId = localUserEntity?.id.orEmpty()
            combine(
                conversationDao.getConversationById(conversationId),
                messageDao.getLatestMessage(conversationId),
                conversationMemberDao.getOtherMember(conversationId, localUserId),
                conversationMemberDao.getMembersForConversation(conversationId),
                prefFlow
            ) { convEntity, latestMsg, otherMemberEntity, memberEntities, pref ->
                convEntity?.let {
                    Conversation(
                        id = it.id,
                        title = if (!it.isGroup && otherMemberEntity != null) {
                            otherMemberEntity.displayName
                        } else {
                            it.title
                        },
                        isGroup = it.isGroup,
                        createdAt = it.createdAt,
                        lastMessage = latestMsg?.toDomain(),
                        unreadCount = 0,
                        otherMember = otherMemberEntity?.toDomain(),
                        members = memberEntities.map { memberEntity -> memberEntity.toDomain() },
                        isHighlighted = pref?.isHighlighted ?: false,
                        customNormalizedX = pref?.customNormalizedX,
                        customNormalizedY = pref?.customNormalizedY
                    )
                }
            }
        }
    }

    override fun getConversationMembers(conversationId: String): Flow<List<User>> {
        return conversationMemberDao.getMembersForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getConversationMemberRoles(conversationId: String): Flow<Map<String, String>> {
        return conversationMemberDao.getMemberEntitiesForConversation(conversationId).map { entities ->
            entities.associate { it.userId to it.role }
        }
    }

    override suspend fun createConversation(
        conversation: Conversation,
        memberIds: List<String>
    ) {
        val now = System.currentTimeMillis()
        val convEntity = ConversationEntity(
            id = conversation.id,
            title = conversation.title,
            isGroup = conversation.isGroup,
            createdAt = conversation.createdAt,
            updatedAt = now
        )
        conversationDao.insertConversation(convEntity)

        val members = memberIds.map { userId ->
            ConversationMemberEntity(
                conversationId = conversation.id,
                userId = userId,
                role = "MEMBER",
                joinedAt = now
            )
        }
        conversationMemberDao.insertMembers(members)
    }

    override suspend fun markConversationAsRead(conversationId: String) {
        val localUser = userDao.getLocalUserOnce()
        val localUserId = localUser?.id.orEmpty()
        val unreadIncoming = messageDao.getUnreadIncomingMessages(conversationId, localUserId)
        messageDao.markMessagesAsRead(conversationId, localUserId)
        for (msg in unreadIncoming) {
            webSocketClient.sendMarkRead(msg.id, conversationId)
        }
    }

    override suspend fun getOrCreateDirectConversation(otherUserId: String): Conversation = mutex.withLock {
        val localUser = userDao.getLocalUserOnce()
            ?: throw IllegalStateException("Local user not found in database")
        require(otherUserId != localUser.id) { "Cannot create direct conversation with self" }
        val otherUser = userDao.getUserByIdOnce(otherUserId)
            ?: throw IllegalArgumentException("User $otherUserId not found")

        // 1. Check if direct 1:1 conversation already exists
        val existingEntity = conversationDao.findDirectConversation(localUser.id, otherUserId)
        if (existingEntity != null) {
            val latestMsg = messageDao.getLatestMessageOnce(existingEntity.id)
            return@withLock Conversation(
                id = existingEntity.id,
                title = otherUser.displayName,
                isGroup = false,
                createdAt = existingEntity.createdAt,
                lastMessage = latestMsg?.toDomain(),
                unreadCount = 0,
                otherMember = otherUser.toDomain()
            )
        }

        // 2. Generate deterministic ID for the pair
        val convId = com.aster.app.domain.repository.getCanonicalDirectConversationId(localUser.id, otherUserId)

        val existingById = conversationDao.getConversationByIdOnce(convId)
        if (existingById != null) {
            val latestMsg = messageDao.getLatestMessageOnce(existingById.id)
            return@withLock Conversation(
                id = existingById.id,
                title = otherUser.displayName,
                isGroup = false,
                createdAt = existingById.createdAt,
                lastMessage = latestMsg?.toDomain(),
                unreadCount = 0,
                otherMember = otherUser.toDomain()
            )
        }

        // 3. Create conversation and member rows atomically
        val now = System.currentTimeMillis()
        val convEntity = ConversationEntity(
            id = convId,
            title = otherUser.displayName,
            isGroup = false,
            createdAt = now,
            updatedAt = now
        )
        conversationDao.insertConversation(convEntity)

        val members = listOf(
            ConversationMemberEntity(
                conversationId = convId,
                userId = localUser.id,
                role = "MEMBER",
                joinedAt = now
            ),
            ConversationMemberEntity(
                conversationId = convId,
                userId = otherUserId,
                role = "MEMBER",
                joinedAt = now
            )
        )
        conversationMemberDao.insertMembers(members)

        Conversation(
            id = convId,
            title = otherUser.displayName,
            isGroup = false,
            createdAt = now,
            lastMessage = null,
            unreadCount = 0,
            otherMember = otherUser.toDomain()
        )
    }

    override suspend fun createGroup(name: String, memberIds: List<String>): Conversation = mutex.withLock {
        val localUser = userDao.getLocalUserOnce()
            ?: throw IllegalStateException("Local user not found in database")
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        // 1. Dispatch CreateGroup over WebSocket
        webSocketClient.createGroup(name, memberIds, requestId)

        // 2. Wait for server acknowledgement with timeout
        val ok = withTimeoutOrNull(3000) {
            webSocketClient.groupOperations.first { it.requestId == requestId }
        }
        val groupId = ok?.groupId ?: ("group_" + UUID.randomUUID().toString().replace("-", "").take(16))

        // 3. Persist locally in Room
        val convEntity = ConversationEntity(
            id = groupId,
            title = name,
            isGroup = true,
            createdAt = now,
            updatedAt = now
        )
        conversationDao.insertConversation(convEntity)

        val allMembers = (memberIds + localUser.id).distinct()
        val memberEntities = allMembers.map { uid ->
            val role = if (uid == localUser.id) "CREATOR" else "MEMBER"
            if (userDao.getUserByIdOnce(uid) == null) {
                val dName = uid.removePrefix("user_").replaceFirstChar { it.uppercase() }
                userDao.insertUser(
                    UserEntity(
                        id = uid,
                        username = uid.removePrefix("user_"),
                        displayName = dName,
                        isLocalUser = (uid == localUser.id),
                        isOnline = (uid == localUser.id)
                    )
                )
            }
            ConversationMemberEntity(
                conversationId = groupId,
                userId = uid,
                role = role,
                joinedAt = now
            )
        }
        conversationMemberDao.insertMembers(memberEntities)

        Conversation(
            id = groupId,
            title = name,
            isGroup = true,
            createdAt = now,
            lastMessage = null,
            unreadCount = 0,
            otherMember = null
        )
    }

    override suspend fun refreshGroups() {
        webSocketClient.getGroups()
    }

    override suspend fun addGroupMember(groupId: String, userId: String) {
        webSocketClient.addGroupMember(groupId, userId)
        val now = System.currentTimeMillis()
        if (userDao.getUserByIdOnce(userId) == null) {
            val dName = userId.removePrefix("user_").replaceFirstChar { it.uppercase() }
            userDao.insertUser(
                UserEntity(
                    id = userId,
                    username = userId.removePrefix("user_"),
                    displayName = dName,
                    isLocalUser = false,
                    isOnline = false
                )
            )
        }
        conversationMemberDao.insertMember(
            ConversationMemberEntity(
                conversationId = groupId,
                userId = userId,
                role = "MEMBER",
                joinedAt = now
            )
        )
    }

    override suspend fun removeGroupMember(groupId: String, userId: String) {
        webSocketClient.removeGroupMember(groupId, userId)
        conversationMemberDao.removeMember(groupId, userId)
    }

    override suspend fun leaveGroup(groupId: String) {
        val localUser = userDao.getLocalUserOnce()
        webSocketClient.leaveGroup(groupId)
        if (localUser != null) {
            conversationMemberDao.removeMember(groupId, localUser.id)
        }
    }

    override suspend fun renameGroup(groupId: String, newName: String) {
        webSocketClient.renameGroup(groupId, newName)
        conversationDao.updateConversationTitle(groupId, newName)
    }

    override fun getNodePrefs(): Flow<List<com.aster.app.data.local.entities.ConstellationNodePrefEntity>> {
        return constellationNodePrefDao?.getAllNodePrefs() ?: flowOf(emptyList())
    }

    override suspend fun setConstellationHighlight(conversationId: String, isHighlighted: Boolean) {
        val dao = constellationNodePrefDao ?: return
        val existing = dao.getNodePref(conversationId)
        if (existing != null) {
            dao.upsertNodePref(existing.copy(isHighlighted = isHighlighted, updatedAt = System.currentTimeMillis()))
        } else {
            dao.upsertNodePref(
                com.aster.app.data.local.entities.ConstellationNodePrefEntity(
                    conversationId = conversationId,
                    customNormalizedX = null,
                    customNormalizedY = null,
                    isHighlighted = isHighlighted
                )
            )
        }
    }

    override suspend fun setConstellationPosition(conversationId: String, normalizedX: Float?, normalizedY: Float?) {
        val dao = constellationNodePrefDao ?: return
        val existing = dao.getNodePref(conversationId)
        if (existing != null) {
            dao.upsertNodePref(existing.copy(customNormalizedX = normalizedX, customNormalizedY = normalizedY, updatedAt = System.currentTimeMillis()))
        } else {
            dao.upsertNodePref(
                com.aster.app.data.local.entities.ConstellationNodePrefEntity(
                    conversationId = conversationId,
                    customNormalizedX = normalizedX,
                    customNormalizedY = normalizedY,
                    isHighlighted = false
                )
            )
        }
    }
}
