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
        return bin_dir + ":" + os.environ["PATH"]

    def agent(self, *args, stdin="", path=None):
        environ = {"PATH": path or os.environ["PATH"], "POCKET_REQUESTS": self.requests, "HOME": self.home,
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

    def test_agent_usage(self):
        for args in [("frob",), ("install", "skynet"), ("notify", "claude"), ("notify", "claude", "loud")]:
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


if __name__ == "__main__":
    unittest.main()
