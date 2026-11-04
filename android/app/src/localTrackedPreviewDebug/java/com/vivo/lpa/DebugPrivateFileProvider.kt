package com.vivo.lpa

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** App-private, read-only URI bridge used only for explicit experimental debug demonstrations. */
class DebugPrivateFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "debug file provider is read-only" }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        require(selection == null && selectionArgs == null && sortOrder == null)
        val file = resolve(uri)
        val columns = projection ?: arrayOf("_display_name", "_size", "date_modified")
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    "_display_name" -> file.name
                    "_size" -> file.length()
                    "date_modified" -> file.lastModified() / 1_000L
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("debug file provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("debug file provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("debug file provider is read-only")

    private fun resolve(uri: Uri): File {
        require(BuildConfig.LOCAL_TRACKED_PREVIEW)
        val appContext = requireNotNull(context)
        require(uri.authority == appContext.packageName + AUTHORITY_SUFFIX)
        require(uri.pathSegments.size == 1)
        val name = uri.pathSegments.single()
        require(Regex("live-result-[0-9]+\\.jpg").matches(name)) {
            "unsupported debug private file"
        }
        val root = File(appContext.filesDir, "probe-native").canonicalFile
        val file = File(root, name).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile)
        return file
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".debugfiles"
    }
}
