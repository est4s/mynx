"""Tests for the `mynx` command (tools/bin/mynx).

Run: python3 -m unittest discover -s tests/mynx
"""
import contextlib
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse

MYNX = os.path.join(os.path.dirname(__file__), "..", "..", "tools", "bin", "mynx")


class FakeApp:
    """Answers requests like the app does (core's MynxRequests)."""

    def __init__(self, folder, replies):
        self.folder = folder
        self.replies = replies  # request name -> reply dict, or a function of the arguments
        self.seen = []  # request names
        self.requests = []  # [name, *args]
        self.stop = threading.Event()
        self.cancelled = False
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
                if "_lines" in answer:
                    answer = self.stream(path[:-4], answer)
                with open(reply + ".tmp", "w") as f:
                    json.dump(answer, f)
                os.rename(reply + ".tmp", reply)
                os.remove(path)
            time.sleep(0.01)


    def stream(self, base, answer):
        """A stream: the readings in "_lines", then the answer, or with
        "_hold" no answer until mynx cancels it."""
        with open(base + ".wait", "w") as f:
            f.write("stream")
        with open(base + ".stream", "a") as f:
            f.writelines(json.dumps(line) + "\n" for line in answer["_lines"])
        if answer.get("_hold"):
            while not os.path.exists(base + ".cancel") and not self.stop.is_set():
                time.sleep(0.01)
            self.cancelled = True
            return answer.get("_stopped", {"ok": True})
        return {k: v for k, v in answer.items() if not k.startswith("_")}


class MynxTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.requests = os.path.join(self.tmp.name, "requests")
        os.mkdir(self.requests)
        self.tools = os.path.join(self.tmp.name, "tools")
        os.mkdir(self.tools)
        self.home = os.path.join(self.tmp.name, "home")
        self.config = os.path.join(self.home, ".config", "mynx")
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

    def mynx(self, *args, env=None, cwd=None, input=None):
        environ = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        environ.update(env or {})
        return subprocess.run(["python3", MYNX, *args], capture_output=True, text=True, env=environ, cwd=cwd,
                              input=input)

    def wait_for_request(self):
        deadline = time.monotonic() + 5
        while not self.app.requests:
            self.assertLess(time.monotonic(), deadline, "mynx sent no request")
            time.sleep(0.01)

    def popen(self, *args):
        environ = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        return subprocess.Popen(["python3", MYNX, *args], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    # --- check -------------------------------------------------------------

    def test_check_with_no_problems(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.mynx("check")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("No problems", run.stdout)
        self.assertEqual(self.app.seen, ["check"])

    def test_check_lists_problems_by_file_and_fails(self):
        self.start_app({"check": {"ok": True, "problems": [
            {"file": "~/.config/mynx/colors.properties",
             "problems": ["line 1: expected key=value", "line 4: unknown key 'x'"]}]}})
        run = self.mynx("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/mynx/colors.properties",
            "  line 1: expected key=value",
            "  line 4: unknown key 'x'",
        ])

    def test_check_json_passes_the_answer_through(self):
        answer = {"ok": True, "problems": [{"file": "f", "problems": ["p"]}]}
        self.start_app({"check": answer})
        run = self.mynx("check", "--json")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(json.loads(run.stdout), answer)

    def test_cleans_up_its_request_files(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.mynx("check")
        self.assertEqual(os.listdir(self.requests), [])

    # --- talking to the app ------------------------------------------------

    def test_outside_the_app_says_so(self):
        run = self.mynx("check", env={"MYNX_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        self.assertIn("only works inside the app", run.stderr)

    def test_an_app_that_doesnt_answer_times_out_and_leaves_no_request(self):
        run = self.mynx("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("didn't answer", run.stderr)
        self.assertEqual(os.listdir(self.requests), [])

    def test_errors_are_json_with_json(self):
        run = self.mynx("check", "--json", env={"MYNX_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        reply = json.loads(run.stdout)
        self.assertFalse(reply["ok"])
        self.assertIn("only works inside the app", reply["error"])

    def test_an_error_from_the_app_is_shown(self):
        self.start_app({"check": {"ok": False, "error": "unknown request 'check'"}})
        run = self.mynx("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown request 'check'", run.stderr)
        self.assertTrue(run.stderr.startswith("mynx: "), run.stderr)

    # --- help and version --------------------------------------------------

    def test_help_lists_the_commands(self):
        for args in [(), ("help",), ("--help",)]:
            run = self.mynx(*args)
            self.assertEqual(run.returncode, 0)
            self.assertIn("check", run.stdout)
            self.assertIn("--json", run.stdout)

    def test_unknown_command_fails_with_help(self):
        run = self.mynx("frobnicate")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown command 'frobnicate'", run.stderr)

    def test_version_is_the_installed_tools_version(self):
        with open(os.path.join(self.tools, ".version"), "w") as f:
            f.write("57\n")
        self.assertEqual(self.mynx("version").stdout, "57\n")
        self.assertEqual(json.loads(self.mynx("version", "--json").stdout), {"ok": True, "version": "57"})

    def test_welcome_prints_the_welcome_page(self):
        with open(os.path.join(self.tools, "welcome.txt"), "w") as f:
            f.write("Hello.\n\nMenu  type menu\n")
        run = self.mynx("welcome")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Hello.\n\nMenu  type menu\n")
        self.assertEqual(json.loads(self.mynx("welcome", "--json").stdout),
                         {"ok": True, "text": "Hello.\n\nMenu  type menu\n"})

    def test_welcome_without_its_page_fails(self):
        run = self.mynx("welcome")
        self.assertEqual(run.returncode, 2)
        self.assertIn("welcome.txt", run.stderr)

    # --- about -------------------------------------------------------------

    LICENSES = ["MIT", "GPL-2.0", "LGPL-3.0", "GPL-3.0", "Apache-2.0", "OFL-1.1"]

    def write_about_files(self):
        with open(os.path.join(self.tools, ".version"), "w") as f:
            f.write("57-1700000000000\n")
        os.mkdir(os.path.join(self.tools, "licenses"))
        for name in self.LICENSES:
            with open(os.path.join(self.tools, "licenses", name + ".txt"), "w") as f:
                f.write(f"full text of {name}\n")

    def test_about_shows_the_version_credits_and_license_texts(self):
        self.write_about_files()
        run = self.mynx("about")
        self.assertEqual(run.returncode, 0, run.stderr)
        text = run.stdout
        self.assertIn("mynx", text)
        self.assertIn("build 57", text)
        for part in ["proot", "https://github.com/termux/proot", "GPL-2.0",
                     "talloc", "LGPL-3.0", "https://www.samba.org/ftp/talloc/",
                     "terminal-view", "v0.118.3", "https://github.com/termux/termux-app", "Apache-2.0",
                     "JetBrains Mono Nerd Font", "OFL-1.1",
                     "Debian", "/usr/share/doc/", "PulseAudio",
                     "separate program"]:
            self.assertIn(part, text)
        for name in self.LICENSES:
            self.assertIn(f"full text of {name}", text)

    def test_about_states_the_apps_own_license_and_source(self):
        self.write_about_files()
        text = self.mynx("about").stdout
        credits = text[:text.index("Made with:")]
        self.assertIn("MIT License", credits)
        self.assertNotIn("General Public License", credits)
        self.assertIn("https://github.com/est4s/mynx", credits)
        answer = json.loads(self.mynx("about", "--json").stdout)
        self.assertEqual(answer["license"], "MIT")
        self.assertEqual(answer["source"], "https://github.com/est4s/mynx")

    def test_about_fits_the_phone_screen(self):
        self.write_about_files()
        lines = self.mynx("about").stdout.splitlines()
        credits = lines[:lines.index(next(l for l in lines if "full text of" in l))]
        for line in credits:
            self.assertLessEqual(len(line), 56, line)

    def test_about_json_lists_the_components(self):
        self.write_about_files()
        answer = json.loads(self.mynx("about", "--json").stdout)
        self.assertTrue(answer["ok"])
        self.assertEqual(answer["name"], "mynx")
        self.assertEqual(answer["version"], "57-1700000000000")
        self.assertEqual(answer["build"], "57")
        by_name = {c["name"]: c for c in answer["components"]}
        self.assertEqual(by_name["proot"]["license"], "GPL-2.0-or-later")
        self.assertEqual(by_name["proot"]["source"], "https://github.com/termux/proot")
        self.assertEqual(by_name["talloc"]["license"], "LGPL-3.0-or-later")
        for c in answer["components"]:
            self.assertEqual(set(c), {"name", "version", "license", "source", "note"}, c)
            self.assertTrue(c["license"] and c["source"], c)

    def test_about_shows_the_version_name_when_the_app_wrote_one(self):
        self.write_about_files()
        with open(os.path.join(self.tools, ".version"), "w") as f:
            f.write("57-1700000000000-0.1.0\n")
        self.assertIn("version 0.1.0 (build 57)", self.mynx("about").stdout)
        answer = json.loads(self.mynx("about", "--json").stdout)
        self.assertEqual(answer["version_name"], "0.1.0")
        self.assertEqual(answer["build"], "57")

    def test_about_json_has_no_version_name_from_older_apps(self):
        self.write_about_files()
        self.assertIsNone(json.loads(self.mynx("about", "--json").stdout)["version_name"])

    def test_about_without_a_version_file_still_shows_the_credits(self):
        os.mkdir(os.path.join(self.tools, "licenses"))
        run = self.mynx("about")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("proot", run.stdout)
        self.assertIsNone(json.loads(self.mynx("about", "--json").stdout)["version"])

    def test_about_has_every_license_text_it_names(self):
        sys.path.insert(0, os.path.join(os.path.dirname(MYNX), "..", "lib"))
        from mynx import cli
        shipped = os.path.join(os.path.dirname(MYNX), "..", "licenses")
        for name in cli.LICENSE_FILES:
            self.assertTrue(os.path.getsize(os.path.join(shipped, name + ".txt")) > 1000, name)
        for c in cli.COMPONENTS:
            for license in c["license"].replace("-or-later", "").split():
                if license[0].isupper():
                    self.assertIn(license, cli.LICENSE_FILES, c["name"])

    def test_about_on_a_terminal_goes_through_the_pager(self):
        import pty
        self.write_about_files()
        paged = os.path.join(self.tmp.name, "paged")
        pager = os.path.join(self.tmp.name, "less")
        with open(pager, "w") as f:
            f.write(f"#!/bin/sh\ncat >{paged}\n")
        os.chmod(pager, 0o755)
        main, side = pty.openpty()
        environ = {"PATH": self.tmp.name + os.pathsep + os.environ["PATH"], "HOME": self.home,
                   "MYNX_TOOLS": self.tools}
        proc = subprocess.run(["python3", MYNX, "about"], stdout=side, stderr=side, env=environ, timeout=20)
        os.close(side)
        os.close(main)
        self.assertEqual(proc.returncode, 0)
        with open(paged) as f:
            text = f.read()
        self.assertIn("https://github.com/termux/proot", text)
        self.assertIn("full text of GPL-2.0", text)


    # --- settings ----------------------------------------------------------

    SETTINGS = {"ok": True, "problems": [], "settings": [
        {"key": "font-size", "value": "14", "default": "12", "description": "Text size.", "choices": None},
        {"key": "cursor-style", "value": "block", "default": "block", "description": "Cursor shape.",
         "choices": ["block", "underline", "bar"]},
    ]}

    def test_settings_lists_values_with_descriptions(self):
        self.start_app({"settings": self.SETTINGS})
        run = self.mynx("settings")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.splitlines(), [
            "font-size = 14  (default 12)",
            "  Text size.",
            "cursor-style = block  (block, underline, bar)",
            "  Cursor shape.",
        ])

    def test_settings_json(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(json.loads(self.mynx("settings", "--json").stdout), self.SETTINGS)

    def test_get_prints_one_value(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(self.mynx("get", "font-size").stdout, "14\n")
        run = self.mynx("get", "colour")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown setting 'colour'", run.stderr)

    def test_set_sends_the_key_and_value(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        run = self.mynx("set", "font-size", "16")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "font-size = 16\n")
        self.assertEqual(self.app.requests, [["set", "font-size", "16"]])

    def test_set_joins_a_value_with_spaces(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        self.mynx("set", "font", "/root/My", "Font.ttf")
        self.assertEqual(self.app.requests, [["set", "font", "/root/My Font.ttf"]])

    def test_commands_say_what_arguments_they_need(self):
        run = self.mynx("set", "font-size")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx set KEY VALUE", run.stderr)

    def test_reset_one_setting_or_all(self):
        self.start_app({"reset": lambda k: {"ok": True, "key": k, "value": "12"} if k != "all" else {"ok": True, "key": "all"}})
        self.assertEqual(self.mynx("reset", "font-size").stdout, "font-size = 12 (default)\n")
        self.assertEqual(self.mynx("reset", "all").stdout, "All settings back to their defaults\n")
        self.assertEqual(self.app.requests, [["reset", "font-size"], ["reset", "all"]])
        self.assertIn("usage: mynx reset KEY|all", self.mynx("reset").stderr)

    # --- themes ------------------------------------------------------------

    THEMES = {"ok": True, "current": "nord", "themes": [
        {"name": "neon", "source": "built-in"}, {"name": "nord", "source": "built-in"},
        {"name": "mine", "source": "~/.config/mynx/themes/mine.colors.properties"}]}

    def test_theme_list_marks_the_current_one(self):
        self.start_app({"themes": self.THEMES})
        for args in [("theme",), ("theme", "list")]:
            self.assertEqual(self.mynx(*args).stdout.splitlines(), [
                "  neon", "* nord", "  mine  (~/.config/mynx/themes/mine.colors.properties)"])

    def test_theme_set_and_show(self):
        self.start_app({"theme-set": lambda n: {"ok": True, "name": n},
                        "theme-show": lambda n: {"ok": True, "name": n, "source": "built-in", "text": "background=#000000\n"}})
        self.assertEqual(self.mynx("theme", "set", "nord").stdout, "Theme: nord\n")
        self.assertEqual(self.mynx("theme", "show", "nord").stdout, "background=#000000\n")
        self.assertEqual(self.app.requests, [["theme-set", "nord"], ["theme-show", "nord"]])

    def test_theme_reset(self):
        self.start_app({"theme-reset": {"ok": True, "name": "neon"}})
        self.assertEqual(self.mynx("theme", "reset").stdout, "Theme: neon (the default)\n")

    def test_theme_unknown_subcommand(self):
        run = self.mynx("theme", "paint")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx theme", run.stderr)

    # --- key bars ----------------------------------------------------------

    def test_keybar_list(self):
        self.start_app({"keybars": {"ok": True, "keybars": [
            {"name": "htop", "builtIn": False, "file": "~/.config/mynx/keybars/htop.conf"},
            {"name": "nnn", "builtIn": True, "file": "~/.config/mynx/keybars/nnn.conf"},
            {"name": "shell", "builtIn": True, "file": None}]}})
        self.assertEqual(self.mynx("keybar", "list").stdout.splitlines(), [
            "htop   yours    ~/.config/mynx/keybars/htop.conf",
            "nnn    edited   ~/.config/mynx/keybars/nnn.conf",
            "shell  built-in",
        ])

    def test_keybar_show_edit_reset(self):
        self.start_app({
            "keybar-show": lambda n: {"ok": True, "name": n, "file": None, "text": "Quit = q\n"},
            "keybar-edit": lambda n: {"ok": True, "file": f"~/.config/mynx/keybars/{n}.conf"},
            "keybar-reset": lambda n: {"ok": True}})
        self.assertEqual(self.mynx("keybar", "show", "nnn").stdout, "Quit = q\n")
        self.assertEqual(self.mynx("keybar", "edit", "nnn").stdout.splitlines()[0],
                         "Edit ~/.config/mynx/keybars/nnn.conf, then run mynx check.")
        self.assertEqual(self.mynx("keybar", "reset", "nnn").stdout, "nnn: back to the built-in bar\n")
        self.assertEqual([r[0] for r in self.app.requests], ["keybar-show", "keybar-edit", "keybar-reset"])


    # --- menu --------------------------------------------------------------

    def write(self, path, text, mode=0o644):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
        os.chmod(path, mode)

    def test_menu_show_prints_the_menu_in_use(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.assertEqual(self.mynx("menu").stdout, "Terminal = shell\n")
        self.write(os.path.join(self.config, "menu.conf"), "Mine = files\n")
        self.assertEqual(self.mynx("menu", "show").stdout, "Mine = files\n")
        self.assertEqual(json.loads(self.mynx("menu", "show", "--json").stdout),
                         {"ok": True, "file": "~/.config/mynx/menu.conf", "text": "Mine = files\n"})

    def test_menu_edit_copies_the_built_in_menu_once(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        run = self.mynx("menu", "edit")
        self.assertEqual(run.stdout.splitlines()[0], "Edit ~/.config/mynx/menu.conf, then run mynx check.")
        mine = os.path.join(self.config, "menu.conf")
        with open(mine) as f:
            self.assertEqual(f.read(), "Terminal = shell\n")
        self.write(mine, "Mine = files\n")
        self.mynx("menu", "edit")
        with open(mine) as f:
            self.assertEqual(f.read(), "Mine = files\n")

    def test_menu_reset_removes_the_users_menu(self):
        mine = os.path.join(self.config, "menu.conf")
        self.write(mine, "Mine = files\n")
        self.assertEqual(self.mynx("menu", "reset").stdout, "Menu: back to the built-in one\n")
        self.assertFalse(os.path.exists(mine))
        run = self.mynx("menu", "reset")
        self.assertEqual(run.returncode, 2)
        self.assertIn("already the built-in menu", run.stderr)

    def test_check_includes_problems_in_the_users_menu(self):
        # The menu checks its own file: menu --check FILE.
        self.write(os.path.join(self.tools, "bin", "menu"),
                   '#!/bin/sh\n[ "$1" = --check ] && echo "line 2: bad $2" && exit 1\n', 0o755)
        self.write(os.path.join(self.config, "menu.conf"), "x\n")
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.mynx("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/mynx/menu.conf",
            f"  line 2: bad {self.config}/menu.conf",
        ])

    # --- notify ------------------------------------------------------------

    def notify_app(self, shown=True):
        reply = {"ok": True, "shown": True} if shown else {"ok": True, "shown": False, "reason": "agent-notify is off"}
        self.start_app({"notify": lambda *args: reply})

    def test_notify_sends_title_text_and_tab(self):
        self.notify_app()
        run = self.mynx("notify", "Build done", "all", "tests", "pass", env={"MYNX_SHELL": "4"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Notification shown\n")
        self.assertEqual(self.app.requests, [["notify", "Build done", "all tests pass", "shell=4"]])

    def test_notify_if_away_and_without_text(self):
        self.notify_app()
        self.mynx("notify", "--if-away", "Hi")
        self.assertEqual(self.app.requests, [["notify", "Hi", "", "if-away"]])

    def test_notify_turns_line_breaks_into_spaces(self):
        self.notify_app()
        self.mynx("notify", "two\nlines", "and\nmore")
        self.assertEqual(self.app.requests, [["notify", "two lines", "and more"]])

    def test_notify_says_why_nothing_was_shown(self):
        self.notify_app(shown=False)
        run = self.mynx("notify", "Hi")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout, "Not shown: agent-notify is off\n")

    def test_notify_needs_a_title(self):
        run = self.mynx("notify")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx notify", run.stderr)

    # --- hook (agents' notification hooks) -----------------------------------

    def hook(self, event, env=None, **fields):
        return self.hook_as("claude", event, env, **fields)

    def hook_as(self, agent, event, env=None, **fields):
        data = {"hook_event_name": event, "session_id": "s1", "cwd": "/root/project", **fields}
        environ = {"TMPDIR": self.tmp.name, "MYNX_SHELL": "2", **(env or {})}
        run = subprocess.run(["python3", MYNX, "hook", agent], input=json.dumps(data),
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests,
                                  "HOME": self.home, "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1",
                                  **environ})
        return run

    def turn_started(self, seconds_ago, agent="claude"):
        folder = os.path.join(self.tmp.name, "mynx-agent-turns")
        os.makedirs(folder, exist_ok=True)
        self.write(os.path.join(folder, f"{agent}-s1"), f"{time.time() - seconds_ago}\n")

    def test_hook_records_when_a_turn_starts_without_asking_the_app(self):
        self.notify_app()
        run = self.hook("UserPromptSubmit", prompt="hi")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.tmp.name, "mynx-agent-turns", "claude-s1")))
        self.assertEqual(self.app.requests, [])

    def test_hook_at_the_end_of_a_turn_sends_how_long_it_took(self):
        self.notify_app()
        self.turn_started(seconds_ago=42)
        run = self.hook("Stop")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "")
        (request,) = self.app.requests
        self.assertEqual(request[:-1], ["notify", "Claude Code", "Your turn (project)", "shell=2", "if-away", "agent"])
        self.assertIn(request[-1], ["took=42", "took=43", "took=44"])  # a slow phone takes a moment
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "mynx-agent-turns", "claude-s1")))

    def test_hook_end_of_a_turn_it_didnt_see_start_is_ignored(self):
        self.notify_app()
        self.assertEqual(self.hook("Stop").returncode, 0)
        self.assertEqual(self.app.requests, [])

    def test_hook_passes_on_what_the_agent_needs(self):
        self.notify_app()
        self.hook("Notification", message="Claude needs your permission to use Bash")
        self.assertEqual(self.app.requests, [["notify", "Claude Code", "Claude needs your permission to use Bash",
                                              "shell=2", "if-away", "agent"]])

    def test_hook_never_fails_the_agent(self):
        # No app answering, and garbage input: still exit 0 and say nothing,
        # since an agent may treat a failing hook as a reason to stop or retry.
        self.turn_started(seconds_ago=99)
        self.assertEqual(self.hook("Stop").returncode, 0)
        run = subprocess.run(["python3", MYNX, "hook", "claude"], input="not json",
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "TMPDIR": self.tmp.name})
        self.assertEqual((run.returncode, run.stdout), (0, ""))

    def test_hook_for_an_unknown_agent(self):
        run = self.mynx("hook", "skynet")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx hook claude|codex|gemini", run.stderr)

    # --- agent -------------------------------------------------------------

    def fake_commands(self, **scripts):
        """Puts fake commands first on the PATH; returns that PATH."""
        bin_dir = os.path.join(self.tmp.name, "bin")
        for name, body in scripts.items():
            self.write(os.path.join(bin_dir, name), "#!/bin/sh\n" + body + "\n", 0o755)
        # Not the real PATH: the machine running the tests may have the real agents.
        return bin_dir + ":/usr/bin:/bin"

    def agent(self, *args, stdin="", path=None):
        environ = {"PATH": path or "/usr/bin:/bin", "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        return subprocess.run(["python3", MYNX, "agent", *args], input=stdin, capture_output=True,
                              text=True, env=environ)

    def claude_settings(self):
        with open(os.path.join(self.home, ".claude", "settings.json")) as f:
            return json.load(f)

    def test_agent_list_says_what_is_installed_and_notifying(self):
        path = self.fake_commands(claude="true")
        self.agent("notify", "claude", "on")
        run = self.agent("list", path=path)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.splitlines(), [
            "claude  Claude Code  installed      notifications on",
            "codex   Codex        not installed  notifications off",
            "gemini  Gemini CLI   not installed  notifications off",
        ])
        answer = json.loads(self.agent("list", "--json", path=path).stdout)
        self.assertEqual(answer["agents"][0],
                         {"name": "claude", "title": "Claude Code", "installed": True, "notify": True,
                          "config": "~/.claude/settings.json"})

    def test_agent_notify_on_and_off_edits_the_agents_config(self):
        self.write(os.path.join(self.home, ".claude", "settings.json"), '{"model": "opus"}\n')
        run = self.agent("notify", "claude", "on")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Claude Code notifications: on (hooks in ~/.claude/settings.json)\n")
        self.assertEqual(sorted(self.claude_settings()["hooks"]), ["Notification", "Stop", "UserPromptSubmit"])
        self.assertEqual(self.agent("notify", "claude", "off").stdout, "Claude Code notifications: off\n")
        self.assertEqual(self.claude_settings(), {"model": "opus"})

    def test_agent_notify_leaves_a_broken_config_alone(self):
        self.write(os.path.join(self.home, ".claude", "settings.json"), "{oops")
        run = self.agent("notify", "claude", "on")
        self.assertEqual(run.returncode, 2)
        self.assertIn("~/.claude/settings.json", run.stderr)
        with open(os.path.join(self.home, ".claude", "settings.json")) as f:
            self.assertEqual(f.read(), "{oops")

    def test_agent_install_shows_the_commands_and_asks_first(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "claude", stdin="n\n", path=path)
        self.assertIn("curl -fsSL https://claude.ai/install.sh | bash", run.stdout)
        self.assertNotIn("RAN-INSTALLER", run.stdout)
        self.assertEqual(run.returncode, 1)

    def test_agent_install_runs_the_installer_then_asks_about_notifications(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "claude", stdin="y\ny\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("RAN-INSTALLER", run.stdout)
        self.assertIn("notifications", run.stdout)
        self.assertIn("Stop", self.claude_settings()["hooks"])

    def test_agent_install_shows_the_silent_download_growing(self):
        installer = ('d=$HOME/.claude/downloads; mkdir -p $d; '
                     'head -c 2000000 /dev/zero > $d/claude; sleep 1; '
                     'head -c 5000000 /dev/zero > $d/claude; sleep 1; echo Setting up')
        path = self.fake_commands(curl=f"echo '{installer}'")
        run = self.agent("install", "claude", "--yes", "--no-notify", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Downloading Claude Code: 5 MB", run.stdout)
        self.assertIn("\x1b[KSetting up", run.stdout)  # text mode reads "\r" as "\n"

    def test_agent_install_without_questions(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "claude", "--yes", "--no-notify", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("RAN-INSTALLER", run.stdout)
        self.assertFalse(os.path.exists(os.path.join(self.home, ".claude", "settings.json")))
        self.agent("install", "claude", "--yes", "--notify", path=path)
        self.assertIn("Stop", self.claude_settings()["hooks"])

    def test_agent_install_stops_when_a_step_fails(self):
        path = self.fake_commands(curl="exit 7")
        run = self.agent("install", "claude", "--yes", "--notify", path=path)
        self.assertEqual(run.returncode, 2)
        self.assertIn("failed", run.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.home, ".claude", "settings.json")))

    def test_agent_install_without_a_name_lets_you_pick(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", stdin="1\ny\nn\n\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("1) Claude Code", run.stdout)
        self.assertIn("RAN-INSTALLER", run.stdout)

    def codex_config(self):
        try:
            with open(os.path.join(self.home, ".codex", "config.toml")) as f:
                return f.read()
        except FileNotFoundError:
            return None

    def test_codex_install_asks_to_turn_its_sandbox_off(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "codex", stdin="y\ny\nn\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Codex can only run commands here with its sandbox\nturned off.", run.stdout)
        self.assertLess(run.stdout.index("Turn Codex's sandbox off? [y/N]"), run.stdout.index("RAN-INSTALLER"))
        self.assertEqual(self.codex_config(), 'sandbox_mode = "danger-full-access"\n')
        self.assertIn("Sandbox: off", run.stdout)

    def test_codex_installer_runs_without_its_own_questions(self):
        # Its own "Start Codex now?" ran Codex inside the install step,
        # and quitting Codex failed the step before the sandbox was off.
        path = self.fake_commands(curl='echo "echo NON_INTERACTIVE=\\$CODEX_NON_INTERACTIVE"')
        run = self.agent("install", "codex", "--yes", "--no-notify", "--sandbox-off", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("NON_INTERACTIVE=1", run.stdout)

    def test_codex_install_with_its_sandbox_on_when_asked_twice(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "codex", stdin="n\ny\ny\nn\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Install Codex anyway, with its sandbox on?", run.stdout)
        self.assertIn("RAN-INSTALLER", run.stdout)
        self.assertIsNone(self.codex_config())
        self.assertIn("Sandbox: on", run.stdout)

    def test_codex_not_installed_when_both_answers_are_no(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "codex", stdin="n\nn\n", path=path)
        self.assertEqual(run.returncode, 1)
        self.assertNotIn("RAN-INSTALLER", run.stdout)
        self.assertIn("Not installed.", run.stdout)

    def test_codex_install_without_questions(self):
        path = self.fake_commands(curl='echo "echo RAN-INSTALLER"')
        run = self.agent("install", "codex", "--yes", "--no-notify", "--sandbox-off", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(self.codex_config(), 'sandbox_mode = "danger-full-access"\n')
        os.remove(os.path.join(self.home, ".codex", "config.toml"))
        run = self.agent("install", "codex", "--yes", "--no-notify", "--sandbox-on", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIsNone(self.codex_config())
        self.assertEqual(self.agent("install", "codex", "--sandbox-on", "--sandbox-off").returncode, 2)

    def test_agent_list_for_scripts(self):
        path = self.fake_commands(claude="true")
        self.assertEqual(self.agent("list", "--tsv", path=path).stdout,
                         "claude\tClaude Code\tinstalled\ncodex\tCodex\t\ngemini\tGemini CLI\t\n")

    def test_agent_start_runs_an_installed_agent(self):
        path = self.fake_commands(claude='echo "CLAUDE STARTED $*"')
        run = self.agent("start", "claude", path=path)
        self.assertEqual((run.returncode, run.stdout), (0, "CLAUDE STARTED \n"))

    def test_agent_start_shows_the_agents_key_bar_then_puts_the_old_one_back(self):
        bar_file = os.path.join(self.tmp.name, "keybar")
        self.write(bar_file, "menu")
        path = self.fake_commands(claude=f'echo "BAR $(cat {bar_file})"; exit 3')
        environ = {"PATH": path, "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1", "MYNX_KEYBAR_FILE": bar_file}
        run = subprocess.run(["python3", MYNX, "agent", "start", "claude"], capture_output=True,
                             text=True, env=environ)
        self.assertEqual((run.returncode, run.stdout), (3, "BAR claude,agent\n"))
        with open(bar_file) as f:
            self.assertEqual(f.read(), "menu")

    def test_agent_start_offers_to_install_a_missing_agent_then_starts_it(self):
        local_bin = os.path.join(self.home, ".local", "bin")
        installer = (f'mkdir -p {local_bin}; printf "#!/bin/sh\\necho CLAUDE STARTED\\n" >{local_bin}/claude; '
                     f'chmod +x {local_bin}/claude')
        path = self.fake_commands(curl=f"echo '{installer}'")
        run = self.agent("start", "claude", stdin="y\nn\n\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Claude Code isn't installed yet.", run.stdout)
        self.assertIn("Start Claude Code now? [Y/n]", run.stdout)
        self.assertTrue(run.stdout.endswith("CLAUDE STARTED\n"), run.stdout)

    def without_ps(self, path):
        """[path] with bash and python3, but no ps."""
        tools = os.path.join(self.tmp.name, "no-ps")
        os.makedirs(tools, exist_ok=True)
        for name in ("bash", "python3", "sh"):
            os.symlink(shutil.which(name), os.path.join(tools, name))
        return path.split(":")[0] + ":" + tools

    def test_agent_start_offers_what_an_installed_agent_still_needs(self):
        path = self.without_ps(self.fake_commands(codex='echo "CODEX STARTED"',
                                                  **{"apt-get": 'echo "APT $*"'}))
        run = self.agent("start", "codex", stdin="y\n", path=path)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Codex needs ps, which isn't installed", run.stdout)
        self.assertIn("APT install -y procps", run.stdout)
        self.assertTrue(run.stdout.endswith("CODEX STARTED\n"), run.stdout)

    def test_agent_start_when_you_decline_the_install(self):
        path = self.fake_commands(curl="echo true")
        run = self.agent("start", "claude", stdin="n\n", path=path)
        self.assertEqual(run.returncode, 1)
        self.assertIn("Not installed.", run.stdout)

    def test_agent_start_without_a_name_lets_you_pick(self):
        path = self.fake_commands(codex='echo "CODEX STARTED"')
        run = self.agent("start", stdin="2\n", path=path)
        self.assertIn("2) Codex", run.stdout)
        self.assertTrue(run.stdout.endswith("CODEX STARTED\n"), run.stdout)

    def test_agent_usage(self):
        for args in [("frob",), ("install", "skynet"), ("notify", "claude"), ("notify", "claude", "loud"),
                     ("start", "skynet")]:
            run = self.agent(*args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("mynx agent", run.stderr)

    def test_hook_for_codex_and_gemini(self):
        self.notify_app()
        self.hook_as("codex", "PermissionRequest", tool_name="Bash")
        self.hook_as("gemini", "Notification", message="Allow write_file?")
        self.turn_started(seconds_ago=50, agent="gemini")
        run = self.hook_as("gemini", "AfterAgent")
        self.assertEqual(run.stdout, "{}\n")  # Gemini CLI wants JSON on stdout
        self.assertEqual([r[1:3] for r in self.app.requests], [
            ["Codex", "Wants to run Bash"], ["Gemini CLI", "Allow write_file?"], ["Gemini CLI", "Your turn (project)"]])

    # --- undo --------------------------------------------------------------

    def test_undo_says_what_it_took_back(self):
        self.start_app({"undo": {"ok": True, "undone": "set font-size 16"}})
        run = self.mynx("undo")
        self.assertEqual((run.returncode, run.stdout), (0, "Undid: set font-size 16\n"))
        self.assertEqual(self.app.requests, [["undo"]])

    def test_undo_with_nothing_to_undo(self):
        self.start_app({"undo": {"ok": True, "undone": None}})
        run = self.mynx("undo")
        self.assertEqual((run.returncode, run.stdout), (1, "Nothing to undo\n"))

    def test_undo_list(self):
        noon = time.mktime((2026, 10, 4, 12, 5, 0, 0, 0, -1)) * 1000
        self.start_app({"undo-list": {"ok": True, "keep": 3, "steps": [
            {"reason": "theme set nord", "time": noon}, {"reason": "edits by hand", "time": noon}]}})
        run = self.mynx("undo", "--list")
        self.assertEqual(run.stdout.splitlines(), [
            "1. theme set nord  (12:05)", "2. edits by hand  (12:05)", "mynx undo takes back 1. (keeps 3: undo-keep)"])

    def test_undo_list_when_empty(self):
        self.start_app({"undo-list": {"ok": True, "keep": 1, "steps": []}})
        self.assertEqual(self.mynx("undo", "--list").stdout, "Nothing to undo (keeps 1: undo-keep)\n")

    def test_menu_edit_and_reset_are_recorded_for_undo(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.mynx("menu", "edit")
        self.mynx("menu", "reset")
        self.assertEqual(self.app.requests, [["check", "menu edit"], ["check", "menu reset"]])

    # --- report ------------------------------------------------------------

    PIXEL = {"ok": True, "android": "16", "sdk": 36, "maker": "Google", "model": "Pixel 10",
             "crash": "java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)",
             "crash_time": 1760000000000}

    # A gh that isn't signed in, so the real one (the machine running the
    # tests may have it) never files an issue.
    GH_SIGNED_OUT = 'echo "You are not logged into any GitHub hosts." >&2; exit 1'

    def fake_gh(self, login="est4s", create="ok"):
        """A signed-in gh that records `issue create` (its args and body)."""
        log = os.path.join(self.tmp.name, "gh")
        return f'''
case "$1 $2" in
  "api user") echo {login} ;;
  "issue create")
    printf '%s\\n' "$@" > {log}.args; cat > {log}.body
    {'echo https://github.com/est4s/mynx/issues/7' if create == "ok" else 'echo "HTTP 502: Bad Gateway" >&2; exit 1'} ;;
  *) exit 1 ;;
esac'''

    def gh_created(self):
        """The `issue create` gh got: its args and the body, or None."""
        log = os.path.join(self.tmp.name, "gh")
        if not os.path.exists(log + ".args"):
            return None
        with open(log + ".args") as a, open(log + ".body") as b:
            return a.read().split("\n")[:-1], b.read()

    def report(self, *args, info=None, input="", gh=None):
        self.write(os.path.join(self.tools, ".version"), "85-1760000000-0.1.0\n")
        self.start_app({"report-info": info or self.PIXEL, "open-url": {"ok": True}})
        path = self.fake_commands(gh=gh or self.GH_SIGNED_OUT)
        return self.mynx("report", *args, input=input, env={"TZ": "UTC", "PATH": path})

    def test_report_sends_it_with_gh_when_signed_in(self):
        run = self.report("The tab froze", input="y\n", gh=self.fake_gh())
        self.assertEqual(run.returncode, 0, run.stderr)
        args, body = self.gh_created()
        self.assertEqual(args, ["issue", "create", "--repo", "est4s/mynx", "--title", "The tab froze",
                                "--body-file", "-"])
        self.assertEqual(body,
                         "### What happened\n\nThe tab froze\n\n### App and phone\n\n```text\n"
                         "mynx 0.1.0 (build 85)\nAndroid 16 (SDK 36), Google Pixel 10\n\n"
                         "Last crash, 2025-10-09 08:53 UTC:\n"
                         "java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)\n```\n\n"
                         "<!-- mynx report -->\n")
        self.assertIn("Sent: https://github.com/est4s/mynx/issues/7", run.stdout)
        self.assertIsNone(self.opened())

    def test_report_with_gh_says_who_sends_it_and_asks(self):
        run = self.report("The tab froze", input="n\n", gh=self.fake_gh())
        text = " ".join(run.stdout.split())
        self.assertIn("This sends it as a new issue on GitHub (github.com/est4s/mynx) as @est4s", text)
        self.assertIn("Send it? [y/N]", text)
        self.assertIn("Nothing was sent.", text)
        self.assertIsNone(self.gh_created())
        self.assertIsNone(self.opened())

    def test_report_with_gh_keeps_a_long_crash_whole(self):
        crash = "java.lang.Error: deep\n" + "".join(f"\tat a.b.C{i}.method(C{i}.kt:{i})\n" for i in range(300))
        self.report("x", info=dict(self.PIXEL, crash=crash), input="y\n", gh=self.fake_gh())
        body = self.gh_created()[1]
        self.assertIn("C299.method", body)
        self.assertNotIn("cut to fit", body)

    def test_report_offers_the_browser_when_gh_fails(self):
        run = self.report("The tab froze", input="y\ny\n", gh=self.fake_gh(create="fail"))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("HTTP 502", run.stdout + run.stderr)
        self.assertIn("Open it in the browser instead? [y/N]", run.stdout)
        self.assertEqual(self.opened()["title"], "The tab froze")

    def test_report_json_sends_nothing_with_gh(self):
        run = self.report("The tab froze", "--json", gh=self.fake_gh())
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIsNone(self.gh_created())

    def opened(self):
        urls = [r[1] for r in self.app.requests if r[0] == "open-url"]
        if not urls:
            return None
        self.assertEqual(len(urls), 1)
        url = urllib.parse.urlsplit(urls[0])
        self.assertEqual(f"{url.scheme}://{url.netloc}{url.path}", "https://github.com/est4s/mynx/issues/new")
        return dict(urllib.parse.parse_qsl(url.query))

    def test_report_shows_it_all_then_opens_github(self):
        run = self.report("The tab froze", input="y\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        for text in ("The tab froze", "mynx 0.1.0 (build 85)", "Android 16 (SDK 36)",
                     "Google Pixel 10", "Last crash, 2025-10-09 08:53",
                     "IllegalStateException: boom", "\tat X.y(X.kt:1)"):
            self.assertIn(text, run.stdout)
        self.assertEqual(self.opened(), {
            "template": "app-report.yml", "title": "The tab froze", "what": "The tab froze",
            "details": "mynx 0.1.0 (build 85)\nAndroid 16 (SDK 36), Google Pixel 10\n\n"
                       "Last crash, 2025-10-09 08:53 UTC:\n"
                       "java.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)"})

    def test_report_asks_before_anything_leaves_the_phone(self):
        run = self.report("The tab froze", input="n\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("[y/N]", run.stdout)
        self.assertIn("Nothing was sent.", run.stdout)
        self.assertIsNone(self.opened())

    def test_report_with_no_answer_sends_nothing(self):
        run = self.report("The tab froze", input="")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIsNone(self.opened())

    def test_report_says_where_it_goes(self):
        text = " ".join(self.report("x", input="n\n").stdout.split())
        self.assertIn("new issue on GitHub (github.com/est4s/mynx)", text)
        self.assertIn("Nothing is sent until you submit it there, with your own GitHub account", text)

    def test_report_asks_what_went_wrong(self):
        run = self.report(input="The tab froze\nwhen I turned the phone\n\ny\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("What went wrong?", run.stdout)
        found = self.opened()
        self.assertEqual(found["what"], "The tab froze\nwhen I turned the phone")
        self.assertEqual(found["title"], "The tab froze")

    def test_report_needs_something_to_say(self):
        run = self.report(input="\n")
        self.assertEqual(run.returncode, 2)
        self.assertIn("nothing to report", run.stderr)
        self.assertIsNone(self.opened())

    def test_report_title_is_short(self):
        run = self.report("word " * 30, input="y\n")
        title = self.opened()["title"]
        self.assertLessEqual(len(title), 60)
        self.assertTrue(title.endswith("…"))

    def test_report_with_no_crash(self):
        run = self.report("x", info=dict(self.PIXEL, crash=None, crash_time=None), input="y\n")
        self.assertNotIn("crash", run.stdout.lower())
        self.assertEqual(self.opened()["details"], "mynx 0.1.0 (build 85)\nAndroid 16 (SDK 36), Google Pixel 10")

    def test_report_can_leave_the_crash_out(self):
        run = self.report("x", "--no-crash", input="y\n")
        self.assertNotIn("boom", run.stdout)
        self.assertNotIn("crash", self.opened()["details"].lower())

    def test_report_names_the_phone_once(self):
        info = dict(self.PIXEL, maker="samsung", model="samsung SM-S921B", crash=None)
        self.report("x", info=info, input="y\n")
        self.assertIn("Android 16 (SDK 36), samsung SM-S921B\n", self.opened()["details"] + "\n")

    def test_report_cuts_a_long_crash_to_fit_the_link(self):
        crash = "java.lang.Error: deep\n" + "".join(f"\tat a.b.C{i}.method(C{i}.kt:{i})\n" for i in range(300))
        run = self.report("x", info=dict(self.PIXEL, crash=crash), input="y\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        url = [r[1] for r in self.app.requests if r[0] == "open-url"][0]
        self.assertLessEqual(len(url), 8000)
        details = self.opened()["details"]
        self.assertIn("java.lang.Error: deep\n\tat a.b.C0.method", details)
        self.assertTrue(details.endswith("(cut to fit the link)"), details[-80:])

    def test_report_json_gives_the_report_and_sends_nothing(self):
        run = self.report("The tab froze", "--json")
        self.assertEqual(run.returncode, 0, run.stderr)
        answer = json.loads(run.stdout)
        self.assertEqual(answer["title"], "The tab froze")
        self.assertIn("IllegalStateException", answer["details"])
        self.assertTrue(answer["url"].startswith("https://github.com/est4s/mynx/issues/new?template=app-report.yml"))
        self.assertIsNone(self.opened())

    def test_report_json_needs_the_text(self):
        run = self.report("--json")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx report", json.loads(run.stdout)["error"])

    # --- github --------------------------------------------------------------

    def fake_github(self, signed_in=None, installed=True, login_fails=False, name="Mona Lisa"):
        """A PATH with bash and fake gh and git (git runs the real one, so
        `git config --global` writes $HOME/.gitconfig). Not installed, an
        apt-get that installs them. gh's account is in tmp/gh-login."""
        state = os.path.join(self.tmp.name, "gh-login")
        if signed_in:
            self.write(state, signed_in + "\n")
        user = ('{"id": 42, "login": "%s", "name": ' + (f'"{name}"' if name else "null") + '}')
        gh = f'''
state={state}
case "$1 $2" in
  "api user")
    [ -s "$state" ] || {{ echo "You are not logged into any GitHub hosts." >&2; exit 1; }}
    read login < "$state"
    if [ "$3" = --jq ]; then echo "$login"; else printf '{user}\\n' "$login"; fi ;;
  "auth login")
    echo "$@" > {state}.args
    printf '\\033[0;33m!\\033[0m First copy your one-time code: \\033[0;1;39mAB12-CD34\\033[0m\\r\\n'
    printf 'Press Enter to open github.com in your browser... '
    read enter
    {'exit 1' if login_fails else 'echo octocat > "$state"; echo "Logged in as octocat"'} ;;
  "auth setup-git") echo done > {state}.setup-git ;;
  *) exit 1 ;;
esac'''
        git = 'exec /usr/bin/git "$@"'
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir, exist_ok=True)
        for tool in ("bash", "python3"):
            with contextlib.suppress(FileExistsError):
                os.symlink(shutil.which(tool), os.path.join(bin_dir, tool))
        if installed:
            self.fake_commands(gh=gh, git=git)
        else:
            self.write(os.path.join(self.tmp.name, "gh.src"), "#!/bin/sh\n" + gh + "\n")
            self.write(os.path.join(self.tmp.name, "git.src"), "#!/bin/sh\n" + git + "\n")
            self.fake_commands(**{"apt-get": f'''
echo "ran apt-get $*"
[ "$1" = install ] || exit 0
while read -r line; do echo "$line"; done < {self.tmp.name}/gh.src > {bin_dir}/gh
while read -r line; do echo "$line"; done < {self.tmp.name}/git.src > {bin_dir}/git
{shutil.which("chmod")} +x {bin_dir}/gh {bin_dir}/git'''})
        return bin_dir

    def github(self, *args, input="", path=None):
        if not self.app:
            self.start_app({"clipboard-set": {"ok": True}})
        return self.mynx("github", *args, input=input, env={"PATH": path or self.fake_github()})

    def git_config(self, key):
        run = subprocess.run(["git", "config", "--global", key], capture_output=True, text=True,
                             env={"HOME": self.home, "PATH": "/usr/bin:/bin"})
        return run.stdout.strip() or None

    def gh_state(self, name):
        path = os.path.join(self.tmp.name, "gh-login" + name)
        if not os.path.exists(path):
            return None
        with open(path) as f:
            return f.read().strip()

    def test_github_signs_in_copies_the_code_and_sets_up_git(self):
        run = self.github(input="\n")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(self.app.requests, [["clipboard-set", "AB12-CD34"]])
        self.assertIn("AB12-CD34", run.stdout)
        self.assertIn("copied", run.stdout)
        self.assertEqual(self.gh_state(".args"),
                         "auth login --web --hostname github.com --git-protocol https")
        self.assertEqual(self.git_config("user.name"), "Mona Lisa")
        self.assertEqual(self.git_config("user.email"), "42+octocat@users.noreply.github.com")
        self.assertEqual(self.gh_state(".setup-git"), "done")
        summary = run.stdout.split("Logged in as octocat")[1]
        self.assertIn("GitHub     signed in as @octocat", summary)
        self.assertIn("git name   Mona Lisa", summary)
        self.assertIn("git email  42+octocat@users.noreply.github.com", summary)

    def test_github_points_git_at_gh_before_signing_in(self):
        # Then gh doesn't ask "Authenticate Git with your GitHub credentials?"
        self.github(input="\n")
        run = subprocess.run(["git", "config", "--global", "--get-all", "credential.https://github.com.helper"],
                             capture_output=True, text=True, env={"HOME": self.home, "PATH": "/usr/bin:/bin"})
        self.assertEqual(run.stdout, "\n!gh auth git-credential\n")

    def test_github_names_git_after_the_login_when_the_account_has_no_name(self):
        run = self.github(input="\n", path=self.fake_github(name=None))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(self.git_config("user.name"), "octocat")

    def test_github_signed_in_asks_to_switch_and_keeps_the_account(self):
        run = self.github(input="n\n", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("Signed in to GitHub as @est4s. Switch to another account? [y/N]", run.stdout)
        self.assertIsNone(self.gh_state(".args"))
        self.assertEqual(self.app.seen, [])
        self.assertEqual(self.git_config("user.email"), "42+est4s@users.noreply.github.com")
        self.assertIn("signed in as @est4s", run.stdout)

    def test_github_signed_in_switches_accounts(self):
        run = self.github(input="y\n\n", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIsNotNone(self.gh_state(".args"))
        self.assertEqual(self.git_config("user.email"), "42+octocat@users.noreply.github.com")

    def test_github_asks_before_replacing_git_identity(self):
        self.write(os.path.join(self.home, ".gitconfig"), "[user]\n\tname = Me\n\temail = me@example.com\n")
        run = self.github(input="n\nn\n", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        text = " ".join(run.stdout.split())
        self.assertIn("git commits are signed Me <me@example.com>", text)
        self.assertIn("Change them to Mona Lisa <42+est4s@users.noreply.github.com>? [y/N]", text)
        self.assertEqual(self.git_config("user.email"), "me@example.com")
        self.assertIn("git email  me@example.com", run.stdout)

    def test_github_replaces_git_identity_when_asked(self):
        self.write(os.path.join(self.home, ".gitconfig"), "[user]\n\tname = Me\n\temail = me@example.com\n")
        self.github(input="n\ny\n", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(self.git_config("user.name"), "Mona Lisa")
        self.assertEqual(self.git_config("user.email"), "42+est4s@users.noreply.github.com")

    def test_github_doesnt_ask_when_git_identity_already_matches(self):
        self.write(os.path.join(self.home, ".gitconfig"),
                   "[user]\n\tname = Mona Lisa\n\temail = 42+est4s@users.noreply.github.com\n")
        run = self.github(input="n\n", path=self.fake_github(signed_in="est4s"))
        self.assertNotIn("Change them", run.stdout)

    def test_github_installs_git_and_gh_asking_first(self):
        run = self.github(input="y\n\n", path=self.fake_github(installed=False))
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        before = run.stdout.split("Install them? [y/N]")[0]
        self.assertIn("apt-get install -y git gh", before)
        self.assertNotIn("ran apt-get", before)
        self.assertIn("ran apt-get update", run.stdout)
        self.assertIn("ran apt-get install -y git gh", run.stdout)
        self.assertEqual(self.git_config("user.name"), "Mona Lisa")

    def test_github_installs_nothing_when_told_no(self):
        run = self.github(input="n\n", path=self.fake_github(installed=False))
        self.assertEqual(run.returncode, 1)
        self.assertNotIn("ran apt-get", run.stdout)
        self.assertIn("Nothing was installed.", run.stdout)

    def test_github_stops_when_signing_in_fails(self):
        run = self.github(input="\n", path=self.fake_github(login_fails=True))
        self.assertEqual(run.returncode, 2)
        self.assertIn("signing in to GitHub didn't work", run.stderr)
        self.assertIsNone(self.git_config("user.name"))

    def test_github_status(self):
        self.write(os.path.join(self.home, ".gitconfig"), "[user]\n\tname = Me\n")
        run = self.github("status", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "GitHub     signed in as @est4s\ngit name   Me\ngit email  (not set)\n")
        self.assertIsNone(self.gh_state(".args"))

    def test_github_status_without_gh(self):
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir)
        os.symlink(shutil.which("python3"), os.path.join(bin_dir, "python3"))
        run = self.github("status", path=bin_dir)
        self.assertEqual(run.stdout, "GitHub     gh isn't installed (mynx github sets it up)\n"
                                     "git name   (git isn't installed)\ngit email  (git isn't installed)\n")

    def test_github_status_json(self):
        self.write(os.path.join(self.home, ".gitconfig"), "[user]\n\tname = Me\n")
        run = self.github("status", "--json", path=self.fake_github(signed_in="est4s"))
        self.assertEqual(json.loads(run.stdout), {"ok": True, "gh": True, "git": True, "login": "est4s",
                                                  "name": "Me", "email": None})

    def test_github_setup_refuses_json_and_unknown_words(self):
        path = self.fake_github()
        for args in (["--json"], ["now"]):
            run = self.github(*args, path=path)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("mynx github [status] [--yes]", run.stdout + run.stderr)

    # --- open --------------------------------------------------------------

    def test_open_hands_a_link_to_the_app(self):
        self.start_app({"open-url": {"ok": True}})
        run = self.mynx("open", "https://claude.ai/login")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["open-url", "https://claude.ai/login"]])

    def test_open_says_why_it_failed(self):
        self.start_app({"open-url": {"ok": False, "error": "only http and https links open: x"}})
        run = self.mynx("open", "x")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only http and https", run.stderr)

    def test_open_needs_one_link(self):
        self.assertIn("usage: mynx open URL", self.mynx("open").stderr)

    # --- update ---------------------------------------------------------------

    AVAILABLE = {"ok": True, "current": "0.1.0", "latest": "0.2.0", "available": True, "tag": "v0.2.0",
                 "notes": "## What's new\n- Updates", "size": 2621440, "published": 1791462600000}
    INSTALLED = {"ok": True, "_lines": [{"bytes": 0, "total": 2621440}, {"bytes": 2621440, "total": 2621440}],
                 "version": "0.2.0", "bytes": 2621440}

    def test_update_when_up_to_date(self):
        for latest in ("0.1.0", None):
            self.start_app({"update-check": {"ok": True, "current": "0.1.0", "latest": latest, "available": False}})
            run = self.mynx("update")
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(run.stdout, "mynx 0.1.0 is up to date.\n")
            self.assertEqual(self.app.seen, ["update-check"])
            self.app.stop.set()
            self.app.thread.join()

    def test_update_check_only_says_what_is_available(self):
        self.start_app({"update-check": self.AVAILABLE})
        run = self.mynx("update", "--check")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "mynx 0.2.0 is available (you have 0.1.0).\n")
        self.assertEqual(self.app.seen, ["update-check"])

    def test_update_shows_the_notes_and_asks(self):
        self.start_app({"update-check": self.AVAILABLE, "update-install": self.INSTALLED})
        run = self.mynx("update", input="n\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("mynx 0.2.0 is available (you have 0.1.0).", run.stdout)
        self.assertIn("## What's new\n- Updates", run.stdout)
        self.assertIn("2.5 MB", run.stdout)
        self.assertIn("Download and install it? [y/N]", run.stdout)
        self.assertIn("Nothing was changed.", run.stdout)
        self.assertEqual(self.app.seen, ["update-check"])

    def test_update_downloads_and_opens_the_installer(self):
        self.start_app({"update-check": self.AVAILABLE, "update-install": self.INSTALLED})
        run = self.mynx("update", input="y\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["update-check"], ["update-install", "0.2.0"]])
        self.assertIn("Downloading", run.stderr)
        self.assertIn("Android's installer is open: tap Update.", run.stdout)

    def test_update_yes_skips_the_question(self):
        self.start_app({"update-check": self.AVAILABLE, "update-install": self.INSTALLED})
        run = self.mynx("update", "--yes")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertNotIn("[y/N]", run.stdout)
        self.assertEqual(self.app.seen, ["update-check", "update-install"])

    def test_update_json(self):
        self.start_app({"update-check": self.AVAILABLE, "update-install": self.INSTALLED})
        run = self.mynx("update", "--check", "--json")
        self.assertEqual(json.loads(run.stdout), self.AVAILABLE)
        run = self.mynx("update", "--yes", "--json")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "version": "0.2.0", "bytes": 2621440})
        run = self.mynx("update", "--json")
        self.assertEqual(run.returncode, 2)
        self.assertIn("--json needs --check or --yes", json.loads(run.stdout)["error"])

    def test_update_says_what_went_wrong(self):
        self.start_app({"update-check": self.AVAILABLE,
                        "update-install": {"ok": False, "error": "the download failed: sha256 doesn't match"}})
        run = self.mynx("update", "--yes")
        self.assertEqual(run.returncode, 2)
        self.assertIn("mynx: the download failed: sha256 doesn't match", run.stderr)

    def test_update_usage(self):
        for args in [("now",), ("--fast",)]:
            run = self.mynx("update", *args)
            self.assertEqual(run.returncode, 2)
            self.assertIn("usage: mynx update [--check] [--yes]", run.stderr)

    # --- install-apk (debug builds) ------------------------------------------

    def test_install_apk_sends_the_full_path(self):
        self.start_app({"install-apk": {"ok": True}})
        run = self.mynx("install-apk", "app.apk", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Installer opened on the phone.\n")
        self.assertEqual(self.app.requests, [["install-apk", os.path.join(self.tmp.name, "app.apk")]])

    # --- vibrate and clipboard --------------------------------------------

    def test_vibrate_for_the_default_or_a_given_time(self):
        self.start_app({"vibrate": {"ok": True}})
        self.assertEqual(self.mynx("vibrate").returncode, 0)
        run = self.mynx("vibrate", "50")
        self.assertEqual((run.returncode, run.stdout), (0, ""))
        self.assertEqual(self.app.requests, [["vibrate"], ["vibrate", "50"]])

    def test_vibrate_takes_one_time_at_most(self):
        self.assertIn("usage: mynx vibrate [MS]", self.mynx("vibrate", "1", "2").stderr)

    def test_clipboard_get_prints_the_text_as_it_is(self):
        self.start_app({"clipboard-get": {"ok": True, "text": "two\nlines"}})
        run = self.mynx("clipboard", "get")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "two\nlines")
        self.assertEqual(self.mynx("clipboard", "get", "--json").stdout, '{"ok": true, "text": "two\\nlines"}\n')

    def test_clipboard_get_says_why_it_couldnt(self):
        self.start_app({"clipboard-get": {"ok": False, "error": "the app must be on screen to read the clipboard"}})
        run = self.mynx("clipboard", "get")
        self.assertEqual(run.returncode, 2)
        self.assertIn("the app must be on screen", run.stderr)

    def test_clipboard_set_sends_the_words_encoded(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.mynx("clipboard", "set", "50%", "off", "+", "é")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Copied 11 characters\n")
        self.assertEqual(self.app.requests, [["clipboard-set", "50%25%20off%20%2B%20%C3%A9"]])

    def test_clipboard_set_reads_stdin_line_breaks_and_all(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.mynx("clipboard", "set", input="a\nb\r\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["clipboard-set", "a%0Ab%0D%0A"]])

    def test_clipboard_needs_get_or_set(self):
        for args in [("clipboard",), ("clipboard", "paste"), ("clipboard", "get", "x")]:
            run = self.mynx(*args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: mynx clipboard get | set [TEXT]", run.stderr)

    # --- location -----------------------------------------------------------

    FIX = {"latitude": 60.1695213, "longitude": 24.9354471, "accuracy": 12.3, "altitude": 21, "speed": None,
           "bearing": None, "provider": "network", "time": 1791177403145}

    def test_location_prints_one_fix(self):
        self.start_app({"location": {"ok": True, "location": self.FIX}})
        run = self.mynx("location", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n")
        self.assertEqual(self.app.requests, [["location"]])
        run = self.mynx("location", "--json")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "location": self.FIX})

    def test_location_leaves_out_an_unknown_accuracy(self):
        self.start_app({"location": {"ok": True, "location": dict(self.FIX, accuracy=None)}})
        self.assertEqual(self.mynx("location", env={"TZ": "UTC"}).stdout, "60.1695213, 24.9354471 network 05:16:43\n")

    def test_location_options(self):
        self.start_app({"location": {"ok": True, "location": self.FIX},
                        "location-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.mynx("location", "--gps", "--timeout", "120").returncode, 0)
        self.assertEqual(self.mynx("location", "--stream", "--every", "2", "--gps").returncode, 0)
        self.assertEqual(self.app.requests, [["location", "gps", "timeout=120"],
                                             ["location-stream", "interval=2", "gps"]])

    def test_location_stream_prints_a_line_per_fix(self):
        second = dict(self.FIX, provider="gps", time=1791177408145)
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX, second]}})
        run = self.mynx("location", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n"
                                     "60.1695213, 24.9354471 ±12 m gps 05:16:48\n")
        run = self.mynx("location", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.FIX, second])

    def test_location_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", MYNX, "location", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("network", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_location_says_why_there_is_no_fix(self):
        self.start_app({"location": {"ok": False, "error": "no fix within 60 s"}})
        run = self.mynx("location")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "mynx: no fix within 60 s\n")

    def test_location_usage(self):
        usage = "usage: mynx location [--gps] [--timeout SECONDS] | --stream [--every SECONDS] [--gps]"
        for args in [("--fast",), ("--timeout",), ("--every", "2"), ("--stream", "--timeout", "5"), ("x",)]:
            run = self.mynx("location", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- sensor -------------------------------------------------------------

    READING = {"sensor": "accelerometer", "values": {"x": 0.1235, "y": 9.8067, "z": -0.5}, "unit": "m/s²",
               "accuracy": "high", "time": 1791177403145}
    LIGHT = {"sensor": "light", "values": {"illuminance": 108}, "unit": "lx", "accuracy": "medium",
             "time": 1791177403245}

    def test_sensor_list(self):
        sensors = [{"name": "accelerometer", "values": ["x", "y", "z"], "unit": "m/s²"},
                   {"name": "rotation-vector", "values": ["x", "y", "z", "w"], "unit": ""},
                   {"name": "light", "values": ["illuminance"], "unit": "lx"},
                   {"name": "magnetic-field-uncalibrated", "values": ["x", "bias-x"], "unit": "µT"}]
        self.start_app({"sensor-list": {"ok": True, "sensors": sensors}})
        run = self.mynx("sensor", "list")
        self.assertEqual(run.returncode, 0, run.stderr)
        # Values line up under the longest name.
        self.assertEqual(run.stdout, "accelerometer               x y z m/s²\n"
                                     "rotation-vector             x y z w\n"
                                     "light                       illuminance lx\n"
                                     "magnetic-field-uncalibrated x bias-x µT\n")
        self.assertEqual(self.app.requests, [["sensor-list"]])
        self.assertEqual(json.loads(self.mynx("sensor", "list", "--json").stdout), {"ok": True, "sensors": sensors})

    def test_sensor_prints_one_reading(self):
        self.start_app({"sensor": lambda name, *rest: {"ok": True, "reading": self.READING if name == "accelerometer"
                                                       else self.LIGHT}})
        run = self.mynx("sensor", "accelerometer")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "x=0.1235 y=9.8067 z=-0.5 m/s²\n")
        self.assertEqual(self.mynx("sensor", "light").stdout, "108 lx\n")
        self.assertEqual(json.loads(self.mynx("sensor", "accelerometer", "--json").stdout),
                         {"ok": True, "reading": self.READING})
        self.assertEqual(self.app.requests[0], ["sensor", "accelerometer"])

    def test_sensor_options(self):
        self.start_app({"sensor": {"ok": True, "reading": self.LIGHT},
                        "sensor-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.mynx("sensor", "light", "--timeout", "30").returncode, 0)
        self.assertEqual(self.mynx("sensor", "--stream", "gyroscope", "--rate", "50").returncode, 0)
        self.assertEqual(self.app.requests, [["sensor", "light", "timeout=30"],
                                             ["sensor-stream", "gyroscope", "rate=50"]])

    def test_sensor_stream_prints_a_line_per_reading(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING, self.LIGHT]}})
        run = self.mynx("sensor", "accelerometer", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "05:16:43.145 x=0.1235 y=9.8067 z=-0.5 m/s²\n"
                                     "05:16:43.245 108 lx\n")
        run = self.mynx("sensor", "accelerometer", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.READING, self.LIGHT])

    def test_sensor_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", MYNX, "sensor", "accelerometer", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("m/s²", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_sensor_says_why_there_is_no_reading(self):
        self.start_app({"sensor": {"ok": False, "error": "the phone has no pressure sensor"}})
        run = self.mynx("sensor", "pressure")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "mynx: the phone has no pressure sensor\n")

    def test_sensor_usage(self):
        usage = "usage: mynx sensor list | NAME [--timeout SECONDS] | NAME --stream [--rate HZ]"
        for args in [(), ("--stream",), ("light", "--fast"), ("light", "--timeout"), ("light", "--rate", "5"),
                     ("light", "--stream", "--timeout", "5"), ("light", "dark"), ("list", "x")]:
            run = self.mynx("sensor", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- camera and torch ---------------------------------------------------

    def test_camera_saves_to_the_files_full_path(self):
        self.start_app({"camera": {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000}})
        run = self.mynx("camera", "a.jpg", cwd="/srv")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (2.3 MB)\n")
        self.assertEqual(self.app.requests, [["camera", "/srv/a.jpg"]])
        self.assertEqual(json.loads(self.mynx("camera", "/srv/a.jpg", "--json").stdout),
                         {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000})

    def test_camera_quick_from_the_front_or_back(self):
        self.start_app({"camera-quick": {"ok": True, "file": "/srv/a.jpg", "bytes": 900_000}})
        run = self.mynx("camera", "--quick", "front", "/srv/a.jpg")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (879 KB)\n")
        self.assertEqual(self.mynx("camera", "/srv/a.jpg", "--quick", "back").returncode, 0)
        self.assertEqual(self.app.requests, [["camera-quick", "/srv/a.jpg", "front"],
                                             ["camera-quick", "/srv/a.jpg", "back"]])

    def test_camera_says_why_there_is_no_photo(self):
        self.start_app({"camera": {"ok": False, "error": "no photo was taken"}})
        run = self.mynx("camera", "/srv/a.jpg")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "mynx: no photo was taken\n")

    def test_camera_stops_quietly_on_ctrl_c(self):
        self.start_app({"camera-quick": {"ok": True, "_lines": [], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", MYNX, "camera", "--quick", "back", "/srv/a.jpg"], env=environ,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        while not self.app.requests:
            time.sleep(0.01)
        time.sleep(0.2)
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, out, err), (130, "", ""))
        self.assertTrue(self.app.cancelled)

    def test_camera_usage(self):
        usage = "usage: mynx camera FILE [--quick front|back]"
        for args in [(), ("a.jpg", "b.jpg"), ("a.jpg", "--quick"), ("a.jpg", "--fast"), ("--quick", "back")]:
            run = self.mynx("camera", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    def test_torch_on_at_a_strength_and_off(self):
        self.start_app({"torch": {"ok": True}})
        for args in [("on",), ("on", "40"), ("off",)]:
            run = self.mynx("torch", *args)
            self.assertEqual((run.returncode, run.stdout), (0, ""), run.stderr)
        self.assertEqual(self.app.requests, [["torch", "on"], ["torch", "on", "40"], ["torch", "off"]])

    def test_torch_usage(self):
        for args in [(), ("bright",), ("off", "5"), ("on", "5", "6")]:
            run = self.mynx("torch", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: mynx torch on [PERCENT] | off", run.stderr, args)

    # --- rotation -----------------------------------------------------------

    def test_rotation_lock_is_held_by_the_program_that_runs_mynx(self):
        self.start_app({"rotation": {"ok": True, "locked": "current"}})
        run = self.mynx("rotation", "lock")
        self.assertEqual((run.returncode, run.stdout), (0, "Rotation: locked as it is\n"), run.stderr)
        self.assertEqual(self.app.requests, [["rotation", "lock", str(os.getpid())]])

    def test_rotation_lock_to_a_side_or_for_another_process(self):
        self.start_app({"rotation": {"ok": True, "locked": "landscape"}})
        run = self.mynx("rotation", "lock", "landscape", "--pid", "77")
        self.assertEqual((run.returncode, run.stdout), (0, "Rotation: locked to landscape\n"), run.stderr)
        self.assertEqual(self.app.requests, [["rotation", "lock", "77", "landscape"]])

    def test_rotation_unlock_and_status(self):
        self.start_app({"rotation": {"ok": True, "locked": "no"}})
        self.assertEqual(self.mynx("rotation", "unlock").stdout, "Rotation: free\n")
        self.assertEqual(self.mynx("rotation").stdout, "Rotation: free\n")
        run = self.mynx("--json", "rotation", "status")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "locked": "no"})
        self.assertEqual(self.app.requests, [["rotation", "unlock"], ["rotation", "status"], ["rotation", "status"]])

    def test_rotation_usage(self):
        for args in [("spin",), ("lock", "sideways"), ("lock", "--pid"), ("lock", "--pid", "x"), ("unlock", "now")]:
            run = self.mynx("rotation", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: mynx rotation [status] | lock [portrait|landscape] [--pid PID] | unlock",
                          run.stderr, args)

    # --- sound --------------------------------------------------------------

    SETTINGS_ON = {"ok": True, "problems": [], "settings": [
        {"key": "sound-device", "value": "on", "default": "on", "choices": ["on", "off"], "description": ""}]}

    def sound(self, *args, stdin="", commands=None, settings=None, restart=0):
        """`mynx sound` with only fake commands on the PATH (this machine
        may have the real PulseAudio), and the server's socket in the test's
        folder. `restart`: seconds after sound-start until sound-server
        writes its new pid (None: never)."""
        bin_dir = os.path.join(self.tmp.name, "bin")
        os.makedirs(bin_dir, exist_ok=True)
        os.symlink(shutil.which("bash"), os.path.join(bin_dir, "bash"))
        for name, body in (commands or {}).items():
            self.write(os.path.join(bin_dir, name), "#!" + shutil.which("sh") + "\n" + body + "\n", 0o755)
        self.socket_path = os.path.join(self.tmp.name, "native")
        def start():
            if restart is not None:
                timer = threading.Timer(restart, lambda: self.write(os.path.join(self.tmp.name, "pid"), "222\n"))
                timer.start()
                self.addCleanup(timer.cancel)
            return {"ok": True}
        self.start_app({"settings": settings or self.SETTINGS_ON, "sound-start": start})
        environ = {"PATH": bin_dir, "MYNX_REQUESTS": self.requests, "HOME": self.home,
                   "MYNX_TOOLS": self.tools, "MYNX_TIMEOUT": "1", "PULSE_SERVER": "unix:" + self.socket_path}
        return subprocess.run([sys.executable, MYNX, "sound", *args], input=stdin, capture_output=True,
                              text=True, env=environ)

    def listen(self):
        server = socket.socket(socket.AF_UNIX)
        server.bind(os.path.join(self.tmp.name, "native"))
        server.listen()
        self.addCleanup(server.close)

    def test_sound_says_the_device_is_on_when_the_server_answers(self):
        self.listen()
        run = self.sound(commands={"pulseaudio": "true"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sound device: on. Programs play through the phone's speaker.\n")

    def test_sound_status_as_json(self):
        run = self.sound("status", "--json", commands={"pulseaudio": "true"})
        self.assertEqual(json.loads(run.stdout), {
            "ok": True, "setting": "on", "installed": True, "running": False,
            "server": "unix:" + self.socket_path})

    def test_sound_says_when_the_server_isnt_running(self):
        run = self.sound(commands={"pulseaudio": "true"})
        self.assertEqual(run.stdout, "Sound device: not running (mynx sound start starts it)\n")

    def test_sound_says_when_pulseaudio_isnt_installed(self):
        run = self.sound()
        self.assertEqual(run.stdout, "Sound device: not installed (mynx sound install installs it)\n")

    def test_sound_says_when_the_setting_is_off(self):
        off = json.loads(json.dumps(self.SETTINGS_ON))
        off["settings"][0]["value"] = "off"
        run = self.sound(commands={"pulseaudio": "true"}, settings=off)
        self.assertEqual(run.stdout, "Sound device: off (mynx set sound-device on turns it on)\n")

    def test_sound_start_asks_the_app(self):
        self.listen()
        run = self.sound("start")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.seen, ["sound-start"])

    def test_sound_start_waits_for_the_new_server_not_the_old_one(self):
        # The old Pulse answers until it has exited; its pid is in the pid file.
        self.write(os.path.join(self.tmp.name, "pid"), "111\n")
        self.listen()
        started = time.monotonic()
        run = self.sound("start", restart=1)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertGreaterEqual(time.monotonic() - started, 1)
        self.assertEqual(run.stdout, "Sound device: started\n")

    def test_sound_start_says_so_when_the_server_doesnt_come_up(self):
        self.write(os.path.join(self.tmp.name, "pid"), "111\n")
        run = self.sound("start", restart=None)
        self.assertEqual(run.returncode, 2)
        self.assertIn("server.log", run.stderr)

    def test_sound_install_shows_the_commands_and_asks_first(self):
        run = self.sound("install", stdin="n\n", commands={"apt-get": "echo apt-get $*"})
        self.assertIn("apt-get install -y --no-install-recommends pulseaudio", run.stdout)
        self.assertNotIn("apt-get update", run.stdout.split("Run it?")[1])
        self.assertEqual(run.returncode, 1)
        self.assertEqual(self.app.seen, [])

    def test_sound_install_installs_then_starts_the_device(self):
        self.listen()
        run = self.sound("install", "--yes", commands={"apt-get": "echo ran apt-get $*"})
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertIn("ran apt-get update", run.stdout)
        self.assertIn("ran apt-get install -y --no-install-recommends pulseaudio pulseaudio-utils "
                      "libasound2-plugins alsa-utils", run.stdout)
        self.assertEqual(self.app.seen, ["sound-start"])

    def test_sound_install_stops_when_apt_fails(self):
        run = self.sound("install", "--yes", commands={"apt-get": "exit 100"})
        self.assertEqual(run.returncode, 2)
        self.assertIn("failed", run.stderr)
        self.assertEqual(self.app.seen, [])

    def test_sound_usage(self):
        run = self.sound("loud")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx sound [status] | start | install [--yes]", run.stderr)

    # --- audio --------------------------------------------------------------

    def test_audio_play_waits_for_the_end_quietly(self):
        self.start_app({"audio-play": {"ok": True, "file": "/srv/a.mp3", "seconds": 3.3}})
        run = self.mynx("audio", "play", "a.mp3", cwd="/srv")
        self.assertEqual((run.returncode, run.stdout, run.stderr), (0, "", ""))
        self.assertEqual(self.app.requests, [["audio-play", "/srv/a.mp3"]])
        self.assertEqual(json.loads(self.mynx("audio", "play", "/srv/a.mp3", "--json").stdout),
                         {"ok": True, "file": "/srv/a.mp3", "seconds": 3.3})

    def test_audio_play_stops_quietly_on_ctrl_c(self):
        self.start_app({"audio-play": {"ok": True, "_lines": [], "_hold": True}})
        proc = self.popen("audio", "play", "/srv/a.mp3")
        self.wait_for_request()
        time.sleep(0.2)
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, out, err), (130, "", ""))
        self.assertTrue(self.app.cancelled)

    def test_audio_record_for_some_seconds(self):
        self.start_app({"audio-record": {"ok": True, "_lines": [{"recording": True}],
                                         "file": "/srv/a.wav", "bytes": 160_044, "seconds": 5}})
        run = self.mynx("audio", "record", "a.wav", "--seconds", "5", "--rate", "16000", cwd="/srv")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stderr, "Recording to /srv/a.wav for 5 s\n")
        self.assertEqual(run.stdout, "Saved /srv/a.wav (156 KB, 5 s)\n")
        self.assertEqual(self.app.requests, [["audio-record", "/srv/a.wav", "seconds=5", "rate=16000"]])
        run = self.mynx("audio", "record", "/srv/a.wav", "--json")
        self.assertEqual(run.stderr, "")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "file": "/srv/a.wav", "bytes": 160_044, "seconds": 5})

    def test_audio_record_until_ctrl_c_says_what_it_saved(self):
        saved = {"ok": True, "file": "/srv/a.m4a", "bytes": 48_000, "seconds": 2.5}
        self.start_app({"audio-record": {"ok": True, "_lines": [{"recording": True}], "_hold": True,
                                         "_stopped": saved}})
        proc = self.popen("audio", "record", "/srv/a.m4a")
        self.wait_for_request()
        time.sleep(0.3)
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual(err, "Recording to /srv/a.m4a: Ctrl+C stops\n")
        self.assertEqual((proc.returncode, out), (0, "Saved /srv/a.m4a (47 KB, 2.5 s)\n"))
        self.assertTrue(self.app.cancelled)

    def test_audio_says_why_it_couldnt(self):
        self.start_app({"audio-record": {"ok": False, "error": "the microphone is off (mynx set android-microphone on)"}})
        run = self.mynx("audio", "record", "/srv/a.m4a")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "mynx: the microphone is off (mynx set android-microphone on)\n")

    def test_audio_usage(self):
        usage = "usage: mynx audio play FILE | record FILE [--seconds N] [--rate HZ]"
        for args in [(), ("play",), ("play", "a", "b"), ("play", "a", "--seconds", "2"), ("record",),
                     ("record", "a.m4a", "--seconds"), ("record", "a.m4a", "--loud"), ("stop",)]:
            run = self.mynx("audio", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- share --------------------------------------------------------------

    def test_share_sends_the_files_full_paths(self):
        self.start_app({"share": {"ok": True, "count": 2}})
        run = self.mynx("share", "a.jpg", "/srv/b c.txt", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 2 files: pick an app on the phone\n")
        self.assertEqual(self.app.requests, [["share", os.path.join(self.tmp.name, "a.jpg"), "/srv/b c.txt"]])

    def test_share_one_file(self):
        self.start_app({"share": {"ok": True, "count": 1}})
        self.assertEqual(self.mynx("share", "/a").stdout, "Sharing 1 file: pick an app on the phone\n")

    def test_share_text_from_words_or_stdin(self):
        self.start_app({"share-text": {"ok": True}})
        run = self.mynx("share", "--text", "50%", "off")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 7 characters: pick an app on the phone\n")
        self.assertEqual(self.mynx("share", "--text", input="a\nb\n").returncode, 0)
        self.assertEqual(self.app.requests, [["share-text", "50%25%20off"], ["share-text", "a%0Ab%0A"]])

    def test_share_says_why_it_couldnt(self):
        self.start_app({"share": {"ok": False, "error": "no such file: /a"}})
        run = self.mynx("share", "/a")
        self.assertEqual(run.returncode, 2)
        self.assertIn("no such file: /a", run.stderr)

    def test_share_refuses_names_with_line_breaks(self):
        run = self.mynx("share", "/a\nb")
        self.assertEqual(run.returncode, 2)
        self.assertIn("can't share a file whose name has a line break", run.stderr)

    def test_share_needs_files_or_text(self):
        run = self.mynx("share")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: mynx share FILE... | --text [TEXT]", run.stderr)

    def test_install_apk_says_why_it_failed(self):
        self.start_app({"install-apk": {"ok": False, "error": "only debug builds can install apps"}})
        run = self.mynx("install-apk", "/tmp/app.apk")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only debug builds can install apps", run.stderr)

    def test_install_apk_needs_one_file(self):
        self.assertIn("usage: mynx install-apk FILE", self.mynx("install-apk").stderr)

    def test_install_apk_is_left_out_of_help(self):
        # A tool for developing the app, not for users.
        self.assertNotIn("install-apk", self.mynx("help").stdout)


if __name__ == "__main__":
    unittest.main()
