package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHomeTest {
    private fun row(id: String, state: ChatRowState? = null) = ChatHistoryRow(id, "Chat $id", 0L, state)

    private val main = listOf(
        HomeItem("AI agents", HomeAction.Menu("agents")),
        HomeItem("Shell", HomeAction.Shell),
        HomeItem("Files", menuActTab("files")),
        HomeItem("Apps", HomeAction.Menu("apps")),
    )

    private fun home(
        history: List<ChatHistoryRow> = emptyList(),
        mode: String = "auto",
        cwd: String = "/root",
        installed: Boolean = true,
        services: Int = 0,
    ) = chatHome(history, main, services, installed, mode, cwd, "/root")

    @Test
    fun continueRowsAreLiveOnesOnlyInHistoryOrderAtMostThree() {
        val history = listOf(
            row("a", ChatRowState.IDLE), row("b", ChatRowState.WAITING), row("c"),
            row("d", ChatRowState.WORKING), row("e", ChatRowState.WORKING), row("f", ChatRowState.WAITING),
        )
        assertEquals(listOf("b", "d", "e"), home(history).continueRows.map { it.sessionId })
        assertEquals(CHAT_HOME_CONTINUE_MAX, 3)
    }

    @Test
    fun noLiveChatsMeansNoContinueRows() {
        assertTrue(home(listOf(row("a", ChatRowState.IDLE), row("b"))).continueRows.isEmpty())
    }

    @Test
    fun drawerChatsPutLiveOnesFirstThenTheRestAtMostFive() {
        val history = listOf(
            row("a"), row("b", ChatRowState.IDLE), row("c", ChatRowState.WORKING),
            row("d"), row("e", ChatRowState.WAITING), row("f"), row("g"),
        )
        assertEquals(listOf("c", "e", "a", "b", "d"), home(history).drawerChats.map { it.sessionId })
        assertEquals(DRAWER_CHATS_MAX, 5)
    }

    @Test
    fun drawerChatsMayBeFewer() {
        assertEquals(listOf("a", "b"), home(listOf(row("a"), row("b"))).drawerChats.map { it.sessionId })
    }

    @Test
    fun linuxItemsAreHomesMainListInOrderWithListsMarked() {
        val linux = home().linux
        assertEquals(listOf("AI agents", "Shell", "Files", "Apps"), linux.map { it.label })
        assertEquals(listOf(true, false, false, true), linux.map { it.opensList })
        assertEquals(main.map { it.action }, linux.map { it.action })
    }

    @Test
    fun servicesInstalledAndInstallActionPassThrough() {
        val model = home(services = 2, installed = false)
        assertEquals(2, model.servicesActive)
        assertFalse(model.agentInstalled)
        assertEquals(menuActTab("agent:claude"), model.installAction)
        assertTrue(home(installed = true).agentInstalled)
    }

    @Test
    fun footerShowsModeAndHomeAsTilde() {
        assertEquals("claude code · auto · ~/", home().footer)
        assertEquals("claude code · auto · ~/", home(cwd = "/root/").footer)
    }

    @Test
    fun footerShowsSubfolderAndOutsideHome() {
        assertEquals("claude code · auto · ~/site/", home(cwd = "/root/site").footer)
        assertEquals("claude code · auto · /tmp/", home(cwd = "/tmp").footer)
        assertEquals("claude code · auto · /rootfs/", home(cwd = "/rootfs").footer)
        assertEquals("claude code · auto · /", home(cwd = "/").footer)
    }

    @Test
    fun footerNamesEachModeShortAndUnknownOnesRaw() {
        assertEquals("claude code · default · ~/", home(mode = "default").footer)
        assertEquals("claude code · plan · ~/", home(mode = "plan").footer)
        assertEquals("claude code · accept edits · ~/", home(mode = "acceptEdits").footer)
        assertEquals("claude code · dontAsk · ~/", home(mode = "dontAsk").footer)
    }
}
