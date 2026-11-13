package net.paramnezia.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryNamesTest {
    @Test
    fun namesWithoutJdkLocaleData() {
        assertEquals("Россия", countryName("RU"))
        assertEquals("Нидерланды", countryName("nl"))
        assertEquals("Соединенные Штаты", countryName("US"))
        val all = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".flatMap { a -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { b -> "$a$b" } }
        assertEquals(249, all.count { countryName(it) != null })
        assertNull(countryName("XX"))
        assertNull(countryName("RUS"))
        assertNull(countryName(null))
    }
}
