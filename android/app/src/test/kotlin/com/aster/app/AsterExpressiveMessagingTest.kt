package com.aster.app

import androidx.lifecycle.SavedStateHandle
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.network.ClientMessage
import com.aster.app.data.network.ServerMessage
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.domain.repository.UserRepository
import com.aster.app.ui.ChatViewModel
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import com.aster.app.ui.motion.AsterReactionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterExpressiveMessagingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Curated Reaction Set & Tokens (Phase 8.4.1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testCuratedReactionsAndEffectsTokens() {
        val emojis = AsterReactionTokens.CuratedEmojis
        assertEquals("Initial curated reaction count must be 6", 6, emojis.size)
        assertTrue(emojis.contains("❤️"))
        assertTrue(emojis.contains("😂"))
        assertTrue(emojis.contains("😮"))
        assertTrue(emojis.contains("😢"))
        assertTrue(emojis.contains("👍"))
        assertTrue(emojis.contains("🔥"))

        val effects = AsterReactionTokens.CelestialEffects
        assertEquals("Must support 5 celestial effects", 5, effects.size)
        assertTrue(effects.contains("nebula"))
        assertTrue(effects.contains("stardust"))
        assertTrue(effects.contains("ripple"))
        assertTrue(effects.contains("pulse"))
        assertTrue(effects.contains("comet"))

        assertEquals("Nebula", AsterReactionTokens.effectDisplayName("nebula"))
        assertEquals("Stardust", AsterReactionTokens.effectDisplayName("stardust"))
        assertEquals("Ripple", AsterReactionTokens.effectDisplayName("ripple"))
        assertEquals("Pulse", AsterReactionTokens.effectDisplayName("pulse"))
        assertEquals("Comet", AsterReactionTokens.effectDisplayName("comet"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Reaction Serialization & Deserialization (Phase 8.4.1 & Persistence)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testReactionSerializationAndDeserialization() {
        val emptyJson = MessageEntity.serializeReactions(emptyMap())
        assertEquals("{}", emptyJson)

        val parsedEmpty = MessageEntity.parseReactions(emptyJson)
        assertTrue("Parsed empty reactions map must be empty", parsedEmpty.isEmpty())

        val reactions = mapOf(
            "❤️" to listOf("user_1", "user_2"),
            "🔥" to listOf("user_1")
        )
        val serialized = MessageEntity.serializeReactions(reactions)
        val deserialized = MessageEntity.parseReactions(serialized)

        assertEquals("Should preserve 2 emoji entries", 2, deserialized.size)
        assertEquals(listOf("user_1", "user_2"), deserialized["❤️"])
        assertEquals(listOf("user_1"), deserialized["🔥"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Reaction Toggle Logic & State Machine
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testReactionToggleStateMachine() {
        var currentReactions = mutableMapOf<String, MutableList<String>>()

        // Step 1: User 1 reacts with ❤️
        val emoji = "❤️"
        val user1 = "user_1"
        val list = currentReactions.getOrPut(emoji) { mutableListOf() }
        list.add(user1)
        assertEquals(1, currentReactions[emoji]?.size)
        assertTrue(currentReactions[emoji]?.contains(user1) == true)

        // Step 2: User 2 reacts with ❤️
        val user2 = "user_2"
        currentReactions.getOrPut(emoji) { mutableListOf() }.add(user2)
        assertEquals(2, currentReactions[emoji]?.size)

        // Step 3: User 1 toggles off ❤️
        currentReactions[emoji]?.remove(user1)
        assertEquals(1, currentReactions[emoji]?.size)
        assertFalse(currentReactions[emoji]?.contains(user1) == true)
        assertTrue(currentReactions[emoji]?.contains(user2) == true)

        // Step 4: User 2 toggles off ❤️ -> Key cleaned up completely
        currentReactions[emoji]?.remove(user2)
        if (currentReactions[emoji]?.isEmpty() == true) {
            currentReactions.remove(emoji)
        }
        assertFalse("Empty reaction list should be pruned from map", currentReactions.containsKey(emoji))
        assertTrue(currentReactions.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Domain Model Reactions & Effect Fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testMessageDomainReactionsAndEffectFields() {
        val msg = Message(
            id = "msg_test_1",
            conversationId = "conv_1",
            senderId = "user_sender",
            body = "Celestial message",
            createdAt = 1000L,
            status = MessageStatus.SENT,
            reactions = mapOf("✨" to listOf("user_sender")),
            effect = "nebula"
        )

        assertEquals(1, msg.reactions.size)
        assertEquals("nebula", msg.effect)

        val entity = MessageEntity.fromDomain(msg)
        assertEquals("nebula", entity.effect)
        assertTrue(entity.reactions.contains("user_sender"))

        val domainRestored = entity.toDomain()
        assertEquals(msg.id, domainRestored.id)
        assertEquals(msg.effect, domainRestored.effect)
        assertEquals(msg.reactions, domainRestored.reactions)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. ChatViewModel Integration for Reactions and Celestial Effects
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testChatViewModelEffectSelectionAndSending() = runTest(testDispatcher) {
        val fakeMessageRepo = FakeExpressiveMessageRepo()
        val fakeConvRepo = FakeExpressiveConvRepo()
        val fakeUserRepo = FakeExpressiveUserRepo()

        val savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv_expressive"))
        val viewModel = ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = fakeMessageRepo,
            conversationRepository = fakeConvRepo,
            userRepository = fakeUserRepo,
            notificationManager = null,
            webSocketClient = null
        )

        val job = backgroundScope.launch(testDispatcher) {
            viewModel.uiState.collect {}
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // 1. Select effect
        assertNull(viewModel.uiState.value.selectedEffect)
        viewModel.selectEffect("stardust")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("stardust", viewModel.uiState.value.selectedEffect)

        // 2. Type message and send
        viewModel.onInputTextChanged("Look at the stars!")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        // 3. Verify sent message carries effect and composer resets effect to null
        assertEquals("stardust", fakeMessageRepo.lastSentEffect)
        assertEquals("Look at the stars!", fakeMessageRepo.lastSentBody)
        assertNull("Selected effect must reset after sending", viewModel.uiState.value.selectedEffect)

        // 4. Toggle reaction via ViewModel
        viewModel.toggleReaction("msg_target", "🔥")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("msg_target", fakeMessageRepo.lastToggledMessageId)
        assertEquals("🔥", fakeMessageRepo.lastToggledEmoji)
        assertEquals("user_local", fakeMessageRepo.lastToggledUserId)

        // 5. Apply effect to existing message
        viewModel.applyEffectToMessage("msg_target", "ripple")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("msg_target", fakeMessageRepo.lastEffectMessageId)
        assertEquals("ripple", fakeMessageRepo.lastAppliedEffect)

        job.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Reduced Motion Compliance for Expressive Features
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testReducedMotionCollapsesCelestialDurationsAndSprings() {
        val normalTween = AsterMotion.tween<Float>(
            durationMillis = AsterMotionTokens.Duration.Standard,
            reducedMotion = false
        )
        assertEquals(AsterMotionTokens.Duration.Standard, normalTween.durationMillis)

        val reducedTween = AsterMotion.tween<Float>(
            durationMillis = AsterMotionTokens.Duration.Standard,
            reducedMotion = true
        )
        assertEquals(0, reducedTween.durationMillis)

        val reducedSpring = AsterMotion.spring<Float>(
            spec = AsterMotionTokens.SpringPhysics.Press,
            reducedMotion = true
        )
        assertEquals(AsterMotionTokens.SpringPhysics.ReducedMotion.stiffness, reducedSpring.stiffness, 0.01f)
        assertEquals(AsterMotionTokens.SpringPhysics.ReducedMotion.dampingRatio, reducedSpring.dampingRatio, 0.01f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Phase 8.4 Network Protocol Serialization & Multi-Client Sync Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testSendMessageWireSerializationWithReplyAndEffect() {
        // Outgoing message with replyToId and celestial effect
        val msgWithBoth = ClientMessage.SendMessage(
            messageId = "msg_out_1",
            conversationId = "conv_1",
            recipientId = "user_rec",
            body = "Stardust quote",
            timestamp = 1793000000000L,
            replyToId = "parent_msg_99",
            effect = "stardust"
        )
        val json = msgWithBoth.toJson()
        assertTrue(json.contains("\"type\":\"SEND_MESSAGE\""))
        assertTrue(json.contains("\"replyToId\":\"parent_msg_99\""))
        assertTrue(json.contains("\"effect\":\"stardust\""))

        // Outgoing message without optional fields
        val msgBasic = ClientMessage.SendMessage(
            messageId = "msg_out_2",
            conversationId = "conv_1",
            recipientId = "user_rec",
            body = "Plain text",
            timestamp = 1793000000000L
        )
        val jsonBasic = msgBasic.toJson()
        assertFalse(jsonBasic.contains("\"replyToId\""))
        assertFalse(jsonBasic.contains("\"effect\""))
    }

    @Test
    fun testToggleReactionWireSerialization() {
        val toggle = ClientMessage.ToggleReaction(
            messageId = "msg_target_55",
            conversationId = "conv_1",
            emoji = "❤️"
        )
        val json = toggle.toJson()
        assertTrue(json.contains("\"type\":\"TOGGLE_REACTION\""))
        assertTrue(json.contains("\"messageId\":\"msg_target_55\""))
        assertTrue(json.contains("\"conversationId\":\"conv_1\""))
        assertTrue(json.contains("\"emoji\":\"❤️\""))
    }

    @Test
    fun testServerRelayMessageWireDeserializationWithReplyAndEffectAndReactions() {
        val rawJson = """
            {
                "type": "MESSAGE",
                "messageId": "msg_in_10",
                "conversationId": "conv_2",
                "senderId": "user_sender_remote",
                "body": "Incoming quote with comet!",
                "timestamp": 1793000005000,
                "replyToId": "parent_msg_50",
                "effect": "comet",
                "reactions": {
                    "❤️": ["user_a", "user_b"],
                    "🔥": ["user_c"]
                }
            }
        """.trimIndent()

        val parsed = ServerMessage.fromJson(rawJson)
        assertTrue(parsed is ServerMessage.RelayMessage)
        val relay = parsed as ServerMessage.RelayMessage
        assertEquals("msg_in_10", relay.messageId)
        assertEquals("conv_2", relay.conversationId)
        assertEquals("user_sender_remote", relay.senderId)
        assertEquals("Incoming quote with comet!", relay.body)
        assertEquals(1793000005000L, relay.timestamp)
        assertEquals("parent_msg_50", relay.replyToId)
        assertEquals("comet", relay.effect)
        assertNotNull(relay.reactions)
        assertEquals(2, relay.reactions?.size)
        assertEquals(listOf("user_a", "user_b"), relay.reactions?.get("❤️"))
        assertEquals(listOf("user_c"), relay.reactions?.get("🔥"))
    }

    @Test
    fun testReactionUpdateWireDeserialization() {
        val rawJson = """
            {
                "type": "REACTION_UPDATE",
                "messageId": "msg_react_99",
                "conversationId": "conv_99",
                "userId": "user_reactor",
                "emoji": "👍",
                "action": "ADDED",
                "reactions": {
                    "👍": ["user_reactor", "user_other"]
                },
                "timestamp": 1793000010000
            }
        """.trimIndent()

        val parsed = ServerMessage.fromJson(rawJson)
        assertTrue(parsed is ServerMessage.ReactionUpdate)
        val update = parsed as ServerMessage.ReactionUpdate
        assertEquals("msg_react_99", update.messageId)
        assertEquals("conv_99", update.conversationId)
        assertEquals("user_reactor", update.userId)
        assertEquals("👍", update.emoji)
        assertEquals("ADDED", update.action)
        assertEquals(1, update.reactions.size)
        assertEquals(listOf("user_reactor", "user_other"), update.reactions["👍"])
    }

    @Test
    fun testMissingReferencedMessageSafety() {
        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "c1",
                senderId = "u1",
                body = "Hello",
                createdAt = 1000L,
                status = MessageStatus.SENT,
                replyToId = "m_missing_deleted_historical"
            )
        )
        // Find referenced message in list - must resolve safely to null without exception
        val referencedMsg = messages.firstOrNull { it.id == messages[0].replyToId }
        assertNull("Missing or deleted parent message must safely return null", referencedMsg)
    }

    @Test
    fun testAllFiveCelestialEffectsInPickerAndTokens() {
        val effects = listOf("nebula", "stardust", "ripple", "pulse", "comet")
        for (effect in effects) {
            assertTrue("Tokens must contain $effect", AsterReactionTokens.CelestialEffects.contains(effect))
            val displayName = AsterReactionTokens.effectDisplayName(effect)
            assertTrue("Display name must be capitalized for $effect", displayName[0].isUpperCase())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Fakes
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeExpressiveMessageRepo : MessageRepository {
        var lastSentBody: String? = null
        var lastSentEffect: String? = null
        var lastToggledMessageId: String? = null
        var lastToggledEmoji: String? = null
        var lastToggledUserId: String? = null
        var lastEffectMessageId: String? = null
        var lastAppliedEffect: String? = null

        override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())

        override suspend fun sendMessage(
            conversationId: String,
            senderId: String,
            body: String,
            replyToId: String?,
            effect: String?
        ): Message {
            lastSentBody = body
            lastSentEffect = effect
            return Message(
                id = "msg_sent",
                conversationId = conversationId,
                senderId = senderId,
                body = body,
                createdAt = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                effect = effect
            )
        }

        override suspend fun toggleReaction(messageId: String, emoji: String, userId: String): Boolean {
            lastToggledMessageId = messageId
            lastToggledEmoji = emoji
            lastToggledUserId = userId
            return true
        }

        override suspend fun setMessageEffect(messageId: String, effect: String?) {
            lastEffectMessageId = messageId
            lastAppliedEffect = effect
        }

        override suspend fun retryMessage(messageId: String): Boolean = true
        override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {}
    }

    private class FakeExpressiveConvRepo : ConversationRepository {
        override fun getConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun getConversation(conversationId: String): Flow<Conversation?> = flowOf(
            Conversation(id = conversationId, title = "Expressive Chat", isGroup = false, createdAt = System.currentTimeMillis())
        )
        override fun getConversationMembers(conversationId: String): Flow<List<User>> = flowOf(emptyList())
        override suspend fun createConversation(conversation: Conversation, memberIds: List<String>) {}
        override suspend fun getOrCreateDirectConversation(otherUserId: String): Conversation =
            Conversation(id = "conv_direct", title = "Direct", isGroup = false, createdAt = System.currentTimeMillis())
        override suspend fun markConversationAsRead(conversationId: String) {}
        override suspend fun createGroup(name: String, memberIds: List<String>): Conversation =
            Conversation(id = "grp_1", title = name, isGroup = true, createdAt = System.currentTimeMillis())
        override suspend fun refreshGroups() {}
        override suspend fun addGroupMember(groupId: String, userId: String) {}
        override suspend fun removeGroupMember(groupId: String, userId: String) {}
        override suspend fun leaveGroup(groupId: String) {}
        override suspend fun renameGroup(groupId: String, newName: String) {}
    }

    private class FakeExpressiveUserRepo : UserRepository {
        private val user = User(id = "user_local", username = "tester", displayName = "Tester")
        override fun getUsers(): Flow<List<User>> = flowOf(listOf(user))
        override fun getUser(userId: String): Flow<User?> = flowOf(user)
        override fun getLocalUser(): Flow<User?> = flowOf(user)
        override suspend fun getLocalUserOnce(): User? = user
        override suspend fun saveUser(user: User) {}
        override suspend fun setAuthenticatedUser(userId: String) {}
        override suspend fun clearAuthenticatedUser() {}
    }
}
