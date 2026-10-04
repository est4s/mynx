"""The settings editors: `pocket edit [settings|theme|keybars [NAME]|menu]`.

Terminal programs on purpose: everything they change goes through the
same requests and files as the `pocket` commands, so an agent can do
whatever a person does here. Keys: ↑ ↓ (or j k) move, Enter opens or
changes, ← → change a value, q or Esc goes back; the app shows the
`pocket-edit` key bar while an editor is open.
"""
import curses
import locale
import os
import re
import shutil
from contextlib import contextmanager

from .client import TOOLS, Failure, request
from .models import ItemsFile, set_color

KEY_BAR = "pocket-edit"
HEX = re.compile(r"#[0-9a-fA-F]{6}")
COLOR_KEYS = ["background", "foreground", "cursor"] + [f"color{i}" for i in range(16)]
MENU_ACTIONS = ["shell", "files", "games", "settings", "system", "exit"]
NUMBER_STEPS = {"font-size": 1, "agent-notify-after": 5}  # ←→ change these by this much
FONT_DIRS = ["/usr/share/fonts", "/usr/local/share/fonts", "~/.fonts", "~/.local/share/fonts",
             "~/.config/pocket-terminal/fonts"]


def main(args):
    editors = {
        "settings": settings_editor, "font": settings_editor, "theme": theme_editor,
        "keybars": keybars_editor, "keybar": keybars_editor, "menu": menu_editor,
    }
    which = args[0] if args else None
    if which is not None and which not in editors:
        raise Failure("usage: pocket edit [settings|theme|keybars [NAME]|menu]")
    editor = editors.get(which, hub)
    os.environ.setdefault("ESCDELAY", "25")  # Esc alone shouldn't wait a second
    locale.setlocale(locale.LC_ALL, "")
    with key_bar(KEY_BAR):
        curses.wrapper(lambda scr: editor(UI(scr), *args[1:]))
    return 0


@contextmanager
def key_bar(name):
    """Shows the app's key bar [name] while the editor runs, like `keybar`."""
    file = os.environ.get("POCKET_KEYBAR_FILE")
    previous = None
    if file:
        try:
            with open(file) as f:
                previous = f.read()
        except OSError:
            previous = ""
        write_quietly(file, name)
    try:
        yield
    finally:
        if file:
            write_quietly(file, previous)


def write_quietly(path, text):
    try:
        with open(path, "w") as f:
            f.write(text)
    except OSError:
        pass


# --- screen ------------------------------------------------------------------

