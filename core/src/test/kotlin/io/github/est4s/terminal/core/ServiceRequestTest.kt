package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeServiceHost : ServiceHost<String> {
    val events = mutableListOf<String>()
    val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    val transcripts = mutableMapOf<String, String>()
    val tabs = mutableMapOf<Int, AdoptedTab<String>>()
    var foreground: Foreground? = null
    var startFails: String? = null
    var openFails: String? = null
    var changes = 0
    val adopted = mutableListOf<String>()
    val followed = mutableListOf<String>()
    private var count = 0

    override fun start(def: ServiceDef, saved: Boolean): String {
        startFails?.let { throw IllegalStateException(it) }
        count++
        events += "start ${def.name} saved=$saved"
        return "${def.name}#$count"
    }

    override fun pid(session: String) = 100 + session.substringAfter('#').toInt()

    override fun stop(session: String, group: Int?, timeoutSeconds: Int) {
        events += "stop $session group=$group timeout=$timeoutSeconds"
    }

    override fun transcript(session: String) = transcripts[session] ?: ""

    override fun open(name: String, session: String): String? {
        openFails?.let { return it }
        events += "open $name $session"
        return null
    }

    override fun adopt(name: String, shell: Int): Result<AdoptedTab<String>> =
        tabs.remove(shell)?.let { adopted += "$name ${it.session}"; Result.success(it) }
            ?: Result.failure(Exception("no tab with shell $shell"))

    override fun started(name: String, session: String) {
        followed += "$name $session"
    }

    override fun foreground(session: String) = foreground

    override fun schedule(delayMs: Long, block: () -> Unit) {
        scheduled += delayMs to block
    }

    override fun gaveUp(name: String, exitCode: Int?) {
        events += "gave up $name $exitCode"
    }

    override fun changed() {
        changes++
    }
}

class ServiceRequestTest {
    private val base = createTempDirectory("services").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val servicesDir = File(base, "tmp-services").apply { mkdirs() }
    private val conf = File(home, "$CONFIG_DIR/$SERVICES_CONF")
    private var time = 1_000_000L
    private val host = FakeServiceHost()
    private val manager = ServiceManager(home, servicesDir, host, now = { time })
    private val requests = MynxRequests(dir, home, now = { time }, later = serviceRequests(manager), alive = { true })

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun write(text: String) = conf.apply { parentFile.mkdirs() }.writeText(text)

    private fun ask(vararg lines: String): String {
        File(dir, "q.reply").delete()
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").takeIf { it.isFile }?.readText() ?: "(no answer yet)"
    }

    private fun waitFile() = File(dir, "q.wait")

    @Test
    fun `list with no file is empty`() {
        assertEquals("""{"ok":true,"services":[]}""", ask("service-list"))
    }

    @Test
    fun `list shows each service from the file`() {
        write("[web]\ncommand = python3 -m http.server\n[db]\ncommand = postgres\ncwd = /srv\nautostart = yes\nrestart = always\n")

        assertEquals(
            """{"ok":true,"services":[""" +
                """{"name":"web","saved":true,"command":"python3 -m http.server","cwd":"~","autostart":false,""" +
                """"restart":"on-failure","state":"stopped","pid":null,"started":null,"exit_code":null,"restarts":0,"open":false},""" +
                """{"name":"db","saved":true,"command":"postgres","cwd":"/srv","autostart":true,""" +
                """"restart":"always","state":"stopped","pid":null,"started":null,"exit_code":null,"restarts":0,"open":false}]}""",
            ask("service-list"),
        )
    }

    @Test
    fun `start runs it and answers with it`() {
        write("[web]\ncommand = run\n")
        File(servicesDir, "web.exit").writeText("1\n")

        val reply = ask("service-start", "web")

        assertEquals(listOf("start web saved=true"), host.events)
        assertFalse(File(servicesDir, "web.exit").exists(), "the last run's exit code is cleared")
        assertTrue(reply.startsWith("""{"ok":true,"service":{"name":"web","""), reply)
        assertTrue(""""state":"running","pid":101,"started":1000000,""" in reply, reply)
        assertTrue(host.changes > 0)
    }

