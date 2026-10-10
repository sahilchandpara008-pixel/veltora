package com.streams.app.ui.screens

import android.content.Context
import com.streams.app.data.Links
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.Load
import com.streams.app.data.Channel
import com.streams.app.data.Repo
import com.streams.app.data.Review
import com.streams.app.data.Title
import com.streams.app.data.friendly
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.components.SecondaryButton
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Poppins
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Supabase free plan: 50 MB per file. */
private const val MAX_VIDEO_BYTES = 50L * 1024 * 1024

/**
 * Profile → My channels. Anyone signed in can ask for a channel; the admin approves it and picks
 * who sees it. Owners change the channel photo/details and upload, edit or delete their videos
 * (each upload is reviewed by the admin before it goes live).
 */
@Composable
fun CreatorScreen(nav: NavController) {
    var version by remember { mutableStateOf(0) }
    val load = rememberLoad(version) {
        coroutineScope {
            val c = async { Repo.myChannels() }
            val t = async { Repo.myUploads() }
            c.await() to t.await()
        }
    }
    var editChannel by remember { mutableStateOf<Channel?>(null) }
    var newChannel by remember { mutableStateOf(false) }
    var deleteChannel by remember { mutableStateOf<Channel?>(null) }
    var videoForm by remember { mutableStateOf<Pair<Channel, Title?>?>(null) }
    var deleteVideo by remember { mutableStateOf<Title?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reload = { version++ }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("My channels", style = MaterialTheme.typography.headlineMedium)
        }
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val (channels, uploads) = s.data
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text(
                            "Create your own channel and share videos. Every new channel and video is checked by our team before it goes live.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Create channel", { newChannel = true }, icon = Icons.Default.Add)
                    }
                    if (channels.isEmpty()) {
                        item { EmptyState(title = "No channels yet", message = "Create a channel to start uploading.", icon = Icons.Default.VideoLibrary) }
                    }
                    items(channels, key = { it.id }) { ch ->
                        MyChannelCard(
                            ch, uploads.filter { it.channelId == ch.id },
                            onEdit = { editChannel = ch },
                            onDelete = { deleteChannel = ch },
                            onUpload = { videoForm = ch to null },
                            onPlay = { t -> nav.navigate(Routes.player(t.id, "full")) },
                            onEditVideo = { t -> videoForm = ch to t },
                            onDeleteVideo = { t -> deleteVideo = t },
                        )
                    }
                }

                if (newChannel || editChannel != null) {
                    ChannelDialog(editChannel, onDismiss = { newChannel = false; editChannel = null }) {
                        newChannel = false; editChannel = null; reload()
                    }
                }
                videoForm?.let { (ch, t) ->
                    VideoDialog(ch, t, onDismiss = { videoForm = null }) { videoForm = null; reload() }
                }
                deleteChannel?.let { ch ->
                    ConfirmDialog(
                        "Delete channel?",
                        "\"${ch.name}\" and all its videos will be deleted. This can't be undone.",
                        onDismiss = { deleteChannel = null },
                    ) {
                        deleteChannel = null
                        scope.launch {
                            runCatching { Repo.deleteMyChannel(ch, uploads) }
                                .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                            reload()
                        }
                    }
                }
                deleteVideo?.let { t ->
                    ConfirmDialog("Delete video?", "\"${t.name}\" will be deleted. This can't be undone.", onDismiss = { deleteVideo = null }) {
                        deleteVideo = null
                        scope.launch {
                            runCatching { Repo.deleteMyUpload(t) }
                                .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                            reload()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyChannelCard(
    ch: Channel,
    videos: List<Title>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onPlay: (Title) -> Unit,
    onEditVideo: (Title) -> Unit,
    onDeleteVideo: (Title) -> Unit,
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(ch.name, ch.avatarPath, 56)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusLabel(ch.status, if (ch.status == Review.APPROVED) "Live" else Review.label(ch.status))
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit channel") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete channel") }
            }
            if (ch.description.isNotBlank()) Text(ch.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            when (ch.status) {
                Review.PENDING -> Text("Waiting for approval. You can upload videos once it's approved.", style = MaterialTheme.typography.bodySmall)
                Review.REJECTED -> Text(
                    "Not approved" + (ch.reviewNote.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") +
                        ". Edit the channel to send it for review again.",
                    style = MaterialTheme.typography.bodySmall, color = Red,
                )
                else -> {
                    SecondaryButton("Upload video", onUpload, icon = Icons.Default.Upload)
                    if (videos.isEmpty()) Text("No videos yet.", style = MaterialTheme.typography.bodySmall)
                    videos.forEach { t -> MyVideoRow(t, { onPlay(t) }, { onEditVideo(t) }, { onDeleteVideo(t) }) }
                }
            }
        }
    }
}

