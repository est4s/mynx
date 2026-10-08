package io.github.est4s.terminal.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
