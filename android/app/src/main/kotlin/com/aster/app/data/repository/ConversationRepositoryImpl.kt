package com.aster.app.data.repository

import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return userDao.getLocalUser().flatMapLatest { localUserEntity ->
            val localUserId = localUserEntity?.id.orEmpty()
            conversationDao.getAllConversations().flatMapLatest { conversationEntities ->
                if (conversationEntities.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val flows = conversationEntities.map { convEntity ->
                        combine(
                            messageDao.getLatestMessage(convEntity.id),
                            messageDao.getUnreadCount(convEntity.id, localUserId),
                            conversationMemberDao.getOtherMember(convEntity.id)
                        ) { latestMsg, unreadCount, otherMemberEntity ->
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
                                otherMember = otherMemberEntity?.toDomain()
                            )
                        }
                    }
                    combine(flows) { it.toList() }
                }
            }
        }
    }

    override fun getConversation(conversationId: String): Flow<Conversation?> {
        return combine(
            conversationDao.getConversationById(conversationId),
            messageDao.getLatestMessage(conversationId),
            conversationMemberDao.getOtherMember(conversationId)
        ) { convEntity, latestMsg, otherMemberEntity ->
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
                    otherMember = otherMemberEntity?.toDomain()
                )
            }
        }
    }

    override fun getConversationMembers(conversationId: String): Flow<List<User>> {
        return conversationMemberDao.getMembersForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
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
        messageDao.markMessagesAsRead(conversationId, localUserId)
    }
}
