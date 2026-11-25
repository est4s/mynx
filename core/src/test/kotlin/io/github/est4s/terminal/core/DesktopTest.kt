package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTest {
    private val base = createTempDirectory("desktop").toFile()
    private val rootfs = File(base, "debian").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val dir = File(rootfs, "tmp/.mynx/requests").apply { mkdirs() }
    private val server = X11Server("/app/lib/libXlorie.so", "/data/files/x11/termux-x11.jar", "io.github.est4s.terminal")
    private val host = FakeHost(server)
    private val names = mutableMapOf<Int, String>()
    private val signals = mutableListOf<Pair<Int, Int>>()
    private val desktop = Desktop(
        rootfs, host,
        processName = { names[it] },
        signal = { pid, sig -> signals += pid to sig },
        later = { _, action -> action() },
    )
    // Whether the app is on screen, and how many times desktop-open showed the desktop.
    private var onScreen = true
    private var opened = 0
    private val requests = MynxRequests(dir, home, later = desktopRequests(desktop) { onScreen.also { if (it) opened++ } })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private class FakeHost(override var server: X11Server?) : DesktopHost {
        val events = mutableListOf<String>()
        var serverLaunch: Launch? = null
        var serverLog: File? = null
        var sessionCommand: List<String>? = null
        var sessionLog: File? = null
        var serverUp = false
        var sessionUp = false
        var serverFails: String? = null

        override fun startServer(launch: Launch, workDir: File, log: File): String? {
            serverFails?.let { return it }
            events += "server"
            serverLaunch = launch
            serverLog = log
            serverUp = true
            return null
        }

        override fun serverRunning() = serverUp

        override fun stopServer() {
            events += "stop server"
            serverUp = false
        }

        override fun startSession(command: List<String>, log: File): String? {
            events += "session"
            sessionCommand = command
            sessionLog = log
            sessionUp = true
            return null
        }

        override fun sessionRunning() = sessionUp

        override fun stopSession() {
            events += "stop session"
            sessionUp = false
        }
    }

    private fun install(vararg paths: String) = paths.forEach { path ->
        val file = File(rootfs, path)
        file.parentFile.mkdirs()
        if (path.endsWith("/")) file.mkdirs() else file.writeText("")
    }

    private fun installXfce() {
        install("usr/share/X11/xkb/", "usr/bin/startxfce4", "usr/bin/dbus-launch")
        dpkg(*desktopPackages(DesktopSession.XFCE).toTypedArray())
    }

    // dpkg's list of what's installed, as in /var/lib/dpkg/status.
    private fun dpkg(vararg installed: String, removed: List<String> = emptyList()) {
        install("var/lib/dpkg/status")
        File(rootfs, "var/lib/dpkg/status").writeText(
            (installed.map { it to "install ok installed" } + removed.map { it to "deinstall ok config-files" })
                .joinToString("\n") { (name, status) -> "Package: $name\nStatus: $status\nVersion: 1\n" },
        )
    }

    private fun settings(text: String) {
        File(home, ".config/mynx").mkdirs()
        File(home, ".config/mynx/settings.conf").writeText(text)
    }

    private fun ask(name: String): String {
        File(dir, "a1.req").writeText("$name\n")
        requests.processPending()
        return File(dir, "a1.reply").readText().also { File(dir, "a1.reply").delete() }
    }

    @Test
    fun `the X server runs outside proot through app_process, its socket and lock in Debian's tmp`() {
        val launch = x11Launch(server, "/data/files/debian")

        assertEquals(
            listOf(
                "/system/bin/app_process", "-Xnoimage-dex2oat", "/", "--nice-name=mynx-x11",
                "com.termux.x11.CmdEntryPoint", ":0", "-ac", "-nolisten", "local", "-fp", "built-ins",
            ),
            launch.argv,
        )
        assertEquals(
            mapOf(
                "CLASSPATH" to "/data/files/x11/termux-x11.jar",
                "TMPDIR" to "/data/files/debian/tmp",
                "XKB_CONFIG_ROOT" to "/data/files/debian/usr/share/X11/xkb",
                "MYNX_X11_PACKAGE" to "io.github.est4s.terminal",
                "MYNX_X11_LIB" to "/app/lib/libXlorie.so",
            ),
            launch.env,
        )
        assertEquals(listOf("LD_PRELOAD", "LD_LIBRARY_PATH"), launch.unset)
    }

    @Test
    fun `the X server's font path is Debian's font folders that have a catalogue`() {
        // Termux:X11 would take <rootfs>/etc/X11/fonts, which xfonts-base makes, holding only an alias file.
        install("etc/X11/fonts/misc/xfonts-base.alias", "usr/share/fonts/X11/misc/fonts.dir", "usr/share/fonts/X11/75dpi/fonts.dir")
        install("usr/share/fonts/X11/100dpi/", "usr/share/fonts/X11/Type1/fonts.scale")
        val argv = x11Launch(server, rootfs.path).argv

        val fonts = "$rootfs/usr/share/fonts/X11"
        assertEquals(listOf("-fp", "$fonts/misc,$fonts/75dpi,built-ins"), argv.takeLast(2))
    }

    @Test
    fun `XFCE starts in a dbus session once the X server's socket is there`() {
        val command = desktopSessionCommand(DesktopSession.XFCE)!!

        assertEquals(listOf("/bin/bash", "--login", "-c"), command.take(3))
        val script = command[3]
        assertTrue(script.startsWith("export DISPLAY=:0; "), script)
        assertTrue("[ -S /tmp/.X11-unix/X0 ] && break" in script, script)
        assertTrue(script.endsWith("exec dbus-launch --exit-with-session startxfce4"), script)
        assertNull(desktopSessionCommand(DesktopSession.NONE))
    }

    @Test
    fun `start runs the X server, then the session, each with its log`() {
        installXfce()

        val answer = ask("desktop-start")

        assertEquals(
            """{"ok":true,"started":true,"running":true,"display":":0","session":"xfce","sessionRunning":true,""" +
                """"socket":"/tmp/.X11-unix/X0","log":"/tmp/.mynx/desktop/x11.log","problem":null,"install":[]}""",
            answer,
        )
        assertEquals(listOf("server", "session"), host.events)
        assertEquals(x11Launch(server, rootfs.path), host.serverLaunch)
        assertEquals(desktopSessionCommand(DesktopSession.XFCE), host.sessionCommand)
        assertEquals(File(rootfs, "tmp/.mynx/desktop/x11.log"), host.serverLog)
        assertEquals(File(rootfs, "tmp/.mynx/desktop/session.log"), host.sessionLog)
        assertTrue(File(rootfs, "tmp/.mynx/desktop").isDirectory)
    }

    @Test
    fun `with no session, start runs the X server alone`() {
        install("usr/share/X11/xkb/")
        settings("desktop = none\n")

        val answer = ask("desktop-start")

        assertTrue(answer.startsWith("""{"ok":true,"started":true,"running":true,"display":":0","session":"none","sessionRunning":false,"""), answer)
        assertEquals(listOf("server"), host.events)
    }

    @Test
    fun `start when it runs starts only a session that has ended`() {
        installXfce()
        ask("desktop-start")
        host.sessionUp = false

        val answer = ask("desktop-start")

        assertTrue(answer.startsWith("""{"ok":true,"started":false,"running":true,"""), answer)
        assertEquals(listOf("server", "session", "session"), host.events)
        ask("desktop-start")
        assertEquals(listOf("server", "session", "session"), host.events)
    }

    @Test
    fun `a build without the X server says so`() {
        installXfce()
        host.server = null

        assertEquals("""{"ok":false,"error":"this build of mynx has no X server yet"}""", ask("desktop-start"))
        assertTrue(ask("desktop-status").endsWith(""""problem":"this build of mynx has no X server yet","install":[]}"""))
        assertEquals(emptyList(), host.events)
    }

    @Test
    fun `says how to install what's missing`() {
        assertEquals(
            """{"ok":false,"error":"the X server needs Debian's keyboard layouts (xkb-data): mynx desktop install installs them"}""",
            ask("desktop-start"),
        )
        install("usr/share/X11/xkb/")
        assertEquals(
            """{"ok":false,"error":"XFCE isn't installed: mynx desktop install installs it (or mynx set desktop none runs the X server alone)"}""",
            ask("desktop-start"),
        )
        install("usr/bin/startxfce4")
        assertEquals(
            """{"ok":false,"error":"XFCE needs dbus-launch (dbus-x11): mynx desktop install installs it"}""",
            ask("desktop-start"),
        )
        settings("desktop = none\n")
        assertTrue(ask("desktop-start").startsWith("""{"ok":true"""))
    }

    @Test
    fun `XFCE takes a small set of Debian's packages, without their recommends`() {
        assertEquals(listOf("xkb-data", "xfonts-base"), desktopPackages(DesktopSession.NONE))
        assertEquals(
            listOf("xkb-data", "xfonts-base", "xfce4", "xfce4-terminal", "dbus-x11", "tango-icon-theme"),
            desktopPackages(DesktopSession.XFCE),
        )
    }

    @Test
    fun `status lists the session's packages dpkg hasn't installed`() {
        dpkg("xkb-data", "xfce4-terminal", removed = listOf("xfce4"))

        val status = desktop.status()

        assertEquals(listOf("xfonts-base", "xfce4", "dbus-x11", "tango-icon-theme"), status.install)
        assertTrue(ask("desktop-status").endsWith(""""install":["xfonts-base","xfce4","dbus-x11","tango-icon-theme"]}"""))
        settings("desktop = none\n")
        assertEquals(listOf("xfonts-base"), desktop.status().install)
        File(rootfs, "var/lib/dpkg/status").delete()
        assertEquals(listOf("xkb-data", "xfonts-base"), desktop.status().install)
    }

    @Test
    fun `the tab that installs the desktop runs mynx desktop install, then a shell`() {
        assertEquals(runThenShell("mynx desktop install"), DESKTOP_INSTALL_TAB_COMMAND)
    }

    @Test
    fun `the X server failing to start is the answer`() {
        installXfce()
        host.serverFails = "app_process: no such file"

        assertEquals("""{"ok":false,"error":"the X server didn't start: app_process: no such file"}""", ask("desktop-start"))
        assertEquals(emptyList(), host.events)
    }

    @Test
    fun `status says what runs, and nothing before a start`() {
        installXfce()
        assertEquals(
            """{"ok":true,"running":false,"display":null,"session":"xfce","sessionRunning":false,""" +
                """"socket":"/tmp/.X11-unix/X0","log":"/tmp/.mynx/desktop/x11.log","problem":null,"install":[]}""",
            ask("desktop-status"),
        )
        ask("desktop-start")
        assertTrue(ask("desktop-status").startsWith("""{"ok":true,"running":true,"display":":0","session":"xfce","sessionRunning":true,"""))
        assertEquals(listOf("server", "session"), host.events)
    }

    @Test
    fun `stop ends the session, then the X server`() {
        installXfce()
        ask("desktop-start")

        assertTrue(ask("desktop-stop").startsWith("""{"ok":true,"stopped":true,"running":false,"display":null,"""))
        assertEquals(listOf("server", "session", "stop session", "stop server"), host.events)
        assertTrue(ask("desktop-stop").startsWith("""{"ok":true,"stopped":false,"""))
    }

    @Test
    fun `open shows the desktop, which starts it, and answers with the status`() {
        installXfce()

        assertTrue(ask("desktop-open").startsWith("""{"ok":true,"opened":true,"running":false,"display":null,"session":"xfce","""))
        assertEquals(1, opened)
        assertEquals(emptyList(), host.events)
    }

    @Test
    fun `open is refused while the app isn't on screen`() {
        onScreen = false

        assertEquals(
            """{"ok":false,"error":"the app must be on screen to open the desktop: bring mynx to the front and try again"}""",
            ask("desktop-open"),
        )
        assertEquals(0, opened)
    }

    @Test
    fun `the desktop's back button names where it goes back to`() {
        assertEquals("← Terminal", desktopBackLabel(fromTouch = false))
        assertEquals("← Touch view", desktopBackLabel(fromTouch = true))
    }

    @Test
    fun `new tabs get the display while the X server runs`() {
        installXfce()
        assertNull(desktop.display)
        ask("desktop-start")
        assertEquals(":0", desktop.display)
        ask("desktop-stop")
        assertNull(desktop.display)
    }

    private fun lock(pid: Int) {
        File(rootfs, "tmp/.X11-unix").mkdirs()
        File(rootfs, "tmp/.X11-unix/X0").writeText("")
        File(rootfs, "tmp/.X0-lock").writeText("%10d\n".format(pid))
    }

    @Test
    fun `a lock and socket left by a server that has gone are removed before a start`() {
        installXfce()
        lock(4321)

        assertTrue(ask("desktop-start").startsWith("""{"ok":true,"started":true"""))
        assertFalse(File(rootfs, "tmp/.X0-lock").exists())
        assertFalse(File(rootfs, "tmp/.X11-unix/X0").exists())
        assertEquals(emptyList(), signals)
    }

    @Test
    fun `a socket with no lock is removed too`() {
        installXfce()
        File(rootfs, "tmp/.X11-unix").mkdirs()
        File(rootfs, "tmp/.X11-unix/X0").writeText("")

        ask("desktop-start")

        assertFalse(File(rootfs, "tmp/.X11-unix/X0").exists())
    }

    @Test
    fun `an X server of ours left from before is stopped first`() {
        installXfce()
        lock(4321)
        names[4321] = "mynx-x11"
        val waits = mutableListOf<Long>()
        val desktop = Desktop(
            rootfs, host,
            processName = { names[it] },
            signal = { pid, sig -> signals += pid to sig },
            later = { ms, action ->
                waits += ms
                if (waits.size == 2) names.remove(4321)
                action()
            },
        )
        val requests = MynxRequests(dir, home, later = desktopRequests(desktop))
        File(dir, "a1.req").writeText("desktop-start\n")

        requests.processPending()

        assertTrue(File(dir, "a1.reply").readText().startsWith("""{"ok":true,"started":true"""))
        assertEquals(listOf(4321 to 15), signals)
        assertEquals(listOf(100L, 100L), waits)
        assertFalse(File(rootfs, "tmp/.X0-lock").exists())
        assertEquals(listOf("server", "session"), host.events)
    }

    @Test
    fun `a leftover server that won't stop is killed`() {
        installXfce()
        lock(4321)
        names[4321] = "mynx-x11"

        assertTrue(ask("desktop-start").startsWith("""{"ok":true,"started":true"""))
        assertEquals(listOf(4321 to 15, 4321 to 9), signals)
    }

    @Test
    fun `another program's X server on the display is left alone`() {
        installXfce()
        lock(4321)
        names[4321] = "Xvfb"

        assertEquals(
            """{"ok":false,"error":"display :0 is taken by another X server (Xvfb, pid 4321): stop it first"}""",
            ask("desktop-start"),
        )
        assertTrue(File(rootfs, "tmp/.X0-lock").exists())
        assertEquals(emptyList(), host.events)
        assertEquals(emptyList(), signals)
    }

    @Test
    fun `stop also ends an X server of ours left from before`() {
        lock(4321)
        names[4321] = "mynx-x11"

        assertTrue(ask("desktop-stop").startsWith("""{"ok":true,"stopped":true,"""))
        assertEquals(listOf(4321 to 15), signals)
    }

    @Test
    fun `a process's name is its command's file name`() {
        val proc = File(base, "proc")
        File(proc, "12").mkdirs()
        File(proc, "12/cmdline").writeBytes("mynx-x11\u0000com.termux.x11.CmdEntryPoint\u0000:0\u0000".toByteArray())
        File(proc, "13").mkdirs()
        File(proc, "13/cmdline").writeBytes("/usr/bin/Xvfb\u0000:0\u0000".toByteArray())
        File(proc, "14").mkdirs()
        File(proc, "14/cmdline").writeBytes(ByteArray(0))
        File(proc, "14/comm").writeText("kworker\n")

        assertEquals("mynx-x11", runningProcessName(12, proc))
        assertEquals("Xvfb", runningProcessName(13, proc))
        assertEquals("kworker", runningProcessName(14, proc))
        assertNull(runningProcessName(15, proc))
    }
}
