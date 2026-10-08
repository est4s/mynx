package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A release answer is a few KB; anything this big isn't one. */
internal const val MAX_RELEASE_JSON = 1 shl 20

/** What asking GitHub for the latest release found. */
sealed interface ReleaseCheck {
    data class Found(val release: Release) : ReleaseCheck

    /** GitHub answered 404: nothing released yet. */
    data object NoRelease : ReleaseCheck

    /** No answer, or an HTTP error (rate limits, outages): try again later. */
    data class Failed(val message: String) : ReleaseCheck

    /** An answer that isn't a release the app can update to. */
    data class BadData(val message: String) : ReleaseCheck
}

/** Asks [url] (GitHub's latest-release API) what the latest release is. [appVersion] goes in the User-Agent. */
fun fetchLatestRelease(url: String = LATEST_RELEASE_URL, appVersion: String, timeoutMs: Int = 15_000): ReleaseCheck {
    val connection = try {
        openHttp(url, appVersion, timeoutMs).apply { setRequestProperty("Accept", "application/vnd.github+json") }
    } catch (e: IOException) {
        return ReleaseCheck.Failed(ioFailure(e))
    }
    try {
        val status = connection.responseCode
        if (status == 404) return ReleaseCheck.NoRelease
        if (status != 200) return ReleaseCheck.Failed("HTTP $status from $url")
        val bytes = connection.inputStream.use { readAtMost(it, MAX_RELEASE_JSON) }
            ?: return ReleaseCheck.BadData("answer over ${MAX_RELEASE_JSON / 1024} KB")
        return try {
            ReleaseCheck.Found(parseRelease(bytes.toString(Charsets.UTF_8)))
        } catch (e: BadRelease) {
            ReleaseCheck.BadData(e.message ?: "bad release")
        }
    } catch (e: IOException) {
        return ReleaseCheck.Failed(ioFailure(e))
    } finally {
        connection.disconnect()
    }
}

/** How a [downloadApk] ended. */
sealed interface ApkDownload {
    data class Done(val file: File) : ApkDownload

    data object Cancelled : ApkDownload

    data class Failed(val message: String) : ApkDownload
}

private const val MAX_REDIRECTS = 5

/**
 * Downloads [url] to [dest], through `dest.part` so a broken download
 * never looks finished, and checks [size] and [sha256] (hex) when
 * given. [progress] gets the bytes so far and the total, if known,
 * after every chunk; [cancelled] is asked between chunks. On failure or
 * cancelling the partial file is deleted and [dest] is left as it was.
 */
fun downloadApk(
    url: String,
    dest: File,
    appVersion: String,
    size: Long? = null,
    sha256: String? = null,
    timeoutMs: Int = 30_000,
    cancelled: () -> Boolean = { false },
    progress: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
): ApkDownload {
    val part = File(dest.parentFile, dest.name + ".part")
    var done = false
    try {
        val connection = try {
            follow(url, appVersion, timeoutMs)
        } catch (e: Redirects) {
            return ApkDownload.Failed(e.message!!)
        }
        try {
            val status = connection.responseCode
            if (status != 200) return ApkDownload.Failed("HTTP $status from ${connection.url}")
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            if (size != null && length != null && length != size) return ApkDownload.Failed("the server sends $length bytes, not $size")
            val total = size ?: length
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            progress(0, total)
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) return ApkDownload.Cancelled
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        bytes += n
                        if (total != null && bytes > total) return ApkDownload.Failed("more than the $total bytes expected")
                        progress(bytes, total)
                    }
                }
            }
            if (cancelled()) return ApkDownload.Cancelled
            if (total != null && bytes != total) return ApkDownload.Failed("got $bytes bytes of $total")
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != null && got != sha256.lowercase()) return ApkDownload.Failed("sha256 doesn't match: got $got, expected $sha256")
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            done = true
            return ApkDownload.Done(dest)
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        return ApkDownload.Failed(ioFailure(e))
    } finally {
        if (!done) part.delete()
    }
}

private class Redirects(message: String) : Exception(message)

// HttpURLConnection won't follow a redirect that changes protocol, so every hop is followed here.
private fun follow(start: String, appVersion: String, timeoutMs: Int): HttpURLConnection {
    var url = start
    repeat(MAX_REDIRECTS + 1) {
        val connection = openHttp(url, appVersion, timeoutMs)
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/octet-stream")
        val status = connection.responseCode
        if (status !in REDIRECTS) return connection
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (location == null) throw Redirects("HTTP $status from $url with nowhere to go")
        val next = URL(URL(url), location)
        if (next.protocol != "https" && next.protocol != "http") throw Redirects("redirect to ${next.protocol}")
        url = next.toString()
    }
    throw Redirects("more than $MAX_REDIRECTS redirects from $start")
}

private val REDIRECTS = setOf(301, 302, 303, 307, 308)

internal fun openHttp(url: String, appVersion: String, timeoutMs: Int): HttpURLConnection {
    val connection = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("not a web address: $url")
    connection.connectTimeout = timeoutMs
    connection.readTimeout = timeoutMs
    connection.setRequestProperty("User-Agent", "Mynx/$appVersion")
    return connection
}

internal fun ioFailure(e: IOException) = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName

private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}
