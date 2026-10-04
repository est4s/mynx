"""The `pocket` command. `pocket help` lists the commands."""
import json
import os
import shutil
import subprocess
import sys

from .client import TOOLS, Failure, request, tools_version

HELP = """\
usage: pocket COMMAND [ARGS] [--json]

Commands:
  check      apply config changes now and list problems in them
  settings   list the settings: font size, font, cursor
  get        get KEY: one setting's value
  set        set KEY VALUE: change a setting (applies at once)
  reset      reset KEY|all: settings back to their defaults
  theme      theme [list] | show NAME | set NAME | reset: colour themes
  keybar     keybar [list] | show NAME | edit NAME | reset NAME
  menu       menu [show] | edit | reset: the launcher menu's items
  edit       edit [settings|theme|keybars|menu]: the settings editors
  version    the app tools' version
  help       this list

--json prints the answer as JSON: {"ok": true, ...} or
{"ok": false, "error": "..."}. Exit codes: 0 fine, 1 problems found,
2 error.

Settings live in ~/.config/pocket-terminal/ (see ~/AGENTS.md).
"""


class Usage(Failure):
    pass


def out(as_json, answer, text):
    """Prints the answer as JSON, or [text] for people."""
    if as_json:
        print(json.dumps(answer))
    elif text:
        print(text)


def config_dir():
    return os.path.join(os.path.expanduser("~"), ".config", "pocket-terminal")


def user_menu():
    return os.path.join(config_dir(), "menu.conf")


def menu_problems():
    """The menu reads its own file, so it checks it too (menu --check)."""
    if not os.path.isfile(user_menu()):
        return []
    run = subprocess.run([os.path.join(TOOLS, "bin", "menu"), "--check", user_menu()],
                         capture_output=True, text=True)
    problems = run.stdout.splitlines()
    if run.returncode != 0 and not problems:
        problems = [run.stderr.strip() or "menu --check failed"]
    return [{"file": "~/.config/pocket-terminal/menu.conf", "problems": problems}] if problems else []


def cmd_check(args, as_json):
    answer = request("check")
    answer["problems"] += menu_problems()
    lines = []
    for file in answer["problems"]:
        lines.append(file["file"])
        lines += ["  " + p for p in file["problems"]]
    out(as_json, answer, "\n".join(lines) or "No problems in ~/.config/pocket-terminal")
    return 1 if answer["problems"] else 0


def cmd_settings(args, as_json):
    answer = request("settings")
    lines = []
    for s in answer["settings"]:
        extra = ", ".join(s["choices"]) if s["choices"] else f"default {s['default']}"
        lines += [f"{s['key']} = {s['value']}  ({extra})", "  " + s["description"]]
    lines += [f"settings.conf: {p}" for p in answer["problems"]]
    out(as_json, answer, "\n".join(lines))
    return 1 if answer["problems"] else 0


def cmd_get(args, as_json):
    if len(args) != 1:
        raise Usage("usage: pocket get KEY")
    for s in request("settings")["settings"]:
        if s["key"] == args[0]:
            out(as_json, {"ok": True, "key": s["key"], "value": s["value"]}, s["value"])
            return 0
    raise Failure(f"unknown setting '{args[0]}' (pocket settings lists them)")


def cmd_set(args, as_json):
    if len(args) < 2:
        raise Usage("usage: pocket set KEY VALUE")
    answer = request("set", args[0], " ".join(args[1:]))
    out(as_json, answer, f"{answer['key']} = {answer['value']}")
    return 0


def cmd_reset(args, as_json):
    if len(args) != 1:
        raise Usage("usage: pocket reset KEY|all")
    answer = request("reset", args[0])
    if args[0] == "all":
        out(as_json, answer, "All settings back to their defaults")
    else:
        out(as_json, answer, f"{answer['key']} = {answer['value']} (default)")
    return 0


