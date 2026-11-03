package net.paramnezia.app.lan

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanShareTest {
    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val apk = LanFile("paramnezia-9.9.9.apk", LanFile.APK_MIME, apkBytes.size.toLong(), "9.9.9") { apkBytes.inputStream() }
    private var settings = true
    private val server = LanServer(
        info = { LanInfo(name = "Pixel", version = "9.9.9", settings = settings, apk = true, exe = false) },
        apk = { apk }, exe = { null },
    )

    @After fun stop() = server.stop()

    private fun peer() = LanPeer("Pixel", "127.0.0.1", server.port)

    @Test fun infoPageAndFiles() = runBlocking {
        server.start(listOf(0))
        val info = LanClient.info("127.0.0.1", server.port)
        assertEquals("Pixel", info?.name)
        val page = LanClient.request("127.0.0.1", server.port, "GET", "/")
        assertEquals(200, page.code)
        assertTrue(page.body.contains("href=\"/a\""))
        assertTrue(page.body.contains("Получить настройки"))
        val file = LanClient.request("127.0.0.1", server.port, "GET", "/a", readTimeoutMs = 10_000)
        assertEquals(200, file.code)
        assertEquals(404, LanClient.request("127.0.0.1", server.port, "GET", "/w").code)
        assertEquals(1, server.served.value["apk"])
    }

    @Test fun settingsOnlyAfterTheOwnerAllows() = runBlocking {
        server.start(listOf(0))
        val asked = async { LanClient.askSettings(peer(), "Sony TV", "tv") }
        while (server.asks.value.isEmpty()) delay(50)
        val ask = server.asks.value.single()
        assertEquals("Sony TV", ask.device)
        assertEquals("tv", ask.kind)
        // The key file is not there before the owner's answer.
        assertEquals(404, LanClient.request("127.0.0.1", server.port, "GET", "/k/${ask.id}").code)
        server.approve(ask.id, "vpn://key")
        assertEquals("vpn://key", asked.await())
        assertEquals("vpn://key", LanClient.request("127.0.0.1", server.port, "GET", "/k/${ask.id}").body)
        assertTrue(server.asks.value.isEmpty())
    }

    @Test fun deniedAskFails() = runBlocking {
        server.start(listOf(0))
        val asked = async { runCatching { LanClient.askSettings(peer(), "PC", "windows") } }
        while (server.asks.value.isEmpty()) delay(50)
        server.deny(server.asks.value.single().id)
        val r = asked.await()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("отклонили"))
    }

    @Test fun appOnlyShareRefusesSettings() = runBlocking {
        settings = false
        server.start(listOf(0))
        try { LanClient.askSettings(peer(), "PC", "windows"); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("только приложение"))
        }
    }

    @Test fun addresses() {
        assertEquals("192.168.1.5" to 8080, LanNet.parseAddress(" http://192.168.1.5:8080/ "))
        assertEquals("192.168.1.5" to 80, LanNet.parseAddress("192.168.1.5"))
        assertNull(LanNet.parseAddress("192.168.1.500"))
        assertNull(LanNet.parseAddress("192.168.1.5:99999"))
        assertEquals("10.0.0.2", LanNet.shortAddress("10.0.0.2", 80))
        assertEquals("10.0.0.2:8080", LanNet.shortAddress("10.0.0.2", 8080))
        val lan = LanNet.Lan("wlan0", java.net.InetAddress.getByName("192.168.1.5") as java.net.Inet4Address, 24)
        assertTrue(lan.contains(java.net.InetAddress.getByName("192.168.1.77")))
        assertTrue(!lan.contains(java.net.InetAddress.getByName("192.168.2.77")))
    }

    @Test fun pageOrdersByDevice() {
        val info = LanInfo(name = "Pixel", version = "1", settings = false, apk = true, exe = true)
        val exe = LanFile("Setup.exe", LanFile.EXE_MIME, 10, "1") { ByteArray(10).inputStream() }
        val win = LanPage.render(info, apk, exe, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        assertTrue(win.indexOf("href=\"/w\"") < win.indexOf("href=\"/a\""))
        val tv = LanPage.render(info, apk, exe, "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K VH2 Build/STT1.211025.001.Z4)")
        assertTrue(tv.indexOf("href=\"/a\"") < tv.indexOf("href=\"/w\""))
        assertEquals(LanPage.Device.TV, LanPage.detect("Dalvik/2.1.0 (Linux; U; Android 9; AFTMM Build/PS7633)"))
        assertNotNull(LanPage.notFound())
    }
}
