"""Tests for the title art the boot splash shows (tools/lib/mynx-art).

Run: python3 -m unittest discover -s tests/mynx
"""
import fcntl
import os
import pty
import select
import struct
import subprocess
import sys
import tempfile
import termios
import time
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
ART = os.path.join(ROOT, "tools", "lib", "mynx-art")
NEON = os.path.join(ROOT, "core", "src", "main", "resources", "io", "github", "est4s",
                    "terminal", "core", "themes", "neon.colors.properties")


class ArtTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = self.tmp.name
        self.env = {"PATH": os.environ["PATH"], "HOME": self.home, "LANG": "C.UTF-8"}

    def tearDown(self):
        self.tmp.cleanup()

    def art(self, *args):
        return subprocess.run([sys.executable, ART, *args], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=30, env=self.env)

    def in_pty(self, keys_after=None, rows=30, cols=56, timeout=10):
        """Runs the art in a terminal, pressing a key after keys_after
        seconds (or never); returns (exit code, seconds taken, output)."""
        start = time.monotonic()
        pid, fd = pty.fork()
        if pid == 0:
            os.execvpe(sys.executable, [sys.executable, ART], self.env)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        output, pressed = b"", keys_after is None
        while True:
            done, status = os.waitpid(pid, os.WNOHANG)
            if done:
                break
            if time.monotonic() - start > timeout:
                os.kill(pid, 9)
                os.waitpid(pid, 0)
                self.fail("the art didn't finish")
            if not pressed and time.monotonic() - start > keys_after:
                os.write(fd, b"x")
                pressed = True
            if select.select([fd], [], [], 0.02)[0]:
                try:
                    output += os.read(fd, 65536)
                except OSError:
                    pass
        os.close(fd)
        return (os.waitstatus_to_exitcode(status), time.monotonic() - start,
                output.decode(errors="replace"))

    def test_the_plain_map_spells_mynx_in_pixels(self):
        lines = self.art("--plain").stdout.splitlines()
        self.assertTrue(lines[0].startswith("   #####"))
        self.assertTrue(all(set(l) <= set("#o ") for l in lines))

    def test_off_a_terminal_it_prints_the_finished_art_once(self):
        result = self.art()
        self.assertEqual(result.returncode, 0)
        lines = result.stdout.splitlines()
        self.assertEqual(len(lines), 6)          # 12 pixel rows, 2 per cell
        self.assertIn("█", result.stdout)
        self.assertNotIn("\x1b[5A", result.stdout)

    def test_it_takes_its_colours_from_the_theme(self):
        os.makedirs(os.path.join(self.home, ".config", "mynx"))
        with open(os.path.join(self.home, ".config", "mynx", "colors.properties"), "w") as f:
            f.write("color6=#ff0000\ncolor14=#ff0000\n")
        self.assertIn("38;2;255;0;0", self.art().stdout)

    def test_without_a_theme_it_uses_neon(self):
        self.assertIn("38;2;0;243;255", self.art().stdout)   # Neon's cyan, made vivid

    def test_its_built_in_colours_are_neons(self):
        built_in = self.art().stdout
        os.makedirs(os.path.join(self.home, ".config", "mynx"))
        with open(NEON) as f, open(os.path.join(self.home, ".config", "mynx",
                                                "colors.properties"), "w") as out:
            out.write(f.read())
        self.assertEqual(self.art().stdout, built_in)

    def test_on_a_terminal_it_animates_then_exits_0(self):
        code, seconds, output = self.in_pty()
        self.assertEqual(code, 0, output)
        self.assertIn("\x1b[5A", output)          # redrawn in place

    def test_a_key_skips_the_animation_with_exit_10(self):
        code, seconds, output = self.in_pty(keys_after=0.3)
        self.assertEqual(code, 10, output)
        self.assertLess(seconds, 1.5)
        self.assertIn("\x1b[?25h", output)        # the cursor comes back


if __name__ == "__main__":
    unittest.main()


class GeneratedArtTest(unittest.TestCase):
    """The pictures made from the art's pixels are up to date."""

    def generated(self, *args):
        return subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "mynx-svg.py"),
                               *args, ART], capture_output=True, text=True, check=True).stdout

    def test_the_readme_title_matches_the_art(self):
        with open(os.path.join(ROOT, "docs", "images", "mynx.svg")) as f:
            self.assertEqual(f.read(), self.generated())

    def test_the_setup_screens_title_matches_the_art(self):
        drawable = os.path.join(ROOT, "app", "src", "main", "res", "drawable", "mynx_title.xml")
        with open(drawable) as f:
            self.assertEqual(f.read(), self.generated("--vector"))
