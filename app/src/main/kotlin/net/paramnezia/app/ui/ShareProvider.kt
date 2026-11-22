package net.paramnezia.app.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the settings files the admin shares (cache/share/<name>.vpn) to the app the admin picked,
 * read-only, through a one-off URI grant. Replaces AndroidX's FileProvider with exactly that: one
 * folder and a separate directory for verified APKs, with plain file names only, no XML
 * path configuration. Not exported; only a grant opens a file.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        val update = segments.size == 2 && segments[0] == UPDATES
        val name = (if (update) segments[1] else segments.singleOrNull()) ?: throw FileNotFoundException("bad uri")
        if (update && !UPDATE_NAME.matches(name)) throw FileNotFoundException("bad update name")
        if (!NAME.matches(name) || name.startsWith(".")) throw FileNotFoundException("bad name")
        val dir = (if (update) updateDir(ctx) else dir(ctx)).canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply { addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }) }
    }

    override fun getType(uri: Uri) = if (uri.pathSegments.size == 2 && uri.pathSegments.first() == UPDATES) APK_TYPE else "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()

    companion object {
        const val DIR = "share"
        const val APK_TYPE = "application/vnd.android.package-archive"
        private const val UPDATES = "updates"
        private val UPDATE_NAME = Regex("paramnezia-[a-f0-9]{64}[.]apk")
        fun updateDir(context: Context): File = File(context.cacheDir, UPDATES)
        fun updateUri(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(context.packageName + ".share")
                .appendPath(UPDATES).appendPath(file.name).build()
        private val NAME = Regex("[^/\\\\\\u0000]{1,128}")

        fun dir(context: Context): File = File(context.cacheDir, DIR)
        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(context.packageName + ".share").appendPath(file.name).build()
    }
}
