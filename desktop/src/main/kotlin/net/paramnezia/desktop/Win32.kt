package net.paramnezia.desktop

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * The handful of Windows calls the app makes (DPAPI, the Run key, the window frame), through the
 * JDK's own foreign function API instead of JNA: no 3 MB library, no native stub unpacked into a
 * temporary folder at start-up, and a failed call reports its Windows error code.
 *
 * Every function is looked up the first time it is used; off Windows nothing here is touched.
 */
internal object Win32 {
    val isWindows: Boolean = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

    private val linker: Linker by lazy { Linker.nativeLinker() }
    private val captureLayout by lazy { Linker.Option.captureStateLayout() }
    private val lastErrorOffset by lazy { captureLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError")) }

    private val libs = HashMap<String, SymbolLookup>()

    private fun fn(lib: String, name: String, result: MemoryLayout?, vararg args: MemoryLayout, lastError: Boolean = false): MethodHandle {
        val lookup = synchronized(libs) { libs.getOrPut(lib) { SymbolLookup.libraryLookup(lib, Arena.global()) } }
        val symbol = lookup.find(name).orElseThrow { UnsatisfiedLinkError("$lib!$name") }
        val desc = if (result == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(result, *args)
        return if (lastError) linker.downcallHandle(symbol, desc, Linker.Option.captureCallState("GetLastError"))
        else linker.downcallHandle(symbol, desc)
    }

    private fun wide(arena: Arena, s: String): MemorySegment =
        arena.allocateFrom(JAVA_BYTE, *(s + "\u0000").toByteArray(Charsets.UTF_16LE))

    // ---- DPAPI ----

    /** DATA_BLOB { DWORD cbData; BYTE *pbData; } with the pointer at its natural alignment. */
    private val blob by lazy {
        MemoryLayout.structLayout(JAVA_INT.withName("cbData"), MemoryLayout.paddingLayout(ADDRESS.byteAlignment() - 4), ADDRESS.withName("pbData"))
    }
    private val blobData by lazy { blob.byteOffset(MemoryLayout.PathElement.groupElement("pbData")) }

    private val protectFn by lazy { fn("crypt32", "CryptProtectData", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, lastError = true) }
    private val unprotectFn by lazy { fn("crypt32", "CryptUnprotectData", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, lastError = true) }
    private val localFreeFn by lazy { fn("kernel32", "LocalFree", ADDRESS, ADDRESS) }
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    /** Encrypts [data] for the current Windows user (CryptProtectData, no extra entropy). */
    fun protect(data: ByteArray): ByteArray = dpapi(protectFn, data, "CryptProtectData")

    /** Decrypts what [protect] produced, on this computer and account only. */
    fun unprotect(data: ByteArray): ByteArray = dpapi(unprotectFn, data, "CryptUnprotectData")

    private fun dpapi(f: MethodHandle, data: ByteArray, name: String): ByteArray = Arena.ofConfined().use { a ->
        val input = a.allocate(blob)
        input.set(JAVA_INT, 0, data.size)
        input.set(ADDRESS, blobData, if (data.isEmpty()) a.allocate(1) else a.allocateFrom(JAVA_BYTE, *data))
        val output = a.allocate(blob)
        val state = a.allocate(captureLayout)
        val ok = f.invokeWithArguments(state, input, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, output) as Int
        if (ok == 0) throw IOException("$name failed (Windows error ${state.get(JAVA_INT, lastErrorOffset)})")
        val n = output.get(JAVA_INT, 0)
        val p = output.get(ADDRESS, blobData)
        try { p.reinterpret(n.toLong()).toArray(JAVA_BYTE) } finally { localFreeFn.invokeWithArguments(p) }
    }

    // ---- registry (HKEY_CURRENT_USER only) ----

    /** HKEY_CURRENT_USER is the predefined handle (HKEY)(LONG)0x80000001, sign-extended. */
    private val HKCU: MemorySegment by lazy { MemorySegment.ofAddress(0x80000001.toInt().toLong()) }
    private val regGetValueFn by lazy { fn("advapi32", "RegGetValueW", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS) }
    private val regSetKeyValueFn by lazy { fn("advapi32", "RegSetKeyValueW", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT) }
    private val regDeleteKeyValueFn by lazy { fn("advapi32", "RegDeleteKeyValueW", JAVA_INT, ADDRESS, ADDRESS, ADDRESS) }
    private const val REG_SZ = 1
    private const val RRF_RT_REG_SZ = 0x2
    private const val ERROR_FILE_NOT_FOUND = 2
    private const val ERROR_MORE_DATA = 234

    /** A string value under HKCU\[key], or null when there is none. */
    fun regGetString(key: String, name: String): String? = Arena.ofConfined().use { a ->
        val k = wide(a, key); val v = wide(a, name)
        val size = a.allocate(JAVA_INT)
        var buf = a.allocate(512)
        size.set(JAVA_INT, 0, buf.byteSize().toInt())
        var rc = regGetValueFn.invokeWithArguments(HKCU, k, v, RRF_RT_REG_SZ, MemorySegment.NULL, buf, size) as Int
        if (rc == ERROR_MORE_DATA) {
            buf = a.allocate(size.get(JAVA_INT, 0).toLong())
            rc = regGetValueFn.invokeWithArguments(HKCU, k, v, RRF_RT_REG_SZ, MemorySegment.NULL, buf, size) as Int
        }
        when (rc) {
            0 -> {
                val bytes = buf.asSlice(0, size.get(JAVA_INT, 0).toLong()).toArray(JAVA_BYTE)
                String(bytes, Charsets.UTF_16LE).substringBefore('\u0000')
            }
            ERROR_FILE_NOT_FOUND -> null
            else -> throw IOException("RegGetValue failed (Windows error $rc)")
        }
    }

    fun regSetString(key: String, name: String, value: String) = Arena.ofConfined().use { a ->
        val data = wide(a, value)
        val rc = regSetKeyValueFn.invokeWithArguments(HKCU, wide(a, key), wide(a, name), REG_SZ, data, data.byteSize().toInt()) as Int
        if (rc != 0) throw IOException("RegSetKeyValue failed (Windows error $rc)")
    }

    fun regDelete(key: String, name: String) = Arena.ofConfined().use { a ->
        val rc = regDeleteKeyValueFn.invokeWithArguments(HKCU, wide(a, key), wide(a, name)) as Int
        if (rc != 0 && rc != ERROR_FILE_NOT_FOUND) throw IOException("RegDeleteKeyValue failed (Windows error $rc)")
    }

    // ---- window frame ----

    private val dwmSetWindowAttributeFn by lazy { fn("dwmapi", "DwmSetWindowAttribute", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT) }
    private val setWindowPosFn by lazy { fn("user32", "SetWindowPos", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT) }

    /** DwmSetWindowAttribute with a DWORD value; the HRESULT (0 = done). */
    fun dwmSetInt(hwnd: Long, attribute: Int, value: Int): Int = Arena.ofConfined().use { a ->
        val v = a.allocate(JAVA_INT).also { it.set(JAVA_INT, 0, value) }
        dwmSetWindowAttributeFn.invokeWithArguments(MemorySegment.ofAddress(hwnd), attribute, v, 4) as Int
    }

    /** Makes Windows redraw the frame now (SWP_NOSIZE | NOMOVE | NOZORDER | FRAMECHANGED). */
    fun refreshFrame(hwnd: Long) {
        setWindowPosFn.invokeWithArguments(MemorySegment.ofAddress(hwnd), MemorySegment.NULL, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0020)
    }

    /**
     * Exercises every call for real (CI: `Paramnezia.exe --selftest` on the packaged app): DPAPI
     * both ways, a registry value written, read back and removed. Null when all is well.
     */
    fun selfTest(): String? = runCatching {
        val secret = ByteArray(300) { it.toByte() }
        val sealed = protect(secret)
        check(!sealed.contentEquals(secret) && sealed.size > secret.size) { "DPAPI did not encrypt" }
        check(unprotect(sealed).contentEquals(secret)) { "DPAPI round trip differs" }
        check(unprotect(protect(ByteArray(0))).isEmpty()) { "DPAPI empty round trip" }
        val key = "Software\\Paramnezia-selftest"
        val value = "\"C:\\Program Files\\Paramnezia\\Paramnezia.exe\" --minimized ✓ " + "x".repeat(400)
        regSetString(key, "v", value)
        check(regGetString(key, "v") == value) { "registry read back differs" }
        regDelete(key, "v")
        check(regGetString(key, "v") == null) { "registry value not deleted" }
        regDelete(key, "v") // deleting what is not there is fine
    }.exceptionOrNull()?.let { it.toString() }
}
