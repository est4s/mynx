"""Tests for proot/mynx_links/links.c, what proot's mynx_links extension
does to directory listings: link2symlink's faked hard links are symlinks
to the kernel (DT_LNK) and regular files to every other call, so they get
the type of the file they stand for.

The extension itself only moves memory; it runs on the phone. Here
links.c is built as a shared library and given real getdents64 records
of a folder laid out as link2symlink lays it out. Skipped where there's
no C compiler, or where a proot's link2symlink hides that layout.

Run: python3 -m unittest discover -s tests/mynx
"""
import ctypes
import errno
import os
import platform
import shutil
import struct
import subprocess
import tempfile
import unittest

SOURCE = os.path.join(os.path.dirname(__file__), "..", "..", "proot", "mynx_links", "links.c")
CC = shutil.which(os.environ.get("CC", "cc"))
GETDENTS64 = {"x86_64": 217, "aarch64": 61}.get(platform.machine())
DT_DIR, DT_REG, DT_LNK = 4, 8, 10
HEADER = struct.Struct("QqHB")


def getdents64(folder):
    """The folder's records, as the kernel gives them, in one buffer."""
    libc = ctypes.CDLL(None, use_errno=True)
    buf = ctypes.create_string_buffer(65536)
    fd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try:
        length = libc.syscall(GETDENTS64, fd, buf, len(buf))
    finally:
        os.close(fd)
    if length < 0:
        raise OSError(ctypes.get_errno(), "getdents64")
    return buf, length


def types(buf, length):
    """{name: d_type} of the records in buf."""
    found, at = {}, 0
    while at < length:
        _, _, reclen, d_type = HEADER.unpack_from(buf, at)
        name = buf.raw[at + HEADER.size:at + reclen].split(b"\0")[0].decode()
        found[name] = d_type
        at += reclen
    return found


@unittest.skipUnless(CC, "no C compiler")
@unittest.skipUnless(GETDENTS64, "no getdents64 number for this machine")
class LinksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.mkdtemp()
        library = os.path.join(cls.build_dir, "links.so")
        subprocess.run([CC, "-shared", "-fPIC", "-O2", "-Wall", "-Werror", "-o", library, SOURCE], check=True)
        cls.lib = ctypes.CDLL(library)
        cls.lib.mynx_links_fix_types.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.build_dir)

    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder)

    def path(self, name):
        return os.path.join(self.folder, name)

    def fake_hard_link(self, name, n, final_kind="file"):
        """name -> .l2s.<name>NNNN -> .l2s.<name>NNNN.0002, as link2symlink makes them."""
        intermediate = self.path(f".l2s.{name}{n:04d}")
        final = intermediate + ".0002"
        if final_kind == "file":
            with open(final, "w") as f:
                f.write("contents")
        elif final_kind == "symlink":
            os.symlink("elsewhere", final)
        if final_kind != "missing":
            os.symlink(final, intermediate)
        os.symlink(intermediate, self.path(name))
        try:
            os.readlink(self.path(name))
        except OSError as e:
            if e.errno == errno.EINVAL:
                self.skipTest("under proot: its link2symlink hides the layout")
            raise

    def fixed(self):
        buf, length = getdents64(self.folder)
        changed = self.lib.mynx_links_fix_types(self.folder.encode(), buf, length)
        return changed, types(buf, length)

    def test_a_faked_hard_link_is_a_regular_file(self):
        self.fake_hard_link("avr-g++", 1)
        changed, found = self.fixed()
        self.assertEqual(1, changed)
        self.assertEqual(DT_REG, found["avr-g++"])

    def test_the_link_files_themselves_keep_their_types(self):
        self.fake_hard_link("as", 1)
        _, found = self.fixed()
        self.assertEqual(DT_LNK, found[".l2s.as0001"])
        self.assertEqual(DT_REG, found[".l2s.as0001.0002"])

    def test_other_entries_are_left_alone(self):
        open(self.path("plain"), "w").close()
        os.mkdir(self.path("folder"))
        os.symlink("plain", self.path("symlink"))
        os.symlink("missing", self.path("dangling"))
        os.symlink(self.path("folder"), self.path("to-folder"))
        changed, found = self.fixed()
        self.assertEqual(0, changed)
        self.assertEqual(DT_REG, found["plain"])
        self.assertEqual(DT_DIR, found["folder"])
        for name in ("symlink", "dangling", "to-folder"):
            self.assertEqual(DT_LNK, found[name], name)

    def test_a_broken_faked_link_stays_a_symlink(self):
        # As link2symlink: one it can't resolve stays a symlink everywhere.
        self.fake_hard_link("moved", 1, final_kind="missing")
        changed, found = self.fixed()
        self.assertEqual(0, changed)
        self.assertEqual(DT_LNK, found["moved"])

    def test_a_faked_hard_link_to_a_symlink_stays_a_symlink(self):
        self.fake_hard_link("link", 1, final_kind="symlink")
        _, found = self.fixed()
        self.assertEqual(DT_LNK, found["link"])

    def test_many_links_in_one_listing(self):
        for n in range(1, 201):
            self.fake_hard_link(f"tool-{n}", n)
        changed, found = self.fixed()
        self.assertEqual(200, changed)
        self.assertTrue(all(found[f"tool-{n}"] == DT_REG for n in range(1, 201)))

    def test_it_builds_and_works_without_libcs_IFTODT(self):
        # Android's bionic has the DT_* names but not glibc's IFTODT.
        wrapper = os.path.join(self.build_dir, "bionic_like.c")
        with open(wrapper, "w") as f:
            f.write(f'#include <dirent.h>\n#include <sys/stat.h>\n#undef IFTODT\n#include "{os.path.abspath(SOURCE)}"\n')
        library = os.path.join(self.build_dir, "bionic_like.so")
        subprocess.run([CC, "-shared", "-fPIC", "-Wall", "-Werror", "-o", library, wrapper], check=True)
        lib = ctypes.CDLL(library)
        lib.mynx_links_fix_types.argtypes = self.lib.mynx_links_fix_types.argtypes
        self.fake_hard_link("tool", 1)
        buf, length = getdents64(self.folder)
        self.assertEqual(1, lib.mynx_links_fix_types(self.folder.encode(), buf, length))
        self.assertEqual(DT_REG, types(buf, length)["tool"])

    def test_a_cut_buffer_is_read_only_up_to_its_end(self):
        self.fake_hard_link("first", 1)
        buf, length = getdents64(self.folder)
        # A length that ends inside a record: that record isn't touched.
        self.assertGreaterEqual(self.lib.mynx_links_fix_types(self.folder.encode(), buf, length - 1), 0)
        self.assertEqual(0, self.lib.mynx_links_fix_types(self.folder.encode(), buf, 0))


if __name__ == "__main__":
    unittest.main()
