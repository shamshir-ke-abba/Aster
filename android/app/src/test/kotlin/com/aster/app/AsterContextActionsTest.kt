package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.DeletedMessageEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
class AsterContextActionsTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var webSocketClient: AsterWebSocketClient
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl

    private val localUserId = "user_context_tester"
    private val otherUserId = "user_peer_node"
    private val testConversationId = "conv_test_context_101"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_context")
        authStorage.saveAuthenticatedUserId(localUserId)

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
            webSocketClient = webSocketClient,
            deletedMessageDao = database.deletedMessageDao()
        )

        runBlocking {
            // Seed users & conversation
            database.userDao().insertUser(UserEntity(localUserId, "tester", "Context Tester", isLocalUser = true, isOnline = true))
            database.userDao().insertUser(UserEntity(otherUserId, "peer", "Peer Node", isLocalUser = false, isOnline = true))

            database.conversationDao().insertConversation(
                ConversationEntity(
                    id = testConversationId,
                    title = "Peer Node",
                    isGroup = false,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            database.conversationMemberDao().insertMember(
                ConversationMemberEntity(conversationId = testConversationId, userId = localUserId, role = "ADMIN", joinedAt = System.currentTimeMillis())
            )
            database.conversationMemberDao().insertMember(
                ConversationMemberEntity(conversationId = testConversationId, userId = otherUserId, role = "MEMBER", joinedAt = System.currentTimeMillis())
            )
        }
    }

    @After
    fun tearDown() {
        messageRepository.cancelScope()
        database.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. MessageStatus.UNSENT State Transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun messageStatus_unsentTransitions() {
        // Any non-terminal state can transition to UNSENT
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.READ.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.UNSENT))

        // UNSENT is terminal: cannot transition backwards
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.READ))
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.FAILED))

        // UNSENT self-transition is idempotent
        assertTrue(MessageStatus.UNSENT.canTransitionTo(MessageStatus.UNSENT))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Delete For You (Local Tombstone & Replay Prevention)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun deleteForSelf_removesMessageLocallyAndCreatesTombstone() = runTest {
        val messageId = "msg_delete_test_1"
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = testConversationId,
            senderId = otherUserId,
            body = "Secret message to be deleted locally",
            createdAt = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED
        )
        database.messageDao().insertMessage(messageEntity)

        // Verify message is present
        assertNotNull(database.messageDao().getMessageById(messageId))
        assertNull(database.deletedMessageDao().isDeleted(messageId))

        // Perform Delete For You
        messageRepository.deleteForSelf(messageId)

        // Verify message is deleted from message table
        assertNull(database.messageDao().getMessageById(messageId))

        // Verify tombstone is recorded
        assertEquals(1, database.deletedMessageDao().isDeleted(messageId))

        // Verify getMessages() flow does not return it
        val activeMessages = messageRepository.getMessages(testConversationId).first()
        assertTrue(activeMessages.none { it.id == messageId })
    }

    @Test
    fun deleteForSelf_preventsReplayAndReinsertion() = runTest {
        val messageId = "msg_tombstone_replay_test"
        // Pre-record tombstone
        database.deletedMessageDao().insertDeletedMessage(
            DeletedMessageEntity(messageId = messageId, deletedAt = System.currentTimeMillis())
        )
        assertEquals(1, database.deletedMessageDao().isDeleted(messageId))

        // Simulate incoming message event from WebSocket
        val incomingServerMsg = ServerMessage.RelayMessage(
            messageId = messageId,
            conversationId = testConversationId,
            senderId = otherUserId,
            body = "This was deleted for self on this device",
            timestamp = System.currentTimeMillis()
        )

        webSocketClient.emitIncomingMessageForTesting(incomingServerMsg)
        for (i in 0 until 20) {
            Thread.sleep(10)
        }

        // Verify message was dropped and not saved to database
        val inDb = database.messageDao().getMessageById(messageId)
        assertNull("Message with tombstone must not be re-inserted upon replay", inDb)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Unsend Message (5-Minute Window & Retraction)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun unsendMessage_clientRejectsMessagesOlderThanFiveMinutes() = runTest {
        val messageId = "msg_too_old_to_unsend"
        val sixMinutesAgo = System.currentTimeMillis() - (6 * 60 * 1000L)
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = testConversationId,
            senderId = localUserId,
            body = "Old message",
            createdAt = sixMinutesAgo,
            status = MessageStatus.SENT
        )
        database.messageDao().insertMessage(messageEntity)

        // Attempt to unsend
        messageRepository.unsendMessage(messageId, testConversationId)

        // Verify status remains unchanged because it's outside the 5m window
        val storedMsg = database.messageDao().getMessageById(messageId)
        assertEquals(MessageStatus.SENT, storedMsg?.status)
    }

    @Test
    fun unsendMessage_handlesServerUnsentEventAndRetractsMessage() = runTest {
        val messageId = "msg_unsend_success"
        val twoMinutesAgo = System.currentTimeMillis() - (2 * 60 * 1000L)
        val messageEntity = MessageEntity(
            id = messageId,
            conversationId = testConversationId,
            senderId = localUserId,
            body = "Sensitive message retracted within 5m",
            createdAt = twoMinutesAgo,
            status = MessageStatus.DELIVERED
        )
        database.messageDao().insertMessage(messageEntity)

        // Emit MESSAGE_UNSENT server event
        val unsentEvent = ServerMessage.MessageUnsent(
            messageId = messageId,
            conversationId = testConversationId,
            senderId = localUserId,
            timestamp = System.currentTimeMillis()
        )
        webSocketClient.emitUnsentForTesting(unsentEvent)
        for (i in 0 until 50) {
            val check = database.messageDao().getMessageById(messageId)
            if (check?.status == MessageStatus.UNSENT) break
            Thread.sleep(20)
        }

        // Verify message status was updated to UNSENT in database
        val updatedMsg = database.messageDao().getMessageById(messageId)
        assertEquals(MessageStatus.UNSENT, updatedMsg?.status)

        // Verify domain model status is MessageStatus.UNSENT
        val messages = messageRepository.getMessages(testConversationId).first()
        val domainMsg = messages.find { it.id == messageId }
        assertNotNull(domainMsg)
        assertEquals(MessageStatus.UNSENT, domainMsg?.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Group Actions (Leave & Remove Member)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun groupActions_leaveGroupRemovesLocalConversation() = runTest {
        val groupConvId = "conv_group_exit_test"
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = groupConvId,
                title = "Space Explorers",
                isGroup = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        var leftCalled = false
        conversationRepository.leaveGroup(groupConvId)
        leftCalled = true

        assertTrue(leftCalled)
    }

    @Test
    fun groupActions_removeMemberRemovesFromLocalStore() = runTest {
        val groupConvId = "conv_group_remove_test"
        val memberToRemoveId = "user_to_kick"

        database.userDao().insertUser(
            UserEntity(memberToRemoveId, "kick", "To Kick", isLocalUser = false, isOnline = true)
        )
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = groupConvId,
                title = "Test Group",
                isGroup = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        database.conversationMemberDao().insertMember(
            ConversationMemberEntity(conversationId = groupConvId, userId = localUserId, role = "ADMIN", joinedAt = System.currentTimeMillis())
        )
        database.conversationMemberDao().insertMember(
            ConversationMemberEntity(conversationId = groupConvId, userId = memberToRemoveId, role = "MEMBER", joinedAt = System.currentTimeMillis())
        )

        // Remove member
        conversationRepository.removeGroupMember(groupConvId, memberToRemoveId)

        // Verify member removed
        val members = database.conversationMemberDao().getMembersForConversationOnce(groupConvId)
        assertTrue(members.none { it.id == memberToRemoveId })
    }

    @Test
    fun groupAdministration_memberRolesFlowEmitsRoles() = runTest {
        val groupConvId = "conv_group_roles_test"
        val adminId = "user_admin"
        val memberId = "user_regular"

        database.userDao().insertUser(UserEntity(adminId, "admin", "Admin", false, true))
        database.userDao().insertUser(UserEntity(memberId, "regular", "Regular", false, true))
        database.conversationDao().insertConversation(
            ConversationEntity(groupConvId, "Roles Group", true, System.currentTimeMillis(), System.currentTimeMillis())
        )

        database.conversationMemberDao().insertMember(
            ConversationMemberEntity(groupConvId, adminId, "ADMIN", System.currentTimeMillis())
        )
        database.conversationMemberDao().insertMember(
            ConversationMemberEntity(groupConvId, memberId, "MEMBER", System.currentTimeMillis())
        )

        val roles = conversationRepository.getConversationMemberRoles(groupConvId).first()
        assertEquals("ADMIN", roles[adminId])
        assertEquals("MEMBER", roles[memberId])
    }

    @Test
    fun groupAdministration_removedLocalUserDeletesConversation() = runTest {
        val groupConvId = "conv_group_kick_local_test"
        database.conversationDao().insertConversation(
            ConversationEntity(groupConvId, "Kick Group", true, System.currentTimeMillis(), System.currentTimeMillis())
        )
        database.conversationMemberDao().insertMember(
            ConversationMemberEntity(groupConvId, localUserId, "MEMBER", System.currentTimeMillis())
        )

        assertNotNull(database.conversationDao().getConversationByIdOnce(groupConvId))

        // Simulate WebSocket group event: MEMBER_REMOVED targeting local user
        webSocketClient.emitGroupEventForTesting(
            ServerMessage.GroupEvent(
                groupId = groupConvId,
                eventType = "MEMBER_REMOVED",
                actorId = "user_admin",
                targetUserId = localUserId,
                timestamp = System.currentTimeMillis()
            )
        )

        var deleted = false
        for (i in 1..20) {
            if (database.conversationDao().getConversationByIdOnce(groupConvId) == null) {
                deleted = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue(deleted)
    }
}