class UI:
    TITLE, ITEM, DIM, SEL, OK, BAD = range(1, 7)
    SWATCH = 20  # pairs 20-35: background colours 0-15

    def __init__(self, scr):
        self.scr = scr
        self.status = ""
        curses.curs_set(0)
        curses.use_default_colors()
        bright = curses.COLORS >= 16
        for pair, color in [(self.TITLE, 13), (self.ITEM, 14), (self.DIM, 8), (self.OK, 10), (self.BAD, 9)]:
            curses.init_pair(pair, color if bright else color - 8, -1)
        curses.init_pair(self.SEL, 0, 13 if bright else 5)
        for i in range(min(16, curses.COLORS)):
            curses.init_pair(self.SWATCH + i, -1, i)

    def key(self):
        """The next key, with arrows and the like as names."""
        k = self.scr.get_wch()
        named = {
            curses.KEY_UP: "up", curses.KEY_DOWN: "down", curses.KEY_LEFT: "left",
            curses.KEY_RIGHT: "right", curses.KEY_ENTER: "enter", curses.KEY_RESIZE: "resize",
            curses.KEY_BACKSPACE: "backspace", curses.KEY_DC: "delete", curses.KEY_HOME: "home",
            curses.KEY_END: "end", curses.KEY_PPAGE: "pgup", curses.KEY_NPAGE: "pgdn",
        }
        if isinstance(k, int):
            return named.get(k, "")
        return {"\n": "enter", "\r": "enter", "\x1b": "esc", "\x7f": "backspace", "\b": "backspace"}.get(k, k)

    def put(self, y, x, text, attr=0):
        h, w = self.scr.getmaxyx()
        if 0 <= y < h and x < w:
            try:
                self.scr.addnstr(y, x, text, max(0, w - x - 1), attr)
            except curses.error:
                pass  # the bottom-right cell

    def frame(self, title, crumb, hint):
        self.scr.erase()
        self.put(0, 1, title, curses.color_pair(self.TITLE) | curses.A_BOLD)
        if crumb:
            self.put(1, 1, crumb, curses.color_pair(self.DIM))
        h, _ = self.scr.getmaxyx()
        self.put(h - 1, 1, hint, curses.color_pair(self.DIM))
        if self.status:
            ok = not self.status.startswith("!")
            self.put(h - 2, 1, self.status.lstrip("!"), curses.color_pair(self.OK if ok else self.BAD))

    def list(self, title, crumb, rows, sel, hint, keys=""):
        """Shows [rows] (text, or (text, swatch colour)) with [sel] chosen;
        returns (key, sel) for Enter, back, or a key in [keys]. ←/→ come
        back too when in [keys] as "left"/"right"."""
        top = 0
        while True:
            h, w = self.scr.getmaxyx()
            room = max(1, h - 6)
            sel = min(max(sel, 0), max(len(rows) - 1, 0))
            top = min(max(top, sel - room + 1), sel)
            self.frame(title, crumb, hint)
            for i, row in enumerate(rows[top:top + room]):
                text, swatch = row if isinstance(row, tuple) else (row, None)
                y = 3 + i
                chosen = top + i == sel
                attr = curses.color_pair(self.SEL) | curses.A_BOLD if chosen else curses.color_pair(self.ITEM)
                self.put(y, 1, ("▶ " if chosen else "  ") + text.ljust(w - 8)[:w - 8], attr)
                if swatch is not None:
                    self.put(y, w - 5, "   ", curses.color_pair(self.SWATCH + swatch))
            self.scr.refresh()
            k = self.key()
            if k in keys.split():
                return k, sel
            if k in ("up", "k"):
                sel = (sel - 1) % max(len(rows), 1)
            elif k in ("down", "j"):
                sel = (sel + 1) % max(len(rows), 1)
            elif k == "pgup":
                sel = max(sel - room, 0)
            elif k == "pgdn":
                sel = min(sel + room, len(rows) - 1)
            elif k in ("enter", "esc", "q", "left"):
                self.status = "" if k in ("esc", "q", "left") else self.status
                return ("back" if k in ("esc", "q", "left") else k), sel
            elif k == "right":
                return "enter", sel

    def prompt(self, title, label, initial=""):
        """A line of text to type; None if cancelled with Esc."""
        text = list(initial)
        pos = len(text)
        curses.curs_set(1)
        try:
            while True:
                self.frame(title, label, "Enter: OK   Esc: cancel")
                _, w = self.scr.getmaxyx()
                shown = "".join(text)
                start = max(0, pos - (w - 6))
                self.put(3, 1, "> " + shown[start:], curses.color_pair(self.ITEM))
                self.scr.move(3, 3 + pos - start)
                self.scr.refresh()
                k = self.key()
                if k == "enter":
                    return "".join(text)
                if k == "esc":
                    return None
                if k == "backspace" and pos > 0:
                    pos -= 1
                    del text[pos]
                elif k == "delete" and pos < len(text):
                    del text[pos]
                elif k == "left":
                    pos = max(pos - 1, 0)
                elif k == "right":
                    pos = min(pos + 1, len(text))
                elif k == "home":
                    pos = 0
                elif k == "end":
                    pos = len(text)
                elif len(k) == 1 and k.isprintable():
                    text.insert(pos, k)
                    pos += 1
        finally:
            curses.curs_set(0)

    def pick(self, title, label, options, sel=0):
        """One of [options]; None if cancelled."""
        key, sel = self.list(title, label, options, sel, "↑↓ move  Enter: pick  q: cancel")
        return None if key == "back" else sel

    def confirm(self, question):
        self.frame(question, "", "y: yes   any other key: no")
        self.scr.refresh()
        return self.key() in ("y", "Y")

    def message(self, title, lines):
        self.frame(title, "", "any key: back")
        for i, line in enumerate(lines):
            self.put(3 + i, 1, line, curses.color_pair(self.ITEM))
        self.scr.refresh()
        self.key()


