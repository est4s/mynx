package io.github.est4s.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.est4s.terminal.core.thumbnailSampleSize
import io.github.est4s.terminal.core.viewerSampleSize
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// One thread for the whole app: a long chat's photos decode one at a time.
private val WORKER: Executor = Executors.newSingleThreadExecutor { work -> Thread(work, "thumbnails").apply { isDaemon = true } }
// Squares of a few dozen kilobytes each.
private const val CACHED = 64
/** A thumbnail's side, in a chip 36 dp high. */
const val CHAT_THUMBNAIL_DP = 28
// A card's preview: its long side within this (it shows up to a card wide, 240 dp high).
private const val PREVIEW_DP = 480
// Previews are larger: a few megabytes each.
private const val PREVIEW_BYTES = 32 * 1024 * 1024

/**
 * Chat's attachment chips' pictures: the image at a Debian path
 * ([hostFile] finds it), cropped to a [CHAT_THUMBNAIL_DP] square and turned
 * upright by its EXIF orientation, decoded on [worker] and kept in a
 * cache. A file that can't be decoded (gone, or a format the phone
 * can't read) is remembered and keeps its icon. [preview] is an image
 * card's picture: whole, its long side within [PREVIEW_DP]. Files' rows
 * use one of their own, [sizeDp] squares.
 */
class ChatThumbnails(
    context: Context,
    private val hostFile: (String) -> File,
    private val worker: Executor = WORKER,
    sizeDp: Int = CHAT_THUMBNAIL_DP,
) {
    private val size = Pixel.dp(context, sizeDp)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(CACHED)
    private val failed = HashSet<String>()
    private val previewSize = Pixel.dp(context, PREVIEW_DP)
    private val previews = object : LruCache<String, Bitmap>(PREVIEW_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /**
     * Calls [done] on the main thread with [path]'s thumbnail: at once if
     * it's cached, never if it can't be made. [version] (the file's time)
     * tells a file written again from the one cached.
     */
    fun load(path: String, version: Long = 0, done: (Bitmap) -> Unit) =
        load(cache, "$path\u0000$version", done) { decode(hostFile(path)) }

    /** As [load], [path]'s whole picture for a card; [version] (its file time) tells a picture drawn again from the last. */
    fun preview(path: String, version: Long, done: (Bitmap) -> Unit) =
        load(previews, "$path\u0000$version", done) { decodeUpright(hostFile(path), previewSize) }

    private fun load(into: LruCache<String, Bitmap>, key: String, done: (Bitmap) -> Unit, decode: () -> Bitmap?) {
        into.get(key)?.let { return done(it) }
        if (key in failed) return
        worker.execute {
            val bitmap = runCatching { decode() }.getOrNull()
            main.post {
                if (bitmap == null) {
                    failed += key
                } else {
                    into.put(key, bitmap)
                    done(bitmap)
                }
            }
        }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, size)
        }
        val full = BitmapFactory.decodeFile(file.path, options) ?: return null
        val side = minOf(full.width, full.height)
        val matrix = Matrix().apply {
            postScale(size.toFloat() / side, size.toFloat() / side)
            postRotate(exifRotation(file).toFloat())
        }
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side, matrix, true)
        if (square !== full) full.recycle()
        return square
    }
}

// Phones' cameras save a portrait photo sideways with a tag saying so.
private fun exifRotation(file: File): Int = runCatching {
    when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

/**
 * The whole picture in [file], its long side within [maxSide] pixels
 * ([viewerSampleSize]) and turned upright; null if it can't be decoded.
 * Reads the file: call it off the main thread.
 */
fun decodeUpright(file: File, maxSide: Int): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = viewerSampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    val full = BitmapFactory.decodeFile(file.path, options) ?: return null
    val degrees = exifRotation(file)
    if (degrees == 0) return full
    val turned = Bitmap.createBitmap(full, 0, 0, full.width, full.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    if (turned !== full) full.recycle()
    return turned
}
