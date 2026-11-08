package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [WebAppHost] the tests drive by hand: they see what was asked and answer the port probes. */
class FakeWebAppHost : WebAppHost {
    override var onScreen = true
    /** The apps whose screen exists. */
    val open = mutableSetOf<String>()
    var openFails: String? = null
    val opened = mutableListOf<String>()
    val reloaded = mutableListOf<String>()
    /** The ports that answer a probe. */
    val answering = mutableSetOf<Int>()
    val probes = mutableListOf<Int>()
    val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    override fun open(app: WebApp): String? {
        openFails?.let { return it }
        opened += app.name
        open += app.name
        return null
    }

    override fun isOpen(name: String) = name in open

    override fun reload(name: String): Boolean {
        if (name !in open) return false
        reloaded += name
        return true
    }

    override fun portAnswers(port: Int, done: (Boolean) -> Unit) {
        probes += port
        done(port in answering)
    }

    override fun schedule(delayMs: Long, block: () -> Unit) {
        scheduled += delayMs to block
    }

    /** Runs what was scheduled, in order, until nothing is left or [limit] runs; [tick] passes each delay to the clock. */
    fun runScheduled(limit: Int = 1000, tick: (Long) -> Unit = {}) {
        var runs = 0
        while (scheduled.isNotEmpty() && runs++ < limit) {
            val (ms, block) = scheduled.removeAt(0)
            tick(ms)
            block()
        }
    }
}

/** A [WebAppServices] with services the tests set up; it records starts and stops. */
class FakeWebAppServices : WebAppServices {
    val services = mutableMapOf<String, WebAppServiceStatus>()
    val events = mutableListOf<String>()
    var startFails: String? = null

    override fun status(name: String) = services[name]

    override fun start(name: String): String? {
        startFails?.let { return it }
        val service = services[name] ?: return "no service named $name"
        events += "start $name"
        services[name] = service.copy(state = ServiceState.RUNNING)
        return null
    }

    override fun stop(name: String): String? {
        val service = services[name] ?: return "no service named $name"
        events += "stop $name"
        services[name] = service.copy(state = ServiceState.STOPPED)
        return null
    }
}

/** `tests/fixtures/` in the repo (Gradle runs the tests in `core/`). */
fun fixturesDir(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val fixtures = File(dir, "tests/fixtures")
        if (File(fixtures, "apps").isDirectory) return fixtures
        dir = dir.parentFile
    }
    error("no tests/fixtures/apps above ${System.getProperty("user.dir")}")
}

/** Copies [from] into [to], links as links. */
fun copyTree(from: File, to: File) {
    val source = from.toPath()
    Files.walk(source).use { paths ->
        for (path in paths) {
            val target: Path = to.toPath().resolve(source.relativize(path).toString())
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target)
            } else {
                Files.copy(path, target, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }
}

class WebAppsTest {
    private val base = createTempDirectory("webapps").toFile()
    private val rootfs = File(base, "rootfs")
    /** Root's home: a copy of `tests/fixtures/`, so `~/apps` holds the fixture apps. */
    private val home = File(rootfs, "root").also { copyTree(fixturesDir(), it) }
    private val tools = File(base, "tools").apply { mkdirs() }
    private val host = FakeWebAppHost()
    private val services = FakeWebAppServices()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `the fixtures are copied with their link out of the folder`() {
        val link = File(home, "$WEB_APPS_DIR/escape/outside.txt").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("../../secret.txt", Files.readSymbolicLink(link).toString())
        assertTrue(File(home, "secret.txt").isFile)
    }
}
