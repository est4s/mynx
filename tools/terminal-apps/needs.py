"""needs: offers to install what an app needs from Debian, for the apps
that use more than Python's standard library.

    np = needs.numpy("tuner")    # numpy, or exits saying how to get it
"""
import importlib
import subprocess
import sys

INSTALL = "apt install python3-numpy"


def numpy(prog, ask=input, run=subprocess.call, tty=sys.stdin.isatty):
    try:
        return importlib.import_module("numpy")
    except ImportError:
        pass
    about = f"{prog} needs numpy (Debian's python3-numpy, about 20 MB)."
    if not tty():
        sys.exit(f"{about}\nInstall it with: {INSTALL}")
    print(about)
    if ask("Install it now? [y/N] ").strip().lower() not in ("y", "yes"):
        sys.exit(f"Not installed. To install it later: {INSTALL}")
    if run(["sh", "-c", "apt-get update && apt-get install -y python3-numpy"]) != 0:
        sys.exit(f"{prog}: installing numpy failed. Try: {INSTALL}")
    importlib.invalidate_caches()
    try:
        return importlib.import_module("numpy")
    except ImportError:
        sys.exit(f"{prog}: numpy was installed but can't be loaded")
