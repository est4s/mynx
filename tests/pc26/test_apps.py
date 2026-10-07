"""Tests for the example apps (tools/apps, /opt/pc26/apps) and their
commands in tools/bin.

Run: python3 -m unittest discover -s tests/pc26
"""
import contextlib
import importlib.util
import io
import os
import subprocess
import sys
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
APPS = os.path.join(ROOT, "tools", "apps")
BIN = os.path.join(ROOT, "tools", "bin")
BARS = os.path.join(ROOT, "core", "src", "main", "resources", "io", "github", "est4s",
                    "terminal", "core", "keybars")
NAMES = ["compass", "incline", "torch", "spectrum", "dbmeter", "tuner", "metronome"]


def load_needs():
    spec = importlib.util.spec_from_file_location("needs", os.path.join(APPS, "needs.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_app(name, *args):
    return subprocess.run([sys.executable, os.path.join(APPS, name), *args],
                          stdin=subprocess.DEVNULL, capture_output=True, text=True,
                          timeout=30, env={**os.environ, "PC26_REQUESTS": "/nonexistent"})


class AppsTest(unittest.TestCase):
    def test_each_app_is_an_executable_with_a_menu_label(self):
        for name in NAMES:
            path = os.path.join(APPS, name)
            self.assertTrue(os.access(path, os.X_OK), name)
            with open(path) as f:
                head = f.read(400).splitlines()
            self.assertEqual(head[0], "#!/usr/bin/env python3", name)
            self.assertTrue(head[1].startswith("# label: "), name)
            self.assertLessEqual(len(head[1]) - len("# label: "), 20, name)

    def test_the_shared_modules_are_not_apps(self):
        for name in ["mic.py", "needs.py"]:
            self.assertFalse(os.access(os.path.join(APPS, name), os.X_OK), name)

    def test_apps_find_the_apps_library_next_to_them(self):
        # Not at the installed path: that would test the installed copy, or nothing.
        for name in NAMES:
            with open(os.path.join(APPS, name)) as f:
                self.assertNotIn('"/opt/pc26/lib"', f.read(), name)

    def test_help_prints_what_the_app_does(self):
        for name in NAMES:
            run = run_app(name, "--help")
            self.assertEqual(run.returncode, 0, f"{name}: {run.stderr}")
            self.assertIn(name, run.stdout.split("\n")[0], name)
            self.assertIn("q", run.stdout, name)

    def test_without_a_terminal_an_app_says_so(self):
        for name in NAMES:
            run = run_app(name)
            self.assertEqual(run.returncode, 2, f"{name}: {run.stdout}{run.stderr}")
            self.assertIn("needs a terminal", run.stderr, name)

    def test_each_app_has_a_command_with_its_key_bar(self):
        for name in NAMES:
            with open(os.path.join(BIN, name)) as f:
                script = f.read()
            self.assertIn(f"keybar {name},shell ", script, name)
            self.assertIn(f"/apps/{name}", script, name)
            self.assertTrue(os.path.isfile(os.path.join(BARS, name + ".conf")), name)

    def test_a_command_runs_its_app(self):
        run = subprocess.run([os.path.join(BIN, "compass"), "--help"], capture_output=True,
                             text=True, timeout=30,
                             env={**os.environ, "PATH": BIN + os.pathsep + os.environ["PATH"],
                                  "PC26_KEYBAR_FILE": ""})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("compass", run.stdout)


class NeedsNumpyTest(unittest.TestCase):
    def setUp(self):
        self.needs = load_needs()
        self.saved = sys.modules.get("numpy", "absent")
        sys.modules["numpy"] = None  # makes `import numpy` fail
        self.ran = []
        self.asked = []

    def tearDown(self):
        if self.saved == "absent":
            sys.modules.pop("numpy", None)
        else:
            sys.modules["numpy"] = self.saved

    def numpy(self, answer, tty=True, result=0):
        def ask(prompt):
            self.asked.append(prompt)
            return answer

        def run(cmd):
            self.ran.append(cmd)
            return result

        with contextlib.redirect_stdout(io.StringIO()):
            return self.needs.numpy("tuner", ask=ask, run=run, tty=lambda: tty)

    def test_returns_numpy_when_it_is_there(self):
        fake = object()
        sys.modules["numpy"] = fake
        self.assertIs(self.needs.numpy("tuner", ask=None, run=None, tty=None), fake)

    def test_offers_to_install_it_and_does_on_yes(self):
        with self.assertRaises(SystemExit) as stop:  # still missing after the fake install
            self.numpy("y")
        self.assertEqual(len(self.asked), 1)
        self.assertIn("Install it now?", self.asked[0])
        self.assertEqual(len(self.ran), 1)
        self.assertIn("apt-get install -y python3-numpy", " ".join(self.ran[0]))
        self.assertIn("apt-get update", " ".join(self.ran[0]))
        self.assertIn("tuner", str(stop.exception.code))

    def test_no_installs_nothing_and_says_how(self):
        with self.assertRaises(SystemExit) as stop:
            self.numpy("n")
        self.assertEqual(self.ran, [])
        self.assertIn("apt install python3-numpy", str(stop.exception.code))

    def test_a_failed_install_says_so(self):
        with self.assertRaises(SystemExit) as stop:
            self.numpy("y", result=100)
        self.assertIn("failed", str(stop.exception.code))

    def test_without_a_terminal_it_only_says_how(self):
        with self.assertRaises(SystemExit) as stop:
            self.numpy("y", tty=False)
        self.assertEqual(self.asked, [])
        self.assertEqual(self.ran, [])
        self.assertIn("apt install python3-numpy", str(stop.exception.code))


class MenuActionsTest(unittest.TestCase):
    def test_the_menu_editor_offers_every_menu_action(self):
        sys.path.insert(0, os.path.join(ROOT, "tools", "lib"))
        from pc26 import editors
        with open(os.path.join(BIN, "menu")) as f:
            line = next(l for l in f if l.startswith("MENU_ACTIONS="))
        actions = line.split('"')[1].replace(" or run COMMAND", "").split(", ")
        self.assertIn("apps", actions)
        self.assertEqual(editors.MENU_ACTIONS, actions)


if __name__ == "__main__":
    unittest.main()
