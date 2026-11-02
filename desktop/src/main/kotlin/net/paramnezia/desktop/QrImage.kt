package net.paramnezia.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.awt.image.BufferedImage

/** Reads QR codes from a picture: a screenshot of AmneziaVPN's QR, or a photo of it. */
object QrImage {
    fun decode(image: BufferedImage): List<String> {
        val bitmap = BinaryBitmap(HybridBinarizer(Source(image)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        // Several codes on one picture (a series side by side), else one.
        val many = runCatching { QRCodeMultiReader().decodeMultiple(bitmap, hints).map { it.text } }.getOrDefault(emptyList())
        if (many.isNotEmpty()) return many.distinct()
        return listOfNotNull(runCatching { QRCodeReader().decode(bitmap, hints).text }.getOrNull())
    }

    private class Source(img: BufferedImage) : LuminanceSource(img.width, img.height) {
        private val lum = ByteArray(img.width * img.height).also { out ->
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                val a = p ushr 24 and 0xff
                // Transparent pixels count as white (QR codes on transparent PNGs).
                val v = if (a < 128) 255 else ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
                out[y * img.width + x] = v.toByte()
            }
        }
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val r = if (row == null || row.size < width) ByteArray(width) else row
            System.arraycopy(lum, y * width, r, 0, width)
            return r
        }
        override fun getMatrix(): ByteArray = lum
    }
}
