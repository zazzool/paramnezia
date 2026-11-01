package net.paramnezia.deploy

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.crypto.WgKey
import java.security.SecureRandom

/**
 * Server-side AmneziaWG parameter sets. Every client of a server must use the same values,
 * so they are generated once at install time and copied into each client config.
 */
enum class ObfuscationProfile(val title: String) {
    /** AmneziaWG 3.1, what current AmneziaVPN installs: header protection, random trailers, jittered timers. */
    AWG_3("AmneziaWG 3"),
    /** AmneziaWG 2.0: magic-header ranges, four padding sizes, a protocol-imitating I1 packet. */
    AWG_2("AmneziaWG 2"),
    /** AmneziaWG 1.0 for old clients: junk packets, two paddings, fixed magic headers. */
    AWG_1("AmneziaWG 1 (legacy clients)");

    fun generate(random: SecureRandom = SecureRandom()): Map<AwgParam, String> {
        fun r(from: Int, until: Int) = from + random.nextInt(until - from)
        val p = LinkedHashMap<AwgParam, String>()
        p[AwgParam.JC] = r(4, 7).toString()
        p[AwgParam.JMIN] = "10"
        p[AwgParam.JMAX] = "50"
        when (this) {
            AWG_3 -> {
                // Same values AmneziaVPN's AwgInstaller writes, which its clients are tested against.
                for (s in listOf(AwgParam.S1, AwgParam.S2, AwgParam.S3, AwgParam.S4)) p[s] = "12"
                p[AwgParam.H1] = "1"; p[AwgParam.H2] = "2"; p[AwgParam.H3] = "3"; p[AwgParam.H4] = "4"
                p[AwgParam.I1] = DNS_LIKE_I1
                p[AwgParam.HEADER_PROTECTION_KEY] = WgKey.generatePrivate().toBase64()
                p[AwgParam.REKEY_AFTER_TIME] = "100-120"
                p[AwgParam.REKEY_TIMEOUT] = "3-7"
                p[AwgParam.REJECT_AFTER_TIME] = "150-180"
                p[AwgParam.KEEPALIVE_TIMEOUT] = "5-15"
                p[AwgParam.MAX_HANDSHAKE_ATTEMPTS] = "15-20"
                p[AwgParam.RANDOM_TRAILERS] = "on"
                p[AwgParam.DISABLE_COOKIES] = "on"
            }
            AWG_2 -> {
                val sizes = distinctPaddings(random, withCookieAndTransport = true)
                p[AwgParam.S1] = sizes[0].toString(); p[AwgParam.S2] = sizes[1].toString()
                p[AwgParam.S3] = sizes[2].toString(); p[AwgParam.S4] = sizes[3].toString()
                val ranges = headerRanges(random)
                p[AwgParam.H1] = ranges[0]; p[AwgParam.H2] = ranges[1]; p[AwgParam.H3] = ranges[2]; p[AwgParam.H4] = ranges[3]
                p[AwgParam.I1] = DNS_LIKE_I1
            }
            AWG_1 -> {
                val sizes = distinctPaddings(random, withCookieAndTransport = false)
                p[AwgParam.S1] = sizes[0].toString(); p[AwgParam.S2] = sizes[1].toString()
                val headers = generateSequence { 5L + (random.nextInt().toLong() and 0x7fffffffL) }.distinct().take(4).toList()
                p[AwgParam.H1] = headers[0].toString(); p[AwgParam.H2] = headers[1].toString()
                p[AwgParam.H3] = headers[2].toString(); p[AwgParam.H4] = headers[3].toString()
            }
        }
        return p
    }

    companion object {
        /**
         * The I1 "signature" packet AmneziaVPN ships by default: a DNS response for icloud.com, so the
         * first datagram of every handshake looks like ordinary DNS to DPI.
         */
        const val DNS_LIKE_I1 =
            "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>"

        private const val INIT = 148
        private const val RESPONSE = 92
        private const val COOKIE = 64
        private const val TRANSPORT = 32

        /** Paddings such that no two message types end up the same size on the wire. */
        private fun distinctPaddings(random: SecureRandom, withCookieAndTransport: Boolean): IntArray {
            while (true) {
                val s = intArrayOf(15 + random.nextInt(136), 15 + random.nextInt(136), 10 + random.nextInt(55), 10 + random.nextInt(55))
                val sizes = if (withCookieAndTransport) {
                    listOf(INIT + s[0], RESPONSE + s[1], COOKIE + s[2], TRANSPORT + s[3])
                } else listOf(INIT + s[0], RESPONSE + s[1], COOKIE, TRANSPORT)
                if (sizes.toSet().size == sizes.size) return s
            }
        }

        /** Four non-overlapping uint32 ranges for AmneziaWG 2 magic headers. */
        private fun headerRanges(random: SecureRandom): List<String> {
            val span = 0x3FFFFFFFL
            return (0 until 4).map { i ->
                val base = 5L + i * span
                val start = base + (random.nextLong() and 0x0FFFFFFFL)
                val end = start + 1 + (random.nextLong() and 0x00FFFFFFL)
                "$start-$end"
            }
        }
    }
}
