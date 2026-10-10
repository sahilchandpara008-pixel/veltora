package com.streams.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Channel
import com.streams.app.data.ChannelJoins
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.currentEmail
import com.streams.app.data.friendly
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PosterCard
import com.streams.app.ui.components.ReportDialog
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.theme.Outline
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class ChannelsData(val channels: List<Channel>, val titles: List<Title>, val members: Map<String, Long>)

@Composable
fun ChannelsScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val joined by ChannelJoins.joined.collectAsStateWithLifecycle()
    val load = rememberLoad(access) {
        coroutineScope {
            val c = async { Repo.channels() }
            val t = async { Repo.titles() }
            val m = async { Repo.memberCounts() }
            ChannelsData(c.await(), t.await(), m.await())
        }
    }
    var filter by remember { mutableStateOf("All channels") }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Channels",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        ChipRow(
            listOf("All channels", "Joined", "Has free titles"),
            filter, { filter = it },
            Modifier.padding(vertical = 10.dp),
        )
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val d = s.data
                val byChannel = d.titles.groupBy { it.channelId }
                val shown = d.channels.filter { ch ->
                    when (filter) {
                        "Joined" -> ch.id in joined
                        "Has free titles" -> byChannel[ch.id].orEmpty().any { it.tier == Tier.FREE }
                        else -> true
                    }
                }
                if (shown.isEmpty()) {
                    EmptyState(
                        title = when {
                            d.channels.isEmpty() -> "No channels yet"
                            filter == "Joined" -> "You haven't joined a channel yet"
                            else -> "Nothing here"
                        },
                        message = when {
                            d.channels.isEmpty() -> "Channels will appear here soon."
                            filter == "Joined" -> "Tap Join on a channel to follow it."
                            else -> "No channels match this filter."
                        },
                        icon = Icons.Default.LiveTv,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { ch ->
                        ChannelCard(ch, byChannel[ch.id].orEmpty(), d.members[ch.id] ?: 0, nav) { nav.navigate(Routes.channel(ch.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, titles: List<Title>, members: Long, nav: NavController, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(ch.name, ch.avatarPath, 56)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ch.isPremium) {
                    Spacer(Modifier.width(8.dp))
                    TierBadge(Tier.PREMIUM)
                }
            }
            if (ch.description.isNotBlank()) {
                Text(ch.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                channelStats(members, titles.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        JoinButton(ch.id, nav, compact = true)
    }
}

fun channelStats(members: Long, videos: Int) =
    "$members member${if (members == 1L) "" else "s"} · $videos video${if (videos == 1) "" else "s"}"

/**
 * Join / Joined toggle. Joining needs an account: guests are sent to sign in first.
 * The server only accepts channels this viewer is allowed to see.
 */
@Composable
fun JoinButton(channelId: String, nav: NavController, compact: Boolean = false) {
    val joinedSet by ChannelJoins.joined.collectAsStateWithLifecycle()
    val joined = channelId in joinedSet
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .then(if (joined) Modifier.border(1.dp, Outline, shape) else Modifier.background(Red))
            .clickable {
                if (currentEmail() == null) {
                    nav.navigate("auth")
                    return@clickable
                }
                scope.launch {
                    ChannelJoins.toggle(channelId)
                        .onSuccess { nowJoined -> if (nowJoined) Toast.makeText(context, "Joined", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                }
            }
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (joined) Icons.Default.Check else Icons.Default.Add, null,
            tint = if (joined) TextMuted else Color.White, modifier = Modifier.size(if (compact) 16.dp else 18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (joined) "Joined" else "Join",
            color = if (joined) TextMuted else Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 13.sp else 15.sp,
        )
    }
}

private data class ChannelDetail(val channel: Channel?, val titles: List<Title>, val members: Long, val joinedAtLoad: Boolean)

@Composable
fun ChannelDetailScreen(nav: NavController, channelId: String) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val joinedSet by ChannelJoins.joined.collectAsStateWithLifecycle()
    val load = rememberLoad(channelId, access) {
        coroutineScope {
            val c = async { Repo.channels().firstOrNull { it.id == channelId } }
            val t = async { Repo.titles().filter { it.channelId == channelId } }
            val m = async { Repo.memberCounts()[channelId] ?: 0L }
            ChannelDetail(c.await(), t.await(), m.await(), channelId in ChannelJoins.joined.value)
        }
    }
    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val (ch, titles, baseMembers, joinedAtLoad) = s.data
            val joinedNow = channelId in joinedSet
            // Keep the member count in step with the Join button without reloading.
            val members = baseMembers + when {
                joinedNow && !joinedAtLoad -> 1
                !joinedNow && joinedAtLoad -> -1
                else -> 0
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Spacer(Modifier.weight(1f))
                            if (ch != null) {
                                var reporting by remember { mutableStateOf(false) }
                                IconButton(onClick = { if (currentEmail() == null) nav.navigate("auth") else reporting = true }) {
                                    Icon(Icons.Outlined.Flag, "Report channel")
                                }
                                if (reporting) ReportDialog("channel", ch.id, ch.name) { reporting = false }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChannelAvatar(ch?.name ?: "Channel", ch?.avatarPath, 64)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ch?.name ?: "Channel", style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(channelStats(members, titles.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (ch != null) {
                            Spacer(Modifier.height(12.dp))
                            JoinButton(ch.id, nav)
                        }
                        ch?.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                        Text("Videos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
                    }
                }
                if (titles.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No videos in this channel yet.") }
                }
                items(titles, key = { it.id }) { t -> PosterCard(t) { nav.navigate(Routes.title(t.id)) } }
            }
        }
    }
}
