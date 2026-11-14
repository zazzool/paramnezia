package net.paramnezia.core.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ReedSolomonTest {
    @Test
    fun correctsUpToHalfTheEcCodewords() {
        val rnd = Random(7)
        repeat(300) {
            val ec = listOf(7, 10, 18, 26, 30).random(rnd)
            val data = IntArray(rnd.nextInt(5, 120)) { rnd.nextInt(256) }
            val code = data + ReedSolomon.encode(data, ec)
            val broken = code.copyOf()
            val errors = rnd.nextInt(0, ec / 2 + 1)
            for (p in (code.indices).shuffled(rnd).take(errors)) broken[p] = broken[p] xor rnd.nextInt(1, 256)
            assertEquals(errors, ReedSolomon.decode(broken, ec))
            assertArrayEquals(code, broken)
        }
    }

    @Test
    fun tooManyErrorsAreReportedNotHidden() {
        val rnd = Random(9)
        var reported = 0
        repeat(200) {
            val data = IntArray(40) { rnd.nextInt(256) }
            val code = data + ReedSolomon.encode(data, 10)
            for (p in code.indices.shuffled(rnd).take(9)) code[p] = code[p] xor rnd.nextInt(1, 256)
            if (ReedSolomon.decode(code, 10) < 0) reported++
        }
        // A rare miscorrection is mathematically possible; nearly every overload must be detected.
        assert(reported >= 190) { "only $reported of 200 detected" }
    }
}