def attempt(ui, action):
    """Runs [action], showing a refusal from the app in the status line."""
    try:
        return action()
    except Failure as e:
        ui.status = "!" + str(e)
        return None


# --- the hub -------------------------------------------------------------------

def hub(ui):
    items = [("Theme", theme_editor), ("Settings", settings_editor), ("Key bars", keybars_editor),
             ("Launcher menu", menu_editor), ("Check config", check_screen)]
    sel = 0
    while True:
        key, sel = ui.list("Settings", "Everything here is also a pocket command.",
                           [label for label, _ in items], sel, "↑↓ move  Enter: open  q: quit")
        if key == "back":
            return
        attempt(ui, lambda: items[sel][1](ui))


def check_screen(ui):
    from .cli import menu_problems
    answer = request("check")
    lines = []
    for file in answer["problems"] + menu_problems():
        lines.append(file["file"])
        lines += ["  " + p for p in file["problems"]]
    ui.message("Check config", lines or ["No problems in ~/.config/pocket-terminal"])


# --- settings --------------------------------------------------------------------

def settings_editor(ui):
    sel = 0
    while True:
        settings = request("settings")["settings"]
        width = max(len(s["key"]) for s in settings) + 2
        rows = [f"{s['key']:<{width}}{s['value']}" for s in settings] + ["Reset all to defaults"]
        crumb = settings[sel]["description"] if sel < len(settings) else "Every setting back to its default."
        key, sel = ui.list("Settings", crumb, rows, sel, "←→ change  Enter: pick  r: reset  q: back",
                           "left right r")
        if key == "back":
            return
        if sel == len(settings):
            if key in ("enter", "r") and ui.confirm("Reset all settings to their defaults?"):
                if attempt(ui, lambda: request("reset", "all")):
                    ui.status = "All settings back to their defaults"
            continue
        s = settings[sel]
        if key == "r":
            if attempt(ui, lambda: request("reset", s["key"])):
                ui.status = f"{s['key']} = {s['default']} (default)"
            continue
        value = None
        if s["choices"]:
            step = -1 if key == "left" else 1
            value = s["choices"][(s["choices"].index(s["value"]) + step) % len(s["choices"])]
        elif s["key"] in NUMBER_STEPS:
            if key == "enter":
                value = ui.prompt(s["key"], s["description"], s["value"])
            else:
                value = str(int(s["value"]) + NUMBER_STEPS[s["key"]] * (-1 if key == "left" else 1))
        elif s["key"] == "font":
            fonts = ["default"] + find_fonts()
            i = ui.pick("Font", "Fonts found in Debian (apt install fonts-…)", fonts,
                        fonts.index(s["value"]) if s["value"] in fonts else 0)
            value = None if i is None else fonts[i]
        else:
            value = ui.prompt(s["key"], s["description"], s["value"])
        if value is not None and value != s["value"]:
            if attempt(ui, lambda: request("set", s["key"], value)):
                ui.status = f"{s['key']} = {value}"


def find_fonts():
    found = []
    for folder in FONT_DIRS:
        for root, _, files in os.walk(os.path.expanduser(folder)):
            found += [os.path.join(root, f) for f in files if f.lower().endswith((".ttf", ".otf"))]
    return sorted(found)[:300]


# --- theme -------------------------------------------------------------------------

