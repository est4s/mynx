package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServiceFilesTest {
    private val dir = createTempDirectory("services").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `the folder is in Debian's tmp, beside the others`() {
        assertEquals("/tmp/.mynx/services", SERVICES_DIR)
    }

    @Test
    fun `the exit code the wrapper wrote`() {
        File(dir, "web.exit").writeText("143\n")
        assertEquals(143, readServiceExit(dir, "web"))
    }

    @Test
    fun `no file or a bad one is no exit code`() {
        assertNull(readServiceExit(dir, "web"))
        File(dir, "web.exit").writeText("")
        assertNull(readServiceExit(dir, "web"))
        File(dir, "web.exit").writeText("lots")
        assertNull(readServiceExit(dir, "web"))
    }

    @Test
    fun `the process group's id`() {
        assertNull(readServicePid(dir, "web"))
        File(dir, "web.pid").writeText("4242\n")
        assertEquals(4242, readServicePid(dir, "web"))
        File(dir, "web.pid").writeText("0\n")
        assertNull(readServicePid(dir, "web"))
    }

    @Test
    fun `a start clears what the last run left`() {
        File(dir, "web.exit").writeText("1\n")
        File(dir, "web.pid").writeText("12\n")
        File(dir, "webby.exit").writeText("1\n")

        clearServiceFiles(dir, "web")

        assertFalse(File(dir, "web.exit").exists())
        assertFalse(File(dir, "web.pid").exists())
        assertEquals(1, readServiceExit(dir, "webby"))
    }

    @Test
    fun `a service's folder in Debian, from its cwd`() {
        assertEquals("/root", serviceWorkDir("~"))
        assertEquals("/root/site", serviceWorkDir("~/site"))
        assertEquals("/srv/www", serviceWorkDir("/srv/www"))
    }
}
