package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BleValuesTest {
    private val battery = "0000180f-0000-1000-8000-00805f9b34fb"
    private val level = "00002a19-0000-1000-8000-00805f9b34fb"

    @Test
    fun `UUIDs are full and in lower case, from 16 and 32 bits too`() {
        assertEquals(level, bleUuid("2a19"))
        assertEquals(level, bleUuid("2A19"))
        assertEquals(level, bleUuid("0x2a19"))
        assertEquals(level, bleUuid("00002a19"))
        assertEquals(level, bleUuid("00002A19-0000-1000-8000-00805F9B34FB"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", bleUuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
        listOf("", "2a1", "2a19x", "12345", "6e400001-b5a3-f393-e0a9", "6e400001b5a3f393e0a9e50e24dcca9e0").forEach {
            assertNull(bleUuid(it), it)
        }
    }

    @Test
    fun `known names stand for their UUIDs, and UUIDs have their names`() {
        assertEquals(level, bleUuid("battery-level"))
        assertEquals(level, bleUuid("Battery-Level"))
        assertEquals(battery, bleUuid("battery-service"))
        assertEquals("battery-level", bleName(level))
        assertEquals("battery-service", bleName(battery))
        assertEquals("nordic-uart", bleName("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", bleUuid("nus-rx"))
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", bleUuid("nus-tx"))
        mapOf(
            "generic-access" to "1800", "device-information" to "180a", "heart-rate" to "180d",
            "device-name" to "2a00", "manufacturer-name" to "2a29", "model-number" to "2a24",
            "serial-number" to "2a25", "firmware-revision" to "2a26", "heart-rate-measurement" to "2a37",
        ).forEach { (name, short) -> assertEquals(bleUuid(short), bleUuid(name), name) }
        assertNull(bleName("0000ffe1-0000-1000-8000-00805f9b34fb"))
        assertNull(bleUuid("no-such-thing"))
    }

    @Test
    fun `formats by their words, and the defaults of known characteristics`() {
        assertEquals(BleFormat.U8, bleFormat("u8"))
        assertEquals(BleFormat.FLOAT32, bleFormat("float32"))
        assertNull(bleFormat("u64"))
        assertEquals(BleFormat.U8, defaultFormat(level))
        assertEquals(BleFormat.TEXT, defaultFormat(bleUuid("device-name")!!))
        assertEquals(BleFormat.HEX, defaultFormat("0000ffe1-0000-1000-8000-00805f9b34fb"))
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `values as JSON in each format, little-endian`() {
        assertEquals("\"0a 1b ff\"", BleFormat.HEX.json(bytes(0x0a, 0x1b, 0xff)))
        assertEquals("\"\"", BleFormat.HEX.json(bytes()))
        assertEquals("\"hé\\n\"", BleFormat.TEXT.json("hé\n".toByteArray()))
        assertEquals("255", BleFormat.U8.json(bytes(0xff)))
        assertEquals("-1", BleFormat.I8.json(bytes(0xff)))
        assertEquals("6699", BleFormat.U16.json(bytes(0x2b, 0x1a)))
        assertEquals("-2", BleFormat.I16.json(bytes(0xfe, 0xff)))
        assertEquals("4294967295", BleFormat.U32.json(bytes(0xff, 0xff, 0xff, 0xff)))
        assertEquals("-2147483648", BleFormat.I32.json(bytes(0, 0, 0, 0x80)))
        assertEquals("1.5", BleFormat.FLOAT32.json(bytes(0, 0, 0xc0, 0x3f)))
        // A number of the wrong size isn't guessed at: hex still has it.
        assertEquals("null", BleFormat.U16.json(bytes(1)))
        assertEquals("null", BleFormat.U8.json(bytes(1, 2)))
        assertEquals("null", BleFormat.FLOAT32.json(bytes(0, 0, 0xc0, 0x7f)))
    }

    @Test
    fun `values parsed for writing`() {
        assertContentEquals(bytes(0x0a, 0x1b), BleFormat.HEX.parse("0a1b"))
        assertContentEquals(bytes(0x0a, 0x1b, 0xff), BleFormat.HEX.parse("0a 1B ff"))
        assertContentEquals("hi there\n".toByteArray(), BleFormat.TEXT.parse("hi there\n"))
        assertContentEquals(bytes(0xff), BleFormat.U8.parse("255"))
        assertContentEquals(bytes(0xff), BleFormat.I8.parse("-1"))
        assertContentEquals(bytes(0x2b, 0x1a), BleFormat.U16.parse("6699"))
        assertContentEquals(bytes(0xfe, 0xff), BleFormat.I16.parse("-2"))
        assertContentEquals(bytes(0xff, 0xff, 0xff, 0xff), BleFormat.U32.parse("4294967295"))
        assertContentEquals(bytes(0, 0, 0, 0x80), BleFormat.I32.parse("-2147483648"))
        assertContentEquals(bytes(0, 0, 0xc0, 0x3f), BleFormat.FLOAT32.parse("1.5"))
    }

    @Test
    fun `bad values are refused with what would do`() {
        fun refused(format: BleFormat, text: String) = assertFailsWith<IllegalArgumentException> { format.parse(text) }.message
        assertEquals("'0a1' isn't hex: two digits a byte, like 0a1b or 0a 1b", refused(BleFormat.HEX, "0a1"))
        assertEquals("'zz' isn't hex: two digits a byte, like 0a1b or 0a 1b", refused(BleFormat.HEX, "zz"))
        assertEquals("u8 is 0 to 255, not '256'", refused(BleFormat.U8, "256"))
        assertEquals("u8 is 0 to 255, not '-1'", refused(BleFormat.U8, "-1"))
        assertEquals("i8 is -128 to 127, not '128'", refused(BleFormat.I8, "128"))
        assertEquals("u16 is 0 to 65535, not 'x'", refused(BleFormat.U16, "x"))
        assertEquals("i16 is -32768 to 32767, not '40000'", refused(BleFormat.I16, "40000"))
        assertEquals("u32 is 0 to 4294967295, not '4294967296'", refused(BleFormat.U32, "4294967296"))
        assertEquals("i32 is -2147483648 to 2147483647, not '2147483648'", refused(BleFormat.I32, "2147483648"))
        assertEquals("'one' isn't a number", refused(BleFormat.FLOAT32, "one"))
    }
}
