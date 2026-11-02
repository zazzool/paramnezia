package net.paramnezia.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Paramnezia's look: «Ирис и аврора».
 *
 * Iris (violet into azure) is the brand: the icon's own gradient, used for the signature button and
 * the accent of every screen. Aurora (mint into azure) means one thing only: "protected", so the
 * glowing power button is what people recognise at a glance. Everything else is quiet tinted
 * neutrals: graphite with a drop of iris at night, pearl by day, never pure black or pure grey.
 *
 * Both themes share one material metaphor: surfaces are machined slabs with a light catching their
 * top edge (see [edge]), wells for input are pressed in, icons are engraved and enamelled.
 */

/** Light, dark or as the system says; chosen in Settings and kept outside the encrypted state. */
enum class ThemeMode(val title: String, val short: String) { SYSTEM("Как в системе", "Авто"), LIGHT("Светлая", "Светлая"), DARK("Тёмная", "Тёмная") }

/**
 * The chosen [ThemeMode]. The platform loads it before the first frame and saves changes
 * ([save] is set by MainActivity on Android, by Main.kt on Windows), so the app never flashes the
 * wrong theme at start.
 */
object ThemePrefs {
    val mode = mutableStateOf(ThemeMode.SYSTEM)
    var save: (ThemeMode) -> Unit = {}

    fun set(m: ThemeMode) {
        mode.value = m
        runCatching { save(m) }
    }

    fun parse(s: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == s } ?: ThemeMode.SYSTEM
}

/** Colours beyond Material's roles: status accents and the tactile surfaces (slabs, engravings, discs). */
class PnPalette(
    val dark: Boolean,
    /** Protected, fine, done. */
    val ok: Color,
    /** Connecting, needs attention. */
    val warn: Color,
    /** Off, failed, missing. */
    val bad: Color,
    /** Download, links, the «Добавить» place. */
    val info: Color,
    /** The brand's iris: upload, «Семья», the admin's own device. */
    val iris: Color,
    /** The power button at rest and its glyph. */
    val offDisc: List<Color>,
    val offGlyph: Color,
    /** A convex slab: its lit outer edge and its deep inner side. */
    val slabHi: Color,
    val slabLo: Color,
    val rimAlpha: Float,
    val shadowAlpha: Float,
    /** The lips of an engraving: light below the cut, dark above. */
    val engraveHi: Color,
    val engraveLo: Color,
    /** Hairline on a card's edge: light from above, fading down. */
    val edgeTop: Color,
    val edgeBottom: Color,
    /** Soft lift under cards, in dp (light theme only; at night the edge light does it). */
    val liftDp: Float,
)

object Brand {
    // Fixed in every theme: these are the brand, recognisable on any phone and any screen.
    /** The icon's gradient: iris into azure. */
    val Signature = listOf(Color(0xFF7B5CFF), Color(0xFF3D7BFF))
    /** Aurora: protected. */
    val On = listOf(Color(0xFF2FE6B5), Color(0xFF1AB8F2))
    val Connecting = listOf(Color(0xFFFFC15E), Color(0xFFFF7A6B))

    // Status accents, tuned per theme for contrast (bright at night, deeper by day).
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalPn.current.ok
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalPn.current.warn
    val Coral: Color @Composable @ReadOnlyComposable get() = LocalPn.current.bad
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalPn.current.info
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalPn.current.iris
}

private val NightPalette = PnPalette(
    dark = true,
    ok = Color(0xFF4DE8BC),
    warn = Color(0xFFFFC15E),
    bad = Color(0xFFFF7A88),
    info = Color(0xFF6CC4FF),
    iris = Color(0xFFA99BFF),
    offDisc = listOf(Color(0xFF34323F), Color(0xFF1E1D27)),
    offGlyph = Color(0xFFA9A6BA),
    slabHi = Color(0xFF2E2C3A),
    slabLo = Color(0xFF15141C),
    rimAlpha = 0.11f,
    shadowAlpha = 0.55f,
    engraveHi = Color.White.copy(alpha = 0.07f),
    engraveLo = Color.Black.copy(alpha = 0.55f),
    edgeTop = Color.White.copy(alpha = 0.09f),
    edgeBottom = Color.White.copy(alpha = 0.015f),
    liftDp = 0f,
)

