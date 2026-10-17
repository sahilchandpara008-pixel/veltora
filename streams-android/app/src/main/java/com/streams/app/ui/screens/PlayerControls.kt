package com.streams.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streams.app.ui.theme.Red
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Remembered while the app is open, like MX Player keeps your last speed. */
private var lastSpeed = 1f

private const val SEEK_STEP_MS = 10_000L
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

/** How the screen turns while the player is open. */
enum class Rotation(val label: String, val orientation: Int, val icon: ImageVector) {
    // FULL_SENSOR follows the phone even when the system auto-rotate switch is off (as MX Player does).
    AUTO("Auto rotate", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, Icons.Default.ScreenRotation),
    LANDSCAPE("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Icons.Default.StayCurrentLandscape),
    PORTRAIT("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Icons.Default.StayCurrentPortrait),
}

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

/** Applies [rotation] while the player is open and restores the previous orientation afterwards. */
@Composable
fun PlayerRotation(rotation: Rotation) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val previous = activity.requestedOrientation
        onDispose { activity.requestedOrientation = previous }
    }
    LaunchedEffect(rotation) { activity.requestedOrientation = rotation.orientation }
}

/**
 * The video surface with MX / VLC style controls:
 * - tap to show / hide controls, double-tap left or right to jump 10 s back or forward
 * - drag sideways to seek, drag up / down on the left for brightness and on the right for volume
 * - press and hold for 2x speed, speed panel 0.25x–4x with fine ±0.05 steps
 * - lock, rotation (auto / landscape / portrait) and fit / crop / stretch
 * [maxPositionMs] caps seeking (used for the 30-second preview).
 */
