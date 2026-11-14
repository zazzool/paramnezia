package net.paramnezia.desktop

/**
 * "Start with Windows", off unless the user turns it on: a value under the current user's Run key
 * (no administrator rights, no scheduled tasks, removed by the uninstaller).
 */
object Autostart {
    private const val KEY = """Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val NAME = "Paramnezia"

    /** The installed launcher (jpackage sets it); null when run from Gradle. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")
    private fun command(path: String) = "\"$path\" --minimized"

    fun enabled(): Boolean = StateFile.WINDOWS && runCatching { Win32.regGetString(KEY, NAME) != null }.getOrDefault(false)

    fun set(on: Boolean) {
        check(StateFile.WINDOWS) { "только в Windows" }
        if (on) Win32.regSetString(KEY, NAME, command(exe ?: error("запустите установленную Paramnezia")))
        else Win32.regDelete(KEY, NAME)
    }

    /**
     * At start-up: if autostart is on but points elsewhere (the app was reinstalled into another
     * folder), point it at this copy, so it keeps working instead of silently starting nothing.
     */
    fun repair() {
        if (!StateFile.WINDOWS) return
        val path = exe ?: return
        runCatching {
            val current = Win32.regGetString(KEY, NAME) ?: return
            if (current != command(path)) Win32.regSetString(KEY, NAME, command(path))
        }
    }
}
