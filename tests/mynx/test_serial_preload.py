"""Tests for tools/src/libmynx-serial.c, the preload library for /dev/serial ports.

Android refuses the calls it wraps; a desktop doesn't, so the test build
(-DMYNX_SERIAL_TEST) refuses them itself on a port's pty, and finds the
ports in MYNX_SERIAL_DIR. Skipped where there's no C compiler.

Run: python3 -m unittest discover -s tests/mynx
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

from test_serial_port import FakePort, sp, sub, wait_for

SOURCE = os.path.join(os.path.dirname(__file__), "..", "..", "tools", "src", "libmynx-serial.c")
CC = shutil.which(os.environ.get("CC", "cc"))
DTR, RTS, INPUTS = 0x002, 0x004, 0x020 | 0x040 | 0x100

# What pyserial and esptool do, in a program run under the library.
PROGRAM = r"""
import fcntl, json, os, struct, sys, termios
def lines():
    return struct.unpack("i", fcntl.ioctl(fd, termios.TIOCMGET, struct.pack("i", 0)))[0]
def refused(call):
    try:
        call()
    except OSError as e:
        return e.errno
    return 0
fd = os.open(sys.argv[1], os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
out = {"start": lines()}
fcntl.ioctl(fd, termios.TIOCMBIC, struct.pack("i", termios.TIOCM_DTR))
out["dtr off"] = lines()
fcntl.ioctl(fd, termios.TIOCMSET, struct.pack("i", termios.TIOCM_DTR))
out["rts off"] = lines()
fcntl.ioctl(fd, termios.TIOCEXCL)
fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
os.write(fd, b"x" * 2000)
termios.tcdrain(fd)
termios.tcflow(fd, termios.TCOON)
out["break"] = refused(lambda: fcntl.ioctl(fd, termios.TCSBRK, 0))
print(json.dumps(out))
"""

# Files that aren't ports: every call as without the library.
OTHERS = r"""
import errno, fcntl, json, os, struct, termios
out = {}
with open(os.devnull, "w") as f:
    fcntl.flock(f, fcntl.LOCK_EX)
    try:
        fcntl.ioctl(f, termios.TIOCMGET, struct.pack("i", 0))
    except OSError as e:
        out["tiocmget"] = errno.errorcode[e.errno]
    try:
        termios.tcdrain(f.fileno())
    except termios.error as e:
        out["tcdrain"] = errno.errorcode[e.args[0]]
print(json.dumps(out))
"""


def build(out, *flags):
    subprocess.run([CC, "-shared", "-fPIC", "-O2", "-Wall", "-Werror", *flags, "-o", out, SOURCE, "-ldl"], check=True)


@unittest.skipUnless(CC, "no C compiler")
class PreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.mkdtemp()
        cls.library = os.path.join(cls.build_dir, "test.so")
        build(cls.library, "-DMYNX_SERIAL_TEST")

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
        os.rmdir(self.folder)

    def run_preloaded(self, program, *args, library=None):
        env = dict(os.environ, LD_PRELOAD=library or self.library, MYNX_SERIAL_DIR=self.folder)
        result = subprocess.run([sys.executable, "-c", program, *args], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_refused_calls_on_a_port_go_to_its_helper(self):
        out = self.run_preloaded(PROGRAM, self.link)
        self.assertEqual(DTR | RTS | INPUTS, out["start"])
        self.assertEqual(RTS | INPUTS, out["dtr off"])
        self.assertEqual(DTR | INPUTS, out["rts off"])
        self.assertEqual(13, out["break"])  # EACCES: a break isn't sent

    def test_the_lines_reach_the_port_in_order(self):
        self.run_preloaded(PROGRAM, self.link)
        # Connected with both up, then DTR off, then RTS off before DTR on.
        wait_for(lambda: self.port.connections and sub(5, 11) + sub(5, 9) + sub(5, 12) + sub(5, 8) in self.port.connections[0], "the lines")

    def test_what_was_written_reaches_the_port_after_tcdrain(self):
        self.run_preloaded(PROGRAM, self.link)
        wait_for(lambda: self.port.connections and bytes(self.port.connections[0]).count(b"x") == 2000, "the bytes")

    def test_other_files_see_no_difference(self):
        self.assertEqual({"tiocmget": "ENOTTY", "tcdrain": "ENOTTY"}, self.run_preloaded(OTHERS))

    def test_the_real_build_loads_without_a_word(self):
        library = os.path.join(self.build_dir, "libmynx-serial.so")
        build(library)
        result = subprocess.run([sys.executable, "-c", "print('hi')"], env=dict(os.environ, LD_PRELOAD=library), capture_output=True, text=True)
        self.assertEqual(("hi\n", ""), (result.stdout, result.stderr))


if __name__ == "__main__":
    unittest.main()
