"""Tests for proot/mynx_usb/devices.c, what proot's mynx_usb extension
decides when a program opens a USB device node (/dev/bus/usb/BBB/DDD):
the app hands out the device's fd over a Unix socket, and the extension
has the program fetch it.

The extension itself only moves registers and memory; it runs on the phone.
Its decisions are here, built as a shared library and called with ctypes.
Skipped where there's no C compiler.

Run: python3 -m unittest discover -s tests/mynx
"""
import ctypes
import errno
import os
import shutil
import subprocess
import tempfile
import unittest

SOURCE = os.path.join(os.path.dirname(__file__), "..", "..", "proot", "mynx_usb", "devices.c")
CC = shutil.which(os.environ.get("CC", "cc"))
OWN = "/data/user/0/io.github.est4s.terminal/files/dev"
SOCKET = OWN + "/bus/usb/.ctl"


@unittest.skipUnless(CC, "no C compiler")
class DevicesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.mkdtemp()
        library = os.path.join(cls.build_dir, "devices.so")
        subprocess.run([CC, "-shared", "-fPIC", "-O2", "-Wall", "-Werror", "-o", library, SOURCE], check=True)
        cls.lib = ctypes.CDLL(library)
        cls.lib.mynx_usb_device.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
        cls.lib.mynx_usb_result.argtypes = [ctypes.c_char_p, ctypes.c_long, ctypes.c_int]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.build_dir)

    def device(self, path, size=16, socket=SOCKET):
        dev = ctypes.create_string_buffer(size)
        if self.lib.mynx_usb_device(path.encode(), socket.encode(), dev, size) != 0:
            return None
        return dev.value.decode()

    def result(self, reply, fd=-1):
        return self.lib.mynx_usb_result(reply, len(reply), fd)

    def test_a_device_node_in_debians_own_dev(self):
        # proot has translated the path: Debian's /dev is the app's folder,
        # which holds the app's socket.
        self.assertEqual(self.device(OWN + "/bus/usb/001/002"), "001/002")
        self.assertEqual(self.device(OWN + "/bus/usb/003/127"), "003/127")

    def test_a_node_elsewhere_isnt_one(self):
        # A program's own files (a test's temp folder) are left alone.
        for path in ("/tmp/x/dev/bus/usb/001/002", "/dev/bus/usb/001/002",
                     "/data/data/io.github.est4s.terminal/files/x/dev/bus/usb/001/002",
                     OWN + "/bus/usb/001/002".replace("/bus", "/x/bus")):
            with self.subTest(path=path):
                self.assertIsNone(self.device(path))

    def test_other_paths(self):
        for tail in ("", "/001", "/001/", "/001/02", "/001/0022", "/0a1/002", "/001/002/x", "/.ctl", "/001/002/../003"):
            with self.subTest(tail=tail):
                self.assertIsNone(self.device(OWN + "/bus/usb" + tail))
        self.assertIsNone(self.device(""))

    def test_a_socket_with_no_folder(self):
        self.assertIsNone(self.device("/001/002", socket=".ctl"))

    def test_too_small_a_buffer(self):
        self.assertIsNone(self.device(OWN + "/bus/usb/001/002", size=7))

    def test_ok_with_an_fd_gives_the_fd(self):
        self.assertEqual(self.result(b"ok\n", 7), 7)

    def test_ok_without_an_fd_is_an_io_error(self):
        self.assertEqual(self.result(b"ok\n"), -errno.EIO)

    def test_an_error_gives_its_errno(self):
        self.assertEqual(self.result(b"err 13\n"), -errno.EACCES)
        self.assertEqual(self.result(b"err 19\n"), -errno.ENODEV)

    def test_nonsense_is_an_io_error(self):
        for reply in (b"", b"err\n", b"err x\n", b"err 0\n", b"err -3\n", b"err 99999\n", b"what\n"):
            with self.subTest(reply=reply):
                self.assertEqual(self.result(reply), -errno.EIO)

    def test_an_error_with_an_fd_still_errs(self):
        # The extension closes a stray fd itself; the program gets the error.
        self.assertEqual(self.result(b"err 13\n", 5), -errno.EACCES)


if __name__ == "__main__":
    unittest.main()
