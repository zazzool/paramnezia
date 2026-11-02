package net.paramnezia.desktop

import java.io.File

/**
 * Programs that go past the VPN on Windows. They are kept in the same `forceDirect` set as the
 * Android apps (so a fleet policy carries both), told apart by the `.exe` ending: Android package
 * names never have it. An entry is a file name ("telegram.exe"), so it keeps working when a
 * program updates itself into a new versioned folder; the service compares without case.
 */
object Programs {
    /** Our own processes: the app reaches the internet past the VPN by itself when it has to. */
    private val OWN = setOf("paramnezia.exe", "paramnezia-service.exe")

    fun isProgram(entry: String) = entry.endsWith(".exe", ignoreCase = true)

    /** The Windows programs in [forceDirect], for the service. */
    fun excluded(forceDirect: Set<String>): List<String> =
        forceDirect.filter { isProgram(it) && it.lowercase() !in OWN }.sortedBy { it.lowercase() }

    /** The entry for a program file, or null for what cannot go on the list. */
    fun entryFor(path: String): String? {
        val name = path.substringAfterLast('\\').substringAfterLast('/').trim()
        return name.takeIf { isProgram(it) && it.lowercase() !in OWN }
    }

    fun add(forceDirect: Set<String>, entry: String): Set<String> =
        if (forceDirect.any { it.equals(entry, ignoreCase = true) }) forceDirect else forceDirect + entry

    fun remove(forceDirect: Set<String>, entry: String): Set<String> =
        forceDirect.filterNot { it.equals(entry, ignoreCase = true) }.toSet()

    /** Programs running now that the user can see (outside the Windows folder), by file name. */
    fun running(): List<Pair<String, String>> {
        val windir = (System.getenv("SystemRoot") ?: "C:\\Windows").lowercase() + "\\"
        return ProcessHandle.allProcesses().toList()
            .mapNotNull { p -> p.info().command().orElse(null) }
            .filter { !it.lowercase().startsWith(windir) }
            .mapNotNull { path -> entryFor(path)?.let { it to path } }
            .distinctBy { it.first.lowercase() }
            .sortedBy { it.first.lowercase() }
    }

    /** A friendlier name for an entry: "Telegram" for "Telegram.exe". */
    fun label(entry: String) = File(entry).nameWithoutExtension
}
