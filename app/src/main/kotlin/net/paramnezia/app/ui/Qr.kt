package net.paramnezia.app.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.amnezia.AmneziaQr
import kotlinx.coroutines.delay

private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp.asImageBitmap()
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
        Image(bitmaps[i], contentDescription = "QR", modifier = Modifier.size(280.dp).background(Color.White).padding(4.dp), filterQuality = FilterQuality.None)
        if (codes.size > 1) Text("QR ${i + 1} из ${codes.size}")
    }
}
