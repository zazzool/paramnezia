package net.paramnezia.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.paramnezia.core.qr.EcLevel
import net.paramnezia.core.qr.LumaImage
import net.paramnezia.core.qr.QrDecoder
import net.paramnezia.core.qr.QrEncoder
import net.paramnezia.core.qr.QrMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class QrTest {
    private val rnd = Random(42)

    private fun randomText(n: Int) = String(CharArray(n) { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[rnd.nextInt(64)] })
    private val samples = listOf(
        "vpn://" + randomText(60),
        "vpn://" + randomText(400),
        "vpn://" + randomText(1100),
        "http://203.0.113.7:8080/#" + randomText(22),
        "Привет, семья! Ключ: " + randomText(40),
        "1234567890",
        "x",
    )

    /** A white page with [m] drawn at [scale] pixels per module and a 4-module quiet zone. */
    private fun render(m: QrMatrix, scale: Int = 4, quiet: Int = 4): Pair<Int, IntArray> {
        val side = (m.size + 2 * quiet) * scale
        val px = IntArray(side * side) { -1 }
        for (y in 0 until m.size) for (x in 0 until m.size) if (m[x, y])
            for (dy in 0 until scale) for (dx in 0 until scale) px[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
        return side to px
    }

    /**
     * zxing in "pure barcode" mode reads the modules directly: an exact check of our encoding. (Its
     * image search fails on a few percent of dense codes rendered small, its own codes included,
     * so it is not used here; our reader's search is tested on camera-like pictures below.)
     */
    private fun zxingDecode(side: Int, px: IntArray): String =
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px))),
            mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.PURE_BARCODE to true)).text

    private fun zxingEncode(text: String, level: ErrorCorrectionLevel): QrMatrix {
        val b = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0))
        val m = QrMatrix(b.width)
        for (y in 0 until b.height) for (x in 0 until b.width) m[x, y] = b[x, y]
        return m
    }

    /** Draws [m] rotated by [angle] degrees and in perspective, with noise: like a phone camera. */
    private fun camera(m: QrMatrix, angle: Double, tilt: Double = 0.0, noise: Int = 0, scale: Double = 5.0): LumaImage {
        val side = ((m.size + 16) * scale * 1.6).toInt()
        val img = ByteArray(side * side)
        val c = side / 2.0
        val a = Math.toRadians(angle)
        for (y in 0 until side) for (x in 0 until side) {
            // Inverse map: image pixel → module coordinates.
            var dx = (x - c); var dy = (y - c)
            val k = 1.0 + tilt * dy / side
            dx /= k; dy /= k
            val u = (dx * cos(a) + dy * sin(a)) / scale + m.size / 2.0
            val v = (-dx * sin(a) + dy * cos(a)) / scale + m.size / 2.0
            val dark = u >= 0 && v >= 0 && u < m.size && v < m.size && m[u.toInt(), v.toInt()]
            val base = if (dark) 40 else 210
            img[y * side + x] = (base + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0).coerceIn(0, 255).toByte()
        }
        return LumaImage(side, side, img)
    }

    @Test
    fun ourCodesAreReadByAnIndependentReader() {
        for (level in EcLevel.entries) for (text in samples) {
            val (side, px) = render(QrEncoder.encode(text, level))
            assertEquals("$level ${text.take(20)}", text, zxingDecode(side, px))
        }
    }

    @Test
    fun weReadCodesFromAnIndependentWriter() {
        for (level in ErrorCorrectionLevel.entries) for (text in samples) {
            val (side, px) = render(zxingEncode(text, level))
            assertEquals("$level ${text.take(20)}", text, QrDecoder.decode(LumaImage.fromArgb(side, side, px)))
        }
    }

    @Test
    fun manyRandomCodesBothWays() {
        repeat(150) {
            val text = randomText(rnd.nextInt(1, 1100))
            val level = EcLevel.entries[rnd.nextInt(4)].takeIf { text.length < 900 } ?: EcLevel.L
            val (side, px) = render(QrEncoder.encode(text, level))
            assertEquals(text, zxingDecode(side, px))
            assertEquals(text, QrDecoder.decode(LumaImage.fromArgb(side, side, px)))
        }
    }

    @Test
    fun cameraLikePictures() {
        val text = "vpn://" + randomText(300)
        val m = QrEncoder.encode(text, EcLevel.M)
        for (angle in listOf(0.0, 17.0, 90.0, 180.0, 233.0)) {
            assertEquals("angle $angle", text, QrDecoder.decode(camera(m, angle)))
        }
        assertEquals("perspective", text, QrDecoder.decode(camera(m, 10.0, tilt = 0.25)))
        assertEquals("noise", text, QrDecoder.decode(camera(m, 5.0, noise = 45)))
    }

    @Test
    fun invertedMirroredAndSeveralCodes() {
        val text = "vpn://" + randomText(120)
        val m = QrEncoder.encode(text)
        val (side, px) = render(m)
        // Light on dark.
        val inv = IntArray(px.size) { px[it] xor 0x00FFFFFF }
        assertEquals(text, QrDecoder.decode(LumaImage.fromArgb(side, side, inv)))
        // Mirrored.
        val mir = IntArray(px.size) { i -> px[(i / side) * side + side - 1 - i % side] }
        assertEquals(text, QrDecoder.decode(LumaImage.fromArgb(side, side, mir)))
        // Two different codes side by side (a series from AmneziaVPN on one screenshot).
        val other = "vpn://" + randomText(100)
        val (s2, px2) = render(QrEncoder.encode(other))
        val w = side + s2; val h = maxOf(side, s2)
        val both = IntArray(w * h) { -1 }
        for (y in 0 until side) System.arraycopy(px, y * side, both, y * w, side)
        for (y in 0 until s2) System.arraycopy(px2, y * s2, both, y * w + side, s2)
        assertEquals(setOf(text, other), QrDecoder.decodeAll(LumaImage.fromArgb(w, h, both)).toSet())
    }

    @Test
    fun nothingInAnEmptyOrNoisyPicture() {
        assertTrue(QrDecoder.decodeAll(LumaImage(200, 200, ByteArray(200 * 200) { -1 })).isEmpty())
        assertTrue(QrDecoder.decodeAll(LumaImage(200, 200, ByteArray(200 * 200) { rnd.nextInt(256).toByte() })).isEmpty())
    }
}
