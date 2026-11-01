package net.paramnezia.app.ui

import net.paramnezia.app.tunnel.readUpTo

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.paramnezia.app.App
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as App

    /** Keys that arrived by link, share or file; handled by the Add screen. */
    private val incoming = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * True when Android refused to make us the VPN. Besides "Cancel" in the system dialog, this is what
     * happens when another app is set as the always-on VPN: the dialog closes by itself and nothing is
     * granted, so the user needs to be told where to switch it off.
     */
    private val vpnRefused = MutableStateFlow(false)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.vpnPermissionIntent() == null) app.connect() else vpnRefused.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            PnTheme {
                Root(app, incoming, onConnect = ::connect)
                val refused by vpnRefused.collectAsState()
                if (refused) VpnRefusedDialog(
                    onSettings = {
                        vpnRefused.value = false
                        runCatching { startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
                    },
                    onDismiss = { vpnRefused.value = false },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Admin: the daily server check runs while the admin is looking at the app, not in the background.
        app.scope.launch { app.healServers() }
        // Everyone in a fleet: opening the app looks for a newer configuration (at most every 15 min).
        app.fleet.syncIfDue(15 * 60_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "vpn") uri.toString()
                // A settings file is a few KB; anything big is not ours.
                else runCatching { contentResolver.openInputStream(uri)?.use { it.readUpTo(256 * 1024).decodeToString() } }.getOrNull()
            }
            // Shared to Paramnezia: a message with a key, or a settings file.
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { "vpn://" in it || "[Interface]" in it }
                ?: streamOf(intent)?.let { uri -> runCatching { contentResolver.openInputStream(uri)?.use { it.readUpTo(256 * 1024).decodeToString() } }.getOrNull() }
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        incoming.tryEmit(text)
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    fun connect() {
        val prepare = app.vpnPermissionIntent()
        if (prepare != null) vpnPermission.launch(prepare) else app.connect()
    }
}

private enum class Tab(val title: String) { HOME("Главная"), ADD("Добавить"), FLEET("Флот"), SETTINGS("Настройки") }

@Composable
private fun Root(app: App, incoming: MutableSharedFlow<String>, onConnect: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    var pending by remember { mutableStateOf<String?>(null) }

    val ready by app.repo.ready.collectAsState()
    val simple by remember { app.repo.state.map { it.simpleUi }.distinctUntilChanged() }.collectAsState(initial = false)
    // The vault is read in parallel with the first frame; wait those few ms rather than flash the wrong mode.
    if (!ready) return

    LaunchedEffect(Unit) { incoming.collect { pending = it; tab = Tab.ADD } }

    if (simple) {
        // One-button mode still accepts a new key or invite opened from a messenger.
        LaunchedEffect(pending) {
            val key = pending ?: return@LaunchedEffect
            pending = null
            runCatching { app.servers.import(key) }.onSuccess { toast("Готово"); onConnect() }.onFailure { toast(it.message ?: "Ошибка") }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding -> SimpleScreen(app, Modifier.padding(padding), onConnect) }
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
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(app, m, onConnect)
            Tab.ADD -> AddScreen(app, m, pending, onConsumed = { pending = null }, toast = toast, onDone = { tab = Tab.HOME }, onConnect = onConnect)
            Tab.FLEET -> FleetScreen(app, m, toast)
            Tab.SETTINGS -> SettingsScreen(app, m, toast)
        }
    }
}

@Composable
private fun VpnRefusedDialog(onSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android не дал включить VPN") },
        text = {
            Text(
                "Скорее всего, другое приложение (например, AmneziaVPN) выбрано как постоянная VPN: пока это так, " +
                    "Android не пускает другие VPN. Откройте настройки VPN, нажмите на шестерёнку у того приложения " +
                    "и выключите «Постоянная VPN», затем нажмите кнопку в Paramnezia ещё раз."
            )
        },
        confirmButton = { TextButton(onClick = onSettings) { Text("Настройки VPN") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
