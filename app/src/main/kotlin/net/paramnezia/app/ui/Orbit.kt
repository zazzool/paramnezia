package net.paramnezia.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.paramnezia.app.App
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home dial. In the middle, the power button. Around it, a bezel with two lines of text bent
 * along the circle: where sites see you, through the VPN and directly (country and address). On the
 * outer orbit, the three places sit like satellites under the button, in the thumb's reach:
 * «Добавить», «Флот», «Настройки». The orbit itself tells the state: a mint ring with a slowly
 * circling light while protected, an amber arc running round while connecting, a dashed ring
 * with no server. Like a watch face with complications, everything is read at a glance.
 */
@Composable
fun OrbitDial(app: App, tunnel: TunnelState, p: Protection, size: Dp, servers: Int, onPower: () -> Unit, onOpen: (Tab) -> Unit) {
    val g = DialGeometry(size)
    val (v, loading, _) = rememberVisibility(app, tunnel)
    val state by app.repo.state.collectAsState()
    val serverLabel = when {
        state.fleet != null -> "флот · серверов: $servers"
        else -> (state.profiles.firstOrNull { it.id == state.activeProfile } ?: state.profiles.firstOrNull())?.name?.let { "сервер · $it" } ?: ""
    }
    fun place(a: net.paramnezia.app.net.SeenAddress?, err: String?): String = when {
        a != null -> listOfNotNull(a.country ?: "страна?", a.ip).joinToString(" · ")
        loading -> "проверяю…"
        err != null -> "не удалось узнать"
        else -> "проверяю…"
    }
    val mint = Brand.Mint
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (outer, inner) = when (p) {
        Protection.NO_SERVER -> Line("нет ни одного сервера", Brand.Coral) to Line("добавьте сервер или вставьте ключ", muted)
        Protection.CONNECTING -> Line("подключаюсь", Brand.Amber) to Line(serverLabel, muted)
        Protection.ON -> Line("через VPN · " + place(v?.foreign, v?.foreignError), mint) to
            Line("напрямую · " + place(v?.russian, v?.russianError), muted)
        Protection.OFF, Protection.FAILED -> Line("сайты видят · " + place(v?.russian ?: v?.foreign, v?.russianError), muted) to
            Line(serverLabel, muted.copy(alpha = 0.8f))
    }

    Box(Modifier.size(width = size, height = size + 14.dp)) {
        OrbitRing(p, g, Modifier.size(size))
        BezelText(outer, inner, g, Modifier.size(size))
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            PowerButton(tunnel.power(), onClick = onPower, size = g.disc, dimmed = p == Protection.NO_SERVER)
        }
        val orbit = LocalOrbit.current
        for ((tab, angle) in Satellites) {
            val rad = angle * PI / 180
            val cx = size / 2 + g.orbit * sin(rad).toFloat()
            val cy = size / 2 - g.orbit * cos(rad).toFloat()
            Column(
                Modifier.offset(cx - 44.dp, cy - SAT / 2).width(88.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val lit = tab == Tab.ADD && p == Protection.NO_SERVER
                Box(contentAlignment = Alignment.Center) {
                    if (lit) Beacon(Modifier.size(SAT + 20.dp))
                    PlaceOrb(
                        tab, PlaceLooks.getValue(tab), SAT,
                        Modifier.onGloballyPositioned {
                            orbit.satellites[tab] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                        },
                        lit = lit,
                    ) { onOpen(tab) }
                }
                Text(
                    tab.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val SAT = 52.dp

/** Satellites, clockwise from 12 o'clock: under the button, where the thumb rests. */
private val Satellites = listOf(Tab.ADD to 228.0, Tab.FLEET to 180.0, Tab.SETTINGS to 132.0)

/** Radii of the dial for its [size]: orbit for the satellites, two text lines, the button. */
private class DialGeometry(val size: Dp) {
    val orbit = size / 2 - SAT / 2 - 4.dp
    val outerText = orbit - 24.dp
    val innerText = outerText - 15.dp
    val disc = (innerText - 13.dp) * 2
}

/** Largest dial that leaves room for the status lines in [height]. */
fun dialFor(height: Dp, width: Dp): Dp = minOf(width, height - 92.dp, 420.dp).coerceAtLeast(220.dp)

private class Line(val text: String, val color: Color)

@Composable
private fun OrbitRing(p: Protection, g: DialGeometry, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val color by animateColorAsState(
        when (p) { Protection.ON -> Brand.Mint; Protection.CONNECTING -> Brand.Amber; Protection.NO_SERVER -> Brand.Coral; else -> track },
        tween(500), label = "ring",
    )
    // Moving parts exist only while something is happening; an idle dial draws no frames.
    val spin = if (p == Protection.ON || p == Protection.CONNECTING) {
        val v by rememberInfiniteTransition(label = "orbit").animateFloat(
            0f, 360f, infiniteRepeatable(tween(if (p == Protection.ON) 14_000 else 1_400, easing = LinearEasing)), label = "orbit")
        v
    } else 0f
    Canvas(modifier) {
        val r = g.orbit.toPx()
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(2 * r, 2 * r)
        when (p) {
            Protection.ON -> {
                drawCircle(Brush.sweepGradient(listOf(Brand.Mint, Brand.Cyan, Brand.Mint.copy(alpha = 0.5f), Brand.Mint), center), r,
                    style = Stroke(2.dp.toPx()), alpha = 0.75f)
                // A small light travelling the orbit, with a short tail.
                rotate(spin) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, Brand.Cyan.copy(alpha = 0.9f)), center),
                        startAngle = -60f, sweepAngle = 60f, useCenter = false, topLeft = tl, size = sz,
                        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawCircle(Color.White, 3.5.dp.toPx(), Offset(center.x + r, center.y))
                    drawCircle(Brand.Cyan.copy(alpha = 0.35f), 8.dp.toPx(), Offset(center.x + r, center.y))
                }
            }
            Protection.CONNECTING -> {
                drawCircle(track, r, style = Stroke(1.5.dp.toPx()))
                rotate(spin) {
                    drawArc(color, startAngle = 0f, sweepAngle = 100f, useCenter = false, topLeft = tl, size = sz,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Protection.NO_SERVER -> drawCircle(color.copy(alpha = 0.6f), r,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))))
            else -> drawCircle(track, r, style = Stroke(1.5.dp.toPx()))
        }
        // Bezel ticks between the orbit and the text, every 6°: the dial reads as an instrument.
        val t0 = (g.orbit - 7.dp).toPx()
        val t1 = (g.orbit - 4.dp).toPx()
        for (i in 0 until 60) {
            val a = i * 6.0 * PI / 180
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            drawLine(track.copy(alpha = if (i % 5 == 0) 0.9f else 0.45f), Offset(center.x + t0 * s, center.y - t0 * c),
                Offset(center.x + t1 * s, center.y - t1 * c), 1.dp.toPx())
        }
    }
}

/** The two lines of text along the top of the bezel. Measured once per text, not per frame. */
@Composable
private fun BezelText(outer: Line, inner: Line, g: DialGeometry, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val outerStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
    val innerStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
    val o = remember(outer.text, g.size, density) {
        with(density) { arcLayout(measurer, outer.text.uppercase(), outerStyle, g.outerText.toPx(), 210f) }
    }
    val i = remember(inner.text, g.size, density) {
        with(density) { arcLayout(measurer, inner.text.uppercase(), innerStyle, g.innerText.toPx(), 170f) }
    }
    // Screen readers (and UI tests) get both lines as plain text.
    Box(modifier.semantics { contentDescription = outer.text.replaceFirstChar { it.uppercase() } }) {
        Box(Modifier.size(1.dp).semantics { contentDescription = inner.text.replaceFirstChar { it.uppercase() } })
    }
    Canvas(modifier) {
        drawArcText(o, g.outerText.toPx(), outer.color)
        drawArcText(i, g.innerText.toPx(), inner.color)
    }
}

/** Glyphs of [text] laid out one by one; shrunk (down to 7 sp) until they fit [maxSweep] degrees at [radius]. */
private fun arcLayout(measurer: TextMeasurer, text: String, style: TextStyle, radius: Float, maxSweep: Float): List<TextLayoutResult> {
    var st = style
    while (true) {
        val glyphs = text.map { measurer.measure(it.toString(), st) }
        val sweep = glyphs.sumOf { it.size.width.toDouble() } / radius * 180 / PI
        if (sweep <= maxSweep || st.fontSize.value <= 7f) return glyphs
        st = st.copy(fontSize = (st.fontSize.value - 0.5f).sp, letterSpacing = (st.letterSpacing.value * 0.85f).sp)
    }
}

/** Centred on 12 o'clock, each glyph turned to stand on the circle. */
private fun DrawScope.drawArcText(glyphs: List<TextLayoutResult>, radius: Float, color: Color) {
    val total = glyphs.sumOf { it.size.width.toDouble() }.toFloat()
    var a = -90f - (total / radius * 180f / PI.toFloat()) / 2
    for (l in glyphs) {
        val w = l.size.width.toFloat()
        val step = w / radius * 180f / PI.toFloat()
        rotate(a + step / 2 + 90f, pivot = center) {
            drawText(l, color = color, topLeft = Offset(center.x - w / 2, center.y - radius - l.size.height / 2f))
        }
        a += step
    }
}

/** A soft pulse behind a satellite that wants attention. */
@Composable
private fun Beacon(modifier: Modifier) {
    val v by rememberInfiniteTransition(label = "beacon").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "beacon")
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Brand.Cyan.copy(alpha = 0.45f * (1 - v)), r * (0.6f + 0.4f * v), style = Stroke(3.dp.toPx()))
    }
}

/**
 * Under the dial: live speed and ping while connected, and the way to the full details.
 * One tactile pill instead of a card: the dial already says where you are seen from.
 */
@Composable
fun StatsStrip(tunnel: TunnelState, onMore: () -> Unit) {
    val up = tunnel is TunnelState.Up
    val live = rememberLive(tunnel)
    val health by TunnelRuntime.health.collectAsState()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).tactile(onClick = onMore),
    ) {
        Row(Modifier.padding(horizontal = Space.M, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (up) {
                Stat("↓", "${formatBytes(live.down)}/с", Brand.Cyan, Modifier.weight(1f))
                Stat("↑", "${formatBytes(live.up)}/с", Brand.Violet, Modifier.weight(1f))
                Stat("пинг", health.rttMs?.let { "$it мс" } ?: "—", Brand.Mint, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(2.dp))
                Text("Подробнее", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!up) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(icon: String, value: String, accent: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(icon, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        FitText(value, MaterialTheme.typography.labelLarge.merge(TabularNumbers), min = 9.sp)
    }
}
