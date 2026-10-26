package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** Where Debian sees the USB devices' sysfs stand-in ([writeUsbDevices]): a `/sys/bus` of its own. */
const val SYS_BUS_MOUNT = "/sys/bus"

/** The app's socket that hands out USB devices' fds, in Debian's own `/dev`. */
const val USB_SOCKET_NAME = "bus/usb/.ctl"

/**
 * A USB device the app may use: Android's [name] for it
 * (`/dev/bus/usb/BBB/DDD`), its raw [descriptors] (the device's, then its
 * configurations', as `UsbDeviceConnection.getRawDescriptors()` gives them)
 * and the strings Android read from it, with its interfaces' names by
 * number (`UsbInterface.getName()`).
 */
class RawUsbDevice(
    val name: String,
    val descriptors: ByteArray,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val interfaceNames: Map<Int, String> = emptyMap(),
) {
    fun copy(
        name: String = this.name,
        descriptors: ByteArray = this.descriptors,
        manufacturer: String? = this.manufacturer,
        product: String? = this.product,
        serial: String? = this.serial,
        interfaceNames: Map<Int, String> = this.interfaceNames,
    ) = RawUsbDevice(name, descriptors, manufacturer, product, serial, interfaceNames)
}

private class UsbInterfaceInfo(val number: Int, val endpoints: Int, val cls: Int, val subclass: Int, val protocol: Int)

private class UsbConfigInfo(val value: Int, val interfaces: Int, val list: List<UsbInterfaceInfo>)

// The first configuration (the one Android sets) and its interfaces' first
// settings, read as far as the descriptors go.
private fun firstConfig(d: ByteArray): UsbConfigInfo? {
    fun byte(i: Int) = d[i].toInt() and 0xff
    var i = byte(0).coerceAtLeast(DEVICE_DESCRIPTOR_SIZE)
    var config: Pair<Int, Int>? = null
    val interfaces = mutableListOf<UsbInterfaceInfo>()
    while (i + 2 <= d.size) {
        val length = byte(i)
        if (length < 2 || i + length > d.size) break
        when (byte(i + 1)) {
            2 -> if (config == null && length >= 9) config = byte(i + 5) to byte(i + 4) else break
            4 -> if (config != null && length >= 9 && byte(i + 3) == 0) {
                interfaces += UsbInterfaceInfo(byte(i + 2), byte(i + 4), byte(i + 5), byte(i + 6), byte(i + 7))
            }
        }
        i += length
    }
    return config?.let { (value, count) -> UsbConfigInfo(value, count, interfaces) }
}

private val DEVICE_NAME = Regex("/dev/bus/usb/(\\d{3})/(\\d{3})")
private val REQUEST = Regex("(\\d{3}/\\d{3})\n")
private const val DEVICE_DESCRIPTOR_SIZE = 18