private val PearlPalette = PnPalette(
    dark = false,
    ok = Color(0xFF00936C),
    warn = Color(0xFFB86E00),
    bad = Color(0xFFD23452),
    info = Color(0xFF1D72D8),
    iris = Color(0xFF5A48E6),
    offDisc = listOf(Color(0xFFFFFFFF), Color(0xFFE2DFEC)),
    offGlyph = Color(0xFF5D5A6E),
    slabHi = Color(0xFFFFFFFF),
    slabLo = Color(0xFFDCD9E6),
    rimAlpha = 0.95f,
    shadowAlpha = 0.10f,
    engraveHi = Color.White.copy(alpha = 0.85f),
    engraveLo = Color(0xFF2A2340).copy(alpha = 0.20f),
    edgeTop = Color.White,
    edgeBottom = Color(0xFFE3E0EB),
    liftDp = 1.5f,
)

val LocalPn = staticCompositionLocalOf { PearlPalette }

/** The palette of the current theme. */
val Pn: PnPalette @Composable @ReadOnlyComposable get() = LocalPn.current

/** True in the dark theme, whichever way it was chosen (use this, not isSystemInDarkTheme). */
@Composable
@ReadOnlyComposable
fun isDark(): Boolean = LocalPn.current.dark

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF1D1065),
    primaryContainer = Color(0xFF3A2E8C),
    onPrimaryContainer = Color(0xFFE4DEFF),
    inversePrimary = Color(0xFF5A48E6),
    secondary = Color(0xFF7CC4FF),
    onSecondary = Color(0xFF00315A),
    secondaryContainer = Color(0xFF203655),
    onSecondaryContainer = Color(0xFFD3E6FF),
    tertiary = Color(0xFF4DE8BC),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF0F4A3B),
    onTertiaryContainer = Color(0xFFA8F7DC),
    background = Color(0xFF0E0D14),
    onBackground = Color(0xFFEDEBF5),
    surface = Color(0xFF0E0D14),
    onSurface = Color(0xFFEDEBF5),
    surfaceVariant = Color(0xFF25242F),
    onSurfaceVariant = Color(0xFFA9A6BA),
    surfaceTint = Color(0xFFA99BFF),
    inverseSurface = Color(0xFFEDEBF5),
    inverseOnSurface = Color(0xFF1B1A22),
    surfaceDim = Color(0xFF0E0D14),
    surfaceBright = Color(0xFF34333F),
    surfaceContainerLowest = Color(0xFF09080D),
    surfaceContainerLow = Color(0xFF131219),
    surfaceContainer = Color(0xFF18171F),
    surfaceContainerHigh = Color(0xFF211F2A),
    surfaceContainerHighest = Color(0xFF2B2935),
    outline = Color(0xFF6E6B80),
    outlineVariant = Color(0xFF2E2D39),
    error = Color(0xFFFF7A88),
    onError = Color(0xFF4A0012),
    errorContainer = Color(0xFF5C1424),
    onErrorContainer = Color(0xFFFFD9DD),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A48E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1E1066),
    inversePrimary = Color(0xFFA99BFF),
    secondary = Color(0xFF1D72D8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEAFF),
    onSecondaryContainer = Color(0xFF002A55),
    tertiary = Color(0xFF00936C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF5E7),
    onTertiaryContainer = Color(0xFF00382A),
    background = Color(0xFFF3F2F8),
    onBackground = Color(0xFF17161E),
    surface = Color(0xFFF3F2F8),
    onSurface = Color(0xFF17161E),
    surfaceVariant = Color(0xFFE6E4EE),
    onSurfaceVariant = Color(0xFF5D5A6E),
    surfaceTint = Color(0xFF5A48E6),
    inverseSurface = Color(0xFF2B2A33),
    inverseOnSurface = Color(0xFFF3F2F8),
    surfaceDim = Color(0xFFDEDCE6),
    surfaceBright = Color.White,
    // Cards are white on pearl; input wells sit a shade below them.
    surfaceContainerLowest = Color(0xFFF7F6FB),
    surfaceContainerLow = Color(0xFFFAF9FD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAE8F2),
    surfaceContainerHighest = Color(0xFFE2DFEB),
    outline = Color(0xFF8A8799),
    outlineVariant = Color(0xFFDAD7E3),
    error = Color(0xFFD23452),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E5),
    onErrorContainer = Color(0xFF5A0018),
    scrim = Color.Black,
)

