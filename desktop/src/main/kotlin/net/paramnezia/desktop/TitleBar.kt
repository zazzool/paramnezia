package net.paramnezia.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

/**
 * The window's title bar in the app's theme: dark at night (Windows 10 20H1+), and on Windows 11
 * painted the very colour of the app's background, so the frame and the app read as one piece.
 * Anything older simply keeps the system frame.
 */
object TitleBar {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    fun apply(window: java.awt.Window, dark: Boolean, background: Color, text: Color) {
        val dwm = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val on = IntByReference(if (dark) 1 else 0)
            if (dwm.DwmSetWindowAttribute(hwnd, USE_IMMERSIVE_DARK_MODE, on, 4) != 0) {
                dwm.DwmSetWindowAttribute(hwnd, USE_IMMERSIVE_DARK_MODE_OLD, on, 4)
            }
            dwm.DwmSetWindowAttribute(hwnd, CAPTION_COLOR, IntByReference(colorRef(background)), 4)
            dwm.DwmSetWindowAttribute(hwnd, TEXT_COLOR, IntByReference(colorRef(text)), 4)
            // Repaint the frame now, not at the next activation.
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0020)
        }
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
