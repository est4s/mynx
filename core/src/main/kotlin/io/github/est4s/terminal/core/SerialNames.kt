package io.github.est4s.terminal.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Where Debian sees the serial ports' sysfs stand-in. */
const val SYS_TTY_MOUNT = "/sys/class/tty"

/**
 * Android's device files that Debian keeps when it has a `/dev` of its
 * own, each bound in when the phone has it. Not its `ttyS*`: those aren't
 * ours to open, and tools would list them as ports.
 */
val HOST_DEVICES = listOf(
    "null", "zero", "full", "random", "urandom", "tty", "ptmx", "pts",
    "ashmem", "kgsl-3d0", "dri", "dma_heap", "ion", "mali0", "fuse", "tun", "net",
)

// The links Linux's /dev has; proot resolves /proc/self for each program.
private val DEV_LINKS = mapOf(
    "fd" to "/proc/self/fd",
    "stdin" to "/proc/self/fd/0",
    "stdout" to "/proc/self/fd/1",
    "stderr" to "/proc/self/fd/2",
)

private val PORT_NAME = Regex("""tty(USB|ACM)\d+""")

/** What a host path is, for [prepareOwnDev]'s probe. */
enum class HostEntry { FILE, DIR }

/**
 * A `/dev` of Debian's own: [dir] (a host path) bound at `/dev`, with the
 * host's [devices] bound into it, and [sysTty] at [SYS_TTY_MOUNT]. Its
 * own, so tools can list it and the ports' names come and go in it:
 * Android doesn't let apps list its `/dev`, and proot can't add a bind
 * once started (see [writeSerialNames]).
 */
data class OwnDev(val dir: String, val devices: List<String>, val sysTty: String)

/**
 * Sets up [devDir] and [sysTtyDir] for an [OwnDev]: a placeholder for each
 * of [HOST_DEVICES] that [probe] (given a name in the host's `/dev`) finds,
 * so listings show it, Linux's links, and the folder for `/dev/serial`.
 * The ports' names are left alone.
 */
fun prepareOwnDev(devDir: File, sysTtyDir: File, probe: (String) -> HostEntry?): OwnDev {
    devDir.mkdirs()
    sysTtyDir.mkdirs()
    val devices = HOST_DEVICES.filter { name ->
        val kind = probe(name) ?: return@filter false
        val placeholder = File(devDir, name)
        if (kind == HostEntry.DIR) placeholder.mkdirs() else if (!placeholder.exists()) placeholder.createNewFile()
        true
    }
    DEV_LINKS.forEach { (name, target) -> setLink(File(devDir, name), target) }
    File(devDir, "serial").mkdirs()
    return OwnDev(devDir.absolutePath, devices, sysTtyDir.absolutePath)
}

/**
 * Gives each port of [devices] the names Linux tools look for: a link
 * `/dev/NAME` to its `/dev/serial` link, and a sysfs entry with its USB
 * details (`/sys/class/tty/NAME/device`, laid out as pyserial's
 * list_ports and go.bug.st/serial read it). Names of ports that are gone
 * go. [devDir] and [sysTtyDir] are an [OwnDev]'s.
 */
fun writeSerialNames(devDir: File, sysTtyDir: File, devices: List<UsbSerialDevice>) {
    val withPorts = devices.filter { it.ports.isNotEmpty() }
    val names = withPorts.flatMap { d -> d.ports.map { it.name } }.toSet()
    devDir.listFiles().orEmpty()
        .filter { PORT_NAME.matches(it.name) && it.name !in names }
        .forEach { Files.deleteIfExists(it.toPath()) }
    names.forEach { setLink(File(devDir, it), "serial/$it") }

    sysTtyDir.mkdirs()
    sysTtyDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    File(sysTtyDir, ".bus/usb").mkdirs()
    File(sysTtyDir, ".bus/usb-serial").mkdirs()
    withPorts.forEachIndexed { index, device ->
        val usbName = "1-${index + 1}"
        val usb = File(sysTtyDir, ".usb/$usbName").apply { mkdirs() }
        val acm = device.driver == "cdc-acm"
        // CDC ACM has a control and a data interface per port; the tty belongs to the control one.
        val step = if (acm) 2 else 1
        usb.attr("idVendor", "%04x".format(device.vendorId))
        usb.attr("idProduct", "%04x".format(device.productId))
        usb.attr("bNumInterfaces", "%2d".format(device.ports.size * step))
        device.serial?.let { usb.attr("serial", it) }
        device.manufacturer?.let { usb.attr("manufacturer", it) }
        device.product?.let { usb.attr("product", it) }
        device.ports.forEachIndexed { i, port ->
            val ifaceName = "$usbName:1.${i * step}"
            val iface = File(usb, ifaceName).apply { mkdirs() }
            setLink(File(iface, "subsystem"), "../../../.bus/usb")
            val target = if (acm) {
                ".usb/$usbName/$ifaceName"
            } else {
                File(iface, port.name).mkdirs()
                setLink(File(iface, "${port.name}/subsystem"), "../../../../.bus/usb-serial")
                ".usb/$usbName/$ifaceName/${port.name}"
            }
            File(sysTtyDir, port.name).mkdirs()
            setLink(File(sysTtyDir, "${port.name}/device"), "../$target")
        }
    }
}

private fun File.attr(name: String, value: String) = File(this, name).writeText("$value\n")

// Replaces whatever is at [link] in one step, so a program never finds it missing.
private fun setLink(link: File, target: String) {
    val path = link.toPath()
    if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == target) return
    val tmp = File(link.parentFile, ".${link.name}.new").toPath()
    Files.deleteIfExists(tmp)
    Files.createSymbolicLink(tmp, Paths.get(target))
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) link.deleteRecursively()
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
