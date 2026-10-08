"""The `mynx` command. `mynx help` lists the commands."""
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
import urllib.parse

from . import agents
from .client import TOOLS, Failure, read, record, request, tools_version

HELP = """\
usage: mynx COMMAND [ARGS] [--json]

Commands:
  check      apply config changes now and list problems in them
  settings   list the settings: font size, font, cursor
  get        get KEY: one setting's value
  set        set KEY VALUE: change a setting (applies at once)
  reset      reset KEY|all: settings back to their defaults
  theme      theme [list] | show NAME | set NAME | reset: colour themes
  keybar     keybar [list] | show NAME | edit NAME | reset NAME
  menu       menu [show] | edit | reset: the launcher menu's items
  undo       undo [--list]: take back the last config change
  edit       edit [settings|theme|keybars|menu]: the settings editors
  notify     notify [--if-away] TITLE [TEXT]: a phone notification
  agent      agent [list] | install [NAME] | notify NAME on|off: AI agents
  open       open URL: open a link in the phone's browser
  vibrate    vibrate [MS]: vibrate the phone (300 ms unless given)
  clipboard  clipboard get | set [TEXT]: the phone's clipboard
  share      share FILE... | --text [TEXT]: send to another app
  location   location [--gps] [--stream]: where the phone is
  sensor     sensor list | NAME [--stream]: the phone's sensors
  camera     camera FILE [--quick front|back]: take a photo
  torch      torch on [PERCENT] | off: the flashlight
  rotation   rotation [status] | lock [portrait|landscape] | unlock
  audio      audio play FILE | record FILE [--seconds N]: sound
  sound      sound [status] | start | install: the sound device
  hook       hook claude|codex|gemini: run by an agent's hooks to notify you
  welcome    the welcome page: what's here and how to get around
  report     report [TEXT]: report a bug (shows it, asks, then opens GitHub)
  about      version, credits and licences
  version    the app tools' version
  help       this list

--json prints the answer as JSON: {"ok": true, ...} or
{"ok": false, "error": "..."}. Exit codes: 0 fine, 1 problems found,
2 error.

Settings live in ~/.config/mynx/ (see ~/AGENTS.md).
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
    return os.path.join(os.path.expanduser("~"), ".config", "mynx")


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
    return [{"file": "~/.config/mynx/menu.conf", "problems": problems}] if problems else []


def cmd_check(args, as_json):
    answer = request("check")
    answer["problems"] += menu_problems()
    lines = []
    for file in answer["problems"]:
        lines.append(file["file"])
        lines += ["  " + p for p in file["problems"]]
    out(as_json, answer, "\n".join(lines) or "No problems in ~/.config/mynx")
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
        raise Usage("usage: mynx get KEY")
    for s in request("settings")["settings"]:
        if s["key"] == args[0]:
            out(as_json, {"ok": True, "key": s["key"], "value": s["value"]}, s["value"])
            return 0
    raise Failure(f"unknown setting '{args[0]}' (mynx settings lists them)")


def cmd_set(args, as_json):
    if len(args) < 2:
        raise Usage("usage: mynx set KEY VALUE")
    answer = request("set", args[0], " ".join(args[1:]))
    out(as_json, answer, f"{answer['key']} = {answer['value']}")
    return 0


def cmd_reset(args, as_json):
    if len(args) != 1:
        raise Usage("usage: mynx reset KEY|all")
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
    raise Usage("usage: mynx theme [list] | show NAME | set NAME | reset")


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
        out(as_json, answer, f"Edit {answer['file']}, then run mynx check.\n"
                             f"(mynx edit keybars is an editor for it.)")
        return 0
    if sub == "reset" and len(args) == 2:
        answer = request("keybar-reset", args[1])
        out(as_json, answer, f"{args[1]}: back to the built-in bar")
        return 0
    raise Usage("usage: mynx keybar [list] | show NAME | edit NAME | reset NAME")


def cmd_menu(args, as_json):
    sub = args[0] if args else "show"
    built_in = os.path.join(TOOLS, "menu.conf")
    if sub == "show" and len(args) <= 1:
        mine = os.path.isfile(user_menu())
        with open(user_menu() if mine else built_in) as f:
            text = f.read()
        file = "~/.config/mynx/menu.conf" if mine else None
        if as_json:
            out(as_json, {"ok": True, "file": file, "text": text}, None)
        else:
            sys.stdout.write(text)
        return 0
    if sub == "edit" and len(args) == 1:
        if not os.path.isfile(user_menu()):
            os.makedirs(config_dir(), exist_ok=True)
            shutil.copyfile(built_in, user_menu())
            record("menu edit")
        out(as_json, {"ok": True, "file": "~/.config/mynx/menu.conf"},
            "Edit ~/.config/mynx/menu.conf, then run mynx check.\n"
            "(mynx edit menu is an editor for it.)")
        return 0
    if sub == "reset" and len(args) == 1:
        if not os.path.isfile(user_menu()):
            raise Failure("the menu is already the built-in menu")
        os.remove(user_menu())
        record("menu reset")
        out(as_json, {"ok": True}, "Menu: back to the built-in one")
        return 0
    raise Usage("usage: mynx menu [show] | edit | reset")


def cmd_open(args, as_json):
    if len(args) != 1:
        raise Usage("usage: mynx open URL")
    answer = request("open-url", args[0])
    out(as_json, answer, None)
    return 0


def cmd_vibrate(args, as_json):
    if len(args) > 1:
        raise Usage("usage: mynx vibrate [MS]")
    answer = request("vibrate", *args)
    out(as_json, answer, None)
    return 0


def cmd_clipboard(args, as_json):
    if args == ["get"]:
        answer = request("clipboard-get")
        if as_json:
            out(as_json, answer, None)
            return 0
        text = answer["text"]
        # A prompt right after the text would be hard to read.
        if text and not text.endswith("\n") and sys.stdout.isatty():
            text += "\n"
        sys.stdout.write(text)
        return 0
    if args[:1] == ["set"]:
        text = " ".join(args[1:]) if len(args) > 1 else sys.stdin.read()
        # Percent-encoded: a request holds one value per line.
        answer = request("clipboard-set", urllib.parse.quote(text, safe=""))
        out(as_json, answer, f"Copied {len(text)} character{'' if len(text) == 1 else 's'}")
        return 0
    raise Usage("usage: mynx clipboard get | set [TEXT]")


def cmd_share(args, as_json):
    if args[:1] == ["--text"]:
        text = " ".join(args[1:]) if len(args) > 1 else sys.stdin.read()
        answer = request("share-text", urllib.parse.quote(text, safe=""))
        out(as_json, answer, f"Sharing {len(text)} character{'' if len(text) == 1 else 's'}: pick an app on the phone")
        return 0
    if not args:
        raise Usage("usage: mynx share FILE... | --text [TEXT]")
    # A request holds one value per line.
    if any("\n" in a for a in args):
        raise Failure("can't share a file whose name has a line break")
    answer = request("share", *(os.path.abspath(a) for a in args))
    count = answer["count"]
    out(as_json, answer, f"Sharing {count} file{'' if count == 1 else 's'}: pick an app on the phone")
    return 0


LOCATION_USAGE = "usage: mynx location [--gps] [--timeout SECONDS] | --stream [--every SECONDS] [--gps]"


def cmd_location(args, as_json):
    options, stream = [], False
    rest = list(args)
    while rest:
        arg = rest.pop(0)
        if arg == "--gps":
            options.append("gps")
        elif arg == "--stream":
            stream = True
        elif arg in ("--timeout", "--every") and rest:
            options.append(f"{'timeout' if arg == '--timeout' else 'interval'}={rest.pop(0)}")
        else:
            raise Usage(LOCATION_USAGE)
    # A stream never ends on its own; one fix has no interval.
    if any(o.startswith("timeout=" if stream else "interval=") for o in options):
        raise Usage(LOCATION_USAGE)
    if not stream:
        answer = request("location", *options)
        out(as_json, answer, fix_text(answer["location"]))
        return 0

    def show(fix):
        print(json.dumps(fix) if as_json else fix_text(fix), flush=True)

    try:
        request("location-stream", *options, on_line=show)
    except KeyboardInterrupt:
        pass
    except BrokenPipeError:
        # The reader went away (| head): nothing more to say.
        sys.stdout = open(os.devnull, "w")
    return 0


def fix_text(fix):
    accuracy = f" ±{round(fix['accuracy'])} m" if fix.get("accuracy") is not None else ""
    when = time.strftime("%H:%M:%S", time.localtime(fix["time"] / 1000))
    return f"{fix['latitude']}, {fix['longitude']}{accuracy} {fix['provider']} {when}"


SENSOR_USAGE = "usage: mynx sensor list | NAME [--timeout SECONDS] | NAME --stream [--rate HZ]"


def cmd_sensor(args, as_json):
    if args[:1] == ["list"]:
        if len(args) > 1:
            raise Usage(SENSOR_USAGE)
        answer = request("sensor-list")
        width = max((len(s["name"]) for s in answer["sensors"]), default=0)
        out(as_json, answer, "\n".join(
            f"{s['name']:<{width}} {' '.join(s['values'])} {s['unit']}".rstrip() for s in answer["sensors"]))
        return 0
    names, options, stream = [], [], False
    rest = list(args)
    while rest:
        arg = rest.pop(0)
        if arg == "--stream":
            stream = True
        elif arg in ("--timeout", "--rate") and rest:
            options.append(f"{arg[2:]}={rest.pop(0)}")
        elif not arg.startswith("-"):
            names.append(arg)
        else:
            raise Usage(SENSOR_USAGE)
    # A stream never ends on its own; one reading has no rate.
    if len(names) != 1 or any(o.startswith("timeout=" if stream else "rate=") for o in options):
        raise Usage(SENSOR_USAGE)
    if not stream:
        answer = request("sensor", *names, *options)
        out(as_json, answer, reading_text(answer["reading"]))
        return 0

    def show(reading):
        if as_json:
            line = json.dumps(reading)
        else:
            when = reading["time"] / 1000
            line = f"{time.strftime('%H:%M:%S', time.localtime(when))}.{reading['time'] % 1000:03d} {reading_text(reading)}"
        print(line, flush=True)

    try:
        request("sensor-stream", *names, *options, on_line=show)
    except KeyboardInterrupt:
        pass
    except BrokenPipeError:
        sys.stdout = open(os.devnull, "w")
    return 0


def reading_text(reading):
    values = reading["values"]
    if len(values) == 1:
        text = " ".join(str(v) for v in values.values())
    else:
        text = " ".join(f"{k}={v}" for k, v in values.items())
    return f"{text} {reading['unit']}".rstrip()


CAMERA_USAGE = "usage: mynx camera FILE [--quick front|back]"


def cmd_camera(args, as_json):
    files, facing = [], None
    rest = list(args)
    while rest:
        arg = rest.pop(0)
        if arg == "--quick" and rest:
            facing = rest.pop(0)
        elif not arg.startswith("-"):
            files.append(arg)
        else:
            raise Usage(CAMERA_USAGE)
    if len(files) != 1:
        raise Usage(CAMERA_USAGE)
    path = os.path.abspath(files[0])
    answer = request("camera", path) if facing is None else request("camera-quick", path, facing)
    out(as_json, answer, f"Saved {answer['file']} ({size_text(answer['bytes'])})")
    return 0


def size_text(size):
    if size < 1024:
        return f"{size} bytes"
    if size < 1024 * 1024:
        return f"{round(size / 1024)} KB"
    return f"{size / 1024 / 1024:.1f} MB"


def cmd_torch(args, as_json):
    if not (args[:1] == ["on"] and len(args) <= 2 or args == ["off"]):
        raise Usage("usage: mynx torch on [PERCENT] | off")
    answer = request("torch", *args)
    out(as_json, answer, None)
    return 0


ROTATION_USAGE = "usage: mynx rotation [status] | lock [portrait|landscape] [--pid PID] | unlock"


def cmd_rotation(args, as_json):
    """The lock is held by the program that ran mynx (or --pid): it ends
    when that program does, so a crash can't leave the screen stuck."""
    sub, rest = (args[0], list(args[1:])) if args else ("status", [])
    if sub == "lock":
        pid, side = os.getppid(), []
        while rest:
            word = rest.pop(0)
            if word == "--pid" and rest and rest[0].isdigit():
                pid = int(rest.pop(0))
            elif word in ("portrait", "landscape") and not side:
                side = [word]
            else:
                raise Usage(ROTATION_USAGE)
        answer = request("rotation", "lock", str(pid), *side)
    elif sub in ("unlock", "status") and not rest:
        answer = request("rotation", sub)
    else:
        raise Usage(ROTATION_USAGE)
    locked = answer["locked"]
    text = {"no": "free", "current": "locked as it is"}.get(locked, f"locked to {locked}")
    out(as_json, answer, "Rotation: " + text)
    return 0