@Composable
private fun MyVideoRow(t: Title, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            val url = imageUrl(t.coverPath)
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.VideoLibrary, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = when {
                t.isLive -> "Live"
                t.reviewStatus == Review.REJECTED -> "Rejected" + (t.reviewNote.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                else -> "Pending review"
            }
            StatusLabel(if (t.isLive) Review.APPROVED else t.reviewStatus, label)
        }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play") }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit video") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete video") }
    }
}

@Composable
private fun StatusLabel(status: String, text: String) {
    val color = when (status) {
        Review.APPROVED -> Green
        Review.REJECTED -> Red
        else -> Amber
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 2)
}

/** Channel photo (DP), or the first letter on a red circle when there's none. */
@Composable
fun ChannelAvatar(name: String, avatarPath: String?, sizeDp: Int, picked: Uri? = null) {
    val url: Any? = picked ?: imageUrl(avatarPath)
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Red),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.take(1).uppercase().ifBlank { "?" }, color = Color.White, fontSize = (sizeDp * 0.42).sp, fontWeight = FontWeight.ExtraBold, fontFamily = Poppins)
    }
}

@Composable
private fun ChannelDialog(existing: Channel?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it ?: photo }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Create channel" else "Edit channel") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(name, existing?.avatarPath, 64, photo)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (photo != null || existing?.avatarPath != null) "Change photo" else "Add photo")
                    }
                }
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Channel name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(1000) }, label = { Text("About this channel") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    Text("Our team reviews new channels before they're shown in the app.", style = MaterialTheme.typography.bodySmall)
                    TermsCheck(agreed) { agreed = it }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && agreed && name.trim().length >= 2, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        val avatar = photo?.let { Repo.uploadFile(context, it, "images", Repo.creatorFolder()) } ?: existing?.avatarPath
                        if (existing == null) Repo.requestChannel(name, description, avatar)
                        else Repo.updateMyChannel(existing.id, name, description, avatar)
                        if (photo != null && existing?.avatarPath != null) Repo.deleteCreatorFile("images", existing.avatarPath)
                    }.onSuccess {
                        Toast.makeText(context, if (existing == null) "Channel request sent for approval" else "Channel updated", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }.onFailure { error = it.friendly(); busy = false }
                }
            }) { Text(if (existing == null) "Send request" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VideoDialog(channel: Channel, existing: Title?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cover = it ?: cover }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val size = fileSize(context, uri)
            if (size != null && size > MAX_VIDEO_BYTES) error = "This video is ${size / 1048576} MB. The limit is 50 MB per video."
            else { video = uri; error = null }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Upload to ${channel.name}" else "Edit video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(3000) }, label = { Text("Description") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                        val url: Any? = cover ?: imageUrl(existing?.coverPath)
                        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text("No cover", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose cover") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            video != null -> "New video selected"
                            existing != null -> "Current video kept"
                            else -> "No video selected"
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                        Text(if (existing == null && video == null) "Choose video" else "Replace video")
                    }
                }
                Text("Max 50 MB. New videos and replaced videos are reviewed before they go live.", style = MaterialTheme.typography.bodySmall)
                if (existing == null) TermsCheck(agreed) { agreed = it }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && agreed && name.isNotBlank() && (video != null || existing?.videoPath != null), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        val folder = Repo.creatorFolder()
                        var duration = existing?.durationSeconds
                        val videoPath = video?.let {
                            duration = videoDuration(context, it) ?: duration
                            step = "Uploading video…"
                            Repo.uploadFile(context, it, "videos", folder)
                        } ?: existing!!.videoPath!!
                        val coverPath = cover?.let { step = "Uploading cover…"; Repo.uploadFile(context, it, "images", folder) } ?: existing?.coverPath
                        step = "Saving…"
                        Repo.creatorSaveTitle(existing?.id, channel.id, name, description, coverPath, videoPath, existing?.trailerPath, duration)
                        // Remove files this edit replaced.
                        if (existing != null) {
                            if (video != null) Repo.deleteCreatorFile("videos", existing.videoPath)
                            if (cover != null) Repo.deleteCreatorFile("images", existing.coverPath)
                        }
                    }.onSuccess {
                        Toast.makeText(context, if (existing == null || video != null) "Sent for review" else "Saved", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }.onFailure { error = it.friendly(); busy = false }
                }
            }) { Text(if (existing == null) "Upload" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Creators accept the Terms of Use (creator rules) before requesting a channel or uploading. */
@Composable
private fun TermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text("I agree to the Terms of Use and Creator rules", style = MaterialTheme.typography.bodySmall)
            Text(
                "Read the rules", color = Red, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Links.TERMS))) }
                },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fileSize(context: Context, uri: Uri): Long? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }

private fun videoDuration(context: Context, uri: Uri): Int? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it / 1000).toInt() }
    } finally {
        r.release()
    }
}.getOrNull()
