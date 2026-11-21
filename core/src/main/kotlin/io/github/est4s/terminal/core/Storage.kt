package io.github.est4s.terminal.core

/**
 * How Android asks for all of the phone's storage: from Android 11 (API
 * 30) "All files access" (`MANAGE_EXTERNAL_STORAGE`), turned on in a
 * settings page; before it the storage permissions' dialog.
 */
enum class StorageAsk(val word: String) { SETTINGS("settings"), DIALOG("dialog") }

fun storageAsk(sdk: Int): StorageAsk = if (sdk >= 30) StorageAsk.SETTINGS else StorageAsk.DIALOG

/**
 * The phone's storage for `mynx storage` and Files: [granted] once Android
 * lets the app (so Debian) see all of it; [ask] asks, the way [how] says,
 * and gives null when it could, else why not.
 */
interface StorageAccess {
    val granted: Boolean
    val how: StorageAsk
    fun ask(): String?
}

/** Files' bar asking for access: shown in the phone's storage (`/storage`, [HOST_BINDS]) until it's allowed. */
fun storageBarShown(folder: String, granted: Boolean): Boolean {
    if (granted) return false
    val clean = folder.trimEnd('/')
    return clean == "/storage" || clean.startsWith("/storage/")
}

/** `mynx storage`'s answer: Android lets the app see `all` of the phone's storage, or `some`. */
internal fun storageWord(granted: Boolean) = if (granted) "all" else "some"
