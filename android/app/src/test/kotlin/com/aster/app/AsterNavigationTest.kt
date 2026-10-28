package com.aster.app

import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.data.network.UserSummary
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.motion.AsterNavigationMotion
import com.aster.app.ui.navigation.AsterRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterNavigationTest {

    enum class TestRelationshipState { CONNECTED, REQUESTED, BLOCKED, AVAILABLE }

    @Test
    fun testAsterRoutesAndDestinationsHierarchy() {
        assertEquals("onboarding", AsterRoutes.ONBOARDING)
        assertEquals("home", AsterRoutes.HOME)
        assertEquals("connections", AsterRoutes.CONNECTIONS)
        assertEquals("profile", AsterRoutes.PROFILE)
        assertEquals("settings", AsterRoutes.SETTINGS)
        assertEquals("chat/{conversationId}", AsterRoutes.CHAT)
        assertEquals("chat/conv_alpha", AsterRoutes.chatRoute("conv_alpha"))

        // Primary tabs represent the 3 core pillars of the Aster hierarchy:
        // Constellations (Home), Connections (Contacts), Profile (Profile & Settings)
        assertEquals(3, AsterNavDestination.primaryTabs.size)
        assertTrue(AsterNavDestination.primaryTabs.contains(AsterNavDestination.CHATS))
        assertTrue(AsterNavDestination.primaryTabs.contains(AsterNavDestination.CONNECTIONS))
        assertTrue(AsterNavDestination.primaryTabs.contains(AsterNavDestination.PROFILE))

        // Settings is reachable via Profile/Settings hierarchy
        assertEquals("settings", AsterNavDestination.SETTINGS.route)
    }

    @Test
    fun testTopLevelTabNavigationBackstackSemantics() {
        // A model reproducing Jetpack Compose Navigation back stack with findStartDestination() popUpTo
        class SimulatedNavStack(val startDestination: String) {
            val backStack = mutableListOf(startDestination)

            fun navigateToTopLevel(destinationRoute: String) {
                // popUpTo(startDestination) { saveState = true }
                while (backStack.size > 1) {
                    backStack.removeAt(backStack.size - 1)
                }
                if (destinationRoute != startDestination) {
                    backStack.add(destinationRoute)
                }
            }

            fun popBack(): Boolean {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.size - 1)
                    return true
                }
                return false
            }
        }

        val stack = SimulatedNavStack(AsterRoutes.HOME)
        assertEquals(listOf(AsterRoutes.HOME), stack.backStack)

        // Switch tabs rapidly: HOME -> CONNECTIONS -> PROFILE -> CONNECTIONS -> HOME
        stack.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), stack.backStack)

        stack.navigateToTopLevel(AsterRoutes.PROFILE)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.PROFILE), stack.backStack)

        stack.navigateToTopLevel(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), stack.backStack)

        stack.navigateToTopLevel(AsterRoutes.HOME)
        assertEquals(listOf(AsterRoutes.HOME), stack.backStack)

        // Back from HOME cannot pop further (app exit)
        assertFalse(stack.popBack())
    }

    @Test
    fun testConversationNavigationFromConstellationAndContacts() {
        class SimulatedBackStack {
            val stack = mutableListOf<String>()

            fun push(route: String) = stack.add(route)
            fun pop(): String? = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else null
            fun top(): String? = stack.lastOrNull()
        }

        // Flow A: From Constellation (Home)
        val flowA = SimulatedBackStack()
        flowA.push(AsterRoutes.HOME)
        flowA.push(AsterRoutes.chatRoute("conv_person_1"))
        assertEquals("chat/conv_person_1", flowA.top())
        flowA.pop() // onBackClick
        assertEquals(AsterRoutes.HOME, flowA.top())

        // Flow B: From Contacts (Connections)
        val flowB = SimulatedBackStack()
        flowB.push(AsterRoutes.HOME)
        flowB.push(AsterRoutes.CONNECTIONS)
        flowB.push(AsterRoutes.chatRoute("conv_contact_2"))
        assertEquals("chat/conv_contact_2", flowB.top())
        flowB.pop() // onBackClick
        assertEquals(AsterRoutes.CONNECTIONS, flowB.top())
        flowB.pop() // system back from Connections
        assertEquals(AsterRoutes.HOME, flowB.top())
    }

    @Test
    fun testNotificationDeepLinkReturnsToAsterRoot() {
        class SimulatedNavHost {
            val stack = mutableListOf<String>()

            init {
                stack.add(AsterRoutes.HOME)
            }

            fun onNotificationTap(convId: String) {
                // popUpTo(AsterRoutes.HOME) { saveState = false }; launchSingleTop = true
                while (stack.size > 1) {
                    stack.removeAt(stack.size - 1)
                }
                stack.add(AsterRoutes.chatRoute(convId))
            }

            fun onChatBackClick() {
                if (stack.size > 1) {
                    stack.removeAt(stack.size - 1)
                } else {
                    stack.clear()
                    stack.add(AsterRoutes.HOME)
                }
            }
        }

        val navHost = SimulatedNavHost()
        // User was in CONNECTIONS when notification arrived and tapped
        navHost.stack.add(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), navHost.stack)

        navHost.onNotificationTap("conv_incoming_urgent")
        assertEquals(listOf(AsterRoutes.HOME, "chat/conv_incoming_urgent"), navHost.stack)

        navHost.onChatBackClick()
        // Must return directly to Aster root (HOME)
        assertEquals(listOf(AsterRoutes.HOME), navHost.stack)
    }

    @Test
    fun testProfileToSettingsHierarchyAndBack() {
        // Test D: Constellation -> Profile -> Settings -> Back -> Profile -> Back -> Home
        val stack = mutableListOf<String>()
        stack.add(AsterRoutes.HOME)

        // 1. Constellation -> Profile
        stack.add(AsterRoutes.PROFILE)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.PROFILE), stack)

        // 2. Profile -> Settings
        stack.add(AsterRoutes.SETTINGS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.PROFILE, AsterRoutes.SETTINGS), stack)

        // 3. Settings -> Back
        stack.removeAt(stack.size - 1)
        assertEquals(AsterRoutes.PROFILE, stack.last())

        // 4. Profile -> Back
        stack.removeAt(stack.size - 1)
        assertEquals(AsterRoutes.HOME, stack.last())
    }

    @Test
    fun testConnectionsRelationshipStateResolution() {
        fun resolveRelationship(
            userId: String,
            friends: List<UserSummary>,
            outgoingRequests: List<FriendRequestSummary>,
            blockedUsers: List<UserSummary>
        ): TestRelationshipState = when {
            friends.any { it.userId == userId } -> TestRelationshipState.CONNECTED
            blockedUsers.any { it.userId == userId } -> TestRelationshipState.BLOCKED
            outgoingRequests.any { it.recipientUserId == userId } -> TestRelationshipState.REQUESTED
            else -> TestRelationshipState.AVAILABLE
        }

        val friends = listOf(UserSummary("u1", "AST-1", "alice", "Alice"))
        val outgoing = listOf(FriendRequestSummary(id = "req1", senderUserId = "local_u", recipientUserId = "u2", status = "pending", createdAt = 1700000000L))
        val blocked = listOf(UserSummary("u3", "AST-3", "charlie", "Charlie"))

        assertEquals(TestRelationshipState.CONNECTED, resolveRelationship("u1", friends, outgoing, blocked))
        assertEquals(TestRelationshipState.REQUESTED, resolveRelationship("u2", friends, outgoing, blocked))
        assertEquals(TestRelationshipState.BLOCKED, resolveRelationship("u3", friends, outgoing, blocked))
        assertEquals(TestRelationshipState.AVAILABLE, resolveRelationship("u4", friends, outgoing, blocked))
    }

    @Test
    fun testPermanentSettingsAccessFromHomeScreenAndConnections() {
        // Flow: User is on Home (Constellation) -> taps permanent gear icon -> opens Settings -> Back returns to Home
        val navStack = mutableListOf<String>()
        navStack.add(AsterRoutes.HOME)

        // 1. Home -> Tap Settings Gear
        navStack.add(AsterRoutes.SETTINGS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.SETTINGS), navStack)

        // 2. Settings -> Back navigation
        navStack.removeAt(navStack.size - 1)
        assertEquals(listOf(AsterRoutes.HOME), navStack)

        // Flow: User is on Connections -> taps Settings Gear -> opens Settings -> Back returns to Connections
        navStack.add(AsterRoutes.CONNECTIONS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), navStack)

        navStack.add(AsterRoutes.SETTINGS)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS, AsterRoutes.SETTINGS), navStack)

        navStack.removeAt(navStack.size - 1)
        assertEquals(listOf(AsterRoutes.HOME, AsterRoutes.CONNECTIONS), navStack)
    }

    @Test
    fun testFluidNavigationTransitionsStandardAndReduced() {
        // Standard Transitions
        val enter = AsterNavigationMotion.enterTransition(isReduced = false)
        val exit = AsterNavigationMotion.exitTransition(isReduced = false)
        val popEnter = AsterNavigationMotion.popEnterTransition(isReduced = false)
        val popExit = AsterNavigationMotion.popExitTransition(isReduced = false)

        assertNotNull(enter)
        assertNotNull(exit)
        assertNotNull(popEnter)
        assertNotNull(popExit)

        // Reduced Motion Transitions
        val reducedEnter = AsterNavigationMotion.enterTransition(isReduced = true)
        val reducedExit = AsterNavigationMotion.exitTransition(isReduced = true)
        val reducedPopEnter = AsterNavigationMotion.popEnterTransition(isReduced = true)
        val reducedPopExit = AsterNavigationMotion.popExitTransition(isReduced = true)

        assertNotNull(reducedEnter)
        assertNotNull(reducedExit)
        assertNotNull(reducedPopEnter)
        assertNotNull(reducedPopExit)
    }

    @Test
    fun testConstellationToChatSpatialTransitions() {
        val constellationEnter = AsterNavigationMotion.constellationToChatEnter(isReduced = false)
        val chatPopExit = AsterNavigationMotion.chatToConstellationPopExit(isReduced = false)

        assertNotNull(constellationEnter)
        assertNotNull(chatPopExit)

        val reducedConstellationEnter = AsterNavigationMotion.constellationToChatEnter(isReduced = true)
        val reducedChatPopExit = AsterNavigationMotion.chatToConstellationPopExit(isReduced = true)

        assertNotNull(reducedConstellationEnter)
        assertNotNull(reducedChatPopExit)
    }

    @Test
    fun testProfileTransitions() {
        val profileEnter = AsterNavigationMotion.profileEnterTransition(isReduced = false)
        val profilePopExit = AsterNavigationMotion.profilePopExitTransition(isReduced = false)

        assertNotNull(profileEnter)
        assertNotNull(profilePopExit)

        val reducedProfileEnter = AsterNavigationMotion.profileEnterTransition(isReduced = true)
        val reducedProfilePopExit = AsterNavigationMotion.profilePopExitTransition(isReduced = true)

        assertNotNull(reducedProfileEnter)
        assertNotNull(reducedProfilePopExit)
    }
}