/** Material roles of a theme (also used by the theme picker's previews). */
fun colorsFor(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Numbers that tick (traffic, timer) keep their width. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Whether the app is dark now: the user's choice, or the system's when they left it to the system. */
@Composable
fun resolveDark(mode: ThemeMode = ThemePrefs.mode.value): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PnTheme(dark: Boolean = resolveDark(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPn provides if (dark) NightPalette else PearlPalette) {
        MaterialTheme(
            colorScheme = colorsFor(dark),
            shapes = AppShapes,
            typography = AppType,
            content = content,
        )
    }
}

/**
 * The machined edge of a card: a hairline that catches light from above and fades downwards, plus
 * (by day) a soft tinted lift. It is what makes flat surfaces read as objects you can press.
 */
@Composable
fun Modifier.edge(shape: Shape): Modifier {
    val p = Pn
    val lifted = if (p.liftDp > 0f) this.shadow(p.liftDp.dp, shape, ambientColor = Color(0xFF3A2E8C), spotColor = Color(0xFF3A2E8C)) else this
    return lifted.border(BorderStroke(1.dp, Brush.verticalGradient(listOf(p.edgeTop, p.edgeBottom))), shape)
}

/**
 * «Оформление»: three tiles, each a miniature of the home dial in that theme (the system one is
 * split diagonally), so the choice is seen rather than read. The tile under the finger sinks.
 */
@Composable
fun ThemePicker(modifier: Modifier = Modifier) {
    val current = ThemePrefs.mode.value
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in ThemeMode.entries) {
            val on = m == current
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = "Тема: ${m.title}"; selected = on }
                    .edge(shape)
                    .clip(shape)
                    .tactile(role = Role.RadioButton) { ThemePrefs.set(m) }
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(0.82f).clip(RoundedCornerShape(14.dp))) {
                    when (m) {
                        ThemeMode.LIGHT -> miniature(false)
                        ThemeMode.DARK -> miniature(true)
                        ThemeMode.SYSTEM -> {
                            miniature(false)
                            clipPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }) { miniature(true) }
                        }
                    }
                }
                Text(
                    m.short, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** The home screen in miniature: background, the segmented ring and the glowing button. */
private fun DrawScope.miniature(dark: Boolean) {
    val c = colorsFor(dark)
    val p = if (dark) NightPalette else PearlPalette
    drawRect(c.background)
    val center = Offset(size.width / 2, size.height * 0.42f)
    val rOut = size.width * 0.36f
    val band = rOut * 0.34f
    for (i in 0 until 3) {
        drawArc(
            Brush.radialGradient(listOf(p.slabLo, p.slabHi), center, rOut), 90f - 60f + i * 120f + 4f, 112f, false,
            topLeft = center - Offset(rOut - band / 2, rOut - band / 2), size = Size(2 * rOut - band, 2 * rOut - band),
            style = Stroke(band),
        )
    }
    val disc = rOut - band - size.width * 0.03f
    drawCircle(Brush.radialGradient(listOf(Brand.On[1].copy(alpha = 0.35f), Color.Transparent), center, disc * 1.5f), disc * 1.5f, center)
    drawCircle(Brush.linearGradient(Brand.On, center - Offset(disc, disc), center + Offset(disc, disc)), disc, center)
    // Two text lines and a card, as on the real screen.
    val w = size.width
    drawRoundRect(c.onSurface.copy(alpha = 0.75f), Offset(w * 0.30f, size.height * 0.76f), Size(w * 0.40f, w * 0.045f), CornerRadius(w))
    drawRoundRect(c.surfaceContainer, Offset(w * 0.12f, size.height * 0.84f), Size(w * 0.76f, size.height * 0.11f), CornerRadius(w * 0.06f))
    drawRoundRect(c.onSurfaceVariant.copy(alpha = 0.4f), Offset(w * 0.20f, size.height * 0.875f), Size(w * 0.42f, w * 0.035f), CornerRadius(w))
}

/**
 * The name with the icon's signature: three dots after it, fading away in iris and azure, as
 * memories do. The same mark everywhere the app introduces itself.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FitText("Paramnezia", style, Modifier.weight(1f, fill = false), min = 14.sp)
        Canvas(Modifier.padding(start = 3.dp).size(20.dp, 28.dp)) {
            val u = size.width / 20f
            val y = size.height * 0.70f
            for ((i, d) in listOf(Triple(3.6f, 3.3f, 1f), Triple(10.6f, 2.6f, 0.75f), Triple(16.6f, 2.0f, 0.5f)).withIndex()) {
                val c = androidx.compose.ui.graphics.lerp(Brand.Signature[0], Brand.Signature[1], i / 2f)
                drawCircle(c.copy(alpha = d.third), d.second * u, Offset(d.first * u, y + (i * 1.2f) * u))
            }
        }
    }
}
