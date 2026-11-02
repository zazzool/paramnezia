package net.paramnezia.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramsTest {
    @Test
    fun entries() {
        assertEquals("Telegram.exe", Programs.entryFor("""C:\Users\a\AppData\Roaming\Telegram Desktop\Telegram.exe"""))
        assertEquals("steam.exe", Programs.entryFor("/c/Games/steam.exe"))
        assertNull(Programs.entryFor("""C:\Program Files\Paramnezia\Paramnezia.exe"""))
        assertNull(Programs.entryFor("""C:\notes.txt"""))
    }

    @Test
    fun listSharesForceDirectWithAndroid() {
        var set = setOf("org.telegram.messenger", "ru.sberbankmobile")
        set = Programs.add(set, "Steam.exe")
        set = Programs.add(set, "steam.exe") // already there, whatever the case
        set = Programs.add(set, "Telegram.exe")
        assertEquals(listOf("Steam.exe", "Telegram.exe"), Programs.excluded(set))
        set = Programs.remove(set, "STEAM.EXE")
        assertEquals(listOf("Telegram.exe"), Programs.excluded(set))
        assertEquals(setOf("org.telegram.messenger", "ru.sberbankmobile", "Telegram.exe"), set)
        assertEquals(emptyList<String>(), Programs.excluded(setOf("paramnezia.exe")))
    }
}
