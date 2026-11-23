package net.paramnezia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import net.paramnezia.app.platform.UpdateInstaller
import net.paramnezia.core.ota.Release
import net.paramnezia.app.App
import net.paramnezia.app.data.UiMode
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.app.ota.Updater

/**
 * One button for follower devices: servers, routing, updates and failover are handled by the fleet.
 * A long press on the title opens the full mode (for whoever looks after this phone).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreen(
    app: App,
    modifier: Modifier,
    onConnect: () -> Unit,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val tunnel by TunnelRuntime.state.collectAsState()
    var askFull by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var serverList by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf<String?>(null) }
    val servers = rememberServerCount(app)
    backHandler(serverList) {
        if (selectedServer != null) selectedServer = null else serverList = false
    }
    selectedServer?.let { id ->
        ServerScreen(app, id, modifier) { selectedServer = null }
        return
    }
    if (serverList) {
        val state by app.repo.state.collectAsState()
        val entries = remember(state.profiles, state.fleet?.manifest) { app.insights.views() }
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.M),
        ) {
            ServerHeader("Серверы", null, onBack = { serverList = false })
            if (entries.isEmpty()) Text("Серверов пока нет")
            else Group {
                entries.forEach { server ->
                    GroupRow(server.name, subtitle = server.endpoint, badge = server.role, icon = Glyph.Server,
                        onClick = { selectedServer = server.id }, trailing = { Icon(PnIcons.ChevronRight, null) })
                }
            }
        }
        return
    }
    val health by TunnelRuntime.health.collectAsState()
    val p = protection(tunnel, servers, health)
    val platform = LocalUiPlatform.current
    val paste = rememberPasteKey(app) { platform.toast(it) }

    // Same rhythm as the full screen, one screen high: title, the button (or the "no servers"
    // hero), the status and the info panel; details one tap away.
    Column(modifier.fillMaxSize().padding(horizontal = Space.M + 4.dp).padding(top = Space.S, bottom = Space.M),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { askFull = true }))
            UpdateAction(app.updater)
        }
        if (servers > 0) TextButton(onClick = { serverList = true }) { Text("Серверы") }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val button = buttonFor(maxHeight, maxWidth, 232.dp)
            if (p == Protection.NO_SERVER) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NearbyOffers(app, { platform.toast(it) }, onDone = onConnect)
                        EmptyHero(follower = true, onAdd = null, onPaste = paste, modifier = Modifier.padding(vertical = Space.M))
                    }
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
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { details = false }) {
            if (app.fleet.stored != null) {
                androidx.compose.material3.TextButton(onClick = { details = false; paste() }) {
                    Text("Вставить обновление семьи")
                }
            }
            DetailsSheet(app, tunnel)
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


/** The same update entry point in both modes and application settings. */
@Composable
fun UpdateAction(updater: Updater) {
    val update by updater.status.collectAsState()
    val delivery by updater.installer.state.collectAsState()
    val checking by updater.installer.checking.collectAsState()
    val installing by updater.installer.installing.collectAsState()
    val notice by updater.installer.notice.collectAsState()
    val platform = LocalUiPlatform.current
    LaunchedEffect(notice) {
        notice?.let {
            if (updater.installer.dismissNotice(it)) platform.toast(it)
        }
    }
    val release: Release? = (update as? Updater.Status.Available)?.release
    val state = delivery
    when {
        state is UpdateInstaller.State.Downloading -> {
            OutlinedButton(onClick = updater.installer::cancel) { Text("Отмена · ${state.percent}%", maxLines = 1) }
        }
        release != null && (state as? UpdateInstaller.State.Ready)?.release == release -> {
            Button(enabled = !installing, onClick = { updater.installer.startInstall(updater::installUpdate) }) {
                Text(if (installing) "Проверяем файл…" else "Установить", maxLines = 1)
            }
        }
        release != null -> {
            Button(enabled = !installing, onClick = updater::downloadUpdate) {
                Text(if (state is UpdateInstaller.State.Failed) "Повторить загрузку" else "Обновить ${release.versionName}", maxLines = 1)
            }
        }
        else -> {
            OutlinedButton(enabled = !checking, onClick = { updater.installer.check(updater::checkForUpdate) }) {
                Text(if (checking) "Проверяем…" else "Обновления", maxLines = 1)
            }
        }
    }
}
