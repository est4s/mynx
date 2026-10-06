package io.github.est4s.terminal.core

import java.io.File

/** The pipe the app writes the microphone into for the server: s16le, [SOUND_RATE] Hz, mono. */
const val SOUND_IN = "$SOUND_DIR/in"
/** Who records, written by `sound-watch` on every change. */
const val SOUND_INPUTS_NAME = "inputs"
const val SOUND_INPUTS = "$SOUND_DIR/$SOUND_INPUTS_NAME"
/** The server's source the app feeds. */
const val MIC_SOURCE = "mic"
/** 20 ms of microphone: small writes keep the pipe even. */
const val MIC_CHUNK = SOUND_RATE / 50 * 2

/**
 * The programs recording from the microphone, from `sound-watch`'s
 * [list]: Pulse's sources, then its source outputs. A paused stream, or
 * one recording what plays, doesn't need the microphone. Names come
 * from [proc] by pid (`arecord` is `aplay` to Pulse), else from Pulse.
 */
fun micUsers(list: String, proc: File = File("/proc")): List<String> {
    val (sources, outputs) = list.split("\n\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val mic = sources.lines().map { it.split('\t') }.firstOrNull { it.getOrNull(1) == MIC_SOURCE }?.get(0)
        ?: return emptyList()
    return outputs.split(Regex("(?m)^(?=Source Output #)")).mapNotNull { block ->
        fun field(name: String) = Regex("(?m)^\\s*${Regex.escape(name)}(?::| =) \"?([^\"\n]*)\"?$")
            .find(block)?.groupValues?.get(1)
        if (field("Source") != mic || field("Corked") != "no") return@mapNotNull null
        val pid = field("application.process.id")
        val comm = pid?.let { runCatching { File(proc, "$it/comm").readText().trim() }.getOrNull() }
        comm?.takeIf { it.isNotEmpty() } ?: field("application.process.binary") ?: field("application.name") ?: "a program"
    }.distinct()
}

/** What the app does with the phone's microphone. */
sealed interface Mic {
    data object Off : Mic
    data class On(val users: List<String>) : Mic
    /** Programs record, but get zeros, because of [why]. */
    data class Silent(val users: List<String>, val why: String) : Mic
}

/**
 * The microphone is on only while [users] record. It stays off if the
 * `android-microphone` setting doesn't [allow] it, Android hasn't
 * [permitted] it, or the app [canStart] it no more (Android only lets the
 * app in use start the microphone).
 */
fun micState(users: List<String>, allowed: Boolean, permitted: Boolean, canStart: Boolean): Mic = when {
    users.isEmpty() -> Mic.Off
    !allowed -> Mic.Silent(users, "android-microphone is off")
    !permitted -> Mic.Silent(users, "the microphone isn't allowed: open the app to allow it")
    !canStart -> Mic.Silent(users, "open the app to start it (Android only lets the app in use start the microphone)")
    else -> Mic.On(users)
}

/** For the app's notification: who is listening, or who would be. */
fun micNotice(mic: Mic): String? = when (mic) {
    Mic.Off -> null
    is Mic.On -> "Microphone: " + mic.users.joinToString(", ")
    is Mic.Silent -> "Microphone blocked for ${mic.users.joinToString(", ")}: ${mic.why}"
}

/**
 * Zeros in [MIC_CHUNK]s at the microphone's pace, for programs the app
 * can't record for: with nothing written they'd wait forever.
 */
class Silence(private val now: () -> Long, private val sleep: (Long) -> Unit = Thread::sleep) {
    private var next: Long? = null

    fun read(buffer: ByteArray): Int {
        val due = next ?: now()
        val wait = due - now()
        if (wait > 0) sleep(wait)
        // Behind (the writer was blocked): catch up from now, no burst.
        next = maxOf(due, now()) + 20
        buffer.fill(0, 0, MIC_CHUNK)
        return MIC_CHUNK
    }
}

/** The phone's microphone as [MicInput] reads it: a read's result is bytes, or an error below 1. */
interface MicRecord {
    fun read(buffer: ByteArray, length: Int): Int
    fun close()
}

/**
 * What the feed writes while programs record: the microphone [open]s,
 * while it delivers, else [silence], so they never wait on nothing. A
 * microphone that fails or won't open is tried again after [retryMs].
 */
class MicInput(
    private val open: () -> MicRecord?,
    private val silence: Silence,
    private val now: () -> Long,
    private val retryMs: Long = 2000,
) {
    private var record: MicRecord? = null
    private var retryAt: Long? = null

    /** Always some bytes, at most [MIC_CHUNK]. */
    fun read(buffer: ByteArray): Int {
        if (record == null && now() >= (retryAt ?: now())) {
            record = runCatching { open() }.getOrNull()
            if (record == null) retryAt = now() + retryMs
        }
        record?.let {
            val read = runCatching { it.read(buffer, MIC_CHUNK) }.getOrDefault(-1)
            if (read > 0) return read
            close()
            retryAt = now() + retryMs
        }
        return silence.read(buffer)
    }

    fun close() {
        record?.let { runCatching { it.close() } }
        record = null
    }
}
