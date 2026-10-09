package com.streams.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streams.app.R

// ---------------------------------------------------------------- palette
val Bg = Color(0xFF09090B)
val Surface1 = Color(0xFF141418)
val Surface2 = Color(0xFF1F1F25)
val Surface3 = Color(0xFF2A2A31)
val Outline = Color(0xFF2E2E36)
val Red = Color(0xFFE50914)
val RedDark = Color(0xFFB20710)
val TextMain = Color(0xFFF5F5F6)
val TextMuted = Color(0xFFA1A1AA)
val TextFaint = Color(0xFF71717A)
val Green = Color(0xFF22C55E)
val Amber = Color(0xFFF59E0B)
val Gold = Color(0xFFFBBF24)

/** Primary call-to-action gradient (used sparingly: Play / Subscribe). */
val RedGradient = Brush.horizontalGradient(listOf(Red, Color(0xFFFF3B30)))

// ---------------------------------------------------------------- type
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Int, color: Color = Color.Unspecified, spacing: Double = 0.0) =
    TextStyle(fontFamily = Poppins, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, color = color, letterSpacing = spacing.sp)

private val type = Typography(
    displaySmall = style(30, FontWeight.ExtraBold, 36),
    headlineMedium = style(24, FontWeight.Bold, 30),
    headlineSmall = style(20, FontWeight.Bold, 26),
    titleLarge = style(18, FontWeight.SemiBold, 24),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.Medium, 19),
    bodyLarge = style(15, FontWeight.Normal, 22),
    bodyMedium = style(14, FontWeight.Normal, 20, TextMuted),
    bodySmall = style(12, FontWeight.Normal, 17, TextMuted),
    labelLarge = style(15, FontWeight.SemiBold, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(10, FontWeight.Bold, 14, spacing = 0.6),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val colors = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = RedDark,
    onPrimaryContainer = Color.White,
    secondary = Red,
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = Outline,
    outlineVariant = Outline,
    error = Color(0xFFF87171),
)

@Composable
fun StreamsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)
}
