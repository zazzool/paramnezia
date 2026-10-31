package io.paramnezia.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.paramnezia.app.App
import io.paramnezia.app.net.SeenAddress
import io.paramnezia.app.net.Visibility
import io.paramnezia.app.net.VisibilityCheck
import io.paramnezia.app.ota.Updater
import io.paramnezia.tunnel.TunnelRuntime
import io.paramnezia.tunnel.TunnelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun TunnelState.power(): PowerState = when (this) {
    is TunnelState.Up -> PowerState.ON
    is TunnelState.Connecting -> PowerState.CONNECTING
    else -> PowerState.OFF
}

@Composable
fun HomeScreen(app: App, modifier: Modifier, onConnect: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    val state by app.repo.state.collectAsState()
    val update by app.updater.status.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(tunnel)
        PowerButton(tunnel.power(), onClick = { if (tunnel.power() == PowerState.OFF) onConnect() else app.disconnect() })
        StatusLine(tunnel)
        if (tunnel is TunnelState.Up) LiveStats(state.settings.blockTrackers)

        VisibilityCard(app, tunnel)

        when (val u = update) {
            is Updater.Status.Available -> ActionCard("Доступна версия ${u.release.versionName}", "Обновить") { scope.launch { app.updater.install() } }
            is Updater.Status.Downloading -> ActionCard("Загружаю версию ${u.release.versionName}…", null) {}
            is Updater.Status.Ready -> ActionCard("Версия ${u.release.versionName} загружена", "Установить") { scope.launch { app.updater.install() } }
            is Updater.Status.Installing -> ActionCard("Подтвердите установку версии ${u.release.versionName}", "Ещё раз") { scope.launch { app.updater.install() } }
            is Updater.Status.Failed -> ActionCard("Обновление не удалось: ${u.reason}", "Повторить") { scope.launch { app.updater.install() } }
            Updater.Status.Idle -> {}
        }

        // Fleet servers are chosen automatically; standalone ones by the user.
        if (state.fleet == null && state.profiles.size > 1) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сервер", style = MaterialTheme.typography.titleMedium)
                    for (p in state.profiles) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = p.id == (state.activeProfile ?: state.profiles.first().id), onClick = {
                                app.repo.update { it.copy(activeProfile = p.id) }
                                app.reconnectIfUp()
                            })
                            Text(p.name)
                        }
                    }
                }
            }
        }
        if (state.fleet == null && state.profiles.isEmpty()) {
            Text("Добавьте сервер: вставьте ключ vpn://, отсканируйте QR или разверните свой VPS на вкладке «Добавить».",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(tunnel: TunnelState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Paramnezia", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        val server = when (tunnel) { is TunnelState.Up -> tunnel.serverName; is TunnelState.Connecting -> tunnel.serverName; else -> null }
        if (server != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (tunnel is TunnelState.Up) Brand.Mint else Brand.Amber))
                    Spacer(Modifier.width(8.dp))
                    Text(server, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(tunnel: TunnelState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel) { while (tunnel is TunnelState.Up) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(tunnel.power(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { p ->
            Text(
                when (p) { PowerState.ON -> "Защищено"; PowerState.CONNECTING -> "Подключаюсь…"; PowerState.OFF -> if (tunnel is TunnelState.Failed) "Нет соединения" else "Отключено" },
                style = MaterialTheme.typography.headlineMedium,
                color = when (p) { PowerState.ON -> Brand.Mint; PowerState.CONNECTING -> Brand.Amber; PowerState.OFF -> MaterialTheme.colorScheme.onSurface },
            )
        }
        Text(
            when (tunnel) {
                is TunnelState.Up -> formatDuration(now - tunnel.since)
                is TunnelState.Failed -> "Нажмите кнопку, чтобы попробовать ещё раз"
                is TunnelState.Connecting -> "Устанавливаю защищённое соединение"
                TunnelState.Down -> "Нажмите кнопку, чтобы включить VPN"
            },
            style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        var prev: io.paramnezia.tunnel.TunnelStats? = null
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

/**
 * "How sites see you": the address foreign sites get (the server's, through the tunnel) and the one
 * Russian sites get (your own, direct). Refreshed after every (re)connect while on screen.
 */
@Composable
private fun VisibilityCard(app: App, tunnel: TunnelState) {
    val generation by TunnelRuntime.generation.collectAsState()
    val v by app.visibility.collectAsState()
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refresh() { if (!loading) scope.launch { loading = true; app.refreshVisibility(); loading = false } }
    val settled = tunnel is TunnelState.Up || tunnel == TunnelState.Down
    LaunchedEffect(generation, settled) { if (settled) { delay(1_200); refresh() } }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(Color.Transparent),
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Brand.Violet.copy(alpha = 0.22f), Brand.Cyan.copy(alpha = 0.14f))))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Как вас видят сайты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else TextButton(onClick = ::refresh) { Text("Обновить") }
            }
            SeenRow("🌍", "Зарубежные", v?.foreign, loading && v == null)
            SeenRow("🇷🇺", "Российские", v?.russian, loading && v == null)
            val verdict = verdict(v, tunnel)
            AnimatedVisibility(verdict != null) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(verdict.orEmpty(), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun verdict(v: Visibility?, tunnel: TunnelState): String? = when {
    v == null -> null
    tunnel !is TunnelState.Up -> "VPN выключен: все сайты видят ваш настоящий адрес."
    v.split -> "Раздельный туннель работает: зарубежные сайты видят адрес сервера, российские сервисы и банки видят ваш обычный российский адрес."
    v.foreign != null && v.russian != null -> "Оба вида сайтов видят один адрес. Проверьте, включено ли «Российское напрямую» в настройках."
    else -> "Не удалось проверить один из адресов. Нажмите «Обновить»."
}

@Composable
private fun SeenRow(emoji: String, who: String, a: SeenAddress?, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$who сайты видят", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when { a != null -> a.ip; loading -> "проверяю…"; else -> "не удалось узнать" },
                style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val place = listOfNotNull(a?.let { VisibilityCheck.flag(it.countryCode) }, a?.country, a?.city).joinToString(" ").takeIf { it.isNotBlank() && a?.country != null }
            val line = listOfNotNull(place, a?.org).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
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
