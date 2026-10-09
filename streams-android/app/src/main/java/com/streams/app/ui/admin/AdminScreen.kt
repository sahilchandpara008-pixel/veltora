package com.streams.app.ui.admin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.AdminEmail
import com.streams.app.data.Campaign
import com.streams.app.data.Channel
import com.streams.app.data.Payment
import com.streams.app.data.PaymentSettings
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.currentEmail
import com.streams.app.data.friendly
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.components.formatPrice
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

@Composable
fun AdminScreen(nav: NavController) {
    val session by AppState.session.collectAsStateWithLifecycle()
    if (session !is SessionStatus.Authenticated) {
        // No session at all → go to the admin sign-in page.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            nav.navigate("admin-login") { popUpTo("admin") { inclusive = true } }
        }
        return
    }
    val role = rememberLoad(currentEmail()) { Repo.myAdminRole() }
    when (val r = role.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(r.message, role.reload)
        is Load.Ok -> if (r.data == null) NotFound(nav) else AdminHome(nav, isOwner = r.data == "owner")
    }
}

/** Shown to signed-in customers — never hints that an admin area exists. */
@Composable
private fun NotFound(nav: NavController) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Page not found", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }) { Text("Go home") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminHome(nav: NavController, isOwner: Boolean) {
    val tabs = buildList {
        add("Overview"); add("Attribution"); add("Payments"); add("Content"); add("Channels"); add("Campaigns")
        if (isOwner) { add("Team"); add("Settings") }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Streams Admin", style = MaterialTheme.typography.titleLarge)
                Text("${currentEmail()} · ${if (isOwner) "Owner" else "Manager"}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching { Repo.signOut() }
                    AppState.refreshAccess()
                    nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                }
            }) { Text("Sign out") }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Color.Transparent) {
            tabs.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
        }
        Box(Modifier.weight(1f)) {
            when (tabs[tab]) {
                "Overview" -> OverviewTab()
                "Attribution" -> AttributionTab()
                "Payments" -> PaymentsTab()
                "Content" -> ContentTab(nav)
                "Channels" -> ChannelsTab()
                "Campaigns" -> CampaignsTab()
                "Team" -> TeamTab()
                "Settings" -> SettingsTab()
            }
        }
    }
}

// ------------------------------------------------------------------ Overview

