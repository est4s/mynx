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
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// One thread for the whole app: a long chat's photos decode one at a time.
private val WORKER: Executor = Executors.newSingleThreadExecutor { work -> Thread(work, "thumbnails").apply { isDaemon = true } }
// Squares of a few dozen kilobytes each.
private const val CACHED = 64
/** A thumbnail's side, in a chip 36 dp high. */
const val CHAT_THUMBNAIL_DP = 28

/**
 * Chat's attachment chips' pictures: the image at a Debian path
 * ([hostFile] finds it), cropped to a [CHAT_THUMBNAIL_DP] square and turned
 * upright by its EXIF orientation, decoded on [worker] and kept in a
 * cache. A file that can't be decoded (gone, or a format the phone
 * can't read) is remembered and keeps its icon.
 */
class ChatThumbnails(
    context: Context,
    private val hostFile: (String) -> File,
    private val worker: Executor = WORKER,
) {
    private val size = Pixel.dp(context, CHAT_THUMBNAIL_DP)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(CACHED)
    private val failed = HashSet<String>()

    /** Calls [done] on the main thread with [path]'s thumbnail: at once if it's cached, never if it can't be made. */
    fun load(path: String, done: (Bitmap) -> Unit) {
        cache.get(path)?.let { return done(it) }
        if (path in failed) return
        worker.execute {
            val bitmap = runCatching { decode(hostFile(path)) }.getOrNull()
            main.post {
                if (bitmap == null) {
                    failed += path
                } else {
                    cache.put(path, bitmap)
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
            postRotate(rotation(file).toFloat())
        }
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side, matrix, true)
        if (square !== full) full.recycle()
        return square
    }

    // Phones' cameras save a portrait photo sideways with a tag saying so.
    private fun rotation(file: File): Int = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
