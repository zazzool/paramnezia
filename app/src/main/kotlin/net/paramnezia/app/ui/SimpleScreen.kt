package net.paramnezia.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
fun SimpleScreen(app: App, modifier: Modifier, onConnect: () -> Unit) {
    val tunnel by TunnelRuntime.state.collectAsState()
    var askFull by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val update by app.updater.status.collectAsState()
    val servers = rememberServerCount(app)
    val p = protection(tunnel, servers)
    val platform = LocalUiPlatform.current
    val paste = rememberPasteKey(app) { platform.toast(it) }

    // Same rhythm as the full screen, one screen high: title, the button (or the "no servers"
    // hero), the status and the info panel; details one tap away.
    Column(modifier.fillMaxSize().padding(horizontal = Space.M + 4.dp).padding(top = Space.S, bottom = Space.M),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { askFull = true }))
            (update as? Updater.Status.Available)?.let { u ->
                Surface(shape = CircleShape, color = Brand.Violet, modifier = Modifier.clip(CircleShape).tactile { app.updater.openDownload() }) {
                    FitText("${u.release.versionName} ↓", MaterialTheme.typography.labelLarge, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val button = buttonFor(maxHeight, maxWidth, 232.dp)
            if (p == Protection.NO_SERVER) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyHero(follower = true, onAdd = null, onPaste = paste, modifier = Modifier.padding(vertical = Space.M))
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
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { details = false }) { DetailsSheet(app, tunnel) }
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
