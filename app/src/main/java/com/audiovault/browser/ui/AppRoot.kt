package com.audiovault.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.audiovault.browser.MainActivity
import com.audiovault.browser.ui.browser.BrowserScreen
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.home.HomeScreen
import com.audiovault.browser.ui.library.LibraryScreen
import com.audiovault.browser.ui.misc.BookmarksScreen
import com.audiovault.browser.ui.misc.CacheScreen
import com.audiovault.browser.ui.misc.DiagnosticsScreen
import com.audiovault.browser.ui.misc.DownloadsScreen
import com.audiovault.browser.ui.misc.HistoryScreen
import com.audiovault.browser.ui.misc.TabsScreen
import com.audiovault.browser.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.collectLatest

object Routes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val TABS = "tabs"
    const val HISTORY = "history"
    const val BOOKMARKS = "bookmarks"
    const val DOWNLOADS = "downloads"
    const val CACHE = "cache"
    const val DIAGNOSTICS = "diagnostics"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    NavItem(Routes.HOME, "Inicio", Icons.Default.Home),
    NavItem(Routes.BROWSER, "Navegador", Icons.Default.Public),
    NavItem(Routes.LIBRARY, "Mi Biblioteca", Icons.Default.LibraryMusic),
    NavItem(Routes.SETTINGS, "Ajustes", Icons.Default.Settings),
)

fun NavHostController.go(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot(activity: MainActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val ctx = activity
    val container = ctx.container
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val customView by activity.customView
    val ask by activity.webPermissionAsk
    val openBrowser by activity.openBrowserRequest

    LaunchedEffect(openBrowser) { if (openBrowser > 0) nav.go(Routes.BROWSER) }
    LaunchedEffect(Unit) {
        container.capture.messages.collectLatest { snackbar.showSnackbar(it) }
    }
    val showMessage: (String) -> Unit = { msg -> activity.showMessage(msg) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (route in bottomItems.map { it.route }) {
                    NavigationBar {
                        bottomItems.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = { nav.go(item.route) },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.label, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                composable(Routes.HOME) { HomeScreen(nav, showMessage) }
                composable(Routes.BROWSER) { BrowserScreen(nav, showMessage) }
                composable(Routes.LIBRARY) { LibraryScreen(showMessage) }
                composable(Routes.SETTINGS) { SettingsScreen(nav, showMessage) }
                composable(Routes.TABS) { TabsScreen(nav) }
                composable(Routes.HISTORY) { HistoryScreen(nav) }
                composable(Routes.BOOKMARKS) { BookmarksScreen(nav) }
                composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
                composable(Routes.CACHE) { CacheScreen(nav, showMessage) }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(nav, showMessage) }
            }
        }

        customView?.let { v ->
            BackHandler { activity.exitFullscreen() }
            AndroidView(
                factory = { v.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { a.onDecision(false) },
            title = { Text("Permiso solicitado por la página") },
            text = { Text("${a.origin} quiere usar: ${a.resources.joinToString(", ")}.") },
            confirmButton = { TextButton(onClick = { a.onDecision(true) }) { Text("Permitir") } },
            dismissButton = { TextButton(onClick = { a.onDecision(false) }) { Text("Bloquear") } },
        )
    }
}
