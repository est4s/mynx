package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneRequestsTest {
    private val base = createTempDirectory("phone").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }
    private val vibrations = mutableListOf<Long>()
    private val copied = mutableListOf<String>()
    private var clipboard: Result<String> = Result.success("")
    private var refusal: String? = null
    private val shared = mutableListOf<Share>()
    private val requests = MynxRequests(
        dir, home,
        vibrate = { ms -> vibrations += ms; refusal },
        setClipboard = { text -> copied += text; refusal },
        readClipboard = { clipboard },
        share = { shared += it; refusal },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `vibrates for 300 ms, or as long as asked`() {
        assertEquals("""{"ok":true}""", ask("vibrate"))
        assertEquals("""{"ok":true}""", ask("vibrate", "50"))
        assertEquals(listOf(300L, 50L), vibrations)
    }

    @Test
    fun `vibrates for 1 ms to 5 s`() {
        val error = """{"ok":false,"error":"vibrate takes milliseconds from 1 to 5000"}"""
        assertEquals(error, ask("vibrate", "0"))
        assertEquals(error, ask("vibrate", "5001"))
        assertEquals(error, ask("vibrate", "long"))
        assertEquals("""{"ok":true}""", ask("vibrate", "5000"))
        assertEquals(listOf(5000L), vibrations)
    }

    @Test
    fun `says why it didn't vibrate`() {
        refusal = "the phone has no vibrator"
        assertEquals("""{"ok":false,"error":"the phone has no vibrator"}""", ask("vibrate"))
    }

    // mynx sends the text percent-encoded, so it can hold line breaks.
    @Test
    fun `copies text to the clipboard, line breaks and all`() {
        assertEquals("""{"ok":true}""", ask("clipboard-set", "two%0Alines%20%2B%20%C3%A9%0D%0A"))
        assertEquals(listOf("two\nlines + é\r\n"), copied)
    }

    @Test
    fun `copying nothing empties the clipboard`() {
        assertEquals("""{"ok":true}""", ask("clipboard-set", ""))
        assertEquals("""{"ok":true}""", ask("clipboard-set"))
        assertEquals(listOf("", ""), copied)
    }

    @Test
    fun `refuses text that's too long or badly encoded`() {
        val long = "a".repeat(MAX_CLIPBOARD_TEXT + 1)
        assertEquals(
            """{"ok":false,"error":"too long for the clipboard: ${MAX_CLIPBOARD_TEXT + 1} characters (at most $MAX_CLIPBOARD_TEXT)"}""",
            ask("clipboard-set", long),
        )
        assertEquals("""{"ok":false,"error":"clipboard-set: badly encoded text"}""", ask("clipboard-set", "100%"))
        assertEquals(emptyList(), copied)
    }

    @Test
    fun `reads the clipboard`() {
        clipboard = Result.success("hi\n\"there\"")
        assertEquals("""{"ok":true,"text":"hi\n\"there\""}""", ask("clipboard-get"))
    }

    @Test
    fun `says why it couldn't read or copy`() {
        clipboard = Result.failure(Exception("the app must be on screen to read the clipboard"))
        assertEquals("""{"ok":false,"error":"the app must be on screen to read the clipboard"}""", ask("clipboard-get"))
        refusal = "the clipboard is busy"
        assertEquals("""{"ok":false,"error":"the clipboard is busy"}""", ask("clipboard-set", "x"))
    }

    @Test
    fun `android-clipboard off keeps programs away from the clipboard`() {
        File(home, ".config/mynx").mkdirs()
        File(home, ".config/mynx/settings.conf").writeText("android-clipboard = off\n")
        clipboard = Result.success("secret")
        val off = """{"ok":false,"error":"clipboard access is off (mynx set android-clipboard on)"}"""
        assertEquals(off, ask("clipboard-get"))
        assertEquals(off, ask("clipboard-set", "x"))
        assertEquals(emptyList(), copied)
    }

    @Test
    fun `shares files, found in Debian`() {
        File(home, "a.txt").writeText("a")
        File(home, "b c.jpg").writeText("b")
        assertEquals("""{"ok":true,"count":2}""", ask("share", "/root/a.txt", "/root/b c.jpg"))
        assertEquals(listOf(Share(listOf(File(home, "a.txt"), File(home, "b c.jpg")), null)), shared)
    }

    @Test
    fun `shares only files that exist`() {
        File(home, "folder").mkdirs()
        assertEquals("""{"ok":false,"error":"share needs a file"}""", ask("share"))
        assertEquals("""{"ok":false,"error":"no such file: /root/nope"}""", ask("share", "/root/nope"))
        assertEquals("""{"ok":false,"error":"not a file: /root/folder"}""", ask("share", "/root/folder"))
        assertEquals("""{"ok":false,"error":"the path must start with /: x"}""", ask("share", "x"))
        assertEquals(emptyList(), shared)
    }

    @Test
    fun `shares at most 100 files at once`() {
        File(home, "a").writeText("a")
        val error = """{"ok":false,"error":"too many files: 101 (at most $MAX_SHARE_FILES at once)"}"""
        assertEquals(error, ask("share", *Array(101) { "/root/a" }))
        assertEquals(emptyList(), shared)
    }

    @Test
    fun `shares text, line breaks and all`() {
        assertEquals("""{"ok":true}""", ask("share-text", "two%0Alines"))
        assertEquals(listOf(Share(emptyList(), "two\nlines")), shared)
    }

    @Test
    fun `refuses empty, long or badly encoded text`() {
        assertEquals("""{"ok":false,"error":"nothing to share"}""", ask("share-text", ""))
        assertEquals(
            """{"ok":false,"error":"too long to share: ${MAX_SHARE_TEXT + 1} characters (at most $MAX_SHARE_TEXT)"}""",
            ask("share-text", "a".repeat(MAX_SHARE_TEXT + 1)),
        )
        assertEquals("""{"ok":false,"error":"share-text: badly encoded text"}""", ask("share-text", "100%"))
        assertEquals(emptyList(), shared)
    }

    @Test
    fun `says why it couldn't share`() {
        refusal = "the app must be on screen to share"
        assertEquals("""{"ok":false,"error":"the app must be on screen to share"}""", ask("share-text", "x"))
    }

    @Test
    fun `android-share off keeps programs from sharing`() {
        File(home, ".config/mynx").mkdirs()
        File(home, ".config/mynx/settings.conf").writeText("android-share = off\n")
        File(home, "a").writeText("a")
        val off = """{"ok":false,"error":"sharing is off (mynx set android-share on)"}"""
        assertEquals(off, ask("share", "/root/a"))
        assertEquals(off, ask("share-text", "x"))
        assertEquals(emptyList(), shared)
    }

    @Test
    fun `without a phone to ask, nothing happens`() {
        val plain = MynxRequests(dir, home)
        fun ask(vararg lines: String): String {
            File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
            plain.processPending()
            return File(dir, "q.reply").readText()
        }
        assertEquals("""{"ok":false,"error":"vibration isn't available here"}""", ask("vibrate"))
        assertEquals("""{"ok":false,"error":"the clipboard isn't available here"}""", ask("clipboard-get"))
        assertEquals("""{"ok":false,"error":"the clipboard isn't available here"}""", ask("clipboard-set", "x"))
        assertEquals("""{"ok":false,"error":"sharing isn't available here"}""", ask("share-text", "x"))
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }
}
