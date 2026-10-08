package io.github.est4s.terminal.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where the app tells the menu which version is available (in Debian). */
const val UPDATE_NOTICE = "tmp/.mynx/update-available"

/** Seconds `mynx` waits for `update-check`: GitHub's answer, with room for a slow network. */
private const val CHECK_SECONDS = 30

private const val UNKNOWN_SIZE_STEP = 256 * 1024L

/**
 * Updates from GitHub releases. Keeps what checks found in [stateFile],
 * writes the available version to [noticeFile] for the launcher menu,
 * downloads APKs into [downloads]. [current] is this app's version
 * name; [releases] is false in builds that don't update from releases
 * (Mynx Dev), which then never check. [fetch] asks GitHub for the
 * latest release and [download] fetches an APK (see [downloadApk]);
 * both block, so they run through [background]. [notify] tells the
 * user a version is available, once per version.
 */
class Updater(
    private val stateFile: File,
    private val noticeFile: File,
    private val downloads: File,
    private val current: String,
    private val releases: Boolean,
    private val fetch: () -> ReleaseCheck,
    private val download: (apk: ReleaseApk, dest: File, cancelled: () -> Boolean, progress: (Long, Long?) -> Unit) -> ApkDownload,
    private val notify: (version: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val background: (() -> Unit) -> Unit = { job -> thread(name = "updates") { job() } },
) {
    private val checking = AtomicBoolean(false)
    // The release the last check found, so installing doesn't ask GitHub again.
    @Volatile
    private var found: Release? = null

    /** When the app starts: the menu's notice from the last check, and no installed downloads left. */
    fun start() {
        runCatching { writeNotice(readUpdateState(stateFile)) }
        downloads.listFiles().orEmpty().filter { !isNewer(versionOfApk(it.name) ?: "", current) }.forEach { it.delete() }
    }

    /** Checks GitHub in the background if a check is due and [on] (the `update-check` setting). */
    fun checkIfDue(on: Boolean) {
        if (!releases || !on || !checkDue(readUpdateState(stateFile), now())) return
        if (!checking.compareAndSet(false, true)) return
        background {
            try {
                val (_, state) = check()
                if (shouldNotify(current, state)) {
                    save(state.notified(updateAvailable(current, state)!!))
                    notify(updateAvailable(current, state)!!)
                }
            } finally {
                checking.set(false)
            }
        }
    }

    /**
     * The `update-check` and `update-install` requests, for
     * [MynxRequests]'s `later`. `update-install VERSION` streams the
     * download's progress, then [install] opens Android's installer for
     * the APK: null if it did, else why not.
     */
    fun requests(install: (File) -> String?): Map<String, Later> {
        val checkNow = Later(CHECK_SECONDS) { _, reply ->
            if (!releases) return@Later reply.refuse(NO_RELEASES)
            background { answerCheck(reply) }
        }
        val installNow = Later(null) { args, reply ->
            if (!releases) return@Later reply.refuse(NO_RELEASES)
            val version = args.firstOrNull()?.trim().orEmpty()
            if (version.isEmpty()) return@Later reply.refuse("update-install needs the version to install")
            if (!isNewer(version, current)) return@Later reply.refuse("$version isn't newer than this version ($current)")
            val cancelled = AtomicBoolean(false)
            reply.onCancel { cancelled.set(true) }
            background { runInstall(version, reply, cancelled, install) }
        }
        return mapOf("update-check" to checkNow, "update-install" to installNow)
    }

    private fun answerCheck(reply: PendingReply) {
        val (result, state) = check()
        when (result) {
            is ReleaseCheck.Found -> {
                val release = result.release
                val available = isNewer(release.version.toString(), current)
                // The user is looking at it: no notification for this one.
                if (available) save(state.notified(release.version.toString()))
                val fields = mutableListOf(
                    "current" to json(current),
                    "latest" to json(release.version.toString()),
                    "available" to available.toString(),
                )
                if (available) {
                    fields += "tag" to json(release.tag)
                    fields += "notes" to json(release.notes)
                    fields += "size" to release.apk.size.toString()
                    fields += "published" to (release.published?.toString() ?: "null")
                }
                reply.ok(*fields.toTypedArray())
            }
            ReleaseCheck.NoRelease -> reply.ok("current" to json(current), "latest" to "null", "available" to "false")
            is ReleaseCheck.Failed -> reply.refuse("couldn't check for updates: ${result.message}")
            is ReleaseCheck.BadData -> reply.refuse("couldn't check for updates: ${result.message}")
            null -> reply.refuse("couldn't check for updates")
        }
    }

    private fun runInstall(version: String, reply: PendingReply, cancelled: AtomicBoolean, install: (File) -> String?) {
        val release = found?.takeIf { it.version.toString() == version } ?: run {
            when (val result = check().first) {
                is ReleaseCheck.Found -> result.release
                ReleaseCheck.NoRelease -> return reply.refuse("there's no release to update to")
                is ReleaseCheck.Failed -> return reply.refuse("couldn't check for updates: ${result.message}")
                is ReleaseCheck.BadData -> return reply.refuse("couldn't check for updates: ${result.message}")
            }
        }
        if (release.version.toString() != version) {
            return reply.refuse("the latest release is ${release.version} now, not $version: run mynx update again")
        }
        val dest = File(downloads, release.apk.name)
        downloads.listFiles().orEmpty().filter { it != dest }.forEach { it.delete() }
        if (!matches(dest, release.apk)) {
            dest.delete()
            downloads.mkdirs()
            var sent = -1L
            val result = download(release.apk, dest, cancelled::get) { bytes, total ->
                val step = total?.let { maxOf(it / 100, 1) } ?: UNKNOWN_SIZE_STEP
                if (sent < 0 || bytes - sent >= step || bytes == total) {
                    sent = bytes
                    reply.line(obj("bytes" to bytes.toString(), "total" to (total?.toString() ?: "null")))
                }
            }
            when (result) {
                is ApkDownload.Done -> {}
                ApkDownload.Cancelled -> return
                is ApkDownload.Failed -> return reply.refuse("the download failed: ${result.message}")
            }
        }
        if (cancelled.get()) return
        install(dest)?.let { return reply.refuse(it) }
        reply.ok("version" to json(version), "bytes" to dest.length().toString())
    }

    // Asks GitHub and records what it said.
    private fun check(): Pair<ReleaseCheck, UpdateState> {
        val result = fetch()
        if (result is ReleaseCheck.Found) found = result.release
        if (result is ReleaseCheck.NoRelease) found = null
        val state = synchronized(this) { readUpdateState(stateFile).afterCheck(result, now()).also { save(it) } }
        return result to state
    }

    @Synchronized
    private fun save(state: UpdateState) {
        runCatching { writeUpdateState(stateFile, state) }
        runCatching { writeNotice(state) }
    }

    private fun writeNotice(state: UpdateState) {
        val version = updateAvailable(current, state)
        if (version == null) {
            noticeFile.delete()
            return
        }
        noticeFile.parentFile?.mkdirs()
        val temp = File(noticeFile.parentFile, ".${noticeFile.name}.tmp")
        temp.writeText("$version\n")
        temp.renameTo(noticeFile)
    }
}

private const val NO_RELEASES = "this build of the app doesn't update from GitHub releases"

private val APK_NAME = Regex("""mynx-(.+)\.apk""")

private fun versionOfApk(name: String): String? = APK_NAME.matchEntire(name)?.groupValues?.get(1)

// A download already there, complete and as GitHub describes it.
private fun matches(file: File, apk: ReleaseApk): Boolean {
    if (!file.isFile || file.length() != apk.size) return false
    val sha256 = apk.sha256 ?: return true
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == sha256
}
