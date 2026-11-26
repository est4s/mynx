package io.github.est4s.terminal.core

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A [ReplySink] for any thread. */
private class Answers : ReplySink {
    val lines = java.util.Collections.synchronizedList(mutableListOf<Map<*, *>>())
    @Volatile var answer: Map<*, *>? = null

    override fun waiting(seconds: Int?) {}
    override fun line(json: String) {
        lines += parseJson(json) as Map<*, *>
    }
    override fun answer(json: String) {
        answer = parseJson(json) as Map<*, *>
    }
    override fun discard() {}
}

private fun waitFor(what: String, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!check()) {
        if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
        Thread.sleep(10)
    }
}

class ScreenLinkTest {
    private val root = Files.createTempDirectory("link").toFile()
    private val port = ServerSocket(0).use { it.localPort }

    private class Platform(val info: ScreenMessage.ScreenInfo) : ScreenPlatform {
        @Volatile var ad: ScreenAd? = null
        @Volatile var adPort = 0
        var hosts: () -> List<FoundScreenHost> = { emptyList() }

        override fun screen() = info
        override fun advertise(ad: ScreenAd?, port: Int) {
            this.ad = ad
            adPort = port
        }
        override fun discover(ms: Long, found: (List<FoundScreenHost>) -> Unit) {
            Thread { found(hosts()) }.start()
        }
    }

    private val hostPlatform = Platform(ScreenMessage.ScreenInfo(1080, 2424, 420, 0))
    private val screenPlatform = Platform(ScreenMessage.ScreenInfo(1080, 2400, 480, 0))
    private val hostSettings = File(root, "a/settings.conf").also { it.parentFile.mkdirs(); it.writeText("screen-port = $port\n") }
    private val screenSettings = File(root, "b/settings.conf").also { it.parentFile.mkdirs(); it.writeText("screen-port = $port\n") }

    private fun newHost() = ScreenLink(File(root, "a/private"), hostSettings, { "Pixel 10" }, hostPlatform, keepAliveMs = 100, idleMs = 10_000)
    private val host = newHost()
    private val screen = ScreenLink(File(root, "b/private"), screenSettings, { "Nord 2" }, screenPlatform, keepAliveMs = 100, idleMs = 10_000)

    init {
        // The screen finds the host as Android's NSD would, from what it advertises.
        screenPlatform.hosts = {
            listOfNotNull(hostPlatform.ad?.let { foundScreenHost(it.name, it.txt, "127.0.0.1", hostPlatform.adPort) })
        }
    }

    @AfterTest
    fun cleanUp() {
        host.stop()
        screen.stop()
        root.deleteRecursively()
    }

    private fun reply(sink: Answers) = PendingReply(sink, "t") {}

    private fun status(link: ScreenLink) = parseJson(ok(*link.status())) as Map<*, *>

    private fun connected(link: ScreenLink) = status(link)["connected"] as Map<*, *>?

    private fun code(sink: Answers, index: Int = 0): String {
        waitFor("a code") { sink.lines.size > index }
        return sink.lines[index]["code"] as String
    }

    private fun pair(): Pair<Answers, Answers> {
        val shown = Answers()
        host.pair(reply(shown))
        val joined = Answers()
        screen.join(code(shown), null, reply(joined))
        waitFor("pairing") { joined.answer != null && shown.answer != null }
        return shown to joined
    }

    @Test
    fun `nothing listens until a pairing waits`() {
        host.refresh()
        assertNull(status(host)["listening"])
        assertNull(hostPlatform.ad)
        val shown = Answers()
        host.pair(reply(shown))
        assertEquals(port.toString(), status(host)["listening"].toString())
        assertEquals("1", hostPlatform.ad?.txt?.get("pairing"))
        assertTrue(Regex("[0-9]{3} [0-9]{3}").matches(code(shown)))
    }

