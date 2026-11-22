"""The files for building Android apps in Debian (`mynx android-sdk`).

Debian packages the build tools (aapt, zipalign, apksigner, the JDK);
Google's SDK has the other two: r8.jar (D8 turns .class files into
classes.dex) and android.jar (Android's API to compile against). They
go where the guide's build.sh looks: ~/androidsdk/r8.jar and
~/androidsdk/android-34/android.jar.
"""
import hashlib
import os
import shutil
import tempfile
import urllib.request
import zipfile
from typing import NamedTuple, Optional


class Download(NamedTuple):
    name: str
    url: str
    sha256: str
    size: int
    path: str  # in the SDK folder
    member: Optional[str] = None  # the file to take out of a zip


FILES = [
    Download("r8.jar", "https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar",
             "0d4f2f68c4fcbc9434eff122f41feb6fb1d98efd2faa530cec1abacab1cea03d", 18273598, "r8.jar"),
    Download("android.jar", "https://dl.google.com/android/repository/platform-34-ext7_r03.zip",
             "16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3", 63180081,
             "android-34/android.jar", member="android-34/android.jar"),
]

JDK_PACKAGE = "openjdk-21-jdk-headless"
# The commands a build runs, and the Debian package each comes in.
TOOLS = {"aapt": "aapt", "zipalign": "zipalign", "apksigner": "apksigner", "javac": JDK_PACKAGE}

CHUNK = 1 << 16


def folder(home):
    return os.path.join(home, "androidsdk")


def status(sdk, which=shutil.which):
    tools = {command: bool(which(command)) for command in TOOLS}
    files = {}
    for download in FILES:
        path = os.path.join(sdk, download.path)
        files[download.name] = path if os.path.isfile(path) else None
    return {"folder": sdk, "tools": tools, "files": files,
            "ready": all(tools.values()) and all(files.values())}


def missing_packages(status):
    return list(dict.fromkeys(TOOLS[command] for command, there in status["tools"].items() if not there))


def fetch(download, sdk, progress=None):
    """Downloads [download] into [sdk] and checks it; returns where it is.
    Leaves nothing behind when the download fails or doesn't match."""
    target = os.path.join(sdk, download.path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with tempfile.TemporaryDirectory(dir=sdk, prefix=".download-") as work:
        fetched = os.path.join(work, "download")
        digest = hashlib.sha256()
        try:
            with urllib.request.urlopen(download.url, timeout=60) as answer, open(fetched, "wb") as f:
                total = int(answer.headers.get("Content-Length") or download.size)
                done = 0
                while chunk := answer.read(CHUNK):
                    f.write(chunk)
                    digest.update(chunk)
                    done += len(chunk)
                    if progress:
                        progress(done, total)
        except OSError as e:
            _remove_empty(os.path.dirname(target), sdk)
            raise OSError(f"can't download {download.name} from {download.url}: {e}") from None
        if digest.hexdigest() != download.sha256:
            _remove_empty(os.path.dirname(target), sdk)
            raise ValueError(f"{download.name}'s checksum doesn't match: the download from "
                             f"{download.url} isn't the file it should be")
        if download.member:
            with zipfile.ZipFile(fetched) as z, z.open(download.member) as member, \
                    open(os.path.join(work, "member"), "wb") as f:
                shutil.copyfileobj(member, f)
            fetched = os.path.join(work, "member")
        os.replace(fetched, target)
    return target


def _remove_empty(path, sdk):
    """Removes folders fetch() made for nothing, up to [sdk]."""
    while path != sdk and os.path.isdir(path) and not os.listdir(path):
        os.rmdir(path)
        path = os.path.dirname(path)
