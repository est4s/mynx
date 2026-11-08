package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfPart2Test {
    @Test
    fun `fullscreen and screen-awake`() {
        assertEquals(Fullscreen.NO, parseAppConf("").conf.fullscreen)
        val parsed = parseAppConf("fullscreen = edge\nscreen-awake = yes\n")
        assertEquals(emptyList(), parsed.problems)
        assertEquals(AppConf(fullscreen = Fullscreen.EDGE, screenAwake = true), parsed.conf)
        assertEquals(Fullscreen.YES, parseAppConf("fullscreen = yes\n").conf.fullscreen)
        assertEquals(AppConf(), parseAppConf("fullscreen = no\nscreen-awake = no\n").conf)
    }

    @Test
    fun `bad values are skipped and reported`() {
        val parsed = parseAppConf("fullscreen = edge\nfullscreen = always\nscreen-awake = on\nscreen-awake =\n")
        assertEquals(AppConf(fullscreen = Fullscreen.EDGE), parsed.conf)
        assertEquals(
            listOf(
                "line 2: fullscreen is no, yes or edge",
                "line 3: screen-awake is yes or no",
                "line 4: screen-awake must not be empty",
            ),
            parsed.problems,
        )
    }
}

class WebAppsPart2Test {
    private val base = createTempDirectory("webapps2").toFile()
    private val rootfs = File(base, "rootfs")
    private val home = File(rootfs, "root").also { copyTree(fixturesDir(), it) }
    private val tools = File(base, "tools").apply { mkdirs() }
    private val apps = WebApps(home, rootfs.path, tools)
    private val dir = File(base, "requests").apply { mkdirs() }
    private val host = FakeWebAppHost()
    private val services = FakeWebAppServices()
    private var time = 0L
    private val launcher = WebAppLauncher(apps, host, services, now = { time })
    private val requests = MynxRequests(dir, home, later = webAppRequests(launcher), alive = { true })

    @AfterTest
    fun cleanUp() {
        deleteTree(base)
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.reply").delete()
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").takeIf { it.isFile }?.readText() ?: "(no answer yet)"
    }

    private fun refused(message: String) = """{"ok":false,"error":${json(message)}}"""

    @Test
    fun `an app carries its fullscreen and screen-awake, and app-list says them`() {
        File(home, "apps/game").mkdirs()
        File(home, "apps/game/index.html").writeText("<title>Game</title>")
        File(home, "apps/game/app.conf").writeText("fullscreen = yes\nscreen-awake = yes\n")
        val game = apps.find("game")!!
        assertEquals(Fullscreen.YES, game.fullscreen)
        assertTrue(game.screenAwake)
        assertEquals(Fullscreen.NO, apps.find("hello")!!.fullscreen)

        val list = (parseJson(ask("app-list")) as Map<*, *>)["apps"] as List<*>
        val entry = list.map { it as Map<*, *> }.single { it["name"] == "game" }
        assertEquals("yes", entry["fullscreen"])
        assertEquals(true, entry["screen_awake"])
    }

    @Test
    fun `mynx js is served from the tools`() {
        val script = File(tools, BRIDGE_SCRIPT_FILE).apply { parentFile.mkdirs(); writeText("// mynx\n") }
        val get = apps.bridgeScript("GET", "/mynx.js")
        assertEquals(200, get.status)
        assertEquals(script, get.file)
        assertEquals("text/javascript", get.mime)
        assertEquals("utf-8", get.encoding)
        assertEquals("no-store", get.headers["Cache-Control"])
        assertEquals(200, apps.bridgeScript("HEAD", "/mynx.js").status)
        assertEquals(405, apps.bridgeScript("POST", "/mynx.js").status)
        assertEquals(404, apps.bridgeScript("GET", "/other.js").status)
        assertEquals(404, apps.bridgeScript("GET", "/").status)
        script.delete()
        assertEquals(404, apps.bridgeScript("GET", "/mynx.js").status)
    }

    private val scheme = NEON.copy(
        foreground = 0xffffff,
        background = 0x000000,
        palette = NEON.palette + (1..14).associateWith { 0x101010 * it },
    )

    @Test
    fun `a shortcut with the app's icon`() {
        val notes = apps.find("notes")!!
        val icon = apps.host(notes.icon!!)!!
        val shortcut = appShortcut(notes, icon, scheme)
        assertEquals("app-notes", shortcut.id)
        assertEquals("notes", shortcut.name)
        assertEquals("My Notes", shortcut.label)
        assertEquals(icon, shortcut.icon)
        assertEquals("M", shortcut.letter)
    }