    @Test
    fun `the screen pairs with the code the host shows, found over mDNS`() {
        val (shown, joined) = pair()
        assertEquals(true, joined.answer!!["ok"], joined.answer.toString())
        assertEquals("Pixel 10", (joined.answer!!["paired"] as Map<*, *>)["name"])
        assertEquals("Nord 2", (shown.answer!!["paired"] as Map<*, *>)["name"])
        waitFor("both linked") { connected(host) != null && connected(screen) != null }
        assertEquals("screen", connected(host)!!["role"])
        assertEquals("host", connected(screen)!!["role"])
        // Each knows the other's screen, and the screen knows where it sits.
        waitFor("screen info") { connected(host)!!["screen"] != null && connected(screen)!!["place"] != null }
        assertEquals("480", (connected(host)!!["screen"] as Map<*, *>)["density"].toString())
        assertEquals("right", connected(screen)!!["place"])
        // The pairing is over: the host advertises that, and still listens for its screen.
        assertEquals("0", hostPlatform.ad?.txt?.get("pairing"))
        assertEquals(listOf("Nord 2"), host.pairs.all().map { it.name })
        assertEquals(listOf(ScreenRole.HOST), screen.pairs.all().map { it.role })
    }

    @Test
    fun `a wrong code fails, and the host shows a new one that works`() {
        val shown = Answers()
        host.pair(reply(shown))
        val first = code(shown)
        val wrong = Answers()
        screen.join(if (first == "000 000") "111111" else "000000", null, reply(wrong))
        waitFor("the refusal") { wrong.answer != null }
        assertEquals(false, wrong.answer!!["ok"])
        assertTrue((wrong.answer!!["error"] as String).startsWith("wrong code"), wrong.answer.toString())
        val second = code(shown, 1)
        assertEquals("1", shown.lines[1]["wrong"].toString())
        // The first code took its one try.
        val again = Answers()
        screen.join(first, null, reply(again))
        waitFor("the second refusal") { again.answer != null }
        assertEquals(false, again.answer!!["ok"])
        val right = Answers()
        screen.join(code(shown, 2), null, reply(right))
        waitFor("pairing") { right.answer != null }
        assertEquals(true, right.answer!!["ok"], "$second, ${right.answer}")
    }

    @Test
    fun `three wrong codes stop the pairing`() {
        val shown = Answers()
        host.pair(reply(shown))
        repeat(MAX_WRONG_CODES) { i ->
            val current = code(shown, i).replace(" ", "")
            val wrong = Answers()
            screen.join(if (current == "000000") "111111" else "000000", null, reply(wrong))
            waitFor("refusal $i") { wrong.answer != null }
        }
        waitFor("the end") { shown.answer != null }
        assertEquals(false, shown.answer!!["ok"])
        assertTrue((shown.answer!!["error"] as String).startsWith("3 wrong codes"))
        assertNull(status(host)["pairing"])
    }

    @Test
    fun `a screen asking a host that isn't pairing is told to start it`() {
        val joined = Answers()
        screen.join("123456", "127.0.0.1:$port", reply(joined))
        waitFor("the answer") { joined.answer != null }
        assertTrue((joined.answer!!["error"] as String).startsWith("couldn't reach"), joined.answer.toString())
        val hostAlone = Answers()
        screen.join("123456", null, reply(hostAlone))
        waitFor("the answer") { hostAlone.answer != null }
        assertTrue((hostAlone.answer!!["error"] as String).startsWith("no phone is pairing"))
    }

    @Test
    fun `after a disconnect the screen comes back without a code`() {
        pair()
        waitFor("linked") { connected(screen) != null }
        assertTrue(screen.disconnect())
        waitFor("both down") { connected(host) == null && connected(screen) == null }
        assertTrue((status(host)["lastEnd"] as String).startsWith("Nord 2 ended the link"))
        val back = Answers()
        screen.connect(null, reply(back))
        waitFor("connected") { back.answer != null }
        assertEquals(true, back.answer!!["ok"], back.answer.toString())
        waitFor("linked again") { connected(host) != null }
    }

    @Test
    fun `a host that stops says so before it goes, from the link's own thread`() {
        pair()
        waitFor("linked") { connected(screen) != null }
        // Android's main thread mustn't touch the network: stop only queues the Bye.
        host.stop()
        waitFor("the screen heard") { connected(screen) == null }
        assertEquals("Pixel 10 ended the link (the app stopped)", status(screen)["lastEnd"])
    }

