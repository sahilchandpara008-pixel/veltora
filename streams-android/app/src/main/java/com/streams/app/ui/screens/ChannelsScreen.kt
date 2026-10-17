package com.streams.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import com.streams.app.data.ChannelStats
import com.streams.app.ui.theme.TextFaint
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

private data class ChannelsData(val channels: List<Channel>, val stats: Map<String, ChannelStats>)

private const val TRENDING = "Trending"
private const val LATEST = "Latest"
private const val MOST_WATCHED = "Most watched"

@Composable
fun ChannelsScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val joined by ChannelJoins.joined.collectAsStateWithLifecycle()
    val load = rememberLoad(access) {
        coroutineScope {
            val c = async { Repo.channels() }
            val st = async { runCatching { Repo.channelDirectory() }.getOrDefault(emptyMap()) }
            ChannelsData(c.await(), st.await())
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }       // 0 = Discover, 1 = Joined
    var sort by rememberSaveable { mutableStateOf(TRENDING) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Channels",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        )
        SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            divider = { HorizontalDivider(color = Outline) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            listOf("Discover", "Joined").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i, onClick = { tab = i },
                    selectedContentColor = Color.White, unselectedContentColor = TextMuted,
                    text = { Text(label, style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
        ChipRow(listOf(TRENDING, LATEST, MOST_WATCHED), sort, { sort = it }, Modifier.padding(vertical = 12.dp))
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val d = s.data
                val q = query.trim()
                val shown = d.channels
                    .filter { tab == 0 || it.id in joined }
                    .filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
                    .let { list ->
                        fun st(c: Channel) = d.stats[c.id] ?: ChannelStats(c.id)
                        when (sort) {
                            LATEST -> list.sortedByDescending { st(it).lastUploadAt ?: "" }
                            MOST_WATCHED -> list.sortedByDescending { st(it).views }
                            else -> list.sortedWith(compareByDescending<Channel> { st(it).members }.thenByDescending { st(it).views })
                        }
                    }
                if (shown.isEmpty()) {
                    EmptyState(
                        title = when {
                            d.channels.isEmpty() -> "No channels yet"
                            q.isNotEmpty() -> "No channels found"
                            tab == 1 -> "You haven't joined a channel yet"
                            else -> "Nothing here"
                        },
                        message = when {
                            d.channels.isEmpty() -> "Channels will appear here soon."
                            q.isNotEmpty() -> "Try a different name."
                            tab == 1 -> "Tap Join on a channel to follow it."
                            else -> "No channels match this filter."
                        },
                        icon = Icons.Default.LiveTv,
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { ch ->
                        val st = d.stats[ch.id] ?: ChannelStats(ch.id)
                        // Keep the member count in step with the Join button without reloading.
                        val members = st.members + when {
                            ch.id in joined && !st.joined -> 1
                            ch.id !in joined && st.joined -> -1
                            else -> 0
                        }
                        ChannelRow(ch, members, st.videos.toInt(), nav) { nav.navigate(Routes.channel(ch.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(Surface1)
            .border(1.dp, Outline, RoundedCornerShape(50))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search channels", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Red),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Default.Close, "Clear", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, members: Long, videos: Int, nav: NavController, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelAvatar(ch.name, ch.avatarPath, 52)
            Spacer(Modifier.width(14.dp))
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
                Text(
                    channelStats(members, videos),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            JoinButton(ch.id, nav, compact = true)
        }
        HorizontalDivider(color = Outline.copy(alpha = 0.6f), modifier = Modifier.padding(start = 82.dp))
    }
}

fun channelStats(members: Long, videos: Int) =
    "${compactCount(members)} member${if (members == 1L) "" else "s"}  •  $videos video${if (videos == 1) "" else "s"}"

/** 950 -> "950", 1200 -> "1.2K", 3400000 -> "3.4M". */
fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}

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
