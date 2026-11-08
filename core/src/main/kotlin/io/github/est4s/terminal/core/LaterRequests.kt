package io.github.est4s.terminal.core

import java.io.File

/**
 * A request the app answers later (a GPS fix, a permission dialog), or a
 * stream of readings. [seconds] is how long `mynx` waits for the
 * answer; null makes it a stream, which runs until `mynx` cancels it.
 * [start] begins the work and must not block: it answers through the
 * [PendingReply], now or later. A request that changes the config files
 * names the change with [change] (for `mynx undo`); it must have written
 * them by the time it answers.
 */
class Later(
    val seconds: Int?,
    val change: ((args: List<String>) -> String)? = null,
    val start: (args: List<String>, reply: PendingReply) -> Unit,
)

/**
 * The answer to a [Later] request; any thread may use it. Next to
 * `ID.req`, the app writes `ID.wait` (the seconds to wait, or `stream`)
 * at once, appends each [line] of a stream to `ID.stream`, and puts the
 * answer in `ID.reply`; only the first answer counts. `mynx` cancels a
 * stream by writing `ID.cancel`; [onCancel] then runs, as it does when
 * `mynx` has gone (see [MynxRequests.sweep]).
 */
class PendingReply internal constructor(
    private val dir: File,
    internal val id: String,
    private val closed: (PendingReply) -> Unit,
) {
    private var done = false

    /** Whether it has been answered (or cancelled). */
    internal val answered: Boolean @Synchronized get() = done
    private var stopping = false
    private var cancelled: (() -> Unit)? = null

    /** One reading of a stream, as a JSON object. */
    @Synchronized
    fun line(json: String) {
        if (!done) File(dir, "$id.stream").appendText(json + "\n")
    }

    /** Answers `{"ok":true, ...}`; field values are JSON. */
    fun ok(vararg fields: Pair<String, String>) {
        answer(okJson(*fields))
    }

    fun refuse(message: String) {
        answer(refusal(message))
    }

    /**
     * What to do when `mynx` cancels the request: stop listening, mainly.
     * It may still answer (a recording says what it saved); else the
     * answer is a plain `{"ok":true}`.
     */
    @Synchronized
    fun onCancel(block: () -> Unit) {
        cancelled = block
    }

    internal fun answer(json: String) {
        synchronized(this) {
            if (done) return
            done = true
            writeReply(dir, id, json)
            File(dir, "$id.wait").delete()
        }
        closed(this)
    }

    // A mynx still [listening] gets an answer, so it can finish; else
    // nothing of the request is left behind.
    internal fun cancel(listening: Boolean) {
        val block = synchronized(this) {
            if (done || stopping) return
            stopping = true
            cancelled
        }
        runCatching { block?.invoke() }
        if (listening) {
            ok()
            File(dir, "$id.cancel").delete()
        } else {
            synchronized(this) { done = true }
            dir.listFiles { f -> f.name.startsWith("$id.") }.orEmpty().forEach { it.delete() }
        }
        closed(this)
    }
}

/**
 * Where a request's answer goes (the seam between [MynxRequests] and
 * its callers; work log "Next", 0, part A builds it). [PendingReply]
 * writes through one: [FileReplySink] for `mynx`'s files (today's
 * behaviour, unchanged), the bridge's own for a page. Any thread may
 * call it; [PendingReply] makes sure only the first answer counts and
 * nothing comes after it.
 * - [waiting]: once, before the handler starts: the seconds the caller
 *   waits ([Later.seconds]), or null for a stream.
 * - [line]: one reading of a stream, a JSON object.
 * - [answer]: the answer, a JSON object with `ok`; the request is over.
 * - [discard]: the request ended with nobody listening (`mynx` gone, a
 *   page closed): leave nothing behind, send nothing.
 */
interface ReplySink {
    fun waiting(seconds: Int?)
    fun line(json: String)
    fun answer(json: String)
    fun discard()
}

/**
 * `mynx`'s files in [dir] for request [id]: [waiting] writes `ID.wait`
 * (renamed into place: the seconds, or `stream`), [line] appends to
 * `ID.stream`, [answer] writes `ID.reply` (renamed into place) and
 * removes `ID.wait` (and `ID.cancel`), [discard] removes every `ID.*`.
 */
class FileReplySink(private val dir: File, private val id: String) : ReplySink {
    override fun waiting(seconds: Int?): Unit = TODO("part A")
    override fun line(json: String): Unit = TODO("part A")
    override fun answer(json: String): Unit = TODO("part A")
    override fun discard(): Unit = TODO("part A")
}

/**
 * A request run in-process by [MynxRequests.call]. [seconds] is its
 * [Later.seconds] (null: a stream; a plain request is answered before
 * `call` returns, with [done] true). [stop]: the caller stopped it, as
 * `mynx`'s `ID.cancel` (Ctrl+C): `onCancel` runs and an answer always
 * follows (the handler's, else `{"ok":true}`). [cancel]: nobody listens
 * any more, as `mynx` gone: `onCancel` runs, then the sink's
 * [ReplySink.discard]. Both are harmless once it's done, or twice.
 */
interface Call {
    val seconds: Int?
    val done: Boolean
    fun stop()
    fun cancel()
}

private val PID_OF_ID = Regex("^(\\d+)-")

/** The process number `mynx` puts at the start of a request's id, if any. */
internal fun pidOfRequest(id: String): Int? = PID_OF_ID.find(id)?.groupValues?.get(1)?.toIntOrNull()

internal fun writeReply(dir: File, id: String, json: String) {
    val tmp = File(dir, "$id.reply.tmp")
    tmp.writeText(json)
    tmp.renameTo(File(dir, "$id.reply"))
}

private fun okJson(vararg fields: Pair<String, String>) = ok(*fields)

internal fun refusal(message: String) = """{"ok":false,"error":${json(message)}}"""