    @Test
    fun `a restarted host still knows its screen`() {
        pair()
        host.stop()
        waitFor("down") { connected(screen) == null }
        val again = newHost()
        try {
            again.refresh()
            val back = Answers()
            screen.connect("Pixel 10", reply(back))
            waitFor("connected") { back.answer != null }
            assertEquals(true, back.answer!!["ok"], back.answer.toString())
        } finally {
            again.stop()
        }
    }

    @Test
    fun `unpairing on one phone forgets the pair on both`() {
        pair()
        waitFor("linked") { connected(screen) != null }
        assertEquals(listOf("Nord 2"), host.unpair("nord 2"))
        waitFor("screen forgot") { screen.pairs.all().isEmpty() }
        assertTrue(host.pairs.all().isEmpty())
        assertNull(status(host)["listening"])
        val back = Answers()
        screen.connect(null, reply(back))
        waitFor("refusal") { back.answer != null }
        assertTrue((back.answer!!["error"] as String).contains("isn't paired with a host"))
    }

    @Test
    fun `a screen the host forgot while away is told to pair again`() {
        pair()
        screen.disconnect()
        waitFor("down") { connected(host) == null }
        // Forgotten while unlinked: the screen still has its key.
        host.pairs.forget(host.pairs.all().single().id)
        host.pair(reply(Answers()))
        val back = Answers()
        screen.connect(null, reply(back))
        waitFor("refusal") { back.answer != null }
        assertTrue((back.answer!!["error"] as String).contains("pair again"), back.answer.toString())
    }

    @Test
    fun `placing on the host tells the screen, placing on the screen asks the host`() {
        pair()
        waitFor("linked") { connected(screen)?.get("place") == "right" }
        assertEquals(false, host.place(ScreenSide.LEFT))
        assertTrue("screen-place = left" in hostSettings.readText())
        waitFor("the screen knows") { connected(screen)?.get("place") == "left" }
        assertEquals(true, screen.place(ScreenSide.ABOVE))
        waitFor("the host took it") { "screen-place = above" in hostSettings.readText() }
        waitFor("and told the screen") { connected(screen)?.get("place") == "above" }
        assertTrue("screen-place" !in screenSettings.readText())
    }

    @Test
    fun `screen-host off refuses pairing and doesn't listen`() {
        hostSettings.appendText("screen-host = off\n")
        val shown = Answers()
        host.pair(reply(shown))
        assertTrue((shown.answer!!["error"] as String).contains("screen-host is off"))
        assertNull(status(host)["listening"])
    }

    @Test
    fun `requests answer through the link`() {
        val requests = screenRequests(screen)
        val sink = Answers()
        requests.getValue("screen-status").start(emptyList(), reply(sink))
        assertEquals("Nord 2", sink.answer!!["name"])
        val bad = Answers()
        requests.getValue("screen-place").start(listOf("behind"), reply(bad))
        assertEquals("place is left, right, above or below", bad.answer!!["error"])
        val none = Answers()
        requests.getValue("screen-unpair").start(listOf("Galaxy"), reply(none))
        assertNotNull(none.answer!!["error"])
        assertNull(requests.getValue("screen-pair").seconds)
    }

    /** What a link hands to screens: every message and the link's end, by name. */
    private class Media : ScreenMedia {
        val heard = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun received(message: ScreenMessage) { heard += message.javaClass.simpleName }
        override fun unlinked() { heard += "unlinked" }
    }

