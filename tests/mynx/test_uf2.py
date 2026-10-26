"""Tests for mynx/uf2.py: flashing a UF2 file onto a bootloader's drive.

Run: python3 -m unittest tests/mynx/test_uf2.py
"""
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))

from mynx import uf2  # noqa: E402

SECTOR = 512
RP2040 = 0xE48BFF56
RP2350_ARM = 0xE48BFF59
INFO = b"UF2 Bootloader v3.0\r\nModel: Raspberry Pi RP2\r\nBoard-ID: RPI-RP2\r\n"


def uf2_file(blocks, family=RP2040):
    """[blocks] UF2 blocks of 256 bytes each for the given family."""
    out = b""
    for n in range(blocks):
        head = struct.pack("<IIIIIIII", uf2.MAGIC_START0, uf2.MAGIC_START1, 0x2000,
                           0x10000000 + n * 256, 256, n, blocks, family)
        out += head + bytes([n & 0xFF]) * 256 + bytes(476 - 256) + struct.pack("<I", uf2.MAGIC_END)
    return out


def fat16(partitioned=True, info=INFO, label=b"RPI-RP2    ", sectors=262144):
    """A disk as the RP2040's bootrom shows it: an MBR, then FAT16 with
    the volume label, INDEX.HTM and INFO_UF2.TXT in the root folder."""
    disk = {}
    start = 1 if partitioned else 0
    reserved, fats, fat_size, root_entries, per_cluster = 1, 2, 129, 512, 8
    if partitioned:
        mbr = bytearray(SECTOR)
        mbr[446 + 4] = 0x0E
        mbr[446 + 8:446 + 16] = struct.pack("<II", start, sectors - start)
        mbr[510:512] = b"\x55\xaa"
        disk[0] = bytes(mbr)
    boot = bytearray(SECTOR)
    boot[0:3] = b"\xeb\x3c\x90"
    boot[3:11] = b"MSWIN4.1"
    boot[11:24] = struct.pack("<HBHBHHBH", SECTOR, per_cluster, reserved, fats, root_entries, 0, 0xF8, fat_size)
    boot[54:62] = b"FAT16   "
    boot[510:512] = b"\x55\xaa"
    disk[start] = bytes(boot)
    root = bytearray(SECTOR)

    def entry(k, name, attr, cluster, size):
        root[k * 32:k * 32 + 11] = name
        root[k * 32 + 11] = attr
        root[k * 32 + 26:k * 32 + 32] = struct.pack("<HI", cluster, size)

    entry(0, label, 0x08, 0, 0)
    entry(1, b"INDEX   HTM", 0x01, 2, 10)
    if info is not None:
        entry(2, b"INFO_UF2TXT", 0x01, 3, len(info))
    root_lba = start + reserved + fats * fat_size
    disk[root_lba] = bytes(root)
    data = root_lba + root_entries * 32 // SECTOR
    if info is not None:
        disk[data + per_cluster] = info.ljust(SECTOR, b"\0")
    return disk, data, sectors


class FakeDrive:
    """A USB drive's bulk-only transport and the SCSI commands a UF2
    bootloader answers. [restart_after] blocks written, it leaves the bus
    before sending the last status, as a board that restarts does."""

    def __init__(self, disk, sectors, restart_after=None, not_ready=0):
        self.disk, self.sectors = dict(disk), sectors
        self.written = {}
        self.restart_after = restart_after
        self.not_ready = not_ready
        self.pending = b""  # what the next bulk_in reads
        self.expect_out = None  # (lba, count, tag) of a WRITE waiting for data
        self.gone = False
        self.commands = []

    def bulk_out(self, data):
        if self.gone:
            raise OSError(19, "No such device")
        if self.expect_out:
            lba, count, tag = self.expect_out
            self.expect_out = None
            assert len(data) == count * SECTOR, len(data)
            for k in range(count):
                self.written[lba + k] = data[k * SECTOR:(k + 1) * SECTOR]
            if self.restart_after is not None and len(self.written) >= self.restart_after:
                self.gone = True
                return
            self.status(tag, 0)
            return
        sig, tag, length, flags, lun, cdb_len = struct.unpack("<IIIBBB", data[:15])
        assert sig == 0x43425355 and len(data) == 31
        cdb = data[15:15 + cdb_len]
        op = cdb[0]
        self.commands.append(op)
        if op == 0x00:  # TEST UNIT READY
            if self.not_ready:
                self.not_ready -= 1
                return self.status(tag, 1)
            return self.status(tag, 0)
        if op == 0x03:  # REQUEST SENSE
            return self.data(tag, bytes([0x70, 0, 2]) + bytes(15))
        if op == 0x25:  # READ CAPACITY (10)
            return self.data(tag, struct.pack(">II", self.sectors - 1, SECTOR))
        if op == 0x28:  # READ (10)
            lba, count = struct.unpack(">I", cdb[2:6])[0], struct.unpack(">H", cdb[7:9])[0]
            return self.data(tag, b"".join(self.disk.get(lba + k, bytes(SECTOR)) for k in range(count)))
        if op == 0x2A:  # WRITE (10)
            lba, count = struct.unpack(">I", cdb[2:6])[0], struct.unpack(">H", cdb[7:9])[0]
            assert lba + count <= self.sectors
            self.expect_out = (lba, count, tag)
            return
        raise AssertionError(f"unexpected SCSI command {op:#x}")

    def data(self, tag, payload):
        self.pending = payload + self.csw(tag, 0)

    def status(self, tag, status):
        self.pending = self.csw(tag, status)

    @staticmethod
    def csw(tag, status):
        return struct.pack("<IIIB", 0x53425355, tag, 0, status)

    def bulk_in(self, length):
        if self.gone:
            raise OSError(19, "No such device")
        out, self.pending = self.pending[:length], self.pending[length:]
        return out


