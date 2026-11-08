package io.github.est4s.terminal

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import io.github.est4s.terminal.core.AppShortcut
import io.github.est4s.terminal.core.SHORTCUT_SAFE_ZONE
import kotlin.math.max

// An adaptive icon's 108 dp, at 4 px a dp.
private const val ICON_PX = 432
// The generated letter's size, as a share of the icon: well inside the safe zone.
private const val LETTER_SIZE = 0.3f
private val OPAQUE = 0xFF000000.toInt()

/**
 * Apps' home screen shortcuts (`mynx app shortcut`): pins one through
 * Android's own dialog and updates pinned ones. What each shows (id,
 * label, icon file or letter, colours) is core's [AppShortcut]; this only
 * draws it.
 */
class AppShortcuts(private val context: Context) {
    private val manager: ShortcutManager? get() = context.getSystemService(ShortcutManager::class.java)

    /** Asks Android to pin [shortcut]: null once asked, else why not. */
    fun pin(shortcut: AppShortcut): String? {
        val shortcuts = manager ?: return "this phone has no home screen shortcuts"
        if (!shortcuts.isRequestPinShortcutSupported) return "the home screen app doesn't take shortcuts"
        return try {
            if (shortcuts.requestPinShortcut(info(shortcut), null)) null else "Android didn't ask to add the shortcut"
        } catch (e: RuntimeException) {
            "couldn't add the shortcut: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Updates [shortcut]'s label and icon if it's pinned. */
    fun update(shortcut: AppShortcut) {
        val shortcuts = manager ?: return
        runCatching {
            if (shortcuts.pinnedShortcuts.any { it.id == shortcut.id }) shortcuts.updateShortcuts(listOf(info(shortcut)))
        }
    }

    private fun info(shortcut: AppShortcut): ShortcutInfo {
        val label = shortcut.label.ifEmpty { shortcut.name }
        return ShortcutInfo.Builder(context, shortcut.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithAdaptiveBitmap(drawIcon(shortcut)))
            .setIntent(WebAppActivity.shortcutIntent(context, shortcut.name))
            .build()
    }

    // app.conf's icon scaled into the safe zone on the theme's background, else the letter.
    private fun drawIcon(shortcut: AppShortcut): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(shortcut.background or OPAQUE)
        val box = (ICON_PX * SHORTCUT_SAFE_ZONE).toFloat()
        val picture = shortcut.icon?.let { file -> runCatching { decode(file.path, box.toInt()) }.getOrNull() }
        if (picture != null) {
            val scale = box / max(picture.width, picture.height)
            val width = picture.width * scale
            val height = picture.height * scale
            val left = (ICON_PX - width) / 2f
            val top = (ICON_PX - height) / 2f
            canvas.drawBitmap(picture, null, RectF(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            picture.recycle()
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shortcut.foreground or OPAQUE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = ICON_PX * LETTER_SIZE
            }
            val baseline = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(shortcut.letter, ICON_PX / 2f, baseline, paint)
        }
        return bitmap
    }

    // A big picture is read at a fraction of its size: the icon needs [size] pixels at most.
    private fun decode(path: String, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
