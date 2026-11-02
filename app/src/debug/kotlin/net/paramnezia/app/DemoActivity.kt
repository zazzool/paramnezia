package net.paramnezia.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import net.paramnezia.app.data.AppState
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.ui.MainActivity
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetCrypto
import net.paramnezia.core.fleet.FleetPolicy
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.cloud.CloudCatalog
import net.paramnezia.deploy.cloud.CloudPlan
import net.paramnezia.deploy.cloud.CloudRegion
import net.paramnezia.tunnel.NativeEd25519
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Debug builds only: replaces the app's state with sample data so the admin screens can be shown
 * on an emulator without real servers (CI screenshots for pull requests). Addresses are from
 * TEST-NET-3 (203.0.113.0/24) and never answer.
 *
 * `adb shell am start -n net.paramnezia.debug/net.paramnezia.app.DemoActivity --es demo admin|server|empty|cloud`
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        val demo = intent.getStringExtra("demo") ?: "admin"
        thread(name = "pn-demo") {
            app.repo.update { demoState(demo) }
            runOnUiThread {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    private fun demoState(demo: String): AppState {
        val now = System.currentTimeMillis()
        val ssh = SshCredentials("203.0.113.10", 22, "root", password = "demo")
        val profile = StoredProfile(UUID.randomUUID().toString(), "Нидерланды", conf("203.0.113.10"), ssh = ssh)
        val links = listOf(
            StoredLink("t1", "http://203.0.113.10/i/Qm9yaXM", "Борис", profile.id, now - 3_600_000, now + 6 * 86_400_000L),
            StoredLink("t2", "http://203.0.113.10/i/TWFtYQ", "Мама", profile.id, now - 86_400_000, now + 5 * 86_400_000L, used = true),
            StoredLink("t3", "", "Вика", profile.id, now - 2 * 86_400_000, Long.MAX_VALUE, file = true),
        )
        val pages = mapOf("203.0.113.10" to "http://203.0.113.10/")
        return when (demo) {
            "empty" -> AppState()
            "cloud" -> {
                writeCatalogs()
                AppState(cloud = net.paramnezia.app.data.CloudState(tokens = mapOf("cloudzy" to "demo", "timeweb" to "demo"), lastProvider = "cloudzy"))
            }
            "server" -> AppState(profiles = listOf(profile), activeProfile = profile.id, links = links, pages = pages)
            else -> {
                val (admin, empty) = FleetAdmin.create("Нидерланды", NativeEd25519)
                var m = empty
                val (me, myKey) = admin.newDevice(m, "Pixel 8")
                m = m.copy(devices = m.devices + me)
                for (name in listOf("Мама", "Папа", "Вика")) {
                    val (d, _) = admin.newDevice(m, name)
                    m = m.copy(devices = m.devices + d)
                }
                val main = FleetServer(UUID.randomUUID().toString(), "Нидерланды", "203.0.113.10", 443, WgKey.generatePrivate().publicKey().toBase64())
                val reserve = FleetServer(UUID.randomUUID().toString(), "Финляндия", "203.0.113.20", 443, WgKey.generatePrivate().publicKey().toBase64())
                m = admin.next(m) { it.copy(servers = listOf(main, reserve), policy = FleetPolicy()) }
                val fleet = StoredFleet(
                    trust = admin.trust, deviceKey = myKey.toBase64(), deviceName = "Pixel 8",
                    manifest = FleetCrypto.encode(admin.seal(m)),
                    adminSeed = Base64.getEncoder().encodeToString(admin.signingSeed),
                    serverAccess = mapOf(main.id to ssh, reserve.id to ssh.copy(host = "203.0.113.20")),
                    lastSync = now - 5 * 60_000, lastSyncVia = "tunnel",
                )
                AppState(fleet = fleet, links = links.map { it.copy(serverRef = main.id, fleetDevice = true) }, pages = pages)
            }
        }
    }

    /** Hoster catalogs as Cloudzy and Timeweb return them (fresh, so the picker does not go online). */
    private fun writeCatalogs() {
        fun plan(id: String, r: String, cpu: Int, ram: Int, disk: Int, price: Double, cur: String) = CloudPlan(id, r, id, cpu, ram, disk, price, cur)
        fun usd(r: String, k: Double = 1.0) = listOf(
            plan("p05", r, 1, 512, 20, 4.95 * k, "USD"), plan("p1", r, 1, 1024, 25, 6.95 * k, "USD"),
            plan("p1b", r, 1, 1024, 25, 6.95 * k, "USD"), plan("p2", r, 1, 2048, 60, 14.95 * k, "USD"),
            plan("p4", r, 2, 4096, 120, 28.95 * k, "USD"), plan("p8", r, 4, 8192, 240, 52.95 * k, "USD"),
        )
        val cz = listOf(
            "Amsterdam" to "NL", "Bern" to "CH", "Dallas" to "US", "Frankfurt" to "DE", "Las Vegas" to "US", "London" to "GB",
            "Los Angeles" to "US", "New York City" to "US", "Sydney" to "AU", "Singapore" to "SG", "Dubai" to "AE", "Moscow" to "RU",
        ).map { (n, c) -> CloudRegion(n.lowercase().replace(' ', '-'), n, c) }
        val tw = listOf("nl-1" to "NL", "de-1" to "DE", "pl-1" to "PL", "kz-1" to "KZ", "ru-1" to "RU")
            .map { (id, c) -> CloudRegion(id, net.paramnezia.deploy.cloud.TimewebProvider.LOCATIONS[id]!!.first, c) }
        val twPlans = tw.associate { r -> r.id to listOf(plan("1", r.id, 1, 1024, 15, 477.0, "RUB"), plan("2", r.id, 1, 2048, 30, 657.0, "RUB"), plan("3", r.id, 2, 2048, 40, 877.0, "RUB")) }
        for ((id, cat) in listOf(
            "cloudzy" to CloudCatalog.of("cloudzy", cz, cz.associate { it.id to usd(it.id) }),
            "timeweb" to CloudCatalog.of("timeweb", tw, twPlans),
        )) java.io.File(cacheDir, "cloud-catalog-$id.json").writeText(CloudCatalog.encode(cat))
    }

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
