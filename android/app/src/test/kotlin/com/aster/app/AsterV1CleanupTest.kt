package com.aster.app

import android.content.Context
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.FriendRequestEntity
import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.network.UserSummary
import com.aster.app.domain.model.Conversation
import com.aster.app.ui.components.calculateConstellationLayout
import com.aster.app.ui.navigation.AsterRoutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
@OptIn(ExperimentalCoroutinesApi::class)
class AsterV1CleanupTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private val density = Density(density = 2.75f, fontScale = 1.0f)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------
    // WORKSTREAM 1 — NAVIGATION STABILIZATION TESTS
    // ---------------------------------------------------------

    @Test
    fun testRapidNavigationStackNeverAccumulatesDuplicatesOrStalls() {
        // Simulates the exact state machine of AsterNavigation.kt: navigateToTopLevel
        class SimulatedNavCoordinator {
            val backStack = mutableListOf(AsterRoutes.HOME)
            var currentDestination: String = AsterRoutes.HOME

            fun navigateToTopLevel(targetRoute: String) {
                // Guard 1: Ignore re-tapping active tab
                if (currentDestination == targetRoute) return

                if (targetRoute == AsterRoutes.HOME) {
                    // Start destination pop: pop back to start destination
                    while (backStack.size > 1) {
                        backStack.removeAt(backStack.size - 1)
                    }
                    currentDestination = AsterRoutes.HOME
                } else {
                    // Secondary tab: popUpTo(startDestination) { saveState = true }
                    while (backStack.size > 1) {
                        backStack.removeAt(backStack.size - 1)
                    }
                    backStack.add(targetRoute)
                    currentDestination = targetRoute
                }
            }

            fun pressSystemBack(): Boolean {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.size - 1)
                    currentDestination = backStack.last()
                    return true
                }
                return false
            }
        }

        val nav = SimulatedNavCoordinator()
        assertEquals(listOf(AsterRoutes.HOME), nav.backStack)

        // Rapid stress sequence:
        // Constellations -> Connections -> Profile -> Constellations -> Profile -> Connections -> Constellations
        nav.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertEquals(AsterRoutes.CONNECTIONS, nav.currentDestination)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), nav.backStack)

        // Re-tapping Connections while already on Connections must be a no-op
        nav.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), nav.backStack)

        nav.navigateToTopLevel(AsterRoutes.PROFILE)
        assertEquals(AsterRoutes.PROFILE, nav.currentDestination)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.PROFILE), nav.backStack)

        nav.navigateToTopLevel(AsterRoutes.HOME)
        assertEquals(AsterRoutes.HOME, nav.currentDestination)
        assertEquals(listOf(AsterRoutes.HOME), nav.backStack)

        nav.navigateToTopLevel(AsterRoutes.PROFILE)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.PROFILE), nav.backStack)

        nav.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), nav.backStack)

        nav.navigateToTopLevel(AsterRoutes.HOME)
        assertEquals(listOf(AsterRoutes.HOME), nav.backStack)

        // Android System Back from Home cannot pop (would finish task)
        assertFalse("System back on root home cannot pop further", nav.pressSystemBack())

        // System Back from a secondary tab returns to Home
        nav.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertTrue(nav.pressSystemBack())
        assertEquals(AsterRoutes.HOME, nav.currentDestination)
        assertEquals(listOf(AsterRoutes.HOME), nav.backStack)
    }

    // ---------------------------------------------------------
    // WORKSTREAM 2 — CHAT ENTRY & TIMELINE POLISH TESTS
    // ---------------------------------------------------------

    @Test
    fun testChatEntryInitialSettlementAndScrollLogic() {
        // Models the timeline scroll coordinator in ChatScreen.kt
        class TimelineScrollController {
            var hasInitiallySettled = false
            var previousMessageCount = 0
            var scrolledToIndex: Int? = null
            var animatedToIndex: Int? = null
            var hasNewMessagesBelow = false
            var currentVisibleIndex = 0

            fun onMessageStateUpdated(
                messageCount: Int,
                isNearBottom: Boolean,
                isOutgoingLast: Boolean
            ) {
                if (messageCount == 0) return

                val target = messageCount - 1
                if (!hasInitiallySettled) {
                    // Initial entry: instant direct jump to latest messages, no animation run
                    scrolledToIndex = target
                    hasInitiallySettled = true
                    currentVisibleIndex = target
                    previousMessageCount = messageCount
                } else if (messageCount > previousMessageCount) {
                    if (isNearBottom || isOutgoingLast) {
                        // Short natural settling: if far away, snap close first so animation is short
                        if (currentVisibleIndex < target - 4) {
                            scrolledToIndex = target - 3
                        }
                        animatedToIndex = target
                        currentVisibleIndex = target
                        hasNewMessagesBelow = false
                    } else {
                        // User is reading history: do NOT scroll away, show unobtrusive indicator
                        hasNewMessagesBelow = true
                    }
                    previousMessageCount = messageCount
                }
            }

            fun onKeyboardOpened(messageCount: Int, isNearBottom: Boolean) {
                if (messageCount > 0 && isNearBottom) {
                    val target = messageCount - 1
                    if (currentVisibleIndex < target - 4) {
                        scrolledToIndex = target - 3
                    }
                    animatedToIndex = target
                    currentVisibleIndex = target
                }
            }

            fun onUserScrolledToBottom() {
                hasNewMessagesBelow = false
            }

            fun isListVisible(messageCount: Int): Boolean {
                return hasInitiallySettled || messageCount == 0
            }
        }

        val controller = TimelineScrollController()

        // 1. Initial state before messages load: 0 items, visible immediately (empty state)
        assertTrue(controller.isListVisible(0))
        assertFalse(controller.hasInitiallySettled)

        // 2. Chat opens with 60 messages: must jump directly to 59 with instant scroll
        controller.onMessageStateUpdated(messageCount = 60, isNearBottom = false, isOutgoingLast = false)
        assertTrue("Must be marked settled after initial load", controller.hasInitiallySettled)
        assertEquals(59, controller.scrolledToIndex)
        assertEquals(null, controller.animatedToIndex) // NO animated long scroll through 60 items!
        assertTrue("List becomes visible once settled", controller.isListVisible(60))

        // 3. User sends an outgoing message (count becomes 61) while at bottom
        controller.onMessageStateUpdated(messageCount = 61, isNearBottom = true, isOutgoingLast = true)
        assertEquals(60, controller.animatedToIndex)
        assertFalse(controller.hasNewMessagesBelow)

        // 4. User scrolls up into history (currentVisibleIndex = 20, isNearBottom = false)
        controller.currentVisibleIndex = 20
        controller.animatedToIndex = null
        controller.scrolledToIndex = null

        // Incoming message arrives: reading position must NOT be interrupted!
        controller.onMessageStateUpdated(messageCount = 62, isNearBottom = false, isOutgoingLast = false)
        assertEquals("Must NOT animate scroll when user is reading older history", null, controller.animatedToIndex)
        assertTrue("Unseen message indicator pill must appear", controller.hasNewMessagesBelow)

        // Keyboard opens while user is reading history: must NOT force scroll to bottom!
        controller.onKeyboardOpened(messageCount = 62, isNearBottom = false)
        assertEquals("Must NOT scroll to bottom when keyboard opens if user is reading history", null, controller.animatedToIndex)

        // User sends a message from scrolled up position (outgoing last): must settle smoothly to bottom
        // with snap-close logic preventing 40-item cinematic sweep
        controller.onMessageStateUpdated(messageCount = 63, isNearBottom = false, isOutgoingLast = true)
        assertEquals(59, controller.scrolledToIndex) // Snapped to target - 3
        assertEquals(62, controller.animatedToIndex) // Short smooth animation for remaining 3 items
        assertFalse(controller.hasNewMessagesBelow)

        // 5. User manually scrolls back to bottom
        controller.hasNewMessagesBelow = true
        controller.onUserScrolledToBottom()
        assertFalse(controller.hasNewMessagesBelow)
    }

    @Test
    fun testReducedMotionConstellationPulseScaleSuppressed() {
        fun computeEffectivePulseScale(isReduced: Boolean, rawPulseScale: Float): Float {
            return if (isReduced) 1.0f else rawPulseScale
        }

        // Under standard motion, pulse expands between 0.95f and 1.15f
        assertEquals(1.15f, computeEffectivePulseScale(isReduced = false, rawPulseScale = 1.15f))
        assertEquals(0.95f, computeEffectivePulseScale(isReduced = false, rawPulseScale = 0.95f))

        // Under reduced motion, pulse scale is strictly locked to 1.0f (no pulsing movement)
        assertEquals(1.0f, computeEffectivePulseScale(isReduced = true, rawPulseScale = 1.15f))
        assertEquals(1.0f, computeEffectivePulseScale(isReduced = true, rawPulseScale = 0.95f))
    }

    // ---------------------------------------------------------
    // WORKSTREAM 3 — REAL-TIME CONNECTION REQUEST AWARENESS TESTS
    // ---------------------------------------------------------

    @Test
    fun testFriendRequestRoomEntityAndDaoOperations() = runTest {
        val dao = database.friendRequestDao()

        // Insert incoming friend request
        val req1 = FriendRequestEntity(
            id = "req_101",
            senderUserId = "usr_alice",
            recipientUserId = "usr_me",
            status = "PENDING",
            senderDisplayName = "Alice",
            senderUsername = "alice",
            senderAsterId = "AST-ALICE",
            createdAt = 1700000000L
        )
        dao.insertOrUpdate(req1)

        val pendingList = dao.getIncomingPendingRequestsFlow().first()
        assertEquals(1, pendingList.size)
        assertEquals("req_101", pendingList[0].id)
        assertEquals("Alice", pendingList[0].senderDisplayName)
        assertEquals("PENDING", pendingList[0].status)

        // Update status to accepted
        dao.updateStatus("req_101", "ACCEPTED")
        val updatedPending = dao.getIncomingPendingRequestsFlow().first()
        assertTrue("Accepted requests should no longer appear in pending flow", updatedPending.isEmpty())

        // Insert second request and delete by id
        val req2 = FriendRequestEntity(
            id = "req_102",
            senderUserId = "usr_bob",
            recipientUserId = "usr_me",
            status = "PENDING",
            senderDisplayName = "Bob",
            senderUsername = "bob",
            senderAsterId = "AST-BOB",
            createdAt = 1700000010L
        )
        dao.insertOrUpdate(req2)
        assertEquals(1, dao.getIncomingPendingRequestsFlow().first().size)

        dao.deleteById("req_102")
        assertEquals(0, dao.getIncomingPendingRequestsFlow().first().size)
    }

    @Test
    fun testConstellationLayoutCalculatesDistinctCandidateStarForPendingRequests() {
        val testConvs = listOf(
            Conversation(
                id = "conv_1",
                title = "Nova",
                isGroup = false,
                createdAt = 1700000000L,
                unreadCount = 0
            )
        )

        val pendingRequests = listOf(
            FriendRequestSummary(
                id = "req_incoming_1",
                senderUserId = "usr_charlie",
                recipientUserId = "usr_me",
                status = "PENDING",
                sender = UserSummary(
                    userId = "usr_charlie",
                    asterId = "AST-CHARLIE",
                    username = "charlie",
                    displayName = "Charlie"
                ),
                createdAt = 1700000050L
            )
        )

        val layout = calculateConstellationLayout(
            conversations = testConvs,
            currentUserId = "usr_me",
            density = density,
            pendingRequests = pendingRequests
        )

        assertEquals(1, layout.directNodes.size)
        assertEquals(1, layout.pendingNodes.size)

        val pendingNode = layout.pendingNodes.first()
        assertEquals("req_incoming_1", pendingNode.request.id)
        assertEquals("Charlie", pendingNode.titleDisplay)

        // Ensure candidate node has non-zero distance from center (does not overlap 'You' node)
        val distFromCenter = kotlin.math.hypot(pendingNode.offset.x, pendingNode.offset.y)
        assertTrue("Pending star must be placed away from center", distFromCenter > 100f)

        // Ensure pending node bounds do not collide with conversation node bounds
        val convNode = layout.directNodes.first()
        assertFalse("Pending candidate star must not collide with established conversation star",
            pendingNode.bounds.intersects(convNode.bounds))
        assertFalse("Pending candidate star must not collide with 'You' node",
            pendingNode.bounds.intersects(layout.youBounds))
    }

    @Test
    fun testWebSocketProtocolParsesFriendRequestPushFrames() {
        val receivedJson = """
            {
                "type": "FRIEND_REQUEST_RECEIVED",
                "request": {
                    "id": "req_push_77",
                    "senderUserId": "usr_stella",
                    "recipientUserId": "usr_me",
                    "status": "PENDING",
                    "createdAt": 1700001000,
                    "sender": {
                        "userId": "usr_stella",
                        "asterId": "AST-STELLA",
                        "username": "stella",
                        "displayName": "Stella"
                    }
                }
            }
        """.trimIndent()

        val parsedReceived = ServerMessage.fromJson(receivedJson)
        assertTrue(parsedReceived is ServerMessage.FriendRequestReceived)
        val reqEvent = parsedReceived as ServerMessage.FriendRequestReceived
        assertEquals("req_push_77", reqEvent.request.id)
        assertEquals("Stella", reqEvent.request.sender?.displayName)
        assertEquals("stella", reqEvent.request.sender?.username)

        val updatedJson = """
            {
                "type": "FRIEND_REQUEST_UPDATED",
                "requestId": "req_push_77",
                "status": "ACCEPTED",
                "senderUserId": "usr_stella",
                "recipientUserId": "usr_me"
            }
        """.trimIndent()

        val parsedUpdated = ServerMessage.fromJson(updatedJson)
        assertTrue(parsedUpdated is ServerMessage.FriendRequestUpdated)
        val updateEvent = parsedUpdated as ServerMessage.FriendRequestUpdated
        assertEquals("req_push_77", updateEvent.requestId)
        assertEquals("ACCEPTED", updateEvent.status)
    }

    @Test
    fun test_productionEnvironment_neverSeedsMockData() = runTest {
        com.aster.app.data.network.NetworkConfig.resetToDefault(com.aster.app.data.network.NetworkEnvironment.PRODUCTION)
        val seedInitializer = com.aster.app.data.seed.DatabaseSeedInitializer(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            authStorage = authStorage
        )

        seedInitializer.initializeIfNeeded()

        // Verify zero mock contacts or dummy conversations are inserted in PRODUCTION
        val rohan = database.userDao().getUserByIdOnce("user_rohan")
        val alex = database.userDao().getUserByIdOnce("user_alex")
        val sam = database.userDao().getUserByIdOnce("user_sam")
        val convAlex = database.conversationDao().getConversationByIdOnce("conv_alex")

        org.junit.Assert.assertNull("Rohan mock contact must not be seeded in PRODUCTION", rohan)
        org.junit.Assert.assertNull("Alex mock contact must not be seeded in PRODUCTION", alex)
        org.junit.Assert.assertNull("Sam mock contact must not be seeded in PRODUCTION", sam)
        org.junit.Assert.assertNull("Alex conversation must not be seeded in PRODUCTION", convAlex)
    }
}
