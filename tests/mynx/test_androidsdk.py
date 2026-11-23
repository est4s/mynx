"""Tests for mynx/androidsdk.py: the files for building Android apps.

Run: python3 -m unittest tests/mynx/test_androidsdk.py
"""
import functools
import hashlib
import http.server
import io
import os
import sys
import tempfile
import threading
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))

from mynx import androidsdk  # noqa: E402
from mynx.androidsdk import Download  # noqa: E402


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class AndroidSdkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.served = os.path.join(self.tmp.name, "served")
        self.folder = os.path.join(self.tmp.name, "androidsdk")
        os.makedirs(self.served)
        server = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), functools.partial(Quiet, directory=self.served))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.base = f"http://127.0.0.1:{server.server_port}"

    def serve(self, name, data):
        with open(os.path.join(self.served, name), "wb") as f:
            f.write(data)
        return f"{self.base}/{name}"

    def zipped(self, members):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as z:
            for name, data in members.items():
                z.writestr(name, data)
        return buffer.getvalue()

    def test_fetches_a_file_and_checks_it(self):
        url = self.serve("r8.jar", b"dex tools")
        download = Download("r8.jar", url, sha256(b"dex tools"), 9, "r8.jar")
        path = androidsdk.fetch(download, self.folder)
        self.assertEqual(path, os.path.join(self.folder, "r8.jar"))
        with open(path, "rb") as f:
            self.assertEqual(f.read(), b"dex tools")
        self.assertEqual(os.listdir(self.folder), ["r8.jar"])

    def test_takes_one_file_out_of_a_zip_and_keeps_only_that(self):
        data = self.zipped({"android-34/android.jar": b"the API", "android-34/other.txt": b"no"})
        url = self.serve("platform.zip", data)
        download = Download("android.jar", url, sha256(data), len(data), "android-34/android.jar",
                            member="android-34/android.jar")
        path = androidsdk.fetch(download, self.folder)
        self.assertEqual(path, os.path.join(self.folder, "android-34", "android.jar"))
        with open(path, "rb") as f:
            self.assertEqual(f.read(), b"the API")
        self.assertEqual(os.listdir(self.folder), ["android-34"])
        self.assertEqual(os.listdir(os.path.join(self.folder, "android-34")), ["android.jar"])

    def test_a_download_that_doesnt_match_leaves_nothing(self):
        url = self.serve("r8.jar", b"something else")
        download = Download("r8.jar", url, sha256(b"dex tools"), 9, "r8.jar")
        with self.assertRaisesRegex(ValueError, "r8.jar's checksum doesn't match"):
            androidsdk.fetch(download, self.folder)
        self.assertEqual(os.listdir(self.folder), [])

    def test_a_failed_download_says_which(self):
        download = Download("r8.jar", f"{self.base}/gone.jar", sha256(b"x"), 1, "r8.jar")
        with self.assertRaisesRegex(OSError, "can't download r8.jar from .*/gone.jar"):
            androidsdk.fetch(download, self.folder)
        self.assertEqual(os.listdir(self.folder), [])

    def test_reports_progress(self):
        url = self.serve("r8.jar", b"dex tools")
        seen = []
        androidsdk.fetch(Download("r8.jar", url, sha256(b"dex tools"), 9, "r8.jar"), self.folder,
                         progress=lambda done, total: seen.append((done, total)))
        self.assertEqual(seen[-1], (9, 9))

    def test_status_says_what_is_missing(self):
        status = androidsdk.status(self.folder, which=lambda command: command == "javac")
        self.assertEqual(status["folder"], self.folder)
        self.assertEqual(status["tools"], {"aapt": False, "zipalign": False, "apksigner": False, "javac": True})
        self.assertEqual(status["files"], {"r8.jar": None, "android.jar": None})
        self.assertFalse(status["ready"])
        self.assertEqual(androidsdk.missing_packages(status), ["aapt", "zipalign", "apksigner"])

    def test_status_when_everything_is_there(self):
        for download in androidsdk.FILES:
            path = os.path.join(self.folder, download.path)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "w").close()
        status = androidsdk.status(self.folder, which=lambda command: True)
        self.assertEqual(status["files"], {"r8.jar": os.path.join(self.folder, "r8.jar"),
                                           "android.jar": os.path.join(self.folder, "android-34", "android.jar")})
        self.assertTrue(status["ready"])
        self.assertEqual(androidsdk.missing_packages(status), [])

    def test_the_pinned_files(self):
        # The guide's build.sh uses these paths: ~/androidsdk/r8.jar, ~/androidsdk/android-34/android.jar.
        self.assertEqual([(d.name, d.path) for d in androidsdk.FILES],
                         [("r8.jar", "r8.jar"), ("android.jar", "android-34/android.jar")])
        for download in androidsdk.FILES:
            self.assertTrue(download.url.startswith("https://dl.google.com/android/"), download.url)
            self.assertRegex(download.sha256, "^[0-9a-f]{64}$")
        self.assertEqual(androidsdk.JDK_PACKAGE, "openjdk-21-jdk-headless")


if __name__ == "__main__":
    unittest.main()
