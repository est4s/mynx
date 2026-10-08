package io.github.est4s.terminal.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A web server on a free local port; [handle] answers each request. */
internal class TestServer(private val handle: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (_: java.io.IOException) {
                // the client hung up; nothing to answer
            } finally {
                exchange.close()
            }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

internal fun HttpExchange.reply(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
    headers.forEach { (k, v) -> responseHeaders.add(k, v) }
    sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.use { it.write(body) }
}

class ReleaseFetchTest {
    private val servers = mutableListOf<TestServer>()

    private fun serve(handle: (HttpExchange) -> Unit) = TestServer(handle).also { servers += it }

    @AfterTest
    fun cleanup() = servers.forEach { it.close() }

    @Test
    fun `finds the latest release, asking as the app`() {
        var accept: String? = null
        var agent: String? = null
        var path: String? = null
        val server = serve {
            accept = it.requestHeaders.getFirst("Accept")
            agent = it.requestHeaders.getFirst("User-Agent")
            path = it.requestURI.path
            it.reply(200, releaseJson().toByteArray(), mapOf("Content-Type" to "application/json; charset=utf-8"))
        }
        val check = fetchLatestRelease("${server.base}/repos/est4s/mynx/releases/latest", appVersion = "0.1.0")
        assertIs<ReleaseCheck.Found>(check)
        assertEquals(Version(0, 2, 0), check.release.version)
        assertEquals("application/vnd.github+json", accept)
        assertEquals("Mynx/0.1.0", agent)
        assertEquals("/repos/est4s/mynx/releases/latest", path)
    }

    @Test
    fun `reads the answer as UTF-8`() {
        val server = serve { it.reply(200, releaseJson(body = "\"Grüße 😀\"").toByteArray(Charsets.UTF_8)) }
        val check = fetchLatestRelease(server.base, appVersion = "0.1.0")
        assertEquals("Grüße 😀", assertIs<ReleaseCheck.Found>(check).release.notes)
    }

    @Test
    fun `404 means no release yet`() {
        val server = serve { it.reply(404, """{"message":"Not Found"}""".toByteArray()) }
        assertEquals(ReleaseCheck.NoRelease, fetchLatestRelease(server.base, appVersion = "0.1.0"))
    }

    @Test
    fun `other statuses are failures`() {
        val server = serve { it.reply(403, """{"message":"API rate limit exceeded"}""".toByteArray()) }
        val check = fetchLatestRelease(server.base, appVersion = "0.1.0")
        assertTrue(assertIs<ReleaseCheck.Failed>(check).message.contains("403"), check.toString())
    }

    @Test
    fun `no server is a failure`() {
        val port = ServerSocket(0).use { it.localPort }
        assertIs<ReleaseCheck.Failed>(fetchLatestRelease("http://127.0.0.1:$port/", appVersion = "0.1.0"))
    }

    @Test
    fun `a server that never answers times out`() {
        val server = serve { Thread.sleep(3000) }
        val start = System.nanoTime()
        assertIs<ReleaseCheck.Failed>(fetchLatestRelease(server.base, appVersion = "0.1.0", timeoutMs = 300))
        assertTrue(System.nanoTime() - start < 2_500_000_000L)
    }

    @Test
    fun `broken or unusable answers are bad data`() {
        for (body in listOf("<html>oops</html>", releaseJson(assets = ""))) {
            val server = serve { it.reply(200, body.toByteArray()) }
            assertIs<ReleaseCheck.BadData>(fetchLatestRelease(server.base, appVersion = "0.1.0"), body)
        }
    }

    @Test
    fun `a huge answer is bad data, not read whole`() {
        val server = serve { it.reply(200, ByteArray(MAX_RELEASE_JSON + 1) { ' '.code.toByte() }) }
        assertIs<ReleaseCheck.BadData>(fetchLatestRelease(server.base, appVersion = "0.1.0"))
    }
}
