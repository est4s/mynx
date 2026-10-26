"""Tests for tools/apps/serial, the serial monitor (`mynx usb monitor`).

Run: python3 -m unittest discover -s tests/mynx
"""
import importlib.machinery
import importlib.util
import os
import pty
import select
import subprocess
import sys
import termios
import time
import tty
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APP = os.path.join(ROOT, "tools", "apps", "serial")


def load_app():
    loader = importlib.machinery.SourceFileLoader("serial_monitor", APP)
    spec = importlib.util.spec_from_loader("serial_monitor", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


sm = load_app()

PORTS = [
    {"name": "ttyACM0", "path": "/dev/serial/ttyACM0", "ids": "303a:1001"},
    {"name": "ttyUSB0", "path": "/dev/serial/ttyUSB0", "ids": "1a86:7523"},
]


class PortChoiceTest(unittest.TestCase):
    def test_a_name_a_path_or_the_only_port(self):
        self.assertEqual(PORTS[1], sm.choose_port("ttyUSB0", PORTS))
        self.assertEqual(PORTS[0], sm.choose_port("/dev/serial/ttyACM0", PORTS))
        self.assertEqual(PORTS[0], sm.choose_port(None, PORTS[:1]))

    def test_any_other_path_is_taken_as_it_is(self):
        self.assertEqual({"name": "pts-3", "path": "/dev/pts/3", "ids": None},
                         sm.choose_port("/dev/pts/3", PORTS))

    def test_says_what_is_wrong(self):
        with self.assertRaisesRegex(sm.Problem, "no port ttyUSB1.*ttyACM0, ttyUSB0"):
            sm.choose_port("ttyUSB1", PORTS)
        with self.assertRaisesRegex(sm.Problem, r"No serial port\. Connect a board \(mynx usb\) or bind a Bluetooth one \(mynx bt\)"):
            sm.choose_port(None, [])
        with self.assertRaisesRegex(sm.Problem, "2 ports: say which"):
            sm.choose_port(None, PORTS)

    def test_with_several_ports_it_asks_which(self):
        asked = []
        answer = lambda prompt: asked.append(prompt) or "2"
        self.assertEqual(PORTS[1], sm.pick(PORTS, answer))
        self.assertIn("1 ttyACM0", asked[0])
        with self.assertRaisesRegex(sm.Problem, "no port 5"):
            sm.pick(PORTS, lambda prompt: "5")

    def test_bluetooth_ports_come_with_the_usb_ones(self):
        # mynx's serial_ports() merges bt-list's served ports with usb-list's.
        bt = {"name": "rfcomm0", "path": "/dev/serial/rfcomm0", "ids": None, "product": "mynx-spp"}
        old = sm.serial_ports
        sm.serial_ports = lambda: PORTS + [bt]
        try:
            self.assertEqual(PORTS + [bt], sm.ask_ports())
        finally:
            sm.serial_ports = old

    def test_the_reset_follows_the_device(self):
        self.assertEqual("usb-jtag", sm.boot_kind(PORTS[0]))
        self.assertEqual("classic", sm.boot_kind(PORTS[1]))
        self.assertEqual("touch", sm.boot_kind({"name": "ttyACM0", "ids": "2341:8037"}))
        self.assertEqual("classic", sm.boot_kind({"name": "pts-3", "ids": None}))


class KeysTest(unittest.TestCase):
    def test_typing_goes_to_the_board_with_enter_as_the_line_ending(self):
        keys = sm.Keys()
        self.assertEqual([("send", b"print(1)\r")], keys.feed(b"print(1)\r"))
        keys.eol = "lf"
        self.assertEqual([("send", b"a\n")], keys.feed(b"a\r"))
        keys.eol = "crlf"
        self.assertEqual([("send", b"\r\n")], keys.feed(b"\r"))

    def test_ctrl_t_starts_a_command_even_across_reads(self):
        keys = sm.Keys()
        self.assertEqual([("send", b"ab"), ("command", "r"), ("send", b"c")], keys.feed(b"ab\x14rc"))
        self.assertEqual([], keys.feed(b"\x14"))
        self.assertEqual([("command", "q")], keys.feed(b"q"))

    def test_ctrl_t_twice_sends_one_and_ctrl_bracket_quits(self):
        keys = sm.Keys()
        self.assertEqual([("send", b"\x14")], keys.feed(b"\x14\x14"))
        self.assertEqual([("send", b"x"), ("command", "q")], keys.feed(b"x\x1d"))


class DisplayTest(unittest.TestCase):
    def test_text_passes_through_and_utf8_split_across_reads_is_kept(self):
        d = sm.Display()
        self.assertEqual("hé", d.feed("hé".encode()[:2], 0) + d.feed("hé".encode()[2:], 0))

    def test_timestamps_start_each_line(self):
        d = sm.Display()
        d.stamps = True
        at = time.mktime((2026, 10, 18, 12, 34, 56, 0, 0, -1)) + 0.789
        out = d.feed(b"one\r\ntw", at) + d.feed(b"o\n", at)
        self.assertEqual(sm.stamp(at) + "one\r\n" + sm.stamp(at) + "two\n", out)
        self.assertIn("12:34:56.789", sm.stamp(at))

    def test_hex_shows_bytes_and_breaks_lines_after_a_newline_or_16(self):
        d = sm.Display()
        d.hex = True
        self.assertEqual("41 0d 0a\n", d.feed(b"A\r\n", 0))
        out = d.feed(bytes(range(0x20, 0x34)), 0)
        self.assertEqual(" ".join(f"{b:02x}" for b in range(0x20, 0x30)) + "\n30 31 32 33 ", out)

    def test_a_note_gets_a_line_of_its_own(self):
        d = sm.Display()
        d.feed(b"half", 0)
        note = d.note("reset")
        self.assertTrue(note.startswith("\r\n"))
        self.assertIn("reset", note)
        self.assertTrue(note.endswith("\r\n"))
        self.assertFalse(d.note("x").startswith("\r\n"))
        d.feed(b"line\n", 0)
        self.assertFalse(d.note("x").startswith("\r\n"))


class ResetTest(unittest.TestCase):
    def sequence(self, kind):
        lines = sm.Lines.__new__(sm.Lines)
        calls = []
        lines.set = lambda dtr=None, rts=None: calls.append(("set", dtr, rts))
        sleeps = []
        port = Recorder(calls)
        sm.reset(kind, lines, port, sleep=lambda s: calls.append(("sleep", s)))
        return calls

    def test_a_reset_drops_rts_first_then_pulses_rts_then_dtr(self):
        # RTS first from both on (see the helper's control_changes); the
        # RTS pulse resets an ESP32 through EN, the DTR pulse an Arduino.
        calls = [c for c in self.sequence("reset") if c[0] == "set"]
        self.assertEqual([("set", None, False), ("set", False, None), ("set", None, True),
                          ("set", None, False), ("set", True, None), ("set", False, None)], calls)

    def test_into_the_bootloader_the_esptool_way(self):
        calls = [c for c in self.sequence("classic") if c[0] == "set"]
        self.assertEqual([("set", False, None), ("set", None, True), ("set", True, None),
                          ("set", None, False), ("set", False, None)], calls)
        jtag = [c for c in self.sequence("usb-jtag") if c[0] == "set"]
        self.assertEqual(("set", None, True), jtag[4])  # through (1,1), not (0,0)
        self.assertEqual(jtag[-2:], [("set", False, None), ("set", None, False)])

    def test_an_arduino_bootloader_is_the_1200_baud_touch(self):
        calls = self.sequence("touch")
        self.assertIn(("baud", 1200), calls)
        self.assertEqual(("set", False, None), [c for c in calls if c[0] == "set"][-1])


class Recorder:
    def __init__(self, calls):
        self.calls = calls

    def set_baud(self, baud):
        self.calls.append(("baud", baud))


class BaudTest(unittest.TestCase):
    def test_steps_through_the_usual_rates(self):
        self.assertEqual(230400, sm.step_baud(115200, 1))
        self.assertEqual(57600, sm.step_baud(115200, -1))
        self.assertEqual(921600, sm.step_baud(921600, 1))
        self.assertEqual(300, sm.step_baud(300, -1))
        self.assertEqual(9600, sm.step_baud(10000, -1))


class MonitorTest(unittest.TestCase):
    """The app in a pseudo-terminal, against a pty standing in for the port."""

    def setUp(self):
        self.port_master, slave = os.openpty()
        tty.setraw(self.port_master)
        self.port_path = os.ttyname(slave)
        self.slave = slave  # keep it open, as the helper's pty stays open

    def tearDown(self):
        os.close(self.port_master)
        os.close(self.slave)

    def start(self, *args):
        pid, fd = pty.fork()
        if pid == 0:
            os.environ["MYNX_REQUESTS"] = "/nonexistent"
            os.execv(sys.executable, [sys.executable, APP, *args])
        self.pid, self.fd = pid, fd
        self.addCleanup(self.stop)
        self.opening = self.expect(b"Ctrl+T h")
        return fd

    def stop(self):
        try:
            os.kill(self.pid, 9)
            os.waitpid(self.pid, 0)
        except (ProcessLookupError, ChildProcessError):
            pass
        os.close(self.fd)

    def expect(self, wanted, fd=None, timeout=10):
        fd = self.fd if fd is None else fd
        seen = b""
        deadline = time.monotonic() + timeout
        while wanted not in seen:
            left = deadline - time.monotonic()
            if left <= 0:
                self.fail(f"never saw {wanted!r}; saw {seen!r}")
            if select.select([fd], [], [], left)[0]:
                try:
                    seen += os.read(fd, 4096)
                except OSError:
                    break
        return seen

    def test_shows_what_the_board_sends_and_sends_what_is_typed(self):
        fd = self.start(self.port_path, "--baud", "9600")
        os.write(self.port_master, b"hello from the board\r\n")
        self.expect(b"hello from the board")
        os.write(fd, b">>> 1+1\r")
        self.expect(b">>> 1+1\r", fd=self.port_master)

    def test_sets_the_baud_and_changes_it(self):
        fd = self.start(self.port_path, "--baud", "9600")
        self.assertEqual(termios.B9600, termios.tcgetattr(self.port_master)[4])
        os.write(fd, b"\x14+")
        self.expect(b"19200")
        self.assertEqual(termios.B19200, termios.tcgetattr(self.port_master)[4])

    def test_quits_on_ctrl_t_q(self):
        fd = self.start(self.port_path)
        os.write(fd, b"\x14q")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            done, status = os.waitpid(self.pid, os.WNOHANG)
            if done:
                self.assertEqual(0, os.waitstatus_to_exitcode(status))
                return
            time.sleep(0.05)
        self.fail("didn't quit")

    def test_help_lists_the_keys(self):
        fd = self.start(self.port_path)
        os.write(fd, b"\x14h")
        self.expect(b"bootloader")

    def test_a_port_that_goes_away_is_waited_for(self):
        link = os.path.join(self.tmp(), "ttyACM0")
        os.symlink(self.port_path, link)
        fd = self.start(link)
        os.unlink(link)
        self.expect(b"waiting")
        os.symlink(self.port_path, link)
        self.expect(b"back")
        os.write(self.port_master, b"again\r\n")
        self.expect(b"again")

    def test_over_bluetooth_the_lines_and_resets_do_nothing_and_say_so(self):
        link = os.path.join(self.tmp(), "rfcomm0")
        os.symlink(self.port_path, link)
        fd = self.start(link, "--lower")
        self.assertNotIn(b"DTR", self.opening)
        for key in b"rbDR":
            os.write(fd, b"\x14" + bytes([key]))
            self.expect(b"nothing over Bluetooth")

    def test_over_bluetooth_le_the_lines_and_resets_do_nothing_and_say_so(self):
        link = os.path.join(self.tmp(), "nus0")
        os.symlink(self.port_path, link)
        fd = self.start(link, "--lower")
        self.assertNotIn(b"DTR", self.opening)
        for key in b"rbDR":
            os.write(fd, b"\x14" + bytes([key]))
            self.expect(b"nothing over Bluetooth")

    def tmp(self):
        import tempfile
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        return d.name


class CommandLineTest(unittest.TestCase):
    def run_app(self, *args):
        return subprocess.run([sys.executable, APP, *args], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=30,
                              env={**os.environ, "MYNX_REQUESTS": "/nonexistent"})

    def test_a_bad_baud_is_refused(self):
        run = self.run_app("--baud", "fast")
        self.assertEqual(2, run.returncode)
        self.assertIn("--baud", run.stderr)


if __name__ == "__main__":
    unittest.main()
