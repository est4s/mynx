"""Flashing UF2 files onto a bootloader's USB drive.

A UF2 bootloader (the RP2040's and RP2350's BOOTSEL, TinyUF2, Adafruit's)
shows a FAT drive and takes the file's 512-byte blocks in whatever sectors
they land: it checks each written sector for UF2's magic numbers. Android
sees the drive but calls it damaged and doesn't mount it, so this writes
the blocks itself, as SCSI WRITE(10) commands over USB mass storage's
bulk-only transport, through the device's raw USB node (which the app
hands out, see the guide's "Raw USB devices"). The blocks go one per
sector from the start of the data area, leaving the FAT alone. Only a
drive with INFO_UF2.TXT in its root folder is written to, so an ordinary
USB stick is never touched.
"""
import ctypes
import errno
import fcntl
import os
import struct
import time

MAGIC_START0 = 0x0A324655
MAGIC_START1 = 0x9E5D5157
MAGIC_END = 0x0AB16F30
FLAG_FAMILY = 0x2000
SECTOR = 512

SYS_DEVICES = "/sys/bus/usb/devices"
DEV_USB = "/dev/bus/usb"

# Microsoft's uf2families.json, the common ones.
FAMILIES = {
    0xE48BFF56: "rp2040", 0xE48BFF57: "absolute", 0xE48BFF58: "data",
    0xE48BFF59: "rp2350-arm-s", 0xE48BFF5A: "rp2350-riscv", 0xE48BFF5B: "rp2350-arm-ns",
    0x68ED2B88: "samd21", 0x55114460: "samd51", 0xADA52840: "nrf52840",
    0xBFDD4EEE: "esp32s2", 0xC47E5767: "esp32s3", 0xD42BA06C: "esp32c3",
    0x57755A57: "stm32f4",
}
# The bootloaders known to skip blocks of other families without a word:
# a file for another chip would "flash" and change nothing.
BOARD_FAMILIES = {
    "RPI-RP2": {0xE48BFF56},
    "RP2350": {0xE48BFF57, 0xE48BFF58, 0xE48BFF59, 0xE48BFF5A, 0xE48BFF5B},
}

WRITE_SECTORS = 32  # 16 KB per WRITE(10): Android's older kernels' bulk limit
NOT_READY_TRIES = 20


class Uf2Error(Exception):
    pass


class Image:
    def __init__(self, data, blocks, families):
        self.data, self.blocks, self.families = data, blocks, families


def read_uf2(data):
    """Checks every block of a UF2 file; gives its blocks and families."""
    if not data:
        raise Uf2Error("the file is empty")
    if len(data) % SECTOR or struct.unpack_from("<II", data) != (MAGIC_START0, MAGIC_START1):
        raise Uf2Error("not a UF2 file")
    blocks = len(data) // SECTOR
    families = set()
    for n in range(blocks):
        at = n * SECTOR
        start0, start1, flags = struct.unpack_from("<III", data, at)
        (end,) = struct.unpack_from("<I", data, at + SECTOR - 4)
        if (start0, start1, end) != (MAGIC_START0, MAGIC_START1, MAGIC_END):
            raise Uf2Error(f"block {n + 1} of {blocks} is damaged")
        if flags & FLAG_FAMILY:
            families.add(struct.unpack_from("<I", data, at + 28)[0])
    return Image(data, blocks, families)


def family_names(families):
    return sorted(FAMILIES.get(f, f"{f:#010x}") for f in families)


class DriveInfo:
    def __init__(self, label, board, model, data_start, sectors):
        self.label, self.board, self.model = label, board, model
        self.data_start, self.sectors = data_start, sectors


