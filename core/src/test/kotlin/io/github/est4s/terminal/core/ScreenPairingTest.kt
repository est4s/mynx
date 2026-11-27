package io.github.est4s.terminal.core

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun waitUntil(what: String, check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!check()) {
        if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
        Thread.sleep(10)
    }
}

/** Runs the `screen-*` requests as the app's [MynxRequests.call] does, for [ScreenPairing]. */
private fun caller(link: ScreenLink): (String, List<String>, ReplySink) -> Call {
    val handlers = screenRequests(link)
    return { name, args, sink ->
        val reply = PendingReply(sink, "t") {}
        handlers.getValue(name).start(args, reply)
        object : Call {
            override val seconds: Int? = null
            override val done get() = reply.answered
            override fun stop() = reply.cancel(listening = true)
            override fun cancel() = reply.cancel(listening = false)
        }
    }
}

/** Settings' pairing on both phones, over real links on loopback. */
class ScreenPairingTest {
    private val root = Files.createTempDirectory("pairing").toFile()
    private val port = ServerSocket(0).use { it.localPort }

    private class Platform(val info: ScreenMessage.ScreenInfo) : ScreenPlatform {
        @Volatile var ad: ScreenAd? = null
        @Volatile var adPort = 0
        var hosts: () -> List<FoundScreenHost> = { emptyList() }
        @Volatile var rounds = 0

        override fun screen() = info
        override fun advertise(ad: ScreenAd?, port: Int) {
            this.ad = ad
            adPort = port
        }
        override fun discover(ms: Long, found: (List<FoundScreenHost>) -> Unit) {
            rounds++
            Thread { Thread.sleep(ms); found(hosts()) }.start()
        }
    }

    private val hostPlatform = Platform(ScreenMessage.ScreenInfo(1080, 2424, 420, 0))
    private val screenPlatform = Platform(ScreenMessage.ScreenInfo(1080, 2400, 480, 0))
    private fun settingsFile(dir: String) = File(root, "$dir/settings.conf").also { it.parentFile.mkdirs(); it.writeText("screen-port = $port\n") }
    private val host = ScreenLink(File(root, "a/private"), settingsFile("a"), { "Pixel 10" }, hostPlatform, keepAliveMs = 100, idleMs = 10_000, discoverMs = 20)
    private val screen = ScreenLink(File(root, "b/private"), settingsFile("b"), { "Nord 2" }, screenPlatform, keepAliveMs = 100, idleMs = 10_000, discoverMs = 20)
    @Volatile private var hostChanges = 0
    @Volatile private var screenChanges = 0
    private val hostFlow = ScreenPairing(host, caller(host), { hostChanges++ })
    private val screenFlow = ScreenPairing(screen, caller(screen), { screenChanges++ }, searchRounds = 3)

    init {
        screenPlatform.hosts = {
            listOfNotNull(hostPlatform.ad?.let { foundScreenHost(it.name, it.txt, "127.0.0.1", hostPlatform.adPort) })
        }
    }

    @AfterTest
    fun cleanUp() {
        host.stop()
        screen.stop()
        deleteTree(root)
    }

    private fun waiting(): HostPairing.Waiting {
        waitUntil("a code") { hostFlow.shown().host is HostPairing.Waiting }
        return hostFlow.shown().host as HostPairing.Waiting
    }

    private fun pairBoth(): String {
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        val code = waiting().code
        assertNull(screenFlow.act("screen-pair-join", listOf(code.replace(" ", ""), "127.0.0.1:$port", "Pixel 10")))
        waitUntil("both paired") { screenFlow.shown().join is JoinPairing.Paired && hostFlow.shown().host is HostPairing.Paired }
        waitUntil("both linked") { host.state().linkedName != null && screen.state().linkedName != null }
        return code
    }

