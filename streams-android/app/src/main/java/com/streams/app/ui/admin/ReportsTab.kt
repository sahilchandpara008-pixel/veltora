package com.streams.app.ui.admin

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.streams.app.Load
import com.streams.app.data.ReportReason
import com.streams.app.data.ReportRow
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import kotlinx.coroutines.launch

/** Admin → Reports: content reported by users. Unpublish the item from the Creators tab if needed. */
@Composable
fun ReportsTab(nav: NavController) {
    var filter by remember { mutableStateOf("Open") }
    var version by remember { mutableStateOf(0) }
    val load = rememberLoad(filter, version) {
        Repo.adminReports(when (filter) { "Open" -> "open"; "Resolved" -> "resolved"; else -> null })
    }
    var resolving by remember { mutableStateOf<ReportRow?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Open", "Resolved", "All"), filter, { filter = it }, Modifier.padding(vertical = 10.dp))
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.data.isEmpty()) item { EmptyState("No reports here.", icon = Icons.Default.Flag) }
                items(s.data, key = { it.id }) { r ->
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                (if (r.targetType == "channel") "Channel: " else "Video: ") + (r.targetName ?: "(deleted)"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (r.targetType == "title" && r.channelName != null) Text("In channel ${r.channelName}", style = MaterialTheme.typography.bodySmall)
                            Text("Owner: ${r.ownerEmail ?: "Admin content"}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                ReportReason.label(r.reason) + if (r.reportCount > 1) " · ${r.reportCount} open reports" else "",
                                color = if (r.status == "open") Amber else Green,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (r.details.isNotBlank()) Text("\"${r.details}\"", style = MaterialTheme.typography.bodySmall)
                            Text("By ${r.reporterEmail ?: "deleted user"} · ${r.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                            if (r.adminNote.isNotBlank()) Text("Note: ${r.adminNote}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (r.targetName != null) {
                                    TextButton(onClick = {
                                        nav.navigate(if (r.targetType == "channel") Routes.channel(r.targetId) else Routes.title(r.targetId))
                                    }) { Text("Open") }
                                }
                                if (r.status == "open") TextButton(onClick = { resolving = r }) { Text("Mark resolved", color = Green) }
                            }
                        }
                    }
                }
            }
        }
    }

    resolving?.let { r ->
        var note by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resolving = null },
            title = { Text("Mark report resolved") },
            text = {
                Column {
                    Text("To remove the content, unpublish it in the Creators tab.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    resolving = null
                    scope.launch {
                        runCatching { Repo.resolveReport(r.id, note) }
                            .onSuccess { version++ }
                            .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Resolve") }
            },
            dismissButton = { TextButton(onClick = { resolving = null }) { Text("Cancel") } },
        )
    }
}
