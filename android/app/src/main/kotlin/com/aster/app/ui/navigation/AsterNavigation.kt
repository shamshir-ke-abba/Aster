package com.aster.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aster.app.ui.ChatScreen
import com.aster.app.ui.HomeScreen

object AsterRoutes {
    const val HOME = "home"
    const val CHAT = "chat/{conversationId}"

    fun chatRoute(conversationId: String): String = "chat/$conversationId"
}

@Composable
fun AsterNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = AsterRoutes.HOME,
        modifier = modifier
    ) {
        composable(AsterRoutes.HOME) {
            HomeScreen(
                onConversationClick = { conversationId ->
                    navController.navigate(AsterRoutes.chatRoute(conversationId))
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
