package net.paramnezia.core.amnezia

/**
 * Finds the settings in whatever a person forwarded: a bare `vpn://` key, a message with the key in
 * the middle ("Вот ключ: vpn://…"), a `.vpn` file (the key, maybe with a BOM or line breaks inside),
 * or a WireGuard/AmneziaWG config.
 */
object KeyText {
    private val KEY = Regex("vpn://[A-Za-z0-9_=+/-]+")

    fun extract(text: String): String {
        val t = text.removePrefix("﻿").trim()
        if (t.contains("[Interface]", ignoreCase = true)) return t
        // Messengers and editors wrap long keys: glue lines that continue a key back together.
        val glued = t.replace(Regex("(?<=[A-Za-z0-9_=+/-])\\s*\\r?\\n\\s*(?=[A-Za-z0-9_=+/-])"), "")
        val candidates = listOfNotNull(KEY.find(t)?.value, KEY.find(glued)?.value).distinct()
        return candidates.firstOrNull { runCatching { AmneziaKey.decode(it) }.isSuccess } ?: candidates.firstOrNull() ?: t
    }
}
