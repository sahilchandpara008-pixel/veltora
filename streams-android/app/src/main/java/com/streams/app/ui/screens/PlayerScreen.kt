package com.streams.app.ui.screens

import com.streams.app.data.MetaEvents
import android.app.Activity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.streams.app.data.Title
import com.streams.app.ui.Routes
import io.github.jan.supabase.auth.status.SessionStatus
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

private data class Playback(val url: String, val mode: String, val heading: String, val title: Title)

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
        if (!signedIn && mode != "trailer") error(if (AppState.freeMode.value) "Sign in to watch the full title." else "Sign in and choose a plan to watch the full title.")
        val url = if (!signedIn) {
            runCatching { Repo.adsTrailerUrl(t.id) }.getOrElse { error(it.message ?: "Sign in to watch.") }
        } else {
            runCatching { Repo.streamUrl(path) }.getOrElse { error("Subscribe to watch this title.") }
        }
        Repo.recordView(t.id, preview = mode != "full")
        Playback(url, mode, ep?.let { "${t.name} · E${it.episodeNumber}" } ?: t.name, t)
    }

    ImmersiveMode()
    // Follows the phone's sensor by default (even with system auto-rotate off), like MX Player.
    var rotation by rememberSaveable { mutableStateOf(Rotation.AUTO) }
    PlayerRotation(rotation)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> VideoPlayer(s.data, nav, hasSub, rotation) { rotation = it }
        }
        if (load.state !is Load.Ok) {
            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(p: Playback, nav: NavController, hasSub: Boolean, rotation: Rotation, onRotation: (Rotation) -> Unit) {
    val context = LocalContext.current
    val session by AppState.session.collectAsStateWithLifecycle()
    val signedIn = session is SessionStatus.Authenticated
    var previewOver by remember(p.url) { mutableStateOf(false) }
    var ended by remember(p.url) { mutableStateOf(false) }
    var playError by remember(p.url) { mutableStateOf<String?>(null) }
    val player = remember(p.url) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)   // pause when headphones are unplugged
            .build().apply {
                setMediaItem(MediaItem.fromUri(p.url))
                prepare()
                playWhenReady = true
            }
    }
    var reportedPlay by remember(p.url) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended = true
                    if (p.mode == "trailer") MetaEvents.trailerComplete(p.title)
                }
                if (state == Player.STATE_READY) playError = null
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !reportedPlay) { reportedPlay = true; MetaEvents.videoPlay(p.title, p.mode) }
            }
            override fun onPlayerError(error: PlaybackException) {
                playError = if (error.errorCode in NETWORK_ERRORS) "Check your internet connection and try again."
                else "This video couldn't be played. Please try again."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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

    ProPlayer(
        player = player,
        title = p.heading,
        badge = when (p.mode) {
            "trailer" -> "Trailer"
            "preview" -> if (previewOver) null else "Preview · 30 seconds"
            else -> null
        },
        rotation = rotation,
        onRotation = onRotation,
        onBack = { nav.popBackStack() },
        maxPositionMs = if (p.mode == "preview") PREVIEW_MS else null,
    )

    val fullAllowed = signedIn && canWatchFull(p.title, hasSub)
    when {
        playError != null -> Overlay(
            title = "Playback problem",
            message = playError!!,
            primary = "Try again" to { playError = null; player.prepare(); player.play() },
            secondary = "Close" to { nav.popBackStack() },
        )
        // Trailer finished: guests sign in to keep watching; members go straight to the full video.
        p.mode == "trailer" && ended -> when {
            !signedIn -> Overlay(
                title = "Want to watch the full video?",
                message = if (AppState.freeMode.value) "Sign in to Veltora to watch ${p.heading} in full. It's free."
                else "Sign in to Veltora to keep watching ${p.heading}.",
                primary = "Sign in to watch" to {
                    nav.popBackStack()
                    nav.navigate(Routes.player(p.title.id, "full"))   // sign-in screen, then the full video
                },
                secondary = "Watch trailer again" to { ended = false; player.seekTo(0); player.play() },
            )
            fullAllowed -> Overlay(
                title = p.heading,
                message = "Ready for the full video?",
                primary = "Watch full video" to {
                    nav.popBackStack()
                    nav.navigate(Routes.player(p.title.id, "full"))
                },
                secondary = "Watch trailer again" to { ended = false; player.seekTo(0); player.play() },
            )
            else -> Overlay(
                title = "Enjoying ${p.heading}?",
                message = "Choose a plan to watch the full video.",
                primary = "See plans" to { nav.navigate("profile") { launchSingleTop = true } },
                secondary = "Not now" to { nav.popBackStack() },
            )
        }
        previewOver -> Overlay(
            title = "Enjoying ${p.heading}?",
            message = if (signedIn) "Choose a plan to keep watching the full video." else "Sign in to keep watching.",
            primary = if (signedIn) "See plans" to { nav.navigate("profile") { launchSingleTop = true } }
            else "Sign in" to { nav.navigate("auth") },
            secondary = "Not now" to { nav.popBackStack() },
        )
    }
}

@Composable
private fun Overlay(title: String, message: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = Color.White)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        Button(onClick = primary.second, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text(primary.first) }
        TextButton(onClick = secondary.second) { Text(secondary.first) }
    }
}

private val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
)

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