def theme_editor(ui):
    answer = request("themes")
    names = [t["name"] for t in answer["themes"]]
    current = answer["current"]
    texts = {}
    previewing = None  # the theme shown but not set

    def text_of(name):
        if name not in texts:
            texts[name] = request("theme-show", name)["text"]
        return texts[name]

    def preview(name):
        nonlocal previewing
        if name != previewing:
            attempt(ui, lambda: request("preview-colors", *text_of(name).splitlines()))
            previewing = name

    sel = names.index(current) if current in names else 0
    shown = current
    while True:
        rows = [("* " if n == current else "  ") + n for n in names] + ["  Edit colours…"]
        key, sel = ui.list("Theme", "Moving previews a theme; Enter keeps it.", rows, sel,
                           "↑↓ preview  Enter: use  r: reset  q: back", "up down k j r")
        if key == "r":
            if ui.confirm("Back to the default theme? Colour changes are lost."):
                answer = attempt(ui, lambda: request("theme-reset"))
                if answer:
                    current = shown = previewing = answer["name"]
                    ui.status = f"Theme: {current} (the default)"
            continue
        if key in ("up", "k", "down", "j"):
            sel = (sel + (-1 if key in ("up", "k") else 1)) % len(rows)
            if sel < len(names):
                preview(names[sel])
                shown = names[sel]
            continue
        if key == "back":
            if shown != current:
                attempt(ui, lambda: request("preview-end"))
            return
        if sel == len(names):
            if shown != current:
                attempt(ui, lambda: request("preview-end"))
                shown = previewing = current
            colours_editor(ui)
            current = request("themes")["current"]
            shown = previewing = current
            continue
        if attempt(ui, lambda: request("theme-set", names[sel])):
            current = shown = previewing = names[sel]
            ui.status = f"Theme: {current}"


def colours_editor(ui):
    path = os.path.expanduser("~/.config/pocket-terminal/colors.properties")
    try:
        with open(path) as f:
            saved = f.read()
    except FileNotFoundError:
        saved = request("theme-show", "neon")["text"]
    draft = saved
    sel = 0
    while True:
        values = dict(re.findall(r"^\s*([a-z0-9]+)\s*=\s*(\S+)", draft, re.M))
        rows = [(f"{k:<12}{values.get(k, '(Neon)')}", int(k[5:]) if k.startswith("color") else None)
                for k in COLOR_KEYS]
        key, sel = ui.list("Colours", "~/.config/pocket-terminal/colors.properties", rows, sel,
                           "Enter: change  r: theme's colour  s: save", "s r")
        if key == "back":
            if draft != saved and not ui.confirm("Discard unsaved colours?"):
                continue
            if draft != saved:
                attempt(ui, lambda: request("preview-end"))
            return
        if key == "s":
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(draft)
            saved = draft
            problems = attempt(ui, lambda: request("check"))
            ui.status = "Saved" if problems is not None else ui.status
            continue
        name = COLOR_KEYS[sel]
        if key == "r":
            theme = re.match(r"#\s*theme:\s*(\S+)", draft)
            original = attempt(ui, lambda: request("theme-show", theme.group(1) if theme else "neon")["text"])
            value = dict(re.findall(r"^\s*([a-z0-9]+)\s*=\s*(\S+)", original or "", re.M)).get(name)
            if value:
                draft = set_color(draft, name, value)
                attempt(ui, lambda: request("preview-colors", *draft.splitlines()))
                ui.status = "Not saved yet: s saves"
            continue
        value = ui.prompt(name, "A colour as #rrggbb", values.get(name, ""))
        if value is None:
            continue
        if not HEX.fullmatch(value):
            ui.status = f"!'{value}' is not a #rrggbb colour"
            continue
        draft = set_color(draft, name, value)
        attempt(ui, lambda: request("preview-colors", *draft.splitlines()))
        ui.status = "Not saved yet: s saves"


# --- key bars ------------------------------------------------------------------------

def keybars_editor(ui, name=None):
    if name:
        return bar_editor(ui, name)
    sel = 0
    while True:
        bars = request("keybars")["keybars"]
        width = max(len(b["name"]) for b in bars) + 2
        rows = [f"{b['name']:<{width}}{kind_of(b)}" for b in bars] + ["  New bar…"]
        key, sel = ui.list("Key bars", "Enter edits a bar; r puts a built-in one back.", rows, sel,
                           "Enter: edit  r: reset  q: back", "r")
        if key == "back":
            return
        if sel == len(bars):
            new = ui.prompt("New key bar", "Name (letters, digits, - _): keybar NAME program")
            if new and attempt(ui, lambda: request("keybar-edit", new)):
                bar_editor(ui, new)
            continue
        bar = bars[sel]
        if key == "r":
            if kind_of(bar) == "edited" and ui.confirm(f"Put the built-in {bar['name']} bar back?"):
                attempt(ui, lambda: request("keybar-reset", bar["name"]))
            elif kind_of(bar) != "edited":
                ui.status = "!Only edited built-in bars can be reset"
            continue
        bar_editor(ui, bar["name"])


