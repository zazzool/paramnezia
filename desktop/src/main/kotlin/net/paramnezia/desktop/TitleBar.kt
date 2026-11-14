package net.paramnezia.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The window's title bar in the app's theme: dark at night (Windows 10 20H1+), and on Windows 11
 * painted the very colour of the app's background, so the frame and the app read as one piece.
 * Anything older simply keeps the system frame.
 */
object TitleBar {
    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    fun apply(window: ComposeWindow, dark: Boolean, background: Color, text: Color) {
        if (!Win32.isWindows) return
        runCatching {
            val hwnd = window.windowHandle
            if (hwnd == 0L) return
            val on = if (dark) 1 else 0
            if (Win32.dwmSetInt(hwnd, USE_IMMERSIVE_DARK_MODE, on) != 0) Win32.dwmSetInt(hwnd, USE_IMMERSIVE_DARK_MODE_OLD, on)
            Win32.dwmSetInt(hwnd, CAPTION_COLOR, colorRef(background))
            Win32.dwmSetInt(hwnd, TEXT_COLOR, colorRef(text))
            // Repaint the frame now, not at the next activation.
            Win32.refreshFrame(hwnd)
        }.onFailure { net.paramnezia.app.platform.Log.w("titlebar", "frame colours: $it") }
    }

    /** COLORREF is 0x00BBGGRR. */
    private fun colorRef(c: Color): Int {
        val argb = c.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
