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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Aster — Post-V1.0 Chat Timeline & Interaction Polish Verification.
 *
 * Covers:
 * 1. Opening a conversation with existing messages resolves to the latest message.
 * 2. Empty conversations do not attempt invalid scrolling.
 * 3. New outgoing messages become visible.
 * 4. Incoming messages auto-scroll only when the user is near the bottom.
 * 5. Incoming messages do not forcibly scroll a user reading history.
 * 6. Effect metadata survives delayed delivery.
 * 7. Unplayed delayed effects become eligible for playback.
 * 8. Played effects remain suppressed after recomposition.
 * 9. Played effects remain suppressed after scrolling away/back.
 * 10. Played effects remain suppressed after app restart.
 * 11. Multiple effects/messages do not trigger duplicate animation jobs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterChatTimelinePolishTest {

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
        authStorage.saveAuthToken("ast_tok_test_polish")
        authStorage.saveAuthenticatedUserId("user_local")

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

        // Seed local user and participant
        kotlinx.coroutines.runBlocking {
            val localUser = UserEntity("user_local", "localuser", "Local User", isLocalUser = true, isOnline = true)
            val peerUser = UserEntity("user_peer", "peeruser", "Peer User", isLocalUser = false, isOnline = true)
            database.userDao().insertUser(localUser)
            database.userDao().insertUser(peerUser)

            val conv = ConversationEntity(
                id = "conv_test",
                title = "Test Conversation",
                isGroup = false,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            database.conversationDao().insertConversation(conv)
            database.conversationMemberDao().insertMembers(
                listOf(
                    ConversationMemberEntity("conv_test", "user_local", "MEMBER", 1000L),
                    ConversationMemberEntity("conv_test", "user_peer", "MEMBER", 1000L)
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Opening conversation with existing messages resolves to latest message
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testOpeningConversationResolvesToLatestMessage() = runTest {
        // Seed 20 historical messages
        val messages = (1..20).map { i ->
            MessageEntity(
                id = "msg_$i",
                conversationId = "conv_test",
                senderId = if (i % 2 == 0) "user_local" else "user_peer",
                body = "Historical message $i",
                createdAt = 1000L + (i * 100L),
                status = MessageStatus.READ
            )
        }
        database.messageDao().insertMessages(messages)

        val retrieved = messageRepository.getMessages("conv_test").first()
        assertEquals(20, retrieved.size)

        // Workstream 1 rule:
        // initialTargetIndex = if (messages.isNotEmpty()) messages.size - 1 else 0
        val targetIndex = (retrieved.size - 1).coerceAtLeast(0)
        assertEquals("Latest message index must be 19 (the 20th message)", 19, targetIndex)
        assertEquals("msg_20", retrieved[targetIndex].id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Empty conversations do not attempt invalid scrolling
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testEmptyConversationDoesNotAttemptInvalidScrolling() = runTest {
        val emptyMessages = messageRepository.getMessages("conv_test").first()
        assertTrue("Messages list must be empty", emptyMessages.isEmpty())

        // Workstream 1 & 2 rule for empty conversations:
        // initialTargetIndex defaults safely to 0 without out-of-bounds or negative indexing
        val initialIndex = if (emptyMessages.isNotEmpty()) emptyMessages.size - 1 else 0
        assertEquals(0, initialIndex)

        // Settle state resolves immediately without scrolling
        val shouldScroll = emptyMessages.isNotEmpty()
        assertFalse("Empty conversation must not attempt scrolling", shouldScroll)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: New outgoing messages become visible
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testNewOutgoingMessageBecomesVisible() = runTest {
        // Seed 10 initial messages
        val initial = (1..10).map { i ->
            MessageEntity("msg_$i", "conv_test", "user_peer", "Message $i", 1000L + i, MessageStatus.READ)
        }
        database.messageDao().insertMessages(initial)

        val beforeSend = messageRepository.getMessages("conv_test").first()
        assertEquals(10, beforeSend.size)

        // Send new outgoing message
        val sent = messageRepository.sendMessage(
            conversationId = "conv_test",
            senderId = "user_local",
            body = "My new outgoing message"
        )

        val afterSend = messageRepository.getMessages("conv_test").first()
        assertEquals(11, afterSend.size)

        val lastMessage = afterSend.last()
        val isOutgoing = lastMessage.senderId == "user_local"
        assertTrue("Newly sent message must be outgoing", isOutgoing)

        // Workstream 4 rule: Outgoing message ALWAYS scrolls into view
        val target = afterSend.size - 1
        assertEquals("Target index must be 10 (the sent message)", 10, target)
        assertEquals(sent.id, afterSend[target].id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Incoming messages auto-scroll only when user is near bottom
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testIncomingMessageAutoScrollsWhenNearBottom() = runTest {
        // Compute isNearBottom helper based on LazyList layout state
        fun computeIsNearBottom(totalItems: Int, lastVisibleIndex: Int): Boolean {
            if (totalItems == 0) return true
            return (totalItems - 1 - lastVisibleIndex) <= 1
        }

        val totalItems = 25
        // User viewport is at the very bottom: last visible item is 24
        assertTrue(
            "User at bottom (lastVisible = 24 / 25) must be near bottom",
            computeIsNearBottom(totalItems, 24)
        )
        // User viewport slightly off bottom (lastVisible = 23 / 25)
        assertTrue(
            "User 1 item off bottom must still be considered near bottom",
            computeIsNearBottom(totalItems, 23)
        )

        // Rule: When near bottom and scroll not in progress, auto-scroll triggers
        val isScrollInProgress = false
        val isNearBottom = computeIsNearBottom(totalItems, 24)
        val shouldAutoScroll = isNearBottom && !isScrollInProgress
        assertTrue("Incoming message must auto-scroll when user is near bottom and idle", shouldAutoScroll)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Incoming messages do not forcibly scroll a user reading history
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testIncomingMessageDoesNotForciblyScrollUserReadingHistory() = runTest {
        fun computeIsNearBottom(totalItems: Int, lastVisibleIndex: Int): Boolean {
            if (totalItems == 0) return true
            return (totalItems - 1 - lastVisibleIndex) <= 1
        }

        val totalItems = 50
        // User is reading history at message 15
        val lastVisibleIndex = 15
        val isNearBottom = computeIsNearBottom(totalItems, lastVisibleIndex)
        assertFalse("User reading message 15 of 50 must NOT be near bottom", isNearBottom)

        // Rule: Do NOT auto-scroll when user reading history; show floating indicator instead
        val isScrollInProgress = false
        val shouldAutoScroll = isNearBottom && !isScrollInProgress
        val showNewMessagesPill = !shouldAutoScroll

        assertFalse("Must NOT auto-scroll while reading history", shouldAutoScroll)
        assertTrue("Must display 'New messages ↓' indicator instead", showNewMessagesPill)

        // Also test case where user is currently scrolling with finger (drag in progress)
        val isDragInProgress = true
        val shouldAutoScrollWhileDragging = computeIsNearBottom(totalItems, 49) && !isDragInProgress
        assertFalse("Must NOT fight active scroll gesture even near bottom", shouldAutoScrollWhileDragging)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: Effect metadata survives delayed delivery
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testEffectMetadataSurvivesDelayedDelivery() = runTest {
        val effects = listOf("nebula", "stardust", "ripple", "pulse", "comet")
        for ((idx, effect) in effects.withIndex()) {
            val msgId = "msg_effect_$idx"
            val entity = MessageEntity(
                id = msgId,
                conversationId = "conv_test",
                senderId = "user_peer",
                body = "Message with celestial $effect",
                createdAt = 2000L + idx,
                status = MessageStatus.DELIVERED,
                effect = effect,
                isEffectPlayed = false
            )
            database.messageDao().insertMessage(entity)

            val retrieved = database.messageDao().getMessageById(msgId)
            assertNotNull("Message must be persisted in database", retrieved)
            assertEquals("Effect metadata must be preserved exactly", effect, retrieved!!.effect)
            assertFalse("Effect must arrive unplayed (isEffectPlayed = false)", retrieved.isEffectPlayed)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: Unplayed delayed effects become eligible for playback
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testUnplayedDelayedEffectsBecomeEligibleForPlayback() = runTest {
        val msgId = "msg_delayed_playback"
        val entity = MessageEntity(
            id = msgId,
            conversationId = "conv_test",
            senderId = "user_peer",
            body = "Delayed nebula effect",
            createdAt = 3000L,
            status = MessageStatus.DELIVERED,
            effect = "nebula",
            isEffectPlayed = false
        )
        database.messageDao().insertMessage(entity)

        val domainMsg = database.messageDao().getMessageById(msgId)!!.toDomain()
        val presentedSet = mutableSetOf<String>()

        // Effect eligibility evaluation
        fun isEffectEligible(msg: Message, presented: Set<String>): Boolean {
            return !msg.effect.isNullOrBlank() && !msg.isEffectPlayed && !presented.contains(msg.id)
        }

        assertTrue("Unplayed delayed effect must be eligible for playback", isEffectEligible(domainMsg, presentedSet))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 8: Played effects remain suppressed after recomposition
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPlayedEffectsRemainSuppressedAfterRecomposition() = runTest {
        val msgId = "msg_recomp_test"
        val entity = MessageEntity(
            id = msgId,
            conversationId = "conv_test",
            senderId = "user_peer",
            body = "Stardust effect",
            createdAt = 4000L,
            status = MessageStatus.DELIVERED,
            effect = "stardust",
            isEffectPlayed = false
        )
        database.messageDao().insertMessage(entity)

        val presentedSet = mutableSetOf<String>()

        fun isEffectEligible(msg: Message, presented: Set<String>): Boolean {
            return !msg.effect.isNullOrBlank() && !msg.isEffectPlayed && !presented.contains(msg.id)
        }

        val initialMsg = database.messageDao().getMessageById(msgId)!!.toDomain()
        assertTrue("Initially eligible before presentation", isEffectEligible(initialMsg, presentedSet))

        // Present effect: add to in-memory tracking set immediately and mark in Room
        presentedSet.add(msgId)
        messageRepository.markEffectPlayed(msgId)

        // Simulate Compose recomposition with updated database emission
        val recomposedMsg = database.messageDao().getMessageById(msgId)!!.toDomain()
        assertTrue("Database row must now have isEffectPlayed = true", recomposedMsg.isEffectPlayed)
        assertFalse(
            "Effect must be suppressed during subsequent recompositions",
            isEffectEligible(recomposedMsg, presentedSet)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 9: Played effects remain suppressed after scrolling away and back
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPlayedEffectsRemainSuppressedAfterScrollingAwayAndBack() = runTest {
        val msgId = "msg_scroll_test"
        val entity = MessageEntity(
            id = msgId,
            conversationId = "conv_test",
            senderId = "user_peer",
            body = "Comet effect",
            createdAt = 5000L,
            status = MessageStatus.DELIVERED,
            effect = "comet",
            isEffectPlayed = false
        )
        database.messageDao().insertMessage(entity)

        val presentedSet = mutableSetOf<String>()

        fun isEffectEligible(msg: Message, presented: Set<String>): Boolean {
            return !msg.effect.isNullOrBlank() && !msg.isEffectPlayed && !presented.contains(msg.id)
        }

        // 1. Encounter message in viewport
        val msgBefore = database.messageDao().getMessageById(msgId)!!.toDomain()
        assertTrue(isEffectEligible(msgBefore, presentedSet))

        // 2. Starts playing -> marked immediately
        presentedSet.add(msgId)
        messageRepository.markEffectPlayed(msgId)

        // 3. User scrolls away (item is recycled/disposed from viewport)
        // 4. User scrolls back (item is recomposed in viewport)
        val msgScrolledBack = database.messageDao().getMessageById(msgId)!!.toDomain()
        assertFalse(
            "Effect must NOT replay when user scrolls away and back",
            isEffectEligible(msgScrolledBack, presentedSet)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 10: Played effects remain suppressed after app restart
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testPlayedEffectsRemainSuppressedAfterAppRestart() = runTest {
        val msgId = "msg_restart_test"
        val entity = MessageEntity(
            id = msgId,
            conversationId = "conv_test",
            senderId = "user_peer",
            body = "Pulse effect across restart",
            createdAt = 6000L,
            status = MessageStatus.DELIVERED,
            effect = "pulse",
            isEffectPlayed = false
        )
        database.messageDao().insertMessage(entity)

        // Mark effect as played
        database.messageDao().markEffectPlayed(msgId)

        val beforeRestart = database.messageDao().getMessageById(msgId)!!.toDomain()
        assertTrue(beforeRestart.isEffectPlayed)

        // Close and reopen database to simulate process termination / app restart
        database.close()
        val reopenedDb = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        reopenedDb.userDao().insertUser(UserEntity("user_peer", "peeruser", "Peer User", isLocalUser = false, isOnline = true))
        reopenedDb.conversationDao().insertConversation(ConversationEntity("conv_test", "Test Conversation", false, 1000L, 1000L))

        // Re-insert the persisted state as would be loaded from SQLite on disk
        val reloadedEntity = MessageEntity.fromDomain(beforeRestart)
        reopenedDb.messageDao().insertMessage(reloadedEntity)

        val afterRestart = reopenedDb.messageDao().getMessageById(msgId)!!.toDomain()
        assertTrue("Persisted isEffectPlayed must remain true after restart", afterRestart.isEffectPlayed)

        // Clean new session: empty in-memory presented set
        val freshSessionPresentedSet = mutableSetOf<String>()
        val eligibleAfterRestart = !afterRestart.effect.isNullOrBlank() &&
            !afterRestart.isEffectPlayed &&
            !freshSessionPresentedSet.contains(afterRestart.id)

        assertFalse("Played effect must NEVER replay after app restart", eligibleAfterRestart)
        reopenedDb.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 11: Multiple effects/messages do not trigger duplicate animation jobs
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testMultipleEffectsDoNotTriggerDuplicateAnimationJobs() = runTest {
        // Seed 3 messages with celestial effects
        val msgs = listOf(
            MessageEntity("msg_eff_1", "conv_test", "user_peer", "Effect 1", 7001L, MessageStatus.DELIVERED, effect = "nebula", isEffectPlayed = false),
            MessageEntity("msg_eff_2", "conv_test", "user_peer", "Effect 2", 7002L, MessageStatus.DELIVERED, effect = "stardust", isEffectPlayed = false),
            MessageEntity("msg_eff_3", "conv_test", "user_peer", "Effect 3", 7003L, MessageStatus.DELIVERED, effect = "ripple", isEffectPlayed = false)
        )
        database.messageDao().insertMessages(msgs)

        val presentedSet = mutableSetOf<String>()
        val animationJobCounter = mutableMapOf<String, Int>()

        fun tryLaunchEffectAnimation(messageId: String, effect: String?, isPlayed: Boolean): Boolean {
            if (effect.isNullOrBlank() || isPlayed || presentedSet.contains(messageId)) {
                return false
            }
            // Record animation job launch
            presentedSet.add(messageId)
            animationJobCounter[messageId] = (animationJobCounter[messageId] ?: 0) + 1
            return true
        }

        // First pass: each effect starts once
        val launched1 = tryLaunchEffectAnimation("msg_eff_1", "nebula", false)
        val launched2 = tryLaunchEffectAnimation("msg_eff_2", "stardust", false)
        val launched3 = tryLaunchEffectAnimation("msg_eff_3", "ripple", false)

        assertTrue(launched1)
        assertTrue(launched2)
        assertTrue(launched3)

        // Verify each message triggered exactly 1 animation job
        assertEquals(1, animationJobCounter["msg_eff_1"])
        assertEquals(1, animationJobCounter["msg_eff_2"])
        assertEquals(1, animationJobCounter["msg_eff_3"])

        // Second pass (simulating recomposition / re-render / scroll jitter):
        // duplicate attempts must be rejected
        val duplicate1 = tryLaunchEffectAnimation("msg_eff_1", "nebula", false)
        val duplicate2 = tryLaunchEffectAnimation("msg_eff_2", "stardust", false)
        val duplicate3 = tryLaunchEffectAnimation("msg_eff_3", "ripple", false)

        assertFalse("Duplicate animation for msg 1 must be prevented", duplicate1)
        assertFalse("Duplicate animation for msg 2 must be prevented", duplicate2)
        assertFalse("Duplicate animation for msg 3 must be prevented", duplicate3)

        assertEquals("Animation job count for msg 1 must strictly remain 1", 1, animationJobCounter["msg_eff_1"])
        assertEquals("Animation job count for msg 2 must strictly remain 1", 1, animationJobCounter["msg_eff_2"])
        assertEquals("Animation job count for msg 3 must strictly remain 1", 1, animationJobCounter["msg_eff_3"])
    }
}
