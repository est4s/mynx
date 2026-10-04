"""Smoke tests for the settings editors (`pocket edit …`): they run in a
pseudo-terminal against a fake app, with keys typed in, and must do the
right requests and file changes and exit cleanly."""
import fcntl
import json
import os
import pty
import select
import struct
import sys
import tempfile
import termios
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))

from test_pocket import POCKET, FakeApp  # noqa: E402

# Application cursor keys: what terminals (and the key bar) send in keypad mode.
UP, DOWN, RIGHT, LEFT, ENTER = "\x1bOA", "\x1bOB", "\x1bOC", "\x1bOD", "\r"


class EditorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.requests = os.path.join(self.tmp.name, "requests")
        os.mkdir(self.requests)
        self.tools = os.path.join(self.tmp.name, "tools")
        os.makedirs(os.path.join(self.tools, "bin"))
        self.home = os.path.join(self.tmp.name, "home")
        self.config = os.path.join(self.home, ".config", "pocket-terminal")
        os.makedirs(self.config)
        self.keybar_file = os.path.join(self.tmp.name, "keybar")
        with open(self.keybar_file, "w") as f:
            f.write("shell")
        self.app = None

    def tearDown(self):
        if self.app:
            self.app.stop.set()
            self.app.thread.join()
        self.tmp.cleanup()

    def start_app(self, replies):
        self.app = FakeApp(self.requests, replies)
        self.app.thread.start()

    def edit(self, args, keys, timeout=15):
        """Runs `pocket edit ARGS` in a 56x30 terminal, types [keys] (one
        string per key press); returns (exit code, screen output)."""
        env = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
               "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "2", "TERM": "xterm-256color",
               "LANG": "C.UTF-8", "POCKET_KEYBAR_FILE": self.keybar_file, "ESCDELAY": "25"}
        pid, fd = pty.fork()
        if pid == 0:
            os.execvpe("python3", ["python3", POCKET, "edit", *args], env)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 56, 0, 0))
        output = b""

        def drain(seconds):
            nonlocal output
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                ready, _, _ = select.select([fd], [], [], 0.05)
                if ready:
                    try:
                        output += os.read(fd, 65536)
                    except OSError:
                        return

        drain(1.0)
        for key in keys:
            os.write(fd, key.encode())
            drain(0.4)
        deadline = time.monotonic() + timeout
        while True:
            done, status = os.waitpid(pid, os.WNOHANG)
            if done:
                break
            if time.monotonic() > deadline:
                os.kill(pid, 9)
                os.waitpid(pid, 0)
                self.fail("the editor didn't exit; screen:\n" + output.decode(errors="replace")[-2000:])
            drain(0.1)
        os.close(fd)
        return os.waitstatus_to_exitcode(status), output.decode(errors="replace")

    def read(self, path):
        with open(path) as f:
            return f.read()

    # --- the hub -----------------------------------------------------------

    def test_the_hub_lists_the_editors_and_quits(self):
        code, screen = self.edit([], ["q"])
        self.assertEqual(code, 0, screen)
        for item in ["Theme", "Font & cursor", "Key bars", "Launcher menu"]:
            self.assertIn(item, screen)

    def test_shows_its_own_key_bar_while_open(self):
        seen = []

        def settings():
            with open(self.keybar_file) as f:
                seen.append(f.read())
            return SETTINGS

        self.start_app({"settings": settings})
        self.edit(["settings"], ["q"])
        self.assertEqual(seen[0], "pocket-edit")
        self.assertEqual(self.read(self.keybar_file), "shell")

    # --- font & cursor -----------------------------------------------------

    def test_settings_change_at_once(self):
        self.start_app({"settings": SETTINGS, "set": lambda k, v: {"ok": True, "key": k, "value": v}})
        code, screen = self.edit(["settings"], [RIGHT, DOWN, DOWN, RIGHT, "q"])
        self.assertEqual(code, 0, screen)
        sets = [r for r in self.app.requests if r[0] == "set"]
        self.assertEqual(sets, [["set", "font-size", "15"], ["set", "cursor-style", "underline"]])

    def test_a_refused_setting_is_shown(self):
        self.start_app({"settings": SETTINGS, "set": {"ok": False, "error": "font-size must be small"}})
        code, screen = self.edit(["settings"], [RIGHT, "q"])
        self.assertEqual(code, 0, screen)
        self.assertIn("font-size must be small", screen)

    # --- theme -------------------------------------------------------------

    def test_theme_previews_while_moving_and_sets_on_enter(self):
        self.start_app(THEME_REPLIES)
        code, screen = self.edit(["theme"], [DOWN, ENTER, "q"])
        self.assertEqual(code, 0, screen)
        names = [r[0] for r in self.app.requests]
        self.assertIn(["preview-colors", "background=#2e3440"], self.app.requests)
        self.assertIn(["theme-set", "nord"], self.app.requests)
        self.assertLess(names.index("preview-colors"), names.index("theme-set"))

    def test_leaving_the_theme_list_ends_the_preview(self):
        self.start_app(THEME_REPLIES)
        code, screen = self.edit(["theme"], [DOWN, "q"])
        self.assertEqual(code, 0, screen)
        self.assertEqual(self.app.requests[-1], ["preview-end"])
        self.assertNotIn("theme-set", [r[0] for r in self.app.requests])

    def test_colour_editor_saves_the_colours_file(self):
        self.start_app(THEME_REPLIES)
        # "Edit colours…" is after the themes; background is its first row.
        code, screen = self.edit(["theme"], [UP, ENTER, ENTER, *"\x7f" * 7, *"#123456", ENTER, "s", "q", "q"])
        self.assertEqual(code, 0, screen)
        self.assertIn("background=#123456", self.read(os.path.join(self.config, "colors.properties")))
        self.assertIn(["preview-colors"], [r[:1] for r in self.app.requests])
        self.assertIn(["check"], self.app.requests)

    # --- key bars ----------------------------------------------------------

    def test_key_bar_editor_edits_and_saves_a_copy(self):
        mine = os.path.join(self.config, "keybars", "nnn.conf")

        def edit(name):
            os.makedirs(os.path.dirname(mine), exist_ok=True)
            with open(mine, "w") as f:
                f.write("# built-in\nOpen = l\nQuit = q\n")
            return {"ok": True, "file": "~/.config/pocket-terminal/keybars/nnn.conf"}

        self.start_app({
            "keybars": {"ok": True, "keybars": [{"name": "nnn", "builtIn": True, "file": None}]},
            "keybar-show": {"ok": True, "name": "nnn", "file": None, "text": "# built-in\nOpen = l\nQuit = q\n"},
            "keybar-edit": edit,
            "check": {"ok": True, "problems": []},
        })
        # Open nnn, move Quit up, add "Top = g" after it, save, back, quit.
        code, screen = self.edit(["keybars"], [ENTER, DOWN, "K", "a", *"Top", ENTER, "g", ENTER, "s", "q", "q"])
        self.assertEqual(code, 0, screen)
        self.assertEqual(self.read(mine), "Quit = q\nTop  = g\n# built-in\nOpen = l\n")

    def test_unsaved_changes_are_kept_only_if_asked(self):
        self.start_app({
            "keybars": {"ok": True, "keybars": [{"name": "nnn", "builtIn": True, "file": None}]},
            "keybar-show": {"ok": True, "name": "nnn", "file": None, "text": "Open = l\n"},
        })
        code, screen = self.edit(["keybars"], [ENTER, "d", "q", "y", "q"])
        self.assertEqual(code, 0, screen)
        self.assertIn("Discard", screen)
        self.assertNotIn("keybar-edit", [r[0] for r in self.app.requests])

    # --- menu --------------------------------------------------------------

    def test_menu_editor_saves_the_users_menu_and_checks_it(self):
        with open(os.path.join(self.tools, "menu.conf"), "w") as f:
            f.write("Terminal = shell\nFiles = files\n")
        menu = os.path.join(self.tools, "bin", "menu")
        with open(menu, "w") as f:
            f.write('#!/bin/sh\necho "checked $2" >"$HOME/checked"\n')
        os.chmod(menu, 0o755)
        # Delete Terminal, add "Top" running htop after Files, save.
        code, screen = self.edit(["menu"], ["d", "a", *"Top", ENTER, UP, ENTER, *"htop", ENTER, "s", "q"])
        self.assertEqual(code, 0, screen)
        self.assertEqual(self.read(os.path.join(self.config, "menu.conf")), "Files = files\nTop   = run htop\n")
        self.assertTrue(os.path.exists(os.path.join(self.home, "checked")))


SETTINGS = {"ok": True, "problems": [], "settings": [
    {"key": "font-size", "value": "14", "default": "12", "description": "Text size.", "choices": None},
    {"key": "font", "value": "default", "default": "default", "description": "Font file.", "choices": None},
    {"key": "cursor-style", "value": "block", "default": "block", "description": "Cursor shape.",
     "choices": ["block", "underline", "bar"]},
]}

THEME_REPLIES = {
    "themes": {"ok": True, "current": "neon", "themes": [
        {"name": "neon", "source": "built-in"}, {"name": "nord", "source": "built-in"}]},
    "theme-show": lambda n: {"ok": True, "name": n, "source": "built-in",
                             "text": "background=#14101f\n" if n == "neon" else "background=#2e3440\n"},
    "theme-set": lambda n: {"ok": True, "name": n},
    "preview-colors": lambda *lines: {"ok": True, "problems": []},
    "preview-end": {"ok": True},
    "check": {"ok": True, "problems": []},
}

if __name__ == "__main__":
    unittest.main()