class Uf2FileTest(unittest.TestCase):
    def test_a_uf2_file_gives_its_blocks_and_families(self):
        image = uf2.read_uf2(uf2_file(3))
        self.assertEqual(3, image.blocks)
        self.assertEqual({RP2040}, image.families)
        self.assertEqual(["rp2040"], uf2.family_names(image.families))

    def test_unknown_families_show_as_numbers(self):
        self.assertEqual(["0x12345678"], uf2.family_names({0x12345678}))

    def test_a_file_that_isnt_uf2_is_refused(self):
        with self.assertRaisesRegex(uf2.Uf2Error, "not a UF2 file"):
            uf2.read_uf2(b"\x7fELF" + bytes(508))
        with self.assertRaisesRegex(uf2.Uf2Error, "not a UF2 file"):
            uf2.read_uf2(uf2_file(2)[:700])
        with self.assertRaisesRegex(uf2.Uf2Error, "empty"):
            uf2.read_uf2(b"")

    def test_a_damaged_block_is_named(self):
        data = bytearray(uf2_file(3))
        data[512 + 508] ^= 0xFF
        with self.assertRaisesRegex(uf2.Uf2Error, "block 2 of 3"):
            uf2.read_uf2(bytes(data))


class DriveTest(unittest.TestCase):
    def test_the_drive_is_read_through_its_partition(self):
        disk, data, sectors = fat16()
        info = uf2.read_drive(FakeDrive(disk, sectors))
        self.assertEqual("RPI-RP2", info.label)
        self.assertEqual("RPI-RP2", info.board)
        self.assertEqual("Raspberry Pi RP2", info.model)
        self.assertEqual(data, info.data_start)
        self.assertEqual(sectors, info.sectors)

    def test_a_drive_with_no_partition_table_too(self):
        disk, data, sectors = fat16(partitioned=False)
        info = uf2.read_drive(FakeDrive(disk, sectors))
        self.assertEqual("RPI-RP2", info.board)
        self.assertEqual(data, info.data_start)

    def test_a_drive_without_info_uf2_is_no_uf2_bootloader(self):
        disk, _, sectors = fat16(info=None, label=b"KINGSTON   ")
        with self.assertRaisesRegex(uf2.Uf2Error, "KINGSTON.*not a UF2 bootloader"):
            uf2.read_drive(FakeDrive(disk, sectors))

    def test_a_drive_with_no_fat_is_no_uf2_bootloader(self):
        with self.assertRaisesRegex(uf2.Uf2Error, "not a UF2 bootloader"):
            uf2.read_drive(FakeDrive({}, 1000))

    def test_a_drive_that_isnt_ready_at_once_is_waited_for(self):
        disk, _, sectors = fat16()
        drive = FakeDrive(disk, sectors, not_ready=2)
        self.assertEqual("RPI-RP2", uf2.read_drive(drive, wait=0).board)


