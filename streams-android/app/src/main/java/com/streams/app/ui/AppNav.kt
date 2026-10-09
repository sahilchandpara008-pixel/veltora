package com.streams.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streams.app.AppState
import com.streams.app.data.Attribution
import com.streams.app.ui.admin.AdminLoginScreen
import com.streams.app.ui.admin.AdminResetPasswordScreen
import com.streams.app.ui.admin.AdminScreen
import com.streams.app.ui.admin.ContentEditorScreen
import com.streams.app.ui.components.Loading
import com.streams.app.ui.screens.AuthScreen
import com.streams.app.ui.screens.SetPasswordScreen
import com.streams.app.ui.screens.ChannelDetailScreen
import com.streams.app.ui.screens.ChannelsScreen
import com.streams.app.ui.screens.CloudScreen
import com.streams.app.ui.screens.HomeScreen
import com.streams.app.ui.screens.PlayerScreen
import com.streams.app.ui.screens.ProfileScreen
import com.streams.app.ui.screens.ShowsScreen
import com.streams.app.ui.screens.TitleDetailScreen
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Outline
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.TextMuted
import io.github.jan.supabase.auth.status.SessionStatus

private data class Tab(val route: String, val label: String, val icon: ImageVector, val iconOff: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    Tab("channels", "Channels", Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    Tab("shows", "Shows", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Tab("cloud", "Cloud", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    Tab("profile", "Profile", Icons.Filled.Person, Icons.Outlined.Person),
)

object Routes {
    fun title(id: String) = "title/$id"
    fun channel(id: String) = "channel/$id"
    /** mode = full | trailer | preview */
    fun player(titleId: String, mode: String, episodeId: String? = null) =
        "player/$titleId?mode=$mode" + (episodeId?.let { "&ep=$it" } ?: "")
    fun edit(id: String?) = if (id == null) "admin-edit" else "admin-edit?id=$id"
}

@Composable
fun AppNav() {
    val session by AppState.session.collectAsStateWithLifecycle()
    val pendingRoute by AppState.pendingRoute.collectAsStateWithLifecycle()

    if (session is SessionStatus.Initializing) {
        Loading()
        return
    }
    val signedIn = session is SessionStatus.Authenticated

    // Re-check subscription whenever the signed-in user changes.
    val userId = (session as? SessionStatus.Authenticated)?.session?.user?.id
    LaunchedEffect(userId) {
        AppState.refreshAccess()
        // Link this install's ad attribution to the signed-in account (server decides; first touch
        // is never overwritten). If that unlocks campaign content, reload the screens.
        if (Attribution.onSessionChanged(userId)) AppState.accessVersion.value++
    }
    // ...and every time the app comes back to the foreground (e.g. after paying, or once
    // an admin approved the payment), so Premium unlocks without restarting the app.
    LifecycleResumeEffect(userId) {
        val job = lifecycleScope.launch { AppState.refreshAccess(onlyIfChanged = true) }
        onPauseOrDispose { job.cancel() }
    }

    val nav = rememberNavController()

    // Leave the sign-in screen (opened from Profile) as soon as a session appears.
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    LaunchedEffect(signedIn, route) {
        if (signedIn && route == "auth") nav.popBackStack()
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigate(it); AppState.pendingRoute.value = null }
    }

    val showBar = tabs.any { it.route == route }
    Scaffold(
        containerColor = Bg,
        bottomBar = { if (showBar) BottomBar(nav, route) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",   // everyone can browse without signing in
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("auth") { AuthScreen(onClose = { nav.popBackStack() }) }
            composable("set-password") { SetPasswordScreen(onDone = { nav.popBackStack() }) }
            composable("home") { HomeScreen(nav) }
            composable("channels") { ChannelsScreen(nav) }
            composable("shows") { ShowsScreen(nav) }
            composable("cloud") { CloudScreen(nav) }
            composable("profile") { ProfileScreen(nav) }
            composable("channel/{id}") { ChannelDetailScreen(nav, it.arguments?.getString("id")!!) }
            composable("title/{id}") { TitleDetailScreen(nav, it.arguments?.getString("id")!!) }
            composable(
                "player/{id}?mode={mode}&ep={ep}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType; defaultValue = "full" },
                    navArgument("ep") { type = NavType.StringType; nullable = true },
                ),
            ) {
                // Watching needs an account. Once sign-in finishes, the video starts here.
                // Exception: installs from Meta ads may watch trailers before signing in.
                val mode = it.arguments?.getString("mode") ?: "full"
                if (!signedIn && !(mode == "trailer" && Attribution.isMetaInstall)) {
                    AuthScreen(onClose = { nav.popBackStack() }, reason = "Sign in to start watching.")
                    return@composable
                }
                PlayerScreen(
                    nav,
                    titleId = it.arguments?.getString("id")!!,
                    requestedMode = it.arguments?.getString("mode") ?: "full",
                    episodeId = it.arguments?.getString("ep"),
                )
            }

            // Admin — reachable only by the hidden gesture on Profile or the e-mail link.
            composable("admin-login") { AdminLoginScreen(nav) }
            composable("admin-reset") { AdminResetPasswordScreen(nav) }
            composable("admin") { AdminScreen(nav) }
            composable(
                "admin-edit?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
            ) { ContentEditorScreen(nav, it.arguments?.getString("id")) }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    Column {
    HorizontalDivider(color = Outline.copy(alpha = 0.6f), thickness = 0.5.dp)
    NavigationBar(containerColor = Color(0xFF0E0E11), tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) tab.icon else tab.iconOff, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    indicatorColor = Red.copy(alpha = 0.22f),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                ),
            )
        }
    }
    }
}
