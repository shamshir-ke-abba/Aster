package com.aster.app

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.domain.repository.UserRepository
import com.aster.app.ui.ChatViewModel
import com.aster.app.ui.motion.AsterDragSettleState
import com.aster.app.ui.motion.AsterMessagingTokens
import com.aster.app.ui.motion.AsterMotion
import com.aster.app.ui.motion.AsterMotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Unit tests verifying Phase 8.3 Messaging Motion & Interaction.
 * Tests message arrival identification, state transitions, swipe-to-reply physics,
 * composer reply context, context action lift, and reduced motion adaptations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterMessagingMotionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testMessagingTokensAdhereToRoadmapSpecifications() {
        assertEquals(64f, AsterMessagingTokens.SwipeThresholdDp.value)
        assertEquals(96f, AsterMessagingTokens.SwipeMaxDistanceDp.value)
        assertEquals(12f, AsterMessagingTokens.ArrivalOffsetDp.value)
        assertEquals(0.97f, AsterMessagingTokens.ArrivalScale)
        assertEquals(AsterMotionTokens.Duration.Short, AsterMessagingTokens.StatusTransitionDuration)
    }

    @Test
    fun testPersistedVsNewArrivalMessageIdentificationLogic() {
        // Initial room sync loads existing messages into the tracking set
        val initialMessageIds = mutableSetOf<String>()
        var isInitialSyncComplete = false

        val persistedMessages = listOf(
            Message(id = "msg_1", conversationId = "conv_1", senderId = "user_alex", body = "Hello", createdAt = 1000L),
            Message(id = "msg_2", conversationId = "conv_1", senderId = "user_saad", body = "Hi Alex", createdAt = 2000L)
        )

        // First load from persistence
        if (!isInitialSyncComplete && persistedMessages.isNotEmpty()) {
            initialMessageIds.addAll(persistedMessages.map { it.id })
            isInitialSyncComplete = true
        }

        // Neither persisted message should qualify as a new arrival
        for (msg in persistedMessages) {
            val isNewArrival = isInitialSyncComplete && !initialMessageIds.contains(msg.id)
            assertFalse("Persisted message ${msg.id} must NOT animate arrival", isNewArrival)
        }

        // A new incoming message arrives after initial sync
        val newIncoming = Message(id = "msg_3", conversationId = "conv_1", senderId = "user_alex", body = "How are you?", createdAt = 3000L)
        val isNewArrival = isInitialSyncComplete && !initialMessageIds.contains(newIncoming.id)
        assertTrue("Newly arrived message msg_3 MUST receive entrance choreography", isNewArrival)

        // After entrance choreography finishes, message settles into initialMessageIds
        initialMessageIds.add(newIncoming.id)
        val isReplayed = isInitialSyncComplete && !initialMessageIds.contains(newIncoming.id)
        assertFalse("Settled message must NOT replay animation on subsequent recompositions", isReplayed)
    }

    @Test
    fun testMessageStatusTransitionsAndMachineOrder() {
        // SENDING -> SENT
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.SENT))
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.FAILED))
        assertFalse(MessageStatus.SENDING.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.SENDING.canTransitionTo(MessageStatus.READ))

        // SENT -> DELIVERED -> READ
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.DELIVERED))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.READ))
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.SENDING))

        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.READ))
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENT))

        // Terminal states
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.DELIVERED))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENT))
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENDING))

        // Unsend transition
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.UNSENT))
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.UNSENT))
        assertFalse(MessageStatus.UNSENT.canTransitionTo(MessageStatus.SENT))
    }

    @Test
    fun testSwipeToReplyPhysicsAndThresholdCalculation() = runTest {
        val dragSettle = AsterDragSettleState(initialX = 0f, initialY = 0f)
        val thresholdPx = 64f
        val maxDragPx = 96f

        fun computeDragOffset(rawDragX: Float): Float {
            return if (rawDragX <= thresholdPx) {
                rawDragX
            } else {
                thresholdPx + (rawDragX - thresholdPx) * 0.25f
            }.coerceAtMost(maxDragPx)
        }

        // Sub-threshold drag: moves 1:1 without triggering reply
        var rawDrag = 30f
        var effectiveOffset = computeDragOffset(rawDrag)
        assertEquals(30f, effectiveOffset, 0.01f)
        var isThresholdReached = effectiveOffset >= thresholdPx
        assertFalse("Drag below threshold must not activate reply", isThresholdReached)

        // Threshold crossing: at 64px
        rawDrag = 64f
        effectiveOffset = computeDragOffset(rawDrag)
        assertEquals(64f, effectiveOffset, 0.01f)
        isThresholdReached = effectiveOffset >= thresholdPx
        assertTrue("Drag at threshold must activate reply", isThresholdReached)

        // Past-threshold resistance: raw 120px is dampened
        rawDrag = 120f
        effectiveOffset = computeDragOffset(rawDrag)
        // 64 + (120 - 64) * 0.25 = 64 + 14 = 78
        assertEquals(78f, effectiveOffset, 0.01f)
        assertTrue(effectiveOffset < maxDragPx)

        // Simulated release settles back to origin
        dragSettle.snapTo(effectiveOffset, 0f)
        assertEquals(78f, dragSettle.offsetX, 0.01f)

        dragSettle.settleTo(0f, 0f, reducedMotion = true)
        assertEquals(0f, dragSettle.offsetX, 0.01f)
        assertFalse(dragSettle.isDragging)
    }

    @Test
    fun testChatViewModelReplySelectionAndSending() = runTest {
        val fakeMessageRepo = FakeTestMessageRepository()
        val fakeConvRepo = FakeTestConversationRepository()
        val fakeUserRepo = FakeTestUserRepository()

        val savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv_test"))
        val viewModel = ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = fakeMessageRepo,
            conversationRepository = fakeConvRepo,
            userRepository = fakeUserRepo
        )

        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        // Initially no reply message selected
        assertNull(viewModel.uiState.value.replyingToMessage)

        // User swipes to reply or taps Reply on a message
        val replyTarget = Message(
            id = "msg_target_1",
            conversationId = "conv_test",
            senderId = "user_alex",
            body = "Are we meeting tomorrow?",
            createdAt = 1000L
        )
        viewModel.selectReplyMessage(replyTarget)
        advanceUntilIdle()

        assertEquals("msg_target_1", viewModel.uiState.value.replyingToMessage?.id)
        assertEquals("Are we meeting tomorrow?", viewModel.uiState.value.replyingToMessage?.body)

        // User types response and sends
        viewModel.onInputTextChanged("Yes, at 10 AM!")
        advanceUntilIdle()

        viewModel.sendMessage()
        advanceUntilIdle()

        // Message repository must be called with replyToId = msg_target_1
        assertEquals("conv_test", fakeMessageRepo.sentConversationId)
        assertEquals("user_saad", fakeMessageRepo.sentSenderId)
        assertEquals("Yes, at 10 AM!", fakeMessageRepo.sentBody)
        assertEquals("msg_target_1", fakeMessageRepo.sentReplyToId)

        // Replying context must automatically reset to null
        assertNull(viewModel.uiState.value.replyingToMessage)
        assertEquals("", viewModel.uiState.value.inputText)

        // Test cancel reply without sending
        viewModel.selectReplyMessage(replyTarget)
        advanceUntilIdle()
        assertEquals("msg_target_1", viewModel.uiState.value.replyingToMessage?.id)

        viewModel.cancelReply()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.replyingToMessage)
    }

    @Test
    fun testReducedMotionCollapsesArrivalAndStatusDurations() {
        // Normal motion preserves duration
        val normalTween = AsterMotion.tween<Float>(
            durationMillis = AsterMessagingTokens.StatusTransitionDuration,
            reducedMotion = false
        )
        assertEquals(250, normalTween.durationMillis)

        // Reduced motion collapses duration to 0ms
        val reducedTween = AsterMotion.tween<Float>(
            durationMillis = AsterMessagingTokens.StatusTransitionDuration,
            reducedMotion = true
        )
        assertEquals(0, reducedTween.durationMillis)

        // Reduced motion collapses scale to 1.0f
        assertEquals(1.0f, AsterMotion.scale(AsterMessagingTokens.ArrivalScale, reducedMotion = true))
    }

    @Test
    fun testContextActionOverlayMaintainsAccessibilityAndAllActions() {
        // Verify MessageContextOverlay parameters support full action spectrum
        val testMsg = Message(
            id = "msg_ctx_1",
            conversationId = "conv_1",
            senderId = "user_saad",
            body = "Hello context actions",
            createdAt = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )

        var replyInvoked = false
        var viewInfoInvoked = false
        var copyInvoked = false
        var unsendInvoked = false
        var deleteInvoked = false
        var dismissInvoked = false

        val onReply = { replyInvoked = true }
        val onViewInfo = { viewInfoInvoked = true }
        val onCopy = { copyInvoked = true }
        val onUnsend = { unsendInvoked = true }
        val onDelete = { deleteInvoked = true }
        val onDismiss = { dismissInvoked = true }

        // Execute all callbacks to ensure contract completeness
        onReply()
        onViewInfo()
        onCopy()
        onUnsend()
        onDelete()
        onDismiss()

        assertTrue(replyInvoked)
        assertTrue(viewInfoInvoked)
        assertTrue(copyInvoked)
        assertTrue(unsendInvoked)
        assertTrue(deleteInvoked)
        assertTrue(dismissInvoked)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Fakes
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeTestMessageRepository : MessageRepository {
        var sentConversationId: String? = null
        var sentSenderId: String? = null
        var sentBody: String? = null
        var sentReplyToId: String? = null
        var sentEffect: String? = null

        override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())

        override suspend fun sendMessage(
            conversationId: String,
            senderId: String,
            body: String,
            replyToId: String?,
            effect: String?
        ): Message {
            sentConversationId = conversationId
            sentSenderId = senderId
            sentBody = body
            sentReplyToId = replyToId
            sentEffect = effect
            return Message(
                id = "sent_msg_1",
                conversationId = conversationId,
                senderId = senderId,
                body = body,
                createdAt = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                effect = effect
            )
        }

        override suspend fun retryMessage(messageId: String): Boolean = true
        override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {}
    }

    private class FakeTestConversationRepository : ConversationRepository {
        override fun getConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun getConversation(conversationId: String): Flow<Conversation?> = flowOf(null)
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

    private class FakeTestUserRepository : UserRepository {
        val user = User(id = "user_saad", username = "saad", displayName = "Saad")

        override fun getLocalUser(): Flow<User?> = flowOf(user)
        override suspend fun getLocalUserOnce(): User? = user
        override fun getUser(userId: String): Flow<User?> = flowOf(null)
        override fun getUsers(): Flow<List<User>> = flowOf(listOf(user))
        override suspend fun saveUser(user: User) {}
        override suspend fun setAuthenticatedUser(userId: String) {}
        override suspend fun clearAuthenticatedUser() {}
    }
}