class Scsi:
    """SCSI commands over bulk-only transport on a [pipe] with bulk_out(data)
    and bulk_in(length)."""

    def __init__(self, pipe):
        self.pipe, self.tag = pipe, 0

    def command(self, cdb, read=0, write=b""):
        self.tag += 1
        length = read or len(write)
        cbw = struct.pack("<IIIBBB", 0x43425355, self.tag, length, 0x80 if read else 0, 0, len(cdb))
        self.pipe.bulk_out(cbw + cdb.ljust(16, b"\0"))
        data = b""
        if read:
            data = self.pipe.bulk_in(read)
        elif write:
            self.pipe.bulk_out(write)
        csw = self.pipe.bulk_in(13)
        if len(csw) != 13 or struct.unpack_from("<I", csw)[0] != 0x53425355:
            raise Uf2Error("the drive gave no proper status")
        return data, csw[12]

    def read(self, lba, count):
        data, status = self.command(struct.pack(">BBIBHB", 0x28, 0, lba, 0, count, 0), read=count * SECTOR)
        if status or len(data) != count * SECTOR:
            raise Uf2Error(f"the drive couldn't read sector {lba}")
        return data

    def write(self, lba, data):
        return self.command(struct.pack(">BBIBHB", 0x2A, 0, lba, 0, len(data) // SECTOR, 0), write=data)[1]


def wait_ready(scsi, wait):
    for _ in range(NOT_READY_TRIES):
        if scsi.command(bytes(6))[1] == 0:
            return
        scsi.command(bytes([0x03, 0, 0, 0, 18, 0]), read=18)  # REQUEST SENSE clears the condition
        time.sleep(wait)
    raise Uf2Error("the drive isn't ready")


def read_drive(pipe, wait=0.25):
    """Reads the drive's size and FAT; refuses one that isn't a UF2 bootloader."""
    scsi = Scsi(pipe)
    wait_ready(scsi, wait)
    data, status = scsi.command(bytes([0x25]) + bytes(9), read=8)
    if status or len(data) != 8:
        raise Uf2Error("the drive didn't say its size")
    last, size = struct.unpack(">II", data)
    if size != SECTOR:
        raise Uf2Error(f"the drive has {size}-byte sectors: not a UF2 bootloader")
    sectors = last + 1
    boot, start = scsi.read(0, 1), 0
    if not has_bpb(boot) and boot[510:512] == b"\x55\xaa":
        start = struct.unpack_from("<I", boot, 446 + 8)[0]  # the first partition
        boot = scsi.read(start, 1) if 0 < start < sectors else b""
    if not has_bpb(boot):
        raise Uf2Error("the drive has no FAT: not a UF2 bootloader")
    per_sector, per_cluster, reserved, fats, root_entries = struct.unpack_from("<HBHBH", boot, 11)
    fat_size = struct.unpack_from("<H", boot, 22)[0] or struct.unpack_from("<I", boot, 36)[0]
    root_sectors = (root_entries * 32 + SECTOR - 1) // SECTOR
    data_start = start + reserved + fats * fat_size + root_sectors

    def cluster_lba(cluster):
        return data_start + (cluster - 2) * per_cluster

    if root_entries:
        root = scsi.read(start + reserved + fats * fat_size, root_sectors)
    else:  # FAT32: the root folder's first cluster
        root = scsi.read(cluster_lba(struct.unpack_from("<I", boot, 44)[0]), per_cluster)
    label, info = None, None
    for at in range(0, len(root), 32):
        entry = root[at:at + 32]
        if entry[0] == 0:
            break
        if entry[0] == 0xE5 or entry[11] == 0x0F:
            continue
        if entry[11] & 0x08:
            label = entry[:11].decode("ascii", "replace").strip()
        elif entry[:11] == b"INFO_UF2TXT":
            cluster = struct.unpack_from("<H", entry, 26)[0] | struct.unpack_from("<H", entry, 20)[0] << 16
            length = min(struct.unpack_from("<I", entry, 28)[0], 4096)
            info = scsi.read(cluster_lba(cluster), max(1, (length + SECTOR - 1) // SECTOR))[:length]
    if info is None:
        raise Uf2Error(f"{label or 'the drive'} has no INFO_UF2.TXT: not a UF2 bootloader")
    fields = {}
    for line in info.decode("utf-8", "replace").splitlines():
        key, _, value = line.partition(":")
        fields[key.strip().lower()] = value.strip()
    return DriveInfo(label, fields.get("board-id"), fields.get("model"), data_start, sectors)


def has_bpb(sector):
    return (len(sector) == SECTOR and sector[0] in (0xEB, 0xE9)
            and struct.unpack_from("<H", sector, 11)[0] == SECTOR and sector[13] > 0)


def flash(pipe, drive, image, progress):
    """Writes the [image]'s blocks, one per sector from the data area;
    [progress] gets the number of blocks written so far."""
    accepted = BOARD_FAMILIES.get(drive.board)
    if accepted and image.families and not image.families & accepted:
        raise Uf2Error(f"the file is for {', '.join(family_names(image.families))}: "
                       f"{drive.board} takes {', '.join(family_names(accepted))}")
    if drive.data_start + image.blocks > drive.sectors:
        raise Uf2Error(f"the file is too big for the drive: {image.blocks} blocks, room for "
                       f"{drive.sectors - drive.data_start}")
    scsi = Scsi(pipe)
    done = 0
    while done < image.blocks:
        count = min(WRITE_SECTORS, image.blocks - done)
        last = done + count == image.blocks
        try:
            status = scsi.write(drive.data_start + done, image.data[done * SECTOR:(done + count) * SECTOR])
        except (OSError, Uf2Error):
            # A board starts the new firmware once it has the last block,
            # often before it says how the write went.
            if last:
                progress(image.blocks)
                return
            raise Uf2Error(f"the board stopped answering after {done + count} of {image.blocks} blocks")
        if status:
            raise Uf2Error(f"the drive refused blocks {done + 1} to {done + count}")
        done += count
        progress(done)


class Drive:
    def __init__(self, ids, product, node, interface):
        self.ids, self.product, self.node, self.interface = ids, product, node, interface


def descriptor_interfaces(descriptors):
    """Each interface's (number, class, subclass, protocol, endpoints) in
    its first alternate setting; endpoints as (address, attributes)."""
    found, current, at = [], None, 0
    while at + 2 <= len(descriptors) and descriptors[at] >= 2:
        length, kind = descriptors[at], descriptors[at + 1]
        if kind == 4 and length >= 9:
            current = None
            if descriptors[at + 3] == 0:
                current = [descriptors[at + 2], *descriptors[at + 5:at + 8], []]
                found.append(current)
        elif kind == 5 and length >= 7 and current is not None:
            current[4].append((descriptors[at + 2], descriptors[at + 3]))
        at += length
    return found


def find_drives(sys_devices=SYS_DEVICES, dev_usb=DEV_USB):
    """The devices Debian may use that have a bulk-only SCSI interface."""
    drives = []
    for name in sorted(os.listdir(sys_devices)) if os.path.isdir(sys_devices) else []:
        folder = os.path.join(sys_devices, name)

        def text(file):
            try:
                with open(os.path.join(folder, file)) as f:
                    return f.read().strip()
            except OSError:
                return ""

        try:
            with open(os.path.join(folder, "descriptors"), "rb") as f:
                descriptors = f.read()
            bus, num = int(text("busnum")), int(text("devnum"))
        except (OSError, ValueError):
            continue
        for number, cls, sub, protocol, _ in descriptor_interfaces(descriptors):
            if (cls, sub, protocol) == (8, 6, 0x50):
                drives.append(Drive(f"{text('idVendor')}:{text('idProduct')}", text("product") or None,
                                    os.path.join(dev_usb, f"{bus:03d}/{num:03d}"), number))
                break
    return drives


def pick_drive(drives, which):
    """The only drive, or the one [which] names (vendor:product, its node,
    or BBB/DDD)."""
    if which:
        def bus_device(path):
            return path.rstrip("/").split("/")[-2:]

        named = [d for d in drives if d.ids == which.lower() if ":" in which] or \
                [d for d in drives if ":" not in which and bus_device(d.node) == bus_device(which)]
        if not named:
            raise Uf2Error(f"no UF2 drive {which} (mynx usb lists the devices)")
        return named[0]
    if not drives:
        raise Uf2Error("no UF2 drive: put the board in its bootloader (on a Pico, hold BOOTSEL while "
                       "plugging it in); if mynx usb says it's not allowed, mynx usb allow it")
    if len(drives) > 1:
        raise Uf2Error(f"{len(drives)} drives: say which ({', '.join(d.ids for d in drives)})")
    return drives[0]


# usbfs, as <linux/usbdevice_fs.h> has it.
class _Bulk(ctypes.Structure):
    _fields_ = [("ep", ctypes.c_uint), ("len", ctypes.c_uint), ("timeout", ctypes.c_uint), ("data", ctypes.c_void_p)]


class _Ioctl(ctypes.Structure):
    _fields_ = [("ifno", ctypes.c_int), ("ioctl_code", ctypes.c_int), ("data", ctypes.c_void_p)]


def _io(kind, number, size):
    return kind << 30 | size << 16 | ord("U") << 8 | number


USBDEVFS_BULK = _io(3, 2, ctypes.sizeof(_Bulk))
USBDEVFS_CLAIMINTERFACE = _io(2, 15, 4)
USBDEVFS_RELEASEINTERFACE = _io(2, 16, 4)
USBDEVFS_IOCTL = _io(3, 18, ctypes.sizeof(_Ioctl))
USBDEVFS_DISCONNECT = _io(0, 22, 0)
TIMEOUT_MS = 5000


class UsbfsPipe:
    """A drive's bulk endpoints, through its usbfs node. Android's kernel
    binds usb-storage to the drive (and calls it damaged): it's let go first."""

    def __init__(self, node, interface):
        self.fd = os.open(node, os.O_RDWR)
        try:
            ends = next((i[4] for i in descriptor_interfaces(os.pread(self.fd, 65536, 0)) if i[0] == interface), [])
            bulk = [address for address, attributes in ends if attributes & 3 == 2]
            self.ep_in = next((a for a in bulk if a & 0x80), None)
            self.ep_out = next((a for a in bulk if not a & 0x80), None)
            if self.ep_in is None or self.ep_out is None:
                raise Uf2Error("the drive has no bulk endpoints")
            self.interface = struct.pack("I", interface)
            try:
                fcntl.ioctl(self.fd, USBDEVFS_CLAIMINTERFACE, self.interface)
            except OSError as e:
                if e.errno != errno.EBUSY:
                    raise
                fcntl.ioctl(self.fd, USBDEVFS_IOCTL, _Ioctl(interface, USBDEVFS_DISCONNECT, None))
                fcntl.ioctl(self.fd, USBDEVFS_CLAIMINTERFACE, self.interface)
        except BaseException:
            os.close(self.fd)
            raise

    def _bulk(self, ep, buffer, length):
        return fcntl.ioctl(self.fd, USBDEVFS_BULK, _Bulk(ep, length, TIMEOUT_MS, ctypes.addressof(buffer)))

    def bulk_out(self, data):
        buffer = ctypes.create_string_buffer(bytes(data), len(data))
        self._bulk(self.ep_out, buffer, len(data))

    def bulk_in(self, length):
        buffer = ctypes.create_string_buffer(length)
        got = self._bulk(self.ep_in, buffer, length)
        return buffer.raw[:got]

    def close(self):
        try:
            fcntl.ioctl(self.fd, USBDEVFS_RELEASEINTERFACE, self.interface)
        except OSError:
            pass  # the board restarted
        os.close(self.fd)
