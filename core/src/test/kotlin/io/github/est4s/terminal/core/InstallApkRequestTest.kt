package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallApkRequestTest {
    private val rootfs = createTempDirectory("install").toFile()
    private val dir = File(rootfs, "tmp/.pc26/requests").apply { mkdirs() }
    private val home = File(rootfs, "root").apply { mkdirs() }
    private val installed = mutableListOf<File>()
    private var refusal: String? = null
    private val requests = Pc26Requests(dir, home, installApk = { apk ->
        installed += apk
        refusal
    })

    @AfterTest
    fun cleanup() {
        rootfs.deleteRecursively()
    }

    @Test
    fun `hands an APK in Debian to the installer as a host file`() {
        File(rootfs, "tmp/app-debug.apk").writeText("apk")
        assertEquals("""{"ok":true}""", ask("install-apk", "/tmp/app-debug.apk"))
        assertEquals(listOf(File(rootfs, "tmp/app-debug.apk").path), installed.map { it.path })
    }

    @Test
    fun `refuses what isn't an APK file`() {
        File(rootfs, "tmp/notes.txt").writeText("hi")
        assertEquals("""{"ok":false,"error":"not an APK: /tmp/notes.txt"}""", ask("install-apk", "/tmp/notes.txt"))
        assertEquals("""{"ok":false,"error":"no such file: /tmp/gone.apk"}""", ask("install-apk", "/tmp/gone.apk"))
        assertEquals("""{"ok":false,"error":"the path must start with /: app.apk"}""", ask("install-apk", "app.apk"))
        assertEquals("""{"ok":false,"error":"install-apk needs 1 argument"}""", ask("install-apk"))
        assertEquals(emptyList(), installed)
    }

    @Test
    fun `says why the installer didn't open`() {
        File(rootfs, "tmp/app.apk").writeText("apk")
        refusal = "only debug builds can install apps"
        assertEquals("""{"ok":false,"error":"only debug builds can install apps"}""", ask("install-apk", "/tmp/app.apk"))
    }

    @Test
    fun `without an installer, nothing is installed`() {
        File(rootfs, "tmp/app.apk").writeText("apk")
        val plain = Pc26Requests(dir, home)
        File(dir, "q.req").writeText("install-apk\n/tmp/app.apk\n")
        plain.processPending()
        assertEquals("""{"ok":false,"error":"apps can't be installed here"}""", File(dir, "q.reply").readText())
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }
}
