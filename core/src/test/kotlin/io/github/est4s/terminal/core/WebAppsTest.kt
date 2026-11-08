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

    private val apps = WebApps(home, rootfs.path, tools)

    private fun write(path: String, text: String = "x\n") =
        File(home, path).apply { parentFile.mkdirs() }.also { it.writeText(text) }

    private fun link(path: String, target: String) {
        val file = File(home, path).apply { parentFile.mkdirs() }
        Files.createSymbolicLink(file.toPath(), Path.of(target))
    }

    private fun writeServices(text: String) = write("$CONFIG_DIR/$SERVICES_CONF", text)

    private fun app(name: String) = apps.find(name)!!

    private fun get(app: String, path: String, method: String = "GET") = apps.respond(app(app), method, path)

    @Test
    fun `lists the fixture apps by name, with their labels`() {
        val list = apps.list()
        assertEquals(listOf("escape", "hello", "notes", "served"), list.map { it.name })
        assertEquals(listOf("Escape", "Hello & Welcome", "My Notes", "Served"), list.map { it.label })
    }

    @Test
    fun `a static app`() {
        assertEquals(
            WebApp(
                name = "hello", label = "Hello & Welcome", path = "/root/apps/hello", root = "/root/apps/hello",
                kind = WebAppKind.STATIC, origin = "https://hello.mynx.invalid", url = "https://hello.mynx.invalid/",
                port = null, service = null, icon = null, problems = emptyList(),
            ),
            app("hello"),
        )
        assertEquals("/root/apps/notes/icon.png", app("notes").icon)
    }

    @Test
    fun `a served app`() {
        writeServices("[served-backend]\ncommand = python3 -m http.server 8765\n")
        val served = app("served")
        assertEquals(WebAppKind.SERVED, served.kind)
        assertEquals("http://127.0.0.1:8765", served.origin)
        assertEquals("http://127.0.0.1:8765/", served.url)
        assertEquals(8765, served.port)
        assertEquals("served-backend", served.service)
        assertEquals(emptyList(), served.problems)
    }

    @Test
    fun `a served app's service must be in services conf`() {
        assertEquals(
            listOf("service: no service named served-backend in ~/.config/mynx/services.conf"),
            app("served").problems,
        )
    }

    @Test
    fun `a served app's origin keeps localhost and drops the path and query`() {
        write("apps/local/app.conf", "url = http://localhost:9000/a/b?c=d\n")
        write("apps/local/index.html", "<title>Local</title>")
        val local = app("local")
        assertEquals(WebAppKind.SERVED, local.kind)
        assertEquals("http://localhost:9000", local.origin)
        assertEquals("http://localhost:9000/a/b?c=d", local.url)
        assertEquals("Local", local.label)
    }

    @Test
    fun `an app conf without a good url leaves a static app static`() {
        write("apps/hello/app.conf", "url = http://example.com/\n")
        assertEquals(WebAppKind.STATIC, app("hello").kind)
        assertEquals(1, app("hello").problems.size)
    }

    @Test
    fun `the tools' apps are listed, and the user's win`() {
        File(tools, "apps/sketch").mkdirs()
        File(tools, "apps/sketch/index.html").writeText("<title>Sketch</title>")
        File(tools, "apps/hello").mkdirs()
        File(tools, "apps/hello/index.html").writeText("<title>Tools Hello</title>")
        File(tools, "apps/plain").mkdirs()
        File(tools, "apps/plain/index.html").writeText("<title>Tools Plain</title>")

        val list = apps.list()
        assertEquals(listOf("escape", "hello", "notes", "plain", "served", "sketch"), list.map { it.name })
        assertEquals("/root/apps/hello", app("hello").path)
        assertEquals("/opt/mynx/apps/plain", app("plain").path)
        assertEquals("/opt/mynx/apps/sketch", app("sketch").path)
        assertEquals("/opt/mynx/apps/sketch", app("sketch").root)
        assertEquals("text/html", apps.respond(app("sketch"), "GET", "/").mime)
    }

    @Test
    fun `a link to a folder elsewhere is an app, confined to that folder`() {
        write("pixel-hero/assets/index.html", "<title>Pixel Hero</title>")
        write("pixel-hero/build.sh")
        link("apps/pixel-hero", "../pixel-hero/assets")
        link("apps/pixel-abs", "/root/pixel-hero/assets")

        val app = app("pixel-hero")
        assertEquals("/root/apps/pixel-hero", app.path)
        assertEquals("/root/pixel-hero/assets", app.root)
        assertEquals("/root/pixel-hero/assets", app("pixel-abs").root)
        assertEquals(200, get("pixel-hero", "/index.html").status)
        assertEquals(404, get("pixel-hero", "/../build.sh").status)
    }

    @Test
    fun `files, executables, dangling links and bad names aren't apps`() {
        write("apps/tool", "#!/bin/sh\n").setExecutable(true)
        link("apps/gone", "/root/nowhere")
        assertEquals(listOf("escape", "hello", "notes", "served"), apps.list().map { it.name })
        assertNull(apps.find("tool"))
        assertNull(apps.find("gone"))
        assertNull(apps.find("Bad_Name"))
        assertNull(apps.find("plain"))
        assertNull(apps.find("nope"))
    }

    @Test
    fun `resolves absolute links as guest paths`() {
        write("target.txt")
        link("abs", "/root/target.txt")
        link("rel", "target.txt")
        link("dir", "/root/apps")
        assertEquals("/root/target.txt", apps.resolve("/root/abs"))
        assertEquals("/root/target.txt", apps.resolve("/root/rel"))
        assertEquals("/root/apps/hello/app.js", apps.resolve("/root/dir/hello/./x/../app.js"))
        assertEquals("/etc", apps.resolve("/../../etc"))
        assertNull(apps.resolve("relative"))
    }

    @Test
    fun `host paths of guest paths`() {
        assertEquals(File(rootfs.path + "/root/x"), apps.host("/root/x"))
        assertEquals(File(tools, "apps/sketch"), apps.host("/opt/mynx/apps/sketch"))
        assertEquals(File(tools.path), apps.host("/opt/mynx"))
        assertEquals(File(rootfs.path + "/opt/mynxy"), apps.host("/opt/mynxy"))
    }

    @Test
    fun `links into the tools folder follow it`() {
        File(tools, "apps/shared").mkdirs()
        File(tools, "apps/shared/index.html").writeText("<title>Shared</title>")
        link("apps/mine", "/opt/mynx/apps/shared")
        assertEquals("/opt/mynx/apps/shared", app("mine").root)
        assertEquals(200, get("mine", "/").status)
    }

    @Test
    fun `a link loop doesn't resolve`() {
        link("a", "/root/b")
        link("b", "/root/a")
        assertNull(apps.resolve("/root/a"))
    }

    @Test
    fun `faked hard links lead back into the rootfs by either spelling`() {
        // link2symlink: NAME → host path of .l2s.NAME0001 → host path of .l2s.NAME0001.0002, the file.
        val alias = File(base, "alias")
        Files.createSymbolicLink(alias.toPath(), base.toPath())
        val dir = File(home, "apps/hello")
        File(dir, ".l2s.data.js0001.0002").writeText("let data = 1;\n")
        Files.createSymbolicLink(File(dir, ".l2s.data.js0001").toPath(), Path.of("$alias/rootfs/root/apps/hello/.l2s.data.js0001.0002"))
        Files.createSymbolicLink(File(dir, "data.js").toPath(), Path.of("$alias/rootfs/root/apps/hello/.l2s.data.js0001"))

        assertEquals("/root/apps/hello/.l2s.data.js0001.0002", apps.resolve("/root/apps/hello/data.js"))
        val response = get("hello", "/data.js")
        assertEquals(200, response.status)
        assertEquals("text/javascript", response.mime)
        assertEquals("let data = 1;\n", response.file!!.readText())
        assertEquals(404, get("hello", "/.l2s.data.js0001.0002").status)
    }

    @Test
    fun `a host link that isn't a faked hard link stays a guest path`() {
        write("x.txt")
        link("other", "${home.path}/x.txt")
        assertEquals("${home.path}/x.txt", apps.resolve("/root/other"))
    }

    @Test
    fun `serves a static app's files`() {
        val index = get("hello", "/")
        assertEquals(200, index.status)
        assertEquals("OK", index.reason)
        assertEquals("text/html", index.mime)
        assertEquals("utf-8", index.encoding)
        assertEquals("no-store", index.headers["Cache-Control"])
        assertEquals(File(home, "apps/hello/index.html").readText(), index.file!!.readText())
        assertEquals(index.file, get("hello", "").file)
        assertEquals(File(home, "apps/hello/sub/index.html").readText(), get("hello", "/sub/").file!!.readText())
        assertEquals("text/javascript", get("hello", "/app.js").mime)
        assertEquals(200, get("hello", "/app.js", "HEAD").status)

        val png = get("notes", "/icon.png")
        assertEquals("image/png", png.mime)
        assertNull(png.encoding)
        assertEquals("no-store", png.headers["Cache-Control"])
    }

    @Test
    fun `decodes each segment`() {
        write("apps/hello/a b+c.txt", "spaced\n")
        assertEquals("spaced\n", get("hello", "/a%20b+c.txt").file!!.readText())
        write("apps/hello/çay.txt", "tea\n")
        assertEquals("tea\n", get("hello", "/%C3%A7ay.txt").file!!.readText())
    }

    @Test
    fun `nothing outside the folder`() {
        assertEquals(200, get("escape", "/inside.txt").status)
        for (path in listOf(
            "/outside.txt", "/../../secret.txt", "/%2e%2e/%2e%2e/secret.txt", "/%2E%2E/x", "/sub/%2e%2e/index.html",
            "/./index.html", "/%2e/index.html", "/a%2fb", "/sub%2Findex.html", "/index.html%00", "/%00",
            "/%zz", "/%e9", "/%", "/nope.txt", "/sub", "/.l2s.x",
        )) {
            val response = get(if (path.startsWith("/sub")) "hello" else "escape", path)
            assertEquals(404, response.status, path)
            assertEquals("Not Found", response.reason, path)
            assertEquals("text/plain", response.mime, path)
            assertNull(response.file, path)
            assertTrue(response.body!!.isNotEmpty(), path)
            assertEquals("no-store", response.headers["Cache-Control"], path)
        }
    }

    @Test
    fun `a link inside the folder is followed`() {
        link("apps/hello/again.js", "app.js")
        link("apps/hello/deep", "sub")
        assertEquals(200, get("hello", "/again.js").status)
        assertEquals("text/javascript", get("hello", "/again.js").mime)
        assertEquals(200, get("hello", "/deep/").status)
    }

    @Test
    fun `only GET and HEAD`() {
        val response = get("hello", "/", "POST")
        assertEquals(405, response.status)
        assertEquals("Method Not Allowed", response.reason)
        assertEquals("GET, HEAD", response.headers["Allow"])
        assertNull(response.file)
    }

    @Test
    fun `a served app's files aren't served`() {
        assertEquals(404, get("served", "/app.conf").status)
    }

    @Test
    fun `the fingerprint changes when a file under the root does`() {
        val hello = app("hello")
        val first = apps.fingerprint(hello)
        assertEquals(first, apps.fingerprint(hello))

        val js = File(home, "apps/hello/app.js")
        js.setLastModified(js.lastModified() - 10_000)
        val touched = apps.fingerprint(hello)
        assertTrue(touched != first)

        File(home, "apps/hello/sub/new.css").writeText("p {}\n")
        val added = apps.fingerprint(hello)
        assertTrue(added != touched)

        File(home, "apps/hello/sub/new.css").delete()
        assertEquals(touched, apps.fingerprint(hello))
    }

    @Test
    fun `the fingerprint skips what's outside the root and faked hard link files`() {
        val escape = app("escape")
        val first = apps.fingerprint(escape)
        File(home, "secret.txt").writeText("changed, and longer\n")
        assertEquals(first, apps.fingerprint(escape))
        File(home, "apps/escape/.l2s.x0001").writeText("x")
        assertEquals(first, apps.fingerprint(escape))
    }

    @Test
    fun `the fingerprint survives a link loop`() {
        link("apps/hello/sub/loop", "..")
        apps.fingerprint(app("hello"))
    }

    @Test
    fun `problems in the user's apps`() {
        write("apps/notes/app.conf", "name = My Notes\nicon = missing.png\ncolour = red\n")
        link("apps/hello/out.png", "../../secret.txt")
        write("apps/hello/app.conf", "icon = out.png\n")
        write("apps/conf-only/app.conf", "name = Nothing\n")
        writeServices("[served-backend]\ncommand = run\n")

        assertEquals(
            listOf(
                ConfigProblems(
                    "~/apps/Bad_Name",
                    listOf("not a usable app name: use a-z, 0-9 and -, up to 40 characters, not starting or ending with -"),
                ),
                ConfigProblems("~/apps/conf-only/app.conf", listOf("not an app: no index.html, and no url")),
                ConfigProblems("~/apps/hello/app.conf", listOf("icon: out.png is outside the app's folder")),
                ConfigProblems(
                    "~/apps/notes/app.conf",
                    listOf("line 3: unknown key 'colour'", "icon: no such file missing.png in the app's folder"),
                ),
            ),
            webAppProblems(home),
        )
        assertNull(app("notes").icon)
        assertEquals(listOf("line 3: unknown key 'colour'", "icon: no such file missing.png in the app's folder"), app("notes").problems)
    }

    @Test
    fun `mynx check lists the apps' problems`() {
        assertEquals(
            listOf("~/apps/Bad_Name", "~/apps/served/app.conf"),
            checkConfig(home).map { it.file },
        )
    }

    @Test
    fun `links to the tools' apps count as dangling in mynx check`() {
        link("apps/Tools_Link", "/opt/mynx/apps/sketch")
        assertEquals(listOf("~/apps/Bad_Name", "~/apps/served/app.conf"), webAppProblems(home).map { it.file })
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
