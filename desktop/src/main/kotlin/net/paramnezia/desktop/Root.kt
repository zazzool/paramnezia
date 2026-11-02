package net.paramnezia.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.paramnezia.app.App
import net.paramnezia.app.ui.AddScreen
import net.paramnezia.app.ui.FleetScreen
import net.paramnezia.app.ui.HomeScreen
import net.paramnezia.app.ui.SimpleScreen
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState

enum class Tab(val title: String) { HOME("Главная"), ADD("Добавить"), FLEET("Флот"), SETTINGS("Настройки") }

/**
 * The window's content: the same four tabs as the Android app, the same screens (shared code),
 * and a Windows settings screen. [incoming] carries keys from opened `.vpn` files.
 */
@Composable
fun Root(app: App, platform: DesktopUiPlatform, incoming: MutableSharedFlow<String>, initialTab: Tab = Tab.HOME, watchService: Boolean = true) {
    var tab by remember { mutableStateOf(initialTab) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    var pending by remember { mutableStateOf<String?>(null) }
    val ready by app.repo.ready.collectAsState()
    val simple by remember { app.repo.state.map { it.simpleUi }.distinctUntilChanged() }.collectAsState(initial = false)
    if (!ready) return

    LaunchedEffect(Unit) { platform.messages.collect { toast(it) } }
    LaunchedEffect(Unit) { incoming.collect { pending = it; tab = Tab.ADD } }
    LaunchedEffect(Unit) {
        // As on Android when the app opens: the admin's daily server check, a fleet sync if due.
        app.scope.launch { app.healServers() }
        app.fleet.syncIfDue(15 * 60_000L)
    }
    // Counters for the home screen, only while the window is open.
    LaunchedEffect(Unit) {
        while (true) {
            if (TunnelRuntime.state.value is TunnelState.Up) withContext(Dispatchers.IO) { app.tunnel.refreshStats() }
            delay(2_000)
        }
    }
    var serviceOk by remember { mutableStateOf(true) }
    if (watchService) LaunchedEffect(Unit) {
        while (true) {
            serviceOk = withContext(Dispatchers.IO) { app.client.available() }
            delay(if (serviceOk) 30_000 else 3_000)
        }
    }
    val onConnect: () -> Unit = { app.connect() }

    if (simple) {
        LaunchedEffect(pending) {
            val key = pending ?: return@LaunchedEffect
            pending = null
            runCatching { app.servers.import(key) }.onSuccess { toast("Готово"); onConnect() }.onFailure { toast(it.message ?: "Ошибка") }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.padding(padding)) {
                if (!serviceOk) ServiceBanner()
                SimpleScreen(app, Modifier.weight(1f), onConnect)
            }
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.ADD -> Icons.Filled.Add
                                    Tab.FLEET -> Icons.Filled.Share
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.title,
                            )
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            if (!serviceOk) ServiceBanner()
            val m = Modifier.weight(1f)
            when (tab) {
                Tab.HOME -> HomeScreen(app, m, onConnect, onAddServer = { tab = Tab.ADD })
                Tab.ADD -> AddScreen(app, m, pending, onConsumed = { pending = null }, toast = toast, onDone = { tab = Tab.HOME }, onConnect = onConnect)
                Tab.FLEET -> FleetScreen(app, m, toast, onAddServer = { tab = Tab.ADD })
                Tab.SETTINGS -> SettingsScreen(app, platform, m, toast, askService = watchService)
            }
        }
    }
}

/** The tunnel lives in the Paramnezia service; without it the button cannot work. */
@Composable
private fun ServiceBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "Служба Paramnezia не отвечает, VPN не включится. Переустановите Paramnezia или запустите службу «Paramnezia VPN» (services.msc).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