    @Test
    fun `the host shows its code, name and address, and says when a screen paired`() {
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        val shown = waiting()
        assertTrue(Regex("[0-9]{3} [0-9]{3}").matches(shown.code))
        assertEquals("Pixel 10", shown.name)
        assertEquals(0, shown.wrong)
        assertTrue(shown.addresses.all { it.endsWith(":$port") }, shown.addresses.toString())
        assertTrue(hostChanges > 0)
        val page = hostFlow.items(SCREEN_PAIR_HOST_MENU)
        assertEquals(HomeItem(shown.code, HomeAction.None, kind = HomeRow.CODE), page.first { it.kind == HomeRow.CODE })
        assertEquals("Pixel 10", page.first { it.label == "This phone" }.detail)
        assertEquals(HomeAction.Config("screen-pair-cancel", emptyList(), back = true), page.first { it.label == "Cancel" }.action)
        // Opening the page again keeps the code: a second start doesn't make a new one.
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        assertEquals(shown.code, (hostFlow.shown().host as HostPairing.Waiting).code)

        assertNull(screenFlow.act("screen-pair-join", listOf(shown.code, "127.0.0.1:$port", "Pixel 10")))
        waitUntil("paired") { hostFlow.shown().host is HostPairing.Paired && screenFlow.shown().join is JoinPairing.Paired }
        assertEquals(HostPairing.Paired("Nord 2"), hostFlow.shown().host)
        assertEquals(JoinPairing.Paired("Pixel 10"), screenFlow.shown().join)
        assertTrue(hostFlow.items(SCREEN_PAIR_HOST_MENU).any { it.label == "Paired with Nord 2, and linked." })
        assertNull(hostFlow.act("screen-pair-done", emptyList()))
        assertEquals(HostPairing.Idle, hostFlow.shown().host)
    }

    @Test
    fun `the screen finds the pairing host on the Wi-Fi, in rounds that stop`() {
        assertNull(screenFlow.act("screen-pair-find", emptyList()))
        assertTrue(screenFlow.shown().searching)
        waitUntil("the search to end") { !screenFlow.shown().searching }
        assertEquals(3, screenPlatform.rounds)
        assertEquals(emptyList(), screenFlow.shown().found)
        assertEquals("None found yet.", screenFlow.items(SCREEN_PAIR_JOIN_MENU).first { it.label.startsWith("None") }.label)

        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        waiting()
        assertNull(screenFlow.act("screen-pair-search", emptyList()))
        waitUntil("the host found") { screenFlow.shown().found.isNotEmpty() }
        val found = screenFlow.shown().found.single()
        assertEquals("Pixel 10", found.name)
        val row = screenFlow.items(SCREEN_PAIR_JOIN_MENU).first { it.label == "Pixel 10" }
        assertEquals("127.0.0.1:$port", row.detail)
        val input = assertIs<HomeAction.Input>(row.action)
        assertTrue(input.numeric)
        assertEquals("Pair", input.button)
        assertEquals(
            HomeAction.Config("screen-pair-join", listOf("123456", "127.0.0.1:$port", "Pixel 10"), back = true),
            homeInputAction(input.target, " 123456 "),
        )
    }

    @Test
    fun `a wrong code fails on the screen, and the host shows a new one`() {
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        val first = waiting().code
        val wrong = if (first == "000 000") "111111" else "000000"
        assertNull(screenFlow.act("screen-pair-join", listOf(wrong, "127.0.0.1:$port", "Pixel 10")))
        waitUntil("the failure") { screenFlow.shown().join is JoinPairing.Failed }
        val why = (screenFlow.shown().join as JoinPairing.Failed).why
        assertTrue(why.startsWith("Wrong code."), why)
        assertEquals(HomeItem(why, HomeAction.None, kind = HomeRow.ERROR), screenFlow.items(SCREEN_PAIR_JOIN_MENU).first())
        waitUntil("a new code") { (hostFlow.shown().host as? HostPairing.Waiting)?.wrong == 1 }
        assertTrue(hostFlow.items(SCREEN_PAIR_HOST_MENU).any { it.label == "Wrong code (1 of 3): this is a new one." })
        // The new code works.
        val second = (hostFlow.shown().host as HostPairing.Waiting).code
        assertNull(screenFlow.act("screen-pair-join", listOf(second, "127.0.0.1:$port", "Pixel 10")))
        waitUntil("paired") { screenFlow.shown().join is JoinPairing.Paired }
    }

    @Test
    fun `a code that isn't six digits is refused before trying`() {
        assertEquals("The code is six digits.", screenFlow.act("screen-pair-join", listOf("12a", "127.0.0.1:$port", "Pixel 10")))
        assertEquals(JoinPairing.Idle, screenFlow.shown().join)
    }

    @Test
    fun `a typed address is checked and listed to pair with`() {
        assertEquals(
            "Not an address: one two. Type the address the host shows under its code, like 192.168.1.20.",
            screenFlow.act("screen-pair-address", listOf("one two")),
        )
        assertNull(screenFlow.act("screen-pair-address", listOf(" 192.168.1.20 ")))
        assertEquals("192.168.1.20", screenFlow.shown().typed)
        val row = screenFlow.items(SCREEN_PAIR_JOIN_MENU).first { it.label == "At 192.168.1.20" }
        assertEquals("screen-join:192.168.1.20 192.168.1.20", (row.action as HomeAction.Input).target)
    }

