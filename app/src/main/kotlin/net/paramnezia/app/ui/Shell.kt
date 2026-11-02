package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max

/** The app's places. Home is the hub: the dial with the big button; the others open out of it. */
enum class Tab(val title: String) { HOME("Главная"), ADD("Добавить"), FLEET("Семья"), SETTINGS("Настройки") }

/**
 * Where each place's satellite sits on the home dial (root coordinates), so a screen can grow out
 * of the satellite that opened it and shrink back into it. Filled by [OrbitDial].
 */
class Orbit {
    val satellites: SnapshotStateMap<Tab, Offset> = mutableStateMapOf()
    /** Set by the small orbs in a screen's header: the next screen grows from there instead. */
    var tapped: Pair<Tab, Offset>? = null
}

val LocalOrbit = staticCompositionLocalOf { Orbit() }

/**
 * The frame around the screens (Android and Windows). There is no bottom bar: the places orbit the
 * power button on the home dial ([OrbitDial]). A place opens as a circle growing out of its
 * satellite and, on the way back, shrinks into it again. Inside a place, a slim header holds a
 * small twin of the power button (its colours tell the VPN state; a tap leads home) and the other
 * two places, so hopping between them is one tap too.
 *
 * [backHandler] wires the system Back (Android) to "home"; [top] is an optional banner above everything.
 */
