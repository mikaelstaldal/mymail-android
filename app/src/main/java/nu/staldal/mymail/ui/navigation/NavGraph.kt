package nu.staldal.mymail.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import nu.staldal.mymail.ui.screen.compose.ComposeScreen
import nu.staldal.mymail.ui.screen.folder.FolderListScreen
import nu.staldal.mymail.ui.screen.folder.MessageListScreen
import nu.staldal.mymail.ui.screen.message.MessageDetailScreen
import nu.staldal.mymail.ui.screen.search.SearchScreen
import nu.staldal.mymail.ui.screen.settings.SettingsScreen
import nu.staldal.mymail.ui.screen.setup.SetupScreen

@Composable
fun NavGraph(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(
            route = "setup",
            deepLinks = listOf(navDeepLink { uriPattern = "mymail://setup" }),
        ) {
            SetupScreen(navController = navController)
        }

        composable(route = "folders") {
            FolderListScreen(navController = navController)
        }

        composable(
            route = "messages/{folderId}",
            deepLinks = listOf(navDeepLink { uriPattern = "mymail://messages/{folderId}" }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")?.toLongOrNull() ?: return@composable
            MessageListScreen(navController = navController, folderId = folderId)
        }

        composable(route = "message/{messageId}") { backStackEntry ->
            val messageId = backStackEntry.arguments?.getString("messageId")?.toLongOrNull() ?: return@composable
            MessageDetailScreen(navController = navController, messageId = messageId)
        }

        composable(
            route = "compose?replyTo={replyTo}&replyAllTo={replyAllTo}&forwardOf={forwardOf}&draftId={draftId}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("replyAllTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("forwardOf") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("draftId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val replyTo = backStackEntry.arguments?.getString("replyTo")
            val replyAllTo = backStackEntry.arguments?.getString("replyAllTo")
            val forwardOf = backStackEntry.arguments?.getString("forwardOf")
            val draftId = backStackEntry.arguments?.getString("draftId")
            ComposeScreen(
                navController = navController,
                replyTo = replyTo,
                replyAllTo = replyAllTo,
                forwardOf = forwardOf,
                draftId = draftId,
            )
        }

        composable(route = "search") {
            SearchScreen(navController = navController)
        }

        composable(route = "settings") {
            SettingsScreen(navController = navController)
        }
    }
}
