package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [WebAppHost] the tests drive by hand: they see what was asked and answer the port probes. */
class FakeWebAppHost : WebAppHost {
    override var onScreen = true
    /** The apps whose screen exists. */
    val open = mutableSetOf<String>()
    var openFails: String? = null
    val opened = mutableListOf<String>()
    val reloaded = mutableListOf<String>()
    /** The ports that answer a probe. */
    val answering = mutableSetOf<Int>()
    val probes = mutableListOf<Int>()
    val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    override fun open(app: WebApp): String? {
        openFails?.let { return it }
        opened += app.name
        open += app.name
        return null
    }

    override fun isOpen(name: String) = name in open

    override fun reload(name: String): Boolean {
        if (name !in open) return false
        reloaded += name
        return true
    }

    override fun portAnswers(port: Int, done: (Boolean) -> Unit) {
        probes += port
        done(port in answering)
    }

    override fun schedule(delayMs: Long, block: () -> Unit) {
        scheduled += delayMs to block
    }

    /** Runs what was scheduled, in order, until nothing is left or [limit] runs; [tick] passes each delay to the clock. */
    fun runScheduled(limit: Int = 1000, tick: (Long) -> Unit = {}) {
        var runs = 0
        while (scheduled.isNotEmpty() && runs++ < limit) {
            val (ms, block) = scheduled.removeAt(0)
            tick(ms)
            block()
        }
    }
}

/** A [WebAppServices] with services the tests set up; it records starts and stops. */
class FakeWebAppServices : WebAppServices {
    val services = mutableMapOf<String, WebAppServiceStatus>()
    val events = mutableListOf<String>()
    var startFails: String? = null

    override fun status(name: String) = services[name]

    override fun start(name: String): String? {
        startFails?.let { return it }
        val service = services[name] ?: return "no service named $name"
        events += "start $name"
        services[name] = service.copy(state = ServiceState.RUNNING)
        return null
    }

    override fun stop(name: String): String? {
        val service = services[name] ?: return "no service named $name"
        events += "stop $name"
        services[name] = service.copy(state = ServiceState.STOPPED)
        return null
    }
}

/** `tests/fixtures/` in the repo (Gradle runs the tests in `core/`). */
fun fixturesDir(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val fixtures = File(dir, "tests/fixtures")
        if (File(fixtures, "apps").isDirectory) return fixtures
        dir = dir.parentFile
    }
    error("no tests/fixtures/apps above ${System.getProperty("user.dir")}")
}

