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
        self.replies = replies  # request name -> reply dict, or a function of the arguments
        self.seen = []  # request names
        self.requests = []  # [name, *args]
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            for name in sorted(os.listdir(self.folder)):
                if not name.endswith(".req"):
                    continue
                path = os.path.join(self.folder, name)
                with open(path) as f:
                    lines = f.read().splitlines()
                request = lines[0]
                self.seen.append(request)
                self.requests.append(lines)
                answer = self.replies[request]
                if callable(answer):
                    answer = answer(*lines[1:])
                reply = os.path.join(self.folder, name[:-4] + ".reply")
                with open(reply + ".tmp", "w") as f:
                    json.dump(answer, f)
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
        self.home = os.path.join(self.tmp.name, "home")
        self.config = os.path.join(self.home, ".config", "pocket-terminal")
        os.makedirs(self.config)
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
        environ = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
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


    # --- settings ----------------------------------------------------------

    SETTINGS = {"ok": True, "problems": [], "settings": [
        {"key": "font-size", "value": "14", "default": "12", "description": "Text size.", "choices": None},
        {"key": "cursor-style", "value": "block", "default": "block", "description": "Cursor shape.",
         "choices": ["block", "underline", "bar"]},
    ]}

    def test_settings_lists_values_with_descriptions(self):
        self.start_app({"settings": self.SETTINGS})
        run = self.pocket("settings")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.splitlines(), [
            "font-size = 14  (default 12)",
            "  Text size.",
            "cursor-style = block  (block, underline, bar)",
            "  Cursor shape.",
        ])

    def test_settings_json(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(json.loads(self.pocket("settings", "--json").stdout), self.SETTINGS)

    def test_get_prints_one_value(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(self.pocket("get", "font-size").stdout, "14\n")
        run = self.pocket("get", "colour")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown setting 'colour'", run.stderr)

    def test_set_sends_the_key_and_value(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        run = self.pocket("set", "font-size", "16")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "font-size = 16\n")
        self.assertEqual(self.app.requests, [["set", "font-size", "16"]])

    def test_set_joins_a_value_with_spaces(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        self.pocket("set", "font", "/root/My", "Font.ttf")
        self.assertEqual(self.app.requests, [["set", "font", "/root/My Font.ttf"]])

    def test_commands_say_what_arguments_they_need(self):
        run = self.pocket("set", "font-size")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pocket set KEY VALUE", run.stderr)

    def test_reset_one_setting_or_all(self):
        self.start_app({"reset": lambda k: {"ok": True, "key": k, "value": "12"} if k != "all" else {"ok": True, "key": "all"}})
        self.assertEqual(self.pocket("reset", "font-size").stdout, "font-size = 12 (default)\n")
        self.assertEqual(self.pocket("reset", "all").stdout, "All settings back to their defaults\n")
        self.assertEqual(self.app.requests, [["reset", "font-size"], ["reset", "all"]])
        self.assertIn("usage: pocket reset KEY|all", self.pocket("reset").stderr)

    # --- themes ------------------------------------------------------------

    THEMES = {"ok": True, "current": "nord", "themes": [
        {"name": "neon", "source": "built-in"}, {"name": "nord", "source": "built-in"},
        {"name": "mine", "source": "~/.config/pocket-terminal/themes/mine.colors.properties"}]}

    def test_theme_list_marks_the_current_one(self):
        self.start_app({"themes": self.THEMES})
        for args in [("theme",), ("theme", "list")]:
            self.assertEqual(self.pocket(*args).stdout.splitlines(), [
                "  neon", "* nord", "  mine  (~/.config/pocket-terminal/themes/mine.colors.properties)"])

    def test_theme_set_and_show(self):
        self.start_app({"theme-set": lambda n: {"ok": True, "name": n},
                        "theme-show": lambda n: {"ok": True, "name": n, "source": "built-in", "text": "background=#000000\n"}})
        self.assertEqual(self.pocket("theme", "set", "nord").stdout, "Theme: nord\n")
        self.assertEqual(self.pocket("theme", "show", "nord").stdout, "background=#000000\n")
        self.assertEqual(self.app.requests, [["theme-set", "nord"], ["theme-show", "nord"]])

    def test_theme_reset(self):
        self.start_app({"theme-reset": {"ok": True, "name": "neon"}})
        self.assertEqual(self.pocket("theme", "reset").stdout, "Theme: neon (the default)\n")

    def test_theme_unknown_subcommand(self):
        run = self.pocket("theme", "paint")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pocket theme", run.stderr)

    # --- key bars ----------------------------------------------------------

    def test_keybar_list(self):
        self.start_app({"keybars": {"ok": True, "keybars": [
            {"name": "htop", "builtIn": False, "file": "~/.config/pocket-terminal/keybars/htop.conf"},
            {"name": "nnn", "builtIn": True, "file": "~/.config/pocket-terminal/keybars/nnn.conf"},
            {"name": "shell", "builtIn": True, "file": None}]}})
        self.assertEqual(self.pocket("keybar", "list").stdout.splitlines(), [
            "htop   yours    ~/.config/pocket-terminal/keybars/htop.conf",
            "nnn    edited   ~/.config/pocket-terminal/keybars/nnn.conf",
            "shell  built-in",
        ])

    def test_keybar_show_edit_reset(self):
        self.start_app({
            "keybar-show": lambda n: {"ok": True, "name": n, "file": None, "text": "Quit = q\n"},
            "keybar-edit": lambda n: {"ok": True, "file": f"~/.config/pocket-terminal/keybars/{n}.conf"},
            "keybar-reset": lambda n: {"ok": True}})
        self.assertEqual(self.pocket("keybar", "show", "nnn").stdout, "Quit = q\n")
        self.assertEqual(self.pocket("keybar", "edit", "nnn").stdout.splitlines()[0],
                         "Edit ~/.config/pocket-terminal/keybars/nnn.conf, then run pocket check.")
        self.assertEqual(self.pocket("keybar", "reset", "nnn").stdout, "nnn: back to the built-in bar\n")
        self.assertEqual([r[0] for r in self.app.requests], ["keybar-show", "keybar-edit", "keybar-reset"])


    # --- menu --------------------------------------------------------------

    def write(self, path, text, mode=0o644):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
        os.chmod(path, mode)

    def test_menu_show_prints_the_menu_in_use(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.assertEqual(self.pocket("menu").stdout, "Terminal = shell\n")
        self.write(os.path.join(self.config, "menu.conf"), "Mine = files\n")
        self.assertEqual(self.pocket("menu", "show").stdout, "Mine = files\n")
        self.assertEqual(json.loads(self.pocket("menu", "show", "--json").stdout),
                         {"ok": True, "file": "~/.config/pocket-terminal/menu.conf", "text": "Mine = files\n"})

    def test_menu_edit_copies_the_built_in_menu_once(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        run = self.pocket("menu", "edit")
        self.assertEqual(run.stdout.splitlines()[0], "Edit ~/.config/pocket-terminal/menu.conf, then run pocket check.")
        mine = os.path.join(self.config, "menu.conf")
        with open(mine) as f:
            self.assertEqual(f.read(), "Terminal = shell\n")
        self.write(mine, "Mine = files\n")
        self.pocket("menu", "edit")
        with open(mine) as f:
            self.assertEqual(f.read(), "Mine = files\n")

    def test_menu_reset_removes_the_users_menu(self):
        mine = os.path.join(self.config, "menu.conf")
        self.write(mine, "Mine = files\n")
        self.assertEqual(self.pocket("menu", "reset").stdout, "Menu: back to the built-in one\n")
        self.assertFalse(os.path.exists(mine))
        run = self.pocket("menu", "reset")
        self.assertEqual(run.returncode, 2)
        self.assertIn("already the built-in menu", run.stderr)

    def test_check_includes_problems_in_the_users_menu(self):
        # The menu checks its own file: menu --check FILE.
        self.write(os.path.join(self.tools, "bin", "menu"),
                   '#!/bin/sh\n[ "$1" = --check ] && echo "line 2: bad $2" && exit 1\n', 0o755)
        self.write(os.path.join(self.config, "menu.conf"), "x\n")
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.pocket("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/pocket-terminal/menu.conf",
            f"  line 2: bad {self.config}/menu.conf",
        ])


if __name__ == "__main__":
    unittest.main()