AUDIO_USAGE = "usage: mynx audio play FILE | record FILE [--seconds N] [--rate HZ]"


def cmd_audio(args, as_json):
    what, rest = (args[0], list(args[1:])) if args else (None, [])
    files, options = [], []
    while rest:
        arg = rest.pop(0)
        if what == "record" and arg in ("--seconds", "--rate") and rest:
            options.append(f"{arg[2:]}={rest.pop(0)}")
        elif not arg.startswith("-"):
            files.append(arg)
        else:
            raise Usage(AUDIO_USAGE)
    if what not in ("play", "record") or len(files) != 1:
        raise Usage(AUDIO_USAGE)
    path = os.path.abspath(files[0])
    if what == "play":
        out(as_json, request("audio-play", path), None)
        return 0
    seconds = next((o.split("=", 1)[1] for o in options if o.startswith("seconds=")), None)

    def started(_):
        if not as_json:
            print(f"Recording to {path} for {seconds} s" if seconds else f"Recording to {path}: Ctrl+C stops",
                  file=sys.stderr, flush=True)

    answer = request("audio-record", path, *options, on_line=started, answer_on_interrupt=True)
    out(as_json, answer, f"Saved {answer['file']} ({size_text(answer['bytes'])}, {answer['seconds']} s)")
    return 0


