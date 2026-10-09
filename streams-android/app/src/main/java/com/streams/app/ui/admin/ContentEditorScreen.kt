package com.streams.app.ui.admin

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.streams.app.data.Channel
import com.streams.app.data.Episode
import com.streams.app.data.Repo
import com.streams.app.data.Tags
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.friendly
import com.streams.app.data.imageUrl
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.Loading
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put

private val tierHelp = listOf(
    Tier.FREE to "Free — anyone can watch the full video, even without an account.",
    Tier.PREMIUM to "Premium — everyone sees it in the app, but only subscribers can watch in full. " +
        "Others get the trailer (or a 30-second preview if there's no trailer).",
    Tier.HIDDEN to "Hidden Premium (Exclusive) — completely invisible to non-subscribers. " +
        "Only people with an active plan can see or watch it.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentEditorScreen(nav: NavController, titleId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = titleId == null

    var loaded by remember { mutableStateOf(isNew) }
    var existing by remember { mutableStateOf<Title?>(null) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }

    // form fields
    var kind by remember { mutableStateOf("movie") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf<String?>(null) }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var trailer by remember { mutableStateOf<Uri?>(null) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var tier by remember { mutableStateOf(Tier.FREE) }
    var published by remember { mutableStateOf(false) }   // new uploads are drafts
    // Content tags (titles.tags). STREAMS_SPECIAL: only returned by the server to accounts
    // acquired through a Meta ad. NEW: "new" label. Featured/Premium keep their own settings.
    var special by remember { mutableStateOf(false) }
    var isNewTag by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var epVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(titleId) {
        runCatching {
            channels = Repo.channels()
            if (titleId != null) {
                val t = Repo.title(titleId) ?: error("Title not found")
                existing = t
                kind = t.kind; name = t.name; description = t.description; channelId = t.channelId
                tier = t.tier; published = t.published
                special = Tags.STREAMS_SPECIAL in t.tags; isNewTag = Tags.NEW in t.tags
            }
        }.onFailure { error = it.friendly() }
        loaded = true
    }
    LaunchedEffect(titleId, epVersion) {
        if (titleId != null) episodes = runCatching { Repo.episodes(titleId) }.getOrDefault(emptyList())
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cover = it ?: cover }
    val pickTrailer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { trailer = it ?: trailer }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { video = it ?: video }

    if (!loaded) { Loading(); return }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .statusBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (isNew) "Upload new title" else "Edit title", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Step(1, "Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("movie" to "Single video", "series" to "Series with episodes").forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { if (isNew) kind = k },
                        enabled = isNew,
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Step(2, "Name and short description")
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Short description") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())

            Step(3, "Channel")
            if (channels.isEmpty()) {
                Text("No channels yet — create one in the Channels tab first (optional).", style = MaterialTheme.typography.bodySmall)
            } else {
                val names = listOf("None") + channels.map { it.name }
                val current = channels.firstOrNull { it.id == channelId }?.name ?: "None"
                ChipRow(names, current, { sel -> channelId = channels.firstOrNull { it.name == sel }?.id }, sidePadding = 0.dp)
            }

            Step(4, "Thumbnail image")
            FilePicker(
                label = "Choose image",
                picked = cover,
                existing = existing?.coverPath,
                isImage = true,
                onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )

            Step(5, "Trailer / preview clip (optional)")
            FilePicker("Choose trailer", trailer, existing?.trailerPath, isImage = false, onPick = { pickTrailer.launch("video/*") })

            Step(6, if (kind == "series") "Episode 1 video" + (if (isNew) " (required)" else " — add more below") else "Main video" + if (isNew) " (required)" else "")
            if (kind == "movie" || isNew) {
                FilePicker("Choose video", video, existing?.videoPath, isImage = false, onPick = { pickVideo.launch("video/*") })
            }

            Step(7, "Who can watch")
            tierHelp.forEach { (value, help) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (tier == value) Surface2 else Surface1)
                        .selectable(selected = tier == value, onClick = { tier = value })
                        .padding(10.dp),
                ) {
                    RadioButton(selected = tier == value, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Red))
                    Spacer(Modifier.width(8.dp))
                    Text(help, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Step(8, "Tags")
            SwitchRow("STREAMS_SPECIAL — shown only to people who installed from a Meta ad", special) { special = it }
            SwitchRow("NEW", isNewTag) { isNewTag = it }

            Step(9, "Publish")
            SwitchRow(if (published) "LIVE — visible in the app" else "DRAFT — hidden from customers", published) { published = it }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
            }
            BusyButton(if (isNew) "Upload & save" else "Save changes", busy != null) {
                error = null
                when {
                    name.isBlank() -> { error = "Please enter a name"; return@BusyButton }
                    isNew && video == null -> { error = "Please choose the video file"; return@BusyButton }
                }
                scope.launch {
                    try {
                        val coverPath = cover?.let { busy = "Uploading thumbnail…"; Repo.uploadFile(context, it, "images", "covers") }
                        val trailerPath = trailer?.let { busy = "Uploading trailer…"; Repo.uploadFile(context, it, "videos", "trailers") }
                        val videoPath = video?.let { busy = "Uploading video… (large files take a while)"; Repo.uploadFile(context, it, "videos", "videos") }
                        val duration = video?.let { durationSeconds(context, it) }
                        busy = "Saving…"
                        val fields = buildJsonObject {
                            put("kind", kind)
                            put("name", name.trim())
                            put("description", description.trim())
                            put("channel_id", channelId)
                            put("tier", tier)
                            put("published", published)
                            putJsonArray("tags") {
                                if (special) add(Tags.STREAMS_SPECIAL)
                                if (isNewTag) add(Tags.NEW)
                            }
                            coverPath?.let { put("cover_path", it) }
                            trailerPath?.let { put("trailer_path", it) }
                            if (kind == "movie") {
                                videoPath?.let { put("video_path", it) }
                                duration?.let { put("duration_seconds", it) }
                            }
                        }
                        val id = Repo.saveTitle(existing?.id, fields)
                        if (kind == "series" && isNew && videoPath != null) {
                            Repo.addEpisode(id, 1, "Episode 1", videoPath)
                        }
                        Toast.makeText(context, if (published) "Saved and live" else "Saved as draft", Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                    } catch (e: Exception) {
                        error = e.friendly()
                    } finally {
                        busy = null
                    }
                }
            }

            if (!isNew && kind == "series" && existing != null) {
                EpisodesSection(existing!!, episodes, onChanged = { epVersion++ })
            }
        }
    }
}

