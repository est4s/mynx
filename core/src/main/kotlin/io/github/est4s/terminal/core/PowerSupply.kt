package io.github.est4s.terminal.core

import java.io.File

/**
 * Where Linux programs look for the battery and chargers (starship,
 * htop, acpi, psutil). Android blocks it, so the app keeps a stand-in
 * written by [writePowerSupply], and proot binds it here.
 */
const val POWER_SUPPLY_MOUNT = "/sys/class/power_supply"

private val KERNEL_STATUSES = mapOf(2 to "Charging", 3 to "Discharging", 4 to "Not charging", 5 to "Full")
private val KERNEL_HEALTHS = mapOf(
    2 to "Good", 3 to "Overheat", 4 to "Dead", 5 to "Over voltage", 6 to "Unspecified failure", 7 to "Cold",
)
// Folder, the kernel's type, and Android's plug that puts it online.
private val CHARGERS = listOf(Triple("ac", "Mains", 1), Triple("usb", "USB", 2), Triple("wireless", "Wireless", 4))

/**
 * The stand-in's files for one reading, by path under [POWER_SUPPLY_MOUNT]:
 * a `battery` folder and one per charger, each with a file per value in
 * the kernel's names and units, and a `uevent` with them all (htop reads
 * that). Values the phone doesn't give have no file.
 */
fun powerSupplyFiles(r: BatteryReading): Map<String, String> {
    val battery = listOf(
        "type" to "Battery",
        "present" to "1",
        "status" to (KERNEL_STATUSES[r.status] ?: "Unknown"),
        "health" to (KERNEL_HEALTHS[r.health] ?: "Unknown"),
        "capacity" to percent(r),
        "technology" to technology(r),
        "temp" to known(r.temperature),
        "voltage_now" to microvolts(r),
        "current_now" to microamps(r, r.current),
        "current_avg" to microamps(r, r.currentAverage),
        // Android's charge counter is what's left, which the kernel calls charge_now.
        "charge_now" to microampHours(r),
        "charge_counter" to microampHours(r),
        "cycle_count" to cycles(r),
        "time_to_full_now" to chargeSeconds(r),
    ).mapNotNull { (name, value) -> value?.let { name to it.toString() } }
    val chargers = CHARGERS.map { (name, type, plug) ->
        name to listOf("type" to type, "online" to if (r.plugged and plug != 0) "1" else "0")
    }
    return (listOf("battery" to battery) + chargers).flatMap { (folder, values) ->
        val uevent = (listOf("name" to folder) + values).joinToString("") { (key, value) ->
            "POWER_SUPPLY_${key.uppercase()}=$value\n"
        }
        values.map { (key, value) -> "$folder/$key" to "$value\n" } + ("$folder/uevent" to uevent)
    }.toMap()
}

/**
 * Brings the stand-in in [dir] up to date with [reading]: changed files
 * are replaced whole (renamed into place), unchanged ones left alone,
 * and files for values the phone stopped giving removed.
 */
fun writePowerSupply(dir: File, reading: BatteryReading) {
    val files = powerSupplyFiles(reading)
    for ((path, content) in files) {
        val file = File(dir, path)
        if (file.isFile && runCatching { file.readText() }.getOrNull() == content) continue
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(file)
    }
    dir.walkBottomUp().filter { it.isFile }.forEach { file ->
        if (file.relativeTo(dir).path !in files) file.delete()
    }
}
