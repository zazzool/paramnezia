package net.paramnezia.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import net.paramnezia.app.net.SeenAddress
import net.paramnezia.app.net.Visibility
import net.paramnezia.app.net.VisibilityCheck
import net.paramnezia.app.net.RuSource
import net.paramnezia.app.net.countryName
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState

/** Number of servers this device can use; recomputed when the stored state changes. */
@Composable
fun rememberServerCount(app: App): Int {
    val state by app.repo.state.collectAsState()
    return remember(state.fleet?.manifest, state.profiles) { app.serverCount() }
}

/** Protection state in one word, for the pill above the button. */
enum class Protection(val label: String) {
    ON("Защищено"), CONNECTING("Подключение…"), OFF("Не защищено"), FAILED("Нет соединения"), NO_SERVER("Нет сервера")
}

fun protection(tunnel: TunnelState, servers: Int): Protection = when {
    tunnel is TunnelState.Up -> Protection.ON
    tunnel is TunnelState.Connecting -> Protection.CONNECTING
    servers == 0 -> Protection.NO_SERVER
    tunnel is TunnelState.Failed -> Protection.FAILED
    else -> Protection.OFF
}

@Composable
fun protectionColor(p: Protection): Color = when (p) {
    Protection.ON -> Brand.Mint
    Protection.CONNECTING -> Brand.Amber
    Protection.OFF, Protection.FAILED, Protection.NO_SERVER -> Brand.Coral
}

/** Imports whatever key is on the clipboard (the quickest path from a messenger). */
@Composable
fun rememberPasteKey(app: App, onDone: (String) -> Unit): () -> Unit {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return {
        val text = clipboard.getText()?.text?.trim()
        if (text.isNullOrEmpty()) onDone("Буфер обмена пуст: скопируйте ключ vpn://")
        else scope.launch {
            runCatching { app.servers.import(text) }
                .onSuccess { onDone("Сервер добавлен") }
                .onFailure { onDone(it.message ?: "Это не ключ vpn:// и не конфиг") }
        }
    }
}

/**
 * Keeps [App.visibility] fresh: re-checks after every (re)connect and disconnect once the state has
 * settled, and hands back the result only if it belongs to the current state.
 */
@Composable
fun rememberVisibility(app: App, tunnel: TunnelState): Triple<Visibility?, Boolean, () -> Unit> {
    val generation by TunnelRuntime.generation.collectAsState()
    val v by app.visibility.collectAsState()
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = { if (!loading) scope.launch { loading = true; runCatching { app.refreshVisibility() }; loading = false } }
    val key = remember(tunnel, generation) { app.visibilityKey() }
    val settled = tunnel is TunnelState.Up || tunnel == TunnelState.Down || tunnel is TunnelState.Failed
    LaunchedEffect(key, settled) { if (settled && v?.key != key) { delay(1_200); refresh() } }
    return Triple(v?.takeIf { it.key == key }, loading, refresh)
}

/**
 * "How sites see you": the address foreign sites get (the server's, through the tunnel) and the one
 * Russian sites get (your own, direct), each with its country by the vote of several databases.
 */
@Composable
fun VisibilityCard(app: App, tunnel: TunnelState) {
    val (v, loading, refresh) = rememberVisibility(app, tunnel)
    val up = tunnel is TunnelState.Up
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Как вас видят сайты", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when { loading -> "проверяю…"; v != null -> "проверено ${ago(now - v.checkedAt)}"; else -> "ещё не проверено" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Обновить") }
                }
            }
            SeenRow("Зарубежные сайты", if (up) Route.TUNNEL else Route.DIRECT, v?.foreign, loading, v?.foreignError)
            // "Through VPN" only when the check proved it (both sides saw the same address).
            val ruRoute = if (up && v?.russian != null && v.foreign != null && !v.split) Route.TUNNEL else Route.DIRECT
            SeenRow("Российские сайты", ruRoute, v?.russian, loading, v?.russianError)
            val notes = notes(v, tunnel)
            AnimatedVisibility(notes.isNotEmpty()) {
                Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { notes.forEach { NoteRow(it) } }
            }
        }
    }
}

enum class Route(val label: String) { TUNNEL("через VPN"), DIRECT("напрямую") }

