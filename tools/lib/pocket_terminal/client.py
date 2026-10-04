"""Talks to the app through request files in $POCKET_REQUESTS (the app
sets it in every tab): ID.req holds the request name and one argument
per line, the app answers in ID.reply as JSON. Both are renamed into
place when complete.
"""
import json
import os
import time

TOOLS = os.environ.get("POCKET_TOOLS", "/opt/pocket-terminal")


class Failure(Exception):
    """Something to tell the user; `pocket` prints it and exits with 2."""


def request(name, *args):
    """Sends a request to the app and returns its answer (a dict)."""
    folder = os.environ.get("POCKET_REQUESTS")
    if not folder or not os.path.isdir(folder):
        raise Failure("pocket only works inside the app's terminal")
    if any("\n" in a for a in args):
        raise Failure("values can't contain line breaks")
    ident = f"{os.getpid()}-{time.time_ns()}"
    req = os.path.join(folder, ident + ".req")
    reply = os.path.join(folder, ident + ".reply")
    with open(req + ".tmp", "w") as f:
        f.write("".join(line + "\n" for line in (name, *args)))
    os.rename(req + ".tmp", req)
    deadline = time.monotonic() + float(os.environ.get("POCKET_TIMEOUT", "5"))
    while not os.path.exists(reply):
        if time.monotonic() > deadline:
            try:
                os.remove(req)
            except FileNotFoundError:
                pass  # answered just now; the reply is left behind
            raise Failure("the app didn't answer (is it still running?)")
        time.sleep(0.02)
    with open(reply) as f:
        answer = json.load(f)
    os.remove(reply)
    if not answer.get("ok"):
        raise Failure(answer.get("error", "the app refused the request"))
    return answer


def tools_version():
    try:
        with open(os.path.join(TOOLS, ".version")) as f:
            return f.read().strip()
    except OSError:
        raise Failure(f"no version file in {TOOLS}")
