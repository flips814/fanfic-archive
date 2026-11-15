package org.fanficarchive.android.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerInfo
import org.fanficarchive.android.data.ServerProfile
import org.fanficarchive.android.screens.activity.ActivityScreen
import org.fanficarchive.android.screens.importing.ImportScreen
import org.fanficarchive.android.screens.library.LibraryScreen
import org.fanficarchive.android.screens.login.LoginScreen
import org.fanficarchive.android.screens.reader.ReaderScreen
import org.fanficarchive.android.screens.settings.SettingsScreen
import org.fanficarchive.android.screens.setup.SetupScreen
import org.fanficarchive.android.screens.users.UsersScreen
import org.fanficarchive.android.screens.work.WorkDetailScreen

private object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val ACTIVITY = "activity"
    const val USERS = "users"
    const val SETTINGS = "settings"
    const val WORK = "work/{workId}"
    const val READER = "work/{workId}/chapter/{number}"

    fun work(id: Int) = "work/$id"
    fun reader(workId: Int, number: Int) = "work/$workId/chapter/$number"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val mainTabs = listOf(
    Tab(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook),
    Tab(Routes.IMPORT, "Add works", Icons.Default.Add),
    Tab(Routes.ACTIVITY, "Activity", Icons.Default.History),
    Tab(Routes.USERS, "Users", Icons.Default.People),
)

@Composable
fun FanficNavHost(repository: ArchiveRepository) {
    var profile by remember { mutableStateOf<ServerProfile?>(null) }
    val liveProfile by repository.profile.collectAsState(initial = ServerProfile())
    val navController = rememberNavController()
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }

    LaunchedEffect(Unit) {
        // Persist cookies across process death; re-check /api/me before routing.
        runCatching { repository.restoreSession() }
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
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = current.loggedIn && currentRoute in mainTabs.map { it.route }

    // Child screens own status-bar / TopAppBar insets. Only consume the bottom nav here
    // so we don't leave a blank strip under the system status bar.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    mainTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
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
                    baseUrl = current.baseUrl,
                    serverInfo = serverInfo,
                    onLoggedIn = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onChangeServer = { navController.navigate(Routes.SETUP) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    repository = repository,
                    siteName = current.siteName,
                    onOpenWork = { navController.navigate(Routes.work(it)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(repository = repository, onBack = null)
            }
            composable(Routes.ACTIVITY) {
                ActivityScreen(
                    repository = repository,
                    onOpenWork = { navController.navigate(Routes.work(it)) },
                )
            }
            composable(Routes.USERS) {
                UsersScreen(repository = repository)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    repository = repository,
                    profile = current,
                    onBack = { navController.popBackStack() },
                    onOpenWork = { navController.navigate(Routes.work(it)) },
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
                    onOpenChapter = { number -> navController.navigate(Routes.reader(workId, number)) },
                    onDeleted = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.LIBRARY) { inclusive = true }
                        }
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
}
