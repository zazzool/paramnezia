package net.paramnezia.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * "Start with Windows", off unless the user turns it on: a value under the current user's Run key
 * (no administrator rights, no scheduled tasks, removed by the uninstaller).
 */
object Autostart {
    private const val KEY = """Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val NAME = "Paramnezia"

    /** The installed launcher (jpackage sets it); null when run from Gradle. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    fun enabled(): Boolean = StateFile.WINDOWS && runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME)
    }.getOrDefault(false)

    fun set(on: Boolean) {
        check(StateFile.WINDOWS) { "только в Windows" }
        if (on) {
            val path = exe ?: error("запустите установленную Paramnezia")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$path\" --minimized")
        } else if (enabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
        }
    }
}
