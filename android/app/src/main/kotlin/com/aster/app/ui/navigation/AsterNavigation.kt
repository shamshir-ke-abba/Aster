package com.aster.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aster.app.ui.ChatScreen
import com.aster.app.ui.ConnectionsScreen
import com.aster.app.ui.HomeScreen
import com.aster.app.ui.OnboardingScreen
import com.aster.app.ui.ProfileScreen
import com.aster.app.ui.SettingsScreen
import com.aster.app.ui.components.AsterNavDestination
import com.aster.app.ui.motion.AsterNavigationMotion
import com.aster.app.ui.motion.LocalReducedMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AsterRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CONNECTIONS = "connections"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{conversationId}"

    fun chatRoute(conversationId: String): String = "chat/$conversationId"
}

@Composable
fun AsterNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    isProvisioned: Boolean = true,
    pendingConversationId: StateFlow<String?> = MutableStateFlow(null)
) {
    val targetConversationId by pendingConversationId.collectAsState()

    // Contextual top-level navigation with deterministic state preservation, single-top semantics,
    // and protection against race conditions / corrupt back stacks during rapid transitions.
    fun navigateToTopLevel(destination: AsterNavDestination) {
        val targetRoute = when (destination) {
            AsterNavDestination.CHATS -> AsterRoutes.HOME
            AsterNavDestination.CONNECTIONS -> AsterRoutes.CONNECTIONS
            AsterNavDestination.PROFILE -> AsterRoutes.PROFILE
            AsterNavDestination.SETTINGS -> AsterRoutes.SETTINGS
        }
        val currentRoute = navController.currentBackStackEntry?.destination?.route
            ?: navController.currentDestination?.route

        if (currentRoute == targetRoute) {
            return
        }

        if (targetRoute == AsterRoutes.HOME) {
            // Popping back to HOME is deterministic and avoids the Navigation Compose 2.8.x bug
            // where navigate(HOME) with restoreState=true drops navigation when HOME is already root.
            val popped = navController.popBackStack(AsterRoutes.HOME, inclusive = false, saveState = true)
            if (!popped) {
                navController.navigate(AsterRoutes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        } else {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(targetConversationId) {
        targetConversationId?.let { convId ->
            navController.navigate(AsterRoutes.chatRoute(convId)) {
                // Return to Aster root (HOME) on back navigation from notification
                popUpTo(AsterRoutes.HOME) {
                    saveState = false
                }
                launchSingleTop = true
            }
            if (pendingConversationId is MutableStateFlow) {
                pendingConversationId.value = null
            }
        }
    }

    val isReduced = LocalReducedMotion.current

    NavHost(
        navController = navController,
        startDestination = if (isProvisioned) AsterRoutes.HOME else AsterRoutes.ONBOARDING,
        modifier = modifier,
        enterTransition = { AsterNavigationMotion.enterTransition(isReduced) },
        exitTransition = { AsterNavigationMotion.exitTransition(isReduced) },
        popEnterTransition = { AsterNavigationMotion.popEnterTransition(isReduced) },
        popExitTransition = { AsterNavigationMotion.popExitTransition(isReduced) }
    ) {
        composable(AsterRoutes.ONBOARDING) {
            OnboardingScreen(
                onAuthenticationSuccess = { _ ->
                    navController.navigate(AsterRoutes.HOME) {
                        popUpTo(AsterRoutes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AsterRoutes.HOME) {
            HomeScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(AsterRoutes.chatRoute(conversationId))
                },
                onNavigate = ::navigateToTopLevel,
                onNavigateToSettings = {
                    navController.navigate(AsterRoutes.SETTINGS)
                }
            )
        }

        composable(AsterRoutes.CONNECTIONS) {
            ConnectionsScreen(
                onNavigate = ::navigateToTopLevel,
                onNavigateToSettings = {
                    navController.navigate(AsterRoutes.SETTINGS)
                },
                onStartChat = { conversationId ->
                    navController.navigate(AsterRoutes.chatRoute(conversationId))
                }
            )
        }

        composable(
            route = AsterRoutes.PROFILE,
            enterTransition = { AsterNavigationMotion.profileEnterTransition(isReduced) },
            popExitTransition = { AsterNavigationMotion.profilePopExitTransition(isReduced) }
        ) {
            ProfileScreen(
                onNavigate = ::navigateToTopLevel,
                onNavigateToSettings = {
                    navController.navigate(AsterRoutes.SETTINGS)
                }
            )
        }

        composable(AsterRoutes.SETTINGS) {
            SettingsScreen(
                onNavigate = ::navigateToTopLevel,
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(AsterRoutes.PROFILE) {
                            launchSingleTop = true
                        }
                    }
                },
                onLogoutSuccess = {
                    navController.navigate(AsterRoutes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = AsterRoutes.CHAT,
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                }
            ),
            enterTransition = { AsterNavigationMotion.constellationToChatEnter(isReduced) },
            popExitTransition = { AsterNavigationMotion.chatToConstellationPopExit(isReduced) }
        ) {
            ChatScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(AsterRoutes.HOME) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
    }
}
