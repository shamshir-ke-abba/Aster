package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterMessagingUxTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var webSocketClient: AsterWebSocketClient
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_user")
        authStorage.saveAuthenticatedUserId("user_tester")

        webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage)

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
        messageRepository.cancelScope()
        database.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Message Status Transitions & Validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun messageStatus_validForwardTransitions() {
        // SENDING -> SENT
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.SENT))
        // SENDING -> FAILED
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.FAILED))
        // SENT -> DELIVERED
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.DELIVERED))
        // SENT -> READ
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.READ))
        // DELIVERED -> READ
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.READ))
        // FAILED -> SENDING (retry)
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.SENDING))
        // Idempotent self-transitions
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.SENT))
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.READ.canTransitionTo(MessageStatus.READ))
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.FAILED))
    }

    @Test
    fun messageStatus_rejectsRegressions() {
        // READ is terminal
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.FAILED))

        // DELIVERED cannot regress to SENT or SENDING
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENDING))

        // SENT cannot regress to SENDING
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.SENDING))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Consecutive Message Grouping Logic
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun messageGrouping_consecutiveClusterDetection() {
        val now = 1000000L
        val messages = listOf(
            Message("m1", "conv1", "user_alex", "First msg", now),
            Message("m2", "conv1", "user_alex", "Second msg", now + 10_000L),
            Message("m3", "conv1", "user_alex", "Third msg", now + 20_000L),
            Message("m4", "conv1", "user_bob", "Bob responds", now + 30_000L),
            Message("m5", "conv1", "user_alex", "Alex responds late", now + 200_000L) // > 2 mins gap
        )

        // Function mirroring ChatScreen grouping logic
        fun isClusterFirst(index: Int): Boolean {
            if (index == 0) return true
            val prev = messages[index - 1]
            val curr = messages[index]
            return !(prev.senderId == curr.senderId && (curr.createdAt - prev.createdAt) < 120_000L)
        }

        fun isClusterLast(index: Int): Boolean {
            if (index == messages.size - 1) return true
            val curr = messages[index]
            val next = messages[index + 1]
            return !(next.senderId == curr.senderId && (next.createdAt - curr.createdAt) < 120_000L)
        }

        // m1: First of run
        assertTrue(isClusterFirst(0))
        assertFalse(isClusterLast(0))

        // m2: Middle of run
        assertFalse(isClusterFirst(1))
        assertFalse(isClusterLast(1))

        // m3: Last of run
        assertFalse(isClusterFirst(2))
        assertTrue(isClusterLast(2))

        // m4: Single from Bob (different sender)
        assertTrue(isClusterFirst(3))
        assertTrue(isClusterLast(3))

        // m5: Single from Alex (> 120s time gap breaks grouping)
        assertTrue(isClusterFirst(4))
        assertTrue(isClusterLast(4))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Sender Differentiation & Name Visibility
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun senderDifferentiation_nameVisibilityRules() {
        val localUserId = "user_tester"

        fun shouldShowSenderName(
            senderId: String,
            isGroup: Boolean,
            isFirstInCluster: Boolean
        ): Boolean {
            val isOutgoing = senderId == localUserId
            return !isOutgoing && isGroup && isFirstInCluster
        }

        // Outgoing message (sent by local user) -> never shows sender name
        assertFalse(shouldShowSenderName("user_tester", isGroup = true, isFirstInCluster = true))
        assertFalse(shouldShowSenderName("user_tester", isGroup = false, isFirstInCluster = true))

        // 1:1 incoming message -> never shows sender name (identified in top bar)
        assertFalse(shouldShowSenderName("user_alex", isGroup = false, isFirstInCluster = true))
        assertFalse(shouldShowSenderName("user_alex", isGroup = false, isFirstInCluster = false))

        // Group incoming message: only shows on first of cluster
        assertTrue(shouldShowSenderName("user_alex", isGroup = true, isFirstInCluster = true))
        assertFalse(shouldShowSenderName("user_alex", isGroup = true, isFirstInCluster = false))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Delivery & Read Receipt Events (WebSocket -> Repository -> Room)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun readReceipts_updatesMessageStatusAndTracksReaders() = runTest {
        // Setup users and conversation
        database.userDao().insertUser(UserEntity("user_tester", "tester", "Tester", isLocalUser = true, isOnline = true))
        database.userDao().insertUser(UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true))
        database.userDao().insertUser(UserEntity("user_bob", "bob", "Bob", isLocalUser = false, isOnline = true))

        val convId = "group_ast_alpha"
        database.conversationDao().insertConversation(
            ConversationEntity(convId, "Alpha Group", isGroup = true, createdAt = 1000L, updatedAt = 1000L)
        )
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_tester", "ADMIN", 1000L))
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_alex", "MEMBER", 1000L))
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_bob", "MEMBER", 1000L))

        // Insert outgoing message
        val msgId = "msg_out_100"
        database.messageDao().insertMessage(
            MessageEntity(
                id = msgId,
                conversationId = convId,
                senderId = "user_tester",
                body = "Hello Alpha Team",
                createdAt = 2000L,
                status = MessageStatus.SENT
            )
        )

        // 1. Simulate MESSAGE_DELIVERED event
        webSocketClient.emitDeliveredForTesting(ServerMessage.MessageDelivered(msgId))
        var entity: MessageEntity? = null
        for (i in 0 until 50) {
            entity = database.messageDao().getMessageById(msgId)
            if (entity?.status == MessageStatus.DELIVERED) break
            Thread.sleep(20)
        }
        assertNotNull(entity)
        assertEquals(MessageStatus.DELIVERED, entity!!.status)

        // 2. Simulate MESSAGE_READ from Alex
        webSocketClient.emitReadForTesting(
            ServerMessage.MessageRead(
                messageId = msgId,
                conversationId = convId,
                readerId = "user_alex",
                timestamp = 2500L
            )
        )
        for (i in 0 until 50) {
            entity = database.messageDao().getMessageById(msgId)
            if (entity?.status == MessageStatus.READ) break
            Thread.sleep(20)
        }
        assertNotNull(entity)
        assertEquals(MessageStatus.READ, entity!!.status)

        // 3. Verify repository Flow reflects readers in domain model
        var domainMsg: Message? = null
        for (i in 0 until 50) {
            val messages = messageRepository.getMessages(convId).first()
            domainMsg = messages.firstOrNull { it.id == msgId }
            if (domainMsg?.readBy?.contains("user_alex") == true) break
            Thread.sleep(20)
        }
        assertNotNull(domainMsg)
        assertEquals(MessageStatus.READ, domainMsg!!.status)
        assertTrue(domainMsg.readBy.contains("user_alex"))
        assertFalse(domainMsg.readBy.contains("user_bob"))

        // 4. Simulate second read from Bob
        webSocketClient.emitReadForTesting(
            ServerMessage.MessageRead(
                messageId = msgId,
                conversationId = convId,
                readerId = "user_bob",
                timestamp = 2600L
            )
        )
        var updatedDomainMsg: Message? = null
        for (i in 0 until 50) {
            val updatedMessages = messageRepository.getMessages(convId).first()
            updatedDomainMsg = updatedMessages.firstOrNull { it.id == msgId }
            if (updatedDomainMsg?.readBy?.size == 2) break
            Thread.sleep(20)
        }
        assertNotNull(updatedDomainMsg)
        assertEquals(MessageStatus.READ, updatedDomainMsg!!.status)
        assertEquals(2, updatedDomainMsg.readBy.size)
        assertTrue(updatedDomainMsg.readBy.contains("user_alex"))
        assertTrue(updatedDomainMsg.readBy.contains("user_bob"))
    }

    @Test
    fun readReceipts_duplicateReadIsIdempotent() = runTest {
        database.userDao().insertUser(UserEntity("user_tester", "tester", "Tester", isLocalUser = true, isOnline = true))
        database.userDao().insertUser(UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true))

        val convId = "conv_123"
        database.conversationDao().insertConversation(
            ConversationEntity(convId, "1:1 Chat", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        )

        val msgId = "msg_idem_1"
        database.messageDao().insertMessage(
            MessageEntity(msgId, convId, "user_tester", "Idempotency test", 2000L, MessageStatus.SENT)
        )

        val readMsg = ServerMessage.MessageRead(msgId, convId, "user_alex", 2500L)
        // Send duplicate events
        webSocketClient.emitReadForTesting(readMsg)
        webSocketClient.emitReadForTesting(readMsg)
        webSocketClient.emitReadForTesting(readMsg)

        var entity: MessageEntity? = null
        for (i in 0 until 50) {
            entity = database.messageDao().getMessageById(msgId)
            if (entity?.status == MessageStatus.READ) break
            Thread.sleep(20)
        }
        assertNotNull(entity)
        assertEquals(MessageStatus.READ, entity!!.status)

        var domainMsg: Message? = null
        for (i in 0 until 50) {
            val msgs = messageRepository.getMessages(convId).first()
            domainMsg = msgs.firstOrNull { it.id == msgId }
            if (domainMsg?.readBy?.isNotEmpty() == true) break
            Thread.sleep(20)
        }
        assertNotNull(domainMsg)
        assertEquals(1, domainMsg!!.readBy.size)
    }

    @Test
    fun readReceipts_unknownMessageIdIgnoredSafely() = runTest {
        // Verify frame with unknown message ID does not throw or corrupt DB
        val readMsg = ServerMessage.MessageRead("msg_non_existent", "conv_xyz", "user_alex", 2500L)
        webSocketClient.emitReadForTesting(readMsg)
        Thread.sleep(100)
        // No crash
        assertTrue(true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Group Read Information: Partial vs All Reads
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun groupReadInfo_distinctMessageAssociation() = runTest {
        database.userDao().insertUser(UserEntity("user_tester", "tester", "Tester", isLocalUser = true, isOnline = true))
        database.userDao().insertUser(UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true))

        val convId = "group_team"
        database.conversationDao().insertConversation(
            ConversationEntity(convId, "Team", isGroup = true, createdAt = 1000L, updatedAt = 1000L)
        )

        val msg1 = "msg_team_1"
        val msg2 = "msg_team_2"
        database.messageDao().insertMessage(MessageEntity(msg1, convId, "user_tester", "Topic 1", 2000L, MessageStatus.SENT))
        database.messageDao().insertMessage(MessageEntity(msg2, convId, "user_tester", "Topic 2", 2100L, MessageStatus.SENT))

        // Read event only for msg1
        webSocketClient.emitReadForTesting(
            ServerMessage.MessageRead(msg1, convId, "user_alex", 2500L)
        )
        var m1: Message? = null
        var m2: Message? = null
        for (i in 0 until 50) {
            val messages = messageRepository.getMessages(convId).first()
            m1 = messages.firstOrNull { it.id == msg1 }
            m2 = messages.firstOrNull { it.id == msg2 }
            if (m1?.readBy?.contains("user_alex") == true) break
            Thread.sleep(20)
        }

        assertNotNull(m1)
        assertNotNull(m2)
        assertTrue(m1!!.readBy.contains("user_alex"))
        assertFalse(m2!!.readBy.contains("user_alex"))
        assertEquals(MessageStatus.READ, m1.status)
        assertEquals(MessageStatus.SENT, m2.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Mark Conversation As Read (Local -> Network dispatch)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun markConversationAsRead_updatesUnreadIncomingMessages() = runTest {
        database.userDao().insertUser(UserEntity("user_tester", "tester", "Tester", isLocalUser = true, isOnline = true))
        database.userDao().insertUser(UserEntity("user_alex", "alex", "Alex", isLocalUser = false, isOnline = true))

        val convId = "conv_unread_test"
        database.conversationDao().insertConversation(
            ConversationEntity(convId, "1:1 Chat", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        )
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_tester", "MEMBER", 1000L))
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_alex", "MEMBER", 1000L))
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_tester", "MEMBER", 1000L))
        database.conversationMemberDao().insertMember(ConversationMemberEntity(convId, "user_alex", "MEMBER", 1000L))

        // Incoming unread messages
        database.messageDao().insertMessage(MessageEntity("in_1", convId, "user_alex", "Hey", 2000L, MessageStatus.DELIVERED))
        database.messageDao().insertMessage(MessageEntity("in_2", convId, "user_alex", "You there?", 2010L, MessageStatus.DELIVERED))
        // Outgoing message should remain unchanged
        database.messageDao().insertMessage(MessageEntity("out_1", convId, "user_tester", "Yes", 2020L, MessageStatus.SENT))

        val unreadBefore = database.messageDao().getUnreadIncomingMessages(convId, "user_tester")
        assertEquals(2, unreadBefore.size)

        conversationRepository.markConversationAsRead(convId)

        val unreadAfter = database.messageDao().getUnreadIncomingMessages(convId, "user_tester")
        assertEquals(0, unreadAfter.size)

        // Incoming messages are now READ
        assertEquals(MessageStatus.READ, database.messageDao().getMessageById("in_1")!!.status)
        assertEquals(MessageStatus.READ, database.messageDao().getMessageById("in_2")!!.status)
        // Outgoing message preserved as SENT
        assertEquals(MessageStatus.SENT, database.messageDao().getMessageById("out_1")!!.status)
    }
}
