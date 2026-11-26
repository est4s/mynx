package io.github.est4s.terminal.core

import kotlin.math.roundToInt

// Android's MotionEvent actions (masked), as a ScreenMessage.Touch carries them.
const val ACTION_DOWN = 0
const val ACTION_UP = 1
const val ACTION_MOVE = 2
const val ACTION_CANCEL = 3
const val ACTION_POINTER_DOWN = 5
const val ACTION_POINTER_UP = 6

/** What to do with an encoded frame: [send] it, and whether to [askKey] (ask the encoder for a key frame now). */
data class PaceStep(val send: Boolean, val askKey: Boolean)

/**
 * The host's flow control for one stream (work log "Next" 0a, step 3a).
 * Video goes only while the screen watches. Each frame sent waits for
 * the screen's ack ([ScreenMessage.VideoAck]); with [maxInFlight]
 * waiting, the screen (or the Wi-Fi) lags, so the frame is dropped
 * instead of queued, and as the frames after it would decode wrong,
 * every frame is dropped until the next key frame. That one is asked
 * for once the acks have drained the pipe (sooner it would only wait
 * too), and again after [keyRetryMs] if it doesn't come. Starting to
 * watch, and the screen asking ([keyWanted]), wait for one likewise.
 * The codec's config always goes: it's small, and the decoder needs it.
 * Any thread; each call is synchronized.
 */
class VideoPacer(
    private val maxInFlight: Int = 6,
    private val keyRetryMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sent = ArrayDeque<Long>()
    private var watching = false
    private var waitingKey = false
    // When the key frame being waited for was asked for; null: not yet.
    private var askedAt: Long? = null

    val inFlight: Int @Synchronized get() = sent.size

    /** The screen starts ([on]) or stops watching; true: ask the encoder for a key frame now. */
    @Synchronized
    fun watch(on: Boolean): Boolean {
        sent.clear()
        watching = on
        if (!on) return false
        waitingKey = true
        askedAt = now()
        return true
    }

    /** The screen asked for a key frame; true: ask the encoder now. */
    @Synchronized
    fun keyWanted(): Boolean {
        if (!watching) return false
        waitingKey = true
        askedAt = null
        return askNow()
    }

    /** An encoded frame with [flags] ([VIDEO_KEY], [VIDEO_CONFIG]) and its time [pts]. */
    @Synchronized
    fun frame(flags: Int, pts: Long): PaceStep {
        if (!watching) return PaceStep(send = false, askKey = false)
        if (flags and VIDEO_CONFIG != 0) return PaceStep(send = true, askKey = false)
        val room = sent.size < maxInFlight
        if (waitingKey) {
            if (flags and VIDEO_KEY != 0 && room) {
                waitingKey = false
                askedAt = null
                sent.addLast(pts)
                return PaceStep(send = true, askKey = false)
            }
            return PaceStep(send = false, askKey = askNow())
        }
        if (!room) {
            waitingKey = true
            askedAt = null
            return PaceStep(send = false, askKey = false)
        }
        sent.addLast(pts)
        return PaceStep(send = true, askKey = false)
    }

    /** The screen got the frame at [pts], and every one before it. */
    @Synchronized
    fun acked(pts: Long): PaceStep {
        while (sent.isNotEmpty() && sent.first() <= pts) sent.removeFirst()
        return PaceStep(send = false, askKey = waitingKey && askNow())
    }

    private fun askNow(): Boolean {
        if (sent.size >= maxInFlight) return false
        val asked = askedAt
        if (asked != null && now() - asked < keyRetryMs) return false
        askedAt = now()
        return true
    }
}

/** Where a video sits in a view: from ([left], [top]), each video pixel [scale] view pixels. */
data class StreamFit(val left: Float, val top: Float, val scale: Float) {
    /** The video pixel under the view's ([x], [y]). */
    fun toVideo(x: Float, y: Float): Pair<Float, Float> = Pair((x - left) / scale, (y - top) / scale)

    /** Whether the view's ([x], [y]) is on a [width] x [height] video. */
    fun inside(x: Float, y: Float, width: Int, height: Int): Boolean {
        val (vx, vy) = toVideo(x, y)
        return vx >= 0f && vy >= 0f && vx <= width && vy <= height
    }
}

/** A [videoW] x [videoH] video in a [viewW] x [viewH] view: as big as fits, centred; null if either is empty. */
fun streamFit(viewW: Int, viewH: Int, videoW: Int, videoH: Int): StreamFit? {
    if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return null
    val scale = minOf(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
    return StreamFit((viewW - videoW * scale) / 2f, (viewH - videoH * scale) / 2f, scale)
}

/**
 * The sizes to try for a [width] x [height] screen's encoder, best
 * first: the size itself (even), then in multiples of 16 (what every
 * H.264 encoder takes), then smaller by quarters, keeping the shape,
 * down to 160 pixels wide.
 */
fun encoderSizes(width: Int, height: Int): List<Pair<Int, Int>> {
    val sizes = LinkedHashSet<Pair<Int, Int>>()
    sizes += Pair(width and 1.inv(), height and 1.inv())
    var scale = 1.0
    while (true) {
        val w = minOf((width * scale / 16).roundToInt() * 16, width / 16 * 16)
        val h = minOf((height * scale / 16).roundToInt() * 16, height / 16 * 16)
        if (w < 160 || h < 16) break
        sizes += Pair(w, h)
        scale *= 0.75
    }
    return sizes.toList()
}

/**
 * The screen's side of touches: a gesture that starts on the video goes
 * to the host in the video's pixels (fingers off its edge held at the
 * edge), one that starts on the bars around it doesn't go at all.
 */
class TouchTrack {
    private var ignoring = false

    /** The [action] (masked; [index] its finger) with [points] in the view's pixels, as a touch of [stream]; null: not sent. */
    fun map(stream: Int, action: Int, index: Int, points: List<TouchPoint>, fit: StreamFit, width: Int, height: Int): ScreenMessage.Touch? {
        if (action == ACTION_DOWN) {
            val first = points.firstOrNull() ?: return null
            ignoring = !fit.inside(first.x, first.y, width, height)
        }
        if (ignoring) return null
        val mapped = points.take(MAX_TOUCH_POINTS).map { p ->
            val (x, y) = fit.toVideo(p.x, p.y)
            TouchPoint(p.id, x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))
        }
        return ScreenMessage.Touch(stream, action, index.coerceIn(0, MAX_TOUCH_POINTS - 1), mapped)
    }
}
