package io.paramnezia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.paramnezia.app.App
import io.paramnezia.app.ota.Updater
import io.paramnezia.tunnel.TunnelRuntime
import io.paramnezia.tunnel.TunnelState
import io.paramnezia.tunnel.TunnelStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(app: App, modifier: Modifier, onConnect: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    val state by app.repo.state.collectAsState()
    val update by app.updater.status.collectAsState()
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<TunnelStats?>(null) }
    var blocked by remember { mutableLongStateOf(0L) }

    // Counters are read only while this screen is visible, once every two seconds.
    LaunchedEffect(tunnel) {
        while (tunnel is TunnelState.Up) {
            stats = TunnelRuntime.stats()
            blocked = TunnelRuntime.blockedQueries()
            delay(2_000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        val (label, color) = when (val t = tunnel) {
            is TunnelState.Up -> "Подключено: ${t.serverName}" to MaterialTheme.colorScheme.primary
            is TunnelState.Connecting -> "Подключаюсь к ${t.serverName}…" to MaterialTheme.colorScheme.tertiary
            is TunnelState.Failed -> "Ошибка: ${t.reason}" to MaterialTheme.colorScheme.error
            TunnelState.Down -> "Отключено" to MaterialTheme.colorScheme.outline
        }
        val up = tunnel is TunnelState.Up || tunnel is TunnelState.Connecting
        Button(
            onClick = { if (up) app.disconnect() else onConnect() },
            shape = CircleShape,
            modifier = Modifier.size(180.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color),
        ) { Text(if (up) "Отключить" else "Подключить", style = MaterialTheme.typography.titleLarge) }
        Text(label, style = MaterialTheme.typography.titleMedium)

        stats?.takeIf { tunnel is TunnelState.Up }?.let { s ->
            Text("↓ ${human(s.rxBytes)}   ↑ ${human(s.txBytes)}", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.settings.blockTrackers && tunnel is TunnelState.Up) {
            Text("Заблокировано трекеров и рекламы: $blocked", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.settings.bypassRussia) {
            Text("Российские сайты и приложения идут напрямую", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        when (val u = update) {
            is Updater.Status.Available -> UpdateCard("Доступна версия ${u.release.versionName}", "Обновить") { scope.launch { app.updater.install() } }
            is Updater.Status.Downloading -> UpdateCard("Загружаю версию ${u.release.versionName}…", null) {}
            is Updater.Status.Failed -> UpdateCard("Обновление не удалось: ${u.reason}", "Повторить") { scope.launch { app.updater.install() } }
            else -> {}
        }

        // Server choice: fleet servers are chosen automatically; standalone ones by the user.
        if (state.fleet == null && state.profiles.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Сервер", fontWeight = FontWeight.Bold)
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
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UpdateCard(text: String, action: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            if (action != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private fun human(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
