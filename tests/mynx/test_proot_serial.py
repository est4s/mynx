"""Tests for proot/mynx_serial/ports.c, what proot's mynx_serial extension
does for programs the preload library can't reach (static ones, like Go's).

The extension itself only moves registers and memory; it runs on the phone.
Its decisions are here, built as a shared library and called with ctypes,
against the real serial-port helper. Skipped where there's no C compiler.

Run: python3 -m unittest discover -s tests/mynx
"""
import ctypes
import errno
import os
import shutil
import subprocess
import tempfile
import termios
import threading
import unittest

from test_serial_port import FakePort, sp, wait_for

SOURCE = os.path.join(os.path.dirname(__file__), "..", "..", "proot", "mynx_serial", "ports.c")
CC = shutil.which(os.environ.get("CC", "cc"))
DTR, RTS, INPUTS = 0x002, 0x004, 0x020 | 0x040 | 0x100


@unittest.skipUnless(CC, "no C compiler")
class PortsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.mkdtemp()
        library = os.path.join(cls.build_dir, "ports.so")
        subprocess.run([CC, "-shared", "-fPIC", "-O2", "-Wall", "-Werror", "-o", library, SOURCE], check=True)
        cls.lib = ctypes.CDLL(library)
        cls.lib.mynx_serial_refused.argtypes = [ctypes.c_ulong]
        cls.lib.mynx_serial_port.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
        cls.lib.mynx_serial_ioctl.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_int)]
        cls.lib.mynx_uevent_path.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_uint, ctypes.c_char_p, ctypes.c_size_t]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.build_dir)

    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.port = FakePort()
        self.bridge = sp.Bridge("ttyUSB0", self.port.port, self.folder)
        self.thread = threading.Thread(target=self.bridge.run, daemon=True)
        self.thread.start()
        self.link = os.path.join(self.folder, "ttyUSB0")
        wait_for(lambda: os.path.islink(self.link), "the link")

    def tearDown(self):
        self.bridge.stopped = True
        self.thread.join(5)
        self.port.close()
        shutil.rmtree(self.folder)

    def port_name(self, tty):
        name = ctypes.create_string_buffer(64)
        if self.lib.mynx_serial_port(self.folder.encode(), tty.encode(), name, len(name)):
            return None
        return name.value.decode()

    def ioctl(self, request, value=0):
        arg = ctypes.c_int(value)
        result = self.lib.mynx_serial_ioctl(self.folder.encode(), b"ttyUSB0", request, ctypes.byref(arg))
        return result, arg.value

    def open_port(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        self.addCleanup(os.close, fd)
        wait_for(lambda: self.port.connections, "the connection")

    def test_only_the_calls_android_refuses_on_a_pty(self):
        for request in (termios.TIOCMGET, termios.TIOCMSET, termios.TIOCMBIS, termios.TIOCMBIC,
                        termios.TIOCEXCL, termios.TIOCNXCL, termios.TCSBRK, termios.TCXONC):
            self.assertTrue(self.lib.mynx_serial_refused(request), hex(request))
        for request in (termios.TCGETS, termios.TCSETS, termios.TIOCOUTQ, termios.TCFLSH):
            self.assertFalse(self.lib.mynx_serial_refused(request), hex(request))

    def test_a_ports_pty_is_found_by_its_link(self):
        self.assertEqual("ttyUSB0", self.port_name(os.readlink(self.link)))

    def test_other_files_arent_ports(self):
        self.assertIsNone(self.port_name("/dev/pts/9999"))
        self.assertIsNone(self.port_name("/dev/null"))
        self.assertIsNone(self.port_name("socket:[1234]"))

    def test_the_modem_lines_go_to_the_helper(self):
        self.open_port()
        self.assertEqual((0, DTR | RTS | INPUTS), self.ioctl(termios.TIOCMGET))
        self.assertEqual(0, self.ioctl(termios.TIOCMBIC, DTR)[0])
        self.assertEqual((0, RTS | INPUTS), self.ioctl(termios.TIOCMGET))
        self.assertEqual(0, self.ioctl(termios.TIOCMSET, DTR)[0])
        self.assertEqual((0, DTR | INPUTS), self.ioctl(termios.TIOCMGET))
        self.assertEqual(0, self.ioctl(termios.TIOCMBIS, RTS)[0])
        self.assertEqual((0, DTR | RTS | INPUTS), self.ioctl(termios.TIOCMGET))

    def test_locks_flow_and_drain_succeed_but_a_break_stays_refused(self):
        self.assertEqual(0, self.ioctl(termios.TIOCEXCL)[0])
        self.assertEqual(0, self.ioctl(termios.TIOCNXCL)[0])
        self.assertEqual(0, self.ioctl(termios.TCXONC, termios.TCOON)[0])
        self.assertEqual(0, self.ioctl(termios.TCSBRK, 1)[0])
        self.assertEqual(-errno.EACCES, self.ioctl(termios.TCSBRK, 0)[0])

    def test_with_no_helper_the_lines_stay_refused(self):
        self.bridge.stopped = True
        self.thread.join(5)
        self.assertEqual(-errno.EACCES, self.ioctl(termios.TIOCMGET)[0])

    def test_a_uevent_socket_gets_a_path_of_its_own(self):
        path = ctypes.create_string_buffer(108)
        self.assertEqual(0, self.lib.mynx_uevent_path(b"/dev/serial", 1234, 2, path, len(path)))
        self.assertEqual(b"/dev/serial/.uevent/1234-2", path.value)
        self.assertEqual(-1, self.lib.mynx_uevent_path(b"/" + b"x" * 100, 1234, 2, path, len(path)))


if __name__ == "__main__":
    unittest.main()
