"""Tests for the editors' file models (tools/lib/pocket_terminal/models.py)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools", "lib"))

from pocket_terminal.models import ItemsFile, set_color  # noqa: E402


class ItemsFileTest(unittest.TestCase):
    TEXT = ("# A key bar.\n"
            "# Format: see AGENTS.md.\n"
            "Open   = l\n"
            "\n"
            "# deleting\n"
            "Delete = x Repeat\n"
            "Quit = q\n")

    def test_reads_items_and_keeps_comments(self):
        f = ItemsFile(self.TEXT)
        self.assertEqual([(i.label, i.value) for i in f.items], [("Open", "l"), ("Delete", "x Repeat"), ("Quit", "q")])
        self.assertEqual(f.render(), self.TEXT.replace("Quit = q", "Quit   = q"))

    def test_lines_that_arent_items_are_kept_as_comments(self):
        f = ItemsFile("Quit = q\noops\n")
        self.assertEqual(len(f.items), 1)
        self.assertEqual(f.render(), "Quit = q\noops\n")

    def test_add_after_delete_and_move(self):
        f = ItemsFile("A = a\nB = b\nC = c\n")
        f.add(0, "New", "n")
        self.assertEqual([i.label for i in f.items], ["A", "New", "B", "C"])
        f.delete(2)
        self.assertEqual([i.label for i in f.items], ["A", "New", "C"])
        self.assertEqual(f.move(1, -1), 0)
        self.assertEqual([i.label for i in f.items], ["New", "A", "C"])
        self.assertEqual(f.move(0, -1), 0)  # already first
        self.assertEqual(f.move(2, 1), 2)  # already last
        self.assertEqual(f.render(), "New = n\nA   = a\nC   = c\n")

    def test_comments_move_with_their_item(self):
        f = ItemsFile("A = a\n# about B\nB = b\n")
        f.move(1, -1)
        self.assertEqual(f.render(), "# about B\nB = b\nA = a\n")

    def test_long_labels_dont_stretch_the_alignment(self):
        f = ItemsFile("Ab = a\nB = b\nA very long label = c\n")
        self.assertEqual(f.render(), "Ab = a\nB  = b\nA very long label = c\n")

    def test_empty_file(self):
        f = ItemsFile("")
        f.add(-1, "Quit", "q")
        self.assertEqual(f.render(), "Quit = q\n")


class SetColorTest(unittest.TestCase):
    def test_replaces_a_colour_and_keeps_the_rest(self):
        self.assertEqual(set_color("# t\nbackground=#000000\ncolor1=#ff0000\n", "background", "#112233"),
                         "# t\nbackground=#112233\ncolor1=#ff0000\n")

    def test_adds_a_missing_colour(self):
        self.assertEqual(set_color("background=#000000", "cursor", "#ffffff"),
                         "background=#000000\ncursor=#ffffff\n")


if __name__ == "__main__":
    unittest.main()
