package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleUartTest {
    @Test
    fun `nus names run from nus0 to nus255`() {
        assertTrue(isNusName("nus0"))
        assertTrue(isNusName("nus255"))
        assertFalse(isNusName("nus256"))
        assertFalse(isNusName("nus01"))
        assertFalse(isNusName("rfcomm0"))
        assertFalse(isNusName("nus"))
    }

    @Test
    fun `nus ports are served past Bluetooth serial's`() {
        assertTrue(FIRST_NUS_TCP_PORT > FIRST_BLUETOOTH_TCP_PORT + 99)
    }

    @Test
    fun `Nordic's UART is tried before HM-10's`() {
        assertEquals(listOf("nordic-uart", "hm-10"), BleUartKind.entries.map { it.word })
        assertEquals(BleUartKind.HM10.rx, BleUartKind.HM10.tx)
    }

    @Test
    fun `a binding's address is public unless it says random`() {
        assertFalse(BtBinding("nus0", "1C:DB:D4:C5:CF:36").random)
    }
}