/** Bus and address from Android's name for a device, `/dev/bus/usb/BBB/DDD`. */
fun usbBusAddress(name: String): Pair<Int, Int>? =
    DEVICE_NAME.matchEntire(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** The device a request on the app's socket names (`BBB/DDD\n`), as Android names it. */
fun usbFdRequest(line: String): String? =
    REQUEST.matchEntire(line)?.let { "/dev/bus/usb/${it.groupValues[1]}" }

/** The reply to a request: `ok` (the fd comes with it), else the errno open() would give. */
fun usbFdReply(present: Boolean, allowed: Boolean): String = when {
    !present -> "err 19\n" // ENODEV
    !allowed -> "err 13\n" // EACCES
    else -> "ok\n"
}

/**
 * Describes [devices] to Debian as Linux's sysfs and `/dev` do, so libusb
 * (through libudev) and tools that read sysfs find them: an entry
 * `usb/devices/B-D` in [sysBusDir] (bound at [SYS_BUS_MOUNT]) and a node
 * `bus/usb/BBB/DDD` in [devDir] (Debian's own `/dev`), which proot's
 * mynx_usb extension opens through the app. Those of devices that left go.
 */
fun writeUsbDevices(sysBusDir: File, devDir: File, devices: List<RawUsbDevice>) {
    val entries = File(sysBusDir, "usb/devices")
    val nodes = File(devDir, "bus/usb")
    entries.mkdirs()
    nodes.mkdirs()
    val wanted = devices.mapNotNull { d ->
        val (bus, address) = usbBusAddress(d.name) ?: return@mapNotNull null
        if (d.descriptors.size < DEVICE_DESCRIPTOR_SIZE) null else Triple(bus, address, d)
    }
    val names = wanted.map { (bus, address) -> "$bus-$address" }.toSet()
    entries.listFiles().orEmpty().filter { it.name !in names }.forEach { it.deleteRecursively() }
    val nodeNames = wanted.map { (bus, address) -> "%03d/%03d".format(bus, address) }.toSet()
    nodes.listFiles().orEmpty().filter { it.isDirectory }.forEach { busDir ->
        busDir.listFiles().orEmpty().filter { "${busDir.name}/${it.name}" !in nodeNames }.forEach { it.delete() }
        if (busDir.list().isNullOrEmpty()) busDir.delete()
    }
    for ((bus, address, device) in wanted) {
        writeEntry(File(entries, "$bus-$address"), bus, address, device)
        File(nodes, "%03d/%03d".format(bus, address)).apply { parentFile.mkdirs(); if (!exists()) createNewFile() }
    }
}

private fun writeEntry(dir: File, bus: Int, address: Int, device: RawUsbDevice) {
    dir.mkdirs()
    val d = device.descriptors
    fun byte(i: Int) = d[i].toInt() and 0xff
    fun word(i: Int) = byte(i) or (byte(i + 1) shl 8)
    val bcdUsb = word(2)
    val vendor = word(8)
    val product = word(10)
    val bcdDevice = word(12)
    val attrs = linkedMapOf(
        // No MAJOR/MINOR: libudev's enumerator skips a device with a
        // device number and no udev database entry.
        "uevent" to "DEVTYPE=usb_device\nDEVNAME=bus/usb/%03d/%03d\nPRODUCT=%x/%x/%x\nTYPE=%d/%d/%d\nBUSNUM=%03d\nDEVNUM=%03d\n"
            .format(bus, address, vendor, product, bcdDevice, byte(4), byte(5), byte(6), bus, address),
        "busnum" to "$bus\n",
        "devnum" to "$address\n",
        "idVendor" to "%04x\n".format(vendor),
        "idProduct" to "%04x\n".format(product),
        "bcdDevice" to "%04x\n".format(bcdDevice),
        "bDeviceClass" to "%02x\n".format(byte(4)),
        "bDeviceSubClass" to "%02x\n".format(byte(5)),
        "bDeviceProtocol" to "%02x\n".format(byte(6)),
        "bMaxPacketSize0" to "${byte(7)}\n",
        "bNumConfigurations" to "${byte(17)}\n",
        // Android doesn't say; nearly every device has the one configuration, 1.
        "bConfigurationValue" to "1\n",
        "version" to "%2x.%02x\n".format(bcdUsb shr 8, bcdUsb and 0xff),
    )
    val config = firstConfig(d)
    config?.let { attrs["bNumInterfaces"] = "%2d\n".format(it.interfaces) }
    device.manufacturer?.let { attrs["manufacturer"] = "$it\n" }
    device.product?.let { attrs["product"] = "$it\n" }
    device.serial?.let { attrs["serial"] = "$it\n" }
    listOf("manufacturer", "product", "serial").filter { it !in attrs }.forEach { File(dir, it).delete() }
    attrs.forEach { (name, value) -> File(dir, name).writeText(value) }
    File(dir, "descriptors").writeBytes(d)
    // One folder per interface, as nusb (probe-rs) and `lsusb -t` read them.
    val folders = config?.list.orEmpty().associateBy { "$bus-$address:${config!!.value}.${it.number}" }
    dir.listFiles().orEmpty().filter { ':' in it.name && it.name !in folders }.forEach { it.deleteRecursively() }
    folders.forEach { (name, i) ->
        val folder = File(dir, name).apply { mkdirs() }
        File(folder, "bInterfaceNumber").writeText("%02x\n".format(i.number))
        File(folder, "bAlternateSetting").writeText("00\n")
        File(folder, "bNumEndpoints").writeText("%02x\n".format(i.endpoints))
        File(folder, "bInterfaceClass").writeText("%02x\n".format(i.cls))
        File(folder, "bInterfaceSubClass").writeText("%02x\n".format(i.subclass))
        File(folder, "bInterfaceProtocol").writeText("%02x\n".format(i.protocol))
        val label = File(folder, "interface")
        device.interfaceNames[i.number]?.let { label.writeText("$it\n") } ?: label.delete()
    }
    val subsystem = File(dir, "subsystem").toPath()
    if (!Files.isSymbolicLink(subsystem)) Files.createSymbolicLink(subsystem, Paths.get("../../../bus/usb"))
}