    @Test
    fun `screens' messages go over the link both ways, and the peer is known`() {
        val hostMedia = Media()
        val screenMedia = Media()
        host.media = hostMedia
        screen.media = screenMedia
        assertNull(host.peer())
        assertEquals(false, host.send(ScreenMessage.StreamEnd(1)))
        pair()
        waitFor("both linked, the screen's size known") { host.peer()?.screen != null && screen.peer() != null }
        assertEquals(LinkedPeer("Nord 2", ScreenRole.HOST, ScreenMessage.ScreenInfo(1080, 2400, 480, 0)), host.peer())
        assertEquals(ScreenRole.SCREEN, screen.peer()!!.role)
        assertEquals(true, host.send(ScreenMessage.StreamStart(1, "Pixel Hero", 1080, 2400)))
        assertEquals(true, host.send(ScreenMessage.Video(1, VIDEO_KEY, 5, ByteArray(100_000))))
        assertEquals(true, screen.send(ScreenMessage.Watch(1, true)))
        assertEquals(true, screen.send(ScreenMessage.Touch(1, ACTION_DOWN, 0, listOf(TouchPoint(0, 1f, 2f)))))
        waitFor("the screen heard") { screenMedia.heard.containsAll(listOf("StreamStart", "Video")) }
        waitFor("the host heard") { hostMedia.heard.containsAll(listOf("Watch", "Touch")) }
        // The link's own messages stay the link's.
        assertTrue("KeepAlive" !in screenMedia.heard && "ScreenInfo" !in screenMedia.heard)
        screen.disconnect()
        waitFor("both told") { "unlinked" in hostMedia.heard && "unlinked" in screenMedia.heard }
        assertNull(host.peer())
    }

    @Test
    fun `a screen sent by request reaches the other phone, and the status lists it`() {
        val casting = FakeCasting()
        val showing = FakeShowing()
        val hostShare = ScreenShare(host::send, host::peer, casting, FakeShowing(), hostSettings, post = { it() })
        val screenShare = ScreenShare(screen::send, screen::peer, FakeCasting(), showing, screenSettings, post = { it() })
        host.media = hostShare
        screen.media = screenShare
        val requests = screenRequests(host, hostShare)
        val early = Answers()
        requests.getValue("screen-send").start(listOf("app", "pixel-hero"), reply(early))
        assertTrue((early.answer!!["error"] as String).startsWith("no screen is linked"))
        pair()
        waitFor("the screen's size") { host.peer()?.screen != null }
        val sink = Answers()
        requests.getValue("screen-send").start(listOf("app", "pixel-hero"), reply(sink))
        assertEquals(true, sink.answer!!["ok"], sink.answer.toString())
        assertEquals("Pixel Hero", (sink.answer!!["screen"] as Map<*, *>)["title"])
        waitFor("the screen heard") { showing.did.isNotEmpty() }
        assertEquals("started 1 Pixel Hero 1080x2400", showing.did.single())
        val status = Answers()
        requests.getValue("screen-status").start(emptyList(), reply(status))
        assertEquals("Pixel Hero", ((status.answer!!["sent"] as List<*>).single() as Map<*, *>)["title"])
        val bad = Answers()
        requests.getValue("screen-send").start(listOf("window", "1"), reply(bad))
        assertEquals("send what? mynx screen send app NAME, or tab [N|new]", bad.answer!!["error"])
        val tab = Answers()
        requests.getValue("screen-send").start(listOf("tab", "1"), reply(tab))
        assertEquals(true, tab.answer!!["ok"], tab.answer.toString())
        assertEquals("bash", (tab.answer!!["screen"] as Map<*, *>)["title"])
        assertEquals("terminal", (tab.answer!!["screen"] as Map<*, *>)["kind"])
        waitFor("the terminal's state there") { "state 2 56x40" in showing.did }
        assertEquals(listOf("started 1 Pixel Hero 1080x2400", "term 2 bash 56x40", "state 2 56x40"), showing.did)
        val took = Answers()
        requests.getValue("screen-take").start(listOf("all"), reply(took))
        assertEquals(listOf("Pixel Hero", "bash"), (took.answer!!["taken"] as List<*>).map { (it as Map<*, *>)["title"] })
        waitFor("the screen let them go") { "ended 1" in showing.did && "term ended 2" in showing.did }
        val none = Answers()
        requests.getValue("screen-take").start(listOf("all"), reply(none))
        assertEquals("nothing sent to take back", none.answer!!["error"])
    }
}
