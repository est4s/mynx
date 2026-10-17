"""Tests for mynx/links.py: hard links proot faked, broken by a move.

Run: python3 -m unittest discover -s tests/mynx
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))
from mynx.links import fix_moved_links  # noqa: E402


class FixMovedLinksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.gone = os.path.join(self.root, "tmp-unpack")  # where the installer unpacked it

    def tearDown(self):
        self.tmp.cleanup()

    def faked(self, folder, name, data, *others):
        """What proot leaves for a file with hard links, after its folder moved:
        each name links to .l2s.NAME0001 (at the old place), which links to
        .l2s.NAME0001.0002, the file itself (moved here)."""
        here = os.path.join(self.root, "pkg", folder)
        os.makedirs(here, exist_ok=True)
        old = os.path.join(self.gone, folder)
        with open(os.path.join(here, f".l2s.{name}0001.0002"), "w") as f:
            f.write(data)
        os.symlink(os.path.join(old, f".l2s.{name}0001.0002"), os.path.join(here, f".l2s.{name}0001"))
        for where, other in [(folder, name), *others]:
            os.makedirs(os.path.join(self.root, "pkg", where), exist_ok=True)
            os.symlink(os.path.join(old, f".l2s.{name}0001"), os.path.join(self.root, "pkg", where, other))

    def read(self, *path):
        with open(os.path.join(self.root, "pkg", *path)) as f:
            return f.read()

    def test_each_name_becomes_a_copy_of_the_file_and_the_l2s_files_go(self):
        self.faked("bin", "avr-g++", "compiler", ("bin", "avr-c++"))
        self.faked("bin", "avr-as", "assembler", ("avr/bin", "as"))

        self.assertEqual(4, fix_moved_links(os.path.join(self.root, "pkg")))

        self.assertEqual("compiler", self.read("bin", "avr-g++"))
        self.assertEqual("compiler", self.read("bin", "avr-c++"))
        self.assertEqual("assembler", self.read("avr", "bin", "as"))
        for folder in ["bin", "avr/bin"]:
            names = os.listdir(os.path.join(self.root, "pkg", folder))
            self.assertEqual([], [n for n in names if n.startswith(".l2s.")], folder)
            for n in names:
                self.assertFalse(os.path.islink(os.path.join(self.root, "pkg", folder, n)), n)

    def test_keeps_the_file_mode(self):
        self.faked("bin", "tool", "x")
        os.chmod(os.path.join(self.root, "pkg", "bin", ".l2s.tool0001.0002"), 0o755)
        fix_moved_links(os.path.join(self.root, "pkg"))
        self.assertEqual(0o755, os.stat(os.path.join(self.root, "pkg", "bin", "tool")).st_mode & 0o777)

    def test_other_links_are_left_alone(self):
        here = os.path.join(self.root, "pkg")
        os.makedirs(here)
        with open(os.path.join(here, "real"), "w") as f:
            f.write("r")
        os.symlink("real", os.path.join(here, "ok"))
        os.symlink("/nowhere/at/all", os.path.join(here, "dangling"))
        os.symlink("/nowhere/.l2s.lost0001", os.path.join(here, "lost"))

        self.assertEqual(0, fix_moved_links(here))
        self.assertEqual("real", os.readlink(os.path.join(here, "ok")))
        self.assertEqual("/nowhere/at/all", os.readlink(os.path.join(here, "dangling")))
        self.assertEqual("/nowhere/.l2s.lost0001", os.readlink(os.path.join(here, "lost")))

    def test_a_folder_with_nothing_to_fix(self):
        self.assertEqual(0, fix_moved_links(self.root))


if __name__ == "__main__":
    unittest.main()