def kind_of(bar):
    return "built-in" if not bar["file"] else "edited" if bar["builtIn"] else "yours"


def bar_editor(ui, name):
    text = request("keybar-show", name)["text"]

    def save(rendered):
        path = os.path.expanduser(request("keybar-edit", name)["file"])
        with open(path, "w") as f:
            f.write(rendered)
        mine = f"~/.config/pocket-terminal/keybars/{name}.conf"
        return [p for f in request("check")["problems"] if f["file"] == mine for p in f["problems"]]

    def keys_for(current):
        return ui.prompt("Keys", 'e.g. q   Ctrl+R   Up   "text" Enter   (see ~/AGENTS.md)', current)

    def reset(ui):
        if not ui.confirm(f"Put the built-in {name} bar back?"):
            return False
        return attempt(ui, lambda: request("keybar-reset", name)) is not None

    items_editor(ui, f"Key bar: {name}", text, keys_for, save, reset)


# --- launcher menu ---------------------------------------------------------------------

def menu_editor(ui):
    from .cli import menu_problems, user_menu
    mine = user_menu()
    with open(mine if os.path.isfile(mine) else os.path.join(TOOLS, "menu.conf")) as f:
        text = f.read()

    def save(rendered):
        os.makedirs(os.path.dirname(mine), exist_ok=True)
        with open(mine, "w") as f:
            f.write(rendered)
        return [p for f in menu_problems() for p in f["problems"]]

    def action_for(current):
        options = MENU_ACTIONS + ["run a command…"]
        sel = options.index(current) if current in options else len(options) - 1 if current.startswith("run ") else 0
        i = ui.pick("Action", "What the item does", options, sel)
        if i is None:
            return None
        if i < len(MENU_ACTIONS):
            return MENU_ACTIONS[i]
        command = ui.prompt("Command", "Runs in bash, e.g. htop", current[4:] if current.startswith("run ") else "")
        return f"run {command}" if command else None

    items_editor(ui, "Launcher menu", text, action_for, save, reset=reset_menu if os.path.isfile(mine) else None)


def reset_menu(ui):
    from .cli import user_menu
    if ui.confirm("Put the built-in menu back?"):
        os.remove(user_menu())
        return True
    return False


# --- editing a "Label = value" file ------------------------------------------------------

def items_editor(ui, title, text, value_for, save, reset=None):
    """Edits key bar buttons or menu items in memory; s saves through
    [save], which returns the problems found in the saved file."""
    f = ItemsFile(text)
    saved = f.render()
    sel = 0
    hint = "Enter edit a add d del K J move s save" + (" r reset" if reset else "")
    while True:
        width = max((len(i.label) for i in f.items), default=0) + 2
        rows = [f"{i.label:<{width}}{i.value}" for i in f.items] or ["(empty: a adds one)"]
        key, sel = ui.list(title, "Not saved" if f.render() != saved else "", rows, sel, hint,
                           "a d K J s" + (" r" if reset else ""))
        if key == "back":
            if f.render() != saved and not ui.confirm("Discard unsaved changes?"):
                continue
            return
        if key == "r":
            if reset(ui):
                return
        elif key == "s":
            problems = attempt(ui, lambda: save(f.render()))
            if problems is not None:
                saved = f.render()
                ui.status = "Saved" if not problems else "!Saved, with problems"
                if problems:
                    ui.message("Problems (those lines are skipped)", problems)
        elif key == "a":
            label = ui.prompt("New item", "Label")
            if label:
                value = value_for("")
                if value:
                    sel = f.add(sel if f.items else -1, label, value)
        elif key == "d" and f.items:
            f.delete(sel)
        elif key in ("K", "J") and f.items:
            sel = f.move(sel, -1 if key == "K" else 1)
        elif key == "enter" and f.items:
            item = f.items[sel]
            label = ui.prompt("Label", "Enter keeps it", item.label)
            if label is None:
                continue
            value = value_for(item.value)
            if value is not None:
                item.label, item.value = label or item.label, value
