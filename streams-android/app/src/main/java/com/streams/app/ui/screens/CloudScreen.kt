package com.streams.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.CloudFile
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.rememberLoad
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PremiumBenefitsCard
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.Surface3
import com.streams.app.ui.theme.TextMuted
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class CloudData(val files: List<CloudFile>, val usedBytes: Long, val quotaGb: Int)

@Composable
fun CloudScreen(nav: NavController) {
    val session by AppState.session.collectAsStateWithLifecycle()
    val hasSub by AppState.hasSubscription.collectAsStateWithLifecycle()
    val freeMode by AppState.freeMode.collectAsStateWithLifecycle()
    val signedIn = session is SessionStatus.Authenticated

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text("Cloud Storage", style = MaterialTheme.typography.headlineMedium)
            Text("Your private files, available on any device", style = MaterialTheme.typography.bodySmall)
        }
        when {
            !signedIn -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!freeMode) PremiumBenefitsCard()
                EmptyState(
                    title = "Sign in to use Cloud Storage",
                    message = if (freeMode) "Get free cloud storage for your photos, videos and documents."
                              else "Store photos, videos and documents safely with a premium plan.",
                    icon = Icons.Default.CloudUpload,
                )
                PrimaryButton("Sign in", { nav.navigate("auth") })
            }
            !hasSub -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PremiumBenefitsCard()
                Text(
                    "Cloud Storage is included with every premium plan. Choose a plan in Profile and pay by UPI to unlock it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrimaryButton("See plans", { nav.navigate("profile") { launchSingleTop = true } })
            }
            else -> CloudFiles()
        }
    }
}

@Composable
private fun CloudFiles() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<CloudFile?>(null) }
    val load = rememberLoad(version) {
        coroutineScope {
            val files = async { Repo.myFiles() }
            val used = async { Repo.cloudUsageBytes() }
            val quota = async { runCatching { Repo.paymentSettings().cloudQuotaGb }.getOrDefault(2048) }
            CloudData(files.await(), used.await(), quota.await())
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEachIndexed { i, uri ->
                uploading = if (uris.size > 1) "Uploading ${i + 1} of ${uris.size}…" else "Uploading…"
                runCatching { Repo.uploadCloudFile(context, uri) }
                    .onFailure {
                        val msg = it.friendly()
                        Toast.makeText(
                            context,
                            if (msg.contains("row-level security")) "Upload not allowed — your storage may be full." else msg,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
            uploading = null
            version++
        }
    }

    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val d = s.data
            val quotaBytes = d.quotaGb.toLong() * 1024 * 1024 * 1024
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card(padding = 18.dp) {
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(formatBytes(d.usedBytes), style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "  of ${formatBytes(quotaBytes)} used",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { (d.usedBytes.toFloat() / quotaBytes).coerceIn(0f, 1f) },
                                color = Red,
                                trackColor = Surface3,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${d.files.size} file${if (d.files.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    PrimaryButton(
                        uploading ?: "Upload files",
                        { picker.launch("*/*") },
                        loading = uploading != null,
                        icon = Icons.Default.CloudUpload,
                    )
                }
                if (uploading != null) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red, trackColor = Surface3) }
                }
                if (d.files.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No files yet",
                            message = "Tap Upload files to back up photos, videos and documents.",
                            icon = Icons.Default.CloudUpload,
                        )
                    }
                }
                items(d.files, key = { it.name }) { f ->
                    FileRow(
                        f,
                        onOpen = {
                            scope.launch {
                                runCatching { Repo.cloudFileUrl(f.name) }
                                    .onSuccess { url ->
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                            .onFailure { Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show() }
                                    }
                                    .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                            }
                        },
                        onDelete = { deleting = f },
                    )
                }
            }
        }
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete file?") },
            text = { Text("\"${f.displayName}\" will be permanently deleted from your Cloud Storage.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { Repo.deleteCloudFile(f.name) }
                            .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                        version++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(f: CloudFile, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Surface1)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(iconFor(f), null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatBytes(f.size)} · ${formatDate(f.createdAt)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = TextMuted) }
    }
}

private fun iconFor(f: CloudFile): ImageVector {
    val m = f.mimetype.orEmpty()
    val n = f.displayName.lowercase()
    return when {
        m.startsWith("image") -> Icons.Default.Image
        m.startsWith("video") -> Icons.Default.VideoFile
        m.startsWith("audio") -> Icons.Default.AudioFile
        m.contains("pdf") || n.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        m.contains("zip") || n.endsWith(".zip") || n.endsWith(".rar") -> Icons.Default.FolderZip
        m.startsWith("text") || n.endsWith(".doc") || n.endsWith(".docx") -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100 || v % 1.0 == 0.0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}
