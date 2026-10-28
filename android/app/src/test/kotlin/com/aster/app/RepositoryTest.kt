package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var webSocketClient: com.aster.app.data.network.AsterWebSocketClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val authStorage = com.aster.app.data.auth.AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_saad")
        authStorage.saveAuthenticatedUserId("user_saad")
        webSocketClient = com.aster.app.data.network.AsterWebSocketClient(okhttp3.OkHttpClient(), authStorage)

        userRepository = UserRepositoryImpl(database.userDao())
        conversationRepository = ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient
        )
        messageRepository = MessageRepositoryImpl(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun userRepository_saveAndRetrieveLocalUser() = runTest {
        val user = User(
            id = "user_saad",
            username = "saad",
            displayName = "Saad",
            isLocalUser = true,
            isOnline = true
        )
        userRepository.saveUser(user)

        val local = userRepository.getLocalUser().first()
        assertNotNull(local)
        assertEquals("user_saad", local?.id)
        assertEquals("Saad", local?.displayName)
    }

    @Test
    fun messageRepository_sendMessage_createsAndPersistsMessageWithSentStatus() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = "c1",
            title = "Alex",
            isGroup = false,
            createdAt = now
        )
        conversationRepository.createConversation(conv, listOf("u1"))

        val sentMessage = messageRepository.sendMessage(
            conversationId = "c1",
            senderId = "u1",
            body = "Hello from local repository"
        )

        assertNotNull(sentMessage.id)
        assertEquals("c1", sentMessage.conversationId)
        assertEquals("u1", sentMessage.senderId)
        assertEquals("Hello from local repository", sentMessage.body)
        assertEquals(MessageStatus.SENDING, sentMessage.status)

        // Verify retrieval from flow (initially SENDING until server ACK)
        val messages = messageRepository.getMessages("c1").first()
        assertEquals(1, messages.size)
        assertEquals(sentMessage.id, messages[0].id)
        assertEquals("Hello from local repository", messages[0].body)
        assertEquals(MessageStatus.SENDING, messages[0].status)
    }

    @Test
    fun messageRepository_serverAckUpdatesStatusToSent() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = System.currentTimeMillis())
        conversationRepository.createConversation(conv, listOf("u1"))

        val sentMessage = messageRepository.sendMessage("c1", "u1", "Hello")
        assertEquals(MessageStatus.SENDING, sentMessage.status)

        // Simulate server ACK
        messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENT)

        val updatedMessages = messageRepository.getMessages("c1").first()
        assertEquals(MessageStatus.SENT, updatedMessages[0].status)
    }

    @Test
    fun conversationRepository_unreadCountAndMarkAsRead() = runTest {
        val localUser = UserEntity("u_local", "saad", "Saad", isLocalUser = true, isOnline = true)
        val remoteUser = UserEntity("u_remote", "alex", "Alex", isLocalUser = false, isOnline = true)
        database.userDao().insertUsers(listOf(localUser, remoteUser))

        val now = System.currentTimeMillis()
        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = now)
        conversationRepository.createConversation(conv, listOf("u_local", "u_remote"))

        // Insert incoming unread message from remote user
        val incomingMsg = MessageEntity(
            id = "m1",
            conversationId = "c1",
            senderId = "u_remote",
            body = "Hey!",
            createdAt = now,
            status = MessageStatus.DELIVERED
        )
        database.messageDao().insertMessage(incomingMsg)

        var conversations = conversationRepository.getConversations().first()
        assertEquals(1, conversations.size)
        assertEquals(1, conversations[0].unreadCount)
        assertEquals("Hey!", conversations[0].lastMessage?.body)

        // Mark conversation as read
        conversationRepository.markConversationAsRead("c1")

        conversations = conversationRepository.getConversations().first()
        assertEquals(0, conversations[0].unreadCount)
    }

    @Test
    fun messageRepository_repeatedAckIsHarmlessAndIdempotent() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = System.currentTimeMillis())
        conversationRepository.createConversation(conv, listOf("u1"))

        val sentMessage = messageRepository.sendMessage("c1", "u1", "Hello")

        // First ACK
        messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENT)
        var messages = messageRepository.getMessages("c1").first()
        assertEquals(MessageStatus.SENT, messages[0].status)

        // Duplicate ACK (should be harmless no-op)
        messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENT)
        messages = messageRepository.getMessages("c1").first()
        assertEquals(MessageStatus.SENT, messages[0].status)
    }

    @Test
    fun messageRepository_invalidStatusRegressionRejected() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = System.currentTimeMillis())
        conversationRepository.createConversation(conv, listOf("u1"))

        val sentMessage = messageRepository.sendMessage("c1", "u1", "Hello")
        messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENT)

        // Attempt invalid regression: SENT -> SENDING
        messageRepository.updateMessageStatus(sentMessage.id, MessageStatus.SENDING)

        // Status must NOT regress
        val messages = messageRepository.getMessages("c1").first()
        assertEquals(MessageStatus.SENT, messages[0].status)
    }

    @Test
    fun messageRepository_unknownMessageAckDoesNotCrash() = runTest {
        // Unknown ACK must be safely ignored without throwing
        messageRepository.updateMessageStatus("unknown_id_xyz", MessageStatus.SENT)
    }

    @Test
    fun messageRepository_rapidSendsMaintainStrictMonotonicOrdering() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = System.currentTimeMillis())
        conversationRepository.createConversation(conv, listOf("u1"))

        val sentMessages = mutableListOf<Message>()
        for (i in 1..5) {
            sentMessages.add(messageRepository.sendMessage("c1", "u1", "Rapid message $i"))
        }

        // Verify strictly monotonic timestamps: T1 < T2 < T3 < T4 < T5
        for (i in 0 until sentMessages.size - 1) {
            assertTrue(
                "Expected message ${i} timestamp (${sentMessages[i].createdAt}) < message ${i+1} (${sentMessages[i+1].createdAt})",
                sentMessages[i].createdAt < sentMessages[i+1].createdAt
            )
        }

        // Verify retrieval order matches send order exactly
        val messages = messageRepository.getMessages("c1").first()
        assertEquals(5, messages.size)
        for (i in 0 until 5) {
            assertEquals("Rapid message ${i+1}", messages[i].body)
        }
    }

    @Test
    fun messageDao_insertMessageIfNotExistsPreventsDuplicates() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val conv = Conversation("c1", "Alex", isGroup = false, createdAt = System.currentTimeMillis())
        conversationRepository.createConversation(conv, listOf("u1"))

        val msg = MessageEntity(
            id = "msg_dup_test",
            conversationId = "c1",
            senderId = "u1",
            body = "Original body",
            createdAt = 1000L,
            status = MessageStatus.READ
        )

        // First insert
        val row1 = database.messageDao().insertMessageIfNotExists(msg)
        assertTrue(row1 != -1L)

        // Duplicate insert with different status/body should be ignored by DB uniqueness
        val duplicateMsg = msg.copy(body = "Mutated body", status = MessageStatus.DELIVERED)
        val row2 = database.messageDao().insertMessageIfNotExists(duplicateMsg)
        assertEquals(-1L, row2)

        // Existing entity remains intact without regression
        val retrieved = database.messageDao().getMessageById("msg_dup_test")
        assertNotNull(retrieved)
        assertEquals("Original body", retrieved!!.body)
        assertEquals(MessageStatus.READ, retrieved.status)
    }

    @Test
    fun conversationRepository_getOrCreateDirectConversation_createsNewConversationAndMembers() = runTest {
        val localUser = UserEntity("user_saad", "saad", "Saad", isLocalUser = true, isOnline = true)
        val sam = UserEntity("user_sam", "sam", "Sam", isLocalUser = false, isOnline = true)
        database.userDao().insertUsers(listOf(localUser, sam))

        val conversation = conversationRepository.getOrCreateDirectConversation("user_sam")
        assertNotNull(conversation)
        assertEquals("Sam", conversation.title)
        assertFalse(conversation.isGroup)
        assertEquals("user_sam", conversation.otherMember?.id)

        // Verify database state: conversation entity exists
        val dbConv = database.conversationDao().getConversationByIdOnce(conversation.id)
        assertNotNull(dbConv)
        assertEquals("Sam", dbConv!!.title)

        // Verify database state: 2 membership records exist with foreign key integrity
        val members = database.conversationMemberDao().getMembersForConversationOnce(conversation.id)
        assertEquals(2, members.size)
        assertTrue(members.any { it.id == "user_saad" })
        assertTrue(members.any { it.id == "user_sam" })
    }

    @Test
    fun conversationRepository_getOrCreateDirectConversation_idempotentAndReturnsExisting() = runTest {
        val localUser = UserEntity("user_saad", "saad", "Saad", isLocalUser = true, isOnline = true)
        val sam = UserEntity("user_sam", "sam", "Sam", isLocalUser = false, isOnline = true)
        database.userDao().insertUsers(listOf(localUser, sam))

        val conv1 = conversationRepository.getOrCreateDirectConversation("user_sam")
        val conv2 = conversationRepository.getOrCreateDirectConversation("user_sam")

        assertEquals(conv1.id, conv2.id)
        assertEquals(conv1.title, conv2.title)

        // Database should only have 1 conversation
        val count = database.conversationDao().getConversationCount()
        assertEquals(1, count)
    }

    @Test
    fun conversationRepository_getOrCreateDirectConversation_findsExistingPreseededConversation() = runTest {
        val localUser = UserEntity("user_saad", "saad", "Saad", isLocalUser = true, isOnline = true)
        val alex = UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true)
        database.userDao().insertUsers(listOf(localUser, alex))

        // Preseed conversation conv_alex
        val now = System.currentTimeMillis()
        val convAlex = Conversation("conv_alex", "Alex", isGroup = false, createdAt = now)
        conversationRepository.createConversation(convAlex, listOf("user_saad", "user_alex"))

        // Call getOrCreateDirectConversation for Alex
        val conversation = conversationRepository.getOrCreateDirectConversation("user_alex")
        assertEquals("conv_alex", conversation.id)
        assertEquals("Alex", conversation.title)

        // Must not duplicate
        val count = database.conversationDao().getConversationCount()
        assertEquals(1, count)
    }

    @Test
    fun conversationRepository_getOrCreateDirectConversation_rejectsSelfOrUnknownUser() = runTest {
        val localUser = UserEntity("user_saad", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(localUser)

        var selfExceptionThrown = false
        try {
            conversationRepository.getOrCreateDirectConversation("user_saad")
        } catch (e: IllegalArgumentException) {
            selfExceptionThrown = true
        }
        assertTrue("Expected rejection when creating conversation with self", selfExceptionThrown)

        var unknownExceptionThrown = false
        try {
            conversationRepository.getOrCreateDirectConversation("user_unknown_999")
        } catch (e: IllegalArgumentException) {
            unknownExceptionThrown = true
        }
        assertTrue("Expected rejection when creating conversation with unknown user", unknownExceptionThrown)
    }

    @Test
    fun conversationRepository_createGroup_persistsGroupAndMembers() = runTest {
        val localUser = UserEntity("user_saad", "saad", "Saad", isLocalUser = true, isOnline = true)
        val userAlex = UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true)
        val userSam = UserEntity("user_sam", "sam", "Sam", isLocalUser = false, isOnline = false)
        database.userDao().insertUsers(listOf(localUser, userAlex, userSam))

        val group = conversationRepository.createGroup("Starlight Circle", listOf("user_alex", "user_sam"))
        assertTrue(group.isGroup)
        assertEquals("Starlight Circle", group.title)
        assertTrue(group.id.startsWith("group_"))

        // Verify conversation entity in Room
        val convEntity = database.conversationDao().getConversationByIdOnce(group.id)
        assertNotNull(convEntity)
        assertTrue(convEntity!!.isGroup)
        assertEquals("Starlight Circle", convEntity.title)

        // Verify members in Room
        val members = database.conversationMemberDao().getMembersForConversationOnce(group.id)
        assertEquals(3, members.size)
        assertTrue(members.any { it.id == "user_saad" })
        assertTrue(members.any { it.id == "user_alex" })
        assertTrue(members.any { it.id == "user_sam" })

        // Verify group appears in getConversations() flow
        val allConversations = conversationRepository.getConversations().first()
        val foundGroup = allConversations.firstOrNull { it.id == group.id }
        assertNotNull(foundGroup)
        assertTrue(foundGroup!!.isGroup)
        assertEquals("Starlight Circle", foundGroup.title)
    }

    @Test
    fun messageRepository_sendMessageToNonexistentConversation_ensuresConversationAndPersists() = runTest {
        val convId = "conv_user_76609505bca143bc_user_b49d508015d141b9"
        val senderId = "user_76609505bca143bc"
        val recipientId = "user_b49d508015d141b9"

        // Verify precondition: neither conversation nor recipient user exist in DB
        assertNull(database.conversationDao().getConversationByIdOnce(convId))
        assertNull(database.userDao().getUserByIdOnce(recipientId))

        // Send first message in the brand-new conversation
        val sentMessage = messageRepository.sendMessage(
            conversationId = convId,
            senderId = senderId,
            body = "First message in brand new conversation"
        )

        // 1. Verify message returned with correct metadata
        assertNotNull(sentMessage.id)
        assertEquals(convId, sentMessage.conversationId)
        assertEquals(senderId, sentMessage.senderId)
        assertEquals("First message in brand new conversation", sentMessage.body)
        assertEquals(MessageStatus.SENDING, sentMessage.status)

        // 2. Verify conversation row exists in Room
        val convEntity = database.conversationDao().getConversationByIdOnce(convId)
        assertNotNull("Conversation entity must exist before message insertion", convEntity)
        assertEquals(convId, convEntity!!.id)
        assertFalse(convEntity.isGroup)

        // 3. Verify sender and recipient users exist in Room
        val senderUser = database.userDao().getUserByIdOnce(senderId)
        assertNotNull("Sender user must exist in Room", senderUser)
        val recipientUser = database.userDao().getUserByIdOnce(recipientId)
        assertNotNull("Recipient user must exist in Room", recipientUser)

        // 4. Verify conversation members exist
        val members = database.conversationMemberDao().getMembersForConversationOnce(convId)
        assertEquals(2, members.size)
        assertTrue(members.any { it.id == senderId })
        assertTrue(members.any { it.id == recipientId })

        // 5. Verify message is persisted in Room
        val messages = messageRepository.getMessages(convId).first()
        assertEquals(1, messages.size)
        assertEquals(sentMessage.id, messages[0].id)
        assertEquals("First message in brand new conversation", messages[0].body)
    }

    @Test
    fun messageRepository_sendSecondMessageInSameConversation_doesNotDuplicateConversation() = runTest {
        val convId = "conv_user_alpha_user_beta"
        val senderId = "user_alpha"

        // First message creates conversation
        val msg1 = messageRepository.sendMessage(convId, senderId, "First message")
        val convAfterFirst = database.conversationDao().getConversationByIdOnce(convId)
        assertNotNull(convAfterFirst)
        val firstCreatedAt = convAfterFirst!!.createdAt

        // Second message in the same conversation
        val msg2 = messageRepository.sendMessage(convId, senderId, "Second message")

        // Verify conversation entity is NOT duplicated or recreated
        val convAfterSecond = database.conversationDao().getConversationByIdOnce(convId)
        assertNotNull(convAfterSecond)
        assertEquals(firstCreatedAt, convAfterSecond!!.createdAt)

        // Verify messages list contains both messages
        val messages = messageRepository.getMessages(convId).first()
        assertEquals(2, messages.size)
        assertEquals(msg1.id, messages[0].id)
        assertEquals("First message", messages[0].body)
        assertEquals(msg2.id, messages[1].id)
        assertEquals("Second message", messages[1].body)
    }

    @Test
    fun messageRepository_sendMessageToNonexistentGenericConversation_succeedsWithoutCrash() = runTest {
        val convId = "custom_unformatted_conv_id"
        val senderId = "user_unique_sender"

        assertNull(database.conversationDao().getConversationByIdOnce(convId))

        val sentMessage = messageRepository.sendMessage(
            conversationId = convId,
            senderId = senderId,
            body = "Generic fallback test"
        )

        assertNotNull(sentMessage.id)
        val convEntity = database.conversationDao().getConversationByIdOnce(convId)
        assertNotNull(convEntity)
        assertEquals(convId, convEntity!!.id)
        assertEquals("Direct Chat", convEntity.title)

        val messages = messageRepository.getMessages(convId).first()
        assertEquals(1, messages.size)
        assertEquals("Generic fallback test", messages[0].body)
    }
}
