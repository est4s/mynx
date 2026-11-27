package io.github.est4s.terminal

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.est4s.terminal.core.FoundScreenHost
import io.github.est4s.terminal.core.HomeMenus
import io.github.est4s.terminal.core.HostPairing
import io.github.est4s.terminal.core.JoinPairing
import io.github.est4s.terminal.core.LOGO_PALETTE
import io.github.est4s.terminal.core.SCREEN_PAIR_HOST_MENU
import io.github.est4s.terminal.core.SCREEN_PAIR_JOIN_MENU
import io.github.est4s.terminal.core.SCREEN_PEER_MENU
import io.github.est4s.terminal.core.ScreenHosting
import io.github.est4s.terminal.core.ScreenLinkState
import io.github.est4s.terminal.core.ScreenPairRow
import io.github.est4s.terminal.core.ScreenPairingShown
import io.github.est4s.terminal.core.ScreenRole
import io.github.est4s.terminal.core.WebApps
import io.github.est4s.terminal.core.builtInThemeText
import io.github.est4s.terminal.core.pixelPalette
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.file.Files

/** Settings' pairing (Second screen) on both phones, from states as they'd be: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class ScreenPairingScreenshotTest {
    private val top = Files.createTempDirectory("pairing-shots").toFile()
    private val rootfs = File(top, "rootfs").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val tools = File(top, "tools").apply { mkdirs() }

    @After
    fun cleanUp() {
        Pixel.use(LOGO_PALETTE)
        // No links in it, so this can't reach outside it.
        top.deleteRecursively()
    }

    private val nord = "b".repeat(32)
    private val pixel = "a".repeat(32)
    private val hostLink = ScreenLinkState(
        "Pixel 10", ScreenHosting.AUTO, pairingCode = null, linkedId = nord, linkedName = "Nord 2",
        lastEnd = null, problem = null, pairs = listOf(ScreenPairRow(nord, "Nord 2", ScreenRole.SCREEN)),
    )
    private val screenLink = ScreenLinkState(
        "Nord 2", ScreenHosting.AUTO, pairingCode = null, linkedId = null, linkedName = null,
        lastEnd = "Pixel 10 left (the connection closed)", problem = null,
        pairs = listOf(ScreenPairRow(pixel, "Pixel 10", ScreenRole.HOST)),
    )
    private val waiting = HostPairing.Waiting("482 913", "Pixel 10", listOf("192.168.1.20:7391"), wrong = 0)
    private val found = listOf(
        FoundScreenHost(pixel, "Pixel 10", "192.168.1.20", 7391, pairing = true),
        FoundScreenHost("c".repeat(32), "Tablet", "192.168.1.31", 7391, pairing = true),
    )

    private fun view(shown: ScreenPairingShown, w: Int = 1080, h: Int = 2400) = Screenshots.open(w, h) { activity ->
        HomeView(activity, menus = { HomeMenus(WebApps(home, rootfs.path, tools), pairing = shown) }, act = {}, config = { _, _ -> null })
    }

    private fun shoot(view: HomeView, name: String, menu: String, title: String) {
        view.showMenu(menu, title)
        view.refresh()
        Screenshots.settleThreads()
        Screenshots.save(view, name)
    }

    // Taps the row labelled [label], as a finger would.
    private fun tap(view: View, label: String): Boolean {
        if (view is TextView && view.text.toString() == label) return (view.parent as View).performClick()
        if (view is ViewGroup) for (i in 0 until view.childCount) if (tap(view.getChildAt(i), label)) return true
        return false
    }

    @Test
    fun group() = shoot(view(ScreenPairingShown(hostLink)), "pairing-settings", "settings:screen", "Second screen")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun groupNarrow() = shoot(view(ScreenPairingShown(hostLink), 945, 2100), "pairing-settings-360dp", "settings:screen", "Second screen")

    @Test
    fun hostWaiting() =
        shoot(view(ScreenPairingShown(hostLink.copy(linkedId = null, linkedName = null), host = waiting)), "pairing-host", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun hostWaitingNarrow() = shoot(
        view(ScreenPairingShown(hostLink, host = waiting.copy(code = "105 337", wrong = 1)), 945, 2100),
        "pairing-host-wrong-360dp", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host",
    )

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun hostWaitingLandscape() =
        shoot(view(ScreenPairingShown(hostLink, host = waiting), 2400, 1080), "pairing-host-landscape", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host")

    // Following the user's theme, a light one here.
    @Test
    fun hostWaitingLight() {
        Pixel.use(pixelPalette("# theme: solarized-light\n" + builtInThemeText("solarized-light")))
        shoot(view(ScreenPairingShown(hostLink, host = waiting)), "pairing-host-solarized-light", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host")
    }

    @Test
    fun hostPaired() = shoot(view(ScreenPairingShown(hostLink, host = HostPairing.Paired("Nord 2"))), "pairing-host-paired", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host")

    @Test
    fun hostStopped() = shoot(
        view(ScreenPairingShown(hostLink, host = HostPairing.Stopped("Three wrong codes: pairing stopped. Start it again for a new code."))),
        "pairing-host-stopped", SCREEN_PAIR_HOST_MENU, "Pair this phone as the host",
    )

    @Test
    fun joinList() = shoot(view(ScreenPairingShown(screenLink, searching = true, found = found)), "pairing-join", SCREEN_PAIR_JOIN_MENU, "Pair with a host")

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun joinFailedNarrow() = shoot(
        view(
            ScreenPairingShown(
                screenLink, join = JoinPairing.Failed("Wrong code. Check the six digits on the host: it shows a new code after each try."),
                found = found.take(1), typed = "192.168.1.20",
            ),
            945, 2100,
        ),
        "pairing-join-failed-360dp", SCREEN_PAIR_JOIN_MENU, "Pair with a host",
    )

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun joinLandscape() =
        shoot(view(ScreenPairingShown(screenLink, found = found), 2400, 1080), "pairing-join-landscape", SCREEN_PAIR_JOIN_MENU, "Pair with a host")

    @Test
    fun joinPaired() = shoot(view(ScreenPairingShown(screenLink, join = JoinPairing.Paired("Pixel 10"))), "pairing-join-paired", SCREEN_PAIR_JOIN_MENU, "Pair with a host")

    // The code's number field, from tapping a host found.
    @Test
    fun codeEntry() {
        val view = view(ScreenPairingShown(screenLink, found = found))
        shoot(view, "pairing-join-list", SCREEN_PAIR_JOIN_MENU, "Pair with a host")
        check(tap(view, "Pixel 10")) { "no Pixel 10 row" }
        view.refresh()
        Screenshots.settleThreads()
        Screenshots.save(view, "pairing-code")
    }

    @Test
    fun hostPeer() = shoot(view(ScreenPairingShown(hostLink)), "pairing-peer-screen", SCREEN_PEER_MENU + nord, "Nord 2")

    @Test
    fun screenPeer() = shoot(
        view(ScreenPairingShown(screenLink, problem = "Couldn't find Pixel 10 on this network. Is mynx open there, on the same Wi-Fi?")),
        "pairing-peer-host", SCREEN_PEER_MENU + pixel, "Pixel 10",
    )

    // Unpair asks first, in a pixel dialog.
    @Test
    fun unpairConfirm() {
        val view = view(ScreenPairingShown(screenLink))
        shoot(view, "pairing-peer-host-idle", SCREEN_PEER_MENU + pixel, "Pixel 10")
        check(tap(view, "Unpair")) { "no Unpair row" }
        Screenshots.settle()
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        Screenshots.saveWindow(decor, 1080 - Pixel.dp(view.context, 32), Pixel.BACKGROUND, "pairing-unpair")
    }
}
