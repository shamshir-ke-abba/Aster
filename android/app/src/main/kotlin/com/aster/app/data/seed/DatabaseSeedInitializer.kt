package com.aster.app.data.seed

import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes development/testing seed data in Room if the database is empty.
 *
 * Persists real SQLite entities so all subsequent mutations, messaging, and
 * read states are real local-first database operations that survive app restarts.
 */
@Singleton
class DatabaseSeedInitializer @Inject constructor(
    private val userDao: UserDao,
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val messageDao: MessageDao
) {

    suspend fun initializeIfNeeded() = withContext(Dispatchers.IO) {
        if (userDao.getUserCount() > 0) {
            return@withContext
        }

        val now = System.currentTimeMillis()

        // 1. Users: Current local user ("Saad") and sample contacts
        val localUser = UserEntity(
            id = "user_saad",
            username = "saad",
            displayName = "Saad",
            isLocalUser = true,
            isOnline = true
        )
        val alex = UserEntity(
            id = "user_alex",
            username = "alex",
            displayName = "Alex",
            isLocalUser = false,
            isOnline = true
        )
        val rohan = UserEntity(
            id = "user_rohan",
            username = "rohan",
            displayName = "Rohan",
            isLocalUser = false,
            isOnline = false
        )
        val sam = UserEntity(
            id = "user_sam",
            username = "sam",
            displayName = "Sam",
            isLocalUser = false,
            isOnline = true
        )

        userDao.insertUsers(listOf(localUser, alex, rohan, sam))

        // 2. Conversation 1: 1:1 with Alex
        val convAlex = ConversationEntity(
            id = "conv_alex",
            title = "Alex",
            isGroup = false,
            createdAt = now - 86400000,
            updatedAt = now - 900000
        )
        val membersAlex = listOf(
            ConversationMemberEntity(convAlex.id, localUser.id, "MEMBER", now - 86400000),
            ConversationMemberEntity(convAlex.id, alex.id, "MEMBER", now - 86400000)
        )
        val messagesAlex = listOf(
            MessageEntity(
                id = "msg_alex_1",
                conversationId = convAlex.id,
                senderId = alex.id,
                body = "Hey Saad! Are you coming tonight?",
                createdAt = now - 3600000,
                status = MessageStatus.READ
            ),
            MessageEntity(
                id = "msg_alex_2",
                conversationId = convAlex.id,
                senderId = localUser.id,
                body = "Yeah I'm coming 😂",
                createdAt = now - 1800000,
                status = MessageStatus.SENT
            ),
            MessageEntity(
                id = "msg_alex_3",
                conversationId = convAlex.id,
                senderId = alex.id,
                body = "Nice. What time?",
                createdAt = now - 900000,
                status = MessageStatus.DELIVERED
            )
        )

        // 3. Conversation 2: 1:1 with Rohan
        val convRohan = ConversationEntity(
            id = "conv_rohan",
            title = "Rohan",
            isGroup = false,
            createdAt = now - 172800000,
            updatedAt = now - 7200000
        )
        val membersRohan = listOf(
            ConversationMemberEntity(convRohan.id, localUser.id, "MEMBER", now - 172800000),
            ConversationMemberEntity(convRohan.id, rohan.id, "MEMBER", now - 172800000)
        )
        val messagesRohan = listOf(
            MessageEntity(
                id = "msg_rohan_1",
                conversationId = convRohan.id,
                senderId = rohan.id,
                body = "Send me that picture",
                createdAt = now - 7200000,
                status = MessageStatus.DELIVERED
            )
        )

        conversationDao.insertConversations(listOf(convAlex, convRohan))
        conversationMemberDao.insertMembers(membersAlex + membersRohan)
        messageDao.insertMessages(messagesAlex + messagesRohan)
    }
}
