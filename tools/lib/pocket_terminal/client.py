"""Talks to the app through request files in $POCKET_REQUESTS (the app
sets it in every tab): ID.req holds the request name and one argument
per line, the app answers in ID.reply as JSON. Both are renamed into
place when complete.
"""
import contextlib
import json
import os
import signal
import threading
import time

TOOLS = os.environ.get("POCKET_TOOLS", "/opt/pocket-terminal")


class Failure(Exception):
    """Something to tell the user; `pocket` prints it and exits with 2."""


def request(name, *args, on_line=None, answer_on_interrupt=False):
    """Sends a request to the app and returns its answer (a dict).

    The app may answer later: it then writes ID.wait at once, with the
    seconds to wait or "stream". A stream's readings arrive in ID.stream,
    one JSON object per line, each handed to [on_line]; it runs until the
    app answers, or until we stop (Ctrl+C, a closed pipe), which writes
    ID.cancel so the app stops too. With [answer_on_interrupt], Ctrl+C
    returns the app's answer to that (a recording says what it saved).
    """
    folder = os.environ.get("POCKET_REQUESTS")
    if not folder or not os.path.isdir(folder):
        raise Failure("pocket only works inside the app's terminal")
    if any("\n" in a for a in args):
        raise Failure("values can't contain line breaks")
    ident = f"{os.getpid()}-{time.time_ns()}"
    base = os.path.join(folder, ident)
    with open(base + ".req.tmp", "w") as f:
        f.write("".join(line + "\n" for line in (name, *args)))
    os.rename(base + ".req.tmp", base + ".req")
    timeout = float(os.environ.get("POCKET_TIMEOUT", "5"))
    deadline = time.monotonic() + timeout
    waiting = False
    stream = Lines(base + ".stream", on_line)
    try:
        while not os.path.exists(base + ".reply"):
            if not waiting and os.path.exists(base + ".wait"):
                waiting = True
                wait = read(base + ".wait")
                deadline = None if wait == "stream" else time.monotonic() + float(wait or 0) + timeout
            stream.read()
            if deadline is not None and time.monotonic() > deadline:
                if waiting:
                    touch(base + ".cancel")
                raise Failure("the app didn't answer (is it still running?)")
            time.sleep(0.02)
        stream.read()
        with open(base + ".reply") as f:
            answer = json.load(f)
    except Failure:
        raise
    except BaseException as e:
        if waiting and not os.path.exists(base + ".reply"):
            cancel(base)
        if not (answer_on_interrupt and isinstance(e, KeyboardInterrupt) and os.path.exists(base + ".reply")):
            raise
        with open(base + ".reply") as f:
            answer = json.load(f)
    finally:
        for suffix in (".req", ".wait", ".stream", ".reply", ".cancel"):
            with contextlib.suppress(FileNotFoundError):
                os.remove(base + suffix)
    if not answer.get("ok"):
        raise Failure(answer.get("error", "the app refused the request"))
    return answer


class Lines:
    """Reads the complete lines added to a stream file since the last read."""

    def __init__(self, path, on_line):
        self.path, self.on_line = path, on_line
        self.offset, self.partial = 0, b""

    def read(self):
        if self.on_line is None:
            return
        try:
            with open(self.path, "rb") as f:
                f.seek(self.offset)
                data = f.read()
        except FileNotFoundError:
            return
        self.offset += len(data)
        *lines, self.partial = (self.partial + data).split(b"\n")
        for line in lines:
            if line.strip():
                self.on_line(json.loads(line))


def cancel(base):
    """Asks the app to stop a stream, and gives it a moment to answer."""
    # A second Ctrl+C (timeout(1) sends two) would clean up ID.cancel
    # before the app sees it, and the app would carry on.
    with ignoring_ctrl_c():
        touch(base + ".cancel")
        deadline = time.monotonic() + 5
        while not os.path.exists(base + ".reply") and time.monotonic() < deadline:
            time.sleep(0.02)


@contextlib.contextmanager
def ignoring_ctrl_c():
    if threading.current_thread() is not threading.main_thread():
        yield
        return
    previous = signal.signal(signal.SIGINT, signal.SIG_IGN)
    try:
        yield
    finally:
        signal.signal(signal.SIGINT, previous)


def read(path):
    with open(path) as f:
        return f.read().strip()


def touch(path):
    with contextlib.suppress(OSError):
        open(path, "w").close()


def record(reason):
    """Tells the app the config changed (for `pocket undo`, which names
    the change [reason]) and applies it. Changes made with no app
    answering still count; the app sees them at its next start."""
    try:
        return request("check", reason)
    except Failure:
        return None


def tools_version():
    try:
        with open(os.path.join(TOOLS, ".version")) as f:
            return f.read().strip()
    except OSError:
        raise Failure(f"no version file in {TOOLS}")