SOUND_USAGE = "usage: mynx sound [status] | start | install [--yes]"
# Keep in step with rootfs/Dockerfile, which puts them in new Debians.
SOUND_PACKAGES = "pulseaudio pulseaudio-utils libasound2-plugins alsa-utils"
SOUND_SERVER = "unix:/tmp/.mynx/sound/native"


def cmd_sound(args, as_json):
    sub = args[0] if args else "status"
    if sub == "status" and len(args) <= 1:
        return sound_status(as_json)
    if sub == "start" and len(args) == 1:
        out(as_json, start_sound(), "Sound device: started")
        return 0
    if sub == "install" and args[1:] in ([], ["--yes"]):
        return install_sound("--yes" in args)
    raise Usage(SOUND_USAGE)


def sound_status(as_json):
    server = os.environ.get("PULSE_SERVER") or SOUND_SERVER
    setting = next(s["value"] for s in request("settings")["settings"] if s["key"] == "sound-device")
    installed = bool(shutil.which("pulseaudio"))
    running = server_answers(server)
    if running:
        text = "on. Programs play through the phone's speaker."
    elif setting == "off":
        text = "off (mynx set sound-device on turns it on)"
    elif not installed:
        text = "not installed (mynx sound install installs it)"
    else:
        text = "not running (mynx sound start starts it)"
    out(as_json, {"ok": True, "setting": setting, "installed": installed, "running": running, "server": server},
        "Sound device: " + text)
    return 0