class FlashTest(unittest.TestCase):
    def test_blocks_go_one_per_sector_from_the_data_area(self):
        disk, data, sectors = fat16()
        drive = FakeDrive(disk, sectors)
        file = uf2_file(70)
        seen = []
        uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(file), lambda n: seen.append(n))
        self.assertEqual(set(range(data, data + 70)), set(drive.written))
        for k in range(70):
            self.assertEqual(file[k * SECTOR:(k + 1) * SECTOR], drive.written[data + k])
        self.assertEqual(70, seen[-1])
        self.assertEqual(seen, sorted(seen))
        self.assertNotIn(0, drive.written)  # the partition table and FAT stay as they are

    def test_writes_go_in_runs_of_at_most_16_kb(self):
        disk, _, sectors = fat16()
        drive = FakeDrive(disk, sectors)
        uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(70)), lambda n: None)
        self.assertEqual(3, drive.commands.count(0x2A))  # 32 + 32 + 6

    def test_a_board_that_restarts_after_the_last_block_has_been_flashed(self):
        disk, _, sectors = fat16()
        drive = FakeDrive(disk, sectors, restart_after=40)
        uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(40)), lambda n: None)
        self.assertEqual(40, len(drive.written))

    def test_a_board_that_goes_away_midway_says_where(self):
        disk, _, sectors = fat16()
        drive = FakeDrive(disk, sectors, restart_after=32)
        with self.assertRaisesRegex(uf2.Uf2Error, "after 32 of 70 blocks"):
            uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(70)), lambda n: None)

    def test_a_file_bigger_than_the_drive_is_refused_before_writing(self):
        disk, data, _ = fat16(sectors=400)
        drive = FakeDrive(disk, 400)
        with self.assertRaisesRegex(uf2.Uf2Error, "too big"):
            uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(400 - data + 1)), lambda n: None)
        self.assertEqual({}, drive.written)

    def test_a_file_for_another_chip_is_refused_before_writing(self):
        disk, _, sectors = fat16()
        drive = FakeDrive(disk, sectors)
        with self.assertRaisesRegex(uf2.Uf2Error, "rp2350-arm-s.*RPI-RP2 takes rp2040"):
            uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(4, RP2350_ARM)), lambda n: None)
        self.assertEqual({}, drive.written)

    def test_boards_it_doesnt_know_take_any_family(self):
        disk, _, sectors = fat16(info=b"UF2 Bootloader 0.6\r\nModel: Feather\r\nBoard-ID: SAMD51-Feather\r\n")
        drive = FakeDrive(disk, sectors)
        uf2.flash(drive, uf2.read_drive(drive), uf2.read_uf2(uf2_file(4, 0x55114460)), lambda n: None)
        self.assertEqual(4, len(drive.written))


class FindingDrivesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sys = os.path.join(self.tmp.name, "sys")
        self.dev = os.path.join(self.tmp.name, "dev")

    def tearDown(self):
        self.tmp.cleanup()

    def device(self, name, bus, num, vendor, product, classes, label="RP2 Boot"):
        folder = os.path.join(self.sys, name)
        os.makedirs(folder)
        desc = struct.pack("<BBHBBBBHHHBBBB", 18, 1, 0x200, 0, 0, 0, 64, vendor, product, 0x100, 1, 2, 3, 1)
        desc += struct.pack("<BBHBBBBB", 9, 2, 9 + 9 * len(classes), len(classes), 1, 0, 0x80, 50)
        for n, (c, s, p) in enumerate(classes):
            desc += struct.pack("<BBBBBBBBB", 9, 4, n, 0, 2, c, s, p, 0)
        for file, text in [("busnum", str(bus)), ("devnum", str(num)), ("product", label),
                           ("idVendor", f"{vendor:04x}"), ("idProduct", f"{product:04x}")]:
            with open(os.path.join(folder, file), "w") as f:
                f.write(text + "\n")
        with open(os.path.join(folder, "descriptors"), "wb") as f:
            f.write(desc)

    def test_devices_with_a_bulk_only_scsi_interface_are_drives(self):
        self.device("1-2", 1, 3, 0x2E8A, 0x0003, [(8, 6, 0x50), (0xFF, 0, 0)])
        self.device("1-4", 1, 4, 0x2E8A, 0x0005, [(2, 2, 0), (0x0A, 0, 0)], label="Board in FS mode")
        found = uf2.find_drives(self.sys, self.dev)
        self.assertEqual([("2e8a:0003", "RP2 Boot", os.path.join(self.dev, "001/003"), 0)],
                         [(d.ids, d.product, d.node, d.interface) for d in found])

    def test_the_drive_interface_need_not_be_the_first(self):
        self.device("1-2", 1, 7, 0x239A, 0x0057, [(2, 2, 0), (0x0A, 0, 0), (8, 6, 0x50)], label="Feather")
        self.assertEqual([2], [d.interface for d in uf2.find_drives(self.sys, self.dev)])

    def test_pick_the_only_drive_or_the_one_named(self):
        self.device("1-2", 1, 3, 0x2E8A, 0x0003, [(8, 6, 0x50)])
        self.device("1-3", 1, 5, 0x239A, 0x0057, [(8, 6, 0x50)], label="Feather")
        drives = uf2.find_drives(self.sys, self.dev)
        with self.assertRaisesRegex(uf2.Uf2Error, "2 drives: say which"):
            uf2.pick_drive(drives, None)
        self.assertEqual("Feather", uf2.pick_drive(drives, "239a:0057").product)
        self.assertEqual("RP2 Boot", uf2.pick_drive(drives, "/dev/bus/usb/001/003").product)
        self.assertEqual("RP2 Boot", uf2.pick_drive(drives, "001/003").product)
        with self.assertRaisesRegex(uf2.Uf2Error, "no UF2 drive 1234:5678"):
            uf2.pick_drive(drives, "1234:5678")

    def test_no_drive_says_how_to_get_one(self):
        with self.assertRaisesRegex(uf2.Uf2Error, "bootloader.*mynx usb"):
            uf2.pick_drive(uf2.find_drives(self.sys, self.dev), None)


if __name__ == "__main__":
    unittest.main()
