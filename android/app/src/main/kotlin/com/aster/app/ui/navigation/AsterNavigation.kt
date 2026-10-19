package com.aster.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    pendingConversationId: StateFlow<String?> = MutableStateFlow(null),
    pendingInviteToken: StateFlow<String?>? = null,
    pendingInviteError: StateFlow<String?>? = null
) {
    val targetConversationId by pendingConversationId.collectAsState()

    LaunchedEffect(targetConversationId) {
        targetConversationId?.let { convId ->
            navController.navigate(AsterRoutes.chatRoute(convId)) {
                launchSingleTop = true
            }
            if (pendingConversationId is MutableStateFlow) {
                pendingConversationId.value = null
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isProvisioned) AsterRoutes.HOME else AsterRoutes.ONBOARDING,
        modifier = modifier
    ) {
        composable(AsterRoutes.ONBOARDING) {
            OnboardingScreen(
                onAuthenticationSuccess = { _ ->
                    navController.navigate(AsterRoutes.HOME) {
                        popUpTo(AsterRoutes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                pendingInviteToken = pendingInviteToken,
                pendingInviteError = pendingInviteError
            )
        }

        composable(AsterRoutes.HOME) {
            HomeScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(AsterRoutes.chatRoute(conversationId))
                },
                onNavigate = { dest ->
                    when (dest) {
                        AsterNavDestination.CHATS -> {}
                        AsterNavDestination.CONNECTIONS -> navController.navigate(AsterRoutes.CONNECTIONS) { launchSingleTop = true }
                        AsterNavDestination.PROFILE -> navController.navigate(AsterRoutes.PROFILE) { launchSingleTop = true }
                        AsterNavDestination.SETTINGS -> navController.navigate(AsterRoutes.SETTINGS) { launchSingleTop = true }
                    }
                }
            )
        }

        composable(AsterRoutes.CONNECTIONS) {
            ConnectionsScreen(
                onNavigate = { dest ->
                    when (dest) {
                        AsterNavDestination.CHATS -> navController.navigate(AsterRoutes.HOME) { launchSingleTop = true }
                        AsterNavDestination.CONNECTIONS -> {}
                        AsterNavDestination.PROFILE -> navController.navigate(AsterRoutes.PROFILE) { launchSingleTop = true }
                        AsterNavDestination.SETTINGS -> navController.navigate(AsterRoutes.SETTINGS) { launchSingleTop = true }
                    }
                },
                onStartChat = { _ ->
                    navController.navigate(AsterRoutes.HOME) { launchSingleTop = true }
                }
            )
        }

        composable(AsterRoutes.PROFILE) {
            ProfileScreen(
                onNavigate = { dest ->
                    when (dest) {
                        AsterNavDestination.CHATS -> navController.navigate(AsterRoutes.HOME) { launchSingleTop = true }
                        AsterNavDestination.CONNECTIONS -> navController.navigate(AsterRoutes.CONNECTIONS) { launchSingleTop = true }
                        AsterNavDestination.PROFILE -> {}
                        AsterNavDestination.SETTINGS -> navController.navigate(AsterRoutes.SETTINGS) { launchSingleTop = true }
                    }
                }
            )
        }

        composable(AsterRoutes.SETTINGS) {
            SettingsScreen(
                onNavigate = { dest ->
                    when (dest) {
                        AsterNavDestination.CHATS -> navController.navigate(AsterRoutes.HOME) { launchSingleTop = true }
                        AsterNavDestination.CONNECTIONS -> navController.navigate(AsterRoutes.CONNECTIONS) { launchSingleTop = true }
                        AsterNavDestination.PROFILE -> navController.navigate(AsterRoutes.PROFILE) { launchSingleTop = true }
                        AsterNavDestination.SETTINGS -> {}
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
            )
        ) {
            ChatScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
