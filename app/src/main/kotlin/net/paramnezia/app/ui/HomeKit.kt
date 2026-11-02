package net.paramnezia.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.paramnezia.app.App
import net.paramnezia.app.net.VisibilityCheck
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import net.paramnezia.tunnel.TunnelStats

/** Spacing rhythm of the home screens: everything is a multiple of 8. */
object Space {
    val S = 8.dp
    val M = 16.dp
    val L = 24.dp
}

/**
 * One line of text that never runs past its bounds: it shrinks (down to [min]) instead of being
 * cut. Used for every value whose length is not known in advance (addresses, countries, names).
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    min: TextUnit = 10.sp,
    align: TextAlign = TextAlign.Start,
) {
    val c = if (color.isSpecified) color else if (style.color.isSpecified) style.color else LocalContentColor.current
    BasicText(
        text, modifier,
        style = style.copy(color = c, textAlign = align),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** Press feedback shared by every tappable surface here: a spring sink and a haptic tick. */
@Composable
fun Modifier.tactile(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    return this.scale(s).clickable(interaction, indication = androidx.compose.material3.ripple(), role = role) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
}

/**
 * The "no servers" hero, in place of the power button (which would have nothing to switch on):
 * an empty server slot with a slowly breathing dashed ring, a title, and big tactile actions.
 */
@Composable
fun EmptyHero(follower: Boolean, onAdd: (() -> Unit)?, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyServerArt(Modifier.size(132.dp))
        Spacer(Modifier.height(Space.L))
        FitText("Нет ни одного сервера", MaterialTheme.typography.headlineSmall, align = TextAlign.Center, min = 16.sp)
        Spacer(Modifier.height(Space.S))
        Text(
            if (follower) "Откройте на этом телефоне приглашение от администратора или вставьте ключ vpn://."
            else "Добавьте сервер, и кнопка включения появится здесь.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Space.M),
        )
        Spacer(Modifier.height(Space.L))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S + 4.dp)) {
            if (onAdd != null) ActionTile(Icons.Filled.Add, "Добавить сервер", "ключ, QR-код или свой VPS", primary = true, onClick = onAdd)
            ActionTile(null, "Вставить ключ", "из буфера обмена", primary = onAdd == null, onClick = onPaste)
        }
    }
}

/** Big two-line button: a label and what it does. */
@Composable
fun ActionTile(icon: ImageVector?, title: String, sub: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Brush.linearGradient(listOf(Brand.Violet, Brand.Indigo)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    val fg = if (primary) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (primary) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        border = if (primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.medium).tactile(onClick = onClick),
    ) {
        Row(Modifier.background(bg).padding(horizontal = Space.M, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(fg.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, tint = fg) else ClipboardGlyph(fg, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f)) {
                FitText(title, MaterialTheme.typography.titleMedium, color = fg, min = 12.sp)
                FitText(sub, MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), min = 9.sp)
            }
        }
    }
}

/** Clipboard drawn in strokes (the core icon set has none). */
@Composable
private fun ClipboardGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = w * 0.11f
        drawRoundRect(color, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.8f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(s))
        drawRoundRect(color, Offset(w * 0.33f, h * 0.04f), Size(w * 0.34f, h * 0.2f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
        drawLine(color, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.68f, h * 0.5f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.7f), Offset(w * 0.58f, h * 0.7f), s, StrokeCap.Round)
    }
}

/** An empty server slot: a rack outline inside a breathing dashed ring, with a "+" badge. */
@Composable
private fun EmptyServerArt(modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "empty").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val ring = Brand.Coral
    val rack = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = 0.10f + 0.08f * t), Color.Transparent), center, r), r)
        drawCircle(ring.copy(alpha = 0.55f), r * (0.86f + 0.04f * t),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()), 0f)))
        drawCircle(surface, r * 0.62f)
        // Two rack units.
        val uw = r * 0.62f; val uh = r * 0.2f; val x = center.x - uw / 2
        for (i in 0..1) {
            val y = center.y - uh - 3.dp.toPx() + i * (uh + 6.dp.toPx())
            drawRoundRect(rack.copy(alpha = 0.8f), Offset(x, y), Size(uw, uh), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
            drawCircle(if (i == 0) ring else rack.copy(alpha = 0.5f), 2.5.dp.toPx(), Offset(x + uw - 9.dp.toPx(), y + uh / 2))
            drawLine(rack.copy(alpha = 0.5f), Offset(x + 8.dp.toPx(), y + uh / 2), Offset(x + uw * 0.5f, y + uh / 2), 2.dp.toPx(), StrokeCap.Round)
        }
        // "+" badge.
        val b = Offset(center.x + r * 0.55f, center.y + r * 0.55f); val br = r * 0.2f
        drawCircle(ring, br, b)
        drawLine(Color.White, Offset(b.x - br * 0.45f, b.y), Offset(b.x + br * 0.45f, b.y), 2.5.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(b.x, b.y - br * 0.45f), Offset(b.x, b.y + br * 0.45f), 2.5.dp.toPx(), StrokeCap.Round)
    }
}

