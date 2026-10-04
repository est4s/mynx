"""Tests for the `pocket` command (tools/bin/pocket).

Run: python3 -m unittest discover -s tests/pocket
"""
import json
import os
import subprocess
import tempfile
import threading
import time
import unittest

POCKET = os.path.join(os.path.dirname(__file__), "..", "..", "tools", "bin", "pocket")


class FakeApp:
    """Answers requests like the app does (core's PocketRequests)."""

    def __init__(self, folder, replies):
        self.folder = folder
        self.replies = replies  # request name -> reply dict
        self.seen = []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            for name in sorted(os.listdir(self.folder)):
                if not name.endswith(".req"):
                    continue
                path = os.path.join(self.folder, name)
                with open(path) as f:
                    request = f.read().splitlines()[0]
                self.seen.append(request)
                reply = os.path.join(self.folder, name[:-4] + ".reply")
                with open(reply + ".tmp", "w") as f:
                    json.dump(self.replies[request], f)
                os.rename(reply + ".tmp", reply)
                os.remove(path)
            time.sleep(0.01)


class PocketTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.requests = os.path.join(self.tmp.name, "requests")
        os.mkdir(self.requests)
        self.tools = os.path.join(self.tmp.name, "tools")
        os.mkdir(self.tools)
        self.app = None

    def tearDown(self):
        if self.app:
            self.app.stop.set()
            self.app.thread.join()
        self.tmp.cleanup()

    def start_app(self, replies):
        self.app = FakeApp(self.requests, replies)
        self.app.thread.start()

    def pocket(self, *args, env=None):
        environ = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1"}
        environ.update(env or {})
        return subprocess.run(["python3", POCKET, *args], capture_output=True, text=True, env=environ)

    # --- check -------------------------------------------------------------

    def test_check_with_no_problems(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.pocket("check")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("No problems", run.stdout)
        self.assertEqual(self.app.seen, ["check"])

    def test_check_lists_problems_by_file_and_fails(self):
        self.start_app({"check": {"ok": True, "problems": [
            {"file": "~/.config/pocket-terminal/colors.properties",
             "problems": ["line 1: expected key=value", "line 4: unknown key 'x'"]}]}})
        run = self.pocket("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/pocket-terminal/colors.properties",
            "  line 1: expected key=value",
            "  line 4: unknown key 'x'",
        ])

    def test_check_json_passes_the_answer_through(self):
        answer = {"ok": True, "problems": [{"file": "f", "problems": ["p"]}]}
        self.start_app({"check": answer})
        run = self.pocket("check", "--json")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(json.loads(run.stdout), answer)

    def test_cleans_up_its_request_files(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.pocket("check")
        self.assertEqual(os.listdir(self.requests), [])

    # --- talking to the app ------------------------------------------------

    def test_outside_the_app_says_so(self):
        run = self.pocket("check", env={"POCKET_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        self.assertIn("only works inside the app", run.stderr)

    def test_an_app_that_doesnt_answer_times_out_and_leaves_no_request(self):
        run = self.pocket("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("didn't answer", run.stderr)
        self.assertEqual(os.listdir(self.requests), [])

    def test_errors_are_json_with_json(self):
        run = self.pocket("check", "--json", env={"POCKET_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        reply = json.loads(run.stdout)
        self.assertFalse(reply["ok"])
        self.assertIn("only works inside the app", reply["error"])

    def test_an_error_from_the_app_is_shown(self):
        self.start_app({"check": {"ok": False, "error": "unknown request 'check'"}})
        run = self.pocket("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown request 'check'", run.stderr)

    # --- help and version --------------------------------------------------

    def test_help_lists_the_commands(self):
        for args in [(), ("help",), ("--help",)]:
            run = self.pocket(*args)
            self.assertEqual(run.returncode, 0)
            self.assertIn("check", run.stdout)
            self.assertIn("--json", run.stdout)

    def test_unknown_command_fails_with_help(self):
        run = self.pocket("frobnicate")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown command 'frobnicate'", run.stderr)

    def test_version_is_the_installed_tools_version(self):
        with open(os.path.join(self.tools, ".version"), "w") as f:
            f.write("57\n")
        self.assertEqual(self.pocket("version").stdout, "57\n")
        self.assertEqual(json.loads(self.pocket("version", "--json").stdout), {"ok": True, "version": "57"})


if __name__ == "__main__":
    unittest.main()
