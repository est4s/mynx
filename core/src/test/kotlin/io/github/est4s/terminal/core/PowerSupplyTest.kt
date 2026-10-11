package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PowerSupplyTest {
    private val dir = createTempDirectory("power-supply").toFile()

    private val charging = BatteryReading(
        level = 82, scale = 100, status = 2, plugged = 2, health = 2,
        temperature = 315, voltage = 4123, technology = "Li-ion",
        current = 1_204_000, currentAverage = 1_150_000, charge = 3_912_000,
        chargeTimeMs = 4_320_000, cycles = 142, powerSaver = false, thermal = 0,
        time = 1_791_177_403_145,
    )

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `the battery in the kernel's names and units`() {
        val files = powerSupplyFiles(charging)
        assertEquals(
            mapOf(
                "type" to "Battery",
                "present" to "1",
                "status" to "Charging",
                "health" to "Good",
                "capacity" to "82",
                "technology" to "Li-ion",
                "temp" to "315",
                "voltage_now" to "4123000",
                "current_now" to "1204000",
                "current_avg" to "1150000",
                "charge_now" to "3912000",
                "charge_counter" to "3912000",
                "charge_full" to "4770732",
                "cycle_count" to "142",
                "time_to_full_now" to "4320",
            ),
            files.filterKeys { it.startsWith("battery/") && it != "battery/uevent" }
                .mapKeys { it.key.removePrefix("battery/") }.mapValues { it.value.trimEnd('\n') },
        )
        assertEquals(
            """
            POWER_SUPPLY_NAME=battery
            POWER_SUPPLY_TYPE=Battery
            POWER_SUPPLY_PRESENT=1
            POWER_SUPPLY_STATUS=Charging
            POWER_SUPPLY_HEALTH=Good
            POWER_SUPPLY_CAPACITY=82
            POWER_SUPPLY_TECHNOLOGY=Li-ion
            POWER_SUPPLY_TEMP=315
            POWER_SUPPLY_VOLTAGE_NOW=4123000
            POWER_SUPPLY_CURRENT_NOW=1204000
            POWER_SUPPLY_CURRENT_AVG=1150000
            POWER_SUPPLY_CHARGE_NOW=3912000
            POWER_SUPPLY_CHARGE_COUNTER=3912000
            POWER_SUPPLY_CHARGE_FULL=4770732
            POWER_SUPPLY_CYCLE_COUNT=142
            POWER_SUPPLY_TIME_TO_FULL_NOW=4320

            """.trimIndent(),
            files["battery/uevent"],
        )
        // Each file ends in a line break, like the kernel's.
        assertEquals("82\n", files["battery/capacity"])
    }

    @Test
    fun `chargers are online when the phone is plugged into them`() {
        val files = powerSupplyFiles(charging)
        assertEquals("Mains\n", files["ac/type"])
        assertEquals("USB\n", files["usb/type"])
        assertEquals("Wireless\n", files["wireless/type"])
        assertEquals(listOf("0\n", "1\n", "0\n"), listOf("ac", "usb", "wireless").map { files["$it/online"] })
        assertEquals("POWER_SUPPLY_NAME=usb\nPOWER_SUPPLY_TYPE=USB\nPOWER_SUPPLY_ONLINE=1\n", files["usb/uevent"])
        val onAc = powerSupplyFiles(charging.copy(plugged = 1))
        assertEquals(listOf("1\n", "0\n", "0\n"), listOf("ac", "usb", "wireless").map { onAc["$it/online"] })
        val unplugged = powerSupplyFiles(charging.copy(plugged = 0, status = 3))
        assertEquals(listOf("0\n", "0\n", "0\n"), listOf("ac", "usb", "wireless").map { unplugged["$it/online"] })
    }

    @Test
    fun `status and health as the kernel spells them`() {
        val statuses = listOf(1 to "Unknown", 2 to "Charging", 3 to "Discharging", 4 to "Not charging", 5 to "Full")
        for ((status, name) in statuses) assertEquals("$name\n", powerSupplyFiles(charging.copy(status = status))["battery/status"])
        val healths = listOf(
            1 to "Unknown", 2 to "Good", 3 to "Overheat", 4 to "Dead", 5 to "Over voltage", 6 to "Unspecified failure",
            7 to "Cold",
        )
        for ((health, name) in healths) assertEquals("$name\n", powerSupplyFiles(charging.copy(health = health))["battery/health"])
    }

    @Test
    fun `what the phone doesn't say has no file`() {
        val files = powerSupplyFiles(
            charging.copy(
                level = -1, temperature = null, voltage = null, technology = null, current = Int.MIN_VALUE,
                currentAverage = null, charge = 0, chargeTimeMs = -1, cycles = -1,
            ),
        )
        assertEquals(
            listOf("type", "present", "status", "health", "uevent"),
            files.keys.filter { it.startsWith("battery/") }.map { it.removePrefix("battery/") },
        )
    }

    @Test
    fun `the full charge is worked out from what's left and the level, as acpi needs it`() {
        assertEquals("4808333\n", powerSupplyFiles(charging.copy(level = 60, charge = 2_885_000))["battery/charge_full"])
        assertEquals("4808333\n", powerSupplyFiles(charging.copy(level = 120, scale = 200, charge = 2_885_000))["battery/charge_full"])
        assertEquals(null, powerSupplyFiles(charging.copy(level = 0))["battery/charge_full"])
        assertEquals(null, powerSupplyFiles(charging.copy(charge = 0))["battery/charge_full"])
    }

    @Test
    fun `the current is its size only, as laptops give it, which psutil expects`() {
        val files = powerSupplyFiles(charging.copy(status = 3, plugged = 0, current = 450_000, currentAverage = -380_000))
        assertEquals("450000\n", files["battery/current_now"])
        assertEquals("380000\n", files["battery/current_avg"])
        assertEquals("890000\n", powerSupplyFiles(charging.copy(current = -890_000))["battery/current_now"])
    }

    @Test
    fun `voltage in microvolts, also from phones that report volts`() {
        assertEquals("4000000\n", powerSupplyFiles(charging.copy(voltage = 4))["battery/voltage_now"])
    }

    @Test
    fun `writes the folders, and removes files the phone stopped filling`() {
        writePowerSupply(dir, charging)
        assertEquals("82\n", File(dir, "battery/capacity").readText())
        assertEquals("1\n", File(dir, "usb/online").readText())
        assertEquals("4320\n", File(dir, "battery/time_to_full_now").readText())

        writePowerSupply(dir, charging.copy(level = 83, status = 3, plugged = 0, chargeTimeMs = -1))
        assertEquals("83\n", File(dir, "battery/capacity").readText())
        assertEquals("0\n", File(dir, "usb/online").readText())
        assertFalse(File(dir, "battery/time_to_full_now").exists())
        // Nothing half-written is left behind.
        assertEquals(emptyList(), dir.walk().filter { it.name.endsWith(".tmp") }.toList())
    }

    @Test
    fun `leaves unchanged files alone`() {
        writePowerSupply(dir, charging)
        val type = File(dir, "battery/type")
        type.setLastModified(1_000_000_000_000)
        writePowerSupply(dir, charging.copy(level = 83))
        assertEquals(1_000_000_000_000, type.lastModified())
    }
}
