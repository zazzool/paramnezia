package net.paramnezia.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.App
import net.paramnezia.app.platform.Log
import net.paramnezia.app.ui.LocalUiPlatform
import net.paramnezia.app.ui.PnTheme
import net.paramnezia.app.ui.ThemePrefs
import net.paramnezia.app.ui.colorsFor
import net.paramnezia.app.ui.resolveDark
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import java.io.File
import java.net.InetAddress
import java.time.LocalDateTime

/**
 * Paramnezia for Windows.
 *
 *   Paramnezia.exe                 open the window
 *   Paramnezia.exe --minimized     start in the tray (start with Windows)
 *   Paramnezia.exe <file>.vpn      import a settings file (also when already running)
 *   Paramnezia.exe --screenshots <dir>   render the screens with sample data (CI, no service needed)
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--screenshots") { Screenshots.render(File(args.getOrElse(1) { "screenshots" })); kotlin.system.exitProcess(0) }
    val opened = args.firstOrNull { it.endsWith(".vpn", true) || it.endsWith(".conf", true) }
        ?.let { f -> runCatching { File(f).readText().take(256 * 1024) }.getOrNull() }
        ?: args.firstOrNull { it.startsWith("vpn://") }
    val incoming = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    // A second start hands its file to the running window and quits.
    if (!SingleInstance.claim(opened) { text -> if (text != null) incoming.tryEmit(text) ; SingleInstance.show() }) return
    opened?.let { incoming.tryEmit(it) }
    setupLog()

    val model = System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Windows")
    val platform = DesktopUiPlatform(model)
    val app = App(StateFile(Paths.home), ServiceClient(), Paths.cache, model, DesktopUiPlatform::openInBrowser)
    // The theme choice is one word in a plain file, read before the window opens (no flash of the wrong theme).
    val themeFile = File(Paths.home, "theme")
    ThemePrefs.mode.value = ThemePrefs.parse(runCatching { themeFile.readText().trim() }.getOrNull())
    ThemePrefs.save = { m -> themeFile.writeText(m.name) }
    app.start()

    application {
        var visible by remember { mutableStateOf("--minimized" !in args) }
        SingleInstance.show = { visible = true }
        val tunnel by TunnelRuntime.state.collectAsState()
        val icon = painterResource("paramnezia.png")
        val state = rememberWindowState(size = DpSize(440.dp, 860.dp), position = WindowPosition(Alignment.Center))
        // The watchdog lives in the service, so the app may leave with the VPN on: the tunnel stays
        // watched and repaired, and the app takes it over when it starts again. Leaving frees the
        // app's whole Java runtime (a few hundred MB) for people who only want the VPN.
        val quit = { exitApplication() }
        val stopAndQuit = {
            app.disconnect()
            runBlocking { withTimeoutOrNull(5_000) { app.tunnel.stopNow() } }
            exitApplication()
        }
        Tray(
            icon = icon,
            tooltip = "Paramnezia: " + when (val t = tunnel) {
                is TunnelState.Up -> "защищено"
                is TunnelState.Connecting -> if (t.reconnecting) "восстановление связи…" else "подключение…"
                is TunnelState.Failed -> "ошибка"
                TunnelState.Down -> "выключено"
            },
            onAction = { visible = true },
            menu = {
                Item("Открыть Paramnezia", onClick = { visible = true })
                if (tunnel is TunnelState.Down || tunnel is TunnelState.Failed) Item("Включить VPN", onClick = { app.connect() })
                else Item("Выключить VPN", onClick = { app.disconnect() })
                Separator()
                if (tunnel is TunnelState.Down || tunnel is TunnelState.Failed) Item("Выйти", onClick = quit)
                else {
                    Item("Выйти (VPN продолжит работать)", onClick = quit)
                    Item("Выключить VPN и выйти", onClick = stopAndQuit)
                }
            },
        )
        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            title = "Paramnezia",
            icon = icon,
            state = state,
        ) {
            window.minimumSize = java.awt.Dimension(380, 640)
            val dark = resolveDark()
            val scheme = colorsFor(dark)
            LaunchedEffect(dark) { TitleBar.apply(window, dark, scheme.background, scheme.onBackground) }
            PnTheme(dark) {
                CompositionLocalProvider(LocalUiPlatform provides platform) {
                    Root(app, platform, incoming)
                }
            }
        }
    }
}

/** One window per user: later starts pass their file to it over a loopback socket. */
object SingleInstance {
    @Volatile var show: () -> Unit = {}
    private val portFile get() = File(Paths.home, "instance.port")

    fun claim(text: String?, onMessage: (String?) -> Unit): Boolean {
        runCatching {
            val port = portFile.readText().trim().toInt()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                s.getOutputStream().write(((text ?: "") + "\u0000").toByteArray())
            }
            return false
        }
        val server = java.net.ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        Thread({
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    s.soTimeout = 2_000
                    val text = s.getInputStream().readNBytes(256 * 1024).decodeToString().substringBefore('\u0000')
                    onMessage(text.ifBlank { null })
                }
                s.close()
            }
        }, "pn-instance").apply { isDaemon = true }.start()
        return true
    }
}

private fun setupLog() {
    val file = Paths.log
    if (file.length() > 1 shl 20) file.renameTo(File(file.parentFile, file.name + ".1"))
    val out = runCatching { java.io.PrintStream(java.io.FileOutputStream(file, true), true, "UTF-8") }.getOrNull() ?: return
    Log.sink = Log.Sink { level, tag, message, error ->
        synchronized(out) {
            out.println("${LocalDateTime.now()} ${level.name.first()}/$tag: $message")
            error?.printStackTrace(out)
        }
    }
}
