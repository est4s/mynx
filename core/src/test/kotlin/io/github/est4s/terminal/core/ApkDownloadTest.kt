package io.github.est4s.terminal.core

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApkDownloadTest {
    private val dir = createTempDirectory("apk").toFile()
    private val dest = File(dir, "mynx-0.2.0.apk")
    private val part = File(dir, "mynx-0.2.0.apk.part")
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val servers = mutableListOf<TestServer>()

    private fun serve(handle: (HttpExchange) -> Unit) = TestServer(handle).also { servers += it }

    private fun serveApk() = serve { it.reply(200, apk, mapOf("Content-Type" to "application/octet-stream")) }

    @AfterTest
    fun cleanup() {
        servers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun download(url: String, size: Long? = apk.size.toLong(), sha256: String? = sha, cancelled: () -> Boolean = { false }, progress: (Long, Long?) -> Unit = { _, _ -> }) =
        downloadApk(url, dest, appVersion = "0.1.0", size = size, sha256 = sha256, timeoutMs = 2000, cancelled = cancelled, progress = progress)

    private fun assertNothingLeft() {
        assertFalse(dest.exists(), "dest left behind")
        assertFalse(part.exists(), "partial file left behind")
    }

    @Test
    fun `downloads to the file, reporting progress`() {
        var agent: String? = null
        val server = serve {
            agent = it.requestHeaders.getFirst("User-Agent")
            it.reply(200, apk)
        }
        val seen = mutableListOf<Pair<Long, Long?>>()
        val result = download("${server.base}/mynx-0.2.0.apk") { bytes, total -> seen += bytes to total }
        assertEquals(ApkDownload.Done(dest), result)
        assertTrue(apk.contentEquals(dest.readBytes()))
        assertFalse(part.exists())
        assertEquals("Mynx/0.1.0", agent)
        assertEquals(0L to apk.size.toLong(), seen.first())
        assertEquals(apk.size.toLong() to apk.size.toLong(), seen.last())
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
    }

    @Test
    fun `replaces an older file`() {
        dest.writeText("old")
        assertIs<ApkDownload.Done>(download(serveApk().base))
        assertTrue(apk.contentEquals(dest.readBytes()))
    }

    @Test
    fun `follows redirects to another host, relative ones too`() {
        val files = serve { if (it.requestURI.path == "/blob") it.reply(200, apk) else it.reply(404, ByteArray(0)) }
        val redirects = serve {
            if (it.requestURI.path == "/second") it.reply(307, ByteArray(0), mapOf("Location" to "${files.base}/blob"))
            else it.reply(302, ByteArray(0), mapOf("Location" to "/second"))
        }
        assertIs<ApkDownload.Done>(download("${redirects.base}/start"))
        assertTrue(apk.contentEquals(dest.readBytes()))
    }

    @Test
    fun `a redirect to a missing file fails`() {
        val files = serve { it.reply(404, ByteArray(0)) }
        val redirect = serve { it.reply(301, ByteArray(0), mapOf("Location" to "${files.base}/gone")) }
        assertIs<ApkDownload.Failed>(download(redirect.base))
        assertNothingLeft()
    }

    @Test
    fun `too many redirects fail`() {
        val loop = serve { it.reply(302, ByteArray(0), mapOf("Location" to "/again")) }
        val result = download(loop.base)
        assertTrue(assertIs<ApkDownload.Failed>(result).message.contains("redirect"), result.toString())
        assertNothingLeft()
    }

    @Test
    fun `HTTP errors fail`() {
        val server = serve { it.reply(404, "Not Found".toByteArray()) }
        val result = download(server.base)
        assertTrue(assertIs<ApkDownload.Failed>(result).message.contains("404"), result.toString())
        assertNothingLeft()
    }

    @Test
    fun `a wrong size fails`() {
        assertIs<ApkDownload.Failed>(download(serveApk().base, size = apk.size + 1L))
        assertNothingLeft()
        assertIs<ApkDownload.Failed>(download(serveApk().base, size = apk.size - 1L))
        assertNothingLeft()
    }

    @Test
    fun `a wrong sha256 fails`() {
        val result = download(serveApk().base, sha256 = "0".repeat(64))
        assertTrue(assertIs<ApkDownload.Failed>(result).message.contains("sha256"), result.toString())
        assertNothingLeft()
    }

    @Test
    fun `size and digest are optional`() {
        val seen = mutableListOf<Long?>()
        assertIs<ApkDownload.Done>(download(serveApk().base, size = null, sha256 = null) { _, total -> seen += total })
        assertEquals(apk.size.toLong(), seen.last())
    }

    @Test
    fun `a connection cut short fails`() {
        val server = serve {
            it.sendResponseHeaders(200, apk.size.toLong())
            it.responseBody.write(apk, 0, 1000)
            it.responseBody.flush()
            it.close()
        }
        assertIs<ApkDownload.Failed>(download(server.base, size = null, sha256 = null))
        assertNothingLeft()
    }

    @Test
    fun `cancelling stops and deletes the partial file`() {
        val server = serve {
            it.sendResponseHeaders(200, apk.size.toLong())
            it.responseBody.use { out ->
                for (start in apk.indices step 10_000) {
                    out.write(apk, start, minOf(10_000, apk.size - start))
                    out.flush()
                    Thread.sleep(20)
                }
            }
        }
        var stop = false
        var last = 0L
        val result = download(server.base, cancelled = { stop }) { bytes, _ ->
            last = bytes
            if (bytes > 0) stop = true
        }
        assertEquals(ApkDownload.Cancelled, result)
        assertTrue(last < apk.size)
        assertNothingLeft()
    }

    @Test
    fun `no server fails`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertIs<ApkDownload.Failed>(download("http://127.0.0.1:$port/x"))
        assertNothingLeft()
    }
}
