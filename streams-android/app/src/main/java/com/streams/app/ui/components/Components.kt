package com.streams.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Gold
import com.streams.app.ui.theme.Outline
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.RedGradient
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.Surface3
import com.streams.app.ui.theme.TextFaint
import com.streams.app.ui.theme.TextMuted

// ---------------------------------------------------------------- badges

@Composable
fun TierBadge(tier: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (bg, fg) = when (tier) {
        Tier.FREE -> Color(0xFF14532D) to Color(0xFF86EFAC)
        Tier.PREMIUM -> Red to Color.White
        else -> Gold to Color.Black
    }
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tier != Tier.FREE) {
            Icon(Icons.Default.WorkspacePremium, null, tint = fg, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(Tier.label(tier).uppercase(), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

// ---------------------------------------------------------------- posters

/** Poster that stretches to whatever width its parent gives it, with a gentle press animation. */
@Composable
fun PosterCard(title: Title, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "posterScale")
    Column(
        modifier
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small)
                .background(Surface2),
        ) {
            val url = imageUrl(title.coverPath)
            if (url != null) {
                AsyncImage(url, title.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                PosterPlaceholder(title.name)
            }
            if (title.tier != Tier.FREE) TierBadge(title.tier, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Text(
            title.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Shown when a title has no cover yet: its initials over a soft gradient, instead of a blank box. */
@Composable
fun PosterPlaceholder(name: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Surface3, Surface1))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Movie, null, tint = TextFaint, modifier = Modifier.size(28.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- headers, chips, buttons

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(Red))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onSeeAll != null) {
            Row(
                Modifier.clip(CircleShape).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sidePadding: Dp = 16.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            val bg by animateColorAsState(if (on) Color.White else Surface1, label = "chipBg")
            val fg by animateColorAsState(if (on) Color.Black else Color.White, label = "chipFg")
            Text(
                o,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.dp, if (on) Color.White else Outline, CircleShape)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Main call-to-action: red gradient, rounded, full width by default. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "btnScale")
    Row(
        modifier
            .scale(scale)
            .height(50.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (enabled) RedGradient else Brush.horizontalGradient(listOf(Surface3, Surface3)))
            .clickable(enabled = enabled && !loading, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else TextMuted, maxLines = 1)
        }
    }
}

/** Secondary action with the same shape as [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.height(50.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

// ---------------------------------------------------------------- loading & states

/** Animated placeholder shimmer. */
@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    return Brush.linearGradient(
        listOf(Surface1, Surface2, Surface1),
        start = androidx.compose.ui.geometry.Offset(x, 0f),
        end = androidx.compose.ui.geometry.Offset(x + 400f, 400f),
    )
}

@Composable
fun SkeletonBox(modifier: Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.small).background(shimmerBrush()))
}

/** Skeleton for a row of posters while content loads. */
@Composable
fun PosterRowSkeleton() {
    Column(Modifier.padding(top = 20.dp)) {
        SkeletonBox(Modifier.padding(horizontal = 16.dp).size(width = 140.dp, height = 18.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { SkeletonBox(Modifier.width(112.dp).aspectRatio(2f / 3f)) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Red, strokeWidth = 3.dp) }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    StateMessage(
        icon = Icons.Default.CloudOff,
        title = "Couldn't load this",
        message = message,
        action = "Try again",
        onAction = onRetry,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = Icons.Default.Movie,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateMessage(icon, title, message, action, onAction, modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
private fun StateMessage(
    icon: ImageVector,
    title: String?,
    message: String,
    action: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Surface1),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = TextMuted, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(action, onAction, Modifier)
        }
    }
}

@Composable
fun LockedNote(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Benefits every premium plan includes (shown on Profile above the plans, and on Cloud Storage). */
val PremiumBenefits = listOf(
    "Ad-Free Experience",
    "Access 2 TB Cloud Storage",
    "Fast Upload & Download Speed",
)

@Composable
fun PremiumBenefitsCard(modifier: Modifier = Modifier, benefits: List<String> = PremiumBenefits) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.verticalGradient(listOf(Red.copy(alpha = 0.16f), Surface1)))
            .border(1.dp, Red.copy(alpha = 0.35f), MaterialTheme.shapes.medium),
    ) {
        // thin red strip across the top, like the reference design
        Box(Modifier.fillMaxWidth().height(4.dp).background(RedGradient))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Red).padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.WorkspacePremium, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Premium", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            benefits.forEach { b ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Red, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(b, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
        }
    }
}

/** Rounded surface card used across screens. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Surface1)
            .border(1.dp, Outline.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(padding),
    ) { content() }
}

/** Small "Movie · 1h 20m"-style metadata line built from non-empty parts. */
@Composable
fun MetaLine(parts: List<String?>, modifier: Modifier = Modifier) {
    Text(
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  •  "),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

@Composable
fun RowScope.WeightSpacer() = Spacer(Modifier.weight(1f))

val ScreenPadding = PaddingValues(horizontal = 16.dp)

/** Soft fade from transparent to the page background, for text over images. */
val BottomScrim = Brush.verticalGradient(0.35f to Color.Transparent, 1f to Bg)

fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${maxOf(m, 1)}m"
}

fun formatDate(iso: String): String = runCatching {
    val instant = java.time.OffsetDateTime.parse(iso)
    instant.atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
}.getOrDefault(iso.take(10))

fun formatPrice(amount: Double?): String =
    if (amount == null) "" else "₹" + (if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount))
