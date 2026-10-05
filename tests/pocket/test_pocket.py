"""Tests for the `pocket` command (tools/bin/pocket).

Run: python3 -m unittest discover -s tests/pocket
"""
import json
import os
import shutil
import signal
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
        "_hold" no answer until pocket cancels it."""
        with open(base + ".wait", "w") as f:
            f.write("stream")
        with open(base + ".stream", "a") as f:
            f.writelines(json.dumps(line) + "\n" for line in answer["_lines"])
        if answer.get("_hold"):
            while not os.path.exists(base + ".cancel") and not self.stop.is_set():
                time.sleep(0.01)
            self.cancelled = True
            return {"ok": True}
        return {k: v for k, v in answer.items() if not k.startswith("_")}


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

    def pocket(self, *args, env=None, cwd=None, input=None):
        environ = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1"}
        environ.update(env or {})
        return subprocess.run(["python3", POCKET, *args], capture_output=True, text=True, env=environ, cwd=cwd,
                              input=input)

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

    # --- notify ------------------------------------------------------------

    def notify_app(self, shown=True):
        reply = {"ok": True, "shown": True} if shown else {"ok": True, "shown": False, "reason": "agent-notify is off"}
        self.start_app({"notify": lambda *args: reply})

    def test_notify_sends_title_text_and_tab(self):
        self.notify_app()
        run = self.pocket("notify", "Build done", "all", "tests", "pass", env={"POCKET_SHELL": "4"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Notification shown\n")
        self.assertEqual(self.app.requests, [["notify", "Build done", "all tests pass", "shell=4"]])

    def test_notify_if_away_and_without_text(self):
        self.notify_app()
        self.pocket("notify", "--if-away", "Hi")
        self.assertEqual(self.app.requests, [["notify", "Hi", "", "if-away"]])

    def test_notify_turns_line_breaks_into_spaces(self):
        self.notify_app()
        self.pocket("notify", "two\nlines", "and\nmore")
        self.assertEqual(self.app.requests, [["notify", "two lines", "and more"]])

    def test_notify_says_why_nothing_was_shown(self):
        self.notify_app(shown=False)
        run = self.pocket("notify", "Hi")
        self.assertEqual(run.returncode, 1)
        self.assertEqual(run.stdout, "Not shown: agent-notify is off\n")

    def test_notify_needs_a_title(self):
        run = self.pocket("notify")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pocket notify", run.stderr)

    # --- hook (agents' notification hooks) -----------------------------------

    def hook(self, event, env=None, **fields):
        return self.hook_as("claude", event, env, **fields)

    def hook_as(self, agent, event, env=None, **fields):
        data = {"hook_event_name": event, "session_id": "s1", "cwd": "/root/project", **fields}
        environ = {"TMPDIR": self.tmp.name, "POCKET_SHELL": "2", **(env or {})}
        run = subprocess.run(["python3", POCKET, "hook", agent], input=json.dumps(data),
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests,
                                  "HOME": self.home, "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1",
                                  **environ})
        return run

    def turn_started(self, seconds_ago, agent="claude"):
        folder = os.path.join(self.tmp.name, "pocket-agent-turns")
        os.makedirs(folder, exist_ok=True)
        self.write(os.path.join(folder, f"{agent}-s1"), f"{time.time() - seconds_ago}\n")

    def test_hook_records_when_a_turn_starts_without_asking_the_app(self):
        self.notify_app()
        run = self.hook("UserPromptSubmit", prompt="hi")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue(os.path.isfile(os.path.join(self.tmp.name, "pocket-agent-turns", "claude-s1")))
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
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "pocket-agent-turns", "claude-s1")))

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
        run = subprocess.run(["python3", POCKET, "hook", "claude"], input="not json",
                             capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "TMPDIR": self.tmp.name})
        self.assertEqual((run.returncode, run.stdout), (0, ""))

    def test_hook_for_an_unknown_agent(self):
        run = self.pocket("hook", "skynet")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pocket hook claude|codex|gemini", run.stderr)

    # --- agent -------------------------------------------------------------

    def fake_commands(self, **scripts):
        """Puts fake commands first on the PATH; returns that PATH."""
        bin_dir = os.path.join(self.tmp.name, "bin")
        for name, body in scripts.items():
            self.write(os.path.join(bin_dir, name), "#!/bin/sh\n" + body + "\n", 0o755)
        # Not the real PATH: the machine running the tests may have the real agents.
        return bin_dir + ":/usr/bin:/bin"

    def agent(self, *args, stdin="", path=None):
        environ = {"PATH": path or "/usr/bin:/bin", "POCKET_REQUESTS": self.requests, "HOME": self.home,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1"}
        return subprocess.run(["python3", POCKET, "agent", *args], input=stdin, capture_output=True,
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
        environ = {"PATH": path, "POCKET_REQUESTS": self.requests, "HOME": self.home,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1", "POCKET_KEYBAR_FILE": bar_file}
        run = subprocess.run(["python3", POCKET, "agent", "start", "claude"], capture_output=True,
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
            self.assertIn("pocket agent", run.stderr)

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
        run = self.pocket("undo")
        self.assertEqual((run.returncode, run.stdout), (0, "Undid: set font-size 16\n"))
        self.assertEqual(self.app.requests, [["undo"]])

    def test_undo_with_nothing_to_undo(self):
        self.start_app({"undo": {"ok": True, "undone": None}})
        run = self.pocket("undo")
        self.assertEqual((run.returncode, run.stdout), (1, "Nothing to undo\n"))

    def test_undo_list(self):
        noon = time.mktime((2026, 10, 4, 12, 5, 0, 0, 0, -1)) * 1000
        self.start_app({"undo-list": {"ok": True, "keep": 3, "steps": [
            {"reason": "theme set nord", "time": noon}, {"reason": "edits by hand", "time": noon}]}})
        run = self.pocket("undo", "--list")
        self.assertEqual(run.stdout.splitlines(), [
            "1. theme set nord  (12:05)", "2. edits by hand  (12:05)", "pocket undo takes back 1. (keeps 3: undo-keep)"])

    def test_undo_list_when_empty(self):
        self.start_app({"undo-list": {"ok": True, "keep": 1, "steps": []}})
        self.assertEqual(self.pocket("undo", "--list").stdout, "Nothing to undo (keeps 1: undo-keep)\n")

    def test_menu_edit_and_reset_are_recorded_for_undo(self):
        self.start_app({"check": {"ok": True, "problems": []}})
        self.write(os.path.join(self.tools, "menu.conf"), "Terminal = shell\n")
        self.pocket("menu", "edit")
        self.pocket("menu", "reset")
        self.assertEqual(self.app.requests, [["check", "menu edit"], ["check", "menu reset"]])

    # --- open --------------------------------------------------------------

    def test_open_hands_a_link_to_the_app(self):
        self.start_app({"open-url": {"ok": True}})
        run = self.pocket("open", "https://claude.ai/login")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["open-url", "https://claude.ai/login"]])

    def test_open_says_why_it_failed(self):
        self.start_app({"open-url": {"ok": False, "error": "only http and https links open: x"}})
        run = self.pocket("open", "x")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only http and https", run.stderr)

    def test_open_needs_one_link(self):
        self.assertIn("usage: pocket open URL", self.pocket("open").stderr)

    # --- install-apk (debug builds) ------------------------------------------

    def test_install_apk_sends_the_full_path(self):
        self.start_app({"install-apk": {"ok": True}})
        run = self.pocket("install-apk", "app.apk", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Installer opened on the phone.\n")
        self.assertEqual(self.app.requests, [["install-apk", os.path.join(self.tmp.name, "app.apk")]])

    # --- vibrate and clipboard --------------------------------------------

    def test_vibrate_for_the_default_or_a_given_time(self):
        self.start_app({"vibrate": {"ok": True}})
        self.assertEqual(self.pocket("vibrate").returncode, 0)
        run = self.pocket("vibrate", "50")
        self.assertEqual((run.returncode, run.stdout), (0, ""))
        self.assertEqual(self.app.requests, [["vibrate"], ["vibrate", "50"]])

    def test_vibrate_takes_one_time_at_most(self):
        self.assertIn("usage: pocket vibrate [MS]", self.pocket("vibrate", "1", "2").stderr)

    def test_clipboard_get_prints_the_text_as_it_is(self):
        self.start_app({"clipboard-get": {"ok": True, "text": "two\nlines"}})
        run = self.pocket("clipboard", "get")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "two\nlines")
        self.assertEqual(self.pocket("clipboard", "get", "--json").stdout, '{"ok": true, "text": "two\\nlines"}\n')

    def test_clipboard_get_says_why_it_couldnt(self):
        self.start_app({"clipboard-get": {"ok": False, "error": "the app must be on screen to read the clipboard"}})
        run = self.pocket("clipboard", "get")
        self.assertEqual(run.returncode, 2)
        self.assertIn("the app must be on screen", run.stderr)

    def test_clipboard_set_sends_the_words_encoded(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.pocket("clipboard", "set", "50%", "off", "+", "é")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Copied 11 characters\n")
        self.assertEqual(self.app.requests, [["clipboard-set", "50%25%20off%20%2B%20%C3%A9"]])

    def test_clipboard_set_reads_stdin_line_breaks_and_all(self):
        self.start_app({"clipboard-set": {"ok": True}})
        run = self.pocket("clipboard", "set", input="a\nb\r\n")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.app.requests, [["clipboard-set", "a%0Ab%0D%0A"]])

    def test_clipboard_needs_get_or_set(self):
        for args in [("clipboard",), ("clipboard", "paste"), ("clipboard", "get", "x")]:
            run = self.pocket(*args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: pocket clipboard get | set [TEXT]", run.stderr)

    # --- location -----------------------------------------------------------

    FIX = {"latitude": 60.1695213, "longitude": 24.9354471, "accuracy": 12.3, "altitude": 21, "speed": None,
           "bearing": None, "provider": "network", "time": 1791177403145}

    def test_location_prints_one_fix(self):
        self.start_app({"location": {"ok": True, "location": self.FIX}})
        run = self.pocket("location", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n")
        self.assertEqual(self.app.requests, [["location"]])
        run = self.pocket("location", "--json")
        self.assertEqual(json.loads(run.stdout), {"ok": True, "location": self.FIX})

    def test_location_leaves_out_an_unknown_accuracy(self):
        self.start_app({"location": {"ok": True, "location": dict(self.FIX, accuracy=None)}})
        self.assertEqual(self.pocket("location", env={"TZ": "UTC"}).stdout, "60.1695213, 24.9354471 network 05:16:43\n")

    def test_location_options(self):
        self.start_app({"location": {"ok": True, "location": self.FIX},
                        "location-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.pocket("location", "--gps", "--timeout", "120").returncode, 0)
        self.assertEqual(self.pocket("location", "--stream", "--every", "2", "--gps").returncode, 0)
        self.assertEqual(self.app.requests, [["location", "gps", "timeout=120"],
                                             ["location-stream", "interval=2", "gps"]])

    def test_location_stream_prints_a_line_per_fix(self):
        second = dict(self.FIX, provider="gps", time=1791177408145)
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX, second]}})
        run = self.pocket("location", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "60.1695213, 24.9354471 ±12 m network 05:16:43\n"
                                     "60.1695213, 24.9354471 ±12 m gps 05:16:48\n")
        run = self.pocket("location", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.FIX, second])

    def test_location_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"location-stream": {"ok": True, "_lines": [self.FIX], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", POCKET, "location", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("network", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_location_says_why_there_is_no_fix(self):
        self.start_app({"location": {"ok": False, "error": "no fix within 60 s"}})
        run = self.pocket("location")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pocket: no fix within 60 s\n")

    def test_location_usage(self):
        usage = "usage: pocket location [--gps] [--timeout SECONDS] | --stream [--every SECONDS] [--gps]"
        for args in [("--fast",), ("--timeout",), ("--every", "2"), ("--stream", "--timeout", "5"), ("x",)]:
            run = self.pocket("location", *args)
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
        run = self.pocket("sensor", "list")
        self.assertEqual(run.returncode, 0, run.stderr)
        # Values line up under the longest name.
        self.assertEqual(run.stdout, "accelerometer               x y z m/s²\n"
                                     "rotation-vector             x y z w\n"
                                     "light                       illuminance lx\n"
                                     "magnetic-field-uncalibrated x bias-x µT\n")
        self.assertEqual(self.app.requests, [["sensor-list"]])
        self.assertEqual(json.loads(self.pocket("sensor", "list", "--json").stdout), {"ok": True, "sensors": sensors})

    def test_sensor_prints_one_reading(self):
        self.start_app({"sensor": lambda name, *rest: {"ok": True, "reading": self.READING if name == "accelerometer"
                                                       else self.LIGHT}})
        run = self.pocket("sensor", "accelerometer")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "x=0.1235 y=9.8067 z=-0.5 m/s²\n")
        self.assertEqual(self.pocket("sensor", "light").stdout, "108 lx\n")
        self.assertEqual(json.loads(self.pocket("sensor", "accelerometer", "--json").stdout),
                         {"ok": True, "reading": self.READING})
        self.assertEqual(self.app.requests[0], ["sensor", "accelerometer"])

    def test_sensor_options(self):
        self.start_app({"sensor": {"ok": True, "reading": self.LIGHT},
                        "sensor-stream": {"ok": True, "_lines": []}})
        self.assertEqual(self.pocket("sensor", "light", "--timeout", "30").returncode, 0)
        self.assertEqual(self.pocket("sensor", "--stream", "gyroscope", "--rate", "50").returncode, 0)
        self.assertEqual(self.app.requests, [["sensor", "light", "timeout=30"],
                                             ["sensor-stream", "gyroscope", "rate=50"]])

    def test_sensor_stream_prints_a_line_per_reading(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING, self.LIGHT]}})
        run = self.pocket("sensor", "accelerometer", "--stream", env={"TZ": "UTC"})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "05:16:43.145 x=0.1235 y=9.8067 z=-0.5 m/s²\n"
                                     "05:16:43.245 108 lx\n")
        run = self.pocket("sensor", "accelerometer", "--stream", "--json")
        self.assertEqual([json.loads(line) for line in run.stdout.splitlines()], [self.READING, self.LIGHT])

    def test_sensor_stream_stops_quietly_on_ctrl_c(self):
        self.start_app({"sensor-stream": {"ok": True, "_lines": [self.READING], "_hold": True}})
        environ = {"PATH": os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
                   "POCKET_TOOLS": self.tools, "POCKET_TIMEOUT": "1"}
        proc = subprocess.Popen(["python3", POCKET, "sensor", "accelerometer", "--stream"], env=environ, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertIn("m/s²", proc.stdout.readline())
        proc.send_signal(signal.SIGINT)
        out, err = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, err), (0, ""))
        self.assertTrue(self.app.cancelled)

    def test_sensor_says_why_there_is_no_reading(self):
        self.start_app({"sensor": {"ok": False, "error": "the phone has no pressure sensor"}})
        run = self.pocket("sensor", "pressure")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pocket: the phone has no pressure sensor\n")

    def test_sensor_usage(self):
        usage = "usage: pocket sensor list | NAME [--timeout SECONDS] | NAME --stream [--rate HZ]"
        for args in [(), ("--stream",), ("light", "--fast"), ("light", "--timeout"), ("light", "--rate", "5"),
                     ("light", "--stream", "--timeout", "5"), ("light", "dark"), ("list", "x")]:
            run = self.pocket("sensor", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    # --- camera and torch ---------------------------------------------------

    def test_camera_saves_to_the_files_full_path(self):
        self.start_app({"camera": {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000}})
        run = self.pocket("camera", "a.jpg", cwd="/srv")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (2.3 MB)\n")
        self.assertEqual(self.app.requests, [["camera", "/srv/a.jpg"]])
        self.assertEqual(json.loads(self.pocket("camera", "/srv/a.jpg", "--json").stdout),
                         {"ok": True, "file": "/srv/a.jpg", "bytes": 2_400_000})

    def test_camera_quick_from_the_front_or_back(self):
        self.start_app({"camera-quick": {"ok": True, "file": "/srv/a.jpg", "bytes": 900_000}})
        run = self.pocket("camera", "--quick", "front", "/srv/a.jpg")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Saved /srv/a.jpg (879 KB)\n")
        self.assertEqual(self.pocket("camera", "/srv/a.jpg", "--quick", "back").returncode, 0)
        self.assertEqual(self.app.requests, [["camera-quick", "/srv/a.jpg", "front"],
                                             ["camera-quick", "/srv/a.jpg", "back"]])

    def test_camera_says_why_there_is_no_photo(self):
        self.start_app({"camera": {"ok": False, "error": "no photo was taken"}})
        run = self.pocket("camera", "/srv/a.jpg")
        self.assertEqual(run.returncode, 2)
        self.assertEqual(run.stderr, "pocket: no photo was taken\n")

    def test_camera_usage(self):
        usage = "usage: pocket camera FILE [--quick front|back]"
        for args in [(), ("a.jpg", "b.jpg"), ("a.jpg", "--quick"), ("a.jpg", "--fast"), ("--quick", "back")]:
            run = self.pocket("camera", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn(usage, run.stderr, args)

    def test_torch_on_at_a_strength_and_off(self):
        self.start_app({"torch": {"ok": True}})
        for args in [("on",), ("on", "40"), ("off",)]:
            run = self.pocket("torch", *args)
            self.assertEqual((run.returncode, run.stdout), (0, ""), run.stderr)
        self.assertEqual(self.app.requests, [["torch", "on"], ["torch", "on", "40"], ["torch", "off"]])

    def test_torch_usage(self):
        for args in [(), ("bright",), ("off", "5"), ("on", "5", "6")]:
            run = self.pocket("torch", *args)
            self.assertEqual(run.returncode, 2, args)
            self.assertIn("usage: pocket torch on [PERCENT] | off", run.stderr, args)

    # --- share --------------------------------------------------------------

    def test_share_sends_the_files_full_paths(self):
        self.start_app({"share": {"ok": True, "count": 2}})
        run = self.pocket("share", "a.jpg", "/srv/b c.txt", cwd=self.tmp.name)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 2 files: pick an app on the phone\n")
        self.assertEqual(self.app.requests, [["share", os.path.join(self.tmp.name, "a.jpg"), "/srv/b c.txt"]])

    def test_share_one_file(self):
        self.start_app({"share": {"ok": True, "count": 1}})
        self.assertEqual(self.pocket("share", "/a").stdout, "Sharing 1 file: pick an app on the phone\n")

    def test_share_text_from_words_or_stdin(self):
        self.start_app({"share-text": {"ok": True}})
        run = self.pocket("share", "--text", "50%", "off")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "Sharing 7 characters: pick an app on the phone\n")
        self.assertEqual(self.pocket("share", "--text", input="a\nb\n").returncode, 0)
        self.assertEqual(self.app.requests, [["share-text", "50%25%20off"], ["share-text", "a%0Ab%0A"]])

    def test_share_says_why_it_couldnt(self):
        self.start_app({"share": {"ok": False, "error": "no such file: /a"}})
        run = self.pocket("share", "/a")
        self.assertEqual(run.returncode, 2)
        self.assertIn("no such file: /a", run.stderr)

    def test_share_refuses_names_with_line_breaks(self):
        run = self.pocket("share", "/a\nb")
        self.assertEqual(run.returncode, 2)
        self.assertIn("can't share a file whose name has a line break", run.stderr)

    def test_share_needs_files_or_text(self):
        run = self.pocket("share")
        self.assertEqual(run.returncode, 2)
        self.assertIn("usage: pocket share FILE... | --text [TEXT]", run.stderr)

    def test_install_apk_says_why_it_failed(self):
        self.start_app({"install-apk": {"ok": False, "error": "only debug builds can install apps"}})
        run = self.pocket("install-apk", "/tmp/app.apk")
        self.assertEqual(run.returncode, 2)
        self.assertIn("only debug builds can install apps", run.stderr)

    def test_install_apk_needs_one_file(self):
        self.assertIn("usage: pocket install-apk FILE", self.pocket("install-apk").stderr)

    def test_install_apk_is_left_out_of_help(self):
        # A tool for developing the app, not for users.
        self.assertNotIn("install-apk", self.pocket("help").stdout)


if __name__ == "__main__":
    unittest.main()
