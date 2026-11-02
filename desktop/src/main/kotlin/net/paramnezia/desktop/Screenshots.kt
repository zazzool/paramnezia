package net.paramnezia.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableSharedFlow
import net.paramnezia.app.App
import net.paramnezia.app.data.AppState
import net.paramnezia.app.data.CloudState
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.net.RuSource
import net.paramnezia.app.net.SeenAddress
import net.paramnezia.app.net.Visibility
import net.paramnezia.app.ui.LocalUiPlatform
import net.paramnezia.app.ui.PnTheme
import net.paramnezia.app.ui.Tab
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetCrypto
import net.paramnezia.core.fleet.FleetPolicy
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.net.GeoVerdict
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelState
import net.paramnezia.tunnel.TunnelStats
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * Renders the screens with sample data into PNG files, without a window, a service or a network
 * (CI attaches them to pull requests). Addresses are from TEST-NET-3 (203.0.113.0/24).
 */
object Screenshots {
    private const val W = 440
    private const val H = 860
    private const val SCALE = 2f

    fun render(out: File) {
        out.mkdirs()
        System.setProperty("paramnezia.screenshots", "true")
        shot(out, "home-empty", AppState(), Tab.HOME)
        shot(out, "add", AppState(), Tab.ADD)
        shot(out, "home-off", serverState(), Tab.HOME)
        shot(out, "home-on", serverState(), Tab.HOME) { app ->
            TunnelRuntime.set(TunnelState.Up("Нидерланды", "demo", System.currentTimeMillis() - 754_000))
            TunnelRuntime.lastStats = TunnelStats(1_284_000_000, 96_500_000, System.currentTimeMillis() / 1000)
            TunnelRuntime.lastBlocked = 1342
            TunnelRuntime.setRtt(48)
            app.showVisibility(Visibility(
                foreign = SeenAddress("203.0.113.10", GeoVerdict("203.0.113.10", "NL", "Амстердам", "Северная Голландия", "AS64500", "Demo Hosting B.V.", 4, 4, emptyMap())),
                russian = SeenAddress("198.51.100.23", GeoVerdict("198.51.100.23", "RU", "Москва", "Москва", "AS64501", "Домашний провайдер", 4, 4, emptyMap())),
                checkedAt = System.currentTimeMillis(), key = app.visibilityKey(), foreignIsServer = true, russianSource = RuSource.YANDEX,
            ))
        }
        shot(out, "fleet-server", serverState(), Tab.FLEET)
        shot(out, "fleet-admin", adminState(), Tab.FLEET)
        shot(out, "settings", serverState(), Tab.SETTINGS)
        // The dock's mini power orb glows while the VPN is on, on every screen.
        shot(out, "fleet-on", adminState(), Tab.FLEET) {
            TunnelRuntime.set(TunnelState.Up("Нидерланды", "demo", System.currentTimeMillis() - 754_000))
        }
        shot(out, "home-follower", adminState().let { it.copy(fleet = it.fleet!!.copy(adminSeed = null)) }, Tab.HOME)
        println("screenshots in ${out.absolutePath}")
    }

    private fun shot(out: File, name: String, state: AppState, tab: Tab, prepare: (App) -> Unit = {}) {
        TunnelRuntime.set(TunnelState.Down)
        val home = Files.createTempDirectory("pn-shot").toFile()
        val app = App(StateFile(home), ServiceClient("""\\.\pipe\paramnezia-none"""), File(home, "cache").apply { mkdirs() }, "DESKTOP-PC", {})
        app.repo.update { state }
        if (state.cloud.tokens.isNotEmpty()) writeCatalogs(File(home, "cache"))
        prepare(app)
        val platform = DesktopUiPlatform("DESKTOP-PC")
        val scene = ImageComposeScene(width = (W * SCALE).toInt(), height = (H * SCALE).toInt(), density = Density(SCALE)) {
            PnTheme {
                CompositionLocalProvider(LocalUiPlatform provides platform) {
                    Root(app, platform, MutableSharedFlow(), initialTab = tab, watchService = false)
                }
            }
        }
        var t = 0L
        repeat(30) { scene.render(t); t += 50_000_000L; Thread.sleep(20) }
        val image = scene.render(t)
        File(out, "windows-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        app.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        home.deleteRecursively()
    }

    private val ssh = SshCredentials("203.0.113.10", 22, "root", password = "demo")

    private fun serverState(): AppState {
        val now = System.currentTimeMillis()
        val profile = StoredProfile("demo", "Нидерланды", conf("203.0.113.10"), ssh = ssh)
        val links = listOf(
            StoredLink("t1", "http://203.0.113.10/i/Qm9yaXM", "Борис", profile.id, now - 3_600_000, now + 6 * 86_400_000L),
            StoredLink("t2", "http://203.0.113.10/i/TWFtYQ", "Мама", profile.id, now - 86_400_000, now + 5 * 86_400_000L, used = true),
        )
        return AppState(profiles = listOf(profile), activeProfile = profile.id, links = links, pages = mapOf("203.0.113.10" to "http://203.0.113.10/"))
    }

    private fun adminState(): AppState {
        val ed = Ed25519.Jca
        val (admin, empty) = FleetAdmin.create("Нидерланды", ed)
        var m = empty
        val (me, myKey) = admin.newDevice(m, "DESKTOP-PC")
        m = m.copy(devices = m.devices + me)
        for (name in listOf("Телефон", "Мама", "Папа", "Вика")) {
            val (d, _) = admin.newDevice(m, name)
            m = m.copy(devices = m.devices + d)
        }
        val main = FleetServer(UUID.randomUUID().toString(), "Нидерланды", "203.0.113.10", 443, WgKey.generatePrivate().publicKey().toBase64())
        val reserve = FleetServer(UUID.randomUUID().toString(), "Финляндия", "203.0.113.20", 443, WgKey.generatePrivate().publicKey().toBase64())
        m = admin.next(m) { it.copy(servers = listOf(main, reserve), policy = FleetPolicy()) }
        val fleet = StoredFleet(
            trust = admin.trust, deviceKey = myKey.toBase64(), deviceName = "DESKTOP-PC",
            manifest = FleetCrypto.encode(admin.seal(m)),
            adminSeed = Base64.getEncoder().encodeToString(admin.signingSeed),
            serverAccess = mapOf(main.id to ssh, reserve.id to ssh.copy(host = "203.0.113.20")),
            lastSync = System.currentTimeMillis() - 5 * 60_000, lastSyncVia = "tunnel",
        )
        val now = System.currentTimeMillis()
        val links = listOf(StoredLink("t1", "http://203.0.113.10/i/Qm9yaXM", "Борис", main.id, now - 3_600_000, now + 6 * 86_400_000L, fleetDevice = true))
        return AppState(fleet = fleet, links = links, pages = mapOf("203.0.113.10" to "http://203.0.113.10/"), cloud = CloudState())
    }

    private fun writeCatalogs(dir: File) = Unit

    private fun conf(host: String) = """
        [Interface]
        PrivateKey = ${WgKey.generatePrivate().toBase64()}
        Address = 10.8.1.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = ${WgKey.generatePrivate().publicKey().toBase64()}
        Endpoint = $host:443
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()
}
