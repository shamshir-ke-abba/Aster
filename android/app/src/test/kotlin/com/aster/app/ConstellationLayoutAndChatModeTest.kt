package com.aster.app

import android.content.Context
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.ui.HomeViewModel
import com.aster.app.ui.SettingsViewModel
import com.aster.app.ui.components.BoundingBox
import com.aster.app.ui.components.CONSTELLATION_DEFAULT_ZOOM
import com.aster.app.ui.components.CONSTELLATION_MAX_ZOOM
import com.aster.app.ui.components.CONSTELLATION_MIN_ZOOM
import com.aster.app.ui.components.calculateConstellationLayout
import com.aster.app.ui.components.calculateOffScreenIndicators
import com.aster.app.ui.components.clampPanOffset
import com.aster.app.ui.components.clampZoom
import com.aster.app.ui.components.findOffScreenConversations
import com.aster.app.ui.components.getViewportWorldBounds
import com.aster.app.ui.components.projectRayToRectEdge
import com.aster.app.ui.components.screenToWorld
import com.aster.app.ui.components.worldToScreen
import com.aster.app.ui.components.worldBoxToScreenBox
import com.aster.app.ui.components.OffScreenConversation
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ConstellationLayoutAndChatModeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var database: AsterDatabase
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var seedInitializer: DatabaseSeedInitializer
    private lateinit var webSocketClient: AsterWebSocketClient
    private val density = Density(density = 2.75f, fontScale = 1.0f)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_saad")
        authStorage.saveAuthenticatedUserId("user_saad")
        authStorage.saveDisplayName("Saad")
        authStorage.saveUsername("saad")

        webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage)

        userRepository = UserRepositoryImpl(database.userDao())
        conversationRepository = ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient
        )
        seedInitializer = DatabaseSeedInitializer(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            authStorage = authStorage
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    private fun createSampleConversations(numDirect: Int, numGroups: Int): List<Conversation> {
        val list = mutableListOf<Conversation>()
        for (i in 1..numDirect) {
            val user = User(id = "user_$i", username = "user$i", displayName = "Contact $i", isLocalUser = false)
            list.add(
                Conversation(
                    id = "conv_direct_$i",
                    title = "Contact $i Long Name $i",
                    isGroup = false,
                    createdAt = System.currentTimeMillis() - (i * 1000L),
                    otherMember = user,
                    unreadCount = if (i % 2 == 0) i else 0
                )
            )
        }
        for (g in 1..numGroups) {
            val members = (1..3).map { m ->
                User(id = "grp_m_${g}_$m", username = "member$m", displayName = "Member $m", isLocalUser = false)
            }
            list.add(
                Conversation(
                    id = "conv_group_$g",
                    title = "Group Project Alpha $g",
                    isGroup = true,
                    createdAt = System.currentTimeMillis() - (g * 2000L),
                    members = members,
                    unreadCount = if (g == 1) 3 else 0
                )
            )
        }
        return list
    }

    // --- Layout Tests ---

    @Test
    fun layout_deterministicPositionGeneration() {
        val conversations = createSampleConversations(numDirect = 5, numGroups = 3)

        val layoutRun1 = calculateConstellationLayout(conversations, "user_saad", density)
        val layoutRun2 = calculateConstellationLayout(conversations, "user_saad", density)

        assertEquals(layoutRun1.directNodes.size, layoutRun2.directNodes.size)
        assertEquals(layoutRun1.groupNodes.size, layoutRun2.groupNodes.size)

        for (i in layoutRun1.directNodes.indices) {
            val n1 = layoutRun1.directNodes[i]
            val n2 = layoutRun2.directNodes[i]
            assertEquals(n1.conversation.id, n2.conversation.id)
            assertEquals("Direct node ${n1.conversation.id} x must match across runs", n1.offset.x, n2.offset.x, 0.001f)
            assertEquals("Direct node ${n1.conversation.id} y must match across runs", n1.offset.y, n2.offset.y, 0.001f)
        }

        for (i in layoutRun1.groupNodes.indices) {
            val g1 = layoutRun1.groupNodes[i]
            val g2 = layoutRun2.groupNodes[i]
            assertEquals(g1.conversation.id, g2.conversation.id)
            assertEquals("Group nexus ${g1.conversation.id} x must match across runs", g1.nexusOffset.x, g2.nexusOffset.x, 0.001f)
            assertEquals("Group nexus ${g1.conversation.id} y must match across runs", g1.nexusOffset.y, g2.nexusOffset.y, 0.001f)
        }
    }

    @Test
    fun layout_differentConversationsProduceVariedDistancesAndAngles() {
        val conversations = createSampleConversations(numDirect = 6, numGroups = 2)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val distances = layout.directNodes.map { hypot(it.offset.x, it.offset.y) }
        val minDistance = distances.minOrNull() ?: 0f
        val maxDistance = distances.maxOrNull() ?: 0f

        // Distances must vary significantly (not a fixed circular radius)
        assertTrue(
            "Positions must vary in distance across organic tiers (min: $minDistance, max: $maxDistance)",
            maxDistance - minDistance > with(density) { 40.dp.toPx() }
        )

        // Must not collapse onto one single point or axis
        val xPositions = layout.directNodes.map { it.offset.x }
        val yPositions = layout.directNodes.map { it.offset.y }
        assertTrue("Positions must distribute across multiple directions", xPositions.distinct().size > 3)
        assertTrue("Positions must distribute across multiple directions", yPositions.distinct().size > 3)
    }

    @Test
    fun layout_collisionDetectionRejectsOverlappingLayoutUnits() {
        // Stress test with 12 direct contacts and 4 group clusters
        val conversations = createSampleConversations(numDirect = 12, numGroups = 4)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val allBoxes = mutableListOf<Pair<String, BoundingBox>>()
        allBoxes.add("YOU" to layout.youBounds)

        for (node in layout.directNodes) {
            allBoxes.add("Direct_${node.conversation.id}" to node.bounds)
        }
        for (group in layout.groupNodes) {
            allBoxes.add("Group_${group.conversation.id}" to group.bounds)
        }

        // Pairwise verify that NO visual unit bounding boxes intersect!
        for (i in allBoxes.indices) {
            for (j in (i + 1) until allBoxes.size) {
                val (idA, boxA) = allBoxes[i]
                val (idB, boxB) = allBoxes[j]
                val intersects = boxA.intersects(boxB)
                assertFalse(
                    "Collision detected between $idA and $idB: boxA=$boxA, boxB=$boxB",
                    intersects
                )
            }
        }
    }

    @Test
    fun layout_labelsAreIncludedInLayoutBounds() {
        val conversations = createSampleConversations(numDirect = 1, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val directNode = layout.directNodes.first()
        val avatarRadiusPx = with(density) { 22.dp.toPx() }
        assertTrue(
            "Direct node bottom bound (${directNode.bounds.bottom}) must extend beyond avatar bottom (${directNode.offset.y + avatarRadiusPx}) to include label",
            directNode.bounds.bottom > directNode.offset.y + avatarRadiusPx + with(density) { 15.dp.toPx() }
        )

        val groupNode = layout.groupNodes.first()
        val nexusRadiusPx = with(density) { 26.dp.toPx() }
        assertTrue(
            "Group node bottom bound (${groupNode.bounds.bottom}) must extend beyond nexus bottom (${groupNode.nexusOffset.y + nexusRadiusPx}) to include title and cluster subtitle",
            groupNode.bounds.bottom > groupNode.nexusOffset.y + nexusRadiusPx + with(density) { 20.dp.toPx() }
        )
    }

    @Test
    fun layout_groupClustersOccupyLargerCollisionBounds() {
        val conversations = createSampleConversations(numDirect = 1, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val directBounds = layout.directNodes.first().bounds
        val groupBounds = layout.groupNodes.first().bounds

        val directArea = directBounds.width * directBounds.height
        val groupArea = groupBounds.width * groupBounds.height

        assertTrue(
            "Group cluster area ($groupArea) must be larger than direct contact area ($directArea) to account for nexus, satellites, and labels",
            groupArea > directArea * 1.5f
        )
    }

    // --- Chat Mode Tests ---

    @Test
    fun chatMode_settingsCanSelectModesAndPersist() = runTest(testDispatcher) {
        val settingsViewModel = SettingsViewModel(
            apiClient = AsterApiClient(OkHttpClient(), authStorage),
            authStorage = authStorage,
            webSocketClient = webSocketClient
        )

        // Initial default is CONSTELLATION
        assertEquals("CONSTELLATION", authStorage.getViewMode())
        assertEquals("CONSTELLATION", settingsViewModel.uiState.value.viewMode)

        // Select LIST
        settingsViewModel.setViewMode("LIST")
        assertEquals("LIST", settingsViewModel.uiState.value.viewMode)
        assertEquals("LIST", authStorage.getViewMode())

        // Select CONSTELLATION again
        settingsViewModel.setViewMode("CONSTELLATION")
        assertEquals("CONSTELLATION", settingsViewModel.uiState.value.viewMode)
        assertEquals("CONSTELLATION", authStorage.getViewMode())
    }

    @Test
    fun chatMode_homeReflectsPersistedModeAndRecreation() = runTest(testDispatcher) {
        seedInitializer.initializeIfNeeded()

        // Set mode to LIST in storage
        authStorage.saveViewMode("LIST")

        // Instantiate HomeViewModel
        val homeViewModel = HomeViewModel(
            conversationRepository = conversationRepository,
            userRepository = userRepository,
            seedInitializer = seedInitializer,
            webSocketClient = webSocketClient,
            authStorage = authStorage
        )

        val state1 = homeViewModel.uiState.filter { !it.isLoading }.first()
        assertEquals("Home must reflect the persisted LIST mode", "LIST", state1.viewMode)

        // Change mode in storage to CONSTELLATION
        authStorage.saveViewMode("CONSTELLATION")

        val state2 = homeViewModel.uiState.filter { !it.isLoading && it.viewMode == "CONSTELLATION" }.first()
        assertEquals("Home must reactively update to CONSTELLATION", "CONSTELLATION", state2.viewMode)
    }

    @Test
    fun chatMode_survivesStorageReloadAndAppRestart() = runTest(testDispatcher) {
        seedInitializer.initializeIfNeeded()
        authStorage.saveViewMode("LIST")

        // Simulate new app launch with fresh AuthStorage instance reading same prefs
        val freshAuthStorage = AsterAuthStorage(context)
        assertEquals("Persisted mode must survive app reload", "LIST", freshAuthStorage.getViewMode())

        val freshHomeViewModel = HomeViewModel(
            conversationRepository = conversationRepository,
            userRepository = userRepository,
            seedInitializer = seedInitializer,
            webSocketClient = webSocketClient,
            authStorage = freshAuthStorage
        )

        val state = freshHomeViewModel.uiState.filter { !it.isLoading }.first()
        assertEquals("Fresh HomeViewModel must load persisted mode", "LIST", state.viewMode)
    }

    // --- Phase 4.3 Zoom, Pan & Off-Screen Awareness Tests ---

    @Test
    fun zoom_boundsAndClampingLimits() {
        assertEquals("Default zoom must be 1.0x", 1.0f, CONSTELLATION_DEFAULT_ZOOM, 0.001f)
        assertEquals("Minimum zoom bound must be 0.55x", 0.55f, CONSTELLATION_MIN_ZOOM, 0.001f)
        assertEquals("Maximum zoom bound must be 2.25x", 2.25f, CONSTELLATION_MAX_ZOOM, 0.001f)

        // Underflow clamped to MIN_ZOOM
        assertEquals(0.55f, clampZoom(0.1f), 0.001f)
        assertEquals(0.55f, clampZoom(-1.0f), 0.001f)

        // Overflow clamped to MAX_ZOOM
        assertEquals(2.25f, clampZoom(3.5f), 0.001f)
        assertEquals(2.25f, clampZoom(10.0f), 0.001f)

        // In-range zoom preserved
        assertEquals(1.45f, clampZoom(1.45f), 0.001f)
    }

    @Test
    fun zoom_preservesUnderlyingConstellationWorldCoordinates() {
        val conversations = createSampleConversations(numDirect = 5, numGroups = 3)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        // Record initial world coordinates
        val directOffsetsBefore = layout.directNodes.map { it.offset }
        val directBoundsBefore = layout.directNodes.map { it.bounds }
        val groupOffsetsBefore = layout.groupNodes.map { it.nexusOffset }
        val groupBoundsBefore = layout.groupNodes.map { it.bounds }

        // Simulate zooming viewport by applying transformations
        val canvasCenter = Offset(540f, 960f)
        val zoomLevels = listOf(0.55f, 0.8f, 1.0f, 1.6f, 2.25f)

        for (zoom in zoomLevels) {
            val screenPos = worldToScreen(layout.directNodes.first().offset, canvasCenter, Offset.Zero, zoom)
            val backToWorld = screenToWorld(screenPos, canvasCenter, Offset.Zero, zoom)
            assertEquals("World coordinate X must roundtrip exactly under zoom $zoom",
                layout.directNodes.first().offset.x, backToWorld.x, 0.01f)
            assertEquals("World coordinate Y must roundtrip exactly under zoom $zoom",
                layout.directNodes.first().offset.y, backToWorld.y, 0.01f)
        }

        // World coordinates inside layout must remain identical
        for (i in layout.directNodes.indices) {
            assertEquals("Direct node world offset must never change", directOffsetsBefore[i], layout.directNodes[i].offset)
            assertEquals("Direct node bounds must never change", directBoundsBefore[i], layout.directNodes[i].bounds)
        }
        for (i in layout.groupNodes.indices) {
            assertEquals("Group nexus world offset must never change", groupOffsetsBefore[i], layout.groupNodes[i].nexusOffset)
            assertEquals("Group bounds must never change", groupBoundsBefore[i], layout.groupNodes[i].bounds)
        }
    }

    @Test
    fun pan_boundsAndClamping() {
        val maxBound = 1600f
        val clampedOffset = clampPanOffset(Offset(5000f, -6000f), zoomScale = 1.0f, maxPanPx = maxBound)
        assertEquals(maxBound, clampedOffset.x, 0.001f)
        assertEquals(-maxBound, clampedOffset.y, 0.001f)

        val insideOffset = clampPanOffset(Offset(250f, -300f), zoomScale = 1.0f, maxPanPx = maxBound)
        assertEquals(250f, insideOffset.x, 0.001f)
        assertEquals(-300f, insideOffset.y, 0.001f)
    }

    @Test
    fun offScreen_visibleConversationsProduceNoIndicators() {
        val conversations = createSampleConversations(numDirect = 3, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        // Viewport large enough to encompass all sample nodes
        val viewportW = 3000f
        val viewportH = 3000f
        val canvasCenter = Offset(1500f, 1500f)
        val viewportBounds = getViewportWorldBounds(viewportW, viewportH, canvasCenter, Offset.Zero, 1.0f)

        val offScreen = findOffScreenConversations(layout, viewportBounds)
        assertTrue("When all nodes fit in viewport, off-screen list must be empty", offScreen.isEmpty())

        val indicators = calculateOffScreenIndicators(
            offScreenList = offScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = Offset.Zero,
            zoomScale = 1.0f,
            density = density
        )
        assertTrue("No indicators should appear when all nodes are visible", indicators.isEmpty())
    }

    @Test
    fun offScreen_outsideContactProducesDirectionalIndicator() {
        val conversations = createSampleConversations(numDirect = 8, numGroups = 4)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        // Restrict viewport tightly so outer nodes are outside
        val viewportW = 400f
        val viewportH = 600f
        val canvasCenter = Offset(200f, 300f)
        val viewportBounds = getViewportWorldBounds(viewportW, viewportH, canvasCenter, Offset.Zero, 1.0f)

        val offScreen = findOffScreenConversations(layout, viewportBounds)
        assertTrue("Restricted viewport must detect off-screen conversations", offScreen.isNotEmpty())

        val indicators = calculateOffScreenIndicators(
            offScreenList = offScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = Offset.Zero,
            zoomScale = 1.0f,
            density = density
        )

        assertTrue("Directional indicators must be generated for off-screen conversations", indicators.isNotEmpty())

        // Every indicator must be clamped inside safe margins
        val marginH = with(density) { 26.dp.toPx() }
        val marginV = with(density) { 20.dp.toPx() }
        for (indicator in indicators) {
            assertTrue("Indicator X (${indicator.screenPosition.x}) must be >= margin ($marginH)",
                indicator.screenPosition.x >= marginH - 1f)
            assertTrue("Indicator X (${indicator.screenPosition.x}) must be <= viewport - margin (${viewportW - marginH})",
                indicator.screenPosition.x <= viewportW - marginH + 1f)
            assertTrue("Indicator Y (${indicator.screenPosition.y}) must be >= margin ($marginV)",
                indicator.screenPosition.y >= marginV - 1f)
            assertTrue("Indicator Y (${indicator.screenPosition.y}) must be <= viewport - margin (${viewportH - marginV})",
                indicator.screenPosition.y <= viewportH - marginV + 1f)
        }
    }

    @Test
    fun offScreen_groupsUseEntireClusterBounds() {
        val conversations = createSampleConversations(numDirect = 1, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val group = layout.groupNodes.first()
        val groupBounds = group.bounds

        // Viewport that covers only the nexus point but excludes the satellite bounds
        // A box barely containing nexusOffset
        val narrowViewport = BoundingBox(
            left = group.nexusOffset.x - 5f,
            top = group.nexusOffset.y - 5f,
            right = group.nexusOffset.x + 5f,
            bottom = group.nexusOffset.y + 5f
        )
        // Group cluster bounds is much larger than this 10x10 viewport
        assertTrue(
            "Group bounds width (${groupBounds.width}) must be wider than nexus-only viewport",
            groupBounds.width > narrowViewport.width
        )

        // When viewport is entirely outside group cluster bounds
        val outsideViewport = BoundingBox(
            left = groupBounds.right + 100f,
            top = groupBounds.bottom + 100f,
            right = groupBounds.right + 500f,
            bottom = groupBounds.bottom + 500f
        )
        val offScreen = findOffScreenConversations(layout, outsideViewport)
        assertTrue("Group cluster outside viewport must be detected as off-screen",
            offScreen.any { it.isGroup && it.conversation.id == group.conversation.id })
    }

    @Test
    fun offScreen_multipleConversationsInSameDirectionMergeWithCount() {
        val sampleConvs = (1..3).map { i ->
            Conversation(
                id = "conv_east_$i",
                title = "East Contact $i",
                isGroup = (i == 1),
                createdAt = 1000L,
                otherMember = User("user_e$i", "e$i", "East $i", isLocalUser = false),
                unreadCount = if (i == 2) 2 else 0
            )
        }
        val layout = calculateConstellationLayout(sampleConvs, "user_saad", density)

        // Viewport that places all three off-screen to the East (pan shifted far West)
        val viewportW = 500f
        val viewportH = 800f
        val canvasCenter = Offset(250f, 400f)
        val panOffset = Offset(-1200f, 0f) // canvas shifted far left, making nodes off-screen to the right
        val viewportBounds = getViewportWorldBounds(viewportW, viewportH, canvasCenter, panOffset, 1.0f)

        val offScreen = findOffScreenConversations(layout, viewportBounds)
        assertEquals("All 3 conversations must be detected off-screen", 3, offScreen.size)

        val indicators = calculateOffScreenIndicators(
            offScreenList = offScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = 1.0f,
            density = density
        )

        // Should merge into 1 or 2 indicators, demonstrating clustering
        assertTrue("Multiple off-screen items in same direction must be clustered into fewer indicators",
            indicators.size <= 2)

        val totalCountAcrossIndicators = indicators.sumOf { it.count }
        assertEquals("Total count across merged indicators must equal off-screen items count", 3, totalCountAcrossIndicators)

        val groupIndicator = indicators.firstOrNull { it.hasGroup }
        assertTrue("Indicator must communicate group presence", groupIndicator != null && groupIndicator.hasGroup)

        val unreadIndicator = indicators.firstOrNull { it.hasUnread }
        assertTrue("Indicator must reflect unread state from its items", unreadIndicator != null && unreadIndicator.hasUnread)
    }

    @Test
    fun offScreen_returningToViewportRemovesIndicator() {
        val conversations = createSampleConversations(numDirect = 2, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        val viewportW = 600f
        val viewportH = 800f
        val canvasCenter = Offset(300f, 400f)

        // 1. Zoomed in tightly: some nodes are off-screen
        val tightZoom = 2.0f
        val tightViewport = getViewportWorldBounds(viewportW, viewportH, canvasCenter, Offset.Zero, tightZoom)
        val tightOffScreen = findOffScreenConversations(layout, tightViewport)
        assertTrue("Tight zoom must push some nodes off-screen", tightOffScreen.isNotEmpty())

        val tightIndicators = calculateOffScreenIndicators(
            offScreenList = tightOffScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = Offset.Zero,
            zoomScale = tightZoom,
            density = density
        )
        assertTrue("Tight zoom must produce indicators", tightIndicators.isNotEmpty())

        // 2. Zoomed out to min zoom: all nodes return to viewport!
        val wideZoom = CONSTELLATION_MIN_ZOOM
        val wideViewport = getViewportWorldBounds(viewportW, viewportH, canvasCenter, Offset.Zero, wideZoom)
        val wideOffScreen = findOffScreenConversations(layout, wideViewport)
        assertTrue("Wide zoom (0.55x) brings nodes back into viewport", wideOffScreen.isEmpty())

        val wideIndicators = calculateOffScreenIndicators(
            offScreenList = wideOffScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = Offset.Zero,
            zoomScale = wideZoom,
            density = density
        )
        assertTrue("Indicators must disappear when conversations return to viewport", wideIndicators.isEmpty())
    }

    @Test
    fun visibility_farFromYouButVisibleProducesNoIndicator() {
        val conversations = createSampleConversations(numDirect = 3, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        // Find a node that is far from YOU (YOU is at (0, 0))
        val farNode = layout.directNodes.maxByOrNull { hypot(it.offset.x, it.offset.y) }!!
        assertTrue("Far node must be at substantial distance from YOU", hypot(farNode.offset.x, farNode.offset.y) > 100f)

        val viewportW = 800f
        val viewportH = 1200f
        val canvasCenter = Offset(viewportW / 2f, viewportH / 2f)

        // Pan directly to farNode so its screen position is at canvas center:
        // worldToScreen(farNode.offset, canvasCenter, panOffset, 1.0f) = canvasCenter + panOffset + farNode.offset
        // Setting panOffset = -farNode.offset centers farNode on screen!
        val panOffset = -farNode.offset
        val zoomScale = 1.0f

        val offScreen = findOffScreenConversations(
            layout = layout,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = zoomScale,
            density = density
        )

        // The farNode is right in the center of the viewport, so it MUST NOT be detected as off-screen!
        assertFalse(
            "Node far from YOU but inside visible viewport must NOT be marked off-screen",
            offScreen.any { it.conversation.id == farNode.conversation.id }
        )

        val indicators = calculateOffScreenIndicators(
            offScreenList = offScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = zoomScale,
            density = density
        )

        assertFalse(
            "Visible node far from YOU must NOT produce an off-screen indicator",
            indicators.any { ind -> ind.items.any { it.conversation.id == farNode.conversation.id } }
        )
    }

    @Test
    fun visibility_closeToYouButOutsideViewportProducesIndicator() {
        val conversations = createSampleConversations(numDirect = 3, numGroups = 1)
        val layout = calculateConstellationLayout(conversations, "user_saad", density)

        // Find a node close to YOU
        val closeNode = layout.directNodes.minByOrNull { hypot(it.offset.x, it.offset.y) }!!

        val viewportW = 800f
        val viewportH = 1200f
        val canvasCenter = Offset(viewportW / 2f, viewportH / 2f)

        // Pan far away so both YOU and closeNode are pushed far off-screen
        val panOffset = Offset(-3000f, -3000f)
        val zoomScale = 1.0f

        val offScreen = findOffScreenConversations(
            layout = layout,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = zoomScale,
            density = density
        )

        assertTrue(
            "Node close to YOU but pushed outside viewport by pan MUST be marked off-screen",
            offScreen.any { it.conversation.id == closeNode.conversation.id }
        )

        val indicators = calculateOffScreenIndicators(
            offScreenList = offScreen,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = zoomScale,
            density = density
        )

        assertTrue(
            "Node outside viewport must produce an off-screen indicator",
            indicators.any { ind -> ind.items.any { it.conversation.id == closeNode.conversation.id } }
        )
    }

    @Test
    fun indicatorDirection_relativeToViewportCenter() {
        val viewportW = 1000f
        val viewportH = 1000f
        val canvasCenter = Offset(500f, 500f)
        val panOffset = Offset.Zero
        val zoomScale = 1.0f

        fun testDirection(itemWorldPos: Offset, expectedAngleRad: Double, tolerance: Double = 0.15) {
            val conv = Conversation(
                id = "conv_dir_${itemWorldPos.x.toInt()}_${itemWorldPos.y.toInt()}",
                title = "Direction Test",
                isGroup = false,
                createdAt = 1000L
            )
            val offScreenItem = OffScreenConversation(
                conversation = conv,
                worldOffset = itemWorldPos,
                isGroup = false,
                bounds = BoundingBox(itemWorldPos.x - 20f, itemWorldPos.y - 20f, itemWorldPos.x + 20f, itemWorldPos.y + 20f)
            )

            val indicators = calculateOffScreenIndicators(
                offScreenList = listOf(offScreenItem),
                viewportWidth = viewportW,
                viewportHeight = viewportH,
                canvasCenter = canvasCenter,
                panOffset = panOffset,
                zoomScale = zoomScale,
                density = density
            )

            assertEquals(1, indicators.size)
            val indicator = indicators.first()
            val diff = kotlin.math.abs(indicator.angleRad - expectedAngleRad)
            assertTrue(
                "Indicator angle (${indicator.angleRad}) should match expected ($expectedAngleRad)",
                diff <= tolerance || kotlin.math.abs(diff - 2 * kotlin.math.PI) <= tolerance
            )
        }

        // 1. East / Right: worldOffset = (1500, 0) -> screen = (2000, 500) -> angle = 0 rad
        testDirection(Offset(1500f, 0f), 0.0)

        // 2. West / Left: worldOffset = (-1500, 0) -> screen = (-1000, 500) -> angle = PI rad (or -PI)
        testDirection(Offset(-1500f, 0f), kotlin.math.PI)

        // 3. South / Down: worldOffset = (0, 1500) -> screen = (500, 2000) -> angle = PI/2 rad (~1.57)
        testDirection(Offset(0f, 1500f), kotlin.math.PI / 2.0)

        // 4. North / Up: worldOffset = (0, -1500) -> screen = (500, -1000) -> angle = -PI/2 rad (~-1.57)
        testDirection(Offset(0f, -1500f), -kotlin.math.PI / 2.0)
    }

    @Test
    fun visibility_groupClusterBoundsUsedNotNexusOnly() {
        // Group with satellites extending outwards
        val members = listOf(
            User("m1", "alice", "Alice", isLocalUser = false),
            User("m2", "bob", "Bob", isLocalUser = false),
            User("m3", "charlie", "Charlie", isLocalUser = false)
        )
        val conv = Conversation(
            id = "group_sat_test",
            title = "Alpha Cluster",
            isGroup = true,
            createdAt = 1000L,
            members = members
        )
        val layout = calculateConstellationLayout(listOf(conv), "user_saad", density)
        val group = layout.groupNodes.first()
        assertTrue("Group must have satellites", group.satellites.isNotEmpty())

        val sat = group.satellites.first()

        val viewportW = 400f
        val viewportH = 400f
        val canvasCenter = Offset(200f, 200f)

        // Pan so that sat is at screen (200, 200) inside viewport:
        // satScreenPos = canvasCenter + panOffset + sat.offset = (200, 200) => panOffset = -sat.offset
        val panOffset = -sat.offset

        val offScreen = findOffScreenConversations(
            layout = layout,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = panOffset,
            zoomScale = 1.0f,
            density = density
        )

        // Group satellite is visible inside viewport, so group MUST NOT be marked off-screen!
        assertFalse(
            "Group with satellite visible inside viewport must NOT be marked off-screen",
            offScreen.any { it.conversation.id == conv.id }
        )

        // When entire cluster is panned far outside viewport
        val farPan = Offset(-5000f, -5000f)
        val offScreenFar = findOffScreenConversations(
            layout = layout,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            canvasCenter = canvasCenter,
            panOffset = farPan,
            zoomScale = 1.0f,
            density = density
        )

        assertTrue(
            "Group entirely outside viewport must be marked off-screen",
            offScreenFar.any { it.conversation.id == conv.id }
        )
    }
}
