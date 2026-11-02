package net.paramnezia.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.paramnezia.tunnel.TunnelState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The home dial: the power button in the middle and, concentric with it, a ring cut into three
 * equal segments. Each segment is a button: «Добавить» at the bottom (the thumb's spot),
 * «Семья» upper left, «Настройки» upper right. A segment is a soft convex slab with a glossy
 * badge; under the finger it sinks towards the centre, darkens into its colour and ticks.
 */
@Composable
fun OrbitDial(tunnel: TunnelState, p: Protection, size: Dp, onPower: () -> Unit, onOpen: (Tab) -> Unit) {
    val g = Ring(size)
    val haptic = LocalHapticFeedback.current
    val orbit = LocalOrbit.current
    val interactions = remember { Segments.associate { it.tab to MutableInteractionSource() } }
    var touched by remember { mutableStateOf<Tab?>(null) }
    val pressed = Segments.associate { s ->
        val viaNode by interactions.getValue(s.tab).collectIsPressedAsState()
        s.tab to (viaNode || touched == s.tab)
    }
    val open: (Tab) -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onOpen(it) }

    Box(
        Modifier
            .size(size)
            // The whole slab is the target, not just its badge: find the segment under the finger.
            .pointerInput(size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val seg = segmentAt(down.position, this.size.width / 2f, g, density) ?: return@awaitEachGesture
                    touched = seg
                    val up = waitForUpOrCancellation()
                    touched = null
                    if (up != null && segmentAt(up.position, this.size.width / 2f, g, density) == seg) open(seg)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SegmentRing(g, pressed, lit = if (p == Protection.NO_SERVER) Tab.ADD else null, Modifier.size(size))
        PowerButton(tunnel.power(), onClick = onPower, size = g.disc, dimmed = p == Protection.NO_SERVER)
        // Badge and label of each segment; also its node for screen readers and UI tests.
        for (s in Segments) {
            val rad = s.center * PI / 180
            val mid = (g.inner + g.outer) / 2
            val dx = mid * cos(rad).toFloat()
            val dy = mid * sin(rad).toFloat()
            Column(
                Modifier
                    .offset(dx, dy)
                    .width(96.dp)
                    .onGloballyPositioned { orbit.satellites[s.tab] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                    .semantics(mergeDescendants = true) { contentDescription = s.tab.title }
                    .clickable(interactions.getValue(s.tab), indication = null, role = Role.Button) { open(s.tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val sink by animateFloatAsState(if (pressed.getValue(s.tab)) 0.9f else 1f,
                    spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "badge")
                Box(Modifier.size(g.badge), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(g.badge * sink), contentAlignment = Alignment.Center) {
                        PlaceBadge(s.tab, PlaceLooks.getValue(s.tab), g.badge * sink)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    s.tab.title, maxLines = 1, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** A segment: its place and the angle of its middle (0° = 3 o'clock, clockwise). Each spans 120°. */
private class Segment(val tab: Tab, val center: Float)

private val Segments = listOf(Segment(Tab.ADD, 90f), Segment(Tab.FLEET, 210f), Segment(Tab.SETTINGS, 330f))

/** Radii of the dial for its [size]: the ring band and the button inside it. */
private class Ring(val size: Dp) {
    val outer = size / 2 - 4.dp
    val band = (size * 0.21f).coerceIn(58.dp, 84.dp)
    val inner = outer - band
    val disc = (inner - 14.dp) * 2
    val badge = (band * 0.52f).coerceIn(30.dp, 40.dp)
    val gap = 7.dp
}

/** Largest dial that leaves room for the status lines in [height]. */
fun dialFor(height: Dp, width: Dp): Dp = minOf(width, height - 76.dp, 400.dp).coerceAtLeast(220.dp)

private fun segmentAt(pos: Offset, half: Float, g: Ring, density: Float): Tab? {
    val dx = pos.x - half
    val dy = pos.y - half
    val r = hypot(dx, dy)
    val inner = g.inner.value * density - 6 * density
    val outer = g.outer.value * density + 10 * density
    if (r < inner || r > outer) return null
    val a = ((atan2(dy, dx) * 180 / PI).toFloat() + 360f) % 360f
    return Segments.firstOrNull { s -> abs(((a - s.center + 540f) % 360f) - 180f) <= 60f }?.tab
}

@Composable
private fun SegmentRing(g: Ring, pressed: Map<Tab, Boolean>, lit: Tab?, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val deep = if (dark) Color(0xFF151A2C) else Color(0xFFD3D8EA)
    val light = if (dark) Color(0xFF2C3458) else Color.White
    val sinks = Segments.associate { s ->
        val v by animateFloatAsState(if (pressed.getValue(s.tab)) 1f else 0f,
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "seg")
        s.tab to v
    }
    // Without a server the «Добавить» segment glows softly until there is one.
    val glow = if (lit != null) {
        val v by rememberInfiniteTransition(label = "lit").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lit")
        v
    } else 0f
    val accents = Segments.associate { s ->
        val look = PlaceLooks.getValue(s.tab)
        val c by animateColorAsState(if (dark) look.tintDark else look.tintLight, label = "accent")
        s.tab to c
    }
    Canvas(modifier) {
        val c = center
        val rOut = g.outer.toPx()
        val rIn = g.inner.toPx()
        val k = 6.dp.toPx() // corner rounding: the path is inset by k and stroked 2k wide with round joins
        val gap = g.gap.toPx()
        for (s in Segments) {
            val sink = sinks.getValue(s.tab)
            val accent = accents.getValue(s.tab)
            val path = slab(c, rIn + k, rOut - k, s.center - 60f, 120f, gap / 2 + k)
            scale(1f - 0.035f * sink, pivot = c) {
                // Soft shadow under the slab: lifted at rest, flat when pressed.
                val shadow = slab(Offset(c.x, c.y + 3.dp.toPx() * (1 - sink)), rIn + k, rOut - k, s.center - 60f, 120f, gap / 2 + k)
                drawPath(shadow, Color.Black.copy(alpha = (if (dark) 0.35f else 0.10f) * (1 - sink)), style = Stroke(2 * k + 2.dp.toPx(), join = StrokeJoin.Round))
                // Convex body: light along the outer edge, deeper towards the button; pressed turns it concave.
                val body = Brush.radialGradient(
                    (rIn / rOut) to (if (sink > 0.5f) light else deep),
                    1f to (if (sink > 0.5f) deep else light),
                    center = c, radius = rOut,
                )
                drawPath(path, body)
                drawPath(path, body, style = Stroke(2 * k, join = StrokeJoin.Round))
                drawPath(path, base.copy(alpha = 0.55f))
                drawPath(path, base.copy(alpha = 0.55f), style = Stroke(2 * k, join = StrokeJoin.Round))
                // The place's colour: a whisper at rest, a wash under the finger or when it calls for attention.
                val wash = 0.07f + 0.20f * sink + if (s.tab == lit) 0.22f * glow else 0f
                drawPath(path, accent.copy(alpha = wash))
                drawPath(path, accent.copy(alpha = wash), style = Stroke(2 * k, join = StrokeJoin.Round))
                // Rim light on the outer edge.
                val inset = (gap / 2 + 2 * k) / rOut * 180f / PI.toFloat()
                drawArc(
                    Color.White.copy(alpha = if (dark) 0.10f else 0.85f), s.center - 60f + inset, 120f - 2 * inset, false,
                    topLeft = Offset(c.x - rOut + 1.dp.toPx(), c.y - rOut + 1.dp.toPx()),
                    size = Size(2 * rOut - 2.dp.toPx(), 2 * rOut - 2.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 1f - sink,
                )
            }
        }
    }
}

/** A ring sector from [start] over [sweep] degrees, its straight edges pulled in by [edge] px. */
private fun slab(c: Offset, rIn: Float, rOut: Float, start: Float, sweep: Float, edge: Float): Path {
    val aOut = edge / rOut * 180f / PI.toFloat()
    val aIn = edge / rIn * 180f / PI.toFloat()
    return Path().apply {
        arcTo(Rect(c, rOut), start + aOut, sweep - 2 * aOut, forceMoveTo = true)
        arcTo(Rect(c, rIn), start + sweep - aIn, -(sweep - 2 * aIn), forceMoveTo = false)
        close()
    }
}

/**
 * The place's icon as a little glossy sweet: a gradient disc with a top light, a soft shadow
 * and a bold, rounded white glyph drawn here (plus; two people; sliders).
 */
@Composable
fun PlaceBadge(tab: Tab, look: PlaceLook, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Color.Black.copy(alpha = 0.16f), r * 0.98f, Offset(center.x, center.y + r * 0.10f))
        drawCircle(Brush.linearGradient(look.gradient, Offset(0f, 0f), Offset(this.size.width, this.size.height)), r)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                Offset(center.x - r * 0.25f, center.y - r * 0.55f), r * 0.85f),
            r,
        )
        drawCircle(Color.White.copy(alpha = 0.25f), r - 0.75.dp.toPx(), style = Stroke(1.dp.toPx()))
        glyph(tab, r)
    }
}

private fun DrawScope.glyph(tab: Tab, r: Float) {
    // A 24-unit grid centred in the badge, about 55 % of its diameter.
    val u = r * 1.1f / 12f
    fun p(x: Float, y: Float) = Offset(center.x + (x - 12f) * u, center.y + (y - 12f) * u)
    val w = Color.White
    val stroke = 2.4f * u
    when (tab) {
        Tab.ADD -> {
            drawLine(w, p(12f, 5.5f), p(12f, 18.5f), stroke * 1.15f, StrokeCap.Round)
            drawLine(w, p(5.5f, 12f), p(18.5f, 12f), stroke * 1.15f, StrokeCap.Round)
        }
        Tab.FLEET -> {
            // Two people: a grown-up and a smaller one beside.
            drawCircle(w, 3.3f * u, p(9f, 7.6f))
            drawArc(w, 180f, 180f, false, topLeft = p(3.2f, 13.6f), size = Size(11.6f * u, 11.6f * u), style = Stroke(stroke, cap = StrokeCap.Round))
            drawCircle(w.copy(alpha = 0.85f), 2.5f * u, p(16.8f, 9.6f))
            drawArc(w.copy(alpha = 0.85f), 200f, 140f, false, topLeft = p(13f, 14.6f), size = Size(8.4f * u, 8.4f * u), style = Stroke(stroke * 0.9f, cap = StrokeCap.Round))
        }
        Tab.SETTINGS -> {
            // Sliders: three tracks with knobs.
            for ((y, x) in listOf(6.5f to 9f, 12f to 15.5f, 17.5f to 11f)) {
                drawLine(w.copy(alpha = 0.6f), p(5f, y), p(19f, y), stroke * 0.8f, StrokeCap.Round)
                drawCircle(w, 2.5f * u, p(x, y))
            }
        }
        Tab.HOME -> Unit
    }
}