@Composable
private fun SeenRow(who: String, route: Route, a: SeenAddress?, loading: Boolean, error: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(if (a?.countryCode != null) VisibilityCheck.flag(a.countryCode) else "🌐", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(who, MaterialTheme.typography.labelMedium, Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, min = 8.sp)
                Spacer(Modifier.width(8.dp))
                RouteBadge(route)
            }
            Text(
                when {
                    a?.country != null -> listOfNotNull(a.country, a.place).joinToString(", ")
                    a != null -> "Страна не определена"
                    loading -> "Проверяю…"
                    else -> "Не удалось узнать"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (a == null && !loading && error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a != null) {
                // IPv6 addresses are long: the address shrinks to fit rather than being cut.
                FitText(
                    a.ip,
                    MaterialTheme.typography.bodyMedium.merge(TabularNumbers).copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                    min = 8.sp,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(a.ip))
                        Toast.makeText(context, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                    },
                )
                a.org?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun RouteBadge(route: Route) {
    val c = if (route == Route.TUNNEL) Brand.Mint else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = c.copy(alpha = 0.14f)) {
        Text(route.label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = c,
            maxLines = 1, softWrap = false)
    }
}

private data class Note(val ok: Boolean?, val text: String)

@Composable
private fun NoteRow(n: Note) {
    val color = when (n.ok) { true -> Brand.Mint; false -> Brand.Amber; null -> MaterialTheme.colorScheme.onSurfaceVariant }
    Row(verticalAlignment = Alignment.Top) {
        Icon(when (n.ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Warning; null -> Icons.Filled.Info }, null,
            Modifier.size(16.dp).padding(top = 2.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(n.text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Plain-language findings under the two addresses. */
private fun notes(v: Visibility?, tunnel: TunnelState): List<Note> {
    v ?: return emptyList()
    val out = ArrayList<Note>()
    val up = tunnel is TunnelState.Up
    if (!up) out += Note(false, "VPN выключен: все сайты видят ваш настоящий адрес.")
    else when {
        v.split -> out += Note(true, "Раздельный туннель работает: зарубежные сайты видят сервер, российские сервисы и банки видят ваш обычный адрес.")
        v.foreign != null && v.russian != null -> out += Note(false, "Оба вида сайтов видят один адрес. Проверьте, включено ли «Российское напрямую» в настройках.")
        else -> out += Note(false, "Не удалось проверить один из адресов. Нажмите «Обновить».")
    }
    if (up && v.russianSource == RuSource.CARRIER) out += Note(null, "Яндекс не ответил, поэтому российский адрес узнан по сети телефона в обход VPN: этим путём идут российские сайты.")
    if (up && v.foreignIsServer == true) out += Note(true, "Зарубежный адрес совпадает с адресом вашего сервера.")
    if (up && v.foreignIsServer == false) out += Note(null, "Сервер выходит в интернет с другого адреса, чем принимает подключения. Так бывает у хостеров, это не утечка.")
    for ((side, a) in listOf("зарубежного" to v.foreign, "российского" to v.russian.takeIf { it?.ip != v.foreign?.ip })) {
        val g = a?.geo ?: continue
        if (g.countryCode != null && g.others.isNotEmpty()) {
            val alt = g.others.entries.joinToString { "${countryName(it.key) ?: it.key} (${it.value})" }
            out += Note(if (g.confident) null else false,
                "Базы геолокации расходятся для $side адреса: ${countryName(g.countryCode)} (${g.agreeing}) или $alt. Сайты могут считать по-разному.")
        }
    }
    v.foreign?.geo?.takeIf { it.others.isEmpty() && it.answered >= 2 }?.let {
        out += Note(true, "Страна подтверждена ${it.answered} базами геолокации.")
    }
    return out
}

fun ago(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 30 -> "только что"
        s < 90 -> "минуту назад"
        s < 3600 -> "${s / 60} мин назад"
        else -> "${s / 3600} ч назад"
    }
}

/** Shared by both screens: the cheap button label that tells what a tap will do. */
fun tapHint(p: Protection): String = when (p) {
    Protection.ON -> "Нажмите, чтобы отключить"
    Protection.CONNECTING -> "Устанавливаю защищённое соединение"
    Protection.FAILED -> "Нажмите, чтобы попробовать ещё раз"
    Protection.OFF -> "Нажмите, чтобы включить VPN"
    Protection.NO_SERVER -> "Сначала добавьте сервер"
}

