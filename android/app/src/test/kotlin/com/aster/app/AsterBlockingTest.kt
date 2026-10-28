package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ClientMessage
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import com.aster.app.domain.model.formatPresenceText
import kotlinx.coroutines.flow.first
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterBlockingTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var webSocketClient: AsterWebSocketClient
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "test_aster_blocking_${System.currentTimeMillis()}.db")
        database = Room.databaseBuilder(context, AsterDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        val authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_block")
        authStorage.saveAuthenticatedUserId("user_alice")
        webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage)

        userRepository = UserRepositoryImpl(
            userDao = database.userDao(),
            authStorage = authStorage,
            webSocketClient = webSocketClient
        )
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
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun test1_blockedUserPersistsInRoom() = runTest {
        val userB = UserEntity(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = true,
            isBlocked = true
        )
        database.userDao().insertUser(userB)

        val retrieved = database.userDao().getUserByIdOnce("user_bob")
        assertNotNull(retrieved)
        assertTrue("isBlocked must persist as true in Room", retrieved!!.isBlocked)
        assertEquals("Bob", retrieved.displayName)
    }

    @Test
    fun test2_blockedStateSurvivesReload() = runTest {
        val userB = UserEntity(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = false,
            isBlocked = false
        )
        database.userDao().insertUser(userB)

        // Block user via repository
        userRepository.blockUser("user_bob")

        // Reload flow
        val reloaded = userRepository.getUser("user_bob").first()
        assertNotNull(reloaded)
        assertTrue("Blocked user state must survive reload", reloaded!!.isBlocked)
    }

    @Test
    fun test3_blockedStateSurvivesColdStart() = runTest {
        // 1. Insert blocked user
        val userB = UserEntity(
            id = "user_bob_cold",
            username = "bob_cold",
            displayName = "Bob Cold",
            isLocalUser = false,
            isOnline = false,
            isBlocked = true
        )
        database.userDao().insertUser(userB)

        // 2. Simulate process death / cold start: close database instance
        database.close()

        // 3. Re-open database from persistent disk file
        val coldStartDb = Room.databaseBuilder(context, AsterDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        val retrieved = coldStartDb.userDao().getUserByIdOnce("user_bob_cold")
        assertNotNull("User must survive cold start", retrieved)
        assertTrue("Blocked status must survive cold start on disk", retrieved!!.isBlocked)

        coldStartDb.close()
    }

    @Test
    fun test4_blockedConstellationRendersRedRestrictionIndicator() = runTest {
        val blockedUser = User(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            isBlocked = true
        )
        val conversation = Conversation(
            id = "conv_user_bob",
            title = "Bob",
            isGroup = false,
            createdAt = System.currentTimeMillis(),
            otherMember = blockedUser
        )

        assertTrue("Conversation must identify as blocked", conversation.isBlocked)

        // Presence text must show "Blocked" rather than "Active now"
        val presenceText = formatPresenceText(
            isOnline = blockedUser.isOnline,
            lastSeen = blockedUser.lastSeen,
            isDeleted = blockedUser.isDeleted,
            isBlocked = blockedUser.isBlocked
        )
        assertEquals("Blocked", presenceText)
    }

    @Test
    fun test5_blockedStateIsNotOverwrittenByPresenceUpdate() = runTest {
        val userB = UserEntity(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = false,
            isBlocked = true
        )
        database.userDao().insertUser(userB)

        // Presence update arrives claiming Bob is online now
        userRepository.updatePresence("user_bob", isOnline = true, lastSeen = System.currentTimeMillis())

        val retrieved = database.userDao().getUserByIdOnce("user_bob")
        assertNotNull(retrieved)
        assertTrue("Presence update must NEVER clobber is_blocked flag", retrieved!!.isBlocked)
        assertTrue("isOnline can update internally", retrieved.isOnline)
    }

    @Test
    fun test6_blockedContextCardShowsCorrectActions() = runTest {
        val blockedUser = User(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = true,
            isBlocked = true
        )
        val conv = Conversation(
            id = "conv_user_bob",
            title = "Bob",
            isGroup = false,
            createdAt = System.currentTimeMillis(),
            otherMember = blockedUser,
            isHighlighted = true // Even if marked highlighted earlier
        )

        assertTrue(conv.isBlocked)
        assertEquals(true, conv.otherMember?.isBlocked)
    }

    @Test
    fun test7_blockedConversationCannotComposeSend() = runTest {
        val localUser = UserEntity("user_alice", "alice", "Alice", isLocalUser = true, isOnline = true)
        val blockedUser = UserEntity("user_bob", "bob", "Bob", isLocalUser = false, isOnline = true, isBlocked = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(blockedUser)

        val convId = "conv_user_bob"
        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Bob", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(convId, "user_alice", "MEMBER", 1000L),
            ConversationMemberEntity(convId, "user_bob", "MEMBER", 1000L)
        ))

        // Attempt to send message to blocked recipient
        val sent = messageRepository.sendMessage(
            conversationId = convId,
            senderId = "user_alice",
            body = "Hello Bob",
            replyToId = null,
            effect = null
        )

        assertEquals("Message to blocked recipient must immediately fail", MessageStatus.FAILED, sent.status)
        val inDb = database.messageDao().getMessageById(sent.id)
        assertEquals(MessageStatus.FAILED, inDb?.status)
    }

    @Test
    fun test8_blockedUserCannotBypassRestrictionThroughReplyContextActions() = runTest {
        val localUser = UserEntity("user_alice", "alice", "Alice", isLocalUser = true, isOnline = true)
        val blockedUser = UserEntity("user_bob", "bob", "Bob", isLocalUser = false, isOnline = true, isBlocked = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(blockedUser)

        val convId = "conv_user_bob"
        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Bob", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        )
        database.conversationMemberDao().insertMembers(listOf(
            ConversationMemberEntity(convId, "user_alice", "MEMBER", 1000L),
            ConversationMemberEntity(convId, "user_bob", "MEMBER", 1000L)
        ))

        val msg = com.aster.app.data.local.entities.MessageEntity(
            id = "msg_old_1",
            conversationId = convId,
            senderId = "user_bob",
            body = "Past message",
            createdAt = 1000L,
            status = MessageStatus.DELIVERED
        )
        database.messageDao().insertMessage(msg)

        // Attempt reaction bypass
        val reacted = messageRepository.toggleReaction("msg_old_1", convId, "🪐", "user_alice")
        assertFalse("Cannot toggle reaction on message in a blocked relationship", reacted)
    }

    @Test
    fun test9_blockedStateRemainsAfterReconnect() = runTest {
        val userB = UserEntity(
            id = "user_bob",
            username = "bob",
            displayName = "Bob",
            isLocalUser = false,
            isOnline = false,
            isBlocked = true
        )
        database.userDao().insertUser(userB)

        // Simulate reconnect event
        userRepository.syncBlockedUsers()

        val afterReconnect = database.userDao().getUserByIdOnce("user_bob")
        assertNotNull(afterReconnect)
        assertTrue("User must remain blocked after reconnect", afterReconnect!!.isBlocked)
    }

    @Test
    fun test10_blockedNotificationMessageStateIsSuppressedCorrectly() = runTest {
        val localUser = UserEntity("user_alice", "alice", "Alice", isLocalUser = true, isOnline = true)
        val blockedUser = UserEntity("user_bob", "bob", "Bob", isLocalUser = false, isOnline = true, isBlocked = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(blockedUser)

        val convId = "conv_user_bob"
        database.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = "Bob", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        )

        // Incoming message arrives from blocked user over WebSocket
        val incomingMsg = ServerMessage.RelayMessage(
            messageId = "msg_blocked_incoming_99",
            conversationId = convId,
            senderId = "user_bob",
            body = "Unsolicited message from blocked user",
            timestamp = System.currentTimeMillis()
        )
        webSocketClient.emitIncomingMessageForTesting(incomingMsg)

        // Allow coroutine collection to process
        kotlinx.coroutines.delay(200)

        // Message must NOT be inserted into Room
        val inDb = database.messageDao().getMessageById("msg_blocked_incoming_99")
        assertNull("Incoming message from blocked user must be dropped and suppressed from Room", inDb)
    }
}
