package io.github.est4s.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands Android's installer the one APK `pocket install-apk` asked for.
 * Only debug builds declare it (app/src/debug/AndroidManifest.xml), along
 * with the permission to install apps.
 */
class ApkProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "io.github.est4s.terminal.apk"
        const val MIME = "application/vnd.android.package-archive"
        private const val NAME = "app.apk"
        val uri: Uri = Uri.parse("content://$AUTHORITY/$NAME")

        // Set just before the installer opens.
        @Volatile
        var apk: File? = null
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = apk?.takeIf { uri.path == "/$NAME" && mode == "r" } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = MIME

    // The installer asks for the file's name and size.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = apk ?: return null
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
