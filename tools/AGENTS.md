# This Debian

Notes for AI agents (and people) working in this terminal. You're root in
a Debian 13 (trixie) arm64 system that runs inside an Android app on a
phone, through proot. The screen is narrow: about 56 columns in portrait.

This guide is `/opt/mynx/AGENTS.md`. It belongs to the app and
is updated with it; `~/AGENTS.md` is yours for your own notes.

**People will ask you to customize their setup** (theme, font, key bars,
the menu). Every setting is a plain-text file in
`~/.config/mynx/`, and the `mynx` command changes, checks
and applies them. See "Changing settings" below.

## How it runs

- **proot, not a VM or container.** Linux system calls go straight to
  Android's kernel; proot only rewrites paths and fakes root.
- **Fake root.** You're uid 0 inside, but Android sees an ordinary app.
  You can't change the kernel, load modules, mount filesystems, or bind
  ports below 1024. There's no `sudo` (you don't need it).
- **No systemd or init.** Nothing starts on its own. Servers and
  daemons run as the app's background services instead (`mynx
  service`, see "Background services"), or in a tab left open. Each
  tab is its own login bash.
- **Some `/proc` files are static stand-ins**, because Android hides the
  real ones: `/proc/stat`, `/proc/vmstat`, `/proc/uptime`,
  `/proc/loadavg`, `/proc/version`. CPU usage and uptime from tools like
  `htop` or `uptime` aren't real.
- **Android 12 and later kill big builds** ("phantom process"
  limits): with many processes or much CPU (a parallel compile),
  Android kills proot. The tab ends, or what it ran keeps going with
  every call failing ("Function not implemented"). Turn the limit off:
  - Android 14 and later: Developer options →
    **Disable child process restrictions**.
  - Android 12, 12L and 13 (no such switch): `adb shell device_config
    set_sync_disabled_for_tests persistent`, then `adb shell
    device_config put activity_manager max_phantom_processes
    2147483647`. On 12L and 13, `adb shell settings put global
    settings_enable_monitor_phantom_procs false` works too, but some
    phones (OnePlus, Oppo, Realme, Xiaomi) refuse it ("Permission
    denial: … WRITE_SECURE_SETTINGS") unless Developer options →
    **Disable permission monitoring** is on; `device_config` needs
    neither.

  `adb` works from here too, with no computer: on Wi-Fi, turn on
  Developer options → **Wireless debugging**, `apt install adb`, then
  with the app and Settings side by side, "Pair device with pairing
  code" and `adb pair localhost:PORT CODE` (the pairing port), `adb
  connect localhost:PORT` (the one Wireless debugging shows), and the
  command above. The settings stay after a restart. Also set the app's
  battery use to **Unrestricted** (Settings → Apps).
- **Network works** (DNS: 1.1.1.1 and 8.8.8.8 in `/etc/resolv.conf`).
- **The time zone is the phone's:** each new tab gets `TZ` set to it
  (e.g. `Europe/Istanbul`), so `date` and the clock show local time.
  Tabs opened before the phone changes zone keep the old one. Debian's
  own `/etc/localtime` stays UTC; `export TZ=…` in `~/.bashrc` wins.
- **Packages:** `apt update` first (the package lists aren't shipped),
  then `apt install <name>`. Everything installed stays across app
  updates.
- **Phone storage** (Download, DCIM, Documents, …) is at
  `/storage/emulated/0`.
- Editors and pagers: `nano` and `less` are installed; `mc` has a file
  manager and editor; `htop` shows processes.

## The setup

| What | Where |
|---|---|
| Settings: font size, font, cursor | `~/.config/mynx/settings.conf` |
| Terminal colours | `~/.config/mynx/colors.properties` |
| Your own themes | `~/.config/mynx/themes/` |
| Key bars above the keyboard | `~/.config/mynx/keybars/` |
| Launcher menu items | `~/.config/mynx/menu.conf` (else `/opt/mynx/menu.conf`) |
| Background services | `~/.config/mynx/services.conf` |
| Shell setup: eza aliases, prompt, tab titles, menu hook | `~/.bashrc` |
| Prompt (starship) | `~/.config/starship.toml` |
| Menu state (last choices) | `~/.local/state/mynx/menu` |
| Apps | `/opt/mynx/apps` (`compass`, `incline`, `torch`, `spectrum`, `dbmeter`, `tuner`, `metronome`, `serial`), yours in `~/apps` |
| Games | `/opt/neon-games` (`rogue`, `drive`, `flap`), yours in `~/games`; `play` runs one with its key bar |
| The app's tools: `mynx`, `menu`, `files`, `keybar`, `play`, the editors | `/opt/mynx/bin` |
| Built-in key bars and themes, to read or copy | `/opt/mynx/keybars`, `/opt/mynx/themes` |
| Licence texts of what the app bundles (`mynx about`) | `/opt/mynx/licenses` |
| USB serial ports (boards, adapters) | `/dev/serial/ttyUSB0`, `rfc2217://localhost:2217` (see "USB serial devices") |

`/opt/mynx` belongs to the app and is replaced on every app
update; don't change it. Its `bin` comes first on the PATH. Your changes
go in your home folder (`~/.local/bin` comes before it).

## Changing settings: `mynx`

**After editing any file in `~/.config/mynx/` by hand, run
`mynx check`**: the app applies the change right away (no need to
leave the app) and lists problems by file and line. Bad lines are
skipped, never fatal. Exit codes: 0 no problems, 1 problems found, 2 an
error (for example not running inside the app). Fix what it lists and
run it again. The `set`, `theme`, `keybar` and `menu` commands apply on
their own.

