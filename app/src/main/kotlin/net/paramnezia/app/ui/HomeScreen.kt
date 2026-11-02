package net.paramnezia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.paramnezia.app.App
import net.paramnezia.app.ota.Updater
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import net.paramnezia.tunnel.TunnelStats

fun TunnelState.power(): PowerState = when (this) {
    is TunnelState.Up -> PowerState.ON
    is TunnelState.Connecting -> PowerState.CONNECTING
    else -> PowerState.OFF
}

/**
 * The home screen fits one screen, without scrolling: header, the button (sized to the space
 * left), the status, and a compact info panel. Everything else is one tap away in a sheet.
 * With no server at all, the button gives way to an "add a server" hero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: App, modifier: Modifier, onConnect: () -> Unit, onAddServer: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    val servers = rememberServerCount(app)
    val p = protection(tunnel, servers)
    val platform = LocalUiPlatform.current
    val paste = rememberPasteKey(app) { platform.toast(it) }
    var details by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = Space.M + 4.dp).padding(top = Space.S, bottom = Space.M)) {
        TopBar(app, tunnel, servers, onDetails = { details = true })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val button = buttonFor(maxHeight, maxWidth, 216.dp)
            if (p == Protection.NO_SERVER) {
                // Normally fits; the scroll is only a safety net for very small or zoomed screens.
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyHero(follower = false, onAdd = onAddServer, onPaste = paste, modifier = Modifier.padding(vertical = Space.M))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerButton(
                        tunnel.power(),
                        onClick = { if (tunnel.power() == PowerState.OFF) onConnect() else app.disconnect() },
                        size = button,
                    )
                    StatusBlock(tunnel, p)
                }
            }
        }
        if (p != Protection.NO_SERVER) InfoPanel(app, tunnel, onMore = { details = true })
    }

    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false }) {
            DetailsSheet(app, tunnel)
        }
    }
}

/** Title, update badge and the server chip (a menu when there are several standalone servers). */
@Composable
private fun TopBar(app: App, tunnel: TunnelState, servers: Int, onDetails: () -> Unit) {
    val state by app.repo.state.collectAsState()
    val update by app.updater.status.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText("Paramnezia", MaterialTheme.typography.titleLarge, Modifier.weight(1f), min = 14.sp)
        (update as? Updater.Status.Available)?.let { u ->
            Surface(shape = CircleShape, color = Brand.Violet, modifier = Modifier.clip(CircleShape).tactile { app.updater.openDownload() }) {
                FitText("${u.release.versionName} ↓", MaterialTheme.typography.labelLarge, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White)
            }
            Spacer(Modifier.width(Space.S))
        }
        val server = when (tunnel) { is TunnelState.Up -> tunnel.serverName; is TunnelState.Connecting -> tunnel.serverName; else -> null }
        val pickable = state.fleet == null && state.profiles.size > 1
        val (label, dot) = when {
            server != null -> server to (if (tunnel is TunnelState.Up) Brand.Mint else Brand.Amber)
            servers == 0 -> "нет сервера" to Brand.Coral
            pickable -> (state.profiles.firstOrNull { it.id == state.activeProfile } ?: state.profiles.first()).name to MaterialTheme.colorScheme.outline
            else -> "серверов: $servers" to MaterialTheme.colorScheme.outline
        }
        Box {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clip(CircleShape).tactile { if (pickable) menu = true else onDetails() }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 160.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(Space.S))
                    FitText(label + if (pickable) " ▾" else "", MaterialTheme.typography.labelLarge, min = 9.sp)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (pr in state.profiles) {
                    DropdownMenuItem(
                        text = { Text(pr.name) },
                        leadingIcon = { RadioButton(selected = pr.id == (state.activeProfile ?: state.profiles.first().id), onClick = null) },
                        onClick = {
                            menu = false
                            app.repo.update { it.copy(activeProfile = pr.id) }
                            app.reconnectIfUp()
                        },
                    )
                }
            }
        }
    }
}

/** Everything that does not fit the main screen: full address details, counters, trackers. */
@Composable
fun DetailsSheet(app: App, tunnel: TunnelState) {
    val state by app.repo.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.M, vertical = Space.S),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        VisibilityCard(app, tunnel)
        if (tunnel is TunnelState.Up) LiveStats(state.settings.blockTrackers)
        Spacer(Modifier.height(Space.L))
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
        Column(Modifier.padding(Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                FitText(title, MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, min = 9.sp)
            }
            FitText(value, MaterialTheme.typography.titleLarge.merge(TabularNumbers), min = 12.sp)
            FitText(sub, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, min = 8.sp)
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