def start_sound():
    """Restarts the server and returns once the new one answers: the old
    one may answer until it has exited. sound-server writes its pid after
    the old one has gone and before Pulse opens its socket (after `mic`)."""
    server = os.environ.get("PULSE_SERVER") or SOUND_SERVER
    folder = os.path.dirname(server[len("unix:"):])
    old = read_text(os.path.join(folder, "pid"))
    answer = request("sound-start")
    deadline = time.monotonic() + 4 * float(os.environ.get("MYNX_TIMEOUT", "5"))
    while time.monotonic() < deadline:
        pid = read_text(os.path.join(folder, "pid"))
        if pid and pid != old and server_answers(server):
            return answer
        time.sleep(0.1)
    raise Failure(f"the sound device didn't start: see {os.path.join(folder, 'server.log')}")


def read_text(path):
    try:
        return read(path)
    except OSError:
        return None


def server_answers(server):
    if not server.startswith("unix:"):
        return False
    with socket.socket(socket.AF_UNIX) as s:
        s.settimeout(1)
        try:
            s.connect(server[len("unix:"):])
            return True
        except OSError:
            return False


def install_sound(yes):
    steps = ["apt-get update", f"apt-get install -y --no-install-recommends {SOUND_PACKAGES}"]
    print("Installing the sound device (PulseAudio) runs:\n")
    for step in steps:
        print(f"  {step}")
    print()
    if not yes and not ask("Run it? [y/N] (--yes skips this question)"):
        print("Not installed.")
        return 1
    for step in steps:
        sys.stdout.flush()
        code = subprocess.run(["bash", "-c", step]).returncode
        if code != 0:
            raise Failure(f"'{step}' failed (exit {code})")
    start_sound()
    print("\nSound device: on. Programs play through the phone's speaker.")
    return 0


# Not in HELP: for installing builds of the app while developing it. Only
# debug builds of the app answer it.
def cmd_install_apk(args, as_json):
    if len(args) != 1:
        raise Usage("usage: mynx install-apk FILE")
    answer = request("install-apk", os.path.abspath(args[0]))
    out(as_json, answer, "Installer opened on the phone.")
    return 0


def cmd_undo(args, as_json):
    if args == ["--list"]:
        answer = request("undo-list")
        keeps = f"keeps {answer['keep']}: undo-keep"
        lines = [f"{i}. {s['reason']}  ({time.strftime('%H:%M', time.localtime(s['time'] / 1000))})"
                 for i, s in enumerate(answer["steps"], 1)]
        text = "\n".join(lines + [f"mynx undo takes back 1. ({keeps})"]) if lines else f"Nothing to undo ({keeps})"
        out(as_json, answer, text)
        return 0
    if args:
        raise Usage("usage: mynx undo [--list]")
    answer = request("undo")
    if answer["undone"] is None:
        out(as_json, answer, "Nothing to undo")
        return 1
    out(as_json, answer, f"Undid: {answer['undone']}")
    return 0


def cmd_edit(args, as_json):
    from . import editors
    return editors.main(args)


def cmd_notify(args, as_json):
    if_away = "--if-away" in args
    words = [a.replace("\n", " ") for a in args if a != "--if-away"]
    if not words:
        raise Usage("usage: mynx notify [--if-away] TITLE [TEXT]")
    answer = request("notify", words[0], " ".join(words[1:]), *notify_options(if_away))
    if answer["shown"]:
        out(as_json, answer, "Notification shown")
        return 0
    out(as_json, answer, f"Not shown: {answer['reason']}")
    return 1