    @Test
    fun `nobody answering is said plainly`() {
        val closed = ServerSocket(0).use { it.localPort }
        assertNull(screenFlow.act("screen-pair-join", listOf("123456", "127.0.0.1:$closed", "Pixel 10")))
        waitUntil("the failure") { screenFlow.shown().join is JoinPairing.Failed }
        val why = (screenFlow.shown().join as JoinPairing.Failed).why
        assertTrue(why.startsWith("Couldn't reach 127.0.0.1:$closed."), why)
    }

    @Test
    fun `cancelling stops the host's pairing`() {
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        waiting()
        assertNull(hostFlow.act("screen-pair-cancel", emptyList()))
        assertEquals(HostPairing.Idle, hostFlow.shown().host)
        waitUntil("the link to stop pairing") { host.state().pairingCode == null }
    }

    @Test
    fun `pairing refused at once shows why on the page`() {
        File(root, "a/settings.conf").writeText("screen-port = $port\nscreen-host = off\n")
        assertNull(hostFlow.act("screen-pair-host", emptyList()))
        val stopped = assertIs<HostPairing.Stopped>(hostFlow.shown().host)
        assertEquals("This phone doesn't take screens: set “Take paired screens” to While paired, below.", stopped.why)
        assertEquals(HomeItem(stopped.why, HomeAction.None, kind = HomeRow.ERROR), hostFlow.items(SCREEN_PAIR_HOST_MENU).first())
        assertEquals(HomeAction.Config("screen-pair-host", emptyList()), hostFlow.items(SCREEN_PAIR_HOST_MENU).first { it.label == "Try again" }.action)
    }

    @Test
    fun `paired phones disconnect, connect again and unpair from their pages`() {
        pairBoth()
        val hostId = screen.pairs.all().single().id
        val screenId = host.pairs.all().single().id
        val page = screenFlow.items(SCREEN_PEER_MENU + hostId)
        assertEquals("Linked", page.first { it.label == "Link" }.detail)
        assertEquals(HomeAction.Config("screen-pair-disconnect", emptyList()), page.first { it.label == "Disconnect" }.action)

        assertNull(screenFlow.act("screen-pair-disconnect", emptyList()))
        waitUntil("unlinked") { screen.state().linkedName == null && host.state().linkedName == null }
        val apart = screenFlow.items(SCREEN_PEER_MENU + hostId)
        assertEquals(HomeAction.Config("screen-pair-connect", listOf(hostId)), apart.first { it.label == "Connect" }.action)
        // The host can't call its screen: the screen connects.
        assertTrue(hostFlow.items(SCREEN_PEER_MENU + screenId).none { it.label == "Connect" })

        assertNull(screenFlow.act("screen-pair-connect", listOf(hostId)))
        waitUntil("linked again") { screen.state().linkedName == "Pixel 10" && screenFlow.shown().connecting == null }
        assertNull(screenFlow.shown().problem)

        val unpair = screenFlow.items(SCREEN_PEER_MENU + hostId).first { it.label == "Unpair" }.action as HomeAction.Config
        assertEquals("screen-pair-unpair", unpair.request)
        assertTrue(unpair.back)
        assertNotNull(unpair.confirm)
        assertNull(screenFlow.act(unpair.request, unpair.args))
        assertEquals(emptyList(), screen.pairs.all())
        waitUntil("the host forgets too") { host.pairs.all().isEmpty() }
    }

    @Test
    fun `the settings group shows the link, both ways to pair and the pairs`() {
        val idle = hostFlow.items("settings:screen")
        assertEquals(HomeItem("Status", HomeAction.None, detail = "Not linked", kind = HomeRow.INFO), idle.first { it.label == "Status" })
        assertEquals(
            HomeAction.Config("screen-pair-host", emptyList(), open = SCREEN_PAIR_HOST_MENU),
            idle.first { it.label == "Pair this phone as the host" }.action,
        )
        assertEquals(
            HomeAction.Config("screen-pair-find", emptyList(), open = SCREEN_PAIR_JOIN_MENU),
            idle.first { it.label == "Pair with a host" }.action,
        )
        assertTrue(idle.any { it.label == "(none yet)" })
        pairBoth()
        val linked = hostFlow.items("settings:screen")
        assertEquals("Linked with Nord 2", linked.first { it.label == "Status" }.detail)
        val pair = linked.first { it.label == "Nord 2" }
        assertEquals(HomeAction.Menu(SCREEN_PEER_MENU + host.pairs.all().single().id), pair.action)
        assertEquals("screen, linked", pair.detail)
    }
}

