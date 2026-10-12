"""Tests for the release notes (tools/lib/mynx/notes.py)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))

from mynx.notes import format_notes, newer, render, version_key  # noqa: E402

GENERATED = ("## What's Changed\n"
             "* Volume keys: a key bar's VolumeUp/VolumeDown lines by @est4s in "
             "https://github.com/est4s/mynx/pull/25\n"
             "* Claude Code: messaging socket and fullscreen at every start by @est4s in "
             "https://github.com/est4s/mynx/pull/18\n"
             "\n"
             "## New Contributors\n"
             "* @someone made their first contribution in https://github.com/est4s/mynx/pull/3\n"
             "\n\n"
             "**Full Changelog**: https://github.com/est4s/mynx/compare/v0.1.0...v0.1.1")


def release(version, notes="", published="2026-10-10T17:04:09Z"):
    return {"version": version, "published": published, "notes": notes}


class VersionTest(unittest.TestCase):
    def test_orders_versions_by_number_with_pre_releases_first(self):
        versions = ["0.10.0", "0.2.0", "0.2.0-beta.1", "0.1.1", "1.0", "0.2.0-alpha"]
        self.assertEqual(sorted(versions, key=version_key),
                         ["0.1.1", "0.2.0-alpha", "0.2.0-beta.1", "0.2.0", "0.10.0", "1.0"])

    def test_something_else_than_a_version_has_no_key(self):
        self.assertIsNone(version_key("nightly"))
        self.assertIsNone(version_key(None))


class NewerTest(unittest.TestCase):
    RELEASES = [release("0.3.0"), release("0.2.0"), release("0.1.1"), release("0.1.0")]

    def versions(self, seen, current):
        return [r["version"] for r in newer(self.RELEASES, seen, current)]

    def test_the_releases_after_the_one_seen_up_to_this_one_newest_first(self):
        self.assertEqual(self.versions("0.1.0", "0.2.0"), ["0.2.0", "0.1.1"])

    def test_nothing_when_this_version_was_seen(self):
        self.assertEqual(self.versions("0.2.0", "0.2.0"), [])

    def test_nothing_seen_yet_gives_this_versions_notes(self):
        self.assertEqual(self.versions(None, "0.2.0"), ["0.2.0"])

    def test_nothing_for_a_build_that_isnt_a_release(self):
        self.assertEqual(self.versions("0.1.0", "0.0.1"), [])
        self.assertEqual(self.versions(None, None), [])

    def test_a_seen_value_that_isnt_a_version_counts_as_nothing_seen(self):
        self.assertEqual(self.versions("junk", "0.2.0"), ["0.2.0"])


class FormatTest(unittest.TestCase):
    def test_githubs_generated_notes_read_well_in_a_terminal(self):
        self.assertEqual(format_notes(GENERATED, 56), [
            "What's Changed",
            "• Volume keys: a key bar's VolumeUp/VolumeDown lines",
            "  (#25)",
            "• Claude Code: messaging socket and fullscreen at every",
            "  start (#18)",
            "",
            "New Contributors",
            "• @someone made their first contribution (#3)",
            "",
            "Full Changelog:",
            "https://github.com/est4s/mynx/compare/v0.1.0...v0.1.1",
        ])

    def test_handwritten_notes_are_wrapped_and_keep_their_paragraphs(self):
        text = ("A **big** release.\r\n\r\n- One thing that is new and rather long to describe in a line\r\n"
                "- Another")
        self.assertEqual(format_notes(text, 30), [
            "A big release.",
            "",
            "• One thing that is new and",
            "  rather long to describe in a",
            "  line",
            "• Another",
        ])

    def test_render_heads_each_release_with_its_version_and_date(self):
        text = render([release("0.1.1", "Fixes."), release("0.1.0", "", "2026-10-09T08:00:00Z")], 56)
        self.assertEqual(text, "mynx 0.1.1 · 2026-10-10\n"
                               "\n"
                               "Fixes.\n"
                               "\n"
                               "mynx 0.1.0 · 2026-10-09\n"
                               "\n"
                               "(no notes)\n")


if __name__ == "__main__":
    unittest.main()