| Command | Does |
|---|---|
| `mynx check` | apply the config now and list problems |
| `mynx settings` | list the settings with values, defaults and descriptions |
| `mynx get KEY` | one setting's value |
| `mynx set KEY VALUE` | change a setting, e.g. `mynx set font-size 14` |
| `mynx reset KEY` | a setting back to its default; `mynx reset all` for all of them |
| `mynx theme list` | the themes (`*` marks the one in use) |
| `mynx theme set NAME` | switch theme, e.g. `mynx theme set nord` |
| `mynx theme show NAME` | a theme's colours file |
| `mynx theme reset` | back to the default theme (deletes the colours file) |
| `mynx keybar list` | key bars: built-in, edited (your copy) or yours |
| `mynx keybar show NAME` | the bar file in use |
| `mynx keybar edit NAME` | copy a built-in bar (or start a new one) in `~/.config/mynx/keybars/` to edit |
| `mynx keybar reset NAME` | delete your copy of a built-in bar |
| `mynx menu show` | the launcher menu file in use |
| `mynx menu edit` | copy the built-in menu to `~/.config/mynx/menu.conf` to edit |
| `mynx menu reset` | delete your menu file (back to the built-in one) |
| `mynx notify TITLE [TEXT]` | a phone notification; tapping it opens this tab. `--if-away` skips it while this tab is on screen |
| `mynx undo` | take back the last config change (`mynx undo --list` shows what it can take back) |
| `mynx open URL` | open a web link in the phone's browser (also `xdg-open URL`) |
| `mynx vibrate [MS]` | vibrate the phone, 300 ms unless given (1 to 5000); `--strength PCT` |
| `mynx vibrate effect NAME` | one of Android's effects: `click`, `double-click`, `tick`, `heavy-click` |
| `mynx vibrate wave STEP…` | a pattern of steps (`MS`, `MS@PERCENT`); `--repeat` until Ctrl+C (see "The phone") |
| `mynx vibrate chain PRIM…` | Android's primitives one after another (`click`, `thud@50+100`, …) |
| `mynx vibrate list` | what the phone's vibrator can do: strength, effects, primitives |
| `mynx vibrate stop` | stop any vibration |
| `mynx clipboard get` | print the phone's clipboard (the app must be on screen) |
| `mynx clipboard set [TEXT]` | copy TEXT to the phone's clipboard, or stdin when there's no TEXT |
| `mynx share FILE…` | send files to another app through Android's share sheet (the app must be on screen) |
| `mynx share --text [TEXT]` | send TEXT, or stdin when there's no TEXT, to another app |
| `mynx location` | where the phone is: one fix; `--stream` keeps printing them (see "The phone") |
| `mynx sensor list` | the phone's sensors, with their values and units |
| `mynx sensor NAME` | one reading of a sensor; `--stream` keeps printing them (see "The phone") |
| `mynx battery` | the battery: charge, charging, temperature, health, battery saver; `--stream` prints changes (see "The phone") |
| `mynx camera FILE` | take a photo with the phone's camera app, saved to FILE; `--quick front\|back` snaps one with no screen |
| `mynx torch on [PERCENT]\|off` | the phone's flashlight |
| `mynx rotation lock [portrait\|landscape]` | stop the screen turning while the program that ran it runs; `unlock`, `status` |
| `mynx audio play FILE` | play a sound file through the phone's speaker, until it ends |
| `mynx audio record FILE` | record from the microphone to FILE (`.m4a`, `.aac`, `.ogg`, `.opus`, `.wav`) until Ctrl+C or `--seconds N` |
| `mynx sound` | whether the sound device is on; `mynx sound start` starts it again, `mynx sound install` installs it (see "The phone") |
| `mynx service [list]` | the background services: state, uptime, restarts (see "Background services") |
| `mynx service add NAME [OPTIONS] -- COMMAND…` | add a service to `services.conf`; `--autostart`, `--restart no\|on-failure\|always`, `--cwd DIR`, `--restart-limit N`, `--restart-delay S`, `--stop-timeout S` |
| `mynx service start\|stop\|restart NAME` | start, stop (TERM, then force after `stop-timeout`) or restart a service |
| `mynx service log NAME [-n N]` | the last N lines (100) of a service's terminal |
| `mynx service open NAME` | show a service as a tab (the app must be on screen); closing the tab hides it again |
| `mynx service remove NAME` | stop a service and remove it from `services.conf` |
| `mynx service autostart NAME on\|off` | whether a service starts when the app starts (its `autostart` line in `services.conf`); it doesn't start or stop it now |
| `mynx service adopt NAME` | turn this tab into the unsaved service NAME, as it runs; `mynx service save NAME [--command CMD]` saves it to `services.conf` |
| `mynx usb [list]` | USB serial devices: their ports' `/dev/serial` paths and `rfc2217://` URLs, or why they have none (see "USB serial devices") |
| `mynx usb allow [DEVICE]` | ask Android to let the app use a USB serial device (the app must be on screen) |
| `mynx usb url\|path [PORT]` | one port's `rfc2217://` URL or `/dev/serial` path, for scripts: `esptool --port "$(mynx usb url)" …` |
| `mynx usb monitor [PORT] [--baud N] [--lower]` | a serial monitor on a port, in this tab (the `serial` app): Ctrl+T then a key resets the board, changes the baud, … (see "USB serial devices") |
| `mynx fix-links DIR…` | repair the hard links an installer broke by moving the folder it unpacked (arduino-cli's toolchains: see "USB serial devices") |
| `mynx agent list` | the AI agents: installed or not, notifications on or off (`--tsv` for scripts) |
| `mynx agent start [NAME [ARGS…]]` | start an agent in this terminal, with ARGS for it; offers to install it first if it's missing |
| `mynx agent install NAME` | install `claude`, `codex` or `gemini` with its official installer (shows the commands and asks first; `--yes`, `--notify`/`--no-notify`, and for Codex `--sandbox-off`/`--sandbox-on`, skip the questions) |
| `mynx agent notify NAME on\|off` | an agent's phone notifications (adds or removes its hooks) |
| `mynx hook claude\|codex\|gemini` | run by the agents' hooks (see "AI agents"); reads the hook's JSON on stdin |
| `mynx edit` | the settings editors, for people (full screen) |
| `mynx welcome` | the welcome page new users see: what's here and how to get around |
| `mynx notes` | every release's notes, newest first; `mynx notes new` only those since the last update, `mynx notes seen` marks them read (see "Updates") |
| `mynx about` | the app's version, what it's made with (proot, Termux's terminal, the font, Debian), their licences and source links; in `less` on a terminal |
| `mynx version` | the version of the app's tools |
| `mynx update` | update the app to its latest release: shows the version and its notes, asks, downloads it and opens Android's installer (`--check` only says; `--yes` skips the question; see "Updates") |
| `mynx report [TEXT]` | report a bug in the app: shows the report, asks, then sends it with `gh` if signed in, else opens a prefilled GitHub issue (see "More of the setup") |
| `mynx github [--yes]` | set up GitHub: installs `git` and `gh`, signs in in the browser (the code copied to the clipboard), sets git's name and noreply email; `mynx github status` shows the account and git's name and email (see "More of the setup") |
| `mynx help` | all commands |

`--json` on any command prints `{"ok": true, ...}` or
`{"ok": false, "error": "..."}`, for scripts.

`mynx edit` (also the menu's Settings → App settings) opens editors for people:
theme (moving through the list previews each theme live), settings
(font, cursor, agent notifications, undo), key bars, the launcher menu,
a config check and "Undo last change". In each, `r` puts things back
to the default. Don't run it yourself: it's interactive. Use the
commands above.

### Settings (`settings.conf`)

One `key = value` per line; `mynx settings` describes each.

- `font-size`: 6 to 40 (default 12). Pinching the terminal changes it
  and saves it here.
- `font`: `default` (JetBrains Mono Nerd Font, which has the icons the
  prompt and `ls` use) or the full path of a `.ttf`/`.otf` file in
  Debian, e.g. after `apt install fonts-hack`:
  `mynx set font /usr/share/fonts/truetype/hack/Hack-Regular.ttf`.
  Other fonts don't show Nerd Font icons.
- `cursor-style`: `block`, `underline` or `bar`.
- `cursor-blink`: `on` or `off`.
- `agent-notify`: `on` (default) or `off`: phone notifications from AI
  agents (see "AI agents").
- `agent-notify-after`: 0 to 3600 seconds (default 30). A finished
  agent turn only notifies if it took at least this long.
- `undo-keep`: 0 to 20 (default 1): how many changes `mynx undo` can
  take back. 0 turns undo off.
- `android-clipboard`: `on` (default) or `off`: whether programs here
  can read and change the phone's clipboard (see "The phone").
- `android-share`: `on` (default) or `off`: whether programs here can
  open Android's share sheet (`mynx share`).
- `android-location`: `on` (default) or `off`: whether programs here
  can ask for the phone's location (`mynx location`).
- `android-sensors`: `on` (default) or `off`: whether programs here
  can read the phone's sensors (`mynx sensor`).
- `android-camera`: `on` (default) or `off`: whether programs here
  can take photos (`mynx camera`).
- `android-microphone`: `on` (default) or `off`: whether programs
  here can record from the microphone (`mynx audio record`, and
  the sound device: they get silence while it's off).
- `sound-device`: `on` (default) or `off`: whether the app runs the
  sound device, so programs here play through the phone's speaker.
- `share-folder`: where files other apps share to this app are saved,
  a full path or one starting with `~/` (default `~/Shared`).
- `tab-swipe`: `on` (default) or `off`: whether a quick sideways
  swipe on the terminal goes to the next tab (swipe left) or the
  one before (swipe right). It stops at the first and last tab;
  scrolling, pinching and selecting text are left alone.
- `wakelock`: `auto` (default), `on` or `off`: whether the app keeps
  the phone's CPU awake, so servers and long jobs (builds, downloads)
  don't pause when the screen goes off. `auto` holds it only while a
  background service runs; `on` whenever the app runs. It uses more
  battery; the app's notification says "wakelock held" while it's on.
- `update-check`: `on` (default) or `off`: whether the app checks
  GitHub about once a day for a new version and tells you (see
  "Updates"). `mynx update` works either way.
- `serial-preload`: `on` (default) or `off`: whether programs here
  load the app's serial library (`LD_PRELOAD`), so tools like esptool
  and picocom work on `/dev/serial` ports (see "USB serial devices").
  New tabs and services follow it.
- `serial-names`: `on` (default) or `off`: whether USB
  serial ports also appear as `/dev/ttyUSB0` and `/dev/ttyACM0`, with their USB
  details in `/sys/class/tty`, so tools find them by themselves. For
  that Debian gets a `/dev` of its own, with the phone's device files
  it needs (`null`, `tty`, `pts`, …); `off` shows Android's `/dev` as
  before. New tabs and services follow it.

### Undo

Every change to `~/.config/mynx/` is recorded so `mynx
undo` can take it back, however it was made: `mynx` commands, the
settings editors, the app (pinching the font size), or by hand. Edits by
hand are recorded at the next `mynx check`, app start or `mynx
undo`, so a file you broke by hand can be undone even if you never
checked it. `mynx undo` takes back one change at a time, newest first,
as far back as `undo-keep` allows; `mynx undo --list` names them.
Fonts in `fonts/` and files over 256 KB aren't recorded. The copies live
in `~/.local/state/mynx/undo/`.

### AI agents

The menu's **AI agents** item lists them: picking an installed one
starts it, picking one marked "(install)" offers to install it first
(`mynx agent start NAME` does the same). `mynx agent install NAME`
installs an agent CLI with its own official installer, never a copy bundled with
the app; the user signs in with their own account:

| Name | Agent | Installer | Its hooks |
|---|---|---|---|
| `claude` | Claude Code | `curl -fsSL https://claude.ai/install.sh \| bash` | `~/.claude/settings.json` |
| `codex` | Codex | `curl -fsSL https://chatgpt.com/codex/install.sh \| sh` | `~/.codex/hooks.json` |
| `gemini` | Gemini CLI | `npm install -g @google/gemini-cli` (needs Node.js 20+: installs `nodejs npm` first) | `~/.gemini/settings.json` |

`curl` is installed first if it's missing. Afterwards it asks whether
to turn on the agent's notifications.

**Codex's sandbox:** Codex runs its commands in a sandbox built on
bubblewrap, which can't work under proot, so with the sandbox on every
command Codex tries fails. Before installing, `mynx agent install
codex` explains this and asks to turn it off; it then puts
`sandbox_mode = "danger-full-access"` at the top of
`~/.codex/config.toml` (keeping the rest of the file). Saying no asks
whether to install it anyway with the sandbox on. To turn it off later,
add that line yourself (before any `[table]`), or run `codex -s
danger-full-access`. Claude Code and Gemini CLI don't need this.

**Claude Code under proot:** typed as `claude` in the shell, or started
with `mynx agent start`, it gets two things it needs here. A socket
for cross-session messaging (`--messaging-socket-path`, in
`~/.cache/mynx/claude-msg/`): proot has no uid map, so Claude Code
can't check its usual socket folder and says "Cross-session messaging
is off". And fullscreen (`CLAUDE_CODE_NO_FLICKER=1`), which keeps its
input at the bottom: Claude Code turns fullscreen off for good after
starts that died early, such as a tab closed while it started. `/tui
default` in Claude Code turns fullscreen off for real (it's saved as
`"tui": "default"` in `~/.claude/settings.json`, which mynx follows);
`/tui fullscreen` turns it back on.

**Notifications:** with them on, the agent's hooks run `mynx hook
NAME`, which posts a phone notification when a turn that took
`agent-notify-after` seconds or more ends ("Your turn"), and whenever
the agent needs permission or input. Nothing is shown while you're
looking at that agent's tab, and tapping a notification opens it. The
hook never fails, so it can't disturb the agent. `mynx agent notify
NAME on|off` (or the Settings editor) adds or removes the hook entries
in the agent's config file and leaves everything else in it alone;
that file is the only record of whether they're on. `agent-notify off`
silences all agents at once.

Your own scripts can notify too: `long-job && mynx notify "Done" "long-job finished"`.

**Signing in:** `BROWSER` points at `xdg-open`, which opens links in the
phone's browser, so an agent that opens a browser to sign in just works
(the app must be on screen). Tapping a link shown in the terminal opens
it too, even one spread over several rows.

### Background services

Servers and daemons (a web server, `sshd`, a database, a bot) run as
services: each in a hidden terminal of its own, kept running by the
app with no tab open, restarted when it fails, and started again
when the app starts. The menu's **Services** lists them.

They're defined in `~/.config/mynx/services.conf`, one `[name]`
block each (names: `a-z`, `0-9` and `-`, up to 20 characters):

```
[web]
command = python3 -m http.server 8080
cwd = ~/site
autostart = yes
restart = on-failure
```

| Key | Means |
|---|---|
| `command` | what to run, in bash (required); the login PATH and `~/.profile` apply |
| `cwd` | the folder it runs in (default `~`) |
| `autostart` | `yes`: start it when the app starts (default `no`; not at phone boot) |
| `restart` | `on-failure` (default): restart it when it exits with a code other than 0; `always`; `no` |
| `restart-limit` | quick failures in a row before giving up, with a phone notification (default 5; 0 never gives up) |
| `restart-delay` | seconds before the first restart (default 1), doubling up to 60 |
| `stop-timeout` | seconds `stop` waits after TERM before forcing it (default 10) |

`mynx service add web --autostart --cwd ~/site -- python3 -m http.server 8080`
writes that block (one argument after `--` is kept as shell text,
several are quoted as needed), `mynx service remove web` stops it and
takes its block out; the rest of the file and its comments stay.
`mynx service autostart web on|off` (or the menu's Services → web →
Autostart) changes its `autostart` line. Edit the file by hand, then
`mynx check`. `mynx service start|stop|restart NAME` control one; a
service stopped this way isn't restarted. **What starts with the app:**
the `autostart = yes` services, and after Android closed the app, also
those that were running then; a service you stopped stays stopped. The
notification's Exit stops them all, and the next start brings back only
the `autostart` ones.
`mynx service` lists them: `running`, `stopping`, `stopped` or
`failed` (it gave up), with the last exit code, uptime and restarts.
`mynx service log NAME -n 50` prints the end of its terminal.
`mynx service open NAME` (or the menu's Services → NAME → Open)
shows it as a tab, titled `● NAME` (`○ NAME: exit 1`, `restarting`,
`failed` or `stopped` while it isn't running), with the `service`
key bar (Esc, Ctrl, arrows, Tab, Ctrl+C, Enter, Ctrl+D) unless its
program asks for another: type into it, Ctrl+C it. Its × only hides
it; long-pressing the tab offers **Hide**, **Restart** and **Stop**
(**Start** when it isn't running). When it restarts, the tab shows
the new run. Service tabs aren't brought back after Android closes
the app: the services come back, hidden.

**Seeing them from anywhere:** while any service runs, the tab strip
shows `● N` (how many) right of **+**; tapping it opens a tab with the
menu's Services list (`menu services` does the same in a shell). The
app's notification has a **Services** button too.

**A tab into a service:** long-press a tab and pick **Run as
service** (it offers the name of the program running in it), or type
`mynx service adopt NAME` in the tab: the tab, with what runs in it,
becomes the service NAME without restarting anything, and stays open
as its tab. It isn't saved (marked `*` in the list) and ends if
Android closes the app; `mynx service save NAME [--command COMMAND]`
writes it to `services.conf`, with the command running in it then
(read from its terminal's foreground program) and its folder.

**How a service runs:** the app runs `/opt/mynx/lib/service-run
NAME`, which runs the command in a process group of its own that has
the terminal, and writes the group's id to
`/tmp/.mynx/services/NAME.pid`. Stop sends TERM to that group (so
databases shut down cleanly), then forces it after `stop-timeout`.
The exit code goes to `/tmp/.mynx/services/NAME.exit`: the command's
own, or 128+N when signal N ended it (143 for TERM, 130 for Ctrl+C);
2 when it couldn't start (no such service, no command, no such
folder). Ctrl+Z is off in a service's terminal. Programs in a service
see `MYNX_SERVICE=NAME`.

- **Ports:** nothing below 1024 (proot's root is fake): run `sshd -p
  8022`, a web server on 8080.
- **Wakelock:** with `wakelock = auto` (the default), the app keeps
  the CPU awake while any service runs, so servers answer with the
  screen off.

### The phone

Commands for the phone itself, and the sound device:

- `mynx vibrate [MS]`: a buzz, e.g. `make && mynx vibrate`. It
  returns at once. `--strength PCT` (1 to 100) sets how strong, on
  phones whose vibrator has strength control (`mynx vibrate list`
  says); elsewhere it's refused, saying why.
- **Vibration patterns:** these wait until the vibration ends, and
  Ctrl+C stops it. Only one vibration plays at a time: a new one, or
  `mynx vibrate stop` (from any tab), ends the one before, whose
  `mynx` then returns. Nothing the phone can't do is skipped
  silently: it's refused with the reason (`--json`: `{"ok": false,
  "error": …}`).
  - `mynx vibrate effect NAME`: Android's predefined `click`,
    `double-click`, `tick` or `heavy-click` (Android 10+), as the
    phone tunes them.
  - `mynx vibrate wave STEP…`: steps one after another, each `MS`
    (full strength), `MS@PERCENT`, or `MS@0` for a pause:
    `mynx vibrate wave 200 100@0 200 100@0 600@40`. Up to 60 s;
    `--repeat` plays it again and again until Ctrl+C or `mynx vibrate
    stop`. Strengths other than 0 and 100 need strength control.
  - `mynx vibrate chain PRIM…`: Android's haptic primitives (Android
    11+), each `NAME[@PERCENT][+MS]`, `+MS` being a pause before it:
    `mynx vibrate chain slow-rise click+100 thud@60`. Names: `click`,
    `tick`, `quick-rise`, `slow-rise`, `quick-fall`, and from Android
    12 `low-tick`, `thud`, `spin`. Phones have only some of them.
  - `mynx vibrate list`: whether the vibrator has strength control,
    each effect (yes, no, or unknown before Android 11) and each
    primitive (with its length in ms from Android 12). `--json` gives
    `amplitude_control`, `effects` (name: true, false or null) and
    `primitives` (name: `supported`, `ms`).
  - A finished `--json` answer has `ms`, how long it played (an
    effect's is a close guess: Android doesn't say), or `"stopped":
    true` when another vibration or `stop` ended it.
  - Vibrations follow the phone's media vibration setting (Android
    13+), like other apps'.
- `mynx clipboard set [TEXT]` copies TEXT, or what's piped in, line
  breaks and all: `git log -1 | mynx clipboard set`.
  `mynx clipboard get` prints it as it is, with no line break added
  unless it goes to the terminal. Android only lets the app on screen
  read the clipboard, so `get` fails while the app is in the
  background; `set` works anyway. `android-clipboard off` blocks both.
- `mynx share FILE…` opens Android's share sheet to send files to
  another app (mail, chat, …): `mynx share report.pdf photo.jpg`.
  `mynx share --text TEXT` sends text, or what's piped in. The app
  must be on screen. `android-share off` blocks it.
- `mynx location` prints one fix: `60.1695213, 24.9354471 ±12 m
  network 08:41:02` (latitude, longitude, accuracy, where it came
  from, time). It takes the first fix from GPS or the network;
  `--gps` waits for GPS only. It gives up after 60 s (`--timeout
  SECONDS`, up to 300). `--stream` prints a fix about every 5 s
  (`--every SECONDS`) until Ctrl+C. `--json` gives `latitude`,
  `longitude`, `accuracy`, `altitude`, `speed`, `bearing` (null when
  unknown), `provider` and `time` (ms since 1970); with `--stream`,
  one object per line. The first time, Android asks to allow location
  (only while the app is in use), so the app must be on screen to
  start it; a stream keeps going in the background. `android-location
  off` blocks it.
- `mynx sensor list` names the phone's sensors: `accelerometer`,
  `gyroscope`, `magnetic-field`, `light`, `proximity`, `pressure`,
  `gravity`, `rotation-vector`, `step-counter`, … as the phone has
  them, and `compass` (azimuth clockwise from magnetic north, pitch
  and roll, in degrees, worked out from `rotation-vector`). `mynx
  sensor NAME` prints one reading: `x=0.1235 y=9.8067 z=-0.5 m/s²`,
  or `108 lx` for one value; it gives up after 10 s (`--timeout
  SECONDS`, up to 60). `--stream` prints up to 10 readings a second
  (`--rate HZ`, 1 to 200), each line starting with the time, until
  Ctrl+C; sensors like `light` and `proximity` only send when the
  value changes. `--json` gives `sensor`, `values` (by name), `unit`,
  `accuracy` (`high`, `medium`, `low`, `unreliable`, `no-contact`) and
  `time` (ms since 1970); with `--stream`, one object per line. The
  step sensors need Android's "physical activity" permission, asked
  the first time (app on screen). Readings keep coming in the
  background. `android-sensors off` blocks it.
- `mynx battery` shows the battery: level, status and what it's
  plugged into (with the time until full while charging),
  temperature, health, voltage, current (in or out, and on average),
  charge left in mAh, charge cycles, the technology, battery saver
  and the phone's thermal status (`none` to `shutdown`: how hard it
  is slowing down to cool off). Lines the phone doesn't fill are left
  out. `--stream` prints a line whenever the level, status, plug,
  health, temperature, battery saver or thermal status changes, until
  Ctrl+C; `--every SECONDS` (1 to 3600) adds one at least that often,
  for logging the current. `--json` gives `percent`, `status`
  (`charging`, `discharging`, `full`, `not-charging`, `unknown`),
  `plugged` (`ac`, `usb`, `wireless`, `dock`, null), `health`,
  `temperature` (°C), `voltage` (V), `current` and `current_average`
  (A, positive into the battery), `charge` (mAh), `time_to_full`
  (seconds), `cycles`, `technology`, `battery_saver`, `thermal` and
  `time` (ms since 1970), null for what the phone doesn't say; with
  `--stream`, one object per line. It needs no permission and works
  in the background. How phones report the current varies; it's
  shown as they say, except that while discharging it always flows
  out. Linux tools see the battery too: Android hides
  `/sys/class/power_supply`, so the app keeps a stand-in there
  (`battery`, `ac`, `usb`, `wireless`, in the kernel's names and
  units) for starship, `acpi`, htop or Python's psutil, updated on
  every change and every 30 s.
- `mynx camera FILE` opens the phone's camera app; the photo you
  take is saved to FILE as a JPEG (replacing it), and `mynx` prints
  `Saved /root/photo.jpg (2.3 MB)`. Backing out of the camera app
  leaves FILE alone ("no photo was taken"). `--quick back` (or
  `front`) takes the photo straight away with no screen, for scripts
  and agents: exposure and focus get a moment to settle, then it's
  saved, within a few seconds. `--json` gives `file` and `bytes`. Android asks
  to allow the camera the first time (for both ways), and only lets
  the app on screen use it, so the app must be on screen.
  `android-camera off` blocks it.
- `mynx torch on` and `mynx torch off` turn the flashlight on and
  off. `mynx torch on 30` sets a strength
  in percent, on phones whose flashlight has levels (Android 13+).
  Both work with the app in the background. Opening the camera turns
  it off.
- `mynx rotation lock` stops the screen turning with the phone,
  e.g. for a program that reads the tilt sensors: it stays as it is,
  or `mynx rotation lock portrait` (`landscape`) turns it to that
  side. The lock belongs to the program that ran `mynx` (the shell,
  when you type it) and ends when that program ends, so a crash
  can't leave the screen stuck; `--pid PID` gives it to another
  process. `mynx rotation unlock` ends every lock; `mynx rotation`
  says whether it's locked (`--json`: `locked` is `no`, `current`,
  `portrait` or `landscape`). From a program: run `mynx rotation
  lock` once at start.
- `mynx audio play FILE` plays a sound file (mp3, ogg, wav, m4a,
  flac, … whatever Android can play) and returns when it ends; Ctrl+C
  stops it. It works with the app in the background. `--json` gives
  `file` and `seconds`.
- `mynx audio record FILE` records from the microphone until Ctrl+C,
  or for `--seconds N`, then prints `Saved /root/memo.m4a (240 KB,
  15 s)`. The file's ending picks the format: `.m4a` or `.aac` (AAC),
  `.ogg` or `.opus` (Opus, Android 10+), `.wav` (16-bit PCM, for
  tools like whisper.cpp). Mono; `--rate HZ` (8000 to 48000) sets the
  sample rate, e.g. `mynx audio record --rate 16000 note.wav`. FILE
  only changes once the recording is complete. `--json` gives `file`,
  `bytes` and `seconds`. Android asks to allow the microphone the
  first time, and only lets the app on screen start recording; a
  recording keeps going in the background (Android shows its
  microphone indicator). `android-microphone off` blocks it.
- **The sound device:** programs here play sound themselves, through
  the phone's speaker: `mpv`, `aplay`, `paplay`, `sox`'s `play`,
  games, anything that uses PulseAudio or ALSA. The app runs
  PulseAudio in the background (outside the tabs) and plays what it
  sends; `PULSE_SERVER` in every tab points programs at it
  (`unix:/tmp/.mynx/sound/native`). Don't start your own
  `pulseaudio`. `mynx sound` says whether it's on; `mynx sound
  start` starts it again and returns once programs can connect;
  Debians set up before the sound device need `mynx sound
  install` once (it runs `apt-get install
  pulseaudio …`, asking first; `--yes` skips that). PulseAudio's
  output is in `/tmp/.mynx/sound/server.log`.
  `sound-device off` turns it off. Programs record through it too
  (`arecord`, `parecord`, `sox`'s `rec`, whisper.cpp, …): the app
  opens the phone's microphone only while a program records, and
  its notification names the programs ("Microphone: arecord").
  Programs get silence, and the notification says why, when
  `android-microphone` is off, when the microphone isn't allowed
  yet, or when recording starts while the app is in the background
  (Android only lets the app in use start the microphone): open the
  app and it starts. Recording is 48 kHz mono.
- **Sharing to Debian:** the app is in Android's share sheet too.
  Files shared to it from other apps are saved in `~/Shared` (the
  `share-folder` setting), keeping their names; shared text is saved
  as a `.txt` file there, named after its subject (a page's title) or
  the time. A name that's taken gets ` (2)`, so nothing is overwritten.
  A notification says what was saved where.
- `mynx notify` and `mynx open`: see "AI agents" above.

### USB serial devices

Boards like Arduino and ESP32, and USB-to-serial adapters, plugged in
with a USB-C OTG cable or adapter, become serial ports in Debian. The
app drives them through Android (no kernel drivers here): CDC ACM
devices (Arduino Uno and Mega's ATmega16U2, Leonardo, ESP32-S2, -S3,
-C3 and -C6's own USB, Raspberry Pi Pico, STM32's virtual COM port),
and the CH340/CH341, CP210x, FTDI and PL2303 chips.

- **Allowing it:** when one is plugged in, Android asks whether to
  open mynx for it; OK lets the app use it until it's unplugged
  ("always" remembers that device). One Android didn't ask about:
  `mynx usb allow [DEVICE]`, with the app on screen; it also opens
  again one that stays at "no port yet". A notification
  says when its ports are ready, and where.
- **Each port, two ways:**
  - `/dev/serial/ttyUSB0` (`ttyACM0` for CDC ACM devices; the next
    one gets 1): a terminal device for programs that open a file:
    `picocom -b 115200 /dev/serial/ttyUSB0`, `screen`, `minicom`,
    `cat`, `stty`, `avrdude`, pyserial and the tools built on it
    (`esptool --port /dev/serial/ttyACM0 …`, miniterm, PlatformIO), a
    script. Opening it raises DTR and RTS, as on Linux (which resets
    an Arduino Uno), and closing it drops them; programs can set them
    too (esptool's reset into the bootloader). The baud rate, data
    bits, parity and stop bits follow what the program sets (9600 8N1
    until one does).
  - `rfc2217://localhost:2217` (2218 for the next port, …): the same
    port over RFC 2217, which pyserial speaks, also for programs the
    serial library can't reach (see below): `esptool --port
    "rfc2217://localhost:2217" …` (reset into the bootloader and
    back), PlatformIO (`upload_port` and `monitor_port`),
    `python3 -m serial.tools.miniterm rfc2217://localhost:2217 115200`.
  A port has one user at a time: while a program has its
  `/dev/serial` file open, its URL is busy, and the other way round.
  A program that opens the file while the URL is in use gets nothing
  until the URL's program lets go; `mynx usb` shows the path as
  "waiting", and the helper's log says "busy".
- **What Android doesn't allow, and the serial library:** Android
  refuses programs the modem-line calls on a terminal device
  (`TIOCMGET`, `TIOCMSET`, `TIOCMBIS`, `TIOCMBIC`, `TIOCEXCL`,
  `tcdrain`, `tcflow`) and `flock` on it ("Permission denied"). The
  app's library, `/opt/mynx/lib/libmynx-serial.so`, loaded into every
  program through `LD_PRELOAD` (the `serial-preload` setting), steps
  in only when one of those fails on a `/dev/serial` port: DTR and RTS
  go to the port's helper (its control socket is
  `/dev/serial/.NAME.ctl`), which sets them on the board; CTS, DSR and
  CD always read as on; locks and `tcdrain` succeed. Sending a break
  stays refused. Static programs, which don't load it (Go ones, such
  as `arduino-cli monitor`), get the same from proot itself, for the
  modem-line calls, `TIOCEXCL` and `tcdrain` (which doesn't wait);
  `flock` and `tcflow` stay refused for them. With `serial-preload
  off`, or `unset LD_PRELOAD`, every program gets just that:
  picocom then needs `--nolock`.
- **Finding the ports:** each port is also `/dev/ttyUSB0` or
  `/dev/ttyACM0` (a link to its `/dev/serial` file), and
  `/sys/class/tty/NAME` has its USB details as on Linux (vendor and
  product IDs, serial number, maker and name). So tools that look for
  ports find them by themselves: `python3 -m serial.tools.list_ports
  -v`, `esptool` with no `--port`, PlatformIO's auto-detect,
  `arduino-cli board list` (also `board list --watch`). Android
  doesn't give apps the kernel's USB event socket, which tools watch
  for ports coming and going: proot gives a program that opens it a
  stand-in, and the app sends it the kernel's `add` and `remove`
  messages for the ports' ttys (no others). It's the `serial-names` setting (on;
  `mynx set serial-names off`, then a new tab, turns it off): for it,
  Debian has a `/dev` of its own, which the app fills (the
  phone's `null`, `zero`, `random`, `urandom`, `tty`, `ptmx`, `pts`,
  the GPU's files, …; not its own `ttyS*` ports). With it off, or in
  a tab opened while it was off, name the port: `/dev/serial/…`.
  esptool still says "Failed to get PID of a device on /dev/pts/N"
  (it follows the link to the terminal): harmless, it uses the usual
  reset.
- **Boards held in reset while the port is open:** some boards'
  auto-reset circuit (the ESP32-CAM-MB's, for one) keeps the chip in
  reset while DTR and RTS are both on, and opening a `/dev/serial`
  file raises them, as on Linux. Lower them as on a PC:
  `python3 -m serial.tools.miniterm --dtr 0 --rts 0
  /dev/serial/ttyUSB0 115200` resets the board and lets it boot
  (PlatformIO: `monitor_dtr = 0`, `monitor_rts = 0`). `picocom
  --lower-dtr --lower-rts …` opens without resetting it; Ctrl+A
  Ctrl+G twice (RTS up, then down) resets it. A board stuck in
  "waiting for download" was reset with DTR still on: reset it that
  way.
- `mynx usb` lists the devices with their ports, paths and URLs;
  `mynx usb url` and `mynx usb path` print the only port's (or
  `PORT`'s) for scripts. `--json` gives `devices`: `id`, `ids`
  (vendor:product), `manufacturer`, `product`, `serial`, `driver`,
  `allowed`, `error` and `ports` (`name`, `path`, `url`, `tcp_port`,
  `in_use`, and `waiting`: its file is open but the URL has the port).
- The tools: `apt install picocom` (or `minicom`, `screen`),
  `python3-serial` (pyserial, miniterm), `esptool`, `avrdude`;
  PlatformIO with `pip`, `arduino-cli` from Arduino. Debian's
  `esptool` has no flasher stub for the original ESP32, S2 and S3
  ("No such file … stub_flasher_32.json"): add `--no-stub` (slower),
  or install esptool with `pip`.
- Each port's helper (it makes the terminal device) logs to
  `/tmp/.mynx/serial/NAME.log`.
- The URL is on localhost: other apps on the phone could reach it
  too, while mynx runs and the device is plugged in.
- **Serial monitor:** `mynx usb monitor [PORT] [--baud N]` (the
  `serial` command, the menu's Apps → Serial monitor) shows what the
  board sends and sends what you type, Enter as CR, at 115200 baud
  unless given. With several ports it asks which. Its key bar,
  `serial`, has Esc, Ctrl, Tab, the arrows, Ctrl+C, Ctrl+D (a
  MicroPython REPL's) and its commands, Ctrl+T then a key: `q` quit
  (Ctrl+] too), `r` reset the board (an RTS pulse for an ESP32's EN,
  then a DTR pulse for an Arduino's reset), `b` into the bootloader
  (esptool's sequence; the 1200 baud touch for Arduino's native USB
  and the Pico), `+`/`-` baud, `x` hex, `s` timestamps, `e` local
  echo, `n` line ending (CR, LF, CR LF), `D`/`R` DTR/RTS on or off,
  `h` the keys. Opening raises DTR and RTS, as on Linux; `--lower`
  drops them (DTR first: the ESP32-CAM-MB boots). When the port goes
  away (unplugged, a board restarting its USB) it waits and opens it
  again. DTR and RTS go to the port's helper, through the serial
  library or proot.
- **Recipes** (name the port: `/dev/serial/…` or its URL, from
  `mynx usb`; `mynx usb path` prints the only one's):
  - **esptool** (`apt install esptool`): `esptool --port
    /dev/serial/ttyACM0 chip_id`; flash with `esptool --port … write_flash
    0x0 firmware.bin` (`--no-stub` for the original ESP32, S2 and S3
    with Debian's esptool, see above). Then `mynx usb monitor`.
  - **MicroPython** with `mpremote` (`apt install pipx`, `pipx install
    mpremote`): `mpremote connect /dev/serial/ttyACM0 ls`, `… cp
    main.py :`, `… repl`; the URL works too. Its shortcuts (`a0`,
    `u0`, `auto`) look for `/dev/ttyACM0` and don't find it.
  - **arduino-cli** (Arduino's installer: `curl -fsSL
    https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh
    | BINDIR=~/.local/bin sh`): `arduino-cli core install arduino:avr`,
    then **`mynx fix-links ~/.arduino15`** (after every core or tool
    install: see below), `arduino-cli compile -b
    arduino:avr:nano:cpu=atmega328old Blink` and `arduino-cli upload -p
    /dev/serial/ttyUSB0 -b arduino:avr:nano:cpu=atmega328old Blink`
    (it runs avrdude, which the serial library covers);
    `arduino-cli board list` finds the board, and `arduino-cli monitor
    -p /dev/serial/ttyUSB0 -c baudrate=115200` shows its output, as
    `mynx usb monitor` does. Boards that
    upload after a 1200 baud touch (Leonardo, Micro, Pro Micro) jump
    to a bootloader that is a new USB device for about 8 s: Android
    asks for it again (tick "always" once) and it gets a port of its
    own; upload to that one with avrdude: `stty -F
    /dev/serial/ttyACM0 1200` (the touch), then `avrdude -p atmega32u4
    -c avr109 -P /dev/serial/ttyACM0 -U flash:w:Blink.ino.hex` with the
    port `mynx usb` shows then (`arduino-cli compile --output-dir .`
    writes the `.hex`).
  - **PlatformIO** (`apt install pipx`, `pipx install platformio`):
    in `platformio.ini`, `upload_port = /dev/serial/ttyUSB0` and
    `monitor_port = /dev/serial/ttyUSB0` (or the URL), since it can't
    find ports itself; `pio run -t upload`, `pio device monitor`.
    Its AVR toolchain installs and builds.
    Its ESP32 toolchains install and build (`platform = espressif32`;
    for the C3, about 6 GB in `~/.platformio` and a first build of
    about 2 minutes); a big build needs Android's process limit off
    (see "How it runs"). While installing, PlatformIO may say
    "Directory not empty" and "Please manually remove" for its own
    temporary folders: harmless.
  - **avrdude** (`apt install avrdude`): `avrdude -p atmega328p -c
    arduino -b 115200 -P /dev/serial/ttyUSB0 -U flash:w:sketch.hex`
    (57600 for old Nano bootloaders); opening the port resets the
    board into its bootloader.
- **`mynx fix-links DIR…`:** proot can't make hard links on Android,
  so it fakes them with symlinks that hold absolute paths (to
  `.l2s.*` files). An installer that unpacks into a temporary folder
  and then moves it breaks them: arduino-cli's toolchains fail with
  "fork/exec …/avr-g++: no such file or directory" and the files
  can't even be listed ("Operation not permitted"). `mynx fix-links
  ~/.arduino15` makes each broken name a copy of its file. `--json`
  gives `fixed` (how many, by folder).

### Themes and colours

Built-in themes: `neon` (the default), `amber`, `phosphor`, `dracula`,
`nord`, `gruvbox-dark`, `solarized-dark`, `solarized-light`,
`catppuccin-mocha`, `tokyo-night`. `mynx theme set NAME` writes the
theme into `~/.config/mynx/colors.properties`, with
`# theme: NAME` on its first line; with no colours file the app shows
Neon.

- **Colours file:** one `key=#rrggbb` per line: `background`,
  `foreground`, `cursor`, `color0` to `color255` (0-7 normal, 8-15
  bright). Keys left out keep the Neon colour. Edit it, then
  `mynx check`.
- **Your own theme:** write
  `~/.config/mynx/themes/NAME.colors.properties` (same
  format; start from `mynx theme show neon`), then
  `mynx theme set NAME`. Yours wins over a built-in one with the same
  name.
- The tab strip, key bar and launcher menu take their colours from the
  theme (the menu uses the 16 basic colours).

### Launcher menu

`menu` opens it (the app opens it in the first tab when it starts
fresh; the very first time, it shows the welcome page,
`/opt/mynx/welcome.txt`, after the boot splash). Its items come from `~/.config/mynx/menu.conf` if it
exists, else `/opt/mynx/menu.conf`. One `Label = action` per
line, in order; labels up to 20 characters. Actions:

| Action | Does |
|---|---|
| `shell` | leave the menu for the shell |
| `files` | the file manager |
| `apps` | the Apps menu (see "Apps") |
| `games` | the Games menu |
| `settings` | the Settings menu: App settings, System, Help |
| `app-settings` | the settings editors (`mynx edit`) |
| `agents` | AI agents: start one, or install it (`mynx agent start`) |
| `services` | the background services with their state; each offers Open, Start or Stop, Restart and Log |
| `system` | Update all, System info, and GitHub setup (`mynx github`) once GitHub is set up |
| `help` | Getting started (`mynx welcome`), Release notes (`mynx notes`), Report a bug (`mynx report`), About (`mynx about`) |
| `welcome` | the welcome page (`mynx welcome` prints it) |
| `exit` | close the tab |
| `run COMMAND` | run a command in bash, e.g. `Top = run htop` |

Until `gh` is signed in and git's email is set, the main menu has
"Set up GitHub" (`mynx github`) after AI agents (last if there's no
AI agents item), whatever the menu file says.

To change it: `mynx menu edit`, edit the file, `mynx check` (it
checks the menu file too). Any executable file in `~/games` shows up
under Games, named after the file: `ln -s /path/to/game ~/games/my-game`
shows "My Game". The menu starts games with `play`. Apps work the same
way from `/opt/mynx/apps` and `~/apps`, named by a `# label: Name` line
near the top of the file.

### Key bars

Two rows of buttons above the keyboard, always visible (more than fit
go on pages: swipe sideways). When the screen is short, as in landscape
with the keyboard up, the bar shrinks to one row, and if the terminal
still has too little room the tab strip hides (swiping between tabs
still works). Holding an arrow (or any button marked
`Repeat`) repeats it. Which bar shows depends on what's running:
`keybar NAME[,FALLBACK…] command…` shows the first of those bars that
exists while the command runs, then the previous one; with no command
running it's the shell's bar. `files` uses `nnn`, `menu` uses `menu`,
the editors use `mynx-edit`, and `mynx about` and `mynx notes` page
with `pager`.

Built-in bars: `shell`, `nnn`, `menu`, `mynx-edit`, `pager`, the games'
`neon-rogue`, `neon-drive`, `neon-flap`, `game` for any other game, the
apps' `compass`, `incline`, `torch`, `spectrum`, `dbmeter`, `tuner` and
`metronome`, `serial` for the serial monitor, `service` for a background service shown as a tab, and
`agent` for AI agents (`claude`, `codex` and `gemini` typed in the shell,
or started with `mynx agent start`; an agent's own bar, e.g. `claude`,
wins when it exists). Typing them by name works through bash functions
from `/opt/mynx/shell.bash`; `unset -f claude` in `~/.bashrc`
turns that off.
A file `~/.config/mynx/keybars/NAME.conf` replaces or adds
one. To change a built-in bar: `mynx keybar edit NAME`, edit the copy,
`mynx check`. Buttons fill two rows in file order, the first half on
top. Format, one button per line:

```
# label = keys, run in order
Open   = l
Rename = Ctrl+R
Files  = "files" Enter
Ctrl   = Ctrl
```

- Key names (any case): Enter Esc Tab Space Backspace Delete Insert Up
  Down Left Right Home End PgUp PgDn F1-F12.
- Any single character is a key: `l`, `/`, `=`.
- Modifiers: `Ctrl+R`, `Alt+Left`, `Shift+Tab`, `Ctrl+Alt+Delete` (Shift: named keys only).
- `"text"` is typed as is (`\"` for a quote).
- `Ctrl` on its own is a sticky Ctrl for the next key typed.
- `Repeat` anywhere on the line: the button repeats while held. Arrows,
  PgUp/PgDn, Backspace and Delete repeat anyway. Repeating buttons send
  as soon as they're touched; others on release.

**The phone's volume keys** can send keys too, while the app is on
screen. Two labels are reserved: a `VolumeUp` or `VolumeDown` line
(any case) isn't a button and takes no room on the bar; it says what
that volume key sends while the bar shows, written like a button's
keys. A press sends them once; holding repeats only with `Repeat`
(arrows don't repeat on their own here). A key the bar showing doesn't
set changes the volume as usual, so set them per bar; no built-in bar
does. For example, in `mynx keybar edit shell`:

```
VolumeUp   = Up Repeat
VolumeDown = Down Repeat
```

**Giving a program or game its own bar** (also what an installer or an
agent setting up a program should do): `mynx keybar edit NAME`, write
the buttons, `mynx check`, then run it under that bar:
`keybar NAME program`, or `play my-game` for a game (bar named after
the file). An alias in `~/.bashrc` makes it stick:
`alias htop='keybar htop htop'`. Fallbacks: `keybar my-tool,game …`
shows the generic game bar until `my-tool.conf` exists.

## More of the setup

- **File manager:** `files [folder]` runs nnn in detail mode (`?` lists
  its keys). Quick places on `b`: `h` home, `d` Download, `p` Pictures,
  `c` DCIM, `r` `/`; set `NNN_BMS` in `~/.bashrc` for your own (format
  `key:path;key:path`). Text files open in `$EDITOR` (nano).
- **Apps:** example programs that use the phone, in Python, to run,
  read and copy. Each is a command and an item in the menu's Apps,
  shown with its own key bar; `--help` lists its keys.

  | Command | What it is | Uses |
  |---|---|---|
  | `compass` | a compass card with a bearing to follow | `mynx sensor compass` |
  | `incline` | a spirit level for flat or on an edge | `mynx sensor accelerometer` |
  | `torch` | the flashlight: strobe, SOS, Morse, … | `mynx torch` |
  | `spectrum` | a live sound spectrum (`--demo` without a mic) | the microphone |
  | `dbmeter` | a sound level meter | the microphone |
  | `tuner` | a guitar tuner (`--demo`) | the microphone, the speaker, numpy |
  | `metronome` | a metronome with tap and clap tempo | the speaker, the flashlight, vibration, numpy |
  | `serial` | a serial monitor for a USB board (`mynx usb monitor`) | a `/dev/serial` port |

  The microphone apps read it through the sound device with `parec`
  (`/opt/mynx/apps/mic.py`). `tuner` and `metronome` need numpy and
  offer to install it (`apt install python3-numpy`) the first time.
  Settings they keep go in `~/.config/NAME/`. **Your own app:** put an
  executable in `~/apps` (or link one there) with a `# label: Name`
  line near the top, and give it a bar: `mynx keybar edit NAME`; the
  menu runs it as `keybar NAME,shell FILE`. For an Android app (an
  APK, a home-screen widget) instead, see "Making an app".
- **Games:** `play GAME [args]` runs a game with the bar named after its
  file (`my-game.py` → `my-game`), else the generic `game` bar. `rogue`,
  `drive` and `flap` use it.
- **Updates:** the app updates from its GitHub releases. About once
  a day (the `update-check` setting) it asks GitHub for the latest
  one; when it's newer, a notification says so and the launcher menu
  starts with **Update available**. Both run `mynx update`: it shows
  the new version, its release notes and the download's size, asks,
  then the app downloads the APK (checking its size and GitHub's
  sha256) and opens Android's installer; tap Update there. The first
  time, Android asks to allow the app to install apps. The app closes
  while it updates; your tabs, Debian and everything in it stay.
  `mynx update --check` only says whether there's one (`--json`:
  `current`, `latest`, `available`, and with one `tag`, `notes`,
  `size`, `published`). The check and download run in the app, not
  Debian, so they work even if Debian's network tools are broken.
- **What's new and release notes:** every release's notes ship with
  the app (`/opt/mynx/release-notes.json`), so they work offline.
  After an update, the first terminal that opens shows **What's new**:
  the notes of each version since the one before (`mynx notes new`),
  once (`mynx notes seen` records the version; the state is
  `~/.local/state/mynx/notes-seen`). `mynx notes` (or the menu's
  Settings → Help → Release notes) shows them all. `--json` gives
  `releases` (`version`, `published`, `notes` in GitHub's Markdown);
  with `new`, also `current` and `seen`.
- **Bug reports:** `mynx report "what went wrong"` (or the menu's
  Settings → Help → Report a bug) builds a report: the description, the app's
  version, the Android version and phone model, and the app's last
  crash, if any (`--no-crash` leaves it out). It shows all of it and
  asks. When `gh` is installed and signed in, yes sends it as a new
  GitHub issue from that account; if that fails, it offers the
  browser instead. Without `gh`, yes opens a new GitHub issue in the
  phone's browser, filled in, and nothing is sent until the user
  submits it there with their own GitHub account. With no TEXT it
  asks what went wrong.
  For agents: `mynx report TEXT --json` gives `title`, `what`,
  `details` and `url` and opens nothing; show the user the report and
  only `mynx open URL` once they agree.
- **GitHub:** `mynx github` (or the menu's Settings → System → GitHub
  setup)
  installs `git` and `gh` if they're missing (asking first), signs in
  with `gh auth login` in the phone's browser and copies the one-time
  code to the clipboard, so it only needs pasting. Already signed in,
  it asks whether to switch accounts. It sets git's `user.name` (the
  account's name) and `user.email` (GitHub's noreply address,
  `ID+login@users.noreply.github.com`), asking before it replaces
  ones already set, and makes `git push` use gh's sign-in (`gh auth
  setup-git`). `--yes` installs and replaces without asking (and
  keeps the account). mynx stores no token: `gh` keeps its own
  sign-in. `mynx github status` (`--json`: `gh`, `git`, `login`,
  `name`, `email`) changes nothing. With `gh` signed in, `mynx
  report` files issues itself.
- **Tab titles:** a program sets its tab's title with the escape code
  `\e]0;title\a`. The prompt sets it to the current folder name. A name
  the user gives a tab (long-press it, then Rename) wins over these. A
  service's tab is always titled with the service's name.
- **Prompt hook:** the app sets `PROMPT_COMMAND` so it can reopen each
  tab in its folder after Android closes the app. If you change the
  prompt setup in `~/.bashrc`, keep whatever `PROMPT_COMMAND` the shell
  started with (starship keeps it on its own).

## Making an app

**When someone asks for an app, ask which kind**, unless they said:

- **A terminal app** that lives in mynx: a program in `~/apps` with
  its own key bar, in the menu's Apps (see "Apps" above). Quick to
  write and change, in Python, bash or anything Debian runs, and it
  reaches the phone through `mynx`: sensors, location, camera,
  microphone, flashlight, vibration, notifications. It runs only
  inside mynx.
- **An Android app** (an APK), installed on the phone like any other:
  its own icon, home-screen widgets, the whole Android API, and it
  runs without mynx. Each change is a build and an install, in plain
  Java (no Kotlin, Gradle or AndroidX libraries).

Hints: "widget", "on my home screen", "give it to a friend" → an
Android app. "Script", "tool", "uses my files or commands here",
"quick" → a terminal app. Both at once works too: an Android widget
can show what a terminal program serves (see below).

### Building an Android app

The tools, once (about 110 MB; `r8.jar` gives `d8`, `android.jar` is
Android 14's API):

```
apt install aapt zipalign apksigner openjdk-21-jdk-headless unzip
mkdir -p ~/androidsdk && cd ~/androidsdk
curl -fLo r8.jar https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar
curl -fLo platform.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -j platform.zip android-34/android.jar -d android-34 && rm platform.zip
```

A project is `AndroidManifest.xml`, `res/` and `src/`, with a
`build.sh` that runs, with `JAR=~/androidsdk/android-34/android.jar`:

```
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$JAR"
javac --release 11 -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
java -cp ~/androidsdk/r8.jar com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
aapt package -f -M AndroidManifest.xml -S res -I "$JAR" -F build/unsigned.apk
(cd build/dex && aapt add ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks release.keystore --out NAME.apk build/aligned.apk
```

(`-A assets` on the second `aapt package` adds an `assets/` folder.)
A build takes about a minute.

- **Signing:** make the keystore once per app (`keytool -genkeypair
  -keystore release.keystore …`) and keep it: Android installs an
  update only when it's signed with the same key. Raise
  `versionCode` in the manifest for each update.
- **The package name** (`package=` in the manifest) must be unique on
  the phone, e.g. `com.NAME.app`.
- **Installing:** serve the folder with `python3 -m http.server 8765
  --bind 127.0.0.1` (in the background) and `mynx open
  http://127.0.0.1:8765/NAME.apk`; the browser downloads it, and
  **Open** starts Android's installer. mynx must be on screen: when
  `mynx open` says it isn't, ask the user to come back to it, then
  run it again. The first time, Android asks to allow the browser to
  install apps.
- **No adb or logcat here:** you can't read the app's log or see why
  it crashed. Have it write errors where Debian can read them, e.g. a
  `Thread.setDefaultUncaughtExceptionHandler` that writes to
  `/storage/emulated/0/Download/NAME-crash.txt`.
- **Hardware:** each permission goes in the manifest
  (`<uses-permission>`); camera, location, microphone, contacts and
  the like are also asked at run time (`requestPermissions`).
- **Internet:** the `INTERNET` permission, then `HttpURLConnection`.
  Android blocks plain `http://` unless the manifest's
  `android:networkSecurityConfig` allows it, e.g. for one host in
  `res/xml/network_security_config.xml`:
  `<network-security-config><domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain></domain-config></network-security-config>`
- **Widgets** (an `AppWidgetProvider`) show only RemoteViews: text,
  images, buttons, lists, `TextClock`; no custom views or WebView
  (draw a `Bitmap` for a chart). They update on their own at most
  every 30 minutes (`updatePeriodMillis`); more often needs
  `AlarmManager` or `JobScheduler`, or a tap
  (`setOnClickPendingIntent`).
- **Running in the background:** short jobs through `JobScheduler`;
  anything that keeps running needs a foreground service with a
  notification.

**An Android app and Debian:** an app or widget can talk to a server
running here as a background service (`mynx service add`, see
"Background services"), at `http://127.0.0.1:PORT` with plain `http`
allowed for `127.0.0.1` as above. So a widget can show what a script
works out, or its buttons can run commands. It only works while mynx
runs; tell the user so.