/** Settings' pairing pages and texts, from a state as it would be. */
class ScreenPairingItemsTest {
    private val nobody = ScreenLinkState(
        name = "Nord 2", hosting = ScreenHosting.AUTO, pairingCode = null, linkedId = null, linkedName = null,
        lastEnd = null, problem = null, pairs = emptyList(),
    )

    @Test
    fun `the join page lists hosts found, says it looks, and offers an address`() {
        val shown = ScreenPairingShown(
            nobody, searching = true,
            found = listOf(FoundScreenHost("a".repeat(32), "Pixel 10", "192.168.1.20", 7391, pairing = true)),
        )
        val labels = screenPairingItems(shown, SCREEN_PAIR_JOIN_MENU)!!.map { it.label }
        assertTrue("Pixel 10" in labels, labels.toString())
        assertTrue("Looking…" in labels)
        assertTrue("Type its address" in labels)
        assertTrue("Search again" !in labels)
        val done = screenPairingItems(shown.copy(searching = false), SCREEN_PAIR_JOIN_MENU)!!
        assertEquals(HomeAction.Config("screen-pair-search", emptyList()), done.first { it.label == "Search again" }.action)
    }

    @Test
    fun `while joining, the page says so and hides the list`() {
        val items = screenPairingItems(ScreenPairingShown(nobody, join = JoinPairing.Joining("Pixel 10")), SCREEN_PAIR_JOIN_MENU)!!
        assertEquals(listOf("Pairing with Pixel 10…"), items.map { it.label })
    }

    @Test
    fun `a host pairing from the terminal shows its code in the status`() {
        val items = screenPairingItems(ScreenPairingShown(nobody.copy(pairingCode = "123 456")), "settings:screen")!!
        assertEquals("Pairing, code 123 456", items.first { it.label == "Status" }.detail)
    }

    @Test
    fun `the link's last end shows while unlinked`() {
        val items = screenPairingItems(ScreenPairingShown(nobody.copy(lastEnd = "Pixel 10 left (the connection closed)")), "settings:screen")!!
        assertTrue(items.any { it.label == "Last: Pixel 10 left (the connection closed)" && it.kind == HomeRow.TEXT })
    }

    @Test
    fun `a pair that's gone says so`() {
        assertEquals(
            listOf("(not paired with this phone any more)"),
            screenPairingItems(ScreenPairingShown(nobody), SCREEN_PEER_MENU + "b".repeat(32))!!.map { it.label },
        )
    }

    @Test
    fun `other menus aren't the pairing's`() {
        assertNull(screenPairingItems(ScreenPairingShown(nobody), "settings:terminal"))
    }

    @Test
    fun `the link's refusals read without terminal commands`() {
        assertEquals(
            "Wrong code. Check the six digits on the host: it shows a new code after each try.",
            screenProblemText("wrong code: pairing failed (check the code the host shows)"),
        )
        assertEquals(
            "Three wrong codes: pairing stopped. Start it again for a new code.",
            screenProblemText("3 wrong codes: pairing stopped (run mynx screen pair again)"),
        )
        assertEquals(
            "No phone paired in 5 minutes: pairing stopped.",
            screenProblemText("no phone paired in 5 minutes (run mynx screen pair again)"),
        )
        assertEquals(
            "Pixel 10 isn't pairing: start pairing on it first.",
            screenProblemText("Pixel 10 isn't pairing: run mynx screen pair on it first"),
        )
        assertEquals("Already linked with Nord 2: disconnect it first.", screenProblemText("already linked with Nord 2 (mynx screen disconnect first)"))
        assertEquals(
            "Couldn't reach 10.0.0.2:7391. Is mynx open there, on the same Wi-Fi? (Connection refused)",
            screenProblemText("couldn't reach 10.0.0.2:7391: Connection refused"),
        )
        assertEquals(
            "10.0.0.2:7391 didn't answer in time.",
            screenProblemText("the link to 10.0.0.2:7391 failed: Read timed out"),
        )
        assertEquals(
            "Couldn't find Pixel 10 on this network. Is mynx open there, on the same Wi-Fi?",
            screenProblemText("couldn't find Pixel 10 on this network (is mynx running there, on the same Wi-Fi?)"),
        )
        assertEquals(
            "Pixel 10 isn't paired with this phone: pair again.",
            screenProblemText("Pixel 10 isn't paired with this phone: pair again (mynx screen pair CODE)"),
        )
        assertEquals("Something else.", screenProblemText("something else"))
    }
}
