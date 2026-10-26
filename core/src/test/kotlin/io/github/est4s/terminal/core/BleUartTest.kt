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

    private val nordic = BleUartKind.NORDIC
    private val hm10 = BleUartKind.HM10

    private fun char(service: String, uuid: String, vararg props: BleProp, instance: Int = 0) =
        BleChar(service, uuid, instance, props.toSet(), emptyList())

    private fun nordicService(rx: Set<BleProp>, tx: Set<BleProp>) = BleService(
        nordic.service,
        listOf(
            BleChar(nordic.service, nordic.rx, 1, rx, emptyList()),
            BleChar(nordic.service, nordic.tx, 2, tx, emptyList()),
        ),
    )

    private val hm10Service = BleService(
        hm10.service,
        listOf(char(hm10.service, hm10.rx, BleProp.READ, BleProp.WRITE, BleProp.WRITE_NO_RESPONSE, BleProp.NOTIFY, instance = 7)),
    )

    @Test
    fun `finds Nordic's UART, writing without a response when rx can`() {
        val service = nordicService(setOf(BleProp.WRITE, BleProp.WRITE_NO_RESPONSE), setOf(BleProp.NOTIFY))
        val uart = bleUart(listOf(BleService("0000180f-0000-1000-8000-00805f9b34fb", emptyList()), service))
        assertEquals(BleUart(nordic, service.characteristics[0], service.characteristics[1], withResponse = false), uart)
    }

    @Test
    fun `writes with a response when rx only takes those, and takes indicate for tx`() {
        val service = nordicService(setOf(BleProp.WRITE), setOf(BleProp.INDICATE))
        val uart = bleUart(listOf(service))!!
        assertTrue(uart.withResponse)
        assertEquals(service.characteristics[1], uart.tx)
    }

    @Test
    fun `finds an HM-10's one characteristic for both ways`() {
        val uart = bleUart(listOf(hm10Service))!!
        assertEquals(hm10, uart.kind)
        assertEquals(hm10Service.characteristics[0], uart.rx)
        assertEquals(uart.rx, uart.tx)
        assertFalse(uart.withResponse)
    }

    @Test
    fun `Nordic's comes first, whatever the device's order`() {
        val service = nordicService(setOf(BleProp.WRITE_NO_RESPONSE), setOf(BleProp.NOTIFY))
        assertEquals(nordic, bleUart(listOf(hm10Service, service))?.kind)
    }

    @Test
    fun `a UART that can't be written or doesn't notify isn't one`() {
        assertEquals(null, bleUart(listOf(nordicService(setOf(BleProp.READ), setOf(BleProp.NOTIFY)))))
        assertEquals(null, bleUart(listOf(nordicService(setOf(BleProp.WRITE), setOf(BleProp.READ)))))
        val unusable = nordicService(setOf(BleProp.WRITE), setOf(BleProp.READ))
        assertEquals(hm10, bleUart(listOf(unusable, hm10Service))?.kind)
        assertEquals(null, bleUart(listOf(BleService(hm10.service, listOf(char(hm10.service, hm10.rx, BleProp.READ))))))
        assertEquals(null, bleUart(emptyList()))
    }

    @Test
    fun `a characteristic outside the UART's service doesn't count`() {
        val elsewhere = BleService(
            "12345678-1234-5678-1234-56789abcdef0",
            listOf(char("12345678-1234-5678-1234-56789abcdef0", hm10.rx, BleProp.WRITE, BleProp.NOTIFY)),
        )
        assertEquals(null, bleUart(listOf(elsewhere)))
    }
}
