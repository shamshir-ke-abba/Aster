package com.aster.app

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.dao.ConstellationNodePrefDao
import com.aster.app.data.local.entities.ConstellationNodePrefEntity
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.MessageStatus
import com.aster.app.domain.model.User
import com.aster.app.ui.components.BoundingBox
import com.aster.app.ui.components.CONSTELLATION_MAX_ZOOM
import com.aster.app.ui.components.CONSTELLATION_MIN_ZOOM
import com.aster.app.ui.components.calculateConstellationLayout
import com.aster.app.ui.components.calculateDynamicSeparation
import com.aster.app.ui.components.clampPanOffset
import com.aster.app.ui.components.clampToViewportSafeZone
import com.aster.app.ui.components.clampZoom
import com.aster.app.ui.components.computeAuthoritativeDestination
import com.aster.app.ui.components.resolveNodeCollisions
import com.aster.app.ui.components.screenToWorld
import com.aster.app.ui.components.worldToScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import kotlin.math.hypot

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConstellationLivingEvolutionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var nodePrefDao: ConstellationNodePrefDao
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private val density = Density(density = 2.75f, fontScale = 1.0f)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        nodePrefDao = database.constellationNodePrefDao()

        val authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test")
        authStorage.saveAuthenticatedUserId("user_local")
        val webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage)

        conversationRepository = ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient,
            constellationNodePrefDao = nodePrefDao
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testHighlightLayoutPrioritySweetSpot() {
        // Highlighting grants placement priority in the inner sweet-spot tier (~125-165dp)
        val convNormal1 = Conversation(id = "conv_normal_1", title = "Normal 1", createdAt = 1000L, isHighlighted = false)
        val convNormal2 = Conversation(id = "conv_normal_2", title = "Normal 2", createdAt = 2000L, isHighlighted = false)
        val convHighlight = Conversation(id = "conv_high", title = "Highlighted Node", createdAt = 3000L, isHighlighted = true)

        val layout = calculateConstellationLayout(
            conversations = listOf(convNormal1, convNormal2, convHighlight),
            currentUserId = "user_local",
            density = density
        )

        val highNode = layout.directNodes.first { it.conversation.id == "conv_high" }
        val distToYou = hypot(highNode.offset.x, highNode.offset.y)

        // Sweet-spot tier 1 distance is ~125dp * density (+/- jitter)
        val tier1Dist = with(density) { 125.dp.toPx() }
        assertTrue(
            "Highlighted node must be placed in the primary sweet-spot tier (~125dp from YOU)",
            distToYou in (tier1Dist - 30f)..(tier1Dist + 30f)
        )
    }

    @Test
    fun testCustomPositionPlacement() {
        // User-repositioned node coordinates must be preserved in world space
        val customNormX = 120f
        val customNormY = -80f
        val conv = Conversation(
            id = "conv_custom",
            title = "Custom Node",
            createdAt = 1000L,
            customNormalizedX = customNormX,
            customNormalizedY = customNormY
        )

        val layout = calculateConstellationLayout(
            conversations = listOf(conv),
            currentUserId = "user_local",
            density = density
        )

        val node = layout.directNodes.first()
        val expectedWorldX = customNormX * density.density
        val expectedWorldY = customNormY * density.density

        assertEquals("Node world X must match customNormalizedX * density", expectedWorldX, node.offset.x, 0.5f)
        assertEquals("Node world Y must match customNormalizedY * density", expectedWorldY, node.offset.y, 0.5f)
    }

    @Test
    fun testCollisionAvoidanceYouNode() {
        // Placing a node directly on YOU (0,0) must push it out to safe orbit
        val layout = calculateConstellationLayout(emptyList(), "user_local", density)
        val resolved = resolveNodeCollisions(
            targetId = "conv_test",
            targetPos = Offset(0f, 0f),
            layout = layout,
            density = density
        )

        val dist = hypot(resolved.x, resolved.y)
        val minYouDist = with(density) { 54.dp.toPx() }
        assertTrue("Node placed at (0,0) must be pushed away from YOU by at least $minYouDist px", dist >= minYouDist - 0.5f)
    }

    @Test
    fun testCollisionAvoidanceOtherNodes() {
        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val layout = calculateConstellationLayout(listOf(convA), "user_local", density)
        val alicePos = layout.directNodes.first().offset

        // Attempt to place node B on top of Alice
        val resolvedB = resolveNodeCollisions(
            targetId = "conv_b",
            targetPos = alicePos,
            layout = layout,
            density = density
        )

        val dist = hypot(resolvedB.x - alicePos.x, resolvedB.y - alicePos.y)
        val minDist = with(density) { (30.dp + 30.dp + 14.dp).toPx() }
        assertTrue("Node B placed on Alice must be pushed outside collision separation radius", dist >= minDist - 0.5f)
    }

    @Test
    fun testDynamicSeparationGentleNudge() {
        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val layout = calculateConstellationLayout(listOf(convA), "user_local", density)
        val alicePos = layout.directNodes.first().offset

        // Drag finger close to Alice
        val draggedPos = Offset(alicePos.x + 30f, alicePos.y + 30f)
        val offsets = calculateDynamicSeparation(
            draggedId = "dragged_node",
            draggedPos = draggedPos,
            layout = layout,
            density = density
        )

        assertTrue("Adjacent node Alice must receive dynamic separation displacement", offsets.containsKey("conv_a"))
        val displacedPos = offsets["conv_a"]!!
        // Displacement must push Alice away from dragged finger
        val distOriginal = hypot(alicePos.x - draggedPos.x, alicePos.y - draggedPos.y)
        val distDisplaced = hypot(displacedPos.x - draggedPos.x, displacedPos.y - draggedPos.y)
        assertTrue("Displaced position must be farther from touch point", distDisplaced > distOriginal)
    }

    @Test
    fun testViewportBoundaryClamping() {
        val viewportWorld = BoundingBox(left = -500f, top = -500f, right = 500f, bottom = 500f)

        // Attempt to drag node off-screen to the right
        val offScreenRight = Offset(1000f, 0f)
        val clamped = clampToViewportSafeZone(offScreenRight, viewportWorld, density, zoomScale = 1.0f)

        val marginX = with(density) { 36.dp.toPx() }
        assertTrue("Clamped X must stay inside viewport right margin", clamped.x <= viewportWorld.right - marginX + 0.5f)
    }

    @Test
    fun testRoomPersistenceHighlightAndPosition() = runTest {
        // Seed local user and conversation
        val localUser = UserEntity(id = "user_local", username = "saad", displayName = "Saad", isLocalUser = true, isOnline = true)
        database.userDao().insertUser(localUser)

        val convEntity = ConversationEntity(id = "conv_room_test", title = "Room Test", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        database.conversationDao().insertConversation(convEntity)

        val memberEntity = ConversationMemberEntity(conversationId = "conv_room_test", userId = "user_local", role = "MEMBER", joinedAt = 1000L)
        database.conversationMemberDao().insertMember(memberEntity)

        // 1. Initial state: not highlighted, no custom position
        val initialConvs = conversationRepository.getConversations().first()
        val c0 = initialConvs.first { it.id == "conv_room_test" }
        assertFalse("Initial conversation must not be highlighted", c0.isHighlighted)
        assertEquals("Initial conversation must have null customNormalizedX", null, c0.customNormalizedX)

        // 2. Set Highlight in Room
        conversationRepository.setConstellationHighlight("conv_room_test", true)
        val prefAfterHigh = nodePrefDao.getNodePref("conv_room_test")
        assertNotNull("Node pref entity must exist in Room", prefAfterHigh)
        assertTrue("Node pref must be marked highlighted in Room", prefAfterHigh!!.isHighlighted)

        val convsAfterHigh = conversationRepository.getConversations().first()
        val c1 = convsAfterHigh.first { it.id == "conv_room_test" }
        assertTrue("Conversation hydrated from Room must be marked highlighted", c1.isHighlighted)

        // 3. Set Custom Position in Room
        conversationRepository.setConstellationPosition("conv_room_test", 150f, -75f)
        val prefAfterPos = nodePrefDao.getNodePref("conv_room_test")
        assertEquals(150f, prefAfterPos?.customNormalizedX)
        assertEquals(-75f, prefAfterPos?.customNormalizedY)
        assertTrue("Highlight status must be preserved when updating position", prefAfterPos?.isHighlighted == true)

        val convsAfterPos = conversationRepository.getConversations().first()
        val c2 = convsAfterPos.first { it.id == "conv_room_test" }
        assertEquals(150f, c2.customNormalizedX)
        assertEquals(-75f, c2.customNormalizedY)
        assertTrue(c2.isHighlighted)

        // 4. Toggle Highlight Off
        conversationRepository.setConstellationHighlight("conv_room_test", false)
        val convsAfterUnhigh = conversationRepository.getConversations().first()
        val c3 = convsAfterUnhigh.first { it.id == "conv_room_test" }
        assertFalse(c3.isHighlighted)
        // Position must remain intact
        assertEquals(150f, c3.customNormalizedX)
        assertEquals(-75f, c3.customNormalizedY)
    }

    @Test
    fun testDeletedUserInvariant() {
        val deletedUser = User(id = "user_del", username = "deleted_account", displayName = "Deleted User", isDeleted = true)
        val conv = Conversation(
            id = "conv_del",
            title = "Old Chat",
            createdAt = 1000L,
            otherMember = deletedUser
        )

        val layout = calculateConstellationLayout(listOf(conv), "user_local", density)
        val node = layout.directNodes.first()
        assertEquals("Deleted user node title must be 'Deleted User'", "Deleted User", node.titleDisplay)
    }

    @Test
    fun testRepositioningDoesNotShuffleOrRotateOtherNodes() {
        // Invariant ID ordering guarantee: moving node A must NEVER rotate or shuffle nodes B and C
        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val convB = Conversation(id = "conv_b", title = "Bob", createdAt = 2000L)
        val convC = Conversation(id = "conv_c", title = "Charlie", createdAt = 3000L)

        val layoutBefore = calculateConstellationLayout(listOf(convA, convB, convC), "user_local", density)
        val posBBefore = layoutBefore.directNodes.first { it.conversation.id == "conv_b" }.offset
        val posCBefore = layoutBefore.directNodes.first { it.conversation.id == "conv_c" }.offset

        // User repositions Alice to custom coordinates
        val convAMoved = convA.copy(customNormalizedX = 140f, customNormalizedY = -90f)
        val layoutAfter = calculateConstellationLayout(listOf(convAMoved, convB, convC), "user_local", density)
        val posBAfter = layoutAfter.directNodes.first { it.conversation.id == "conv_b" }.offset
        val posCAfter = layoutAfter.directNodes.first { it.conversation.id == "conv_c" }.offset

        assertEquals("Bob's X position must be 100% stable when Alice moves", posBBefore.x, posBAfter.x, 0.001f)
        assertEquals("Bob's Y position must be 100% stable when Alice moves", posBBefore.y, posBAfter.y, 0.001f)
        assertEquals("Charlie's X position must be 100% stable when Alice moves", posCBefore.x, posCAfter.x, 0.001f)
        assertEquals("Charlie's Y position must be 100% stable when Alice moves", posCBefore.y, posCAfter.y, 0.001f)
    }

    @Test
    fun testContinuousPanAndPinchZoomBounds() {
        // Zoom must clamp strictly within [0.55f, 2.25f]
        assertEquals(CONSTELLATION_MIN_ZOOM, clampZoom(0.1f), 0.001f)
        assertEquals(CONSTELLATION_MAX_ZOOM, clampZoom(5.0f), 0.001f)
        assertEquals(1.25f, clampZoom(1.25f), 0.001f)

        // Pan offset clamping must support continuous exploration up to bound
        val normalPan = clampPanOffset(Offset(300f, -400f), zoomScale = 1.0f)
        assertEquals(300f, normalPan.x, 0.001f)
        assertEquals(-400f, normalPan.y, 0.001f)

        val extremePan = clampPanOffset(Offset(5000f, -5000f), zoomScale = 1.0f, maxPanPx = 1600f)
        assertEquals(1600f, extremePan.x, 0.001f)
        assertEquals(-1600f, extremePan.y, 0.001f)
    }

    @Test
    fun testDestinationPreviewOccupiedDetection() {
        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val layout = calculateConstellationLayout(listOf(convA), "user_local", density)
        val alicePos = layout.directNodes.first().offset

        // Scenario 1: Dragging over open space (comfortably clear of Alice and YOU)
        val openPos = Offset(400f, 400f)
        val settledOpen = resolveNodeCollisions("conv_b", openPos, layout, density)
        val openDist = (openPos - settledOpen).getDistance()
        val isOccupiedOpen = openDist > with(density) { 8.dp.toPx() }
        assertFalse("Open space destination must not be marked occupied", isOccupiedOpen)

        // Scenario 2: Dragging directly over Alice (occupied)
        val settledOccupied = resolveNodeCollisions("conv_b", alicePos, layout, density)
        val occupiedDist = (alicePos - settledOccupied).getDistance()
        val isOccupiedAlice = occupiedDist > with(density) { 8.dp.toPx() }
        assertTrue("Dragging over existing node Alice must be detected as occupied", isOccupiedAlice)
    }

    @Test
    fun testDelayedCelestialEffectPersistence() = runTest {
        val messageDao = database.messageDao()
        val convEntity = ConversationEntity(id = "conv_effect_test", title = "Effect Test", isGroup = false, createdAt = 1000L, updatedAt = 1000L)
        database.conversationDao().insertConversation(convEntity)

        val user = UserEntity(id = "user_sender", username = "bob", displayName = "Bob", isLocalUser = false, isOnline = true)
        database.userDao().insertUser(user)

        // Message delivered while away / offline mailbox replay: isEffectPlayed = false
        val messageEntity = MessageEntity(
            id = "msg_nebula_delayed",
            conversationId = "conv_effect_test",
            senderId = "user_sender",
            body = "Look at this effect!",
            effect = "nebula",
            isEffectPlayed = false,
            status = MessageStatus.DELIVERED,
            createdAt = 1000L
        )
        messageDao.insertMessage(messageEntity)

        val retrievedBefore = messageDao.getMessageById("msg_nebula_delayed")
        assertNotNull("Retrieved message must exist", retrievedBefore)
        assertEquals("Effect must be nebula", "nebula", retrievedBefore!!.effect)
        assertFalse("Message must have isEffectPlayed = false on arrival", retrievedBefore.isEffectPlayed)

        // Chat screen animates effect and marks it played
        messageDao.markEffectPlayed("msg_nebula_delayed")

        val retrievedAfter = messageDao.getMessageById("msg_nebula_delayed")
        assertNotNull(retrievedAfter)
        assertTrue("Message must persist isEffectPlayed = true after presentation", retrievedAfter!!.isEffectPlayed)
    }

    @Test
    fun testAuthoritativeDestinationPipelineConsistency() {
        // Phase 8.6 Final Reposition Destination Consistency Repair:
        // Destination preview MUST match the release destination, the spring target, and the reloaded position.
        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val convB = Conversation(id = "conv_b", title = "Bob", createdAt = 2000L)
        val initialLayout = calculateConstellationLayout(listOf(convA, convB), "user_local", density)

        val canvasWidth = 1080f
        val canvasHeight = 1920f
        val panOffset = Offset(120f, -80f)
        val zoomScale = 1.35f

        // Simulated drag pointer screen position
        val pointerScreenPos = Offset(500f, 800f)

        // 1. Authoritative destination calculation
        val resolution = computeAuthoritativeDestination(
            targetConvId = "conv_b",
            isGroup = false,
            pointerScreenPos = pointerScreenPos,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            panOffset = panOffset,
            zoomScale = zoomScale,
            layout = initialLayout,
            density = density
        )

        // The preview is rendered at resolution.resolvedDestination
        val previewDestination = resolution.resolvedDestination
        assertNotNull("Resolved destination must not be null", previewDestination)

        // 2. Release: persisted normalized coordinates
        val normX = previewDestination.x / density.density
        val normY = previewDestination.y / density.density

        // 3. Reload from Room/database
        val convBRepositioned = convB.copy(customNormalizedX = normX, customNormalizedY = normY)
        val reloadedLayout = calculateConstellationLayout(listOf(convA, convBRepositioned), "user_local", density)

        val reloadedNodeB = reloadedLayout.directNodes.first { it.conversation.id == "conv_b" }

        // The reloaded position MUST 100% equal the previewed destination!
        assertEquals("Reloaded X must equal preview destination X", previewDestination.x, reloadedNodeB.offset.x, 0.001f)
        assertEquals("Reloaded Y must equal preview destination Y", previewDestination.y, reloadedNodeB.offset.y, 0.001f)
    }

    @Test
    fun testCustomPositionPreservedWithoutSpiralRejection() {
        // Even when custom position is placed very close to another node or YOU hub,
        // calculateConstellationLayout must honor the exact user coordinates rather than spiraling away!
        val customX = 85f
        val customY = -60f

        val convA = Conversation(id = "conv_a", title = "Alice", createdAt = 1000L)
        val convCustom = Conversation(
            id = "conv_custom",
            title = "Custom Node",
            createdAt = 2000L,
            customNormalizedX = customX,
            customNormalizedY = customY
        )

        val layout = calculateConstellationLayout(listOf(convA, convCustom), "user_local", density)
        val node = layout.directNodes.first { it.conversation.id == "conv_custom" }

        val expectedWorldX = customX * density.density
        val expectedWorldY = customY * density.density

        assertEquals("Custom X must be preserved exactly without spiral relocation", expectedWorldX, node.offset.x, 0.001f)
        assertEquals("Custom Y must be preserved exactly without spiral relocation", expectedWorldY, node.offset.y, 0.001f)
    }

    @Test
    fun testCoordinateTransformInvariance() {
        // Screen to World and World to Screen must be pure inverses under all pan and zoom configurations
        val canvasCenter = Offset(540f, 960f)
        val testPans = listOf(Offset.Zero, Offset(250f, -310f), Offset(-180f, 420f))
        val testZooms = listOf(0.55f, 1.0f, 1.75f, 2.25f)
        val testScreenPoints = listOf(Offset(100f, 200f), Offset(540f, 960f), Offset(980f, 1800f))

        for (pan in testPans) {
            for (zoom in testZooms) {
                for (screenPt in testScreenPoints) {
                    val world = screenToWorld(screenPt, canvasCenter, pan, zoom)
                    val roundTrip = worldToScreen(world, canvasCenter, pan, zoom)

                    assertEquals("Round-trip screen X must match original under pan=$pan, zoom=$zoom", screenPt.x, roundTrip.x, 0.01f)
                    assertEquals("Round-trip screen Y must match original under pan=$pan, zoom=$zoom", screenPt.y, roundTrip.y, 0.01f)
                }
            }
        }
    }
}
