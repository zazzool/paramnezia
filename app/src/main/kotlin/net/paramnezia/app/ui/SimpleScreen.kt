package net.paramnezia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import net.paramnezia.app.App
import net.paramnezia.app.data.UiMode
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import net.paramnezia.tunnel.TunnelStats
import net.paramnezia.app.ota.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One button for follower devices: servers, routing, updates and failover are handled by the fleet.
 * A long press on the title opens the full mode (for whoever looks after this phone).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimpleScreen(app: App, modifier: Modifier, onConnect: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    var stats by remember { mutableStateOf<TunnelStats?>(null) }
    var blocked by remember { mutableLongStateOf(0L) }
    var askFull by remember { mutableStateOf(false) }
    val update by app.updater.status.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(tunnel) {
        while (tunnel is TunnelState.Up) {
            stats = TunnelRuntime.stats(); blocked = TunnelRuntime.blockedQueries(); delay(2_000)
        }
    }

    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Text("Paramnezia", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { askFull = true }))
        PowerButton(tunnel.power(), onClick = { if (tunnel.power() == PowerState.OFF) onConnect() else app.disconnect() }, size = 232.dp)
        Text(when (val t = tunnel) {
            is TunnelState.Up -> "Защищено"
            is TunnelState.Connecting -> "Подключаюсь…"
            is TunnelState.Failed -> "Не удалось подключиться"
            TunnelState.Down -> "Нажмите, чтобы включить"
        }, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = when (tunnel.power()) {
            PowerState.ON -> Brand.Mint; PowerState.CONNECTING -> Brand.Amber; PowerState.OFF -> MaterialTheme.colorScheme.onSurface
        })
        stats?.takeIf { tunnel is TunnelState.Up }?.let {
            Text("↓ ${formatBytes(it.rxBytes)}   ↑ ${formatBytes(it.txBytes)}   трекеров: $blocked", style = MaterialTheme.typography.bodyMedium)
        }
        // The browser downloads the APK; Android's installer asks the user, as with the first install.
        (update as? Updater.Status.Available)?.let { u ->
            androidx.compose.material3.OutlinedButton(onClick = { app.updater.openDownload() }) {
                Text("Скачать версию ${u.release.versionName}")
            }
        }
    }

    if (askFull) {
        AlertDialog(
            onDismissRequest = { askFull = false },
            title = { Text("Полный режим") },
            text = { Text("Показать все настройки на этом устройстве?") },
            confirmButton = { TextButton(onClick = { askFull = false; app.repo.update { it.copy(uiMode = UiMode.FULL) } }) { Text("Показать") } },
            dismissButton = { TextButton(onClick = { askFull = false }) { Text("Отмена") } },
        )
    }
}
