package com.streams.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streams.app.Load
import com.streams.app.data.AttributedUser
import com.streams.app.data.AttributionRow
import com.streams.app.data.Repo
import com.streams.app.rememberLoad
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.components.formatPrice
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.TextMuted
import java.time.OffsetDateTime

private val ranges = linkedMapOf("7 days" to 7L, "30 days" to 30L, "90 days" to 90L, "All time" to null)
private val sources = linkedMapOf(
    "All sources" to null, "Meta" to "meta", "Organic" to "organic",
    "Direct" to "direct", "Unknown" to "unknown", "Other" to "other",
)
private val purchaseFilters = linkedMapOf("Everyone" to null, "Purchased" to true, "Not purchased" to false)

private fun sourceLabel(s: String?) = when (s) {
    "meta" -> "Meta"; "organic" -> "Organic"; "direct" -> "Direct"; "other" -> "Other"; else -> "Unknown"
}

private fun pct(part: Long, whole: Long) = if (whole <= 0) "—" else "%.0f%%".format(part * 100.0 / whole)

/** Admin → Attribution: acquisition source, campaign performance, purchase attribution, users. */
@Composable
internal fun AttributionTab() {
    var range by rememberSaveable { mutableStateOf("30 days") }
    var source by rememberSaveable { mutableStateOf("All sources") }
    var purchase by rememberSaveable { mutableStateOf("Everyone") }
    var planName by rememberSaveable { mutableStateOf("All plans") }
    var campaign by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var detailFor by remember { mutableStateOf<String?>(null) }

    val plans = rememberLoad(Unit) { Repo.plans() }
    val planList = (plans.state as? Load.Ok)?.data.orEmpty()
    val planId = planList.firstOrNull { it.name == planName }?.id
    val from = ranges[range]?.let { OffsetDateTime.now().minusDays(it).toString() }
    val src = sources[source]

    val report = rememberLoad(range, source, campaign, planId) { Repo.attributionReport(from, src, campaign, planId) }
    val users = rememberLoad(range, source, campaign, planId, purchase, search) {
        Repo.attributionUsers(from, src, campaign, purchaseFilters[purchase], planId, search)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipRow(ranges.keys.toList(), range, { range = it })
                ChipRow(sources.keys.toList(), source, { source = it })
                ChipRow(listOf("All plans") + planList.map { it.name }, planName, { planName = it })
                campaign?.let {
                    Text(
                        "Campaign: $it   ✕",
                        style = MaterialTheme.typography.labelLarge,
                        color = Amber,
                        modifier = Modifier.padding(horizontal = 16.dp).clickable { campaign = null },
                    )
                }
            }
        }

        when (val r = report.state) {
            Load.Loading -> item { Loading(Modifier.heightIn(min = 120.dp)) }
            is Load.Err -> item { ErrorState(r.message, report.reload) }
            is Load.Ok -> {
                item { SectionTitle("By source") }
                if (r.data.bySource.isEmpty()) item { EmptyState("No installs, sign-ups or purchases in this range yet.") }
                items(r.data.bySource, key = { "s_" + it.source }) { SourceCard(it) }
                item { SectionTitle("Campaigns") }
                if (r.data.campaigns.isEmpty()) {
                    item {
                        Text(
                            "No campaign data yet. Campaigns appear when people install from a tracked Play Store link.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(r.data.campaigns, key = { "c_" + it.source + it.campaign }) { row ->
                    SourceCard(row, onClick = { campaign = row.campaign })
                }
            }
        }

        item { SectionTitle("Users") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipRow(purchaseFilters.keys.toList(), purchase, { purchase = it })
                OutlinedTextField(
                    search, { search = it.trim() },
                    label = { Text("Search e-mail") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        when (val u = users.state) {
            Load.Loading -> item { Loading(Modifier.heightIn(min = 120.dp)) }
            is Load.Err -> item { ErrorState(u.message, users.reload) }
            is Load.Ok -> {
                if (u.data.isEmpty()) item { EmptyState("No users match these filters.") }
                items(u.data, key = { it.userId }) { UserRow(it) { detailFor = it.userId } }
            }
        }
    }

    detailFor?.let { id -> UserDetailDialog(id) { detailFor = null } }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
}

@Composable
private fun SourceCard(row: AttributionRow, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.campaign ?: sourceLabel(row.source),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatPrice(row.revenue), style = MaterialTheme.typography.titleMedium, color = Green)
        }
        if (row.campaign != null) Text(sourceLabel(row.source), style = MaterialTheme.typography.bodySmall)
        Text(
            "Installs ${row.installs} · Registrations ${row.registrations} · Purchasers ${row.purchasers}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Install → registration ${pct(row.registrations, row.installs)} · " +
                "Registration → purchase ${pct(row.purchasers, row.registrations)}",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun UserRow(u: AttributedUser, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(u.email ?: u.userId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sourceLabel(u.source) + (u.campaign?.let { " · $it" } ?: "") + " · joined ${formatDate(u.registeredAt)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatPrice(u.revenue), style = MaterialTheme.typography.labelLarge, color = if (u.revenue > 0) Green else TextMuted)
    }
}

@Composable
private fun UserDetailDialog(userId: String, onDismiss: () -> Unit) {
    val load = rememberLoad(userId) { Repo.userDetail(userId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("User") },
        text = {
            when (val s = load.state) {
                Load.Loading -> Loading(Modifier.heightIn(min = 120.dp))
                is Load.Err -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is Load.Ok -> {
                    val d = s.data
                    val a = d.attribution
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Line("User ID", d.userId)
                        Line("E-mail", d.email ?: "—")
                        Line("Registered", formatDate(d.registeredAt))
                        Line("Acquisition", sourceLabel(a?.firstTouchSource))
                        Line("Campaign", a?.firstTouchCampaign ?: "—")
                        Line("Ad / content", a?.firstTouchContent ?: "—")
                        Line("First touch", a?.firstTouchAt?.let { formatDate(it) } ?: "—")
                        Line("Last touch", a?.lastTouchAt?.let { formatDate(it) + " · " + sourceLabel(a.lastTouchSource) } ?: "—")
                        Line("Subscription", d.subscription?.status?.replaceFirstChar { it.uppercase() } ?: "None")
                        Line("Current plan", d.subscription?.plan ?: "—")
                        Line("Expires", d.subscription?.expiresAt?.let { formatDate(it) } ?: "—")
                        Line("Total revenue", formatPrice(d.revenue))
                        Text("Purchases", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        if (d.payments.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall)
                        d.payments.forEach { p ->
                            Text(
                                "${formatDate(p.createdAt)} · ${p.plan ?: "Plan"} · ${formatPrice(p.amount)} · " +
                                    p.status.replaceFirstChar { it.uppercase() } +
                                    (p.attributionSource?.let { " · ${sourceLabel(it)}" } ?: "") +
                                    (p.attributionCampaign?.let { " / $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
