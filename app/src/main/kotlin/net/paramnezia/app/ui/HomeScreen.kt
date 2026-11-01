package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.paramnezia.app.App
import net.paramnezia.app.ota.Updater
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import kotlinx.coroutines.delay
import net.paramnezia.tunnel.TunnelStats

fun TunnelState.power(): PowerState = when (this) {
    is TunnelState.Up -> PowerState.ON
    is TunnelState.Connecting -> PowerState.CONNECTING
    else -> PowerState.OFF
}

@Composable
fun HomeScreen(app: App, modifier: Modifier, onConnect: () -> Unit, onAddServer: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    val state by app.repo.state.collectAsState()
    val update by app.updater.status.collectAsState()
    val servers = rememberServerCount(app)
    val p = protection(tunnel, servers)
    val context = androidx.compose.ui.platform.LocalContext.current
    val paste = rememberPasteKey(app) { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(tunnel, servers)
        ProtectionPill(p)
        PowerButton(
            tunnel.power(),
            onClick = {
                when {
                    tunnel.power() != PowerState.OFF -> app.disconnect()
                    servers == 0 -> onAddServer()
                    else -> onConnect()
                }
            },
            dimmed = p == Protection.NO_SERVER,
        )
        StatusLine(tunnel, p)
        if (p == Protection.NO_SERVER) NoServersCard(follower = false, onAdd = onAddServer, onPaste = paste)
        if (tunnel is TunnelState.Up) LiveStats(state.settings.blockTrackers)
        if (p != Protection.NO_SERVER) VisibilityCard(app, tunnel)

        when (val u = update) {
            is Updater.Status.Available -> ActionCard("Доступна версия ${u.release.versionName}", "Скачать") {
                if (!app.updater.openDownload()) android.widget.Toast.makeText(app, "Нет ссылки на APK", android.widget.Toast.LENGTH_SHORT).show()
            }
            Updater.Status.Idle -> {}
        }

        // Fleet servers are chosen automatically; standalone ones by the user.
        if (state.fleet == null && state.profiles.size > 1) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сервер", style = MaterialTheme.typography.titleMedium)
                    for (pr in state.profiles) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pr.id == (state.activeProfile ?: state.profiles.first().id), onClick = {
                                app.repo.update { it.copy(activeProfile = pr.id) }
                                app.reconnectIfUp()
                            })
                            Text(pr.name)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(tunnel: TunnelState, servers: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Paramnezia", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        val server = when (tunnel) { is TunnelState.Up -> tunnel.serverName; is TunnelState.Connecting -> tunnel.serverName; else -> null }
        val (label, dot) = when {
            server != null -> server to (if (tunnel is TunnelState.Up) Brand.Mint else Brand.Amber)
            servers == 0 -> "нет сервера" to Brand.Coral
            else -> "серверов: $servers" to MaterialTheme.colorScheme.outline
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatusLine(tunnel: TunnelState, p: Protection) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel) { while (tunnel is TunnelState.Up) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(p, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { s ->
            Text(
                when (s) {
                    Protection.ON -> "VPN включён"
                    Protection.CONNECTING -> "Подключаюсь…"
                    Protection.FAILED -> "Нет соединения"
                    Protection.OFF -> "VPN выключен"
                    Protection.NO_SERVER -> "Сервер не добавлен"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = if (s == Protection.ON || s == Protection.CONNECTING) protectionColor(s) else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            if (tunnel is TunnelState.Up) "${tunnel.serverName} · ${formatDuration(now - tunnel.since)}" else tapHint(p),
            style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Speed, totals, ping and blocked trackers; read only while visible, every 2 seconds. */
@Composable
private fun LiveStats(blockTrackers: Boolean) {
    val health by TunnelRuntime.health.collectAsState()
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    var blocked by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var prev: TunnelStats? = null
        var prevAt = 0L
        while (true) {
            val s = TunnelRuntime.stats()
            val t = System.currentTimeMillis()
            if (s != null) {
                prev?.let { p -> val dt = (t - prevAt).coerceAtLeast(1); down = (s.rxBytes - p.rxBytes) * 1000 / dt; up = (s.txBytes - p.txBytes) * 1000 / dt }
                rx = s.rxBytes; tx = s.txBytes; prev = s; prevAt = t
            }
            blocked = TunnelRuntime.blockedQueries()
            delay(2_000)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Загрузка", "${formatBytes(down)}/с", "всего ${formatBytes(rx)}", Brand.Cyan, Modifier.weight(1f))
        StatTile("Отдача", "${formatBytes(up)}/с", "всего ${formatBytes(tx)}", Brand.Violet, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Пинг", health.rttMs?.let { "$it мс" } ?: "—", if (health.reconnects > 0) "переподключений: ${health.reconnects}" else "соединение живо", Brand.Mint, Modifier.weight(1f))
        if (blockTrackers) StatTile("Трекеры", blocked.toString(), "заблокировано", Brand.Coral, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, accent: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ActionCard(text: String, action: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (action != null) FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
