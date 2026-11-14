package net.paramnezia.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import net.paramnezia.core.qr.EcLevel
import net.paramnezia.core.qr.QrEncoder
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.amnezia.AmneziaQr
import kotlinx.coroutines.delay

/**
 * One module per pixel with the standard 4-module quiet zone, drawn with Compose's own canvas (the
 * same code on Android and Windows). [level]: L for long keys (fewer, larger modules on screen), M for
 * short texts, which then survive glare and a dirty lens better at no cost in size.
 */
private fun qrBitmap(text: String, level: EcLevel = EcLevel.L): ImageBitmap {
    val m = QrEncoder.encode(text, level)
    val quiet = 4
    val side = m.size + 2 * quiet
    val bmp = ImageBitmap(side, side)
    val canvas = Canvas(bmp)
    canvas.drawRect(Rect(0f, 0f, side.toFloat(), side.toFloat()), Paint().apply { color = Color.White })
    val black = Paint().apply { color = Color.Black; isAntiAlias = false }
    for (y in 0 until m.size) {
        // Runs of dark modules as one rectangle each: a few hundred draws instead of thousands.
        var x = 0
        while (x < m.size) {
            if (!m[x, y]) { x++; continue }
            val start = x
            while (x < m.size && m[x, y]) x++
            canvas.drawRect(Rect((start + quiet).toFloat(), (y + quiet).toFloat(), (x + quiet).toFloat(), (y + quiet + 1).toFloat()), black)
        }
    }
    return bmp
}

/**
 * Shows a `vpn://` key as a QR code. Keys too long for one code are shown as AmneziaVPN's QR series,
 * cycling every second, so both apps can scan them.
 */
@Composable
fun KeyQr(key: String) {
    val codes = remember(key) {
        if (key.length <= 1800) listOf(key)
        else AmneziaQr.encode(AmneziaKey.compressedPayload(key))
    }
    val bitmaps = remember(codes) { codes.map(::qrBitmap) }
    var i by remember(codes) { mutableIntStateOf(0) }
    LaunchedEffect(codes) { while (codes.size > 1) { delay(1_000); i = (i + 1) % codes.size } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(bitmaps[i], contentDescription = "QR", modifier = Modifier.size(280.dp).background(Color.White), filterQuality = FilterQuality.None)
        if (codes.size > 1) Text("QR ${i + 1} из ${codes.size}")
    }
}

/** A short text (an address) as one QR code, for a phone camera. */
@Composable
fun AddressQr(text: String, size: androidx.compose.ui.unit.Dp = 168.dp) {
    val bitmap = remember(text) { qrBitmap(text, EcLevel.M) }
    Image(bitmap, contentDescription = "QR: $text", modifier = Modifier.size(size), filterQuality = FilterQuality.None)
}
