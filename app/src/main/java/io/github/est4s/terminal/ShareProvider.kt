package io.github.est4s.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.est4s.terminal.core.SharedFiles
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands other apps the files `pocket share` shared, read-only:
 * `content://AUTHORITY/TOKEN/N/NAME`. Not exported: apps only get the
 * URIs they were granted with the share.
 */
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "io.github.est4s.terminal.share"
        val shared = SharedFiles()

        fun uris(files: List<File>): List<Uri> {
            val token = shared.add(files)
            return files.mapIndexed { i, file ->
                Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath(token).appendPath(i.toString()).appendPath(file.name).build()
            }
        }

        fun typeOf(file: File): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
    }

    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val segments = uri.pathSegments
        val index = segments.getOrNull(1)?.toIntOrNull()
        return segments.getOrNull(0)?.let { token -> index?.let { shared.find(token, it) } }
            ?: throw FileNotFoundException(uri.toString())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only: $uri")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = runCatching { typeOf(fileOf(uri)) }.getOrNull() ?: "application/octet-stream"

    // Receiving apps ask for the file's name and size.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = runCatching { fileOf(uri) }.getOrNull() ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
}
