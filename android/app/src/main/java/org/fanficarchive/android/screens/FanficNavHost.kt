package org.fanficarchive.android.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerInfo
import org.fanficarchive.android.data.ServerProfile
import org.fanficarchive.android.screens.importing.ImportScreen
import org.fanficarchive.android.screens.library.LibraryScreen
import org.fanficarchive.android.screens.login.LoginScreen
import org.fanficarchive.android.screens.reader.ReaderScreen
import org.fanficarchive.android.screens.settings.SettingsScreen
import org.fanficarchive.android.screens.setup.SetupScreen
import org.fanficarchive.android.screens.work.WorkDetailScreen

private object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    const val WORK = "work/{workId}"
    const val READER = "work/{workId}/chapter/{number}"

    fun work(id: Int) = "work/$id"
    fun reader(workId: Int, number: Int) = "work/$workId/chapter/$number"
}

@Composable
fun FanficNavHost(repository: ArchiveRepository) {
    var profile by remember { mutableStateOf<ServerProfile?>(null) }
    val liveProfile by repository.profile.collectAsState(initial = ServerProfile())
    val navController = rememberNavController()
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }

    LaunchedEffect(Unit) {
        profile = repository.profile.first()
    }
    LaunchedEffect(liveProfile) {
        if (profile != null) profile = liveProfile
    }

    val current = profile ?: return
    val startDestination = when {
        current.baseUrl.isBlank() -> Routes.SETUP
        !current.loggedIn -> Routes.LOGIN
        else -> Routes.LIBRARY
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            SetupScreen(
                repository = repository,
                initialUrl = current.baseUrl,
                initialAllowHttp = current.allowHttp,
                onConnected = { info ->
                    serverInfo = info
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                repository = repository,
                siteName = current.siteName,
                serverInfo = serverInfo,
                onLoggedIn = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onChangeServer = {
                    navController.navigate(Routes.SETUP)
                },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                repository = repository,
                siteName = current.siteName,
                onOpenWork = { navController.navigate(Routes.work(it)) },
                onImport = { navController.navigate(Routes.IMPORT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(repository = repository, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                repository = repository,
                profile = current,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onDisconnected = {
                    serverInfo = null
                    navController.navigate(Routes.SETUP) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.WORK,
            arguments = listOf(navArgument("workId") { type = NavType.IntType }),
        ) { entry ->
            val workId = entry.arguments!!.getInt("workId")
            WorkDetailScreen(
                repository = repository,
                workId = workId,
                baseUrl = current.baseUrl,
                onBack = { navController.popBackStack() },
                onOpenChapter = { number ->
                    navController.navigate(Routes.reader(workId, number))
                },
            )
        }
        composable(
            Routes.READER,
            arguments = listOf(
                navArgument("workId") { type = NavType.IntType },
                navArgument("number") { type = NavType.IntType },
            ),
        ) { entry ->
            val workId = entry.arguments!!.getInt("workId")
            val number = entry.arguments!!.getInt("number")
            ReaderScreen(
                repository = repository,
                workId = workId,
                chapterNumber = number,
                baseUrl = current.baseUrl,
                onBack = { navController.popBackStack() },
                onOpenChapter = { next ->
                    navController.navigate(Routes.reader(workId, next)) {
                        popUpTo(Routes.work(workId))
                    }
                },
            )
        }
    }
}
