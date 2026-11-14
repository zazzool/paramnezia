package net.paramnezia.desktop

import net.paramnezia.core.qr.LumaImage
import net.paramnezia.core.qr.QrDecoder
import java.awt.image.BufferedImage

/** Reads QR codes from a picture: a screenshot of AmneziaVPN's QR (a whole series at once), or a photo of it. */
object QrImage {
    fun decode(image: BufferedImage): List<String> {
        val w = image.width; val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)
        return QrDecoder.decodeAll(LumaImage.fromArgb(w, h, argb))
    }
}
