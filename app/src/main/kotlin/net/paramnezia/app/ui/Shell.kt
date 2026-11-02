package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's places. Home is the hub: the big button; the others open over it. */
enum class Tab(val title: String) { HOME("Главная"), ADD("Добавить"), FLEET("Флот"), SETTINGS("Настройки") }

/**
 * The frame around the screens (Android and Windows): no classic bottom bar, but a floating
 * "orbit" dock. On the home screen it holds the three actions under the big button; on any other
 * screen a small copy of the power button joins it on the left, showing the VPN state in its
 * colours and leading back home. Screens rise from the dock and sink back into it.
 *
 * [backHandler] wires the system Back (Android) to "home"; [top] is an optional banner above the content.
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
    // While typing, the dock steps aside: the field and the keyboard get the whole screen.
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = !typing,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { OrbitDock(tab, power, onTab) }
        },
    ) { padding ->
        // Consumed, so the screens' imePadding() adds only the part of the keyboard above the dock.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            top()
            AnimatedContent(
                targetState = tab,
                transitionSpec = { transition(initialState, targetState) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "screen",
            ) { t -> content(t, Modifier.fillMaxSize()) }
        }
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * From home a screen rises out of the dock while home recedes; back home it sinks again.
 * Between the dock's screens: a short sideways glide in the dock's order.
 */
private fun transition(from: Tab, to: Tab): ContentTransform {
    val d = 340
    return when {
        from == Tab.HOME -> (slideInVertically(tween(d, easing = Emphasized)) { it / 6 } + fadeIn(tween(d / 2)) + scaleIn(tween(d, easing = Emphasized), 0.96f))
            .togetherWith(scaleOut(tween(d, easing = Emphasized), 0.94f) + fadeOut(tween(d / 2)))
        to == Tab.HOME -> (scaleIn(tween(d, easing = Emphasized), 0.94f) + fadeIn(tween(d)))
            .togetherWith(slideOutVertically(tween(d, easing = Emphasized)) { it / 6 } + fadeOut(tween(d / 2)) + scaleOut(tween(d), 0.96f))
        else -> {
            val dir = if (to.ordinal > from.ordinal) 1 else -1
            (slideInHorizontally(tween(d, easing = Emphasized)) { dir * it / 5 } + fadeIn(tween(d / 2)))
                .togetherWith(slideOutHorizontally(tween(d, easing = Emphasized)) { -dir * it / 5 } + fadeOut(tween(d / 2)))
        }
    }
}

/** Colours of each place: the glyph tint at rest and the orb gradient when it is the open screen. */
private class Look(val icon: ImageVector, val tintLight: Color, val tintDark: Color, val gradient: List<Color>)

private val Looks = mapOf(
    Tab.ADD to Look(Icons.Rounded.Add, Color(0xFF00839C), Brand.Cyan, listOf(Brand.Cyan, Brand.Indigo)),
    Tab.FLEET to Look(Icons.Rounded.Share, Color(0xFF5B3DD6), Color(0xFFA48BFF), listOf(Brand.Violet, Color(0xFFE040FB))),
    Tab.SETTINGS to Look(Icons.Rounded.Settings, Color(0xFF59607A), Color(0xFFBAC1DC), listOf(Color(0xFF8A93B2), Color(0xFF4A5275))),
)

/**
 * The floating dock: a frosted capsule lifted off the screen edge, with round, springy orbs.
 * The open screen's orb fills with its colour and grows a little; home is the mini power orb.
 */
@Composable
private fun OrbitDock(tab: Tab, power: PowerState, onTab: (Tab) -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(34.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) 0.92f else 0.96f),
            shadowElevation = 12.dp,
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.9f), Color.White.copy(alpha = if (dark) 0.02f else 0.3f)),
                ),
            ),
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                AnimatedVisibility(
                    visible = tab != Tab.HOME,
                    enter = expandHorizontally(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) +
                        scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), 0.3f) + fadeIn(),
                    exit = shrinkHorizontally(tween(260, easing = FastOutSlowInEasing)) + scaleOut(tween(220), 0.3f) + fadeOut(tween(180)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeOrb(power) { onTab(Tab.HOME) }
                        // A hairline between "where my VPN is" and "where I can go".
                        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                    }
                }
                for (t in listOf(Tab.ADD, Tab.FLEET, Tab.SETTINGS)) {
                    DockOrb(t, Looks.getValue(t), selected = tab == t, dark = dark, modifier = Modifier.weight(1f)) { onTab(t) }
                }
            }
        }
    }
}

/** Spring sink and a haptic tick, without the rectangular ripple (the orb itself reacts). */
@Composable
private fun Modifier.press(label: String, selected: Boolean, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.88f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    return this
        .semantics(mergeDescendants = true) { contentDescription = label; this.selected = selected }
        .clip(RoundedCornerShape(22.dp))
        .clickable(interaction, indication = null, role = Role.Tab) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
        .scale(s)
}

@Composable
private fun DockOrb(tab: Tab, look: Look, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (dark) look.tintDark else look.tintLight
    val size by animateDpAsState(if (selected) 48.dp else 44.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "orb")
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "fill")
    val iconColor by animateColorAsState(if (selected) Color.White else tint, tween(260), label = "icon")
    val labelColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(260), label = "label")
    Column(modifier.press(tab.title, selected, onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2
                // At rest: a soft tinted well. Open: the place's gradient with a glossy top light and a glow.
                drawCircle(tint.copy(alpha = 0.13f * (1 - fill)), r)
                if (fill > 0f) {
                    drawCircle(
                        Brush.radialGradient(listOf(look.gradient[0].copy(alpha = 0.35f * fill), Color.Transparent), center, r * 1.25f),
                        r * 1.25f,
                    )
                    drawCircle(
                        Brush.linearGradient(look.gradient.map { it.copy(alpha = fill) }, Offset.Zero, Offset(this.size.width, this.size.height)),
                        r,
                    )
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f * fill), Color.Transparent),
                            Offset(center.x - r * 0.35f, center.y - r * 0.45f), r * 0.9f),
                        r,
                    )
                }
            }
            Icon(look.icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        }
        Text(
            tab.title, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = labelColor,
            maxLines = 1, textAlign = TextAlign.Center,
        )
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
    // Breathes only while connected, like the big button; no frames at rest.
    val pulse = if (power != PowerState.OFF) {
        val v by rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (power == PowerState.ON) 2200 else 700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
        v
    } else 0f
    val state = when (power) { PowerState.ON -> "VPN включён"; PowerState.CONNECTING -> "VPN подключается"; PowerState.OFF -> "VPN выключен" }
    Column(Modifier.width(68.dp).press("Главная, $state", false, onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val r = 22.dp.toPx()
                if (power != PowerState.OFF) {
                    drawCircle(
                        Brush.radialGradient(listOf(c1.copy(alpha = 0.30f + 0.20f * pulse), Color.Transparent), center, this.size.minDimension / 2),
                        this.size.minDimension / 2,
                    )
                }
                drawCircle(Brush.linearGradient(listOf(c0, c1), Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r)), r)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        Offset(center.x - r * 0.35f, center.y - r * 0.45f), r * 0.9f),
                    r,
                )
                drawCircle(Color.White.copy(alpha = 0.12f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            PowerGlyph(glyph, Modifier.size(18.dp))
        }
        Text(
            Tab.HOME.title, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}