@Composable
private fun EpisodesSection(title: Title, episodes: List<Episode>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epName by remember { mutableStateOf("") }
    var epVideo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { epVideo = it ?: epVideo }

    Text("Episodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
    episodes.forEach { e ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${e.episodeNumber}. ${e.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = {
                scope.launch {
                    runCatching { Repo.deleteEpisode(e) }.onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                    onChanged()
                }
            }) { Icon(Icons.Default.Delete, "Delete episode", tint = MaterialTheme.colorScheme.error) }
        }
    }
    val nextNumber = (episodes.maxOfOrNull { it.episodeNumber } ?: 0) + 1
    Text("Add episode $nextNumber", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(epName, { epName = it }, label = { Text("Episode name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FilePicker("Choose episode video", epVideo, null, isImage = false, onPick = { pick.launch("video/*") })
    BusyButton("Upload episode", busy) {
        val v = epVideo
        if (epName.isBlank() || v == null) {
            Toast.makeText(context, "Enter a name and choose a video", Toast.LENGTH_SHORT).show(); return@BusyButton
        }
        busy = true
        scope.launch {
            runCatching {
                val path = Repo.uploadFile(context, v, "videos", "episodes/${title.id}")
                Repo.addEpisode(title.id, nextNumber, epName, path)
            }.onSuccess { epName = ""; epVideo = null; onChanged() }
                .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
}

@Composable
private fun Step(n: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Red), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun FilePicker(label: String, picked: Uri?, existing: String?, isImage: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface1)
            .clickable(onClick = onPick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            val preview: Any? = if (isImage) picked ?: imageUrl(existing) else null
            if (preview != null) AsyncImage(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (isImage) Icons.Default.Image else Icons.Default.VideoFile, null, tint = TextMuted)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    picked != null -> "New file selected"
                    existing != null -> "Current file kept"
                    else -> "No file chosen"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(if (picked != null || existing != null) "Tap to replace" else "Tap to choose", style = MaterialTheme.typography.bodySmall)
        }
        if (picked != null) Icon(Icons.Default.CheckCircle, null, tint = Green)
        else OutlinedButton(onClick = onPick) { Text(label.removePrefix("Choose ").replaceFirstChar { it.uppercase() }, maxLines = 1) }
    }
    Spacer(Modifier.height(2.dp))
}

private suspend fun durationSeconds(context: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
    runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it / 1000).toInt() }
        } finally {
            r.release()
        }
    }.getOrNull()
}
