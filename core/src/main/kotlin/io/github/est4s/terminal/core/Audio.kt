package io.github.est4s.terminal.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MAX_RECORD_SECONDS = 86400
const val MIN_RECORD_RATE = 8000
const val MAX_RECORD_RATE = 48000

/** What `pc26 audio record` can save, picked by the file's [extensions]; [rate] is the default. */
enum class AudioFormat(val extensions: List<String>, val rate: Int) {
    M4A(listOf("m4a"), 44100),
    AAC(listOf("aac"), 44100),
    OGG(listOf("ogg", "opus"), 48000),
    WAV(listOf("wav"), 44100),
}

/** A file to play: [file] on the host, [path] its name in Debian. */
data class PlayQuery(val file: File, val path: String)

/**
 * A recording to save in [target] ([path] in Debian), for [seconds] or
 * until stopped. The app records into [partial], next to it, so the
 * target only changes once the recording is complete.
 */
data class RecordQuery(val target: File, val path: String, val format: AudioFormat, val rate: Int, val seconds: Int?) {
    val partial: File get() = File(target.parentFile, ".${target.name}.part")
}

/** Where the app reports how playing went; any thread may use it. */
interface PlayReport {
    /** The file played to its end. */
    fun done(seconds: Double)
    fun fail(message: String)
    /** `pc26` stopped waiting (Ctrl+C), or has gone: stop playing. */
    fun onCancel(block: () -> Unit)
}

/** Where the app reports a recording; any thread may use it. */
interface RecordReport {
    /** The microphone is on: `pc26` says it's recording. */
    fun started()
    /** [RecordQuery.partial] holds the whole recording: it becomes the target. */
    fun recorded(seconds: Double)
    fun fail(message: String)
    /**
     * `pc26` stopped (Ctrl+C), or has gone: [block] must stop recording
     * and call [recorded] or [fail] before it returns.
     */
    fun onStop(block: () -> Unit)
}

/**
 * The `audio-play` and `audio-record` requests, for [Pc26Requests]'s
 * `later`; both run until they end or `pc26` stops them. Lines: the
 * Debian path, then for `audio-record` `seconds=N` and `rate=HZ`. [play]
 * and [record] start and must not block.
 */
fun audioRequests(
    home: File,
    play: (PlayQuery, PlayReport) -> Unit,
    record: (RecordQuery, RecordReport) -> Unit,
): Map<String, Later> {
    val rootfs = home.parentFile.path
    val settings = File(home, "$CONFIG_DIR/settings.conf")
    val playing = Later(null) { args, reply ->
        val query = parsePlayQuery(args, rootfs).getOrElse { return@Later reply.refuse(it.message!!) }
        play(query, object : PlayReport {
            override fun done(seconds: Double) = reply.ok("file" to json(query.path), "seconds" to number(seconds, 1))
            override fun fail(message: String) = reply.refuse(message)
            override fun onCancel(block: () -> Unit) = reply.onCancel(block)
        })
    }
    val recording = Later(null) { args, reply ->
        val query = parseRecordQuery(args, rootfs).getOrElse { return@Later reply.refuse(it.message!!) }
        if (!loadSettings(settings).settings.androidMicrophone) {
            return@Later reply.refuse("the microphone is off (pc26 set android-microphone on)")
        }
        record(query, object : RecordReport {
            override fun started() = reply.line(obj("recording" to "true"))

            override fun recorded(seconds: Double) {
                val bytes = query.partial.length()
                if (query.partial.isFile && query.partial.renameTo(query.target)) {
                    reply.ok("file" to json(query.path), "bytes" to bytes.toString(), "seconds" to number(seconds, 1))
                } else {
                    query.partial.delete()
                    reply.refuse("couldn't save the recording to ${query.path}")
                }
            }

            override fun fail(message: String) {
                query.partial.delete()
                reply.refuse(message)
            }

            override fun onStop(block: () -> Unit) = reply.onCancel(block)
        })
    }
    return mapOf("audio-play" to playing, "audio-record" to recording)
}

private fun parsePlayQuery(args: List<String>, rootfs: String): Result<PlayQuery> = runCatching {
    val path = args.getOrNull(0)?.trim().orEmpty()
    if (path.isEmpty()) throw IllegalArgumentException("audio play needs a file to play")
    val file = hostPath(path, rootfs)?.let(::File) ?: throw IllegalArgumentException("the path must start with /: $path")
    when {
        file.isDirectory -> throw IllegalArgumentException("$path is a folder")
        !file.isFile -> throw IllegalArgumentException("no such file: $path")
    }
    PlayQuery(file, path)
}

private fun parseRecordQuery(args: List<String>, rootfs: String): Result<RecordQuery> = runCatching {
    val path = args.getOrNull(0)?.trim().orEmpty().trimEnd('/')
    if (path.isEmpty()) throw IllegalArgumentException("audio record needs a file to save to")
    val target = targetFile(path, rootfs)
    val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    val format = AudioFormat.entries.firstOrNull { extension in it.extensions }
        ?: throw IllegalArgumentException(
            "the file name must end in " +
                AudioFormat.entries.flatMap { it.extensions }.map { ".$it" }.let { it.dropLast(1).joinToString() + " or " + it.last() },
        )
    var query = RecordQuery(target, path, format, format.rate, null)
    for (option in args.drop(1).map { it.trim() }.filter { it.isNotEmpty() }) {
        val value = option.substringAfter('=', "").toIntOrNull()
        query = when {
            option.startsWith("seconds=") -> query.copy(
                seconds = value?.takeIf { it in 1..MAX_RECORD_SECONDS }
                    ?: throw IllegalArgumentException("the length is 1 to $MAX_RECORD_SECONDS seconds"),
            )
            option.startsWith("rate=") -> query.copy(
                rate = value?.takeIf { it in MIN_RECORD_RATE..MAX_RECORD_RATE }
                    ?: throw IllegalArgumentException("the rate is $MIN_RECORD_RATE to $MAX_RECORD_RATE Hz"),
            )
            else -> throw IllegalArgumentException("unknown record option '$option'")
        }
    }
    query
}

/** The host file for a Debian [path] to save to, in a folder that exists. */
internal fun targetFile(path: String, rootfs: String): File {
    val target = hostPath(path, rootfs)?.let(::File) ?: throw IllegalArgumentException("the path must start with /: $path")
    when {
        target.isDirectory -> throw IllegalArgumentException("$path is a folder")
        target.parentFile?.isDirectory != true ->
            throw IllegalArgumentException("no such folder: ${path.substringBeforeLast('/').ifEmpty { "/" }}")
    }
    return target
}

/** The 44-byte header of a WAV file holding [dataBytes] of 16-bit PCM. */
fun wavHeader(rate: Int, channels: Int, dataBytes: Int): ByteArray {
    val frame = channels * 2
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        .putInt(rate).putInt(rate * frame).putShort(frame.toShort()).putShort(16)
        .put("data".toByteArray()).putInt(dataBytes)
        .array()
}
