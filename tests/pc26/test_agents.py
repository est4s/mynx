"""Tests for the AI agents' hook entries and install steps
(tools/lib/pc26/agents.py)."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))

from pc26.agents import (AGENTS, add_hooks, download_progress, has_hooks, install_steps,
                                    missing_steps,  # noqa: E402
                                    remove_hooks)
from pc26.client import Failure  # noqa: E402

CLAUDE = AGENTS["claude"]
HOOK = "/opt/pc26/bin/pocket hook claude"


def ours(command):
    return {"hooks": [{"type": "command", "command": command}]}


class HooksTest(unittest.TestCase):
    def test_adds_a_hook_for_each_event_to_a_new_file(self):
        config = json.loads(add_hooks(None, CLAUDE))
        self.assertEqual(config, {"hooks": {
            "UserPromptSubmit": [ours(HOOK)], "Stop": [ours(HOOK)], "Notification": [ours(HOOK)]}})

    def test_keeps_everything_else_in_the_file(self):
        text = json.dumps({"model": "opus", "hooks": {"Stop": [ours("say done")],
                                                      "PreToolUse": [{"matcher": "Bash", "hooks": []}]}})
        config = json.loads(add_hooks(text, CLAUDE))
        self.assertEqual(config["model"], "opus")
        self.assertEqual(config["hooks"]["Stop"], [ours("say done"), ours(HOOK)])
        self.assertEqual(config["hooks"]["PreToolUse"], [{"matcher": "Bash", "hooks": []}])

    def test_adding_twice_changes_nothing(self):
        once = add_hooks(None, CLAUDE)
        self.assertEqual(add_hooks(once, CLAUDE), once)

    def test_written_for_people_to_read(self):
        text = add_hooks('{"a": 1}', CLAUDE)
        self.assertTrue(text.startswith('{\n  "a": 1,\n'), text)
        self.assertTrue(text.endswith("}\n"))

    def test_removes_only_its_own_hooks(self):
        text = add_hooks(json.dumps({"theme": "dark", "hooks": {"Stop": [ours("say done")]}}), CLAUDE)
        self.assertEqual(json.loads(remove_hooks(text, CLAUDE)),
                         {"theme": "dark", "hooks": {"Stop": [ours("say done")]}})
        self.assertEqual(json.loads(remove_hooks(add_hooks(None, CLAUDE), CLAUDE)), {})

    def test_its_hook_sharing_a_group_with_others(self):
        text = json.dumps({"hooks": {"Stop": [{"hooks": [
            {"type": "command", "command": "say done"}, {"type": "command", "command": HOOK}]}]}})
        self.assertEqual(json.loads(remove_hooks(text, CLAUDE)), {"hooks": {"Stop": [ours("say done")]}})

    def test_knows_whether_they_are_set_up(self):
        self.assertFalse(has_hooks(None, CLAUDE))
        self.assertTrue(has_hooks(add_hooks(None, CLAUDE), CLAUDE))
        text = json.dumps({"hooks": {"Stop": [ours(HOOK)]}})
        self.assertFalse(has_hooks(text, CLAUDE))  # only some events

    def test_recognises_its_hook_by_any_path(self):
        text = json.dumps({"hooks": {e: [ours("pocket hook claude")] for e in CLAUDE.events}})
        self.assertTrue(has_hooks(text, CLAUDE))
        self.assertEqual(json.loads(remove_hooks(text, CLAUDE)), {})

    def test_refuses_a_file_it_cant_read(self):
        for text in ["{oops", "[1, 2]", '{"hooks": []}', '{"hooks": {"Stop": {}}}']:
            with self.assertRaises(Failure, msg=text):
                add_hooks(text, CLAUDE)

    def test_each_agent_hooks_its_own_events(self):
        self.assertEqual(list(AGENTS), ["claude", "codex", "gemini"])
        self.assertEqual(AGENTS["codex"].config, "~/.codex/hooks.json")
        self.assertEqual(list(AGENTS["codex"].events), ["UserPromptSubmit", "Stop", "PermissionRequest"])
        self.assertEqual(AGENTS["gemini"].config, "~/.gemini/settings.json")
        self.assertEqual(list(AGENTS["gemini"].events), ["BeforeAgent", "AfterAgent", "Notification"])


class InstallStepsTest(unittest.TestCase):
    def test_official_installers(self):
        self.assertEqual(install_steps(AGENTS["claude"], curl=True, node=None),
                         ["curl -fsSL https://claude.ai/install.sh | bash"])
        self.assertEqual(install_steps(AGENTS["codex"], curl=True, node=None),
                         ["curl -fsSL https://chatgpt.com/codex/install.sh | sh"])
        self.assertEqual(install_steps(AGENTS["gemini"], curl=False, node=22),
                         ["npm install -g @google/gemini-cli"])

    def test_gets_curl_first_when_missing(self):
        self.assertEqual(install_steps(AGENTS["claude"], curl=False, node=None)[0],
                         "apt-get update && apt-get install -y curl ca-certificates")

    def test_codex_needs_ps(self):
        # Codex runs ps to keep track of its background server.
        self.assertEqual(install_steps(AGENTS["codex"], curl=True, node=None, ps=False),
                         ["apt-get update && apt-get install -y procps",
                          "curl -fsSL https://chatgpt.com/codex/install.sh | sh"])
        self.assertEqual(install_steps(AGENTS["codex"], curl=False, node=None, ps=False)[0],
                         "apt-get update && apt-get install -y curl ca-certificates procps")
        self.assertEqual(install_steps(AGENTS["claude"], curl=True, node=None, ps=False),
                         ["curl -fsSL https://claude.ai/install.sh | bash"])

    def test_what_an_installed_agent_still_needs(self):
        self.assertEqual(missing_steps(AGENTS["codex"], ps=False),
                         ["apt-get update && apt-get install -y procps"])
        self.assertEqual(missing_steps(AGENTS["codex"], ps=True), [])
        self.assertEqual(missing_steps(AGENTS["claude"], ps=False), [])

    def test_gets_node_for_gemini_when_missing_or_too_old(self):
        for node in [None, 18]:
            self.assertEqual(install_steps(AGENTS["gemini"], curl=True, node=node),
                             ["apt-get update && apt-get install -y nodejs npm",
                              "npm install -g @google/gemini-cli"])


class DownloadProgressTest(unittest.TestCase):
    """The official installers download silently; pocket shows what has arrived."""

    def test_shows_the_size_while_it_grows(self):
        self.assertEqual(download_progress("Claude Code", 0, 87_400_000, showing=False),
                         ("\r\x1b[K  Downloading Claude Code: 87 MB", True))
        self.assertEqual(download_progress("Claude Code", 87_400_000, 120_000_000, showing=True),
                         ("\r\x1b[K  Downloading Claude Code: 120 MB", True))

    def test_clears_the_line_when_it_stops_growing(self):
        # So the installer's own output starts on a clean line.
        self.assertEqual(download_progress("Claude Code", 5, 5, showing=True), ("\r\x1b[K", False))
        self.assertEqual(download_progress("Claude Code", 5, 0, showing=True), ("\r\x1b[K", False))

    def test_says_nothing_before_anything_arrives(self):
        self.assertEqual(download_progress("Claude Code", 0, 0, showing=False), ("", False))
        self.assertEqual(download_progress("Claude Code", 5, 5, showing=False), ("", False))

    def test_claude_downloads_into_its_own_folder(self):
        self.assertEqual(AGENTS["claude"].downloads, "~/.claude/downloads")
        self.assertIsNone(AGENTS["codex"].downloads)


if __name__ == "__main__":
    unittest.main()