@Composable
private fun OverviewTab() {
    val load = rememberLoad(Unit) { Repo.adminStats() }
    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val st = s.data
            val cards = listOf(
                "Total videos" to st.totalVideos, "Exclusive videos" to st.exclusiveVideos,
                "Total views" to st.totalViews, "Preview views" to st.previewViews,
                "Paying subscribers" to st.payingSubscribers, "Expired subscribers" to st.expiredSubscribers,
                "Payments waiting" to st.pendingPayments,
            )
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cards) { (label, value) ->
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp)) {
                        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Payments

@Composable
private fun PaymentsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pending = order created / UPI app answered but not verified yet. "UTR review" = customer typed a UTR.
    val filters = mapOf(
        "All" to null, "Pending" to "initiated", "Success" to "approved", "Failed" to "failed",
        "Cancelled" to "cancelled", "UTR review" to "pending", "Rejected" to "rejected", "Revoked" to "revoked",
    )
    var filter by rememberSaveable { mutableStateOf("All") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf<Payment?>(null) }
    var revoking by remember { mutableStateOf<Payment?>(null) }
    var verifying by remember { mutableStateOf<Payment?>(null) }
    val load = rememberLoad(filter) { Repo.adminPayments(filters[filter]) }

    Column {
        ChipRow(filters.keys.toList(), filter, { filter = it }, Modifier.padding(vertical = 10.dp))
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (s.data.isEmpty()) item { EmptyState("Nothing here.") }
                items(s.data, key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.userEmail ?: "—", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatPrice(p.amount), style = MaterialTheme.typography.titleMedium)
                        }
                        Text("${p.planName} · Order ${p.reference}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            p.displayStatus,
                            style = MaterialTheme.typography.labelLarge,
                            color = when (p.status) {
                                "approved" -> Green
                                "initiated", "pending" -> Amber
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        p.txnId?.let { Text("UTR / provider txn: $it", style = MaterialTheme.typography.bodySmall, color = Amber) }
                        p.upiStatusLabel?.let {
                            Text("UPI app said: $it (not proof of payment)", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Created ${formatDate(p.createdAt)}" + (p.updatedAt?.let { " · Updated ${formatDate(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        p.verifiedAt?.let {
                            Text("Verified ${formatDate(it)} · ${p.verificationSource ?: "—"}", style = MaterialTheme.typography.bodySmall, color = Green)
                        }
                        if (p.status == "initiated") {
                            Text("Waiting for verified payment — not activated", style = MaterialTheme.typography.bodySmall, color = Amber)
                        }
                        if (p.status == "rejected" || p.status == "revoked") p.adminNote?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                        if (p.status == "pending") {
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BusyButton("Approve", busyId == p.id, Modifier.weight(1f)) {
                                    busyId = p.id
                                    scope.launch {
                                        runCatching { Repo.approvePayment(p.id) }
                                            .onSuccess { Toast.makeText(context, "Approved — subscription extended", Toast.LENGTH_SHORT).show() }
                                            .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                                        busyId = null
                                        load.reload()
                                    }
                                }
                                OutlinedButton(onClick = { rejecting = p }, modifier = Modifier.weight(1f)) { Text("Reject") }
                            }
                        } else {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    p.reviewedBy?.let { "By $it" } ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                // Money never arrived? Take the plan days back.
                                if (p.status == "approved") TextButton(onClick = { revoking = p }) { Text("Revoke") }
                                // Money is in the bank statement but the order is still open: activate with the UTR as evidence.
                                if (p.status in listOf("initiated", "failed", "cancelled")) {
                                    TextButton(onClick = { verifying = p }) { Text("Mark as paid") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    verifying?.let { p ->
        var utr by remember { mutableStateOf(p.txnId.orEmpty()) }
        var err by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { verifying = null },
            title = { Text("Mark as paid?") },
            text = {
                Column {
                    Text("Order ${p.reference} · ${formatPrice(p.amount)} · ${p.userEmail ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Only do this if you can see exactly ${formatPrice(p.amount)} credited in your bank statement. " +
                            "Type that credit's UTR — each UTR can be used once.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedTextField(utr, { utr = it.trim() }, label = { Text("Bank UTR (12 digits)") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching { Repo.adminVerifyPayment(p.id, utr) }
                            .onSuccess {
                                verifying = null
                                Toast.makeText(context, "Verified — plan activated", Toast.LENGTH_SHORT).show()
                                load.reload()
                            }
                            .onFailure { err = it.friendly() }
                        saving = false
                    }
                }) { Text("Activate plan") }
            },
            dismissButton = { TextButton(onClick = { verifying = null }) { Text("Cancel") } },
        )
    }

    rejecting?.let { p ->
        var note by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text("Reject payment?") },
            text = {
                Column {
                    Text("UTR ${p.reference} · ${formatPrice(p.amount)}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(note, { note = it }, label = { Text("Reason (shown to customer)") }, modifier = Modifier.padding(top = 8.dp))
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (note.trim().length < 3) { err = "Please type a short reason"; return@TextButton }
                    scope.launch {
                        runCatching { Repo.rejectPayment(p.id, note) }
                            .onSuccess { rejecting = null; load.reload() }
                            .onFailure { err = it.friendly() }
                    }
                }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } },
        )
    }

    revoking?.let { p ->
        var note by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke payment?") },
            text = {
                Column {
                    Text(
                        "UTR ${p.utr} · ${formatPrice(p.amount)}\nThe plan's days are taken back from ${p.userEmail ?: "this customer"}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(note, { note = it }, label = { Text("Reason (shown to customer)") }, modifier = Modifier.padding(top = 8.dp))
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (note.trim().length < 3) { err = "Please type a short reason"; return@TextButton }
                    scope.launch {
                        runCatching { Repo.revokePayment(p.id, note) }
                            .onSuccess { revoking = null; load.reload() }
                            .onFailure { err = it.friendly() }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Content

@Composable
private fun ContentTab(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load = rememberLoad(version) { Repo.titles() to Repo.channels() }
    var deleting by remember { mutableStateOf<Title?>(null) }

    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
        version++
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = load.state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(s.message, load.reload)
            is Load.Ok -> {
                val (titles, channels) = s.data
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (titles.isEmpty()) item { EmptyState("No titles yet. Tap Upload to add your first one.") }
                    items(titles, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(48.dp, 72.dp).clip(RoundedCornerShape(6.dp)).background(Surface2)) {
                                val url = imageUrl(t.coverPath)
                                if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(Icons.Default.Movie, null, tint = TextMuted, modifier = Modifier.align(Alignment.Center))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (t.isSeries) "Series" else "Movie") + " · " + (channels.firstOrNull { it.id == t.channelId }?.name ?: "No channel"),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                )
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        if (t.published) "LIVE" else "DRAFT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (t.published) Green.copy(alpha = 0.8f) else Color(0xFF52525B))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .clickableNoRipple { act { Repo.setPublished(t.id, !t.published) } },
                                    )
                                    // Tap to cycle Free → Premium → Exclusive → Free
                                    TierBadge(t.tier, onClick = { act { Repo.setTier(t.id, Tier.next(t.tier)) } })
                                }
                            }
                            IconButton(onClick = { act { Repo.setFeatured(t.id, !t.featured) } }) {
                                Icon(if (t.featured) Icons.Default.Star else Icons.Default.StarBorder, "Feature on Home", tint = if (t.featured) Amber else TextMuted)
                            }
                            IconButton(onClick = { nav.navigate(Routes.edit(t.id)) }) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton(onClick = { deleting = t }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.navigate(Routes.edit(null)) },
            containerColor = Red,
            contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Upload") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${t.name}\"?") },
            text = { Text("This removes the title, its episodes and video files. It can't be undone.") },
            confirmButton = { TextButton(onClick = { deleting = null; act { Repo.deleteTitle(t) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Channels

@Composable
private fun ChannelsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load = rememberLoad(version) { Repo.channels() }
    var editing by remember { mutableStateOf<Channel?>(null) }

    SimpleListTab(
        state = load.state, reload = load.reload, addLabel = "New channel",
        onAdd = { editing = Channel(name = "") },
    ) { list: List<Channel> ->
        items(list, key = { it.id }) { ch ->
            ListCard(
                title = ch.name,
                subtitle = ch.description.ifBlank { "No description" },
                badge = if (ch.isPremium) "Premium" else null,
                onEdit = { editing = ch },
                onDelete = {
                    scope.launch {
                        runCatching { Repo.deleteChannel(ch.id) }.onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                        version++
                    }
                },
            )
        }
    }

    editing?.let { ch ->
        var name by remember(ch) { mutableStateOf(ch.name) }
        var desc by remember(ch) { mutableStateOf(ch.description) }
        var premium by remember(ch) { mutableStateOf(ch.isPremium) }
        EditDialog(
            title = if (ch.id.isEmpty()) "New channel" else "Edit channel",
            onDismiss = { editing = null },
            onSave = {
                Repo.saveChannel(ch.id.ifEmpty { null }, name, desc, premium)
                editing = null; version++
            },
            canSave = name.isNotBlank(),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("Short description") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            SwitchRow("Contains premium content", premium) { premium = it }
        }
    }
}

// ------------------------------------------------------------------ Campaigns

@Composable
private fun CampaignsTab() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load = rememberLoad(version) { Repo.campaigns() }
    var editing by remember { mutableStateOf<Campaign?>(null) }

    SimpleListTab(
        state = load.state, reload = load.reload, addLabel = "New campaign",
        onAdd = { editing = Campaign(name = "") },
        header = "The newest active campaign shows as a banner at the top of Home.",
    ) { list: List<Campaign> ->
        items(list, key = { it.id }) { c ->
            ListCard(
                title = c.name,
                subtitle = c.message.ifBlank { "—" },
                badge = if (c.active) "Active" else "Off",
                onEdit = { editing = c },
                onDelete = { scope.launch { runCatching { Repo.deleteCampaign(c.id) }; version++ } },
            )
        }
    }

    editing?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var msg by remember(c) { mutableStateOf(c.message) }
        var active by remember(c) { mutableStateOf(c.active) }
        EditDialog(
            title = if (c.id.isEmpty()) "New campaign" else "Edit campaign",
            onDismiss = { editing = null },
            onSave = {
                Repo.saveCampaign(c.id.ifEmpty { null }, name, msg, active)
                editing = null; version++
            },
            canSave = name.isNotBlank(),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Headline") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(msg, { msg = it }, label = { Text("Message") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            SwitchRow("Show on Home", active) { active = it }
        }
    }
}

// ------------------------------------------------------------------ Team (owner only)

@Composable
private fun TeamTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load = rememberLoad(version) { Repo.team() }
    var adding by remember { mutableStateOf(false) }

    SimpleListTab(
        state = load.state, reload = load.reload, addLabel = "Add manager",
        onAdd = { adding = true },
        header = "Managers can upload content and verify payments. They sign in on the admin page with " +
            "\"Forgot password? / Set first password\" using the email you add here.",
    ) { list: List<AdminEmail> ->
        items(list, key = { it.email }) { a ->
            ListCard(
                title = a.email,
                subtitle = if (a.role == "owner") "Owner" else "Manager",
                badge = null,
                onEdit = null,
                onDelete = if (a.role == "manager") ({
                    scope.launch {
                        runCatching { Repo.removeManager(a.email) }.onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                        version++
                    }
                }) else null,
            )
        }
    }

    if (adding) {
        var email by remember { mutableStateOf("") }
        EditDialog(
            title = "Add manager",
            onDismiss = { adding = false },
            onSave = { Repo.addManager(email); adding = false; version++ },
            canSave = email.contains("@"),
        ) {
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ Settings (owner only)

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad(Unit) { Repo.paymentSettings() }
    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            var upi by remember { mutableStateOf(s.data.upiId) }
            var payee by remember { mutableStateOf(s.data.payeeName) }
            var mcc by remember { mutableStateOf(s.data.merchantCode.orEmpty()) }
            var busy by remember { mutableStateOf(false) }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Payments: a plan turns on only after the payment is verified. Open Payments → Pending, match the " +
                        "order with your bank statement and tap “Mark as paid” with the bank UTR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
                Spacer(Modifier.size(8.dp))
                Text("UPI payment details", style = MaterialTheme.typography.titleMedium)
                Text("Customers pay to this UPI ID. Changes apply instantly.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(upi, { upi = it.trim() }, label = { Text("UPI ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(payee, { payee = it }, label = { Text("Payee name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BusyButton("Save", busy) {
                    if (!upi.contains("@") || payee.isBlank()) {
                        Toast.makeText(context, "Enter a valid UPI ID (like name@bank) and a name", Toast.LENGTH_LONG).show(); return@BusyButton
                    }
                    busy = true
                    scope.launch {
                        runCatching { Repo.saveSettings(PaymentSettings(upi, payee, merchantCode = mcc)) }
                            .onSuccess { Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                        busy = false
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ shared admin widgets

@Composable
private fun <T> SimpleListTab(
    state: Load<List<T>>,
    reload: () -> Unit,
    addLabel: String,
    onAdd: () -> Unit,
    header: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.(List<T>) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            Load.Loading -> Loading()
            is Load.Err -> ErrorState(state.message, reload)
            is Load.Ok -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                header?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                if (state.data.isEmpty()) item { EmptyState("Nothing yet.") }
                content(state.data)
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            containerColor = Red,
            contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text(addLabel) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun ListCard(title: String, subtitle: String, badge: String?, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                badge?.let { Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = Red) }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        onEdit?.let { IconButton(onClick = it) { Icon(Icons.Default.Edit, "Edit") } }
        onDelete?.let { IconButton(onClick = it) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun EditDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: suspend () -> Unit,
    canSave: Boolean,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { scope.launch { runCatching { onSave() }.onFailure { err = it.friendly() } } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Red))
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) =
    clickable(interactionSource = null, indication = null, onClick = onClick)
