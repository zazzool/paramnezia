package net.paramnezia.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PowerState { OFF, CONNECTING, ON }

/**
 * The on/off button: a glowing disc that breathes while connected, spins a comet while connecting
 * and sinks under the finger with a spring and a haptic tick. Everything is drawn on one Canvas
 * (no images), and the animations stop when the state is OFF, so it costs nothing at rest.
 */
@Composable
fun PowerButton(state: PowerState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 216.dp) {
    val dark = isSystemInDarkTheme()
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")

    val colors = when (state) {
        PowerState.ON -> Brand.On
        PowerState.CONNECTING -> Brand.Connecting
        PowerState.OFF -> if (dark) Brand.Off else Brand.OffLight
    }
    val c0 by animateColorAsState(colors[0], tween(450), label = "c0")
    val c1 by animateColorAsState(colors[1], tween(450), label = "c1")
    val glowAlpha by animateFloatAsState(if (state == PowerState.OFF) 0f else 1f, tween(600), label = "glow")
    val iconColor by animateColorAsState(
        if (state == PowerState.OFF) (if (dark) Color(0xFFBAC1DC) else Color(0xFF59607A)) else Color.White, tween(400), label = "icon")

    // Endless animations exist only while they are visible, so an idle screen draws no frames.
    val breath = if (state == PowerState.ON) {
        val v by rememberInfiniteTransition(label = "breath").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        v
    } else 0.5f
    val spin = if (state == PowerState.CONNECTING) {
        val v by rememberInfiniteTransition(label = "spin").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
        v
    } else 0f

    Box(
        modifier
            .size(size * 1.35f)
            .semantics {
                contentDescription = "VPN"
                stateDescription = when (state) { PowerState.ON -> "включён"; PowerState.CONNECTING -> "подключается"; PowerState.OFF -> "выключен" }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Halo: soft rings behind the disc.
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            val pulse = if (state == PowerState.ON) breath else 0.5f
            drawCircle(
                Brush.radialGradient(
                    0.55f to c1.copy(alpha = 0.38f * glowAlpha * (0.6f + 0.4f * pulse)),
                    1f to Color.Transparent,
                    center = center, radius = r,
                ),
                radius = r,
            )
            drawCircle(c0.copy(alpha = 0.22f * glowAlpha), radius = r * (0.80f + 0.05f * pulse), style = Stroke(2.dp.toPx()))
        }
        Box(
            Modifier
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Switch) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2
                // Disc with a diagonal gradient and a glossy top light.
                drawCircle(Brush.linearGradient(listOf(c0, c1), start = Offset.Zero, end = Offset(this.size.width, this.size.height)))
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(center.x - r * 0.35f, center.y - r * 0.45f), radius = r * 0.9f),
                )
                drawCircle(Color.White.copy(alpha = 0.10f), radius = r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                // Connecting: a comet running around the rim.
                if (state == PowerState.CONNECTING) {
                    val w = 6.dp.toPx()
                    rotate(spin) {
                        drawArc(
                            Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.95f)), center),
                            startAngle = 0f, sweepAngle = 300f, useCenter = false,
                            topLeft = Offset(w, w), size = Size(this.size.width - 2 * w, this.size.height - 2 * w),
                            style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            PowerGlyph(iconColor, Modifier.size(size * 0.36f))
        }
    }
}

/** The IEC power symbol: an open ring and a bar. */
@Composable
fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.padding(2.dp)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = stroke / 2
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(inset, inset + this.size.height * 0.06f),
            size = Size(this.size.width - stroke, this.size.height - stroke - this.size.height * 0.06f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(center.x, inset), Offset(center.x, this.size.height * 0.48f), stroke, StrokeCap.Round)
    }
}
