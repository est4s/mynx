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
                "line 2: 'ttyUSB0' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255, or nus0 to nus255",
                "line 3: 'AA:BB:CC:DD:EE' isn't a Bluetooth address (like D4:E9:F4:A2:4A:02)",
                "line 4: expected rfcommN = ADDRESS, or nusN = ADDRESS [random]",
                "line 5: rfcomm0 is already bound on line 1",
                "line 6: AA:BB:CC:DD:EE:FF is already bound to rfcomm0 on line 1",
                "line 7: 'rfcomm07' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255, or nus0 to nus255",
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
        assertEquals("'tty0' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255, or nus0 to nus255", error(BtBinding("tty0", "D4:E9:F4:A2:4A:02")))
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

    @Test
    fun `reads BLE UART ports, with random addresses`() {
        val parsed = parseBluetooth("rfcomm0 = AA:BB:CC:DD:EE:FF\nnus0 = d4:e9:f4:a2:4a:06\nnus3 = 1C:DB:D4:C5:CF:36  random \n")
        assertEquals(
            listOf(
                BtBinding("rfcomm0", "AA:BB:CC:DD:EE:FF"),
                BtBinding("nus0", "D4:E9:F4:A2:4A:06"),
                BtBinding("nus3", "1C:DB:D4:C5:CF:36", random = true),
            ),
            parsed.bindings,
        )
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `random is only for nus lines, and nothing else may follow the address`() {
        val parsed = parseBluetooth(
            listOf(
                "rfcomm0 = AA:BB:CC:DD:EE:01 random",
                "nus0 = AA:BB:CC:DD:EE:02 public",
                "nus1 = AA:BB:CC:DD:EE:03 random random",
                "nus256 = AA:BB:CC:DD:EE:04",
                "nus2 = AA:BB:CC:DD:EE:05 RANDOM",
            ).joinToString("\n"),
        )
        assertEquals(listOf(BtBinding("nus2", "AA:BB:CC:DD:EE:05", random = true)), parsed.bindings)
        assertEquals(
            listOf(
                "line 1: random is only for nus ports (Bluetooth LE): rfcomm0 = ADDRESS",
                "line 2: 'public' after the address: only random may follow it",
                "line 3: 'random random' after the address: only random may follow it",
                "line 4: 'nus256' isn't a Bluetooth port's name: use rfcomm0 to rfcomm255, or nus0 to nus255",
            ),
            parsed.problems,
        )
    }

    @Test
    fun `an address is bound once, whatever the kind`() {
        val parsed = parseBluetooth("rfcomm0 = AA:BB:CC:DD:EE:FF\nnus0 = AA:BB:CC:DD:EE:FF random\nnus0 = D4:E9:F4:A2:4A:06\n")
        // Line 2 is skipped, so nus0 is free for line 3.
        assertEquals(listOf(BtBinding("rfcomm0", "AA:BB:CC:DD:EE:FF"), BtBinding("nus0", "D4:E9:F4:A2:4A:06")), parsed.bindings)
        assertEquals(listOf("line 2: AA:BB:CC:DD:EE:FF is already bound to rfcomm0 on line 1"), parsed.problems)
        assertEquals(
            listOf("line 2: nus0 is already bound on line 1"),
            parseBluetooth("nus0 = AA:BB:CC:DD:EE:01\nnus0 = AA:BB:CC:DD:EE:02 random\n").problems,
        )
    }

    @Test
    fun `binding a BLE UART port writes random when its address is`() {
        val text = addBluetoothBinding("rfcomm0 = AA:BB:CC:DD:EE:FF\n", BtBinding("nus0", "1c:db:d4:c5:cf:36", random = true)).getOrThrow()
        assertEquals("rfcomm0 = AA:BB:CC:DD:EE:FF\nnus0 = 1C:DB:D4:C5:CF:36 random\n", text)
        assertEquals(
            "nus1 = D4:E9:F4:A2:4A:06\n",
            addBluetoothBinding("", BtBinding("nus1", "D4:E9:F4:A2:4A:06")).getOrThrow(),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:FF is already bound to rfcomm0",
            addBluetoothBinding(text, BtBinding("nus1", "AA:BB:CC:DD:EE:FF")).exceptionOrNull()?.message,
        )
        assertEquals(
            "random is only for nus ports (Bluetooth LE)",
            addBluetoothBinding(null, BtBinding("rfcomm1", "D4:E9:F4:A2:4A:06", random = true)).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `the header says what both kinds of line are`() {
        assertTrue("\"rfcommN = ADDRESS\"" in BLUETOOTH_HEADER)
        assertTrue("\"nusN = ADDRESS\"" in BLUETOOTH_HEADER)
        assertTrue("mynx ble bind" in BLUETOOTH_HEADER)
        val text = addBluetoothBinding(null, BtBinding("nus0", "D4:E9:F4:A2:4A:06", random = true)).getOrThrow()
        assertEquals(emptyList(), parseBluetooth(text).problems)
    }

    @Test
    fun `unbinding takes out nus lines too`() {
        val text = "rfcomm0 = AA:BB:CC:DD:EE:FF\nnus0 = D4:E9:F4:A2:4A:06 random\n"
        assertEquals(
            Unbound("rfcomm0 = AA:BB:CC:DD:EE:FF\n", listOf("nus0")),
            removeBluetoothBindings(text) { it.address == "D4:E9:F4:A2:4A:06" },
        )
    }
}
