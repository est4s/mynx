package io.github.est4s.terminal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.est4s.terminal.core.CONFIG_DIR
import io.github.est4s.terminal.core.ChatAttachment
import io.github.est4s.terminal.core.Inbox
import io.github.est4s.terminal.core.inbox
import io.github.est4s.terminal.core.loadSettings
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread

private val PHOTO_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
// Android's photo picker takes at most its own limit; ten is under it everywhere.
private const val MAX_PHOTOS = 10

/**
 * Chat's + tiles: the camera app (as `mynx camera`, through
 * [PhotoProvider]), Android's photo picker and its file picker. What's
 * picked is copied into Debian's share folder (the share-folder setting,
 * as shares are, never overwriting) off the main thread, and handed back
 * on it as [ChatAttachment]s with the paths Debian sees.
 */
class ChatFiles(private val activity: Activity, private val asks: ActivityAsks, private val rootfs: File) {
    private val main = Handler(Looper.getMainLooper())

    fun pick(source: ChatAttachSource, done: (List<ChatAttachment>) -> Unit) {
        when (source) {
            ChatAttachSource.CAMERA -> camera(done)
            ChatAttachSource.PHOTOS -> {
                val intent = if (Build.VERSION.SDK_INT >= 33) {
                    Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX_PHOTOS)
                } else {
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                picker(intent, "no photo picker on the phone", done)
            }
            ChatAttachSource.FILES -> picker(
                Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                "no file picker on the phone",
                done,
            )
        }
    }

    // Declaring CAMERA (for `mynx camera --quick`) makes Android refuse the camera app too until it's allowed.
    private fun camera(done: (List<ChatAttachment>) -> Unit) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhoto(done)
            return
        }
        asks.askPermissions(arrayOf(Manifest.permission.CAMERA)) { allowed ->
            if (allowed) takePhoto(done) else toast("The camera wasn't allowed (allow it in the app's Android settings)")
        }
    }

    private fun takePhoto(done: (List<ChatAttachment>) -> Unit) {
        val photo = File(File(activity.cacheDir, "chat-photos").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        photo.createNewFile()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uriFor(photo))
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val started = asks.startForData(intent) { ok, _ ->
            if (!ok || photo.length() == 0L) {
                photo.delete()
                return@startForData
            }
            thread(name = "chat-photo") {
                val saved = try {
                    val box = shareFolder()
                    val target = box.newFile("photo-${LocalDateTime.now().format(PHOTO_STAMP)}.jpg", "jpg")
                    try {
                        photo.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                        attachment(box, target, image = true)
                    } catch (e: IOException) {
                        target.delete()
                        throw e
                    }
                } catch (e: Exception) {
                    toast("Couldn't save the photo: ${e.message ?: e.javaClass.simpleName}")
                    null
                } finally {
                    photo.delete()
                }
                saved?.let { main.post { done(listOf(it)) } }
            }
        }
        if (!started) {
            photo.delete()
            toast("No camera app on the phone")
        }
    }

    private fun picker(intent: Intent, missing: String, done: (List<ChatAttachment>) -> Unit) {
        val started = asks.startForData(intent) { ok, data ->
            val uris = if (ok && data != null) urisOf(data) else emptyList()
            if (uris.isEmpty()) return@startForData
            // The read grant lasts while the activity does: copied now, off the main thread.
            thread(name = "chat-files") {
                val box = try {
                    shareFolder()
                } catch (e: Exception) {
                    toast("Couldn't open the share folder: ${e.message ?: e.javaClass.simpleName}")
                    return@thread
                }
                val saved = uris.mapNotNull { copy(box, it) }
                if (saved.size < uris.size) {
                    val failed = uris.size - saved.size
                    toast(if (failed == 1) "1 file couldn't be read" else "$failed files couldn't be read")
                }
                if (saved.isNotEmpty()) main.post { done(saved) }
            }
        }
        if (!started) toast(missing)
    }

    private fun urisOf(data: Intent): List<Uri> {
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    // The copied file, or null if the picked one couldn't be read.
    private fun copy(box: Inbox, uri: Uri): ChatAttachment? {
        val resolver = activity.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        }.getOrNull()
        val type = runCatching { resolver.getType(uri) }.getOrNull()
        val extension = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val file = box.newFile(name, extension)
        return try {
            val input = resolver.openInputStream(uri) ?: throw IOException("no data")
            input.use { file.outputStream().use { out -> it.copyTo(out) } }
            attachment(box, file, image = type?.startsWith("image/") == true)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun shareFolder(): Inbox {
        val home = File(rootfs, "root")
        return inbox(loadSettings(File(home, "$CONFIG_DIR/settings.conf")).settings, home)
    }

    // The folder's label is how Debian spells it: `~/Shared` or a full path.
    private fun attachment(box: Inbox, file: File, image: Boolean): ChatAttachment {
        val folder = if (box.label.startsWith("~/")) "/root/" + box.label.removePrefix("~/") else box.label
        return ChatAttachment(folder.trimEnd('/') + "/" + file.name, file.name, image)
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
}
