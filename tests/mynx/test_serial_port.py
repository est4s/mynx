"""Tests for tools/lib/serial-port, the pseudo-terminal of a USB serial port.

Run: python3 -m unittest discover -s tests/mynx
"""
import importlib.machinery
import importlib.util
import os
import socket
import subprocess
import sys
import tempfile
import termios
import threading
import time
import unittest

HELPER = os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib", "serial-port")


def load_helper():
    loader = importlib.machinery.SourceFileLoader("serial_port", HELPER)
    spec = importlib.util.spec_from_loader("serial_port", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


sp = load_helper()


def sub(*values):
    return bytes([255, 250, 44, *values, 255, 240])


class EncodingTest(unittest.TestCase):
    def test_settings_come_from_termios(self):
        attrs = [0, 0, termios.CS8 | termios.CREAD, 0, termios.B115200, termios.B115200, []]
        self.assertEqual((115200, 8, 1, 1, False), sp.line_settings(attrs))
        attrs[2] = termios.CS7 | termios.PARENB | termios.CSTOPB | termios.CRTSCTS
        self.assertEqual((115200, 7, 3, 2, True), sp.line_settings(attrs))
        attrs[2] = termios.CS8 | termios.PARENB | termios.PARODD
        self.assertEqual(2, sp.line_settings(attrs)[2])

    def test_setup_asks_for_each_setting(self):
        self.assertEqual(
            sub(1, 0, 1, 0xC2, 0) + sub(2, 8) + sub(3, 1) + sub(4, 1) + sub(5, 1),
            sp.setup((115200, 8, 1, 1, False)))
        self.assertTrue(sp.setup((9600, 8, 1, 1, True)).endswith(sub(5, 3)))

    def test_an_iac_in_a_value_is_doubled(self):
        self.assertEqual(sub(1, 0, 0, 0, 255, 255), sp.subnegotiation(1, bytes([0, 0, 0, 255])))

    def test_escape_doubles_iac(self):
        self.assertEqual(b"a\xff\xffb", sp.escape(b"a\xffb"))

    def test_unescape_drops_telnet_and_keeps_data_across_reads(self):
        u = sp.Unescaper()
        self.assertEqual(b"a", u.feed(bytes([97, 255, 251, 3, 255, 250, 44, 101, 0])))
        self.assertEqual(b"\xffb", u.feed(bytes([1, 255, 240, 255, 255, 98])))
        self.assertEqual(b"c", u.feed(bytes([255, 241, 99])))


class FakePort:
    """The app's RFC 2217 server, as far as the helper sees it: records
    what each connection sent, and can send bytes to the current one."""

    def __init__(self):
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.connections = []  # one bytearray per connection
        self.current = None
        self.closed = threading.Event()
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            received = bytearray()
            self.connections.append(received)
            self.current = conn
            self.closed.clear()
            threading.Thread(target=self.read, args=(conn, received), daemon=True).start()

    def read(self, conn, received):
        while True:
            try:
                data = conn.recv(4096)
            except OSError:
                data = b""
            if not data:
                break
            received.extend(data)
        if self.current is conn:
            self.current = None
        self.closed.set()

    def close(self):
        self.server.close()


def wait_for(check, what, seconds=5):
    until = time.monotonic() + seconds
    while not check():
        if time.monotonic() > until:
            raise AssertionError(f"timed out waiting for {what}")
        time.sleep(0.02)


class BridgeTest(unittest.TestCase):
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

    def wait_for_setup(self):
        wait_for(lambda: self.port.connections and self.port.connections[0].endswith(sub(5, 11)), "the setup")

    def test_the_link_points_at_a_terminal(self):
        self.assertTrue(os.readlink(self.link).startswith("/dev/pts/"))

    def test_no_connection_while_nothing_has_it_open(self):
        time.sleep(0.4)
        self.assertEqual([], self.port.connections)

    def test_opening_connects_with_its_settings_and_raises_dtr_and_rts(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            wait_for(lambda: len(self.port.connections) == 1, "a connection")
            expected = (bytes([255, 251, 44]) + sp.setup((9600, 8, 1, 1, False))
                        + sub(5, 8) + sub(5, 11))
            wait_for(lambda: bytes(self.port.connections[0]) == expected, "the setup")
        finally:
            os.close(fd)

    def test_bytes_go_both_ways(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            self.wait_for_setup()
            start = len(self.port.connections[0])
            os.write(fd, b"hi\xff")
            wait_for(lambda: bytes(self.port.connections[0][start:]) == b"hi\xff\xff", "the bytes")
            self.port.current.sendall(b"ok\xff\xff" + sub(101, 0, 0, 37, 128) + b"!")
            got = b""
            until = time.monotonic() + 5
            while got != b"ok\xff!" and time.monotonic() < until:
                time.sleep(0.05)
                try:
                    got += os.read(fd, 100)
                except BlockingIOError:
                    pass
            self.assertEqual(b"ok\xff!", got)
        finally:
            os.close(fd)

    def test_a_new_baud_rate_goes_before_the_bytes_after_it(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            self.wait_for_setup()
            start = len(self.port.connections[0])
            attrs = termios.tcgetattr(fd)
            attrs[4] = attrs[5] = termios.B115200
            termios.tcsetattr(fd, termios.TCSANOW, attrs)
            os.write(fd, b"x")
            wait_for(lambda: bytes(self.port.connections[0][start:]).endswith(b"x"), "the byte")
            self.assertEqual(sp.setup((115200, 8, 1, 1, False)) + b"x", bytes(self.port.connections[0][start:]))
        finally:
            os.close(fd)

    def test_closing_lets_go_and_opening_again_connects_again(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        wait_for(lambda: self.port.current is not None, "a connection")
        os.close(fd)
        wait_for(lambda: self.port.current is None, "the end of the connection")
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            wait_for(lambda: len(self.port.connections) == 2, "a second connection")
        finally:
            os.close(fd)

    def test_bytes_written_just_before_closing_still_go(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        os.write(fd, b"bye")
        os.close(fd)
        wait_for(lambda: self.port.connections and bytes(self.port.connections[0]).endswith(b"bye"), "the bytes")

    def lose_the_port(self, change_link):
        """The app drops the connection and changes the link while a program
        still has the old terminal open, as when a board is unplugged."""
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            wait_for(lambda: self.port.current is not None, "a connection")
            connection = self.port.current  # the helper may let go first, which clears current
            change_link()
            connection.close()
            self.thread.join(5)
            self.assertFalse(self.thread.is_alive())
            time.sleep(sp.RETRY + 0.3)
            self.assertEqual(1, len(self.port.connections))
        finally:
            os.close(fd)

    def test_a_removed_link_stops_it_without_connecting_again(self):
        self.lose_the_port(lambda: os.unlink(self.link))
        self.assertFalse(os.path.lexists(self.link))

    def test_a_replaced_link_stops_it_and_stays(self):
        def replace():
            os.unlink(self.link)
            os.symlink("/dev/pts/999", self.link)
        self.lose_the_port(replace)
        self.assertEqual("/dev/pts/999", os.readlink(self.link))
        os.unlink(self.link)

    def test_stopping_removes_the_link(self):
        self.bridge.stopped = True
        self.thread.join(5)
        self.assertFalse(os.path.lexists(self.link))


DTR, RTS = 0x002, 0x004
INPUTS = 0x020 | 0x040 | 0x100  # CTS, CD, DSR: always on


def ask(folder, line):
    """One request to the helper's control socket, as libmynx-serial.so sends it."""
    with socket.socket(socket.AF_UNIX) as s:
        s.settimeout(5)
        s.connect(os.path.join(folder, ".ttyUSB0.ctl"))
        s.sendall(line.encode() + b"\n")
        reply = b""
        while not reply.endswith(b"\n"):
            data = s.recv(100)
            if not data:
                break
            reply += data
        return reply.decode()


class LinesTest(unittest.TestCase):
    def test_raising_goes_dtr_first_and_dropping_rts_first(self):
        on, off = (True, True), (False, False)
        self.assertEqual([8, 11], sp.control_changes(off, on))
        self.assertEqual([12, 9], sp.control_changes(on, off))

    def test_switching_from_one_line_to_the_other_passes_through_both_off(self):
        self.assertEqual([12, 8], sp.control_changes((False, True), (True, False)))
        self.assertEqual([9, 11], sp.control_changes((True, False), (False, True)))

    def test_only_changes_are_sent_and_with_no_old_state_both_are(self):
        self.assertEqual([], sp.control_changes((True, False), (True, False)))
        self.assertEqual([9, 11], sp.control_changes(None, (False, True)))


class ControlTest(BridgeTest):
    def open_and_set_up(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        self.addCleanup(os.close, fd)
        self.wait_for_setup()
        return len(self.port.connections[0])

    def sent_since(self, start, expected):
        wait_for(lambda: bytes(self.port.connections[0][start:]) == expected, f"{expected!r}")

    def test_get_reports_dtr_and_rts_up_and_the_inputs_on(self):
        self.open_and_set_up()
        self.assertEqual(f"ok {DTR | RTS | INPUTS}\n", ask(self.folder, "get 0"))

    def test_bic_drops_a_line_on_the_port(self):
        start = self.open_and_set_up()
        self.assertEqual(f"ok {RTS | INPUTS}\n", ask(self.folder, f"bic {DTR}"))
        self.sent_since(start, sub(5, 9))

    def test_set_drops_rts_first_and_raises_dtr_first(self):
        start = self.open_and_set_up()
        self.assertEqual(f"ok {INPUTS}\n", ask(self.folder, "set 0"))
        self.assertEqual(f"ok {DTR | RTS | INPUTS}\n", ask(self.folder, f"set {DTR | RTS}"))
        self.sent_since(start, sub(5, 12) + sub(5, 9) + sub(5, 8) + sub(5, 11))

    def test_bis_raises_a_line(self):
        start = self.open_and_set_up()
        ask(self.folder, f"bic {RTS}")
        self.assertEqual(f"ok {DTR | RTS | INPUTS}\n", ask(self.folder, f"bis {RTS}"))
        self.sent_since(start, sub(5, 12) + sub(5, 11))

    def test_a_bad_request_is_refused(self):
        self.open_and_set_up()
        self.assertEqual("error\n", ask(self.folder, "fly 3"))

    def test_closing_puts_the_lines_back_up_for_the_next_program(self):
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        self.wait_for_setup()
        ask(self.folder, "set 0")
        os.close(fd)
        wait_for(lambda: self.port.current is None, "the end of the connection")
        fd = os.open(self.link, os.O_RDWR | os.O_NOCTTY)
        try:
            wait_for(lambda: len(self.port.connections) == 2 and self.port.connections[1].endswith(sub(5, 8) + sub(5, 11)), "both up")
        finally:
            os.close(fd)

    def test_stopping_removes_the_socket(self):
        socket_path = os.path.join(self.folder, ".ttyUSB0.ctl")
        self.assertTrue(os.path.exists(socket_path))
        self.bridge.stopped = True
        self.thread.join(5)
        self.assertFalse(os.path.lexists(socket_path))


class EarlyControlTest(unittest.TestCase):
    def test_a_request_before_the_helper_saw_the_open_connects_with_those_lines(self):
        folder = tempfile.mkdtemp()
        port = FakePort()
        bridge = sp.Bridge("ttyUSB0", port.port, folder)
        bridge.make_terminal()
        fd = os.open(os.path.join(folder, "ttyUSB0"), os.O_RDWR | os.O_NOCTTY)
        try:
            self.assertEqual(f"ok {RTS | INPUTS}", bridge.control("set", RTS))
            expected = bytes([255, 251, 44]) + sp.setup((9600, 8, 1, 1, False)) + sub(5, 9) + sub(5, 11)
            wait_for(lambda: port.connections and bytes(port.connections[0]) == expected, "the setup")
        finally:
            os.close(fd)
            bridge.disconnect("done")
            bridge.remove_link()
            port.close()
            os.rmdir(folder)


class AppAwayTest(unittest.TestCase):
    def test_with_no_app_the_bytes_are_dropped_and_it_keeps_trying(self):
        folder = tempfile.mkdtemp()
        unused = socket.socket()
        unused.bind(("127.0.0.1", 0))
        port = unused.getsockname()[1]
        unused.close()
        bridge = sp.Bridge("ttyACM0", port, folder)
        thread = threading.Thread(target=bridge.run, daemon=True)
        thread.start()
        link = os.path.join(folder, "ttyACM0")
        wait_for(lambda: os.path.islink(link), "the link")
        fd = os.open(link, os.O_RDWR | os.O_NOCTTY)
        os.write(fd, b"lost")
        time.sleep(0.5)
        self.assertGreater(bridge.retry_at, 0)
        os.close(fd)
        bridge.stopped = True
        thread.join(5)
        os.rmdir(folder)


class CommandTest(unittest.TestCase):
    def test_usage(self):
        result = subprocess.run([sys.executable, HELPER, "ttyUSB0"], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("usage: serial-port NAME TCPPORT", result.stderr)

    def test_term_stops_it_and_removes_the_link(self):
        folder = tempfile.mkdtemp()
        process = subprocess.Popen([sys.executable, HELPER, "ttyUSB3", "1", folder], stderr=subprocess.PIPE, text=True)
        link = os.path.join(folder, "ttyUSB3")
        wait_for(lambda: os.path.islink(link), "the link")
        process.terminate()
        self.assertEqual(0, process.wait(5))
        self.assertFalse(os.path.lexists(link))
        self.assertIn(f"{link} -> /dev/pts/", process.stderr.read())
        os.rmdir(folder)


if __name__ == "__main__":
    unittest.main()