def notify_options(if_away):
    """The tab the notification comes from, so tapping it opens that tab."""
    shell = os.environ.get("MYNX_SHELL", "")
    return ([f"shell={shell}"] if shell.isdigit() else []) + (["if-away"] if if_away else [])


def cmd_hook(args, as_json):
    """The agents' hooks call this with the event as JSON on stdin.

    Never fails and prints nothing (Gemini CLI: `{}`): Claude Code and
    Codex treat exit code 2 (Codex: any stderr) from a Stop hook as
    "keep working", and Gemini CLI wants JSON on stdout.
    """
    if len(args) != 1 or args[0] not in agents.AGENTS:
        raise Usage("usage: mynx hook claude|codex|gemini (reads the hook's JSON on stdin)")
    agent = agents.AGENTS[args[0]]
    try:
        agent_hook(agent, json.load(sys.stdin))
    except Exception:
        pass
    if agent.name == "gemini":
        print("{}")
    return 0


def agent_hook(agent, event):
    session = "".join(c for c in str(event.get("session_id") or "default") if c.isalnum() or c in "-_")
    turns = os.path.join(tempfile.gettempdir(), "mynx-agent-turns")
    started = os.path.join(turns, f"{agent.name}-{session}")
    kind = agent.events.get(event.get("hook_event_name"))
    options = notify_options(if_away=True) + ["agent"]
    if kind == "start":
        os.makedirs(turns, exist_ok=True)
        with open(started, "w") as f:
            f.write(f"{time.time()}\n")
    elif kind == "stop":
        try:
            with open(started) as f:
                took = int(time.time() - float(f.read()))
        except (OSError, ValueError):
            return  # hooks set up mid-turn: no start time, nothing to measure
        os.remove(started)
        folder = os.path.basename(str(event.get("cwd") or "").rstrip("/"))
        text = f"Your turn ({folder})" if folder else "Your turn"
        request("notify", agent.title, text, *options, f"took={took}")
    elif kind == "attention":
        if event.get("tool_name"):
            text = f"Wants to run {event['tool_name']}"
        else:
            text = str(event.get("message") or "Needs your input").replace("\n", " ")
        request("notify", agent.title, text, *options)


AGENT_USAGE = ("usage: mynx agent [list] | start [NAME] | install [NAME] [--yes] [--notify|--no-notify]"
               " | notify NAME on|off")


def cmd_agent(args, as_json):
    sub = args[0] if args else "list"
    if sub == "list" and args[1:] == ["--tsv"]:
        for s in (agents.status(a) for a in agents.AGENTS.values()):
            print(f"{s['name']}\t{s['title']}\t{'installed' if s['installed'] else ''}")
        return 0
    if sub == "start" and len(args) <= 2:
        if len(args) == 1:
            return pick_agent("Start an AI agent:", start_agent)
        if args[1] in agents.AGENTS:
            return start_agent(agents.AGENTS[args[1]])
    if sub == "list" and len(args) <= 1:
        found = [agents.status(a) for a in agents.AGENTS.values()]
        lines = [f"{s['name']:<7} {s['title']:<12} {'installed' if s['installed'] else 'not installed':<14} "
                 f"notifications {'on' if s['notify'] else 'off'}" for s in found]
        out(as_json, {"ok": True, "agents": found}, "\n".join(lines))
        return 0
    if sub == "notify" and len(args) == 3 and args[1] in agents.AGENTS and args[2] in ("on", "off"):
        agent = agents.AGENTS[args[1]]
        agents.set_notify(agent, args[2] == "on")
        where = f" (hooks in {agent.config})" if args[2] == "on" else ""
        out(as_json, {"ok": True, "name": agent.name, "notify": args[2] == "on"},
            f"{agent.title} notifications: {args[2]}{where}")
        return 0
    if sub == "install":
        flags = {"--yes", "--notify", "--no-notify"}
        names = [a for a in args[1:] if a not in flags]
        if len(names) > 1 or (names and names[0] not in agents.AGENTS) or {"--notify", "--no-notify"} <= set(args):
            raise Usage(AGENT_USAGE)
        notify = True if "--notify" in args else False if "--no-notify" in args else None
        if names:
            return install_agent(agents.AGENTS[names[0]], "--yes" in args, notify)
        return pick_and_install()
    raise Usage(AGENT_USAGE)


def ask(question):
    """A yes/no answer from stdin; no answer (EOF) means no."""
    print(question, end=" ", flush=True)
    answer = sys.stdin.readline()
    if not answer:
        print()
    return answer.strip().lower() in ("y", "yes")


def node_major():
    try:
        run = subprocess.run(["node", "--version"], capture_output=True, text=True)
        return int(run.stdout.strip().lstrip("v").split(".")[0])
    except (OSError, ValueError):
        return None


def agent_binary(agent):
    found = shutil.which(agent.command)
    local = os.path.expanduser(f"~/.local/bin/{agent.command}")
    return found or (local if os.access(local, os.X_OK) else None)