    @Test
    fun `a generated shortcut's letter and colours`() {
        assertEquals("H", appShortcut(testApp("hello", "hello there"), null, scheme).letter)
        assertEquals("4", appShortcut(testApp("x", "  42 things"), null, scheme).letter)
        assertEquals("Q", appShortcut(testApp("q-app", "!!!"), null, scheme).letter)

        val indices = listOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14)
        for (name in listOf("hello", "weather", "a", "pixel-hero", "zz9")) {
            val shortcut = appShortcut(testApp(name), null, scheme)
            val background = scheme.palette.getValue(indices[Math.floorMod(name.hashCode(), 12)])
            assertEquals(background, shortcut.background, name)
            val foreground = if (contrast(background, scheme.background) >= contrast(background, scheme.foreground)) {
                scheme.background
            } else {
                scheme.foreground
            }
            assertEquals(foreground, shortcut.foreground, name)
        }
    }

    @Test
    fun `dark backgrounds get the light colour, light ones the dark`() {
        val dark = scheme.copy(palette = scheme.palette + indicesTo(0x202020))
        assertEquals(0xffffff, appShortcut(testApp("a"), null, dark).foreground)
        val light = scheme.copy(palette = scheme.palette + indicesTo(0xeeeeee))
        assertEquals(0x000000, appShortcut(testApp("a"), null, light).foreground)
    }

    private fun indicesTo(color: Int) = (listOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14)).associateWith { color }

    @Test
    fun `app-shortcut asks Android to pin the app's shortcut`() {
        assertTrue("app-shortcut" in WEB_APP_REQUEST_NAMES)
        assertEquals(WEB_APP_SHORTCUT_SECONDS, launcher.requests()["app-shortcut"]!!.seconds)
        assertEquals("""{"ok":true,"name":"notes","id":"app-notes"}""", ask("app-shortcut", "notes"))
        val shortcut = host.pinned.single()
        assertEquals("My Notes", shortcut.label)
        assertEquals(File(home, "apps/notes/icon.png").canonicalFile, shortcut.icon!!.canonicalFile)
        assertEquals(appShortcut(apps.find("notes")!!, shortcut.icon, NEON), shortcut)
    }

    @Test
    fun `app-shortcut follows the user's colours`() {
        File(home, "$CONFIG_DIR/colors.properties").apply { parentFile.mkdirs() }.writeText("background=#ffffff\n")
        ask("app-shortcut", "hello")
        val colors = loadColorScheme(File(home, "$CONFIG_DIR/colors.properties")).scheme
        assertEquals(appShortcut(apps.find("hello")!!, null, colors), host.pinned.single())
    }

    @Test
    fun `app-shortcut refusals`() {
        assertEquals(refused("app-shortcut needs a name"), ask("app-shortcut"))
        assertEquals(refused("no app named nope (mynx app list shows them)"), ask("app-shortcut", "nope"))
        host.onScreen = false
        assertEquals(refused("mynx must be on screen to add a shortcut"), ask("app-shortcut", "hello"))
        host.onScreen = true
        host.pinFails = "this launcher can't pin shortcuts"
        assertEquals(refused("this launcher can't pin shortcuts"), ask("app-shortcut", "hello"))
    }

    private fun backend(state: ServiceState) {
        services.services["served-backend"] = WebAppServiceStatus(state, false)
    }

    @Test
    fun `the open step shows a static app and updates its shortcut`() {
        val shown = mutableListOf<String>()
        val results = mutableListOf<Result<WebAppOpened>>()
        launcher.openStep("hello", { shown += it.name; null }) { results += it }
        assertEquals(listOf("hello"), shown)
        assertEquals(WebAppOpened(apps.find("hello")!!, false), results.single().getOrThrow())
        assertEquals(listOf("app-hello"), host.updated.map { it.id })
        assertEquals(emptyList(), host.opened)
    }

    @Test
    fun `the open step words its refusals as app-open does`() {
        val results = mutableListOf<Result<WebAppOpened>>()
        launcher.openStep("nope", { null }) { results += it }
        assertEquals("no app named nope (mynx app list shows them)", results.single().exceptionOrNull()?.message)
        launcher.openStep("hello", { "the screen went" }) { results += it }
        assertEquals("the screen went", results.last().exceptionOrNull()?.message)
        assertEquals(emptyList(), host.updated)
    }

    @Test
    fun `the open step starts a served app's service and remembers it`() {
        backend(ServiceState.STOPPED)
        val results = mutableListOf<Result<WebAppOpened>>()
        launcher.openStep("served", { null }) { results += it }
        assertEquals(emptyList(), results)
        host.answering += 8765
        host.runScheduled { time += it }
        assertTrue(results.single().getOrThrow().startedService)
        launcher.screenClosed("served")
        assertEquals(listOf("start served-backend", "stop served-backend"), services.events)
    }

    @Test
    fun `cancelling the open step stops the service it started, and says nothing`() {
        backend(ServiceState.STOPPED)
        val results = mutableListOf<Result<WebAppOpened>>()
        val cancel = launcher.openStep("served", { null }) { results += it }
        cancel()
        host.answering += 8765
        host.runScheduled { time += it }
        assertEquals(emptyList(), results)
        assertEquals(listOf("start served-backend", "stop served-backend"), services.events)
    }

    @Test
    fun `a show that fails stops the service it started`() {
        backend(ServiceState.STOPPED)
        host.answering += 8765
        val results = mutableListOf<Result<WebAppOpened>>()
        launcher.openStep("served", { "no screen" }) { results += it }
        assertEquals("no screen", results.single().exceptionOrNull()?.message)
        assertEquals(listOf("start served-backend", "stop served-backend"), services.events)
        assertFalse(results.single().isSuccess)
        assertNull(results.single().getOrNull())
    }
}
