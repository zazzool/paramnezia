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
import androidx.compose.ui.graphics.lerp
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
 * equal segments. Each whole segment is a button: «Добавить» at the bottom (the thumb's spot),
 * «Семья» upper left, «Настройки» upper right. A segment is a soft convex slab with its icon
 * engraved and enamelled; under the finger it sinks towards the centre, takes its colour and ticks.
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
        // Each segment's node for screen readers and UI tests (the drawing is all in SegmentRing).
        for (s in Segments) {
            val rad = s.center * PI / 180
            val mid = (g.inner + g.outer) / 2
            Box(
                Modifier
                    .offset(mid * cos(rad).toFloat(), mid * sin(rad).toFloat())
                    .size(g.band * 0.8f)
                    .onGloballyPositioned { orbit.satellites[s.tab] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                    .semantics { contentDescription = s.tab.title }
                    .focusRing(interactions.getValue(s.tab))
                    .clickable(interactions.getValue(s.tab), indication = null, role = Role.Button) { open(s.tab) },
            )
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
    val dark = isDark()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val pn = Pn
    val deep = pn.slabLo
    val light = pn.slabHi
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
    val enamels = Segments.associate { s ->
        val look = PlaceLooks.getValue(s.tab)
        val on = pressed.getValue(s.tab) || s.tab == lit
        val c by animateColorAsState(if (on) look.gradient[0] else look.gradient[1], tween(200), label = "enamel")
        s.tab to c
    }
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
                drawPath(shadow, Color.Black.copy(alpha = pn.shadowAlpha * (1 - sink)), style = Stroke(2 * k + 2.dp.toPx(), join = StrokeJoin.Round))
                // Convex body: light along the outer edge, deeper towards the button; pressed turns it concave.
                // Colours are blended here (opaque), so the fill and its rounding stroke overlap without a seam.
                val wash = 0.07f + 0.20f * sink + if (s.tab == lit) 0.22f * glow else 0f
                fun tone(x: Color) = lerp(lerp(x, base, 0.55f), accent, wash)
                val body = Brush.radialGradient(
                    (rIn / rOut) to tone(if (sink > 0.5f) light else deep),
                    1f to tone(if (sink > 0.5f) deep else light),
                    center = c, radius = rOut,
                )
                drawPath(path, body)
                drawPath(path, body, style = Stroke(2 * k, join = StrokeJoin.Round))
                // Rim light on the outer edge.
                val inset = (gap / 2 + 2 * k) / rOut * 180f / PI.toFloat()
                drawArc(
                    Color.White.copy(alpha = pn.rimAlpha), s.center - 60f + inset, 120f - 2 * inset, false,
                    topLeft = Offset(c.x - rOut + 1.dp.toPx(), c.y - rOut + 1.dp.toPx()),
                    size = Size(2 * rOut - 2.dp.toPx(), 2 * rOut - 2.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 1f - sink,
                )
                // The icon is engraved into the slab and filled with enamel of the place's colour.
                val rad = s.center * PI.toFloat() / 180f
                val mid = (rIn + rOut) / 2
                val at = Offset(c.x + mid * cos(rad), c.y + mid * sin(rad))
                engraved(s.tab, at, (rOut - rIn) * 0.30f, enamels.getValue(s.tab), sink, pn)
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
 * The place's icon in a screen header: the same engraving, on a small round slab.
 */
@Composable
fun PlaceBadge(tab: Tab, look: PlaceLook, size: Dp) {
    val pn = Pn
    val slab = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Color.Black.copy(alpha = pn.shadowAlpha * 0.6f), r - 1.dp.toPx(), center + Offset(0f, 1.5.dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(pn.slabHi, slab)), r - 1.dp.toPx())
        drawCircle(Color.White.copy(alpha = pn.rimAlpha * 0.8f), r - 1.75.dp.toPx(), style = Stroke(1.dp.toPx()))
        engraved(tab, center, r * 0.62f, look.gradient[1], 0f, pn)
    }
}

/**
 * An engraving with enamel: the glyph is cut into the surface (a dark lip on the upper-left edge of
 * the cut, a light one on the lower-right), then filled with glossy enamel slightly below the surface.
 */
private fun DrawScope.engraved(tab: Tab, at: Offset, r: Float, enamel: Color, pressed: Float, pn: PnPalette) {
    val d = 1.dp.toPx()
    glyph(tab, at + Offset(0f, d), r, pn.engraveHi, 1f)
    glyph(tab, at - Offset(0f, d * 0.9f), r, pn.engraveLo, 1f)
    glyph(tab, at, r, enamel, 1f)
    // The enamel's gloss: a faint, thinner pass shifted up.
    glyph(tab, at - Offset(0f, d * 0.5f), r, Color.White.copy(alpha = 0.16f * (1 - pressed)), 0.4f)
}

/** The segment's Lucide glyph centred at [at], [r] being half its size; [weight] scales the strokes. */
private fun DrawScope.glyph(tab: Tab, at: Offset, r: Float, color: Color, weight: Float) {
    val name = when (tab) {
        Tab.ADD -> "Plus"
        Tab.FLEET -> "Users"
        Tab.SETTINGS -> "Sliders"
        Tab.HOME -> return
    }
    val box = r * 2f * 1.15f
    drawLucide(name, at, box, color, box / 24f * 2.3f * weight)
}
