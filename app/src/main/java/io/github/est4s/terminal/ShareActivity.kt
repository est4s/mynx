package io.github.est4s.terminal

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import io.github.est4s.terminal.core.CONFIG_DIR
import io.github.est4s.terminal.core.Inbox
import io.github.est4s.terminal.core.RootfsInstaller
import io.github.est4s.terminal.core.filesToSave
import io.github.est4s.terminal.core.inbox
import io.github.est4s.terminal.core.loadSettings
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

private const val SHARED_CHANNEL_ID = "shared"
private const val SHARED_NOTICE_ID = 2

/**
 * The app in Android's share sheet: saves what other apps share into
 * Debian's share folder (the share-folder setting) and says where in a
 * notification. Stays open while copying: the read grant on the shared
 * files lasts as long as this activity.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val installer = RootfsInstaller(filesDir)
        if (!installer.isInstalled) {
            done("Open the app once first, so it can set up Debian")
            return
        }
        val home = File(installer.rootfs, "root")
        val inbox = inbox(loadSettings(File(home, "$CONFIG_DIR/settings.conf")).settings, home)
        val share = intent
        setContentView(TextView(this).apply {
            text = "Saving to ${inbox.label}…"
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        })
        thread(name = "share-save") {
            val text = share.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val uris = filesToSave(streamsOf(share), clipUris(share), text)
            val message = try {
                if (uris.isEmpty() && !text.isNullOrEmpty()) {
                    val file = inbox.saveText(text, share.getStringExtra(Intent.EXTRA_SUBJECT))
                    inbox.notice(listOf(file), failed = 0)
                } else {
                    val saved = uris.mapNotNull { save(inbox, it) }
                    inbox.notice(saved, failed = uris.size - saved.size)
                }
            } catch (e: Exception) {
                "Couldn't save to ${inbox.label}: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { done(message) }
        }
    }

    @Suppress("DEPRECATION") // the typed getParcelable…Extra need API 33
    private fun streamsOf(share: Intent): List<Uri> = when (share.action) {
        Intent.ACTION_SEND_MULTIPLE -> share.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    private fun clipUris(share: Intent): List<Uri> {
        val clip = share.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    // The saved file, or null if the other app's file couldn't be read.
    private fun save(inbox: Inbox, uri: Uri): File? {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        }.getOrNull()
        val extension = contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val file = inbox.newFile(name, extension)
        return try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("no data")
            input.use { file.outputStream().use { out -> it.copyTo(out) } }
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun done(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.areNotificationsEnabled()) {
            manager.createNotificationChannel(
                NotificationChannel(SHARED_CHANNEL_ID, "Shared to the app", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            manager.notify(SHARED_NOTICE_ID, Notification.Builder(this, SHARED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