@OptIn(UnstableApi::class)
@Composable
fun ProPlayer(
    player: ExoPlayer,
    title: String,
    badge: String?,
    rotation: Rotation,
    onRotation: (Rotation) -> Unit,
    onBack: () -> Unit,
    maxPositionMs: Long? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var buffering by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableFloatStateOf(lastSpeed) }
    var holdBoost by remember { mutableStateOf(false) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }        // centre pill: "+20s", "Volume 60%", "Crop"...
    var hintIcon by remember { mutableStateOf<ImageVector?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var doubleSide by remember { mutableIntStateOf(0) }           // -1 left, +1 right
    var doubleTotal by remember { mutableLongStateOf(0L) }
    var doubleAt by remember { mutableLongStateOf(0L) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    fun touch() { lastTouch = System.currentTimeMillis() }
    fun showHint(text: String, icon: ImageVector? = null) { hint = text; hintIcon = icon; hintAt = System.currentTimeMillis() }
    fun clamp(ms: Long): Long {
        val end = if (duration > 0) duration else Long.MAX_VALUE
        return ms.coerceIn(0L, minOf(end, maxPositionMs ?: Long.MAX_VALUE))
    }
    fun seekBy(delta: Long) { player.seekTo(clamp(player.currentPosition + delta)); position = player.currentPosition }
    fun applySpeed(s: Float) {
        val v = ((s * 100).roundToInt() / 100f).coerceIn(0.25f, 4f)
        speed = v; lastSpeed = v
        player.playbackParameters = PlaybackParameters(v)
    }

    LaunchedEffect(player) { player.playbackParameters = PlaybackParameters(speed) }

    // Progress, play state and auto-hide.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            buffered = player.bufferedPosition
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            buffering = player.playbackState == Player.STATE_BUFFERING
            val now = System.currentTimeMillis()
            if (visible && !showSpeed && scrubbing == null && player.isPlaying && now - lastTouch > 3_500) visible = false
            if (hint != null && now - hintAt > 900) hint = null
            if (doubleSide != 0 && now - doubleAt > 800) { doubleSide = 0; doubleTotal = 0 }
            delay(200)
        }
    }

    // Brightness set by the player only lasts while it is open.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = RESIZE_MODES[resizeIndex].first
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture layer.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { touch(); visible = !visible; if (!visible) showSpeed = false },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            touch()
                            val third = widthPx / 3
                            when {
                                o.x < third -> {
                                    if (doubleSide != -1) { doubleSide = -1; doubleTotal = 0 }
                                    doubleTotal += SEEK_STEP_MS; doubleAt = System.currentTimeMillis(); seekBy(-SEEK_STEP_MS)
                                }
                                o.x > widthPx - third -> {
                                    if (doubleSide != 1) { doubleSide = 1; doubleTotal = 0 }
                                    doubleTotal += SEEK_STEP_MS; doubleAt = System.currentTimeMillis(); seekBy(SEEK_STEP_MS)
                                }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onLongPress = {
                            if (!locked && player.isPlaying) {
                                holdBoost = true
                                player.playbackParameters = PlaybackParameters(2f)
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (holdBoost) { holdBoost = false; player.playbackParameters = PlaybackParameters(speed) }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var mode = 0                 // 0 undecided, 1 seek, 2 brightness, 3 volume
                    var startPos = 0L
                    var target = 0L
                    var dx = 0f
                    var dy = 0f
                    var startLevel = 0f
                    var leftSide = true
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = 0; dx = 0f; dy = 0f
                            leftSide = o.x < widthPx / 2
                            startPos = player.currentPosition
                        },
                        onDragEnd = {
                            if (mode == 1) { player.seekTo(clamp(target)); scrubbing = null }
                            mode = 0
                        },
                        onDragCancel = { scrubbing = null; mode = 0 },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x; dy += amount.y
                            if (mode == 0) {
                                mode = if (abs(dx) > abs(dy)) 1 else if (leftSide) 2 else 3
                                startLevel = when (mode) {
                                    2 -> activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                                    3 -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                                    else -> 0f
                                }
                            }
                            when (mode) {
                                1 -> {
                                    // Full screen width = 90 seconds, like MX Player's swipe seek.
                                    target = clamp(startPos + (dx / widthPx * 90_000).roundToLong())
                                    if (duration > 0) scrubbing = target.toFloat() / duration
                                    val diff = (target - startPos) / 1000
                                    showHint("${if (diff >= 0) "+" else "-"}${formatTime(abs(diff) * 1000)}  [${formatTime(target)}]")
                                }
                                2 -> {
                                    val v = (startLevel - dy / (heightPx * 0.8f)).coerceIn(0.01f, 1f)
                                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v } }
                                    showHint("Brightness ${(v * 100).roundToInt()}%", Icons.Default.Brightness6)
                                }
                                3 -> {
                                    val v = (startLevel - dy / (heightPx * 0.8f)).coerceIn(0f, 1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt(), 0)
                                    showHint("Volume ${(v * 100).roundToInt()}%", Icons.AutoMirrored.Filled.VolumeUp)
                                }
                            }
                        },
                    )
                },
        )

        // Double-tap seek ripples.
        if (doubleSide != 0) {
            Box(
                Modifier
                    .align(if (doubleSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(maxWidth * 0.33f)
                    .background(
                        Brush.horizontalGradient(
                            if (doubleSide < 0) listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
                            else listOf(Color.Transparent, Color.White.copy(alpha = 0.18f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (doubleSide < 0) Icons.Default.FastRewind else Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(36.dp))
                    Text("${doubleTotal / 1000} seconds", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        if (holdBoost) Pill("2x speed", Icons.Default.FastForward, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
        hint?.let { Pill(it, hintIcon, Modifier.align(Alignment.Center)) }
        if (buffering && hint == null) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(48.dp))

        // Locked: only the unlock button.
        if (locked) {
            AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                RoundButton(Icons.Default.Lock, "Unlock", Modifier.padding(start = 20.dp), size = 52) { locked = false; touch() }
            }
        } else AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (badge != null) Text(badge, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                    TextChip(if (speed == 1f) "1x" else "${trimSpeed(speed)}x", Icons.Default.Speed) { showSpeed = !showSpeed; touch() }
                    IconButton(onClick = {
                        val next = Rotation.entries[(rotation.ordinal + 1) % Rotation.entries.size]
                        onRotation(next); showHint(next.label, next.icon); touch()
                    }) { Icon(rotation.icon, rotation.label, tint = Color.White) }
                    IconButton(onClick = {
                        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                        playerView?.resizeMode = RESIZE_MODES[resizeIndex].first
                        showHint(RESIZE_MODES[resizeIndex].second, Icons.Default.AspectRatio); touch()
                    }) { Icon(Icons.Default.AspectRatio, "Resize", tint = Color.White) }
                }

                // Centre: rewind / play / forward.
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Default.Replay10, "Back 10 seconds", size = 52) { seekBy(-SEEK_STEP_MS); touch() }
                    RoundButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", size = 72, filled = true) {
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                        touch()
                    }
                    RoundButton(Icons.Default.Forward10, "Forward 10 seconds", size = 52) { seekBy(SEEK_STEP_MS); touch() }
                }

                // Bottom: time bar.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                ) {
                    val shown = scrubbing ?: if (duration > 0) position.toFloat() / duration else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(((scrubbing ?: 0f) * duration).toLong().takeIf { scrubbing != null } ?: position), color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = shown.coerceIn(0f, 1f),
                            onValueChange = { scrubbing = it; touch() },
                            onValueChangeFinished = {
                                scrubbing?.let { player.seekTo(clamp((it * duration).toLong())) }
                                scrubbing = null
                            },
                            enabled = duration > 0,
                            colors = SliderDefaults.colors(
                                thumbColor = Red, activeTrackColor = Red,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(if (duration > 0) formatTime(duration) else "--:--", color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; showSpeed = false; touch() }) {
                            Icon(Icons.Default.LockOpen, "Lock controls", tint = Color.White)
                        }
                        Text(
                            "Double-tap sides to skip · swipe to seek · hold for 2x",
                            color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (showSpeed) {
                    SpeedPanel(
                        speed,
                        onChange = { applySpeed(it); touch() },
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        onClose = { showSpeed = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedPanel(speed: Float, onChange: (Float) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF0141418))
            .clickable(enabled = false) {}
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Playback speed", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Text("Reset", color = Red, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { onChange(1f) }.padding(6.dp))
            Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Default.FastRewind, "Slower", size = 40) { onChange(speed - 0.05f) }
            Text("${trimSpeed(speed)}x", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            RoundButton(Icons.Default.FastForward, "Faster", size = 40) { onChange(speed + 0.05f) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { s ->
                val on = abs(s - speed) < 0.001f
                Text(
                    "${trimSpeed(s)}x",
                    color = if (on) Color.White else Color.White.copy(alpha = 0.8f),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) Red else Color.White.copy(alpha = 0.1f))
                        .clickable { onChange(s) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Int = 48, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (filled) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun TextChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector?, modifier: Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

private fun trimSpeed(s: Float): String =
    "%.2f".format(s).trimEnd('0').trimEnd('.')

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
