package io.github.est4s.terminal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Lets the camera app write the photo `pocket camera` asked for: one
 * temporary file at a time, under a fresh random name, so the camera app
 * can reach nothing else.
 */
class PhotoProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "io.github.est4s.terminal.photo"

        @Volatile
        private var current: Pair<String, File>? = null

        /** Where the camera app may write [file], until the next call. */
        fun uriFor(file: File): Uri {
            val name = "${UUID.randomUUID()}.jpg"
            current = name to file
            return Uri.parse("content://$AUTHORITY/$name")
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (name, file) = current ?: throw FileNotFoundException(uri.toString())
        if (uri.path != "/$name") throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
}
