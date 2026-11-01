package net.paramnezia.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colours: the same in every phone, so the app (and its button) is recognisable at a glance. */
object Brand {
    val Violet = Color(0xFF7C4DFF)
    val Indigo = Color(0xFF3D5AFE)
    val Cyan = Color(0xFF00E5FF)
    val Mint = Color(0xFF00E6A8)
    val Lime = Color(0xFF76FF7A)
    val Amber = Color(0xFFFFB300)
    val Coral = Color(0xFFFF5370)
    val Night = Color(0xFF0B0E1A)
    val NightSurface = Color(0xFF151A2C)
    val NightSurfaceHigh = Color(0xFF1E2540)
    val Slate = Color(0xFF8A93B2)

    /** Button and widget gradients per state. */
    val On = listOf(Mint, Cyan)
    val Connecting = listOf(Amber, Coral)
    val Off = listOf(Color(0xFF39405C), Color(0xFF262C45))
    val OffLight = listOf(Color(0xFFD9DCEA), Color(0xFFC3C8DD))
}

private val DarkColors = darkColorScheme(
    primary = Brand.Cyan,
    onPrimary = Color(0xFF00222A),
    primaryContainer = Color(0xFF003B49),
    onPrimaryContainer = Color(0xFFB4F4FF),
    secondary = Brand.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E2463),
    onSecondaryContainer = Color(0xFFE3DCFF),
    tertiary = Brand.Mint,
    background = Brand.Night,
    onBackground = Color(0xFFE7EAF6),
    surface = Brand.Night,
    onSurface = Color(0xFFE7EAF6),
    surfaceVariant = Brand.NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFBAC1DC),
    surfaceContainerLowest = Color(0xFF080A14),
    surfaceContainerLow = Color(0xFF111526),
    surfaceContainer = Brand.NightSurface,
    surfaceContainerHigh = Brand.NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF283052),
    outline = Brand.Slate,
    outlineVariant = Color(0xFF353D5E),
    error = Brand.Coral,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F4FF),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF5B3DD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF1B0A5E),
    tertiary = Color(0xFF00876A),
    background = Color(0xFFF6F7FC),
    surface = Color(0xFFF6F7FC),
    surfaceContainerLow = Color(0xFFF0F2FA),
    surfaceContainer = Color(0xFFE9ECF7),
    surfaceContainerHigh = Color(0xFFE2E6F4),
    outline = Color(0xFF6B7391),
    error = Color(0xFFD32F4F),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Numbers that tick (traffic, timer) keep their width. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun PnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppType,
        content = content,
    )
}
