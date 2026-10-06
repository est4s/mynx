package io.github.est4s.terminal.core

import java.io.File

/** How long `pc26 camera` waits for the camera app: people take their time framing a shot. */
const val CAMERA_APP_SECONDS = 600
/** How long `pc26 camera --quick` waits for its shot. */
const val QUICK_SHOT_SECONDS = 30
const val MAX_TORCH_PERCENT = 100
// A quick shot lets exposure and focus settle at least this long, at most the longer.
private const val MIN_SETTLE_MS = 300L
private const val MAX_SETTLE_MS = 2500L

enum class Facing { FRONT, BACK }

/**
 * A photo `pc26 camera` asks for, to be saved in [target] (on the host;
 * [path] is its name in Debian): through the phone's camera app, or with
 * [quick], straight from that camera with no screen.
 */
data class PhotoQuery(val target: File, val path: String, val quick: Facing?)

/** Where the app reports the photo for one [PhotoQuery]; any thread may use it. */
interface PhotoReport {
    /** The photo is in [file], a temporary file: it's moved to the target. */
    fun taken(file: File)
    fun fail(message: String)
    /** `pc26` stopped waiting, or has gone: a photo taken after this is thrown away. */
    fun onCancel(block: () -> Unit)
}

/**
 * The `camera` and `camera-quick` requests, for [Pc26Requests]'s
 * `later`. Lines: the Debian path to save to, then for `camera-quick`
 * `front` or `back`. [take] starts the camera and must not block.
 */
fun cameraRequests(home: File, take: (PhotoQuery, PhotoReport) -> Unit): Map<String, Later> {
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    fun later(quick: Boolean, seconds: Int) = Later(seconds) { args, reply ->
        val query = parsePhotoQuery(args, quick, home.parentFile.path).getOrElse {
            reply.refuse(it.message!!)
            return@Later
        }
        if (!loadSettings(settings).settings.androidCamera) {
            reply.refuse("the camera is off (pc26 set android-camera on)")
            return@Later
        }
        var cancelled = false
        var stop: (() -> Unit)? = null
        reply.onCancel {
            synchronized(reply) { cancelled = true }
            stop?.invoke()
        }
        take(query, object : PhotoReport {
            override fun taken(file: File) {
                if (synchronized(reply) { cancelled }) {
                    file.delete()
                    return
                }
                val bytes = file.length()
                if (moveFile(file, query.target)) {
                    reply.ok("file" to json(query.path), "bytes" to bytes.toString())
                } else {
                    file.delete()
                    reply.refuse("couldn't save the photo to ${query.path}")
                }
            }

            override fun fail(message: String) = reply.refuse(message)

            override fun onCancel(block: () -> Unit) {
                stop = block
            }
        })
    }
    return mapOf("camera" to later(false, CAMERA_APP_SECONDS), "camera-quick" to later(true, QUICK_SHOT_SECONDS))
}

private fun parsePhotoQuery(args: List<String>, quick: Boolean, rootfs: String): Result<PhotoQuery> = runCatching {
    val path = args.getOrNull(0)?.trim().orEmpty().trimEnd('/')
    if (path.isEmpty()) throw IllegalArgumentException("camera needs a file to save the photo to")
    val target = targetFile(path, rootfs)
    val facing = if (!quick) null else when (val side = args.getOrNull(1)?.trim().orEmpty()) {
        "front" -> Facing.FRONT
        "back" -> Facing.BACK
        else -> throw IllegalArgumentException("the camera is front or back, not '$side'")
    }
    PhotoQuery(target, path, facing)
}

// The temporary file may be on another filesystem (phone storage).
private fun moveFile(from: File, to: File): Boolean =
    from.renameTo(to) || runCatching {
        from.copyTo(to, overwrite = true)
        from.delete()
    }.isSuccess

/**
 * Camera2's JPEG_ORIENTATION for a camera mounted at [sensorOrientation]
 * degrees, with the screen turned [displayRotation] degrees (Surface's
 * ROTATION_*: the phone itself is turned the other way).
 */
fun jpegOrientation(sensorOrientation: Int, facing: Facing, displayRotation: Int): Int {
    val device = (360 - displayRotation) % 360
    return when (facing) {
        Facing.BACK -> (sensorOrientation + device) % 360
        Facing.FRONT -> (sensorOrientation - device + 360) % 360
    }
}

/**
 * Whether a quick shot can be taken: auto-exposure ([ae], Camera2's
 * CONTROL_AE_STATE) and autofocus ([af], CONTROL_AF_STATE; null for a
 * camera without it) have settled, or waiting longer won't help.
 */
fun shotReady(ae: Int?, af: Int?, elapsedMs: Long): Boolean {
    if (elapsedMs >= MAX_SETTLE_MS) return true
    if (elapsedMs < MIN_SETTLE_MS) return false
    // CONVERGED, LOCKED, FLASH_REQUIRED
    val exposed = ae == null || ae in setOf(2, 3, 4)
    // PASSIVE_FOCUSED, FOCUSED_LOCKED, NOT_FOCUSED_LOCKED, PASSIVE_UNFOCUSED
    val focused = af == null || af in setOf(2, 4, 5, 6)
    return exposed && focused
}

/** The torch level, 1 to [max], for a strength in percent. */
fun torchLevel(percent: Int, max: Int): Int = ((percent * max + 50) / 100).coerceIn(1, max)