/** Live counters, read every 2 s while the tunnel is up and the screen is shown. */
data class Live(val down: Long = 0, val up: Long = 0, val rx: Long = 0, val tx: Long = 0, val blocked: Long = 0)

@Composable
fun rememberLive(tunnel: TunnelState): Live {
    var live by remember { mutableStateOf(Live()) }
    LaunchedEffect(tunnel is TunnelState.Up) {
        var prev: TunnelStats? = null
        var prevAt = 0L
        while (tunnel is TunnelState.Up) {
            val s = TunnelRuntime.stats()
            val t = System.currentTimeMillis()
            if (s != null) {
                val p = prev
                val dt = (t - prevAt).coerceAtLeast(1)
                live = Live(
                    down = if (p != null) (s.rxBytes - p.rxBytes) * 1000 / dt else 0,
                    up = if (p != null) (s.txBytes - p.txBytes) * 1000 / dt else 0,
                    rx = s.rxBytes, tx = s.txBytes, blocked = TunnelRuntime.blockedQueries(),
                )
                prev = s; prevAt = t
            }
            delay(2_000)
        }
    }
    return live
}

/**
 * The info panel at the bottom of the home screen: where sites see you (both kinds) and, while
 * connected, the speed. One tap opens everything else in a sheet.
 */
@Composable
fun InfoPanel(app: App, tunnel: TunnelState, onMore: () -> Unit) {
    val (v, loading, _) = rememberVisibility(app, tunnel)
    val health by TunnelRuntime.health.collectAsState()
    val live = rememberLive(tunnel)
    val up = tunnel is TunnelState.Up
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).tactile(onClick = onMore),
    ) {
        Column(Modifier.padding(horizontal = Space.M, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            PlaceLine("Зарубежные сайты", v?.foreign, loading, if (up) Brand.Mint else null, v?.foreignError)
            PlaceLine("Российские сайты", v?.russian, loading, null, v?.russianError)
            if (up) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                Row(Modifier.fillMaxWidth()) {
                    Metric("↓", "${formatBytes(live.down)}/с", Brand.Cyan, Modifier.weight(1f))
                    Metric("↑", "${formatBytes(live.up)}/с", Brand.Violet, Modifier.weight(1f))
                    Metric("пинг", health.rttMs?.let { "$it мс" } ?: "—", Brand.Mint, Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                FitText("Подробнее", MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Flag, "who · address" in small type, and the country in body type: the country is what people read. */
@Composable
private fun PlaceLine(who: String, a: net.paramnezia.app.net.SeenAddress?, loading: Boolean, accent: Color?, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(a?.let { VisibilityCheck.flag(it.countryCode) } ?: "🌐", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(listOfNotNull(who, a?.ip).joinToString(" · "), MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                    Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.onSurfaceVariant, min = 8.sp)
                if (accent != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                }
            }
            FitText(
                when { a != null -> a.country ?: "страна не определена"; loading -> "проверяю…"; else -> "не удалось узнать" },
                MaterialTheme.typography.bodyLarge, min = 12.sp,
            )
            // The real reason, not a bare "failed": which services were asked and what each said.
            if (a == null && !loading && error != null) {
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Metric(icon: String, value: String, accent: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(icon, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        FitText(value, MaterialTheme.typography.labelLarge.merge(TabularNumbers), min = 9.sp)
    }
}

/** Big status title and its subtitle, under the button. */
@Composable
fun StatusBlock(tunnel: TunnelState, p: Protection) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel) { while (tunnel is TunnelState.Up) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FitText(
            when (p) {
                Protection.ON -> "Защищено"
                Protection.CONNECTING -> "Подключаюсь…"
                Protection.FAILED -> "Нет соединения"
                Protection.OFF -> "VPN выключен"
                Protection.NO_SERVER -> "Нет сервера"
            },
            MaterialTheme.typography.headlineMedium, align = TextAlign.Center, min = 18.sp,
            color = when (p) { Protection.ON, Protection.CONNECTING, Protection.FAILED -> protectionColor(p); else -> MaterialTheme.colorScheme.onSurface },
        )
        Spacer(Modifier.height(4.dp))
        FitText(
            if (tunnel is TunnelState.Up) "${formatDuration(now - tunnel.since)} · ${tunnel.serverName}" else tapHint(p),
            MaterialTheme.typography.bodyMedium.merge(TabularNumbers), align = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, min = 10.sp,
        )
    }
}

/** Largest button that leaves room for the status block in [height]. */
fun buttonFor(height: Dp, width: Dp, max: Dp): Dp {
    val byHeight = (height - 72.dp) / 1.35f
    val byWidth = width * 0.62f
    return minOf(max, byHeight, byWidth).coerceAtLeast(88.dp)
}

@Composable
fun FillBox(modifier: Modifier, content: @Composable () -> Unit) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
