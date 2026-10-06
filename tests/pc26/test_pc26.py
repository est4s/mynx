"""Tests for the `pc26` command (tools/bin/pc26).

Run: python3 -m unittest discover -s tests/pc26
"""
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

PC26 = os.path.join(os.path.dirname(__file__), "..", "..", "tools", "bin", "pc26")


class FakeApp:
    """Answers requests like the app does (core's Pc26Requests)."""

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
        "_hold" no answer until pc26 cancels it."""
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


class Pc26Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.requests = os.path.join(self.tmp.name, "requests")
        os.mkdir(self.requests)
        self.tools = os.path.join(self.tmp.name, "tools")
        os.mkdir(self.tools)
        self.home = os.path.join(self.tmp.name, "home")
        self.config = os.path.join(self.home, ".config", "pc26")
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

    def pc26(self, *args, env=None, cwd=None, input=None):
        environ = {"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        environ.update(env or {})
        return subprocess.run(["python3", PC26, *args], capture_output=True, text=True, env=environ, cwd=cwd,
                              input=input)

    def wait_for_request(self):
        deadline = time.monotonic() + 5
        while not self.app.requests:
            self.assertLess(time.monotonic(), deadline, "pc26 sent no request")
            time.sleep(0.01)

    def popen(self, *args):
        environ = {"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        return subprocess.Popen(["python3", PC26, *args], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    # --- check -------------------------------------------------------------

    def test_check_with_no_problems(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.pc26("check")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn("No problems", run.stdout)
        self.assertEqual(self.app.seen, ["check"])

    def test_check_lists_problems_by_file_and_fails(self):
        self.start_app({"check": {"ok": True, "problems": [
            {"file": "~/.config/pc26/colors.properties",
             "problems": ["line 1: expected key=value", "line 4: unknown key 'x'"]}]}})
        run = self.pc26("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/pc26/colors.properties",
            "  line 1: expected key=value",
            "  line 4: unknown key 'x'",
        ])

    def test_check_json_passes_the_answer_through(self):
        answer = {"ok": True, "problems": [{"file": "f", "problems": ["p"]}]}
        self.start_app({"check": answer})
        run = self.pc26("check", "--json")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(json.loads(run.stdout), answer)

    def test_cleans_up_its_request_files(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.pc26("check")
        self.assertEqual(os.listdir(self.requests), [])

    # --- talking to the app ------------------------------------------------

    def test_outside_the_app_says_so(self):
        run = self.pc26("check", env={"PC26_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        self.assertIn("only works inside the app", run.stderr)

    def test_an_app_that_doesnt_answer_times_out_and_leaves_no_request(self):
        run = self.pc26("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("didn't answer", run.stderr)
        self.assertEqual(os.listdir(self.requests), [])

    def test_errors_are_json_with_json(self):
        run = self.pc26("check", "--json", env={"PC26_REQUESTS": ""})
        self.assertEqual(run.returncode, 2)
        reply = json.loads(run.stdout)
        self.assertFalse(reply["ok"])
        self.assertIn("only works inside the app", reply["error"])

    def test_an_error_from_the_app_is_shown(self):
        self.start_app({"check": {"ok": False, "error": "unknown request 'check'"}})
        run = self.pc26("check")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown request 'check'", run.stderr)

    # --- help and version --------------------------------------------------

    def test_help_lists_the_commands(self):
        for args in [(), ("help",), ("--help",)]:
            run = self.pc26(*args)
            self.assertEqual(run.returncode, 0)
            self.assertIn("check", run.stdout)
            self.assertIn("--json", run.stdout)

    def test_unknown_command_fails_with_help(self):
        run = self.pc26("frobnicate")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown command 'frobnicate'", run.stderr)

    def test_version_is_the_installed_tools_version(self):
        with open(os.path.join(self.tools, ".version"), "w") as f:
            f.write("57\n")
        self.assertEqual(self.pc26("version").stdout, "57\n")
        self.assertEqual(json.loads(self.pc26("version", "--json").stdout), {"ok": True, "version": "57"})

    def test_welcome_prints_the_welcome_page(self):
        with open(os.path.join(self.tools, "welcome.txt"), "w") as f:
            f.write("Hello.\n\nMenu  type menu\n")
        run = self.pc26("welcome")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Hello.\n\nMenu  type menu\n")
        self.assertEqual(json.loads(self.pc26("welcome", "--json").stdout),
                         {"ok": True, "text": "Hello.\n\nMenu  type menu\n"})

    def test_welcome_without_its_page_fails(self):
        run = self.pc26("welcome")
        self.assertEqual(run.returncode, 2)
        self.assertIn("welcome.txt", run.stderr)


    # --- settings ----------------------------------------------------------

    SETTINGS = {"ok": True, "problems": [], "settings": [
        {"key": "font-size", "value": "14", "default": "12", "description": "Text size.", "choices": None},
        {"key": "cursor-style", "value": "block", "default": "block", "description": "Cursor shape.",
         "choices": ["block", "underline", "bar"]},
    ]}

    def test_settings_lists_values_with_descriptions(self):
        self.start_app({"settings": self.SETTINGS})
        run = self.pc26("settings")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.splitlines(), [
            "font-size = 14  (default 12)",
            "  Text size.",
            "cursor-style = block  (block, underline, bar)",
            "  Cursor shape.",
        ])

    def test_settings_json(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(json.loads(self.pc26("settings", "--json").stdout), self.SETTINGS)

    def test_get_prints_one_value(self):
        self.start_app({"settings": self.SETTINGS})
        self.assertEqual(self.pc26("get", "font-size").stdout, "14\n")
        run = self.pc26("get", "colour")
        self.assertEqual(run.returncode, 2)
        self.assertIn("unknown setting 'colour'", run.stderr)

    def test_set_sends_the_key_and_value(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        run = self.pc26("set", "font-size", "16")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "font-size = 16\n")
        self.assertEqual(self.app.requests, [["set", "font-size", "16"]])

    def test_set_joins_a_value_with_spaces(self):
        self.start_app({"set": lambda k, v: {"ok": True, "key": k, "value": v}})
        self.pc26("set", "font", "/root/My", "Font.ttf")
        self.assertEqual(self.app.requests, [["set", "font", "/root/My Font.ttf"]])

    def test_commands_say_what_arguments_they_need(self):
        run = self.pc26("set", "font-size")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pc26 set KEY VALUE", run.stderr)

    def test_reset_one_setting_or_all(self):
        self.start_app({"reset": lambda k: {"ok": True, "key": k, "value": "12"} if k != "all" else {"ok": True, "key": "all"}})
        self.assertEqual(self.pc26("reset", "font-size").stdout, "font-size = 12 (default)\n")
        self.assertEqual(self.pc26("reset", "all").stdout, "All settings back to their defaults\n")
        self.assertEqual(self.app.requests, [["reset", "font-size"], ["reset", "all"]])
        self.assertIn("usage: pc26 reset KEY|all", self.pc26("reset").stderr)

    # --- themes ------------------------------------------------------------

    THEMES = {"ok": True, "current": "nord", "themes": [
        {"name": "neon", "source": "built-in"}, {"name": "nord", "source": "built-in"},
        {"name": "mine", "source": "~/.config/pc26/themes/mine.colors.properties"}]}

    def test_theme_list_marks_the_current_one(self):
        self.start_app({"themes": self.THEMES})
        for args in [("theme",), ("theme", "list")]:
            self.assertEqual(self.pc26(*args).stdout.splitlines(), [
                "  neon", "* nord", "  mine  (~/.config/pc26/themes/mine.colors.properties)"])

    def test_theme_set_and_show(self):
        self.start_app({"theme-set": lambda n: {"ok": True, "name": n},
                        "theme-show": lambda n: {"ok": True, "name": n, "source": "built-in", "text": "background=#000000\n"}})
        self.assertEqual(self.pc26("theme", "set", "nord").stdout, "Theme: nord\n")
        self.assertEqual(self.pc26("theme", "show", "nord").stdout, "background=#000000\n")
        self.assertEqual(self.app.requests, [["theme-set", "nord"], ["theme-show", "nord"]])

    def test_theme_reset(self):
        self.start_app({"theme-reset": {"ok": True, "name": "neon"}})
        self.assertEqual(self.pc26("theme", "reset").stdout, "Theme: neon (the default)\n")

    def test_theme_unknown_subcommand(self):
        run = self.pc26("theme", "paint")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pc26 theme", run.stderr)

    # --- key bars ----------------------------------------------------------

    def test_keybar_list(self):
        self.start_app({"keybars": {"ok": True, "keybars": [
            {"name": "htop", "builtIn": False, "file": "~/.config/pc26/keybars/htop.conf"},
            {"name": "nnn", "builtIn": True, "file": "~/.config/pc26/keybars/nnn.conf"},
            {"name": "shell", "builtIn": True, "file": None}]}})
        self.assertEqual(self.pc26("keybar", "list").stdout.splitlines(), [
            "htop   yours    ~/.config/pc26/keybars/htop.conf",
            "nnn    edited   ~/.config/pc26/keybars/nnn.conf",
            "shell  built-in",
        ])

    def test_keybar_show_edit_reset(self):
        self.start_app({
            "keybar-show": lambda n: {"ok": True, "name": n, "file": None, "text": "Quit = q\n"},
            "keybar-edit": lambda n: {"ok": True, "file": f"~/.config/pc26/keybars/{n}.conf"},
            "keybar-reset": lambda n: {"ok": True}})
        self.assertEqual(self.pc26("keybar", "show", "nnn").stdout, "Quit = q\n")
        self.assertEqual(self.pc26("keybar", "edit", "nnn").stdout.splitlines()[0],
                         "Edit ~/.config/pc26/keybars/nnn.conf, then run pc26 check.")
        self.assertEqual(self.pc26("keybar", "reset", "nnn").stdout, "nnn: back to the built-in bar\n")
        self.assertEqual([r[0] for r in self.app.requests], ["keybar-show", "keybar-edit", "keybar-reset"])


    # --- menu --------------------------------------------------------------

    def write(self, path, text, mode=0o644):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)
        os.chmod(path, mode)

    def test_menu_show_prints_the_menu_in_use(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.assertEqual(self.pc26("menu").stdout, "Terminal = shell\n")
        self.write(os.path.join(self.config, "menu.conf"), "Mine = files\n")
        self.assertEqual(self.pc26("menu", "show").stdout, "Mine = files\n")
        self.assertEqual(json.loads(self.pc26("menu", "show", "--json").stdout),
                         {"ok": True, "file": "~/.config/pc26/menu.conf", "text": "Mine = files\n"})

    def test_menu_edit_copies_the_built_in_menu_once(self):
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        run = self.pc26("menu", "edit")
        self.assertEqual(run.stdout.splitlines()[0], "Edit ~/.config/pc26/menu.conf, then run pc26 check.")
        mine = os.path.join(self.config, "menu.conf")
        with open(mine) as f:
            self.assertEqual(f.read(), "Terminal = shell\n")
        self.write(mine, "Mine = files\n")
        self.pc26("menu", "edit")
        with open(mine) as f:
            self.assertEqual(f.read(), "Mine = files\n")

    def test_menu_reset_removes_the_users_menu(self):
        mine = os.path.join(self.config, "menu.conf")
        self.write(mine, "Mine = files\n")
        self.assertEqual(self.pc26("menu", "reset").stdout, "Menu: back to the built-in one\n")
        self.assertFalse(os.path.exists(mine))
        run = self.pc26("menu", "reset")
        self.assertEqual(run.returncode, 2)
        self.assertIn("already the built-in menu", run.stderr)

    def test_check_includes_problems_in_the_users_menu(self):
        # The menu checks its own file: menu --check FILE.
        self.write(os.path.join(self.tools, "bin", "menu"),
                   '#!/bin/sh\n[ "$1" = --check ] && echo "line 2: bad $2" && exit 1\n', 0o755)
        self.write(os.path.join(self.config, "menu.conf"), "x\n")
        self.start_app({"check": {"ok": True, "problems": []}})
        run = self.pc26("check")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout.splitlines(), [
            "~/.config/pc26/menu.conf",
            f"  line 2: bad {self.config}/menu.conf",
        ])

    # --- notify ------------------------------------------------------------

    def notify_app(self, shown=True):
        reply = {"ok": True, "shown": True} if shown else {"ok": True, "shown": False, "reason": "agent-notify is off"}
        self.start_app({"notify": lambda *args: reply})

    def test_notify_sends_title_text_and_tab(self):
        self.notify_app()
        run = self.pc26("notify", "Build done", "all", "tests", "pass", env={"PC26_SHELL": "4"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Notification shown\n")
        self.assertEqual(self.app.requests, [["notify", "Build done", "all tests pass", "shell=4"]])

    def test_notify_if_away_and_without_text(self):
        self.notify_app()
        self.pc26("notify", "--if-away", "Hi")
        self.assertEqual(self.app.requests, [["notify", "Hi", "", "if-away"]])

    def test_notify_turns_line_breaks_into_spaces(self):
        self.notify_app()
        self.pc26("notify", "two\nlines", "and\nmore")
        self.assertEqual(self.app.requests, [["notify", "two lines", "and more"]])

    def test_notify_says_why_nothing_was_shown(self):
        self.notify_app(shown=False)
        run = self.pc26("notify", "Hi")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout, "Not shown: agent-notify is off\n")

    def test_notify_needs_a_title(self):
        run = self.pc26("notify")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pc26 notify", run.stderr)

    # --- hook (agents' notification hooks) -----------------------------------

    def hook(self, event, env=None, **fields):
        return self.hook_as("claude", event, env, **fields)

    def hook_as(self, agent, event, env=None, **fields):
        data = {"hook_event_name": event, "session_id": "s1", "cwd": "/root/project", **fields}
        environ = {"TMPDIR": self.tmp.name, "PC26_SHELL": "2", **(env or {})}
        run = subprocess.run(["python3", PC26, "hook", agent], input=json.dumps(data),
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests,
                                  "HOME": self.home, "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1",
                                  **environ})
        return run

    def turn_started(self, seconds_ago, agent="claude"):
        folder = os.path.join(self.tmp.name, "pc26-agent-turns")
        os.makedirs(folder, exist_ok=True)
        self.write(os.path.join(folder, f"{agent}-s1"), f"{time.time() - seconds_ago}\n")

    def test_hook_records_when_a_turn_starts_without_asking_the_app(self):
        self.notify_app()
        run = self.hook("UserPromptSubmit", prompt="hi")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.tmp.name, "pc26-agent-turns", "claude-s1")))
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
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "pc26-agent-turns", "claude-s1")))

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
        run = subprocess.run(["python3", PC26, "hook", "claude"], input="not json",
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "TMPDIR": self.tmp.name})
        self.assertEqual((run.returncode, run.stdout), (0, ""))

    def test_hook_for_an_unknown_agent(self):
        run = self.pc26("hook", "skynet")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pc26 hook claude|codex|gemini", run.stderr)

    # --- agent -------------------------------------------------------------

    def fake_commands(self, **scripts):
        """Puts fake commands first on the PATH; returns that PATH."""
        bin_dir = os.path.join(self.tmp.name, "bin")
        for name, body in scripts.items():
            self.write(os.path.join(bin_dir, name), "#!/bin/sh\n" + body + "\n", 0o755)
        # Not the real PATH: the machine running the tests may have the real agents.
        return bin_dir + ":/usr/bin:/bin"

    def agent(self, *args, stdin="", path=None):
        environ = {"PATH": path or "/usr/bin:/bin", "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        return subprocess.run(["python3", PC26, "agent", *args], input=stdin, capture_output=True,
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
        environ = {"PATH": path, "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1", "PC26_KEYBAR_FILE": bar_file}
        run = subprocess.run(["python3", PC26, "agent", "start", "claude"], capture_output=True,
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
            self.assertIn("pc26 agent", run.stderr)

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
        run = self.pc26("undo")
        self.assertEqual((run.returncode, run.stdout), (0, "Undid: set font-size 16\n"))
        self.assertEqual(self.app.requests, [["undo"]])

    def test_undo_with_nothing_to_undo(self):
        self.start_app({"undo": {"ok": True, "undone": None}})
        run = self.pc26("undo")
        self.assertEqual((run.returncode, run.stdout), (1, "Nothing to undo\n"))

    def test_undo_list(self):
        noon = time.mktime((2026, 10, 4, 12, 5, 0, 0, 0, -1)) * 1000
        self.start_app({"undo-list": {"ok": True, "keep": 3, "steps": [
            {"reason": "theme set nord", "time": noon}, {"reason": "edits by hand", "time": noon}]}})
        run = self.pc26("undo", "--list")
        self.assertEqual(run.stdout.splitlines(), [
            "1. theme set nord  (12:05)", "2. edits by hand  (12:05)", "pc26 undo takes back 1. (keeps 3: undo-keep)"])

    def test_undo_list_when_empty(self):
        self.start_app({"undo-list": {"ok": True, "keep": 1, "steps": []}})
        self.assertEqual(self.pc26("undo", "--list").stdout, "Nothing to undo (keeps 1: undo-keep)\n")

    def test_menu_edit_and_reset_are_recorded_for_undo(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.pc26("menu", "edit")
        self.pc26("menu", "reset")
        self.assertEqual(self.app.requests, [["check", "menu edit"], ["check", "menu reset"]])

    # --- open --------------------------------------------------------------

    def test_open_hands_a_link_to_the_app(self):
        self.start_app({"open-url": {"ok": True}})
        run = self.pc26("open", "https://claude.ai/login")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["open-url", "https://claude.ai/login"]])

    def test_open_says_why_it_failed(self):
        self.start_app({"open-url": {"ok": False, "error": "only http and https links open: x"}})
        run = self.pc26("open", "x")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only http and https", run.stderr)

    def test_open_needs_one_link(self):
        self.assertIn("usage: pc26 open URL", self.pc26("open").stderr)

    # --- install-apk (debug builds) ------------------------------------------

    def test_install_apk_sends_the_full_path(self):
        self.start_app({"install-apk": {"ok": True}})
        run = self.pc26("install-apk", "app.apk", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Installer opened on the phone.\n")
        self.assertEqual(self.app.requests, [["install-apk", os.path.join(self.tmp.name, "app.apk")]])

    # --- vibrate and clipboard --------------------------------------------

    def test_vibrate_for_the_default_or_a_given_time(self):
        self.start_app({"vibrate": {"ok": True}})
        self.assertEqual(self.pc26("vibrate").returncode, 0)
        run = self.pc26("vibrate", "50")
        self.assertEqual((run.returncode, run.stdout), (0, ""))
        self.assertEqual(self.app.requests, [["vibrate"], ["vibrate", "50"]])

    def test_vibrate_takes_one_time_at_most(self):
        self.assertIn("usage: pc26 vibrate [MS]", self.pc26("vibrate", "1", "2").stderr)

    def test_clipboard_get_prints_the_text_as_it_is(self):
        self.start_app({"clipboard-get": {"ok": True, "text": "two\nlines"}})
        run = self.pc26("clipboard", "get")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "two\nlines")
        self.assertEqual(self.pc26("clipboard", "get", "--json").stdout, '{"ok": true, "text": "two\\nlines"}\n')

    def test_clipboard_get_says_why_it_couldnt(self):
        self.start_app({"clipboard-get": {"ok": False, "error": "the app must be on screen to read the clipboard"}})
        run = self.pc26("clipboard", "get")
        self.assertEqual(run.returncode, 2)
        self.assertIn("the app must be on screen", run.stderr)

    def test_clipboard_set_sends_the_words_encoded(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.pc26("clipboard", "set", "50%", "off", "+", "é")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Copied 11 characters\n")
        self.assertEqual(self.app.requests, [["clipboard-set", "50%25%20off%20%2B%20%C3%A9"]])

    def test_clipboard_set_reads_stdin_line_breaks_and_all(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.pc26("clipboard", "set", input="a\nb\r\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["clipboard-set", "a%0Ab%0D%0A"]])

    def test_clipboard_needs_get_or_set(self):
        for args in [("clipboard",), ("clipboard", "paste"), ("clipboard", "get", "x")]:
            run = self.pc26(*args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: pc26 clipboard get | set [TEXT]", run.stderr)

    # --- location -----------------------------------------------------------

    FIX = {"latitude": 60.1695213, "longitude": 24.9354471, "accuracy": 12.3, "altitude": 21, "speed": None,
           "bearing": None, "provider": "network", "time": 1791177403145}

    def test_location_prints_one_fix(self):
        self.start_app({"location": {"ok": True, "location": self.FIX}})
        run = self.pc26("location", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n")
        self.assertEqual(self.app.requests, [["location"]])
        run = self.pc26("location", "--json")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "location": self.FIX})

    def test_location_leaves_out_an_unknown_accuracy(self):
        self.start_app({"location": {"ok": True, "location": dict(self.FIX, accuracy=None)}})
        self.assertEqual(self.pc26("location", env={"TZ": "UTC"}).stdout, "60.1695213, 24.9354471 network 05:16:43\n")

    def test_location_options(self):
        self.start_app({"location": {"ok": True, "location": self.FIX},
                        "location-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.pc26("location", "--gps", "--timeout", "120").returncode, 0)
        self.assertEqual(self.pc26("location", "--stream", "--every", "2", "--gps").returncode, 0)
        self.assertEqual(self.app.requests, [["location", "gps", "timeout=120"],
                                             ["location-stream", "interval=2", "gps"]])

    def test_location_stream_prints_a_line_per_fix(self):
        second = dict(self.FIX, provider="gps", time=1791177408145)
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX, second]}})
        run = self.pc26("location", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n"
                                     "60.1695213, 24.9354471 ±12 m gps 05:16:48\n")
        run = self.pc26("location", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.FIX, second])

    def test_location_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", PC26, "location", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("network", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_location_says_why_there_is_no_fix(self):
        self.start_app({"location": {"ok": False, "error": "no fix within 60 s"}})
        run = self.pc26("location")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pc26: no fix within 60 s\n")

    def test_location_usage(self):
        usage = "usage: pc26 location [--gps] [--timeout SECONDS] | --stream [--every SECONDS] [--gps]"
        for args in [("--fast",), ("--timeout",), ("--every", "2"), ("--stream", "--timeout", "5"), ("x",)]:
            run = self.pc26("location", *args)
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
        run = self.pc26("sensor", "list")
        self.assertEqual(run.returncode, 0, run.stderr)
        # Values line up under the longest name.
        self.assertEqual(run.stdout, "accelerometer               x y z m/s²\n"
                                     "rotation-vector             x y z w\n"
                                     "light                       illuminance lx\n"
                                     "magnetic-field-uncalibrated x bias-x µT\n")
        self.assertEqual(self.app.requests, [["sensor-list"]])
        self.assertEqual(json.loads(self.pc26("sensor", "list", "--json").stdout), {"ok": True, "sensors": sensors})

    def test_sensor_prints_one_reading(self):
        self.start_app({"sensor": lambda name, *rest: {"ok": True, "reading": self.READING if name == "accelerometer"
                                                       else self.LIGHT}})
        run = self.pc26("sensor", "accelerometer")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "x=0.1235 y=9.8067 z=-0.5 m/s²\n")
        self.assertEqual(self.pc26("sensor", "light").stdout, "108 lx\n")
        self.assertEqual(json.loads(self.pc26("sensor", "accelerometer", "--json").stdout),
                         {"ok": True, "reading": self.READING})
        self.assertEqual(self.app.requests[0], ["sensor", "accelerometer"])

    def test_sensor_options(self):
        self.start_app({"sensor": {"ok": True, "reading": self.LIGHT},
                        "sensor-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.pc26("sensor", "light", "--timeout", "30").returncode, 0)
        self.assertEqual(self.pc26("sensor", "--stream", "gyroscope", "--rate", "50").returncode, 0)
        self.assertEqual(self.app.requests, [["sensor", "light", "timeout=30"],
                                             ["sensor-stream", "gyroscope", "rate=50"]])

    def test_sensor_stream_prints_a_line_per_reading(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING, self.LIGHT]}})
        run = self.pc26("sensor", "accelerometer", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "05:16:43.145 x=0.1235 y=9.8067 z=-0.5 m/s²\n"
                                     "05:16:43.245 108 lx\n")
        run = self.pc26("sensor", "accelerometer", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.READING, self.LIGHT])

    def test_sensor_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", PC26, "sensor", "accelerometer", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("m/s²", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_sensor_says_why_there_is_no_reading(self):
        self.start_app({"sensor": {"ok": False, "error": "the phone has no pressure sensor"}})
        run = self.pc26("sensor", "pressure")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pc26: the phone has no pressure sensor\n")

    def test_sensor_usage(self):
        usage = "usage: pc26 sensor list | NAME [--timeout SECONDS] | NAME --stream [--rate HZ]"
        for args in [(), ("--stream",), ("light", "--fast"), ("light", "--timeout"), ("light", "--rate", "5"),
                     ("light", "--stream", "--timeout", "5"), ("light", "dark"), ("list", "x")]:
            run = self.pc26("sensor", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- camera and torch ---------------------------------------------------

    def test_camera_saves_to_the_files_full_path(self):
        self.start_app({"camera": {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000}})
        run = self.pc26("camera", "a.jpg", cwd="/srv")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (2.3 MB)\n")
        self.assertEqual(self.app.requests, [["camera", "/srv/a.jpg"]])
        self.assertEqual(json.loads(self.pc26("camera", "/srv/a.jpg", "--json").stdout),
                         {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000})

    def test_camera_quick_from_the_front_or_back(self):
        self.start_app({"camera-quick": {"ok": True, "file": "/srv/a.jpg", "bytes": 900_000}})
        run = self.pc26("camera", "--quick", "front", "/srv/a.jpg")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (879 KB)\n")
        self.assertEqual(self.pc26("camera", "/srv/a.jpg", "--quick", "back").returncode, 0)
        self.assertEqual(self.app.requests, [["camera-quick", "/srv/a.jpg", "front"],
                                             ["camera-quick", "/srv/a.jpg", "back"]])

    def test_camera_says_why_there_is_no_photo(self):
        self.start_app({"camera": {"ok": False, "error": "no photo was taken"}})
        run = self.pc26("camera", "/srv/a.jpg")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pc26: no photo was taken\n")

    def test_camera_stops_quietly_on_ctrl_c(self):
        self.start_app({"camera-quick": {"ok": True, "_lines": [], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", PC26, "camera", "--quick", "back", "/srv/a.jpg"], env=environ,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        while not self.app.requests:
            time.sleep(0.01)
        time.sleep(0.2)
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, out, err), (130, "", ""))
        self.assertTrue(self.app.cancelled)

    def test_camera_usage(self):
        usage = "usage: pc26 camera FILE [--quick front|back]"
        for args in [(), ("a.jpg", "b.jpg"), ("a.jpg", "--quick"), ("a.jpg", "--fast"), ("--quick", "back")]:
            run = self.pc26("camera", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    def test_torch_on_at_a_strength_and_off(self):
        self.start_app({"torch": {"ok": True}})
        for args in [("on",), ("on", "40"), ("off",)]:
            run = self.pc26("torch", *args)
            self.assertEqual((run.returncode, run.stdout), (0, ""), run.stderr)
        self.assertEqual(self.app.requests, [["torch", "on"], ["torch", "on", "40"], ["torch", "off"]])

    def test_torch_usage(self):
        for args in [(), ("bright",), ("off", "5"), ("on", "5", "6")]:
            run = self.pc26("torch", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: pc26 torch on [PERCENT] | off", run.stderr, args)

    # --- rotation -----------------------------------------------------------

    def test_rotation_lock_is_held_by_the_program_that_runs_pc26(self):
        self.start_app({"rotation": {"ok": True, "locked": "current"}})
        run = self.pc26("rotation", "lock")
        self.assertEqual((run.returncode, run.stdout), (0, "Rotation: locked as it is\n"), run.stderr)
        self.assertEqual(self.app.requests, [["rotation", "lock", str(os.getpid())]])

    def test_rotation_lock_to_a_side_or_for_another_process(self):
        self.start_app({"rotation": {"ok": True, "locked": "landscape"}})
        run = self.pc26("rotation", "lock", "landscape", "--pid", "77")
        self.assertEqual((run.returncode, run.stdout), (0, "Rotation: locked to landscape\n"), run.stderr)
        self.assertEqual(self.app.requests, [["rotation", "lock", "77", "landscape"]])

    def test_rotation_unlock_and_status(self):
        self.start_app({"rotation": {"ok": True, "locked": "no"}})
        self.assertEqual(self.pc26("rotation", "unlock").stdout, "Rotation: free\n")
        self.assertEqual(self.pc26("rotation").stdout, "Rotation: free\n")
        run = self.pc26("--json", "rotation", "status")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "locked": "no"})
        self.assertEqual(self.app.requests, [["rotation", "unlock"], ["rotation", "status"], ["rotation", "status"]])

    def test_rotation_usage(self):
        for args in [("spin",), ("lock", "sideways"), ("lock", "--pid"), ("lock", "--pid", "x"), ("unlock", "now")]:
            run = self.pc26("rotation", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: pc26 rotation [status] | lock [portrait|landscape] [--pid PID] | unlock",
                          run.stderr, args)

    # --- sound --------------------------------------------------------------

    SETTINGS_ON = {"ok": True, "problems": [], "settings": [
        {"key": "sound-device", "value": "on", "default": "on", "choices": ["on", "off"], "description": ""}]}

    def sound(self, *args, stdin="", commands=None, settings=None, restart=0):
        """`pc26 sound` with only fake commands on the PATH (this machine
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
        environ = {"PATH": bin_dir, "PC26_REQUESTS": self.requests, "HOME": self.home,
                   "PC26_TOOLS": self.tools, "PC26_TIMEOUT": "1", "PULSE_SERVER": "unix:" + self.socket_path}
        return subprocess.run([sys.executable, PC26, "sound", *args], input=stdin, capture_output=True,
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
        self.assertEqual(run.stdout, "Sound device: not running (pc26 sound start starts it)\n")

    def test_sound_says_when_pulseaudio_isnt_installed(self):
        run = self.sound()
        self.assertEqual(run.stdout, "Sound device: not installed (pc26 sound install installs it)\n")

    def test_sound_says_when_the_setting_is_off(self):
        off = json.loads(json.dumps(self.SETTINGS_ON))
        off["settings"][0]["value"] = "off"
        run = self.sound(commands={"pulseaudio": "true"}, settings=off)
        self.assertEqual(run.stdout, "Sound device: off (pc26 set sound-device on turns it on)\n")

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
        self.assertIn("usage: pc26 sound [status] | start | install [--yes]", run.stderr)

    # --- audio --------------------------------------------------------------

    def test_audio_play_waits_for_the_end_quietly(self):
        self.start_app({"audio-play": {"ok": True, "file": "/srv/a.mp3", "seconds": 3.3}})
        run = self.pc26("audio", "play", "a.mp3", cwd="/srv")
        self.assertEqual((run.returncode, run.stdout, run.stderr), (0, "", ""))
        self.assertEqual(self.app.requests, [["audio-play", "/srv/a.mp3"]])
        self.assertEqual(json.loads(self.pc26("audio", "play", "/srv/a.mp3", "--json").stdout),
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
        run = self.pc26("audio", "record", "a.wav", "--seconds", "5", "--rate", "16000", cwd="/srv")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stderr, "Recording to /srv/a.wav for 5 s\n")
        self.assertEqual(run.stdout, "Saved /srv/a.wav (156 KB, 5 s)\n")
        self.assertEqual(self.app.requests, [["audio-record", "/srv/a.wav", "seconds=5", "rate=16000"]])
        run = self.pc26("audio", "record", "/srv/a.wav", "--json")
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
        self.start_app({"audio-record": {"ok": False, "error": "the microphone is off (pc26 set android-microphone on)"}})
        run = self.pc26("audio", "record", "/srv/a.m4a")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pc26: the microphone is off (pc26 set android-microphone on)\n")

    def test_audio_usage(self):
        usage = "usage: pc26 audio play FILE | record FILE [--seconds N] [--rate HZ]"
        for args in [(), ("play",), ("play", "a", "b"), ("play", "a", "--seconds", "2"), ("record",),
                     ("record", "a.m4a", "--seconds"), ("record", "a.m4a", "--loud"), ("stop",)]:
            run = self.pc26("audio", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- share --------------------------------------------------------------

    def test_share_sends_the_files_full_paths(self):
        self.start_app({"share": {"ok": True, "count": 2}})
        run = self.pc26("share", "a.jpg", "/srv/b c.txt", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 2 files: pick an app on the phone\n")
        self.assertEqual(self.app.requests, [["share", os.path.join(self.tmp.name, "a.jpg"), "/srv/b c.txt"]])

    def test_share_one_file(self):
        self.start_app({"share": {"ok": True, "count": 1}})
        self.assertEqual(self.pc26("share", "/a").stdout, "Sharing 1 file: pick an app on the phone\n")

    def test_share_text_from_words_or_stdin(self):
        self.start_app({"share-text": {"ok": True}})
        run = self.pc26("share", "--text", "50%", "off")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 7 characters: pick an app on the phone\n")
        self.assertEqual(self.pc26("share", "--text", input="a\nb\n").returncode, 0)
        self.assertEqual(self.app.requests, [["share-text", "50%25%20off"], ["share-text", "a%0Ab%0A"]])

    def test_share_says_why_it_couldnt(self):
        self.start_app({"share": {"ok": False, "error": "no such file: /a"}})
        run = self.pc26("share", "/a")
        self.assertEqual(run.returncode, 2)
        self.assertIn("no such file: /a", run.stderr)

    def test_share_refuses_names_with_line_breaks(self):
        run = self.pc26("share", "/a\nb")
        self.assertEqual(run.returncode, 2)
        self.assertIn("can't share a file whose name has a line break", run.stderr)

    def test_share_needs_files_or_text(self):
        run = self.pc26("share")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pc26 share FILE... | --text [TEXT]", run.stderr)

    def test_install_apk_says_why_it_failed(self):
        self.start_app({"install-apk": {"ok": False, "error": "only debug builds can install apps"}})
        run = self.pc26("install-apk", "/tmp/app.apk")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only debug builds can install apps", run.stderr)

    def test_install_apk_needs_one_file(self):
        self.assertIn("usage: pc26 install-apk FILE", self.pc26("install-apk").stderr)

    def test_install_apk_is_left_out_of_help(self):
        # A tool for developing the app, not for users.
        self.assertNotIn("install-apk", self.pc26("help").stdout)


if __name__ == "__main__":
    unittest.main()