    @Test
    fun `the pid is the process group's once the wrapper wrote it`() {
        write("[web]\ncommand = run\n")
        ask("service-start", "web")
        File(servicesDir, "web.pid").writeText("4242\n")

        assertTrue(""""pid":4242""" in ask("service-list"))
    }

    @Test
    fun `start refuses an unknown, running or failing service`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"no service named db (mynx service list shows them)"}""", ask("service-start", "db"))
        assertEquals("""{"ok":false,"error":"service-start needs a name"}""", ask("service-start"))
        ask("service-start", "web")
        assertEquals("""{"ok":false,"error":"web is already running"}""", ask("service-start", "web"))
        ask("service-stop", "web")
        assertEquals("""{"ok":false,"error":"web is stopping"}""", ask("service-start", "web"))
    }

    @Test
    fun `a start that fails says why`() {
        write("[web]\ncommand = run\n")
        host.startFails = "no rootfs"
        assertEquals("""{"ok":false,"error":"couldn't start web: no rootfs"}""", ask("service-start", "web"))
        assertEquals(ServiceState.FAILED, manager.services["web"]!!.state)
    }

    @Test
    fun `stop waits for the end, sending TERM to the group first`() {
        write("[web]\ncommand = run\nstop-timeout = 5\n")
        ask("service-start", "web")
        File(servicesDir, "web.pid").writeText("4242\n")

        assertEquals("(no answer yet)", ask("service-stop", "web"))
        assertTrue(waitFile().isFile)
        assertEquals("stop web#1 group=4242 timeout=5", host.events.last())
        assertTrue(""""state":"stopping"""" in ask("service-list"))

        File(servicesDir, "web.exit").writeText("143\n")
        File(dir, "q.reply").delete()
        assertTrue(manager.exited("web#1"))
        val reply = File(dir, "q.reply").readText()
        assertTrue(reply.startsWith("""{"ok":true,"service":{"name":"web""""), reply)
        assertTrue(""""state":"stopped","pid":null,"started":null,"exit_code":143""" in reply, reply)
        assertTrue(host.scheduled.isEmpty(), "never restarted")
    }

    @Test
    fun `stop refuses one that isn't running, and calls off a waiting restart`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"web isn't running"}""", ask("service-stop", "web"))

        ask("service-start", "web")
        manager.exited("web#1")
        assertEquals(1, host.scheduled.size)
        assertTrue(ask("service-stop", "web").startsWith("""{"ok":true"""))
        host.scheduled.single().second()
        assertEquals(listOf("start web saved=true"), host.events)
    }

    @Test
    fun `a failure restarts after the delay, from the exit code the wrapper left`() {
        write("[web]\ncommand = run\nrestart-delay = 2\n")
        ask("service-start", "web")
        File(servicesDir, "web.exit").writeText("1\n")

        assertTrue(manager.exited("web#1"))
        assertEquals(2000L, host.scheduled.single().first)
        assertTrue(""""state":"stopped"""" in ask("service-list"))

        host.scheduled.single().second()
        assertEquals(listOf("start web saved=true", "start web saved=true"), host.events)
        val list = ask("service-list")
        assertTrue(""""state":"running","pid":102,"started":1000000,"exit_code":1,"restarts":1""" in list, list)
    }

    @Test
    fun `a clean exit isn't restarted on-failure`() {
        write("[web]\ncommand = run\n")
        ask("service-start", "web")
        File(servicesDir, "web.exit").writeText("0\n")
        manager.exited("web#1")
        assertTrue(host.scheduled.isEmpty())
    }

    @Test
    fun `giving up tells the phone`() {
        write("[web]\ncommand = run\nrestart-limit = 1\n")
        ask("service-start", "web")
        File(servicesDir, "web.exit").writeText("1\n")
        manager.exited("web#1")
        host.scheduled.single().second()
        File(servicesDir, "web.exit").writeText("2\n")
        manager.exited("web#2")

        assertEquals("gave up web 2", host.events.last())
        assertTrue(""""state":"failed"""" in ask("service-list"))
    }

    @Test
    fun `a session that isn't a service's is left to the app`() {
        assertFalse(manager.exited("tab"))
    }

    @Test
    fun `restart stops it, then starts it again`() {
        write("[web]\ncommand = run\n")
        ask("service-start", "web")

        assertEquals("(no answer yet)", ask("service-restart", "web"))
        assertEquals("stop web#1 group=null timeout=10", host.events.last())
        manager.exited("web#1")

        assertEquals("start web saved=true", host.events.last())
        val reply = File(dir, "q.reply").readText()
        assertTrue(""""state":"running","pid":102""" in reply, reply)
        assertTrue(host.scheduled.isEmpty())
    }

    @Test
    fun `restart starts one that isn't running`() {
        write("[web]\ncommand = run\n")
        assertTrue(""""state":"running"""" in ask("service-restart", "web"))
    }

    @Test
    fun `the log is the end of the transcript`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"web hasn't run yet"}""", ask("service-log", "web"))
        ask("service-start", "web")
        host.transcripts["web#1"] = (1..150).joinToString("\n") { "line $it" } + "\n\n\n"

        val all = ask("service-log", "web")
        assertTrue(all.startsWith("""{"ok":true,"log":"line 51\nline 52"""), all)
        assertTrue(all.endsWith("""line 150"}"""), all)
        assertEquals("""{"ok":true,"log":"line 149\nline 150"}""", ask("service-log", "web", "2"))
        assertEquals(
            """{"ok":false,"error":"the number of lines must be a whole number from 1 to $MAX_LOG_LINES"}""",
            ask("service-log", "web", "0"),
        )
    }

    @Test
    fun `the log stays after it ends`() {
        write("[web]\ncommand = run\n")
        ask("service-start", "web")
        host.transcripts["web#1"] = "Traceback\n"
        manager.exited("web#1")
        assertEquals("""{"ok":true,"log":"Traceback"}""", ask("service-log", "web"))
    }

    @Test
    fun `open shows it as a tab`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"web hasn't run yet"}""", ask("service-open", "web"))
        ask("service-start", "web")
        assertEquals("""{"ok":true}""", ask("service-open", "web"))
        assertEquals("open web web#1", host.events.last())
        assertTrue(""""open":true""" in ask("service-list"))

        manager.setOpen("web", false)
        assertTrue(""""open":false""" in ask("service-list"))

        host.openFails = "the app isn't on screen"
        assertEquals("""{"ok":false,"error":"the app isn't on screen"}""", ask("service-open", "web"))
    }

    @Test
    fun `add writes a block, keeping the file`() {
        write("# mine\n[web]\ncommand = run\n")

        val reply = ask("service-add", "db", "command=postgres -c a=b", "cwd=/srv", "autostart=yes", "restart=always")

        assertTrue(reply.startsWith("""{"ok":true,"service":{"name":"db","saved":true,"command":"postgres -c a=b","cwd":"/srv","autostart":true,"restart":"always","state":"stopped""""), reply)
        assertEquals("# mine\n[web]\ncommand = run\n\n[db]\ncommand = postgres -c a=b\ncwd = /srv\nautostart = yes\nrestart = always\n", conf.readText())
        assertTrue(host.events.isEmpty(), "add doesn't start it")
    }

    @Test
    fun `add starts a new file`() {
        ask("service-add", "web", "command=run")
        assertEquals(SERVICES_HEADER + "\n[web]\ncommand = run\n", conf.readText())
    }

    @Test
    fun `add refuses what it can't write`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"there's already a service named web"}""", ask("service-add", "web", "command=x"))
        assertEquals("""{"ok":false,"error":"service-add needs command=…"}""", ask("service-add", "db"))
        assertEquals("""{"ok":false,"error":"${badServiceName("Db")}"}""", ask("service-add", "Db", "command=x"))
        assertEquals("""{"ok":false,"error":"unknown key 'colour'"}""", ask("service-add", "db", "command=x", "colour=blue"))
        assertEquals("""{"ok":false,"error":"expected KEY=VALUE: oops"}""", ask("service-add", "db", "command=x", "oops"))
        assertEquals("""{"ok":false,"error":"autostart must be yes or no"}""", ask("service-add", "db", "command=x", "autostart=maybe"))
        assertEquals("[web]\ncommand = run\n", conf.readText())
    }

    @Test
    fun `add is an undo step`() {
        requests.start()
        ask("service-add", "web", "command=run")
        assertEquals("""{"ok":true,"undone":"service add web"}""", ask("undo"))
        assertFalse(conf.exists())
    }

    @Test
    fun `remove takes out a stopped one's block`() {
        write("# mine\n[web]\ncommand = run\n\n[db]\ncommand = postgres\n")
        assertEquals("""{"ok":true}""", ask("service-remove", "db"))
        assertEquals("# mine\n[web]\ncommand = run\n", conf.readText())
        assertEquals(listOf("web"), manager.services.all.map { it.name })
        assertEquals("""{"ok":false,"error":"no service named db (mynx service list shows them)"}""", ask("service-remove", "db"))
    }

    @Test
    fun `remove stops a running one first, and answers once it's gone`() {
        write("[web]\ncommand = run\nrestart = always\n")
        ask("service-start", "web")

        assertEquals("(no answer yet)", ask("service-remove", "web"))
        assertFalse(conf.readText().contains("[web]"))
        assertEquals("stop web#1 group=null timeout=10", host.events.last())

        manager.exited("web#1")
        assertEquals("""{"ok":true}""", File(dir, "q.reply").readText())
        assertTrue(host.scheduled.isEmpty())
        assertEquals("""{"ok":true,"services":[]}""", ask("service-list"))
    }

    @Test
    fun `adopt turns a tab into an unsaved service`() {
        write("[web]\ncommand = run\n")
        host.tabs[3] = AdoptedTab("tab3", pid = 77, command = "vim notes.txt", cwd = "~/notes", open = true)

        val reply = ask("service-adopt", "notes", "3")

        assertEquals(
            """{"ok":true,"service":{"name":"notes","saved":false,"command":"vim notes.txt","cwd":"~/notes","autostart":false,""" +
                """"restart":"no","state":"running","pid":77,"started":1000000,"exit_code":null,"restarts":0,"open":true}}""",
            reply,
        )
        assertEquals("[web]\ncommand = run\n", conf.readText(), "not saved")
        assertTrue(manager.exited("tab3"))
        assertTrue(host.scheduled.isEmpty())
    }

    @Test
    fun `an adopted idle shell runs bash`() {
        host.tabs[1] = AdoptedTab("tab1", pid = 5, command = null, cwd = "~", open = false)
        assertTrue(""""command":"bash"""" in ask("service-adopt", "shell", "1"))
    }

    @Test
    fun `adopt refuses a taken name, a bad shell or a tab the app can't give`() {
        write("[web]\ncommand = run\n")
        host.tabs[1] = AdoptedTab("tab1", 5, "x", "~", false)
        assertEquals("""{"ok":false,"error":"there's already a service named web"}""", ask("service-adopt", "web", "1"))
        assertEquals("""{"ok":false,"error":"service-adopt needs a name and a tab's MYNX_SHELL number"}""", ask("service-adopt", "x", "one"))
        assertEquals("""{"ok":false,"error":"no tab with shell 9"}""", ask("service-adopt", "x", "9"))
        assertEquals("""{"ok":false,"error":"${badServiceName("X")}"}""", ask("service-adopt", "X", "1"))
    }

    @Test
    fun `save writes an adopted one from what runs in it now`() {
        write("# mine\n")
        host.tabs[3] = AdoptedTab("tab3", 77, "vim", "~", open = true)
        ask("service-adopt", "notes", "3")
        host.foreground = Foreground(command = "python3 -m http.server 9000", cwd = "/srv")

        val reply = ask("service-save", "notes")

        assertTrue(""""saved":true,"command":"python3 -m http.server 9000","cwd":"/srv","autostart":false,"restart":"on-failure","state":"running"""" in reply, reply)
        assertEquals("# mine\n\n[notes]\ncommand = python3 -m http.server 9000\ncwd = /srv\n", conf.readText())
        assertTrue(manager.services["notes"]!!.saved)
    }

    @Test
    fun `save takes the command and keys given`() {
        host.tabs[3] = AdoptedTab("tab3", 77, "vim", "~", open = true)
        ask("service-adopt", "notes", "3")

        ask("service-save", "notes", "command=vim -R notes", "autostart=yes")

        assertEquals(SERVICES_HEADER + "\n[notes]\ncommand = vim -R notes\nautostart = yes\n", conf.readText())
    }

    @Test
    fun `save refuses a saved or unknown one`() {
        write("[web]\ncommand = run\n")
        assertEquals("""{"ok":false,"error":"web is already in ~/.config/mynx/services.conf"}""", ask("service-save", "web"))
        assertEquals("""{"ok":false,"error":"no service named db (mynx service list shows them)"}""", ask("service-save", "db"))
    }

    @Test
    fun `remove of an adopted one stops it and forgets it`() {
        host.tabs[3] = AdoptedTab("tab3", 77, "vim", "~", open = true)
        ask("service-adopt", "notes", "3")
        assertEquals("(no answer yet)", ask("service-remove", "notes"))
        manager.exited("tab3")
        assertEquals("""{"ok":true,"services":[]}""", ask("service-list"))
    }

    @Test
    fun `at app start, the autostart ones and those that were running start`() {
        write("[web]\ncommand = run\n[db]\ncommand = pg\nautostart = yes\n[ssh]\ncommand = sshd\n")

        manager.launch(parseSavedServices(SavedServices(listOf("ssh")).serialize()))

        assertEquals(listOf("start db saved=true", "start ssh saved=true"), host.events)
        assertEquals(SavedServices(listOf("db", "ssh")), parseSavedServices(manager.stateText()))
    }

    @Test
    fun `stopAll stops every running one and calls off waiting restarts`() {
        write("[web]\ncommand = run\n[db]\ncommand = pg\nstop-timeout = 3\n")
        ask("service-start", "web")
        ask("service-start", "db")
        File(servicesDir, "db.pid").writeText("77\n")
        File(servicesDir, "web.exit").writeText("1\n")
        manager.exited("web#1")
        assertEquals(1, host.scheduled.size)
        assertEquals(2, manager.active)

        manager.stopAll()

        assertEquals("stop db#2 group=77 timeout=3", host.events.last())
        host.scheduled.single().second()
        assertEquals(2, host.events.count { it.startsWith("start") }, "the waiting restart is off")
        manager.exited("db#2")
        assertEquals(ServiceState.STOPPED, manager.services["db"]!!.state)
        assertTrue(host.scheduled.size == 1, "never restarted")
        assertEquals(0, manager.active)
    }

    @Test
    fun `active counts the running ones and those waiting to restart`() {
        write("[web]\ncommand = run\n[db]\ncommand = pg\n")
        assertEquals(0, manager.active)
        ask("service-start", "web")
        assertEquals(1, manager.active)
        File(servicesDir, "web.exit").writeText("0\n")
        manager.exited("web#1")
        assertEquals(0, manager.active)
    }

    @Test
    fun `nothing to start with no file`() {
        manager.launch(null)
        assertTrue(host.events.isEmpty())
        assertNull(manager.services["web"])
    }

    @Test
    fun `every new session is reported, so an open tab can follow it`() {
        write("[web]\ncommand = run\n")
        ask("service-start", "web")
        File(servicesDir, "web.exit").writeText("1\n")
        manager.exited("web#1")
        host.scheduled.single().second()

        assertEquals(listOf("web web#1", "web web#2"), host.followed)
    }

    @Test
    fun `a tab's title says how its service is`() {
        write("[web]\ncommand = run\nrestart-limit = 1\n")
        manager.reload()
        assertEquals("○ web: not running", manager.tabTitle("web"))
        ask("service-start", "web")
        assertNull(manager.tabTitle("web"), "running: the plain mark")
        File(servicesDir, "web.exit").writeText("1\n")
        manager.exited("web#1")
        assertEquals("○ web: restarting", manager.tabTitle("web"))
        host.scheduled.single().second()
        File(servicesDir, "web.exit").writeText("1\n")
        manager.exited("web#2")
        assertEquals("○ web: failed (exit 1)", manager.tabTitle("web"))
        manager.start("web")
        ask("service-stop", "web")
        assertEquals("● web: stopping", manager.tabTitle("web"))
        File(servicesDir, "web.exit").writeText("143\n")
        manager.exited("web#3")
        assertEquals("○ web: stopped", manager.tabTitle("web"))
        assertNull(manager.tabTitle("db"))
    }

    @Test
    fun `an ended service's title has its exit code`() {
        write("[web]\ncommand = run\nrestart = no\n")
        ask("service-start", "web")
        File(servicesDir, "web.exit").writeText("3\n")
        manager.exited("web#1")
        assertEquals("○ web: exit 3", manager.tabTitle("web"))
    }

    @Test
    fun `the app stops, restarts and starts services by name, and hears why not`() {
        write("[web]\ncommand = run\n")
        assertEquals("web isn't running", manager.stop("web"))
        assertEquals("no service named db (mynx service list shows them)", manager.stop("db"))
        assertNull(manager.start("web"))
        assertEquals("web is already running", manager.start("web"))
        assertNull(manager.restart("web"))
        assertEquals("stop web#1 group=null timeout=10", host.events.last())
        manager.exited("web#1")
        assertEquals("start web saved=true", host.events.last())
        assertNull(manager.stop("web"))
        assertEquals(ServiceState.STOPPING, manager.services["web"]!!.state)
    }

    @Test
    fun `the app adopts a tab by its shell number`() {
        host.tabs[3] = AdoptedTab("tab3", 77, "vim", "~", open = true)
        assertNull(manager.adopt("notes", 3))
        assertEquals(listOf("notes tab3"), host.adopted)
        assertEquals("there's already a service named notes", manager.adopt("notes", 4))
        assertEquals("no tab with shell 4", manager.adopt("other", 4))
    }

    @Test
    fun `a tab adopted from inside runs mynx then, and that isn't its command`() {
        host.tabs[3] = AdoptedTab("tab3", 77, "mynx service adopt notes", "~", open = true)
        assertTrue(""""command":"bash"""" in ask("service-adopt", "notes", "3"))
        host.tabs[4] = AdoptedTab("tab4", 78, "/usr/bin/python3 /opt/mynx/bin/mynx service adopt b", "~", open = true)
        assertTrue(""""command":"bash"""" in ask("service-adopt", "b", "4"))
        host.tabs[5] = AdoptedTab("tab5", 79, "python3 -m http.server", "~", open = true)
        assertTrue(""""command":"python3 -m http.server"""" in ask("service-adopt", "c", "5"))
    }

    @Test
    fun `an adopted one restarts with what runs in it now`() {
        host.tabs[3] = AdoptedTab("tab3", 77, null, "~/src", open = true)
        ask("service-adopt", "dev", "3")
        host.foreground = Foreground(command = "npm run dev", cwd = "~/src/app")

        ask("service-restart", "dev")
        manager.exited("tab3")

        assertEquals("start dev saved=false", host.events.last())
        assertEquals(ServiceDef("dev", "npm run dev", "~/src/app", restart = Restart.NO), manager.services["dev"]!!.def)
    }
}