def start_agent(agent):
    """Runs [agent] in this terminal; offers to install it first if it's missing."""
    if not agent_binary(agent):
        print(f"{agent.title} isn't installed yet.\n")
        code = install_agent(agent, yes=False, notify=None, then="")
        if code != 0:
            return code
        print(f"Start {agent.title} now? [Y/n]", end=" ", flush=True)
        if sys.stdin.readline().strip().lower() in ("n", "no"):
            return 0
    binary = agent_binary(agent)
    if not binary:
        raise Failure(f"can't find {agent.command} after installing it; open a new tab and run {agent.command}")
    get_what_it_needs(agent)
    sys.stdout.flush()
    bar_file = os.environ.get("MYNX_KEYBAR_FILE")
    if not bar_file:
        os.execv(binary, [agent.command])
    # Like the keybar command: the agent's bar (else the generic one) while
    # it runs, then the bar from before.
    try:
        with open(bar_file) as f:
            before = f.read()
    except OSError:
        before = ""
    write_quietly(bar_file, f"{agent.name},agent")
    signal.signal(signal.SIGINT, signal.SIG_IGN)  # Ctrl+C is for the agent
    try:
        return subprocess.run([agent.command], executable=binary).returncode
    finally:
        write_quietly(bar_file, before)


def write_quietly(path, text):
    try:
        with open(path, "w") as f:
            f.write(text)
    except OSError:
        pass


def folder_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass  # removed while we looked
    return total


@contextlib.contextmanager
def watch_download(agent, every=0.5):
    """Shows how much of [agent]'s silent download has arrived."""
    if not agent.downloads:
        yield
        return
    folder = os.path.expanduser(agent.downloads)
    done = threading.Event()

    def watch():
        before, showing = folder_size(folder), False
        while not done.wait(every):
            size = folder_size(folder)
            text, showing = agents.download_progress(agent.title, before, size, showing)
            before = size
            if text:
                sys.stdout.write(text)
                sys.stdout.flush()
        if showing:
            sys.stdout.write("\r\x1b[K")
            sys.stdout.flush()

    thread = threading.Thread(target=watch, daemon=True)
    thread.start()
    try:
        yield
    finally:
        done.set()
        thread.join()


def get_what_it_needs(agent):
    """Offers to install what an installed [agent] needs but is missing
    (e.g. ps for Codex, on Debians from before the image had it)."""
    steps = agents.missing_steps(agent, ps=bool(shutil.which("ps")))
    if not steps:
        return
    missing = [c for c in agent.needs if not shutil.which(c)]
    print(f"{agent.title} needs {', '.join(missing)}, which isn't installed. Installing it runs:\n")
    for step in steps:
        print(f"  {step}")
    print()
    if not ask("Run it? [y/N]"):
        return
    for step in steps:
        sys.stdout.flush()
        code = subprocess.run(["bash", "-o", "pipefail", "-c", step]).returncode
        if code != 0:
            raise Failure(f"'{step}' failed (exit {code})")
    print()


def install_agent(agent, yes, notify, then=None):
    steps = agents.install_steps(agent, curl=bool(shutil.which("curl")), node=node_major(),
                                 ps=bool(shutil.which("ps")))
    print(f"Installing {agent.title} with its official installer runs:\n")
    for step in steps:
        print(f"  {step}")
    print()
    if not yes and not ask("Run it? [y/N] (--yes skips this question)"):
        print("Not installed.")
        return 1
    for step in steps:
        sys.stdout.flush()
        # pipefail: a failed download in "curl … | bash" must fail the step
        with watch_download(agent):
            code = subprocess.run(["bash", "-o", "pipefail", "-c", step]).returncode
        if code != 0:
            raise Failure(f"'{step}' failed (exit {code}); nothing else was run")
    print()
    if notify is None:
        notify = ask(f"Also set up notifications for {agent.title} (when it finishes or needs you)? [y/N]")
    if notify:
        agents.set_notify(agent, True)
        print(f"Notifications: on (mynx agent notify {agent.name} off turns them off)")
    else:
        print(f"Notifications: off (mynx agent notify {agent.name} on turns them on)")
    print(then if then is not None else f"\nStart it with: {agent.command}  (sign in with your own account)")
    return 0


def pick_agent(heading, action):
    """A numbered list of the agents; runs [action] on the one picked."""
    found = list(agents.AGENTS.values())
    print(f"{heading}\n")
    for i, agent in enumerate(found, 1):
        status = "installed" if agents.status(agent)["installed"] else ""
        print(f"  {i}) {agent.title:<12} {status}".rstrip())
    print()
    print(f"Number (1-{len(found)}, Enter to go back):", end=" ", flush=True)
    choice = sys.stdin.readline().strip()
    if not choice.isdigit() or not 1 <= int(choice) <= len(found):
        return 0
    return action(found[int(choice) - 1])


