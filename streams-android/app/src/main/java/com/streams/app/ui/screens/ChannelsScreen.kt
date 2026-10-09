package com.streams.app.ui.screens

import androidx.compose.material.icons.filled.LiveTv
import com.streams.app.ui.theme.Poppins
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Channel
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PosterCard
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun ChannelsScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val load = rememberLoad(access) {
        coroutineScope {
            val c = async { Repo.channels() }
            val t = async { Repo.titles() }
            c.await() to t.await()
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
            listOf("All channels", "Has free titles", "Has premium"),
            filter, { filter = it },
            Modifier.padding(vertical = 10.dp),
        )
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val (channels, titles) = s.data
                val byChannel = titles.groupBy { it.channelId }
                val shown = channels.filter { ch ->
                    val list = byChannel[ch.id].orEmpty()
                    when (filter) {
                        "Has free titles" -> list.any { it.tier == Tier.FREE }
                        "Has premium" -> ch.isPremium || list.any { it.tier != Tier.FREE }
                        else -> true
                    }
                }
                if (shown.isEmpty()) EmptyState(title = if (channels.isEmpty()) "No channels yet" else "Nothing here", message = if (channels.isEmpty()) "Channels will appear here soon." else "No channels match this filter.", icon = Icons.Default.LiveTv)
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { ch ->
                        ChannelCard(ch, byChannel[ch.id].orEmpty()) { nav.navigate(Routes.channel(ch.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, titles: List<Title>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(
                Brush.linearGradient(listOf(Red, Color(0xFF7F0A12))),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(ch.name.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Poppins)
        }
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
                Text(ch.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val free = titles.count { it.tier == Tier.FREE }
            Text(
                "${titles.size} title${if (titles.size == 1) "" else "s"}" + if (free > 0) " · $free free" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun ChannelDetailScreen(nav: NavController, channelId: String) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val load = rememberLoad(channelId, access) {
        coroutineScope {
            val c = async { Repo.channels().firstOrNull { it.id == channelId } }
            val t = async { Repo.titles().filter { it.channelId == channelId } }
            c.await() to t.await()
        }
    }
    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val (ch, titles) = s.data
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.statusBarsPadding()) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(ch?.name ?: "Channel", style = MaterialTheme.typography.headlineMedium)
                        ch?.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (titles.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No titles in this channel yet.") }
                }
                items(titles, key = { it.id }) { t -> PosterCard(t) { nav.navigate(Routes.title(t.id)) } }
            }
        }
    }
}
