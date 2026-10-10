package com.streams.app.ui.admin

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.Load
import com.streams.app.data.Audience
import com.streams.app.data.CreatorChannelRow
import com.streams.app.data.CreatorTitleRow
import com.streams.app.data.Repo
import com.streams.app.data.Review
import com.streams.app.data.friendly
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.screens.ChannelAvatar
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val statusFilters = listOf("Pending", "Approved", "Rejected", "All")
private fun statusOf(filter: String) = when (filter) {
    "Pending" -> Review.PENDING
    "Approved" -> Review.APPROVED
    "Rejected" -> Review.REJECTED
    else -> null
}

/** Admin → Creators: approve user channels and their uploads, and pick who sees them. */
@Composable
fun CreatorsTab(nav: NavController) {
    var section by remember { mutableStateOf("Channel requests") }
    var filter by remember { mutableStateOf("Pending") }
    var version by remember { mutableStateOf(0) }
    val load = rememberLoad(filter, version) {
        coroutineScope {
            val c = async { Repo.adminCreatorChannels(statusOf(filter)) }
            val t = async { Repo.adminCreatorTitles(statusOf(filter)) }
            c.await() to t.await()
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rejecting by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { version++ }
                .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val counts = (load.state as? Load.Ok)?.data
        ChipRow(
            listOf("Channel requests", "Creator videos"), section, { section = it },
            Modifier.padding(top = 10.dp),
        )
        ChipRow(statusFilters, filter, { filter = it }, Modifier.padding(vertical = 8.dp))
        if (counts != null && filter == "Pending") {
            Text(
                "${counts.first.size} channel request(s) · ${counts.second.size} video(s) waiting",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val (channels, titles) = s.data
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (section == "Channel requests") {
                        if (channels.isEmpty()) item { EmptyState("No channel requests here.", icon = Icons.Default.VideoLibrary) }
                        items(channels, key = { it.id }) { c ->
                            ChannelReviewCard(
                                c,
                                onSet = { status, audience -> act { Repo.reviewChannel(c.id, status, audience, "") } },
                                onReject = { audience ->
                                    rejecting = "Reject channel \"${c.name}\"" to { note: String -> act { Repo.reviewChannel(c.id, Review.REJECTED, audience, note) } }
                                },
                            )
                        }
                    } else {
                        if (titles.isEmpty()) item { EmptyState("No creator videos here.", icon = Icons.Default.VideoLibrary) }
                        items(titles, key = { it.id }) { t ->
                            TitleReviewCard(
                                t,
                                onPreview = { nav.navigate(Routes.player(t.id, "full")) },
                                onSet = { status, audience -> act { Repo.reviewTitle(t.id, status, audience, "") } },
                                onReject = { audience ->
                                    rejecting = "Reject video \"${t.name}\"" to { note: String -> act { Repo.reviewTitle(t.id, Review.REJECTED, audience, note) } }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    rejecting?.let { (title, done) ->
        var note by remember(title) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Reason (shown to the creator)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { rejecting = null; done(note.trim()) }) { Text("Reject", color = Red) } },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChannelReviewCard(c: CreatorChannelRow, onSet: (status: String, audience: String) -> Unit, onReject: (audience: String) -> Unit) {
    var audience by remember(c.id, c.audience) { mutableStateOf(c.audience) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(c.name, c.avatarPath, 52)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.ownerEmail ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text("${c.videoCount} video(s) · ${c.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                }
                ReviewStatus(c.status)
            }
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            if (c.status == Review.REJECTED && c.reviewNote.isNotBlank()) Text("Reason: ${c.reviewNote}", style = MaterialTheme.typography.bodySmall, color = Red)
            AudiencePicker(audience) { audience = it }
            ReviewButtons(c.status, audienceChanged = audience != c.audience, onApprove = { onSet(Review.APPROVED, audience) }, onReject = { onReject(audience) })
        }
    }
}

@Composable
private fun TitleReviewCard(
    t: CreatorTitleRow,
    onPreview: () -> Unit,
    onSet: (status: String, audience: String) -> Unit,
    onReject: (audience: String) -> Unit,
) {
    var audience by remember(t.id, t.audience) { mutableStateOf(t.audience) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                    val url = imageUrl(t.coverPath)
                    if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Default.VideoLibrary, null, tint = TextMuted)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${t.channelName ?: "No channel"} · ${t.uploaderEmail ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    t.channelAudience?.let { Text("Channel: ${Audience.label(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                ReviewStatus(t.reviewStatus)
            }
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            if (t.reviewStatus == Review.REJECTED && t.reviewNote.isNotBlank()) Text("Reason: ${t.reviewNote}", style = MaterialTheme.typography.bodySmall, color = Red)
            TextButton(onClick = onPreview) { Text("Preview video") }
            AudiencePicker(audience) { audience = it }
            ReviewButtons(t.reviewStatus, audienceChanged = audience != t.audience, onApprove = { onSet(Review.APPROVED, audience) }, onReject = { onReject(audience) })
        }
    }
}

@Composable
private fun AudiencePicker(selected: String, onSelect: (String) -> Unit) {
    Column {
        Text("Who can see it", style = MaterialTheme.typography.labelMedium)
        ChipRow(Audience.values.map(Audience::label), Audience.label(selected), { label ->
            onSelect(Audience.values.first { Audience.label(it) == label })
        }, Modifier.padding(top = 4.dp), sidePadding = 0.dp)
    }
}

@Composable
private fun ReviewButtons(status: String, audienceChanged: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            status != Review.APPROVED -> TextButton(onClick = onApprove) { Text("Approve", color = Green) }
            audienceChanged -> TextButton(onClick = onApprove) { Text("Save audience", color = Green) }
        }
        TextButton(onClick = onReject) { Text(if (status == Review.APPROVED) "Unpublish" else "Reject", color = Red) }
    }
}

@Composable
private fun ReviewStatus(status: String) {
    Text(
        when (status) { Review.APPROVED -> "Live"; Review.REJECTED -> "Rejected"; else -> "Pending" },
        color = when (status) { Review.APPROVED -> Green; Review.REJECTED -> Red; else -> Amber },
        style = MaterialTheme.typography.labelMedium,
    )
}