def pick_and_install():
    def install(agent):
        try:
            code = install_agent(agent, yes=False, notify=None)
        except Failure as e:
            print(f"mynx: {e}", file=sys.stderr)
            code = 2
        print("\nPress Enter to go back.", end=" ", flush=True)
        sys.stdin.readline()
        return code
    return pick_agent("Install an AI agent:", install)


def cmd_welcome(args, as_json):
    path = os.path.join(TOOLS, "welcome.txt")
    try:
        with open(path) as f:
            text = f.read()
    except OSError:
        raise Failure(f"no {path}")
    if as_json:
        out(True, {"ok": True, "text": text}, None)
    else:
        print(text, end="")
    return 0


APP_NAME = "Mynx"

# What the app ships that others wrote. Licence texts are in
# /opt/mynx/licenses/; proot's GPL needs its source linked.
APP_LICENSE = "MIT"
APP_SOURCE = "https://github.com/est4s/mynx"

COMPONENTS = [
    {"name": "proot", "version": "v5.1.107.96", "license": "GPL-2.0-or-later",
     "source": "https://github.com/termux/proot",
     "note": "Termux's fork of PRoot, which runs Debian. The app runs it as a separate "
             "program (libproot.so, libproot-loader.so), built from the tagged "
             "source with one line added so it compiles (#include <string.h> in "
             "src/extension/ashmem_memfd/ashmem_memfd.c)."},
    {"name": "talloc", "version": "2.5.0", "license": "LGPL-3.0-or-later",
     "source": "https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz",
     "note": "Samba's memory allocator, linked into proot statically, with its "
             "libreplace."},
    {"name": "Termux terminal-emulator and terminal-view", "version": "v0.118.3",
     "license": "Apache-2.0", "source": "https://github.com/termux/termux-app",
     "note": "The terminal on screen (from termux-app, libraries only)."},
    {"name": "JetBrains Mono Nerd Font", "version": "v3.5.1", "license": "OFL-1.1",
     "source": "https://github.com/ryanoasis/nerd-fonts",
     "note": "The terminal font: JetBrains Mono "
             "(https://github.com/JetBrains/JetBrainsMono) patched by Nerd Fonts."},
    {"name": "Debian", "version": "13 (trixie)", "license": "many (see each package)",
     "source": "https://sources.debian.org",
     "note": "The Linux system in the terminal, with PulseAudio, Python, nnn, "
             "starship and the other packages in it. Each package's licence is in "
             "/usr/share/doc/PACKAGE/copyright; apt source PACKAGE fetches its source."},
]

# The licence files in /opt/mynx/licenses/, in the order they're shown:
# the app's own first.
LICENSE_FILES = ["MIT", "GPL-2.0", "LGPL-3.0", "GPL-3.0", "Apache-2.0", "OFL-1.1"]


def wrap(text, indent="  ", width=56):
    import textwrap
    return textwrap.fill(text, width=width, initial_indent=indent, subsequent_indent=indent,
                         break_long_words=False, break_on_hyphens=False)


def version_parts(version):
    """The build number and version name in the tools' `.version`
    (`BUILD-INSTALLTIME-NAME`; apps before 10.4 wrote no name)."""
    if not version:
        return None, None
    parts = version.split("-", 2)
    return parts[0], parts[2] if len(parts) == 3 and parts[2] else None


def about_text(version):
    build, name = version_parts(version)
    heading = (f"version {name} (build {build})" if name else
               f"build {build}" if build else "version unknown")
    lines = [APP_NAME, heading, "",
             "A Debian terminal for Android.", "",
             wrap("Free software under the MIT License (its text is "
                  "first below). Source:", ""), APP_SOURCE, "",
             "Made with:", ""]
    for c in COMPONENTS:
        source = f"  Source: {c['source']}"
        lines += [f"{c['name']} {c['version']}", f"  License: {c['license']}",
                  *([source] if len(source) <= 56 else ["  Source:", f"  {c['source']}"]),
                  wrap(c["note"]), ""]
    lines += [wrap("LGPL-3.0 is GPL-3.0 plus extra permissions, so both texts are "
                   "below. The full licence texts are also in /opt/mynx/licenses/.", ""), ""]
    for name in LICENSE_FILES:
        try:
            with open(os.path.join(TOOLS, "licenses", name + ".txt")) as f:
                body = f.read()
        except OSError:
            continue
        lines += ["=" * 56, name, "=" * 56, "", body.rstrip("\n"), ""]
    return "\n".join(lines) + "\n"


def cmd_about(args, as_json):
    try:
        version = tools_version()
    except Failure:
        version = None
    if as_json:
        build, name = version_parts(version)
        out(True, {"ok": True, "name": APP_NAME, "version": version,
                   "version_name": name, "build": build,
                   "license": APP_LICENSE, "source": APP_SOURCE,
                   "components": COMPONENTS}, None)
        return 0
    text = about_text(version)
    if sys.stdout.isatty() and shutil.which("less"):
        subprocess.run(["less"], input=text, text=True)
    else:
        try:
            sys.stdout.write(text)
            sys.stdout.flush()
        except BrokenPipeError:  # mynx about | head
            os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
    return 0


