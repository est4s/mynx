package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppPermissionsTest {
    @Test
    fun `permissions are known by their words`() {
        assertEquals(
            listOf("location", "camera", "microphone", "clipboard", "notifications", "bluetooth", "serial"),
            AppPermission.entries.map { it.word },
        )
        assertEquals(AppPermission.CLIPBOARD, AppPermission.of("clipboard"))
        assertNull(AppPermission.of("services"))
    }

    @Test
    fun `answers are allow, deny or ask`() {
        assertEquals(PermissionAnswer.DENY, PermissionAnswer.of("deny"))
        assertNull(PermissionAnswer.of("yes"))
    }
}

class AppPermissionsFileTest {
    @Test
    fun `blocks of answers, comments and blank lines`() {
        val parsed = parseAppPermissions(
            """
            # comment
            [weather]
            location = allow
             camera=deny

            [ sketch ]
            clipboard = allow
            """.trimIndent(),
        )
        assertEquals(emptyList(), parsed.problems)
        assertEquals(
            mapOf(
                "weather" to mapOf(AppPermission.LOCATION to PermissionAnswer.ALLOW, AppPermission.CAMERA to PermissionAnswer.DENY),
                "sketch" to mapOf(AppPermission.CLIPBOARD to PermissionAnswer.ALLOW),
            ),
            parsed.apps,
        )
    }

    @Test
    fun `bad lines are skipped and reported`() {
        val parsed = parseAppPermissions(
            listOf(
                "location = allow",
                "[Bad_Name]",
                "camera = allow",
                "[ok]",
                "camera",
                "services = allow",
                "camera = maybe",
                "location = allow",
                "location = deny",
                "[ok]",
                "microphone = allow",
            ).joinToString("\n"),
        )
        assertEquals(
            listOf(
                "line 1: expected [app] first",
                "line 2: 'Bad_Name' can't be an app's name",
                "line 5: expected permission = allow or deny",
                "line 6: unknown permission 'services' (location, camera, microphone, clipboard, notifications, bluetooth, serial)",
                "line 7: camera is allow or deny",
                "line 9: location is already set on line 8: the last counts",
                "line 10: [ok] is already on line 4",
            ),
            parsed.problems,
        )
        assertEquals(
            mapOf("ok" to mapOf(AppPermission.LOCATION to PermissionAnswer.DENY, AppPermission.MICROPHONE to PermissionAnswer.ALLOW)),
            parsed.apps,
        )
    }

    @Test
    fun `setting starts a new file, adds blocks at the end and keeps the rest`() {
        val first = setAppPermission(null, "weather", AppPermission.LOCATION, PermissionAnswer.ALLOW).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\nlocation = allow\n", first)
        val more = setAppPermission(first, "weather", AppPermission.CAMERA, PermissionAnswer.DENY).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\nlocation = allow\ncamera = deny\n", more)
        val changed = setAppPermission(more, "weather", AppPermission.LOCATION, PermissionAnswer.DENY).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\nlocation = deny\ncamera = deny\n", changed)
        val other = setAppPermission(changed, "sketch", AppPermission.SERIAL, PermissionAnswer.ALLOW).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\nlocation = deny\ncamera = deny\n\n[sketch]\nserial = allow\n", other)
    }

    @Test
    fun `setting keeps comments and changes every line of a permission given twice`() {
        val text = "# mine\n[weather]\n# where\nlocation = deny\nlocation=deny\n[sketch]\ncamera = allow\n"
        assertEquals(
            "# mine\n[weather]\n# where\nlocation = allow\n[sketch]\ncamera = allow\n",
            setAppPermission(text, "weather", AppPermission.LOCATION, PermissionAnswer.ALLOW).getOrThrow(),
        )
    }

    @Test
    fun `ask removes the line, and a block left empty`() {
        val text = NEW_APP_PERMISSIONS + "\n[weather]\nlocation = allow\ncamera = deny\n\n[sketch]\nserial = allow\n"
        val one = setAppPermission(text, "weather", AppPermission.CAMERA, PermissionAnswer.ASK).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\nlocation = allow\n\n[sketch]\nserial = allow\n", one)
        val none = setAppPermission(one, "weather", AppPermission.LOCATION, PermissionAnswer.ASK).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[sketch]\nserial = allow\n", none)
        assertEquals(none, setAppPermission(none, "weather", AppPermission.LOCATION, PermissionAnswer.ASK).getOrThrow())
    }

    @Test
    fun `a bad name can't be set`() {
        val result = setAppPermission(null, "No", AppPermission.CAMERA, PermissionAnswer.ALLOW)
        assertEquals("'No' can't be an app's name", result.exceptionOrNull()?.message)
    }

    @Test
    fun `reset removes the app's blocks and keeps the rest`() {
        val text = "# top\n\n[weather]\nlocation = allow\n\n[sketch]\nserial = allow\n\n[weather]\ncamera = deny\n"
        assertEquals("# top\n\n[sketch]\nserial = allow\n", resetAppPermissions(text, "weather"))
        assertEquals(text, resetAppPermissions(text, "other"))
    }
}

