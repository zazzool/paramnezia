package net.paramnezia.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import net.paramnezia.core.amnezia.AmneziaQr
import net.paramnezia.core.qr.LumaImage
import net.paramnezia.core.qr.QrDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The QR scanner: the camera through Camera2 and our own decoder (core/qr), no libraries.
 *
 * Frames are decoded on one background thread, the newest frame only (a frame that arrives while
 * the previous one is being decoded is dropped, so the preview never lags). An AmneziaVPN series of
 * codes is collected right here, the camera staying open between them ("QR 2 из 3"); the result is
 * every text of the series in order, or the one text of a single code. A tap toggles the torch.
 */
class QrScanActivity : Activity() {
    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private val bg = HandlerThread("pn-camera").apply { start() }
    private val handler = Handler(bg.looper)
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "pn-qr").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null
    private var torch = false
    private val series = AmneziaQr.Assembler()
    private val chunks = HashMap<Int, String>()
    @Volatile private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        texture = TextureView(this)
        hint = TextView(this).apply {
            text = "Наведите камеру на QR-код"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 160)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(texture)
            addView(Viewfinder(this@QrScanActivity))
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { toggleTorch() }
        }
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else { hint.text = "Без доступа к камере QR-код не прочитать"; texture.postDelayed({ finish() }, 1_500) }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startWhenReady()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        bg.quitSafely()
        decoder.shutdownNow()
        super.onDestroy()
    }

    private fun startWhenReady() {
        if (camera != null) return
        if (texture.isAvailable) openCamera()
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    @Suppress("MissingPermission") // checked by the callers
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: cm.cameraIdList.firstOrNull() ?: run { hint.text = "Камера не найдена"; return }
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        // About 1280×720: enough pixels per module for dense codes, cheap to decode.
        val previewSize = closest(map.getOutputSizes(SurfaceTexture::class.java), 1280, 720)
        val frameSize = closest(map.getOutputSizes(ImageFormat.YUV_420_888), 1280, 720)
        texture.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        fitPreview(previewSize)
        val r = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        reader = r
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startSession(device, r)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; runOnUiThread { hint.text = "Камера недоступна" } }
            }, handler)
        }.onFailure { hint.text = "Камера недоступна" }
    }

    @Suppress("DEPRECATION") // the list form of createCaptureSession covers API 26+
    private fun startSession(device: CameraDevice, r: ImageReader) {
        val preview = Surface(texture.surfaceTexture ?: return)
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            addTarget(r.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        request = req
        device.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                runCatching { s.setRepeatingRequest(req.build(), null, handler) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { hint.text = "Камера недоступна" } }
        }, handler)
    }

    private fun toggleTorch() {
        val req = request ?: return
        torch = !torch
        req.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        runCatching { session?.setRepeatingRequest(req.build(), null, handler) }
    }

    private fun stopCamera() {
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
    }

    /** The newest frame's brightness plane to the decoder, unless it is still busy with the last one. */
    private fun onFrame(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        if (finished || !busy.compareAndSet(false, true)) { image.close(); return }
        val w = image.width; val h = image.height
        val plane = image.planes[0]
        val stride = plane.rowStride
        val buf = plane.buffer
        val luma = ByteArray(w * h)
        for (y in 0 until h) { buf.position(y * stride); buf.get(luma, y * w, w) }
        image.close()
        decoder.execute {
            try {
                val texts = runCatching { QrDecoder.decodeAll(LumaImage(w, h, luma)) }.getOrDefault(emptyList())
                if (texts.isNotEmpty()) runOnUiThread { onTexts(texts) }
            } finally { busy.set(false) }
        }
    }

    private fun onTexts(texts: List<String>) {
        if (finished) return
        for (t in texts) {
            val chunk = AmneziaQr.parseChunk(t)
            if (chunk == null) { finish(listOf(t)); return }
            chunks[chunk.index] = t
            if (series.offer(chunk) != null) { finish((0 until series.expected).map { chunks.getValue(it) }); return }
            hint.text = "QR ${series.received} из ${series.expected}: покажите следующий"
        }
    }

    private fun finish(texts: List<String>) {
        finished = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXTS, texts.toTypedArray()))
        finish()
    }

    /** Centre-crop: the preview fills the screen without being stretched. */
    private fun fitPreview(size: Size) {
        texture.post {
            val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
            if (vw == 0f || vh == 0f) return@post
            val portrait = vh > vw
            val bw = if (portrait) size.height.toFloat() else size.width.toFloat()
            val bh = if (portrait) size.width.toFloat() else size.height.toFloat()
            val scale = maxOf(vw / bw, vh / bh)
            val m = Matrix()
            m.setScale(bw * scale / vw, bh * scale / vh, vw / 2, vh / 2)
            texture.setTransform(m)
        }
    }

    private fun closest(sizes: Array<Size>?, w: Int, h: Int): Size =
        sizes?.minByOrNull { Math.abs(it.width * it.height - w * h) + if (it.width < it.height) 1_000_000 else 0 } ?: Size(w, h)

    /** A dark veil with a clear square in the middle, where the code should go. */
    private class Viewfinder(context: Context) : View(context) {
        private val veil = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val side = minOf(width, height) * 0.68f
            val l = (width - side) / 2; val t = (height - side) / 2.4f
            val r = RectF(l, t, l + side, t + side)
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRoundRect(r, 36f, 36f, Path.Direction.CW)
            }
            c.drawPath(path, veil)
            c.drawRoundRect(r, 36f, 36f, edge)
        }
    }

    /** Opens the scanner; the result is the texts read (one code, or a whole series in order). */
    class Contract : ActivityResultContract<Unit, List<String>?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, QrScanActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            intent?.getStringArrayExtra(EXTRA_TEXTS)?.toList()?.takeIf { resultCode == RESULT_OK && it.isNotEmpty() }
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val EXTRA_TEXTS = "net.paramnezia.QR_TEXTS"
    }
}
