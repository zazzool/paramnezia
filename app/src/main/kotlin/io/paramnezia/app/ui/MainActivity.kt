package io.paramnezia.app.ui

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Icon
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
import io.paramnezia.app.App
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as App

    /** Keys that arrived by link, share or file; handled by the Add screen. */
    private val incoming = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.vpnPermissionIntent() == null) app.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            PnTheme {
                Root(app, incoming, onConnect = ::connect)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "vpn") uri.toString()
                else runCatching { contentResolver.openInputStream(uri)?.use { it.reader().readText() } }.getOrNull()
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        incoming.tryEmit(text)
    }

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
            runCatching { app.servers.import(key) }.onSuccess { toast("Готово") }.onFailure { toast(it.message ?: "Ошибка") }
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
            Tab.ADD -> AddScreen(app, m, pending, onConsumed = { pending = null }, toast = toast, onDone = { tab = Tab.HOME })
            Tab.FLEET -> FleetScreen(app, m, toast)
            Tab.SETTINGS -> SettingsScreen(app, m, toast)
        }
    }
}