def cmd_theme(args, as_json):
    sub = args[0] if args else "list"
    if sub == "list" and len(args) <= 1:
        answer = request("themes")
        lines = []
        for t in answer["themes"]:
            mark = "*" if t["name"] == answer["current"] else " "
            where = "" if t["source"] == "built-in" else f"  ({t['source']})"
            lines.append(f"{mark} {t['name']}{where}")
        out(as_json, answer, "\n".join(lines))
        return 0
    if sub == "show" and len(args) == 2:
        answer = request("theme-show", args[1])
        if as_json:
            out(as_json, answer, None)
        else:
            sys.stdout.write(answer["text"])
        return 0
    if sub == "set" and len(args) == 2:
        answer = request("theme-set", args[1])
        out(as_json, answer, f"Theme: {answer['name']}")
        return 0
    if sub == "reset" and len(args) == 1:
        answer = request("theme-reset")
        out(as_json, answer, f"Theme: {answer['name']} (the default)")
        return 0
    raise Usage("usage: pocket theme [list] | show NAME | set NAME | reset")


def cmd_keybar(args, as_json):
    sub = args[0] if args else "list"
    if sub == "list" and len(args) <= 1:
        answer = request("keybars")
        bars = answer["keybars"]
        width = max((len(b["name"]) for b in bars), default=0)
        lines = []
        for b in bars:
            kind = "built-in" if not b["file"] else "edited" if b["builtIn"] else "yours"
            lines.append(f"{b['name']:<{width}}  {kind:<8} {b['file'] or ''}".rstrip())
        out(as_json, answer, "\n".join(lines))
        return 0
    if sub == "show" and len(args) == 2:
        answer = request("keybar-show", args[1])
        if as_json:
            out(as_json, answer, None)
        else:
            sys.stdout.write(answer["text"])
        return 0
    if sub == "edit" and len(args) == 2:
        answer = request("keybar-edit", args[1])
        out(as_json, answer, f"Edit {answer['file']}, then run pocket check.\n"
                             f"(pocket edit keybars is an editor for it.)")
        return 0
    if sub == "reset" and len(args) == 2:
        answer = request("keybar-reset", args[1])
        out(as_json, answer, f"{args[1]}: back to the built-in bar")
        return 0
    raise Usage("usage: pocket keybar [list] | show NAME | edit NAME | reset NAME")


def cmd_menu(args, as_json):
    sub = args[0] if args else "show"
    built_in = os.path.join(TOOLS, "menu.conf")
    if sub == "show" and len(args) <= 1:
        mine = os.path.isfile(user_menu())
        with open(user_menu() if mine else built_in) as f:
            text = f.read()
        file = "~/.config/pocket-terminal/menu.conf" if mine else None
        if as_json:
            out(as_json, {"ok": True, "file": file, "text": text}, None)
        else:
            sys.stdout.write(text)
        return 0
    if sub == "edit" and len(args) == 1:
        if not os.path.isfile(user_menu()):
            os.makedirs(config_dir(), exist_ok=True)
            shutil.copyfile(built_in, user_menu())
        out(as_json, {"ok": True, "file": "~/.config/pocket-terminal/menu.conf"},
            "Edit ~/.config/pocket-terminal/menu.conf, then run pocket check.\n"
            "(pocket edit menu is an editor for it.)")
        return 0
    if sub == "reset" and len(args) == 1:
        if not os.path.isfile(user_menu()):
            raise Failure("the menu is already the built-in menu")
        os.remove(user_menu())
        out(as_json, {"ok": True}, "Menu: back to the built-in one")
        return 0
    raise Usage("usage: pocket menu [show] | edit | reset")


def cmd_edit(args, as_json):
    from . import editors
    return editors.main(args)


def cmd_version(args, as_json):
    version = tools_version()
    out(as_json, {"ok": True, "version": version}, version)
    return 0


def cmd_help(args, as_json):
    print(HELP, end="")
    return 0


COMMANDS = {
    "check": cmd_check, "settings": cmd_settings, "get": cmd_get, "set": cmd_set, "reset": cmd_reset,
    "theme": cmd_theme, "keybar": cmd_keybar, "menu": cmd_menu, "edit": cmd_edit,
    "version": cmd_version, "help": cmd_help,
}


def main(argv):
    as_json = "--json" in argv
    args = [a for a in argv if a != "--json"]
    name = args[0] if args else "help"
    if name in ("-h", "--help"):
        name = "help"
    try:
        if name not in COMMANDS:
            raise Failure(f"unknown command '{name}' (pocket help lists them)")
        return COMMANDS[name](args[1:], as_json)
    except Failure as e:
        if as_json:
            print(json.dumps({"ok": False, "error": str(e)}))
        else:
            print(f"pocket: {e}", file=sys.stderr)
        return 2
