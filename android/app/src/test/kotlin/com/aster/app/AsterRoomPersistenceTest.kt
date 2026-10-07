package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterRoomPersistenceTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun userPersistence_insertAndQuery() = runTest {
        val user = UserEntity(
            id = "user_saad",
            username = "saad",
            displayName = "Saad",
            isLocalUser = true,
            isOnline = true
        )
        database.userDao().insertUser(user)

        val retrieved = database.userDao().getUserById("user_saad").first()
        assertNotNull(retrieved)
        assertEquals("Saad", retrieved?.displayName)
        assertEquals(true, retrieved?.isLocalUser)

        val localUser = database.userDao().getLocalUser().first()
        assertNotNull(localUser)
        assertEquals("user_saad", localUser?.id)
    }

    @Test
    fun conversationAndMemberPersistence() = runTest {
        val user1 = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        val user2 = UserEntity("u2", "alex", "Alex", isLocalUser = false, isOnline = true)
        database.userDao().insertUsers(listOf(user1, user2))

        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "c1",
            title = "Alex",
            isGroup = false,
            createdAt = now,
            updatedAt = now
        )
        database.conversationDao().insertConversation(conversation)

        val members = listOf(
            ConversationMemberEntity("c1", "u1", "MEMBER", now),
            ConversationMemberEntity("c1", "u2", "MEMBER", now)
        )
        database.conversationMemberDao().insertMembers(members)

        val convList = database.conversationDao().getAllConversations().first()
        assertEquals(1, convList.size)
        assertEquals("c1", convList[0].id)

        val memberUsers = database.conversationMemberDao().getMembersForConversation("c1").first()
        assertEquals(2, memberUsers.size)

        val otherMember = database.conversationMemberDao().getOtherMember("c1").first()
        assertNotNull(otherMember)
        assertEquals("u2", otherMember?.id)
        assertEquals("Alex", otherMember?.displayName)
    }

    @Test
    fun messagePersistence_insertOrderAndRetrieve() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val now = System.currentTimeMillis()
        val conv = ConversationEntity("c1", "Notes", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        val msg1 = MessageEntity(
            id = "m1",
            conversationId = "c1",
            senderId = "u1",
            body = "First message",
            createdAt = now,
            status = MessageStatus.SENT
        )
        val msg2 = MessageEntity(
            id = "m2",
            conversationId = "c1",
            senderId = "u1",
            body = "Second message",
            createdAt = now + 1000,
            status = MessageStatus.SENT
        )
        database.messageDao().insertMessages(listOf(msg1, msg2))

        val messages = database.messageDao().getMessagesForConversation("c1").first()
        assertEquals(2, messages.size)
        assertEquals("First message", messages[0].body)
        assertEquals("Second message", messages[1].body)

        val latest = database.messageDao().getLatestMessage("c1").first()
        assertNotNull(latest)
        assertEquals("m2", latest?.id)
        assertEquals("Second message", latest?.body)
    }

    @Test
    fun messageStatusTransition_fromSendingToSentToRead() = runTest {
        val user = UserEntity("u1", "saad", "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(user)

        val now = System.currentTimeMillis()
        val conv = ConversationEntity("c1", "Alex", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        val msg = MessageEntity(
            id = "m1",
            conversationId = "c1",
            senderId = "u1",
            body = "Sending test",
            createdAt = now,
            status = MessageStatus.SENDING
        )
        database.messageDao().insertMessage(msg)

        var retrieved = database.messageDao().getMessageById("m1")
        assertEquals(MessageStatus.SENDING, retrieved?.status)

        // Local send acceptance transition
        database.messageDao().updateMessageStatus("m1", MessageStatus.SENT)
        retrieved = database.messageDao().getMessageById("m1")
        assertEquals(MessageStatus.SENT, retrieved?.status)

        // Mark as read transition
        database.messageDao().updateMessageStatus("m1", MessageStatus.READ)
        retrieved = database.messageDao().getMessageById("m1")
        assertEquals(MessageStatus.READ, retrieved?.status)
    }

    @Test
    fun databaseReload_messagesAndConversationsSurviveRestart() = runTest {
        val dbFile = File(context.cacheDir, "test_persistence.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. First database session: create data and close
        val firstDb = Room.databaseBuilder(context, AsterDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        val user = UserEntity("u_perm", "saad", "Saad", isLocalUser = true, isOnline = true)
        firstDb.userDao().insertUser(user)

        val now = System.currentTimeMillis()
        val conv = ConversationEntity("c_perm", "Saved Chat", isGroup = false, createdAt = now, updatedAt = now)
        firstDb.conversationDao().insertConversation(conv)

        val msg = MessageEntity(
            id = "m_perm",
            conversationId = "c_perm",
            senderId = "u_perm",
            body = "This message survives app restart",
            createdAt = now,
            status = MessageStatus.SENT
        )
        firstDb.messageDao().insertMessage(msg)

        // Close the database to simulate app termination
        firstDb.close()

        // 2. Second database session: reopen the exact same database file
        val reopenedDb = Room.databaseBuilder(context, AsterDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        try {
            val retrievedConv = reopenedDb.conversationDao().getConversationByIdOnce("c_perm")
            assertNotNull("Conversation must survive reload", retrievedConv)
            assertEquals("Saved Chat", retrievedConv?.title)

            val retrievedMsg = reopenedDb.messageDao().getMessageById("m_perm")
            assertNotNull("Message must survive reload", retrievedMsg)
            assertEquals("This message survives app restart", retrievedMsg?.body)
            assertEquals(MessageStatus.SENT, retrievedMsg?.status)

            val retrievedUser = reopenedDb.userDao().getUserByIdOnce("u_perm")
            assertNotNull("User must survive reload", retrievedUser)
            assertEquals("Saad", retrievedUser?.displayName)
        } finally {
            reopenedDb.close()
            dbFile.delete()
        }
    }
}
