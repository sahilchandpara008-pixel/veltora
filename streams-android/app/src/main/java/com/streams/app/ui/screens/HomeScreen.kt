package com.streams.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Campaign
import com.streams.app.data.Channel
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.BottomScrim
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.MetaLine
import com.streams.app.ui.components.PosterCard
import com.streams.app.ui.components.PosterPlaceholder
import com.streams.app.ui.components.PosterRowSkeleton
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.components.SecondaryButton
import com.streams.app.ui.components.SectionHeader
import com.streams.app.ui.components.SkeletonBox
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Poppins
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface3
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private data class HomeData(val titles: List<Title>, val channels: List<Channel>, val campaign: Campaign?)

fun List<Title>.filterTier(filter: String) = when (filter) {
    "Free" -> filter { it.tier == Tier.FREE }
    "Premium" -> filter { it.tier != Tier.FREE }
    else -> this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    var refreshKey by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val load = rememberLoad(access, refreshKey) {
        try {
            coroutineScope {
                val t = async { Repo.titles() }
                val c = async { Repo.channels() }
                val camp = async { runCatching { Repo.activeCampaign() }.getOrNull() }
                HomeData(t.await(), c.await(), camp.await())
            }
        } finally {
            refreshing = false
        }
    }
    var filter by remember { mutableStateOf("All") }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; refreshKey++ },
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val s = load.state) {
            Load.Loading -> HomeSkeleton()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val all = s.data.titles.filterTier(filter)
                // Hero banner shows only titles the admin marked as Featured (☆) — never automatic.
                val featured = all.filter { it.featured }.take(5)
                val channelName = { t: Title -> s.data.channels.firstOrNull { it.id == t.channelId }?.name }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                    item(key = "hero") {
                        Box {
                            if (featured.isNotEmpty()) HeroCarousel(featured, channelName, nav)
                            else Spacer(Modifier.statusBarsPadding().height(64.dp))
                            TopBar(nav)
                        }
                    }
                    s.data.campaign?.let { c -> item(key = "campaign") { CampaignBanner(c) } }
                    item(key = "chips") {
                        ChipRow(listOf("All", "Free", "Premium"), filter, { filter = it }, Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    if (all.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                title = if (filter == "All") "New titles are on the way" else "Nothing in $filter yet",
                                message = if (filter == "All") "We're adding movies and series. Pull down to refresh."
                                else "Try another filter to see more titles.",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                    // Campaign content: the server only returns STREAMS_SPECIAL titles to accounts that
                    // came from a Meta ad, so for everyone else this list is empty and the row is hidden.
                    val special = all.filter { it.isStreamsSpecial }
                    if (special.isNotEmpty()) item(key = "special") { PosterRow("Streams Special", special, nav) }
                    val trending = all.sortedByDescending { it.viewCount }.take(10)
                    if (trending.size >= 3) item(key = "top10") { Top10Row(trending, nav) }
                    val newest = all.take(12)
                    if (newest.isNotEmpty()) item(key = "new") { PosterRow("New on Streams", newest, nav) }
                    s.data.channels.forEach { ch ->
                        val list = all.filter { it.channelId == ch.id }
                        if (list.isNotEmpty()) item(key = ch.id) {
                            PosterRow(ch.name, list, nav, onMore = { nav.navigate(Routes.channel(ch.id)) })
                        }
                    }
                    val free = all.filter { it.tier == Tier.FREE }
                    if (filter == "All" && free.size >= 2) item(key = "free") { PosterRow("Free to watch", free, nav) }
                }
            }
        }
    }
}

@Composable
private fun TopBar(nav: NavController) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = 0.85f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "STREAMS",
            color = Red,
            fontFamily = Poppins,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { nav.navigate("shows") { launchSingleTop = true } }) {
            Icon(Icons.Default.Search, "Search", tint = Color.White)
        }
    }
}

@Composable
private fun HeroCarousel(items: List<Title>, channelName: (Title) -> String?, nav: NavController) {
    val pager = rememberPagerState { items.size }
    // Gently auto-advance every 6 seconds (restarts whenever the user swipes).
    LaunchedEffect(pager.currentPage, items.size) {
        if (items.size > 1) {
            delay(6000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Box {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
            val t = items[page]
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .background(Surface1)
                    .clickable { nav.navigate(Routes.title(t.id)) },
            ) {
                val url = imageUrl(t.coverPath)
                if (url != null) AsyncImage(url, t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else PosterPlaceholder(t.name)
                Box(Modifier.fillMaxSize().background(BottomScrim))
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (t.tier != Tier.FREE) TierBadge(t.tier)
                    Text(
                        t.name,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    MetaLine(
                        listOf(if (t.isSeries) "Series" else "Movie", channelName(t), if (t.tier == Tier.FREE) "Free" else null),
                        Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton("Play", { nav.navigate(Routes.player(t.id, "full")) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                        SecondaryButton("Details", { nav.navigate(Routes.title(t.id)) }, Modifier.weight(1f), icon = Icons.Default.Info)
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(items.size) { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier.size(width = w, height = 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CampaignBanner(c: Campaign) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.horizontalGradient(listOf(Red.copy(alpha = 0.28f), Surface1)))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Campaign, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(c.name, style = MaterialTheme.typography.titleSmall)
            if (c.message.isNotBlank()) Text(c.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterRow(heading: String, titles: List<Title>, nav: NavController, onMore: (() -> Unit)? = null) {
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader(heading, onSeeAll = onMore)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(titles, key = { it.id }) { t ->
                PosterCard(t, Modifier.width(116.dp)) { nav.navigate(Routes.title(t.id)) }
            }
        }
    }
}

/** "Top 10" row with large rank numbers behind each poster. */
@Composable
private fun Top10Row(titles: List<Title>, nav: NavController) {
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader("Top 10 on Streams")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(titles, key = { _, t -> "top" + t.id }) { i, t ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${i + 1}",
                        fontFamily = Poppins,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 64.sp,
                        lineHeight = 64.sp,
                        color = Surface3,
                        modifier = Modifier.padding(bottom = 36.dp),
                    )
                    PosterCard(t, Modifier.width(104.dp).padding(start = 2.dp, end = 8.dp)) { nav.navigate(Routes.title(t.id)) }
                }
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize()) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(0.8f))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { SkeletonBox(Modifier.size(width = 72.dp, height = 34.dp).clip(RoundedCornerShape(50))) }
        }
        PosterRowSkeleton()
        PosterRowSkeleton()
    }
}