/** Copies [from] into [to], links as links. */
fun copyTree(from: File, to: File) {
    val source = from.toPath()
    Files.walk(source).use { paths ->
        for (path in paths) {
            val target: Path = to.toPath().resolve(source.relativize(path).toString())
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target)
            } else {
                Files.copy(path, target, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }
}

class WebAppsTest {
    private val base = createTempDirectory("webapps").toFile()
    private val rootfs = File(base, "rootfs")
    /** Root's home: a copy of `tests/fixtures/`, so `~/apps` holds the fixture apps. */
    private val home = File(rootfs, "root").also { copyTree(fixturesDir(), it) }
    private val tools = File(base, "tools").apply { mkdirs() }
    private val host = FakeWebAppHost()
    private val services = FakeWebAppServices()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `the fixtures are copied with their link out of the folder`() {
        val link = File(home, "$WEB_APPS_DIR/escape/outside.txt").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("../../secret.txt", Files.readSymbolicLink(link).toString())
        assertTrue(File(home, "secret.txt").isFile)
    }
}

class WebAppRulesTest {
    @Test
    fun `app names are DNS labels`() {
        for (good in listOf("a", "sketch", "pixel-hero", "app2", "0", "a".repeat(40))) assertTrue(isWebAppName(good), good)
        for (bad in listOf("", "Bad_Name", "Sketch", "-a", "a-", "a.b", "a b", "a".repeat(41), "é")) {
            assertFalse(isWebAppName(bad), bad)
        }
    }

    @Test
    fun `a static app's origin is its own host`() {
        assertEquals("https://sketch.mynx.invalid", webAppOrigin("sketch"))
    }

    @Test
    fun `the app of a host`() {
        assertEquals("sketch", webAppNameOfHost("sketch.mynx.invalid"))
        assertEquals("sketch", webAppNameOfHost("Sketch.MYNX.invalid"))
        assertNull(webAppNameOfHost("mynx.invalid"))
        assertNull(webAppNameOfHost("a.b.mynx.invalid"))
        assertNull(webAppNameOfHost("-a.mynx.invalid"))
        assertNull(webAppNameOfHost("sketch.example.com"))
        assertNull(webAppNameOfHost("sketch.mynx.invalid.example.com"))
    }

    @Test
    fun `MIME types by ending, in any case`() {
        assertEquals("text/html", webAppMimeType("index.html"))
        assertEquals("text/html", webAppMimeType("A.HTM"))
        assertEquals("text/javascript", webAppMimeType("app.js"))
        assertEquals("text/javascript", webAppMimeType("mod.mjs"))
        assertEquals("text/css", webAppMimeType("s.css"))
        assertEquals("application/json", webAppMimeType("d.json"))
        assertEquals("application/manifest+json", webAppMimeType("m.webmanifest"))
        assertEquals("image/svg+xml", webAppMimeType("i.svg"))
        assertEquals("image/png", webAppMimeType("i.png"))
        assertEquals("image/jpeg", webAppMimeType("i.JPG"))
        assertEquals("image/webp", webAppMimeType("i.webp"))
        assertEquals("application/wasm", webAppMimeType("m.wasm"))
        assertEquals("audio/mpeg", webAppMimeType("s.mp3"))
        assertEquals("audio/ogg", webAppMimeType("s.ogg"))
        assertEquals("audio/wav", webAppMimeType("s.wav"))
        assertEquals("font/woff2", webAppMimeType("f.woff2"))
        assertEquals("application/octet-stream", webAppMimeType("x.unknown"))
        assertEquals("application/octet-stream", webAppMimeType("Makefile"))
    }

    @Test
    fun `app conf keys`() {
        val parsed = parseAppConf(
            "# comment\n\n  name =  My Notes  \nicon = icon.png\nurl = http://127.0.0.1:8765/a/b?x=1\nservice = backend\n",
        )
        assertEquals(AppConf("My Notes", "icon.png", "http://127.0.0.1:8765/a/b?x=1", "backend"), parsed.conf)
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `app conf urls`() {
        for (good in listOf("http://127.0.0.1:1024", "http://localhost:65535/", "http://localhost:8080/x/y.html", "http://127.0.0.1:8080?q")) {
            assertEquals(good, parseAppConf("url = $good\n").conf.url, good)
        }
        for (bad in listOf("https://127.0.0.1:8080/", "http://127.0.0.1/", "http://127.0.0.1:80/", "http://127.0.0.1:65536/",
            "http://example.com:8080/", "http://127.0.0.2:8080/", "http://localhost:8080/#x", "http://localhost:8080/a b", "")) {
            val parsed = parseAppConf("url = $bad\n")
            assertNull(parsed.conf.url, bad)
            assertEquals(1, parsed.problems.size, bad)
            assertTrue(parsed.problems[0].startsWith("line 1: url must be"), parsed.problems[0])
        }
    }

    @Test
    fun `app conf problems`() {
        val parsed = parseAppConf(
            "oops\nname =\ncolour = red\nname = A\nname = B\nservice = Bad_Name\nicon = icon.gif\nicon = /etc/x.png\nicon =\n",
        )
        assertEquals(AppConf(name = "B"), parsed.conf)
        assertEquals(
            listOf(
                "line 1: expected key = value",
                "line 2: name must not be empty",
                "line 3: unknown key 'colour'",
                "line 5: name is already set on line 4: the last counts",
                "line 6: ${badServiceName("Bad_Name")}",
                "line 7: icon must be a PNG, JPEG or WebP file",
                "line 8: icon must be a file in the app's folder, relative to it",
                "line 9: icon must not be empty",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `a long name is cut and reported`() {
        val parsed = parseAppConf("name = An Unusually Long App Name\n")
        assertEquals("An Unusually Long Ap", parsed.conf.name)
        assertEquals(listOf("line 1: name is longer than 20 characters: cut to 'An Unusually Long Ap'"), parsed.problems)
    }

    @Test
    fun `a service needs a url`() {
        val parsed = parseAppConf("service = backend\n")
        assertNull(parsed.conf.service)
        assertEquals(listOf("line 1: service needs a url (it's for served apps)"), parsed.problems)
    }

    @Test
    fun `the title of a page`() {
        assertEquals("Hello & Welcome", htmlTitle("<html><TITLE lang=\"en\">\n  Hello &amp;\n\t Welcome </title>"))
        assertEquals("<a> \"b\" 'c' &lt;", htmlTitle("<title>&lt;a&gt; &quot;b&quot; &#39;c&#39; &amp;lt;</title>"))
        assertEquals("Twenty Characters Ok", htmlTitle("<title>Twenty Characters Ok and more</title>"))
        assertNull(htmlTitle("<title>   </title>"))
        assertNull(htmlTitle("<title>never closed"))
        assertNull(htmlTitle("<p>no title</p>"))
        assertNull(htmlTitle("<titles>x</titles>"))
    }

    @Test
    fun `labels from folder names`() {
        assertEquals("Pixel Hero", folderLabel("pixel-hero"))
        assertEquals("Sketch", folderLabel("sketch"))
        assertEquals("App 2", folderLabel("app-2"))
        assertEquals("Aaaaaaaaaa Bbbbbbbbb", folderLabel("aaaaaaaaaa-bbbbbbbbbbbb"))
    }
}
