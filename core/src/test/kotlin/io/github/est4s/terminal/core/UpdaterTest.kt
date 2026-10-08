package io.github.est4s.terminal.core

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    private val base = createTempDirectory("updater").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "debian/root").apply { mkdirs() }
    private val stateFile = File(base, "state/update-state")
    private val noticeFile = File(base, "debian/tmp/.mynx/update-available")
    private val downloads = File(base, "cache/updates")
    private val t = 1_790_000_000_000L
    private var now = t
    private val apkBytes = "the new app".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private var check: ReleaseCheck = found("v0.2.0")
    private var fetches = 0
    private val downloaded = mutableListOf<ReleaseApk>()
    private var downloadResult: ((File) -> ApkDownload)? = null
    private val notified = mutableListOf<String>()
    private val installed = mutableListOf<File>()
    private var installAnswer: String? = null

    private fun found(tag: String, size: Long = apkBytes.size.toLong(), digest: String? = "sha256:$sha"): ReleaseCheck {
        val version = parseVersion(tag)!!
        return ReleaseCheck.Found(parseRelease(releaseJson(tag = tag, assets = apkAsset("mynx-$version.apk", size = size, digest = digest))))
    }

    private fun updater(current: String = "0.1.0", releases: Boolean = true) = Updater(
        stateFile, noticeFile, downloads, current, releases,
        fetch = { fetches++; check },
        download = { apk, dest, _, progress ->
            downloaded += apk
            downloadResult?.invoke(dest) ?: run {
                progress(0, apk.size)
                progress(apk.size, apk.size)
                dest.parentFile.mkdirs()
                dest.writeBytes(apkBytes)
                ApkDownload.Done(dest)
            }
        },
        notify = { notified += it },
        now = { now },
        background = { it() },
    )

    private fun requests(updater: Updater) =
        MynxRequests(dir, home, later = updater.requests { apk -> installed += apk; installAnswer })

    private fun send(requests: MynxRequests, id: String, vararg lines: String) {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
    }

    private fun reply(id: String) = File(dir, "$id.reply").readText()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `checks when due and tells the user once per version`() {
        val updater = updater()
        updater.checkIfDue(on = true)
        assertEquals(1, fetches)
        assertEquals(listOf("0.2.0"), notified)
        assertEquals("0.2.0\n", noticeFile.readText())
        assertEquals(UpdateState(lastAttempt = t, lastSuccess = t, latest = "0.2.0", notified = "0.2.0"), readUpdateState(stateFile))

        now = t + 3_600_000L
        updater.checkIfDue(on = true)
        assertEquals(1, fetches, "not due yet")

        now = t + CHECK_EVERY_MS
        updater.checkIfDue(on = true)
        assertEquals(2, fetches)
        assertEquals(listOf("0.2.0"), notified, "already told about 0.2.0")
    }

    @Test
    fun `no check while the setting is off or in builds that don't update from releases`() {
        updater().checkIfDue(on = false)
        updater(releases = false).checkIfDue(on = true)
        assertEquals(0, fetches)
        assertFalse(noticeFile.exists())
    }

    @Test
    fun `nothing newer means no notice, and an old one goes`() {
        noticeFile.parentFile.mkdirs()
        noticeFile.writeText("0.2.0\n")
        updater(current = "0.2.0").checkIfDue(on = true)
        assertTrue(notified.isEmpty())
        assertFalse(noticeFile.exists())
    }

    @Test
    fun `a failed check keeps what the last one found`() {
        updater().checkIfDue(on = true)
        check = ReleaseCheck.Failed("offline")
        now = t + CHECK_EVERY_MS
        updater().checkIfDue(on = true)
        assertEquals("0.2.0\n", noticeFile.readText())
        assertEquals("offline", readUpdateState(stateFile).error)
    }

    @Test
    fun `start shows the notice the last check left, and clears it after the update`() {
        writeUpdateState(stateFile, UpdateState(lastSuccess = t, latest = "0.2.0"))
        updater().start()
        assertEquals("0.2.0\n", noticeFile.readText())
        updater(current = "0.2.0").start()
        assertFalse(noticeFile.exists())
    }

    @Test
    fun `start deletes downloads that are installed or older`() {
        downloads.mkdirs()
        File(downloads, "mynx-0.1.0.apk").writeText("old")
        File(downloads, "mynx-0.2.0.apk.part").writeText("half")
        File(downloads, "mynx-0.3.0.apk").writeText("newer")
        updater(current = "0.2.0").start()
        assertEquals(listOf("mynx-0.3.0.apk"), downloads.list()!!.toList())
    }

    @Test
    fun `update-check answers what the latest release is`() {
        val requests = requests(updater())
        send(requests, "5-1", "update-check")
        assertEquals(
            """{"ok":true,"current":"0.1.0","latest":"0.2.0","available":true,"tag":"v0.2.0","notes":"## What's new\n- Updates",""" +
                """"size":${apkBytes.size},"published":${java.time.Instant.parse("2026-10-08T12:30:00Z").toEpochMilli()}}""",
            reply("5-1"),
        )
        assertEquals("0.2.0\n", noticeFile.readText())
        assertTrue(notified.isEmpty(), "the user is looking already")
        assertEquals("0.2.0", readUpdateState(stateFile).notified)
    }

    @Test
    fun `update-check with nothing newer or nothing released`() {
        val requests = requests(updater(current = "0.2.0"))
        send(requests, "5-1", "update-check")
        assertEquals("""{"ok":true,"current":"0.2.0","latest":"0.2.0","available":false}""", reply("5-1"))
        check = ReleaseCheck.NoRelease
        send(requests, "5-2", "update-check")
        assertEquals("""{"ok":true,"current":"0.2.0","latest":null,"available":false}""", reply("5-2"))
    }

    @Test
    fun `update-check says why it couldn't check`() {
        check = ReleaseCheck.Failed("HTTP 503 from x")
        val requests = requests(updater())
        send(requests, "5-1", "update-check")
        assertEquals("""{"ok":false,"error":"couldn't check for updates: HTTP 503 from x"}""", reply("5-1"))
        check = ReleaseCheck.BadData("no mynx-0.2.0.apk in v0.2.0")
        send(requests, "5-2", "update-check")
        assertEquals("""{"ok":false,"error":"couldn't check for updates: no mynx-0.2.0.apk in v0.2.0"}""", reply("5-2"))
    }

    @Test
    fun `builds that don't update from releases say so`() {
        val requests = requests(updater(releases = false))
        send(requests, "5-1", "update-check")
        send(requests, "5-2", "update-install", "0.2.0")
        val refusal = """{"ok":false,"error":"this build of the app doesn't update from GitHub releases"}"""
        assertEquals(refusal, reply("5-1"))
        assertEquals(refusal, reply("5-2"))
        assertEquals(0, fetches)
    }

    @Test
    fun `update-install downloads with progress, then opens the installer`() {
        val updater = updater()
        val requests = requests(updater)
        send(requests, "5-1", "update-check")
        send(requests, "5-2", "update-install", "0.2.0")
        assertEquals(1, fetches, "installs the release it just checked")
        val apk = File(downloads, "mynx-0.2.0.apk")
        assertEquals(listOf(apk), installed)
        assertEquals("""{"ok":true,"version":"0.2.0","bytes":${apkBytes.size}}""", reply("5-2"))
        assertEquals(
            listOf("""{"bytes":0,"total":${apkBytes.size}}""", """{"bytes":${apkBytes.size},"total":${apkBytes.size}}"""),
            File(dir, "5-2.stream").readLines(),
        )
    }

    @Test
    fun `update-install reuses a download that checks out`() {
        downloads.mkdirs()
        File(downloads, "mynx-0.2.0.apk").writeBytes(apkBytes)
        val requests = requests(updater())
        send(requests, "5-1", "update-install", "0.2.0")
        assertEquals(1, fetches, "asks which release is latest")
        assertTrue(downloaded.isEmpty())
        assertEquals("""{"ok":true,"version":"0.2.0","bytes":${apkBytes.size}}""", reply("5-1"))

        File(downloads, "mynx-0.2.0.apk").writeText("broken")
        send(requests, "5-2", "update-install", "0.2.0")
        assertEquals(1, downloaded.size, "a file that doesn't match is downloaded again")
    }

    @Test
    fun `update-install keeps only the one download`() {
        downloads.mkdirs()
        File(downloads, "mynx-0.1.5.apk").writeText("older")
        send(requests(updater()), "5-1", "update-install", "0.2.0")
        assertEquals(listOf("mynx-0.2.0.apk"), downloads.list()!!.toList())
    }

    @Test
    fun `update-install only installs the version the user saw`() {
        val requests = requests(updater())
        send(requests, "5-1", "update-check")
        check = found("v0.3.0")
        send(requests, "5-2", "update-install", "0.3.0")
        assertEquals(2, fetches, "0.3.0 isn't the release it checked")
        assertTrue(installed.isNotEmpty())

        check = found("v0.4.0")
        send(requests, "5-3", "update-install", "0.3.5")
        assertEquals("""{"ok":false,"error":"the latest release is 0.4.0 now, not 0.3.5: run mynx update again"}""", reply("5-3"))
    }

    @Test
    fun `update-install refuses what isn't newer or can't be had`() {
        val requests = requests(updater(current = "0.2.0"))
        send(requests, "5-1", "update-install", "0.2.0")
        assertEquals("""{"ok":false,"error":"0.2.0 isn't newer than this version (0.2.0)"}""", reply("5-1"))
        send(requests, "5-2", "update-install")
        assertEquals("""{"ok":false,"error":"update-install needs the version to install"}""", reply("5-2"))
        check = ReleaseCheck.Failed("offline")
        send(requests(updater()), "5-3", "update-install", "0.2.0")
        assertEquals("""{"ok":false,"error":"couldn't check for updates: offline"}""", reply("5-3"))
        check = ReleaseCheck.NoRelease
        send(requests(updater()), "5-4", "update-install", "0.2.0")
        assertEquals("""{"ok":false,"error":"there's no release to update to"}""", reply("5-4"))
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `update-install says why the download or installer failed`() {
        downloadResult = { ApkDownload.Failed("sha256 doesn't match") }
        send(requests(updater()), "5-1", "update-install", "0.2.0")
        assertEquals("""{"ok":false,"error":"the download failed: sha256 doesn't match"}""", reply("5-1"))

        downloadResult = null
        installAnswer = "the app must be on screen to open the installer"
        send(requests(updater()), "5-2", "update-install", "0.2.0")
        assertEquals("""{"ok":false,"error":"the app must be on screen to open the installer"}""", reply("5-2"))
        assertTrue(File(downloads, "mynx-0.2.0.apk").isFile, "kept, so trying again is quick")
    }

    @Test
    fun `progress is sent about every percent`() {
        val size = 1_000_000L
        check = found("v0.2.0", size = size, digest = null)
        downloadResult = { dest -> ApkDownload.Done(dest) }
        val updater = Updater(
            stateFile, noticeFile, downloads, "0.1.0", true,
            fetch = { check },
            download = { _, dest, _, progress ->
                for (bytes in 0..size step 1000) progress(bytes, size)
                dest.parentFile.mkdirs()
                dest.writeText("x")
                ApkDownload.Done(dest)
            },
            notify = {}, now = { now }, background = { it() },
        )
        send(MynxRequests(dir, home, later = updater.requests { null }), "5-1", "update-install", "0.2.0")
        val lines = File(dir, "5-1.stream").readLines()
        assertTrue(lines.size in 100..102, "${lines.size} lines")
        assertEquals("""{"bytes":$size,"total":$size}""", lines.last())
    }

    @Test
    fun `cancelling stops the download and installs nothing`() {
        val jobs = mutableListOf<() -> Unit>()
        var stopped: Boolean? = null
        val updater = Updater(
            stateFile, noticeFile, downloads, "0.1.0", true,
            fetch = { check },
            download = { _, _, isCancelled, _ -> stopped = isCancelled(); ApkDownload.Cancelled },
            notify = {}, now = { now }, background = { jobs += it },
        )
        val requests = MynxRequests(dir, home, later = updater.requests { installed += it; null })
        send(requests, "5-1", "update-install", "0.2.0")
        File(dir, "5-1.cancel").writeText("")
        requests.sweep()
        assertEquals("""{"ok":true}""", reply("5-1"))
        jobs.single()()
        assertEquals(true, stopped)
        assertTrue(installed.isEmpty())
    }
}