@Composable
fun AppShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    power: PowerState,
    snackbar: SnackbarHostState,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    top: @Composable () -> Unit = {},
    content: @Composable (Tab, Modifier) -> Unit,
) {
    backHandler(tab != Tab.HOME) { onTab(Tab.HOME) }
    val orbit = remember { Orbit() }
    CompositionLocalProvider(LocalOrbit provides orbit) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            // Consumed, so the screens' imePadding() adds only what the system bars do not already cover.
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                top()
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { transition(initialState, targetState) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "screen",
                ) { t ->
                    if (t == Tab.HOME) content(t, Modifier.fillMaxSize())
                    else Revealed(t, orbit) {
                        Column(Modifier.fillMaxSize()) {
                            PlaceHeader(t, power, orbit, onTab)
                            content(t, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val REVEAL_MS = 460

/**
 * Home stays underneath and recedes a little; the place grows over it (see [Revealed]). Going home,
 * the place stays on top and shrinks into its satellite while home comes forward again.
 */
@OptIn(ExperimentalAnimationApi::class)
private fun transition(from: Tab, to: Tab): ContentTransform = when (to) {
    Tab.HOME -> (scaleIn(tween(REVEAL_MS, easing = Emphasized), 0.94f) + fadeIn(tween(REVEAL_MS / 2)))
        .togetherWith(keep())
        .apply { targetContentZIndex = -1f }
    else -> EnterTransition.None
        .togetherWith(
            if (from == Tab.HOME) scaleOut(tween(REVEAL_MS, easing = Emphasized), 0.94f) + fadeOut(tween(REVEAL_MS, delayMillis = 120))
            else keep(),
        )
        .apply { targetContentZIndex = 1f }
}

/** Stays fully visible for the whole reveal (the clip in [Revealed] does the work). */
private fun keep(): ExitTransition = fadeOut(tween(REVEAL_MS), targetAlpha = 0.999f)

/** A circular reveal centred on the satellite (or header orb) that opened this place. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun androidx.compose.animation.AnimatedContentScope.Revealed(t: Tab, orbit: Orbit, content: @Composable () -> Unit) {
    val p by transition.animateFloat(transitionSpec = { tween(REVEAL_MS, easing = Emphasized) }, label = "reveal") {
        if (it == EnterExitState.Visible) 1f else 0f
    }
    // Leaving: shrink back into the satellite on the dial, wherever the place was opened from.
    val leaving = transition.targetState == EnterExitState.PostExit
    var at by remember { mutableStateOf(Offset.Zero) }
    // Fixed when the place opens: from the orb that was tapped, else from its satellite on the dial.
    val from = remember { orbit.tapped?.takeIf { it.first == t }?.second.also { orbit.tapped = null } }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { at = it.positionInRoot() }
            .drawWithContent {
                if (p >= 0.999f) { drawContent(); return@drawWithContent }
                val origin = (if (leaving) orbit.satellites[t] ?: from else from ?: orbit.satellites[t])?.minus(at) ?: Offset(size.width / 2, size.height)
                val far = max(
                    max(hypot(origin.x, origin.y), hypot(size.width - origin.x, origin.y)),
                    max(hypot(origin.x, size.height - origin.y), hypot(size.width - origin.x, size.height - origin.y)),
                )
                val r = 26.dp.toPx() + (far - 26.dp.toPx()) * p
                clipPath(Path().apply { addOval(Rect(origin, r)) }) { this@drawWithContent.drawContent() }
            },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/** Colours of each place: the glyph tint and the badge gradient. */
class PlaceLook(val tintLight: Color, val tintDark: Color, val gradient: List<Color>)

val PlaceLooks = mapOf(
    Tab.ADD to PlaceLook(Color(0xFF00839C), Brand.Cyan, listOf(Color(0xFF26E0FF), Brand.Indigo)),
    Tab.FLEET to PlaceLook(Color(0xFF5B3DD6), Color(0xFFA48BFF), listOf(Color(0xFFB388FF), Color(0xFF6A3DF0))),
    Tab.SETTINGS to PlaceLook(Color(0xFF59607A), Color(0xFFBAC1DC), listOf(Color(0xFFFFC46B), Color(0xFFFF7A59))),
)

/** Inside a place: home (with the VPN state) on the left, the other two places on the right. */
@Composable
private fun PlaceHeader(tab: Tab, power: PowerState, orbit: Orbit, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeOrb(power) { onTab(Tab.HOME) }
        Spacer(Modifier.weight(1f))
        for (t in listOf(Tab.ADD, Tab.FLEET, Tab.SETTINGS)) {
            if (t == tab) continue
            var at by remember { mutableStateOf(Offset.Zero) }
            PlaceOrb(
                t, PlaceLooks.getValue(t), 40.dp,
                Modifier.onGloballyPositioned { at = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) },
            ) {
                orbit.tapped = t to at
                onTab(t)
            }
        }
    }
}

/** Spring sink and a haptic tick, without a rectangular ripple (the orb itself reacts). */
@Composable
fun Modifier.orbPress(label: String, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.86f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    return this
        .semantics(mergeDescendants = true) { contentDescription = label }
        .clip(CircleShape)
        .clickable(interaction, indication = null, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
        .scale(s)
}

/** A place in a screen's header: its glossy badge, springy under the finger. */
@Composable
fun PlaceOrb(tab: Tab, look: PlaceLook, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(size).orbPress(tab.title, onClick), contentAlignment = Alignment.Center) {
        PlaceBadge(tab, look, size)
    }
}

/** A small twin of the power button: its colours tell the VPN state; a tap leads home to the big one. */
@Composable
private fun HomeOrb(power: PowerState, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when (power) {
        PowerState.ON -> Brand.On
        PowerState.CONNECTING -> Brand.Connecting
        PowerState.OFF -> if (dark) Brand.Off else Brand.OffLight
    }
    val c0 by animateColorAsState(colors[0], tween(450), label = "c0")
    val c1 by animateColorAsState(colors[1], tween(450), label = "c1")
    val glyph by animateColorAsState(
        if (power == PowerState.OFF) (if (dark) Color(0xFFBAC1DC) else Color(0xFF59607A)) else Color.White, tween(400), label = "glyph")
    // Breathes only while connected or connecting, like the big button; no frames at rest.
    val pulse = if (power != PowerState.OFF) {
        val v by rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (power == PowerState.ON) 2200 else 700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
        v
    } else 0f
    val state = when (power) { PowerState.ON -> "VPN включён"; PowerState.CONNECTING -> "VPN подключается"; PowerState.OFF -> "VPN выключен" }
    Row(
        Modifier.orbPressPill("Главная, $state", onClick).padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = 17.dp.toPx()
                if (power != PowerState.OFF) {
                    drawCircle(
                        Brush.radialGradient(listOf(c1.copy(alpha = 0.30f + 0.25f * pulse), Color.Transparent), center, this.size.minDimension / 2),
                        this.size.minDimension / 2,
                    )
                }
                drawCircle(Brush.linearGradient(listOf(c0, c1), Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r)), r)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        Offset(center.x - r * 0.35f, center.y - r * 0.45f), r * 0.9f),
                    r,
                )
            }
            PowerGlyph(glyph, Modifier.size(15.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(Tab.HOME.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [orbPress] for a pill-shaped control (the home orb with its label). */
@Composable
private fun Modifier.orbPressPill(label: String, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    return this
        .semantics(mergeDescendants = true) { contentDescription = label }
        .scale(s)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(interaction, indication = null, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
        .padding(2.dp)
}
