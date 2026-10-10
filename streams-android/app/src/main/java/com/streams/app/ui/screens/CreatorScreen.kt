package com.streams.app.ui.screens

import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Outline
import com.streams.app.ui.theme.Bg
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
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
            val m = async { Repo.memberCounts() }
            Triple(c.await(), t.await(), m.await())
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
                val (channels, uploads, members) = s.data
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text(
                            "Create your own channel and share videos. New channels are checked by our team; once approved, your videos go live right away.",
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
                            ch, uploads.filter { it.channelId == ch.id }, members[ch.id] ?: 0L,
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
    members: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onPlay: (Title) -> Unit,
    onEditVideo: (Title) -> Unit,
    onDeleteVideo: (Title) -> Unit,
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(ch.name, ch.avatarPath, 56)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(ch.status, if (ch.status == Review.APPROVED) "Live" else Review.label(ch.status))
                        if (ch.status == Review.APPROVED) {
                            Spacer(Modifier.width(8.dp))
                            Text(channelStats(members, videos.size), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit channel", tint = TextMuted) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete channel", tint = TextMuted) }
            }
            if (ch.description.isNotBlank()) Text(ch.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            when (ch.status) {
                Review.PENDING -> InfoBox(
                    "Waiting for approval",
                    "Our team is checking your channel. You can add videos as soon as it's approved.",
                    Amber,
                )
                Review.REJECTED -> InfoBox(
                    "Not approved",
                    (ch.reviewNote.takeIf { it.isNotBlank() }?.let { "$it. " } ?: "") + "Edit the channel to send it for review again.",
                    Red,
                )
                else -> {
                    PrimaryButton("Add video", onUpload, icon = Icons.Default.Add)
                    if (videos.isEmpty()) {
                        Text("No videos yet. Tap Add video to publish your first one.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Videos (${videos.size})", style = MaterialTheme.typography.labelLarge)
                        videos.forEach { t -> MyVideoRow(t, { onPlay(t) }, { onEditVideo(t) }, { onDeleteVideo(t) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBox(title: String, text: String, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = color, style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MyVideoRow(t: Title, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp)).background(Surface2).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            val url = imageUrl(t.coverPath)
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                t.isLive -> StatusChip(Review.APPROVED, "Live")
                t.reviewStatus == Review.REJECTED -> StatusChip(Review.REJECTED, "Unpublished")
                else -> StatusChip(Review.PENDING, "Processing")
            }
            if (t.reviewStatus == Review.REJECTED && t.reviewNote.isNotBlank()) {
                Text(t.reviewNote, style = MaterialTheme.typography.bodySmall, color = Red, maxLines = 2)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit video", tint = TextMuted) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete video", tint = TextMuted) }
    }
}

@Composable
private fun StatusChip(status: String, text: String) {
    val color = when (status) {
        Review.APPROVED -> Green
        Review.REJECTED -> Red
        else -> Amber
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
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
                        Toast.makeText(context, if (existing == null) "Channel created. It goes live once our team approves it." else "Channel updated", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }.onFailure { error = it.friendly(); busy = false }
                }
            }) { Text(if (existing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen form to add or edit one video: details, thumbnail, optional trailer and the full video. */
@Composable
private fun VideoDialog(channel: Channel, existing: Title?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var trailer by remember { mutableStateOf<Uri?>(null) }
    var removeTrailer by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun checked(uri: Uri?, what: String, set: (Uri) -> Unit) {
        if (uri == null) return
        val size = fileSize(context, uri)
        if (size != null && size > MAX_VIDEO_BYTES) error = "This $what is ${size / 1048576} MB. The limit is 50 MB."
        else { set(uri); error = null }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cover = it ?: cover }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { checked(it, "video") { u -> video = u } }
    val pickTrailer = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        checked(it, "trailer") { u -> trailer = u; removeTrailer = false }
    }
    val hasTrailer = trailer != null || (!removeTrailer && existing?.trailerPath != null)
    val canSave = !busy && agreed && name.isNotBlank() && (video != null || existing?.videoPath != null)
    val videoOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)

    fun save() {
        busy = true; error = null
        scope.launch {
            runCatching {
                val folder = Repo.creatorFolder()
                val total = listOfNotNull(video, trailer, cover).size
                var n = 0
                fun next(what: String) { n++; step = "Uploading $what ($n of $total)…" }
                var duration = existing?.durationSeconds
                val videoPath = video?.let {
                    duration = videoDuration(context, it) ?: duration
                    next("full video")
                    Repo.uploadFile(context, it, "videos", folder)
                } ?: existing!!.videoPath!!
                val trailerPath = trailer?.let { next("trailer"); Repo.uploadFile(context, it, "videos", folder) }
                    ?: if (removeTrailer) null else existing?.trailerPath
                val coverPath = cover?.let { next("thumbnail"); Repo.uploadFile(context, it, "images", folder) } ?: existing?.coverPath
                step = "Publishing…"
                Repo.creatorSaveTitle(existing?.id, channel.id, name, description, coverPath, videoPath, trailerPath, duration)
                // Remove files this edit replaced.
                if (existing != null) {
                    if (video != null) Repo.deleteCreatorFile("videos", existing.videoPath)
                    if (trailer != null || removeTrailer) Repo.deleteCreatorFile("videos", existing.trailerPath)
                    if (cover != null) Repo.deleteCreatorFile("images", existing.coverPath)
                }
            }.onSuccess {
                Toast.makeText(context, if (existing == null) "Published to ${channel.name}" else "Saved", Toast.LENGTH_SHORT).show()
                onSaved()
            }.onFailure { error = it.friendly(); busy = false }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!busy) onDismiss() }) { Icon(Icons.Default.Close, "Close") }
                    Column {
                        Text(if (existing == null) "Add video" else "Edit video", style = MaterialTheme.typography.titleLarge)
                        Text(channel.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Outline)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Details", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it.take(120) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        description, { description = it.take(3000) }, label = { Text("Description") },
                        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    )

                    Text("Thumbnail", style = MaterialTheme.typography.titleMedium)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surface2)
                            .border(1.dp, Outline, RoundedCornerShape(14.dp))
                            .clickable(enabled = !busy) { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val url: Any? = cover ?: imageUrl(existing?.coverPath)
                        if (url != null) {
                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text(
                                "Change",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Image, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                Text("Tap to choose a thumbnail", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                                Text("16:9 image works best", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Text("Videos", style = MaterialTheme.typography.titleMedium)
                    FileTile(
                        title = "Full video",
                        badge = "Required",
                        icon = Icons.Default.Movie,
                        status = when {
                            video != null -> fileLabel(context, video!!)
                            existing != null -> "Current video"
                            else -> "No file chosen"
                        },
                        hasFile = video != null || existing != null,
                        chooseLabel = if (video != null || existing != null) "Replace" else "Choose",
                        enabled = !busy,
                        onChoose = { pickVideo.launch(videoOnly) },
                        onRemove = null,
                    )
                    FileTile(
                        title = "Trailer",
                        badge = "Optional",
                        icon = Icons.Default.Theaters,
                        status = when {
                            trailer != null -> fileLabel(context, trailer!!)
                            hasTrailer -> "Current trailer"
                            else -> "No trailer"
                        },
                        hasFile = hasTrailer,
                        chooseLabel = if (hasTrailer) "Replace" else "Choose",
                        enabled = !busy,
                        onChoose = { pickTrailer.launch(videoOnly) },
                        onRemove = if (hasTrailer) ({ trailer = null; removeTrailer = true }) else null,
                    )
                    Text(
                        "MP4 recommended · max 50 MB per file. Your video goes live in ${channel.name} as soon as you publish.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (existing == null) TermsCheck(agreed) { agreed = it }
                    error?.let { InfoBox("Couldn't publish", it, Red) }
                }
                Column(
                    Modifier.fillMaxWidth().background(Surface1).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
                        Text(step, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Cancel", { if (!busy) onDismiss() }, Modifier.weight(1f), enabled = !busy)
                        PrimaryButton(
                            if (existing == null) "Publish" else "Save", { save() }, Modifier.weight(1f),
                            enabled = canSave, loading = busy,
                        )
                    }
                }
            }
        }
    }
}

/** One file slot (full video / trailer) with clear Choose / Replace / Remove actions. */
@Composable
private fun FileTile(
    title: String,
    badge: String,
    icon: ImageVector,
    status: String,
    hasFile: Boolean,
    chooseLabel: String,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, if (hasFile) Red.copy(alpha = 0.6f) else Outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (hasFile) Red else Surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                Text(badge, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Button(
                onClick = onChoose,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(50),
            ) { Text(chooseLabel, style = MaterialTheme.typography.labelLarge) }
            if (onRemove != null) {
                TextButton(onClick = onRemove, enabled = enabled) { Text("Remove", color = TextMuted) }
            }
        }
    }
}

/** "clip.mp4 · 12.4 MB" for a picked file. */
private fun fileLabel(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Selected file"
    val size = fileSize(context, uri)?.let { " · %.1f MB".format(it / 1048576.0) } ?: ""
    return name + size
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
