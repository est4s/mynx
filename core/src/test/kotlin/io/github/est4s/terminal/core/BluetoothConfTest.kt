package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothConfTest {
    @Test
    fun `reads one port per line, addresses in upper case, skipping comments and blank lines`() {
        val parsed = parseBluetooth("# mine\n\nrfcomm0 = d4:e9:f4:a2:4a:02\n  rfcomm3=AA:BB:CC:DD:EE:FF  \n")
        assertEquals(
            listOf(BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02"), BtBinding("rfcomm3", "AA:BB:CC:DD:EE:FF")),
            parsed.bindings,
        )
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `bad lines are skipped and reported by line`() {
        val parsed = parseBluetooth(
            listOf(
                "rfcomm0 = AA:BB:CC:DD:EE:FF",
                "ttyUSB0 = AA:BB:CC:DD:EE:01",
                "rfcomm1 = AA:BB:CC:DD:EE",
                "rfcomm1",
                "rfcomm0 = AA:BB:CC:DD:EE:02",
                "rfcomm2 = aa:bb:cc:dd:ee:ff",
                "rfcomm07 = AA:BB:CC:DD:EE:03",
                "rfcomm4 = AA:BB:CC:DD:EE:04",
            ).joinToString("\n"),
        )
        assertEquals(listOf(BtBinding("rfcomm0", "AA:BB:CC:DD:EE:FF"), BtBinding("rfcomm4", "AA:BB:CC:DD:EE:04")), parsed.bindings)
        assertEquals(
            listOf(
                "line 2: 'ttyUSB0' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255",
                "line 3: 'AA:BB:CC:DD:EE' isn't a Bluetooth address (like D4:E9:F4:A2:4A:02)",
                "line 4: expected rfcommN = ADDRESS",
                "line 5: rfcomm0 is already bound on line 1",
                "line 6: AA:BB:CC:DD:EE:FF is already bound to rfcomm0 on line 1",
                "line 7: 'rfcomm07' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `port names are Linux's, rfcomm0 to rfcomm255`() {
        assertTrue(isRfcommName("rfcomm0"))
        assertTrue(isRfcommName("rfcomm255"))
        assertFalse(isRfcommName("rfcomm256"))
        assertFalse(isRfcommName("rfcomm01"))
        assertFalse(isRfcommName("rfcomm"))
        assertFalse(isRfcommName("ttyACM0"))
    }

    @Test
    fun `addresses are six hex pairs, given in any case`() {
        assertEquals("D4:E9:F4:A2:4A:02", btAddress("d4:e9:f4:a2:4A:02"))
        assertEquals(null, btAddress("D4-E9-F4-A2-4A-02"))
        assertEquals(null, btAddress("D4:E9:F4:A2:4A"))
        assertEquals(null, btAddress("mynx-spp"))
    }

    @Test
    fun `a new port takes the lowest free name`() {
        assertEquals("rfcomm0", rfcommName(emptySet()))
        assertEquals("rfcomm1", rfcommName(setOf("rfcomm0", "rfcomm2")))
    }

    @Test
    fun `binding adds a line at the end, keeping the rest`() {
        val text = "# mine\nrfcomm0 = AA:BB:CC:DD:EE:FF\n\n"
        assertEquals(
            "# mine\nrfcomm0 = AA:BB:CC:DD:EE:FF\nrfcomm1 = D4:E9:F4:A2:4A:02\n",
            addBluetoothBinding(text, BtBinding("rfcomm1", "D4:E9:F4:A2:4A:02")).getOrThrow(),
        )
    }

    @Test
    fun `binding with no file starts it with a header`() {
        val text = addBluetoothBinding(null, BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02")).getOrThrow()
        assertTrue(text.startsWith("# Bluetooth serial ports"))
        assertTrue(text.endsWith("\nrfcomm0 = D4:E9:F4:A2:4A:02\n"))
        assertEquals(listOf(BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02")), parseBluetooth(text).bindings)
        assertEquals(emptyList(), parseBluetooth(text).problems)
    }

    @Test
    fun `binding refuses a bad name, a taken port and a bound address`() {
        val text = "rfcomm0 = AA:BB:CC:DD:EE:FF\n"
        fun error(binding: BtBinding) = addBluetoothBinding(text, binding).exceptionOrNull()?.message
        assertEquals("'tty0' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255", error(BtBinding("tty0", "D4:E9:F4:A2:4A:02")))
        assertEquals("rfcomm0 is already bound to AA:BB:CC:DD:EE:FF", error(BtBinding("rfcomm0", "D4:E9:F4:A2:4A:02")))
        assertEquals("AA:BB:CC:DD:EE:FF is already bound to rfcomm0", error(BtBinding("rfcomm1", "AA:BB:CC:DD:EE:FF")))
    }

    @Test
    fun `unbinding removes the port's lines, or the address's, keeping comments`() {
        val text = "# mine\nrfcomm0 = AA:BB:CC:DD:EE:FF\n# the CAM\nrfcomm1 = D4:E9:F4:A2:4A:02\n"
        assertEquals(
            Unbound("# mine\n# the CAM\nrfcomm1 = D4:E9:F4:A2:4A:02\n", listOf("rfcomm0")),
            removeBluetoothBindings(text) { it.port == "rfcomm0" },
        )
        assertEquals(
            Unbound("# mine\nrfcomm0 = AA:BB:CC:DD:EE:FF\n# the CAM\n", listOf("rfcomm1")),
            removeBluetoothBindings(text) { it.address == "D4:E9:F4:A2:4A:02" },
        )
        assertEquals(Unbound(text, emptyList()), removeBluetoothBindings(text) { it.port == "rfcomm9" })
    }

    @Test
    fun `unbinding also takes out a bad line for the same port`() {
        // Else the port's name would stay taken by a line nothing reads.
        val text = "rfcomm0 = AA:BB:CC:DD:EE:FF\nrfcomm0 = D4:E9:F4:A2:4A:02\n"
        assertEquals(Unbound("", listOf("rfcomm0")), removeBluetoothBindings(text) { it.port == "rfcomm0" })
    }
}
