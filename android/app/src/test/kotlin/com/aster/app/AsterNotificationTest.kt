package com.aster.app

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.domain.model.MessageStatus
import com.aster.app.ui.notification.AsterNotificationManager
import com.aster.app.ui.notification.NotificationPreviewPolicy
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterNotificationTest {

    private lateinit var context: Context
    private lateinit var notificationManager: AsterNotificationManager
    private lateinit var sysNotificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager
    private lateinit var database: AsterDatabase
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var webSocketClient: AsterWebSocketClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sysNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(sysNotificationManager)

        notificationManager = AsterNotificationManager(context)
        notificationManager.initChannels()

        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val authStorage = com.aster.app.data.auth.AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_saad")
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
            notificationManager = notificationManager
        )
    }

    @After
    fun tearDown() {
        messageRepository.cancelScope()
        database.close()
        notificationManager.dismissAllNotifications()
    }

    @Test
    fun initChannels_createsDedicatedNotificationChannelWithCorrectSettings() {
        val channel = sysNotificationManager.getNotificationChannel(AsterNotificationManager.CHANNEL_ID_MESSAGES)
        assertNotNull("Message notification channel should be created", channel)
        assertEquals("Aster Messages", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue("Vibration should be enabled", channel.shouldVibrate())
        assertTrue("Badges should be enabled", channel.canShowBadge())
    }

    @Test
    fun showMessageNotification_directMessage_postsNotificationWithSenderTitle() {
        val now = System.currentTimeMillis()
        notificationManager.showMessageNotification(
            conversationId = "conv_direct_1",
            conversationTitle = "Alex",
            isGroup = false,
            senderDisplayName = "Alex Mercer",
            messageText = "Meet me at the rendezvous point",
            messageTimestamp = now
        )

        val notifications = shadowNotificationManager.allNotifications
        assertTrue("At least one notification should be posted", notifications.isNotEmpty())

        val notif = notifications.firstOrNull { it.extras.getCharSequence("android.title")?.toString() == "Alex Mercer" }
        assertNotNull("Notification for conversation should exist", notif)
        assertEquals("Alex Mercer", notif?.extras?.getCharSequence("android.title")?.toString())
        assertEquals("Meet me at the rendezvous point", notif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun showMessageNotification_groupMessage_postsNotificationWithGroupTitleAndSenderPrefix() {
        val now = System.currentTimeMillis()
        notificationManager.showMessageNotification(
            conversationId = "conv_group_1",
            conversationTitle = "Aster Core Devs",
            isGroup = true,
            senderDisplayName = "Elena",
            messageText = "Release v1.0 is tagged",
            messageTimestamp = now
        )

        val notifications = shadowNotificationManager.allNotifications
        val notif = notifications.firstOrNull { it.extras.getCharSequence("android.title")?.toString() == "Aster Core Devs" }
        assertNotNull("Group notification should have group title", notif)
        assertEquals("Elena: Release v1.0 is tagged", notif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun showMessageNotification_privacyPolicyMasking_hidesMessagePreview() {
        notificationManager.previewPolicy = NotificationPreviewPolicy.HIDE_PREVIEW_NAME_ONLY
        val now = System.currentTimeMillis()

        // Direct message masked
        notificationManager.showMessageNotification(
            conversationId = "conv_direct_priv",
            conversationTitle = "Secret Contact",
            isGroup = false,
            senderDisplayName = "Secret Contact",
            messageText = "Highly confidential data",
            messageTimestamp = now
        )

        var notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Secret Contact"
        }
        assertNotNull(notif)
        assertEquals("New message", notif?.extras?.getCharSequence("android.text")?.toString())

        // Group message masked
        notificationManager.showMessageNotification(
            conversationId = "conv_group_priv",
            conversationTitle = "Covert Group",
            isGroup = true,
            senderDisplayName = "Agent 007",
            messageText = "Operation started",
            messageTimestamp = now
        )

        notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Covert Group"
        }
        assertNotNull(notif)
        assertEquals("Agent 007 sent a message", notif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun showMessageNotification_suppressedWhenConversationIsActivelyViewed() {
        notificationManager.isAppForeground = true
        notificationManager.setActiveConversation("conv_open_chat")
        assertEquals("conv_open_chat", notificationManager.getActiveConversation())

        notificationManager.showMessageNotification(
            conversationId = "conv_open_chat",
            conversationTitle = "Open Chat",
            isGroup = false,
            senderDisplayName = "Partner",
            messageText = "Hello are you reading this?",
            messageTimestamp = System.currentTimeMillis()
        )

        // No notification should be posted
        val convNotif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Partner"
        }
        assertNull("Notification must be suppressed when conversation is active", convNotif)
    }

    @Test
    fun showMessageNotification_whenAppBackgrounded_postsNotificationEvenIfConversationIsActive() {
        notificationManager.setActiveConversation("conv_open_chat")
        assertEquals("conv_open_chat", notificationManager.getActiveConversation())

        // App backgrounded (e.g. user pressed HOME)
        notificationManager.isAppForeground = false

        notificationManager.showMessageNotification(
            conversationId = "conv_open_chat",
            conversationTitle = "Open Chat",
            isGroup = false,
            senderDisplayName = "Partner",
            messageText = "Backgrounded alert check",
            messageTimestamp = System.currentTimeMillis()
        )

        // Notification MUST be posted because app is backgrounded
        val convNotif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Partner"
        }
        assertNotNull("Notification must NOT be suppressed when app is backgrounded", convNotif)
        assertEquals("Backgrounded alert check", convNotif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun setActiveConversation_dismissesExistingNotification() {
        val convId = "conv_to_open"
        notificationManager.showMessageNotification(
            conversationId = convId,
            conversationTitle = "Contact",
            isGroup = false,
            senderDisplayName = "Contact",
            messageText = "Message before opening",
            messageTimestamp = System.currentTimeMillis()
        )

        // Verify notification is posted
        val notifId = convId.hashCode()
        assertNotNull(shadowNotificationManager.allNotifications.firstOrNull { it.extras.getCharSequence("android.title")?.toString() == "Contact" })

        // Open the conversation
        notificationManager.setActiveConversation(convId)

        // Verify notification cancelled
        val remaining = shadowNotificationManager.allNotifications.filter { it.extras.getCharSequence("android.title")?.toString() == "Contact" }
        assertTrue("Notification should be dismissed upon opening conversation", remaining.isEmpty())
    }

    @Test
    fun showMessageNotification_notificationsDisabled_doesNotPost() {
        shadowNotificationManager.setNotificationsEnabled(false)

        notificationManager.showMessageNotification(
            conversationId = "conv_quiet",
            conversationTitle = "Quiet",
            isGroup = false,
            senderDisplayName = "Quiet User",
            messageText = "Silent message",
            messageTimestamp = System.currentTimeMillis()
        )

        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Quiet User"
        }
        assertNull("Should not post notification if notifications are disabled", notif)
    }

    @Test
    fun repository_incomingMessage_triggersNotificationAndDeliveredStatusWhenInactive() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local", "local_user", "Local User", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender", "sender_user", "Sender User", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_test", "Test Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)
        database.conversationMemberDao().insertMember(ConversationMemberEntity("c_test", "u_local", "MEMBER", now))
        database.conversationMemberDao().insertMember(ConversationMemberEntity("c_test", "u_sender", "MEMBER", now))

        // Ensure active conversation is null
        notificationManager.setActiveConversation(null)

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_recv_101",
            conversationId = "c_test",
            senderId = "u_sender",
            body = "Hello offline world",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)

        var storedMsg: MessageEntity? = null
        for (i in 0 until 50) {
            storedMsg = database.messageDao().getMessageById("msg_recv_101")
            if (storedMsg != null) break
            Thread.sleep(20)
        }

        // Assert message stored with DELIVERED status
        assertNotNull("Message should be stored", storedMsg)
        assertEquals(MessageStatus.DELIVERED, storedMsg?.status)

        // Assert unread count incremented
        val unreadCount = database.messageDao().getUnreadCount("c_test", "u_local").first()
        assertEquals(1, unreadCount)

        // Assert notification was posted
        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Sender User"
        }
        assertNotNull("Notification should be posted for inactive chat", notif)
        assertEquals("Hello offline world", notif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun repository_incomingMessage_marksReadAndSuppressesNotificationWhenActive() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local2", "local2", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender2", "sender2", "Sender", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_active", "Active Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        // Set conversation as actively viewed in foreground
        notificationManager.isAppForeground = true
        notificationManager.setActiveConversation("c_active")

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_recv_active_1",
            conversationId = "c_active",
            senderId = "u_sender2",
            body = "Are you watching?",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)

        var storedMsg: MessageEntity? = null
        for (i in 0 until 50) {
            storedMsg = database.messageDao().getMessageById("msg_recv_active_1")
            if (storedMsg != null) break
            Thread.sleep(20)
        }

        // Assert stored directly as READ
        assertNotNull("Message should be stored", storedMsg)
        assertEquals(MessageStatus.READ, storedMsg?.status)

        // Assert unread count remains 0
        val unreadCount = database.messageDao().getUnreadCount("c_active", "u_local2").first()
        assertEquals(0, unreadCount)

        // Assert NO notification posted
        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Sender"
        }
        assertNull("Notification must be suppressed for active chat", notif)
    }

    @Test
    fun repository_duplicateIncomingMessage_doesNotDuplicateNotificationOrUnreadCount() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local3", "local3", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender3", "sender3", "Sender", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_dup", "Dup Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)
        notificationManager.setActiveConversation(null)

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_dup_1",
            conversationId = "c_dup",
            senderId = "u_sender3",
            body = "Duplicate test message",
            timestamp = now
        )

        // First delivery
        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        for (i in 0 until 50) {
            if (database.messageDao().getMessageById("msg_dup_1") != null &&
                shadowNotificationManager.allNotifications.isNotEmpty()) break
            Thread.sleep(20)
        }
        assertEquals(1, database.messageDao().getUnreadCount("c_dup", "u_local3").first())
        val initialCount = shadowNotificationManager.allNotifications.size

        // Duplicate delivery with same message ID
        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        Thread.sleep(100)

        // Unread count must NOT be incremented again
        assertEquals(1, database.messageDao().getUnreadCount("c_dup", "u_local3").first())
        // Notification count should not grow
        assertEquals(initialCount, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun incomingMessage_whenAppBackgrounded_withOpenConversation_postsNotificationAndSetsDelivered() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local_bg", "local_bg", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender_bg", "sender_bg", "Sender", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_bg", "Background Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        // User opened chat c_bg, but then backgrounded the app (e.g. pressed Home)
        notificationManager.setActiveConversation("c_bg")
        notificationManager.isAppForeground = false

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_bg_1",
            conversationId = "c_bg",
            senderId = "u_sender_bg",
            body = "Should alert in background even if chat was open",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        for (i in 0 until 50) {
            if (database.messageDao().getMessageById("msg_bg_1") != null &&
                shadowNotificationManager.allNotifications.isNotEmpty()) break
            Thread.sleep(20)
        }

        val msg = database.messageDao().getMessageById("msg_bg_1")
        assertNotNull("Message must be inserted into Room", msg)
        assertEquals("Incoming message in background must have DELIVERED status", MessageStatus.DELIVERED, msg?.status)

        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Sender"
        }
        assertNotNull("Notification MUST be posted when app is in background even if conversation is set as active", notif)
        assertEquals("Should alert in background even if chat was open", notif?.extras?.getCharSequence("android.text")?.toString())
    }

    @Test
    fun incomingMessage_whenAppInRecents_postsNotification() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local_rec", "local_rec", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender_rec", "sender_rec", "Sender", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_rec", "Recents Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        // Activity is in Recents overview (onPause called -> isAppForeground = false)
        notificationManager.setActiveConversation("c_rec")
        notificationManager.isAppForeground = false

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_rec_1",
            conversationId = "c_rec",
            senderId = "u_sender_rec",
            body = "Recents delivery check",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        for (i in 0 until 50) {
            if (database.messageDao().getMessageById("msg_rec_1") != null &&
                shadowNotificationManager.allNotifications.isNotEmpty()) break
            Thread.sleep(20)
        }

        val msg = database.messageDao().getMessageById("msg_rec_1")
        assertNotNull("Message must be persisted", msg)
        assertEquals("Incoming message in Recents must be DELIVERED", MessageStatus.DELIVERED, msg?.status)

        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Sender"
        }
        assertNotNull("Notification MUST be posted when Aster is visible in Recents", notif)
    }

    @Test
    fun incomingMessage_whenForegroundAndActivelyViewed_suppressesNotificationAndSetsRead() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local_fg", "local_fg", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender_fg", "sender_fg", "Sender", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_fg", "Foreground Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        // Active chat and user is looking at it in the foreground
        notificationManager.isAppForeground = true
        notificationManager.setActiveConversation("c_fg")

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_fg_1",
            conversationId = "c_fg",
            senderId = "u_sender_fg",
            body = "Actively viewing message",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        for (i in 0 until 50) {
            if (database.messageDao().getMessageById("msg_fg_1") != null) break
            Thread.sleep(20)
        }

        val msg = database.messageDao().getMessageById("msg_fg_1")
        assertNotNull("Message must be inserted into Room", msg)
        assertEquals("Actively viewed message must be initialized as READ", MessageStatus.READ, msg?.status)

        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Sender"
        }
        assertNull("Notification must be suppressed when conversation is actively viewed in foreground", notif)
    }

    @Test
    fun incomingMessage_whenForegroundAndDifferentConversation_postsNotification() = runTest {
        val now = System.currentTimeMillis()
        val localUser = UserEntity("u_local_diff", "local_diff", "Local", isLocalUser = true, isOnline = true)
        val sender = UserEntity("u_sender_diff", "sender_diff", "Alice", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(localUser)
        database.userDao().insertUser(sender)

        val conv = ConversationEntity("c_diff", "Alice Chat", isGroup = false, createdAt = now, updatedAt = now)
        database.conversationDao().insertConversation(conv)

        // User is in foreground, but in a DIFFERENT conversation (e.g. Bob's chat)
        notificationManager.isAppForeground = true
        notificationManager.setActiveConversation("c_bob")

        val serverMsg = ServerMessage.RelayMessage(
            messageId = "msg_diff_1",
            conversationId = "c_diff",
            senderId = "u_sender_diff",
            body = "Hey from Alice while you are with Bob",
            timestamp = now
        )

        webSocketClient.emitIncomingMessageForTesting(serverMsg)
        for (i in 0 until 50) {
            if (database.messageDao().getMessageById("msg_diff_1") != null &&
                shadowNotificationManager.allNotifications.isNotEmpty()) break
            Thread.sleep(20)
        }

        val msg = database.messageDao().getMessageById("msg_diff_1")
        assertNotNull("Message must be persisted", msg)
        assertEquals("Message from different conversation must be DELIVERED", MessageStatus.DELIVERED, msg?.status)

        val notif = shadowNotificationManager.allNotifications.firstOrNull {
            it.extras.getCharSequence("android.title")?.toString() == "Alice"
        }
        assertNotNull("Notification MUST be posted for a different conversation while app is in foreground", notif)
    }
}
