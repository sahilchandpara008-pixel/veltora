package com.streams.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.AppState
import com.streams.app.data.Attribution
import com.streams.app.Load
import com.streams.app.data.Channel
import com.streams.app.data.Episode
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.BottomScrim
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.LockedNote
import com.streams.app.ui.components.MetaLine
import com.streams.app.ui.components.PosterPlaceholder
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.components.SecondaryButton
import com.streams.app.ui.components.SkeletonBox
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.components.formatDuration
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Gold
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** What a viewer may play for this title. The server enforces the same rules on the video files. */
fun canWatchFull(t: Title, hasSub: Boolean) = t.tier == Tier.FREE || hasSub

/** For locked titles: trailer if one exists, otherwise a 30-second preview of the real video. */
fun lockedMode(t: Title) = if (t.trailerPath != null) "trailer" else "preview"

private data class DetailData(val title: Title, val episodes: List<Episode>, val channel: Channel?, val related: List<Title>)

@Composable
fun TitleDetailScreen(nav: NavController, titleId: String) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val hasSub by AppState.hasSubscription.collectAsStateWithLifecycle()
    // Make sure the Subscribe / Play buttons reflect the latest plan status.
    LaunchedEffect(titleId) {
        Attribution.logEvent("content_view", titleId)   // server logs special_content_view for STREAMS_SPECIAL titles
        AppState.refreshAccess(onlyIfChanged = true)
    }
    val load = rememberLoad(titleId, access) {
        coroutineScope {
            val t = Repo.title(titleId) ?: error("This title isn't available.")
            val eps = async { if (t.isSeries) Repo.episodes(t.id) else emptyList() }
            val channels = async { runCatching { Repo.channels() }.getOrDefault(emptyList()) }
            val all = async { runCatching { Repo.titles() }.getOrDefault(emptyList()) }
            val related = all.await().filter { it.id != t.id }
                .sortedByDescending { (if (it.channelId == t.channelId && t.channelId != null) 1000 else 0) + it.viewCount }
                .take(9)
            DetailData(t, eps.await(), channels.await().firstOrNull { it.id == t.channelId }, related)
        }
    }

    when (val s = load.state) {
        Load.Loading -> DetailSkeleton(nav)
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val d = s.data
            val t = d.title
            val full = canWatchFull(t, hasSub)
            val firstEp = d.episodes.firstOrNull()
            var expanded by remember { mutableStateOf(false) }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                item { Header(t, nav) }
                item {
                    Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TierBadge(t.tier)
                            MetaLine(
                                listOf(
                                    if (t.isSeries) "Series" else "Movie",
                                    if (t.isSeries) "${d.episodes.size} episode${if (d.episodes.size == 1) "" else "s"}" else formatDuration(t.durationSeconds),
                                    d.channel?.name,
                                ),
                            )
                        }
                        Spacer(Modifier.height(18.dp))

                        val canPlayMain = !t.isSeries || firstEp != null
                        if (full) {
                            PrimaryButton(
                                if (t.isSeries) "Play episode 1" else "Play",
                                { nav.navigate(Routes.player(t.id, "full", firstEp?.id)) },
                                enabled = canPlayMain,
                                icon = Icons.Default.PlayArrow,
                            )
                            if (t.trailerPath != null) {
                                Spacer(Modifier.height(10.dp))
                                SecondaryButton("Watch trailer", { nav.navigate(Routes.player(t.id, "trailer")) }, icon = Icons.Default.Theaters)
                            }
                        } else {
                            val mode = lockedMode(t)
                            PrimaryButton(
                                "Subscribe to watch",
                                { nav.navigate("profile") { launchSingleTop = true } },
                                icon = Icons.Default.WorkspacePremium,
                            )
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(
                                if (mode == "trailer") "Watch trailer" else "Watch 30-sec preview",
                                { nav.navigate(Routes.player(t.id, mode, firstEp?.id)) },
                                icon = Icons.Default.PlayArrow,
                                enabled = mode == "trailer" || canPlayMain,
                            )
                            Spacer(Modifier.height(12.dp))
                            PremiumNote()
                        }

                        if (t.description.isNotBlank()) {
                            Text(
                                t.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFD4D4D8),
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).clickable { expanded = !expanded },
                            )
                            if (t.description.length > 180) {
                                Text(
                                    if (expanded) "Show less" else "More",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { expanded = !expanded },
                                )
                            }
                        }
                    }
                }
                if (t.isSeries) {
                    item {
                        Text(
                            "Episodes",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
                        )
                    }
                    if (d.episodes.isEmpty()) {
                        item { Text("Episodes are coming soon.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
                    }
                    items(d.episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, t, locked = !full) {
                            nav.navigate(Routes.player(t.id, if (full) "full" else lockedMode(t), ep.id))
                        }
                    }
                }
                if (d.related.isNotEmpty()) {
                    item { PosterRow("More like this", d.related, nav) }
                }
            }
        }
    }
}

@Composable
private fun Header(t: Title, nav: NavController) {
    val url = imageUrl(t.coverPath)
    Box(Modifier.fillMaxWidth()) {
        // Blurred, darkened backdrop with the sharp poster centred on top.
        Box(Modifier.matchParentSize().background(Surface1)) {
            if (url != null) {
                AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(28.dp))
            }
            Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.45f)))
            Box(Modifier.fillMaxSize().background(BottomScrim))
        }
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 48.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(170.dp)
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Surface2),
            ) {
                if (url != null) AsyncImage(url, t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else PosterPlaceholder(t.name)
            }
            Text(
                t.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp),
            )
        }
        IconButton(
            onClick = { nav.popBackStack() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Bg.copy(alpha = 0.55f)),
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
    }
}

@Composable
private fun PremiumNote() {
    Card(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Premium title — pick a plan in Profile, pay by UPI and unlock everything on Streams.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, t: Title, locked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            imageUrl(t.coverPath)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (locked) Color.Black.copy(alpha = 0.6f) else Red),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (locked) Icons.Default.Lock else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Episode ${ep.episodeNumber}", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Text(ep.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = formatDuration(ep.durationSeconds)
            if (locked) LockedNote("Subscribe to watch")
            else if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailSkeleton(nav: NavController) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SkeletonBox(Modifier.width(170.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.height(18.dp))
            SkeletonBox(Modifier.size(width = 200.dp, height = 26.dp))
            Spacer(Modifier.height(24.dp))
            SkeletonBox(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(50.dp))
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(50.dp))
        }
        IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
    }
}
