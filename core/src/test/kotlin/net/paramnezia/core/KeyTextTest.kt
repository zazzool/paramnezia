package net.paramnezia.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.amnezia.KeyText
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTextTest {
    private val key = AmneziaKey.encode(buildJsonObject { put("description", JsonPrimitive("Дом ".repeat(40))) })

    @Test
    fun findsTheKeyInWhateverWasForwarded() {
        assertEquals(key, KeyText.extract(key))
        assertEquals(key, KeyText.extract("﻿$key\n"))
        assertEquals(key, KeyText.extract("Вот ключ: $key\nThanks"))
        assertEquals(key, KeyText.extract("Hello\n$key"))
        // A messenger wrapped the long key over several lines.
        assertEquals(key, KeyText.extract("Ключ:\n" + key.chunked(60).joinToString("\n")))
        val conf = "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y\n"
        assertEquals(conf.trim(), KeyText.extract(conf))
        assertEquals("nothing here", KeyText.extract(" nothing here "))
    }
}
