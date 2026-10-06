"""Tests for requests the app answers later, and streams (client.py).

The app's side is core's PendingReply: next to ID.req it writes ID.wait
(seconds, or "stream"), appends readings to ID.stream, and answers in
ID.reply; pc26 cancels a stream by writing ID.cancel.
"""
import json
import os
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))
from pc26 import client  # noqa: E402


class LaterApp:
    """Answers one request the way the app answers a Later request."""

    def __init__(self, folder, wait, lines=(), answer=None, delay=0.0, until_cancelled=False, slow_to_notice=0.0):
        self.folder = folder
        self.slow_to_notice = slow_to_notice
        self.wait, self.lines, self.answer = wait, list(lines), answer or {"ok": True}
        self.delay, self.until_cancelled = delay, until_cancelled
        self.cancelled = False
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def path(self, ident, suffix):
        return os.path.join(self.folder, ident + suffix)

    def put(self, ident, suffix, text):
        try:
            with open(self.path(ident, suffix) + ".tmp", "w") as f:
                f.write(text)
            os.rename(self.path(ident, suffix) + ".tmp", self.path(ident, suffix))
        except FileNotFoundError:
            pass  # the test is over and its folder gone

    def run(self):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            reqs = [n for n in os.listdir(self.folder) if n.endswith(".req")]
            if reqs:
                break
            time.sleep(0.01)
        else:
            return
        ident = reqs[0][:-4]
        os.remove(self.path(ident, ".req"))
        self.put(ident, ".wait", self.wait)
        for line in self.lines:
            with open(self.path(ident, ".stream"), "a") as f:
                f.write(json.dumps(line) + "\n")
            time.sleep(0.02)
        if self.until_cancelled:
            time.sleep(self.slow_to_notice)
            while not os.path.exists(self.path(ident, ".cancel")):
                if time.monotonic() > deadline:
                    return
                time.sleep(0.01)
            self.cancelled = True
            os.remove(self.path(ident, ".cancel"))
        time.sleep(self.delay)
        self.put(ident, ".reply", json.dumps(self.answer))


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.folder = self.tmp.name
        patch = mock.patch.dict(os.environ, {"PC26_REQUESTS": self.folder, "PC26_TIMEOUT": "0.3"})
        patch.start()
        self.addCleanup(patch.stop)
        self.addCleanup(self.tmp.cleanup)

    def left_behind(self):
        return sorted(os.listdir(self.folder))

    def test_waits_as_long_as_the_app_says(self):
        # Longer than PC26_TIMEOUT, within what the app asked for.
        LaterApp(self.folder, "2", answer={"ok": True, "fix": 1}, delay=0.6)
        self.assertEqual(client.request("slow"), {"ok": True, "fix": 1})
        self.assertEqual(self.left_behind(), [])

    def test_gives_up_after_the_wait_the_app_asked_for(self):
        LaterApp(self.folder, "0", delay=3)
        start = time.monotonic()
        with self.assertRaisesRegex(client.Failure, "didn't answer"):
            client.request("slow")
        self.assertLess(time.monotonic() - start, 2)

    def test_a_refusal_after_waiting_is_a_failure(self):
        LaterApp(self.folder, "5", answer={"ok": False, "error": "no GPS fix"}, delay=0.6)
        with self.assertRaisesRegex(client.Failure, "no GPS fix"):
            client.request("slow")

    def test_streams_hand_over_each_line_then_the_answer(self):
        LaterApp(self.folder, "stream", lines=[{"n": 1}, {"n": 2}, {"n": 3}], answer={"ok": True})
        got = []
        self.assertEqual(client.request("feed", on_line=got.append), {"ok": True})
        self.assertEqual(got, [{"n": 1}, {"n": 2}, {"n": 3}])
        self.assertEqual(self.left_behind(), [])

    def test_a_stream_has_no_time_limit(self):
        LaterApp(self.folder, "stream", lines=[{"n": 1}], delay=0.6)
        got = []
        client.request("feed", on_line=got.append)
        self.assertEqual(got, [{"n": 1}])

    def test_stopping_a_stream_cancels_it_in_the_app(self):
        app = LaterApp(self.folder, "stream", lines=[{"n": 1}, {"n": 2}], until_cancelled=True)

        def stop_after_two(line):
            if line["n"] == 2:
                raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            client.request("feed", on_line=stop_after_two)
        app.thread.join(2)
        self.assertTrue(app.cancelled)
        self.assertEqual(self.left_behind(), [])

    def test_a_second_ctrl_c_doesnt_take_the_cancel_back(self):
        # timeout(1) and impatient people send two; the app may look later.
        app = LaterApp(self.folder, "stream", lines=[{"n": 1}], until_cancelled=True, slow_to_notice=0.4)

        def stop(line):
            threading.Timer(0.1, os.kill, (os.getpid(), signal.SIGINT)).start()
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            client.request("feed", on_line=stop)
        app.thread.join(2)
        self.assertTrue(app.cancelled)
        self.assertEqual(self.left_behind(), [])
        self.assertIs(signal.getsignal(signal.SIGINT), signal.default_int_handler)


if __name__ == "__main__":
    unittest.main()
