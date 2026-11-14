package net.paramnezia.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** admin-keys.sh, run here with a fake home: adds once, removes only what it was asked to, keeps the rest. */
class AdminKeysScriptTest {
    private fun run(home: File, add: String = "", del: String = ""): String {
        val p = ProcessBuilder("bash", "-s").apply { environment().putAll(mapOf("PN_HOME" to home.path, "PN_ADD" to add, "PN_DEL" to del)) }
            .redirectErrorStream(true).start()
        p.outputStream.use { it.write(ServerManager.script("admin-keys.sh").toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS)); assertEquals(out, 0, p.exitValue())
        return out
    }

    @Test fun addsOnceRemovesExactly() {
        val home = Files.createTempDirectory("pn-home").toFile()
        val f = File(home, ".ssh/authorized_keys").apply { parentFile.mkdirs(); writeText("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOWNEROWNEROWNEROWNEROWNEROWNEROWNEROWNER owner\n") }
        val mom = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMOMMOMMOMMOMMOMMOMMOMMOMMOMMOMMOMMOMMOM paramnezia-admin:mom"
        val dad = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDADDADDADDADDADDADDADDADDADDADDADDADDAD paramnezia-admin:dad"
        run(home, add = "$mom\n$dad"); run(home, add = mom)
        assertEquals(3, f.readLines().size)
        assertTrue(run(home, del = mom.split(' ')[1]).contains("keys=2"))
        assertEquals(listOf("owner", "paramnezia-admin:dad"), f.readLines().map { it.substringAfterLast(' ') })
        assertEquals("rw-------", Files.getPosixFilePermissions(f.toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
    }
}
