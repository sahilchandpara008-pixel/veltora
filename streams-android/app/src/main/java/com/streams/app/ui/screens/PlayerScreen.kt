package com.streams.app.ui.screens

import android.app.Activity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Repo
import com.streams.app.data.supabase
import io.github.jan.supabase.auth.auth
import com.streams.app.rememberLoad
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.theme.Red
import kotlinx.coroutines.delay

private const val PREVIEW_MS = 30_000L

private data class Playback(val url: String, val mode: String, val heading: String)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(nav: NavController, titleId: String, requestedMode: String, episodeId: String?) {
    val hasSub by AppState.hasSubscription.collectAsStateWithLifecycle()
    val load = rememberLoad(titleId, requestedMode, episodeId, hasSub) {
        val t = Repo.title(titleId) ?: error("This title isn't available.")
        // Never trust the requested mode: re-derive what this viewer may play.
        val mode = when {
            requestedMode == "trailer" && t.trailerPath != null -> "trailer"
            canWatchFull(t, hasSub) -> "full"
            else -> lockedMode(t)
        }
        val ep = episodeId?.let { id -> Repo.episodes(t.id).firstOrNull { it.id == id } }
            ?: if (t.isSeries && mode != "trailer") Repo.episodes(t.id).firstOrNull() else null
        val path = when (mode) {
            "trailer" -> t.trailerPath
            else -> ep?.videoPath ?: t.videoPath
        } ?: error("No video has been uploaded for this title yet.")
        val signedIn = supabase.auth.currentUserOrNull() != null
        if (!signedIn && mode != "trailer") error("Sign in and choose a plan to watch the full title.")
        val url = if (!signedIn) {
            runCatching { Repo.adsTrailerUrl(t.id) }.getOrElse { error(it.message ?: "Sign in to watch.") }
        } else {
            runCatching { Repo.streamUrl(path) }.getOrElse { error("Subscribe to watch this title.") }
        }
        Repo.recordView(t.id, preview = mode != "full")
        Playback(url, mode, ep?.let { "${t.name} · E${it.episodeNumber}" } ?: t.name)
    }

    ImmersiveMode()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> VideoPlayer(s.data, nav)
        }
        IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(p: Playback, nav: NavController) {
    val context = LocalContext.current
    var previewOver by remember { mutableStateOf(false) }
    val player = remember(p.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(p.url))
            prepare()
            playWhenReady = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        // Pause when the app goes to the background; free the player when leaving the screen.
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }

    if (p.mode == "preview") {
        LaunchedEffect(player) {
            while (!previewOver) {
                if (player.currentPosition >= PREVIEW_MS) {
                    player.pause()
                    previewOver = true
                }
                delay(250)
            }
        }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setShowSubtitleButton(true) } },
        modifier = Modifier.fillMaxSize(),
    )

    if (p.mode == "preview" && !previewOver) {
        Text(
            "Preview · 30 seconds",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.statusBarsPadding().padding(top = 20.dp, start = 64.dp),
        )
    }
    if (previewOver) {
        Column(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Enjoying ${p.heading}?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "Subscribe to keep watching the full video.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Button(
                onClick = { nav.navigate("profile") { launchSingleTop = true } },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
            ) { Text("See plans") }
            TextButton(onClick = { nav.popBackStack() }) { Text("Not now") }
        }
    }
}

/** Hides status/navigation bars while the player is open. */
@Composable
private fun ImmersiveMode() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
