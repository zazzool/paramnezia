package net.paramnezia.core.qr

/*
 * QR Code tables from ISO/IEC 18004 (the same data as in zxing's Version.java, Apache 2.0):
 * per version, the alignment pattern centres and, for each error correction level (L, M, Q, H),
 * the error correction codewords per block and the block groups (count x data codewords).
 */

/** Error correction level; [bits] is its value in the format information. */
enum class EcLevel(val bits: Int) { L(1), M(0), Q(3), H(2) }

internal class EcBlocks(val ecPerBlock: Int, val groups: List<Pair<Int, Int>>) {
    val blockCount: Int get() = groups.sumOf { it.first }
    val dataCodewords: Int get() = groups.sumOf { it.first * it.second }
    val totalCodewords: Int get() = dataCodewords + ecPerBlock * blockCount
}

internal class QrVersion(val number: Int, val alignment: IntArray, private val levels: List<EcBlocks>) {
    val size: Int get() = 17 + 4 * number
    fun blocks(level: EcLevel): EcBlocks = levels[level.ordinal]

    companion object {
        val ALL: List<QrVersion> = TABLE.map { row ->
            val parts = row.split('|')
            val align = parts[1].takeIf { it.isNotEmpty() }?.split(',')?.map { it.toInt() }?.toIntArray() ?: IntArray(0)
            val levels = parts.drop(2).map { lv ->
                val (ec, groups) = lv.split(':')
                EcBlocks(ec.toInt(), groups.split(',').map { g -> g.substringBefore('x').toInt() to g.substringAfter('x').toInt() })
            }
            QrVersion(parts[0].toInt(), align, levels)
        }

        fun of(number: Int): QrVersion = ALL[number - 1]
        fun forSize(size: Int): QrVersion? = if ((size - 17) % 4 == 0 && size in 21..177) ALL[(size - 17) / 4 - 1] else null
    }
}

private val TABLE = listOf(
    "1||7:1x19|10:1x16|13:1x13|17:1x9",
    "2|6,18|10:1x34|16:1x28|22:1x22|28:1x16",
    "3|6,22|15:1x55|26:1x44|18:2x17|22:2x13",
    "4|6,26|20:1x80|18:2x32|26:2x24|16:4x9",
    "5|6,30|26:1x108|24:2x43|18:2x15,2x16|22:2x11,2x12",
    "6|6,34|18:2x68|16:4x27|24:4x19|28:4x15",
    "7|6,22,38|20:2x78|18:4x31|18:2x14,4x15|26:4x13,1x14",
    "8|6,24,42|24:2x97|22:2x38,2x39|22:4x18,2x19|26:4x14,2x15",
    "9|6,26,46|30:2x116|22:3x36,2x37|20:4x16,4x17|24:4x12,4x13",
    "10|6,28,50|18:2x68,2x69|26:4x43,1x44|24:6x19,2x20|28:6x15,2x16",
    "11|6,30,54|20:4x81|30:1x50,4x51|28:4x22,4x23|24:3x12,8x13",
    "12|6,32,58|24:2x92,2x93|22:6x36,2x37|26:4x20,6x21|28:7x14,4x15",
    "13|6,34,62|26:4x107|22:8x37,1x38|24:8x20,4x21|22:12x11,4x12",
    "14|6,26,46,66|30:3x115,1x116|24:4x40,5x41|20:11x16,5x17|24:11x12,5x13",
    "15|6,26,48,70|22:5x87,1x88|24:5x41,5x42|30:5x24,7x25|24:11x12,7x13",
    "16|6,26,50,74|24:5x98,1x99|28:7x45,3x46|24:15x19,2x20|30:3x15,13x16",
    "17|6,30,54,78|28:1x107,5x108|28:10x46,1x47|28:1x22,15x23|28:2x14,17x15",
    "18|6,30,56,82|30:5x120,1x121|26:9x43,4x44|28:17x22,1x23|28:2x14,19x15",
    "19|6,30,58,86|28:3x113,4x114|26:3x44,11x45|26:17x21,4x22|26:9x13,16x14",
    "20|6,34,62,90|28:3x107,5x108|26:3x41,13x42|30:15x24,5x25|28:15x15,10x16",
    "21|6,28,50,72,94|28:4x116,4x117|26:17x42|28:17x22,6x23|30:19x16,6x17",
    "22|6,26,50,74,98|28:2x111,7x112|28:17x46|30:7x24,16x25|24:34x13",
    "23|6,30,54,78,102|30:4x121,5x122|28:4x47,14x48|30:11x24,14x25|30:16x15,14x16",
    "24|6,28,54,80,106|30:6x117,4x118|28:6x45,14x46|30:11x24,16x25|30:30x16,2x17",
    "25|6,32,58,84,110|26:8x106,4x107|28:8x47,13x48|30:7x24,22x25|30:22x15,13x16",
    "26|6,30,58,86,114|28:10x114,2x115|28:19x46,4x47|28:28x22,6x23|30:33x16,4x17",
    "27|6,34,62,90,118|30:8x122,4x123|28:22x45,3x46|30:8x23,26x24|30:12x15,28x16",
    "28|6,26,50,74,98,122|30:3x117,10x118|28:3x45,23x46|30:4x24,31x25|30:11x15,31x16",
    "29|6,30,54,78,102,126|30:7x116,7x117|28:21x45,7x46|30:1x23,37x24|30:19x15,26x16",
    "30|6,26,52,78,104,130|30:5x115,10x116|28:19x47,10x48|30:15x24,25x25|30:23x15,25x16",
    "31|6,30,56,82,108,134|30:13x115,3x116|28:2x46,29x47|30:42x24,1x25|30:23x15,28x16",
    "32|6,34,60,86,112,138|30:17x115|28:10x46,23x47|30:10x24,35x25|30:19x15,35x16",
    "33|6,30,58,86,114,142|30:17x115,1x116|28:14x46,21x47|30:29x24,19x25|30:11x15,46x16",
    "34|6,34,62,90,118,146|30:13x115,6x116|28:14x46,23x47|30:44x24,7x25|30:59x16,1x17",
    "35|6,30,54,78,102,126,150|30:12x121,7x122|28:12x47,26x48|30:39x24,14x25|30:22x15,41x16",
    "36|6,24,50,76,102,128,154|30:6x121,14x122|28:6x47,34x48|30:46x24,10x25|30:2x15,64x16",
    "37|6,28,54,80,106,132,158|30:17x122,4x123|28:29x46,14x47|30:49x24,10x25|30:24x15,46x16",
    "38|6,32,58,84,110,136,162|30:4x122,18x123|28:13x46,32x47|30:48x24,14x25|30:42x15,32x16",
    "39|6,26,54,82,110,138,166|30:20x117,4x118|28:40x47,7x48|30:43x24,22x25|30:10x15,67x16",
    "40|6,30,58,86,114,142,170|30:19x118,6x119|28:18x47,31x48|30:34x24,34x25|30:20x15,61x16"
)