REPORT_USAGE = "usage: mynx report [TEXT] [--no-crash]  (--json needs TEXT)"
ISSUES_URL = APP_SOURCE + "/issues/new"
# Longer links get cut off by browsers or refused by GitHub.
MAX_REPORT_URL = 8000
CUT_NOTE = "… (cut to fit the link)"


def cmd_report(args, as_json):
    no_crash = "--no-crash" in args
    words = [a for a in args if a != "--no-crash"]
    if any(w.startswith("--") for w in words) or (as_json and not words):
        raise Usage(REPORT_USAGE)
    what = " ".join(words).strip()
    if not what:
        what = ask_what_went_wrong()
    if not what:
        raise Failure("nothing to report")
    try:
        info = request("report-info")
    except Failure:
        info = {}
    crash = None if no_crash else info.get("crash")
    title = report_title(what)
    details, url = report_link(title, what, report_details(info), crash, info.get("crash_time"))
    if as_json:
        out(True, {"ok": True, "title": title, "what": what, "details": details, "url": url}, None)
        return 0
    print(f"\nWhat happened:\n{what}\n\n{details}\n")
    print(wrap("This opens a new issue on GitHub (" + APP_SOURCE.split("://")[1] + ") in the "
               "phone's browser, filled in with the report above. Nothing is sent until "
               "you submit it there, with your own GitHub account.", ""))
    if not ask("Open it? [y/N]"):
        print("Nothing was sent.")
        return 0
    request("open-url", url)
    print("Opened in the phone's browser.")
    return 0


def ask_what_went_wrong():
    print("What went wrong? (end with an empty line)")
    lines = []
    for line in sys.stdin:
        if not line.strip():
            break
        lines.append(line.rstrip("\n"))
    return "\n".join(lines).strip()


def report_title(what, width=60):
    first = what.splitlines()[0].strip()
    return first if len(first) <= width else first[:width - 1].rstrip() + "…"


def report_details(info):
    try:
        build, name = version_parts(tools_version())
    except Failure:
        build, name = None, None
    lines = [f"{APP_NAME} " + (f"{name} (build {build})" if name else
                               f"build {build}" if build else "version unknown")]
    if info.get("android"):
        maker, model = info.get("maker") or "", info.get("model") or ""
        phone = model if model.lower().startswith(maker.lower()) else f"{maker} {model}".strip()
        lines.append(f"Android {info['android']} (SDK {info.get('sdk')}), {phone}")
    return "\n".join(lines)


def report_link(title, what, details, crash, crash_time):
    """The details with the crash, and the link to a prefilled issue;
    a crash too long for the link loses lines from its end."""
    def link(text):
        query = urllib.parse.urlencode({"template": "app-report.yml", "title": title,
                                        "what": what, "details": text})
        return f"{ISSUES_URL}?{query}"

    if not crash:
        return details, link(details)
    when = time.strftime("%Y-%m-%d %H:%M %Z", time.localtime(crash_time / 1000)) if crash_time else "time unknown"
    head = f"{details}\n\nLast crash, {when}:\n"
    lines = crash.rstrip("\n").split("\n")
    full = head + "\n".join(lines)
    if len(link(full)) <= MAX_REPORT_URL:
        return full, link(full)
    while lines:
        lines.pop()
        text = head + "\n".join(lines + [CUT_NOTE])
        if len(link(text)) <= MAX_REPORT_URL:
            return text, link(text)
    return details, link(details)


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
    "notify": cmd_notify, "hook": cmd_hook, "agent": cmd_agent, "undo": cmd_undo, "open": cmd_open,
    "vibrate": cmd_vibrate, "clipboard": cmd_clipboard, "share": cmd_share,
    "location": cmd_location, "sensor": cmd_sensor, "camera": cmd_camera, "torch": cmd_torch,
    "rotation": cmd_rotation,
    "audio": cmd_audio, "sound": cmd_sound,
    "install-apk": cmd_install_apk, "welcome": cmd_welcome, "report": cmd_report, "about": cmd_about, "version": cmd_version, "help": cmd_help,
}


def main(argv):
    as_json = "--json" in argv
    args = [a for a in argv if a != "--json"]
    name = args[0] if args else "help"
    if name in ("-h", "--help"):
        name = "help"
    try:
        if name not in COMMANDS:
            raise Failure(f"unknown command '{name}' (mynx help lists them)")
        return COMMANDS[name](args[1:], as_json)
    except Failure as e:
        if as_json:
            print(json.dumps({"ok": False, "error": str(e)}))
        else:
            print(f"mynx: {e}", file=sys.stderr)
        return 2
