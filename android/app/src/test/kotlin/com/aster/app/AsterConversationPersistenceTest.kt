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
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.repository.getCanonicalDirectConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AsterConversationPersistenceTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var webSocketClient: AsterWebSocketClient
    private lateinit var messageRepository: MessageRepositoryImpl

    private val userM21 = UserEntity("user_m21", "m21", "Samsung M21", isLocalUser = true, isOnline = true)
    private val userWaydroid = UserEntity("user_waydroid", "waydroid", "Waydroid Client", isLocalUser = false, isOnline = true)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()
        authStorage.saveAuthenticatedUserId("user_m21")
        authStorage.saveAuthToken("ast_tok_m21")

        webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage, context)

        messageRepository = MessageRepositoryImpl(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient,
            notificationManager = null
        )
    }

    @After
    fun tearDown() {
        webSocketClient.cleanup()
        database.close()
    }

    private suspend fun awaitMessage(messageId: String): MessageEntity? {
        var msg: MessageEntity? = null
        for (i in 0 until 50) {
            msg = database.messageDao().getMessageById(messageId)
            if (msg != null) break
            Thread.sleep(20)
        }
        return msg
    }

    @Test
    fun canonicalDirectConversationId_isOrderInvariant() {
        val id1 = getCanonicalDirectConversationId("user_m21", "user_waydroid")
        val id2 = getCanonicalDirectConversationId("user_waydroid", "user_m21")

        assertEquals("conv_user_m21_user_waydroid", id1)
        assertEquals(id1, id2)

        // Additional pair tests
        assertEquals(
            getCanonicalDirectConversationId("user_b", "user_a"),
            getCanonicalDirectConversationId("user_a", "user_b")
        )
        assertEquals("conv_user_a_user_b", getCanonicalDirectConversationId("user_b", "user_a"))
    }

    @Test
    fun cascadeDeleteTrap_insertConversationWithIgnoreDoesNotDeleteExistingMessages() = runTest {
        database.userDao().insertUsers(listOf(userM21, userWaydroid))

        val convId = getCanonicalDirectConversationId(userM21.id, userWaydroid.id)
        val now = System.currentTimeMillis()

        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(convId, userM21.id, "MEMBER", now),
            ConversationMemberEntity(convId, userWaydroid.id, "MEMBER", now)
        ))

        // Existing sent message in Room
        val msg1 = MessageEntity("msg_sent_1", convId, userM21.id, "Hello from M21 LTE", now, MessageStatus.SENT)
        database.messageDao().insertMessage(msg1)

        val beforeCount = database.messageDao().getMessagesForConversation(convId).first().size
        assertEquals(1, beforeCount)

        // Simulating the bug condition: a reconnect or incoming frame calls insertConversation on the same convId
        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now + 1000)
        )

        // CRITICAL VERIFICATION: With OnConflictStrategy.IGNORE, SQLite does NOT cascade-delete the messages!
        val afterCount = database.messageDao().getMessagesForConversation(convId).first().size
        assertEquals(1, afterCount)
        val retrieved = database.messageDao().getMessageById("msg_sent_1")
        assertNotNull(retrieved)
        assertEquals("Hello from M21 LTE", retrieved?.body)
    }

    @Test
    fun cascadeDeleteTrap_insertUserWithIgnoreDoesNotDeleteExistingMessages() = runTest {
        database.userDao().insertUsers(listOf(userM21, userWaydroid))

        val convId = getCanonicalDirectConversationId(userM21.id, userWaydroid.id)
        val now = System.currentTimeMillis()

        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(convId, userM21.id, "MEMBER", now),
            ConversationMemberEntity(convId, userWaydroid.id, "MEMBER", now)
        ))

        val msg1 = MessageEntity("msg_sent_1", convId, userM21.id, "Hello from M21", now, MessageStatus.SENT)
        database.messageDao().insertMessage(msg1)

        assertEquals(1, database.messageDao().getMessagesForConversation(convId).first().size)

        // Re-inserting user (e.g. from incoming message sender check or friend list sync)
        database.userDao().insertUser(userWaydroid)

        // Messages must NOT be cascade-deleted
        assertEquals(1, database.messageDao().getMessagesForConversation(convId).first().size)
    }

    @Test
    fun incomingDirectMessage_resolvesToExistingDirectConversationAndPreservesHistory() = runTest {
        database.userDao().insertUsers(listOf(userM21, userWaydroid))

        val canonicalConvId = getCanonicalDirectConversationId(userM21.id, userWaydroid.id)
        val now = System.currentTimeMillis()

        // 1. M21 already sent a message previously
        database.conversationDao().insertConversation(
            ConversationEntity(id = canonicalConvId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(canonicalConvId, userM21.id, "MEMBER", now),
            ConversationMemberEntity(canonicalConvId, userWaydroid.id, "MEMBER", now)
        ))
        database.messageDao().insertMessage(
            MessageEntity("msg_1", canonicalConvId, userM21.id, "Message 1 sent from M21", now, MessageStatus.SENT)
        )

        // Verify initial state: 1 message in canonical conversation
        val initialMessages = database.messageDao().getMessagesForConversation(canonicalConvId).first()
        assertEquals(1, initialMessages.size)

        // 2. Incoming message arrives from Waydroid over wire with an arbitrary or asymmetric wire conversationId
        val incomingFrame = ServerMessage.RelayMessage(
            messageId = "msg_2",
            conversationId = "conv_user_waydroid", // Wire conversationId sent by client/backend
            senderId = userWaydroid.id,
            body = "Reply from Waydroid after M21 reconnect",
            timestamp = now + 5000
        )
        webSocketClient.emitIncomingMessageForTesting(incomingFrame)

        // Await Room insertion on background thread
        val storedMsg = awaitMessage("msg_2")
        assertNotNull("Incoming message should be stored in Room", storedMsg)

        // 3. Verify: The incoming message resolved to the existing canonical conversation!
        val updatedMessages = database.messageDao().getMessagesForConversation(canonicalConvId).first()
        assertEquals(2, updatedMessages.size)
        assertEquals("msg_1", updatedMessages[0].id)
        assertEquals("Message 1 sent from M21", updatedMessages[0].body)
        assertEquals("msg_2", updatedMessages[1].id)
        assertEquals("Reply from Waydroid after M21 reconnect", updatedMessages[1].body)

        // Verify only 1 conversation exists in the entire database
        val totalConversations = database.conversationDao().getConversationCount()
        assertEquals(1, totalConversations)
    }

    @Test
    fun incomingMessage_idempotentDuplicateDeliveryDoesNotDuplicateOrDelete() = runTest {
        database.userDao().insertUsers(listOf(userM21, userWaydroid))

        val canonicalConvId = getCanonicalDirectConversationId(userM21.id, userWaydroid.id)
        val now = System.currentTimeMillis()

        database.conversationDao().insertConversation(
            ConversationEntity(id = canonicalConvId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(canonicalConvId, userM21.id, "MEMBER", now),
            ConversationMemberEntity(canonicalConvId, userWaydroid.id, "MEMBER", now)
        ))

        val incomingFrame = ServerMessage.RelayMessage(
            messageId = "msg_dup_1",
            conversationId = canonicalConvId,
            senderId = userWaydroid.id,
            body = "Idempotent message test",
            timestamp = now
        )

        // Deliver first time
        webSocketClient.emitIncomingMessageForTesting(incomingFrame)
        assertNotNull(awaitMessage("msg_dup_1"))
        assertEquals(1, database.messageDao().getMessagesForConversation(canonicalConvId).first().size)

        // Deliver second time (e.g. network retry / mailbox replay)
        webSocketClient.emitIncomingMessageForTesting(incomingFrame)
        Thread.sleep(100)

        // Count must strictly remain 1
        val messages = database.messageDao().getMessagesForConversation(canonicalConvId).first()
        assertEquals(1, messages.size)
        assertEquals("msg_dup_1", messages[0].id)
    }

    @Test
    fun mailboxReplay_offlineMessagesAppendSeamlesslyToExistingHistory() = runTest {
        database.userDao().insertUsers(listOf(userM21, userWaydroid))

        val canonicalConvId = getCanonicalDirectConversationId(userM21.id, userWaydroid.id)
        val now = System.currentTimeMillis()

        // 2 pre-existing messages
        database.conversationDao().insertConversation(
            ConversationEntity(id = canonicalConvId, title = "Waydroid Client", isGroup = false, createdAt = now, updatedAt = now)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(canonicalConvId, userM21.id, "MEMBER", now),
            ConversationMemberEntity(canonicalConvId, userWaydroid.id, "MEMBER", now)
        ))
        database.messageDao().insertMessage(
            MessageEntity("msg_old_1", canonicalConvId, userM21.id, "Old 1", now - 2000, MessageStatus.SENT)
        )
        database.messageDao().insertMessage(
            MessageEntity("msg_old_2", canonicalConvId, userWaydroid.id, "Old 2", now - 1000, MessageStatus.DELIVERED)
        )

        assertEquals(2, database.messageDao().getMessagesForConversation(canonicalConvId).first().size)

        // Simulate mailbox replay of 3 offline messages while M21 was disconnected
        for (i in 1..3) {
            val frame = ServerMessage.RelayMessage(
                messageId = "msg_offline_$i",
                conversationId = canonicalConvId,
                senderId = userWaydroid.id,
                body = "Offline message $i",
                timestamp = now + (i * 1000)
            )
            webSocketClient.emitIncomingMessageForTesting(frame)
        }

        // Await last offline message
        assertNotNull(awaitMessage("msg_offline_3"))

        // All 5 messages must be present in order
        val allMessages = database.messageDao().getMessagesForConversation(canonicalConvId).first()
        assertEquals(5, allMessages.size)
        assertEquals("msg_old_1", allMessages[0].id)
        assertEquals("msg_old_2", allMessages[1].id)
        assertEquals("msg_offline_1", allMessages[2].id)
        assertEquals("msg_offline_2", allMessages[3].id)
        assertEquals("msg_offline_3", allMessages[4].id)
    }
}
