package net.paramnezia.app.platform

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.paramnezia.core.ota.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateInstallerTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun opensOnlyAnArtifactThatStillMatchesTheManifest() = runBlocking {
        val payload = "release".toByteArray()
        val release = releaseFor(payload)
        val directory = files.newFolder()
        val updater = UpdateInstaller(directory, "apk", this)
        updater.download(release) { read -> payload.inputStream().use(read) }
        val ready = withTimeout(5_000) {
            updater.state.first { it is UpdateInstaller.State.Ready } as UpdateInstaller.State.Ready
        }

        val restarted = UpdateInstaller(directory, "apk", this)
        restarted.restore(release)
        withTimeout(5_000) { restarted.state.first { it is UpdateInstaller.State.Ready } }

        assertNull(restarted.install(release) { file ->
            assertEquals(ready.file, file)
            null
        })
        ready.file.writeText("altered")
        var opened = false
        assertNotNull(restarted.install(release) {
            opened = true
            null
        })
        assertFalse(opened)
        assertEquals(UpdateInstaller.State.Failed, restarted.state.value)
    }

    @Test
    fun discardsInvalidBytesAndCanUseTheNextSource() = runBlocking {
        val payload = "release".toByteArray()
        val release = releaseFor(payload)
        val dir = files.newFolder()
        val updater = UpdateInstaller(dir, "exe", this)
        updater.download(release) { read ->
            assertFalse("altered".byteInputStream().use(read))
            payload.inputStream().use(read)
        }
        withTimeout(5_000) { updater.state.first { it is UpdateInstaller.State.Ready } }
        assertEquals(listOf("exe"), dir.listFiles().orEmpty().map { it.extension })
    }

    private fun releaseFor(payload: ByteArray) = Release(
        packageName = "net.paramnezia",
        versionCode = 28,
        versionName = "v2.19.2",
        sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        size = payload.size.toLong(),
    )
}
