package net.paramnezia.core.routing

import net.paramnezia.core.net.IpPrefix

/**
 * The "Russia goes direct" data set, as produced by tools/bypass-lists/generate.py.
 * IP lists are ordered by importance, so taking a prefix of the list keeps the most useful blocks.
 */
class BypassLists(
    val ipv4: List<IpPrefix>,
    val ipv6: List<IpPrefix>,
    /** Domain suffixes (`ru`, `yandex.net`) and exact hosts (`full:host`). */
    val domains: DomainMatcher,
    val apps: AppRules,
    val version: String,
) {
    companion object {
        fun parsePrefixes(text: String): List<IpPrefix> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull(IpPrefix::parseOrNull).toList()
    }
}

/** Matches host names against domain suffixes, like geosite `domain:` / `full:` rules. */
class DomainMatcher(suffixes: Collection<String>, full: Collection<String>) {
    private val suffixes = suffixes.mapTo(HashSet()) { it.lowercase().trimEnd('.') }
    private val full = full.mapTo(HashSet()) { it.lowercase().trimEnd('.') }

    fun matches(host: String): Boolean {
        var h = host.lowercase().trimEnd('.')
        if (h in full || h in suffixes) return true
        while (true) {
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (h in suffixes) return true
        }
    }

    companion object {
        fun parse(text: String): DomainMatcher {
            val s = ArrayList<String>(); val f = ArrayList<String>()
            for (raw in text.lineSequence()) {
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                if (l.startsWith("full:")) f += l.removePrefix("full:") else s += l.removePrefix("domain:")
            }
            return DomainMatcher(s, f)
        }
    }
}

/**
 * Package rules: exact names and `prefix.*` wildcards. User overrides win over the bundled list.
 */
class AppRules(patterns: Collection<String>) {
    private val exact = HashSet<String>()
    private val prefixes = ArrayList<String>()

    init {
        for (p in patterns) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (t.endsWith(".*")) prefixes += t.dropLast(1) else exact += t
        }
    }

    fun matches(pkg: String): Boolean = pkg in exact || prefixes.any { pkg.startsWith(it) }

    /**
     * Packages from [installed] that should bypass the tunnel. [forceTunnel] / [forceDirect] are the
     * user's per-app choices. The app itself is never excluded: its sockets are protected anyway.
     */
    fun select(installed: Collection<String>, forceTunnel: Set<String>, forceDirect: Set<String>, self: String): List<String> =
        installed.filter { it != self && it !in forceTunnel && (it in forceDirect || matches(it)) }.sorted()

    companion object {
        fun parse(text: String) = AppRules(
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList(),
        )
    }
}