class AppPermissionsCheckTest {
    private val base = createTempDirectory("perms").toFile()
    private val home = File(base, "root").apply { mkdirs() }
    private val file = File(home, "$CONFIG_DIR/$APP_PERMISSIONS_CONF")
    private val apps = WebApps(home, base.path, File(base, "tools").apply { mkdirs() })
    private val changes = mutableListOf<String>()
    private val permissions = AppPermissions(home, apps) { changes += it }
    private val host = FakeBridgeHost()
    private val weather = app("weather", "Weather")

    init {
        File(home, "$WEB_APPS_DIR/weather").mkdirs()
        File(home, "$WEB_APPS_DIR/weather/index.html").writeText("<title>Weather</title>")
    }

    @AfterTest
    fun cleanUp() {
        deleteTree(base)
    }

    private fun write(text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `answers come from the file, read again each time`() {
        assertEquals(PermissionAnswer.ASK, permissions.answer("weather", AppPermission.LOCATION))
        write("[weather]\nlocation = allow\n")
        assertEquals(PermissionAnswer.ALLOW, permissions.answer("weather", AppPermission.LOCATION))
        write("[weather]\nlocation = deny\n")
        assertEquals(PermissionAnswer.DENY, permissions.answer("weather", AppPermission.LOCATION))
    }

    @Test
    fun `set writes the file and names the change`() {
        permissions.set("weather", AppPermission.CAMERA, PermissionAnswer.DENY).getOrThrow()
        permissions.set("weather", AppPermission.CAMERA, PermissionAnswer.ALLOW, asked = true).getOrThrow()
        assertEquals(NEW_APP_PERMISSIONS + "\n[weather]\ncamera = allow\n", file.readText())
        assertEquals(
            listOf("app permission weather camera deny", "app permission weather camera allow (asked)"),
            changes,
        )
        assertTrue(file.parentFile.list()!!.none { it.endsWith(".tmp") })
    }

    private fun check(permission: AppPermission = AppPermission.LOCATION): Pair<MutableList<PermissionRefusal?>, () -> Unit> {
        val results = mutableListOf<PermissionRefusal?>()
        val handle = permissions.check(weather, permission, host) { results += it }
        return results to handle
    }

    @Test
    fun `allowed goes on, denied is refused, with no dialog`() {
        write("[weather]\nlocation = allow\ncamera = deny\n")
        assertEquals(listOf<PermissionRefusal?>(null), check().first)
        assertEquals(
            listOf<PermissionRefusal?>(
                PermissionRefusal(BridgeError.DENIED, "Weather isn't allowed to use the camera (mynx app permissions weather camera allow)"),
            ),
            check(AppPermission.CAMERA).first,
        )
        assertEquals(emptyList(), host.asked)
    }

    @Test
    fun `not in front, it can't ask`() {
        assertEquals(
            listOf<PermissionRefusal?>(PermissionRefusal(BridgeError.REFUSED, "Weather must be on screen to ask for your location")),
            check().first,
        )
        assertEquals(emptyList(), host.asked)
        assertTrue(!file.exists())
    }

    @Test
    fun `asking writes and logs the answer, and the call goes on`() {
        host.front += "weather"
        val (results, _) = check()
        assertEquals(emptyList(), results)
        assertEquals("weather" to AppPermission.LOCATION, host.asked.single().let { it.first to it.second })
        host.answer(true)
        assertEquals(listOf<PermissionRefusal?>(null), results)
        assertEquals(PermissionAnswer.ALLOW, permissions.answer("weather", AppPermission.LOCATION))
        assertEquals(listOf("app permission weather location allow (asked)"), changes)
        assertEquals(listOf("weather log permission location: allow (asked)"), host.logged)
        assertEquals(listOf<PermissionRefusal?>(null), check().first)
        assertEquals(emptyList(), host.asked)
    }

    @Test
    fun `don't allow is remembered and refused`() {
        host.front += "weather"
        val (results, _) = check()
        host.answer(false)
        assertEquals(BridgeError.DENIED, results.single()!!.code)
        assertEquals(PermissionAnswer.DENY, permissions.answer("weather", AppPermission.LOCATION))
        assertEquals(listOf("weather log permission location: deny (asked)"), host.logged)
    }

    @Test
    fun `a dialog closed unanswered refuses closed and writes nothing`() {
        host.front += "weather"
        val (results, _) = check()
        host.answer(null)
        assertEquals(
            listOf<PermissionRefusal?>(PermissionRefusal(BridgeError.CLOSED, "Weather's question about your location was closed")),
            results,
        )
        assertTrue(!file.exists())
        assertEquals(emptyList(), changes)
    }

    @Test
    fun `calls asking the same wait for one dialog`() {
        host.front += "weather"
        val (first, _) = check()
        val (second, _) = check()
        val (camera, _) = check(AppPermission.CAMERA)
        assertEquals(2, host.asked.size)
        host.answer(true)
        assertEquals(listOf<PermissionRefusal?>(null), first)
        assertEquals(listOf<PermissionRefusal?>(null), second)
        assertEquals(emptyList(), camera)
    }

    @Test
    fun `a withdrawn caller is refused closed, and the dialog goes once nobody waits`() {
        host.front += "weather"
        val (first, withdrawFirst) = check()
        val (second, withdrawSecond) = check()
        withdrawFirst()
        assertEquals(BridgeError.CLOSED, first.single()!!.code)
        assertEquals(0, host.dismissed)
        withdrawSecond()
        withdrawSecond()
        assertEquals(1, second.size)
        assertEquals(1, host.dismissed)
        assertTrue(!file.exists())
        val (third, _) = check()
        assertEquals(1, host.asked.size)
        host.answer(true)
        assertEquals(listOf<PermissionRefusal?>(null), third)
    }

    @Test
    fun `withdrawing after the answer does nothing`() {
        write("[weather]\nlocation = allow\n")
        val (results, withdraw) = check()
        withdraw()
        assertEquals(listOf<PermissionRefusal?>(null), results)
    }

    private fun request(vararg args: String): Map<*, *> {
        val sink = FakeReplySink()
        val later = permissions.requests().getValue(args[0])
        later.start(args.drop(1), PendingReply(sink, "t") {})
        return parseJson(sink.answers.single()) as Map<*, *>
    }

    @Test
    fun `app-permissions lists every app installed or in the file`() {
        write("[gone]\ncamera = deny\n[weather]\nlocation = allow\n")
        val all = request("app-permissions")
        assertEquals(true, all["ok"])
        val list = all["apps"] as List<*>
        assertEquals(
            listOf(
                mapOf(
                    "name" to "gone", "label" to null, "installed" to false,
                    "permissions" to AppPermission.entries.associate { it.word to if (it == AppPermission.CAMERA) "deny" else "ask" },
                ),
                mapOf(
                    "name" to "weather", "label" to "Weather", "installed" to true,
                    "permissions" to AppPermission.entries.associate { it.word to if (it == AppPermission.LOCATION) "allow" else "ask" },
                ),
            ),
            list,
        )
        assertEquals(listOf("weather"), (request("app-permissions", "weather")["apps"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals("no app named nope (mynx app list shows them)", request("app-permissions", "nope")["error"])
    }

    @Test
    fun `app-permission-set sets, asks again and resets`() {
        assertEquals(
            mapOf("ok" to true, "name" to "x", "permission" to "camera", "value" to "allow"),
            request("app-permission-set", "x", "camera", "allow"),
        )
        assertEquals(PermissionAnswer.ALLOW, permissions.answer("x", AppPermission.CAMERA))
        request("app-permission-set", "x", "location", "deny")
        request("app-permission-set", "x", "camera", "ask")
        assertEquals(PermissionAnswer.ASK, permissions.answer("x", AppPermission.CAMERA))
        assertEquals(mapOf("ok" to true, "name" to "x", "reset" to true), request("app-permission-set", "x", "reset"))
        assertEquals(NEW_APP_PERMISSIONS, file.readText())
    }

    @Test
    fun `app-permission-set refuses bad lines`() {
        assertEquals("'X' can't be an app's name", request("app-permission-set", "X", "camera", "allow")["error"])
        assertEquals(
            "unknown permission 'cam' (location, camera, microphone, clipboard, notifications, bluetooth, serial)",
            request("app-permission-set", "x", "cam", "allow")["error"],
        )
        assertEquals("camera is allow, deny or ask", request("app-permission-set", "x", "camera", "yes")["error"])
        assertEquals(
            "app-permission-set needs NAME PERMISSION allow|deny|ask, or NAME reset",
            request("app-permission-set", "x")["error"],
        )
    }

    @Test
    fun `app-permission-set names its change for undo`() {
        val later = permissions.requests().getValue("app-permission-set")
        assertEquals("app permission x camera allow", later.change!!(listOf("x", "camera", "allow")))
        assertEquals("app permissions x reset", later.change!!(listOf("x", "reset")))
    }

    @Test
    fun `mynx check reports the file's problems`() {
        assertEquals(emptyList(), appPermissionProblems(home))
        write("[weather]\nlocation = maybe\n")
        val expected = ConfigProblems("~/$CONFIG_DIR/$APP_PERMISSIONS_CONF", listOf("line 2: location is allow or deny"))
        assertEquals(listOf(expected), appPermissionProblems(home))
        assertTrue(expected in checkConfig(home))
    }
}

/** A static app for the tests, not on disk. */
fun app(name: String, label: String = folderLabel(name), service: String? = null, served: Boolean = false): WebApp =
    if (served) {
        WebApp(name, label, "/root/apps/$name", "/root/apps/$name", WebAppKind.SERVED, "http://127.0.0.1:8080",
            "http://127.0.0.1:8080/", 8080, service, null, emptyList())
    } else {
        WebApp(name, label, "/root/apps/$name", "/root/apps/$name", WebAppKind.STATIC, webAppOrigin(name),
            webAppOrigin(name) + "/", null, null, null, emptyList())
    }
