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

**More in `/opt/mynx/docs/`**, read the one you need when the task
comes up:

| File | About |
|---|---|
| `/opt/mynx/docs/phone.md` | the phone: vibration, clipboard, sharing, location, sensors, battery, camera, flashlight, rotation, sound files, the sound device, sharing to Debian, files and viewers (`mynx open FILE`) |
| `/opt/mynx/docs/apps.md` | apps in HTML and JavaScript, the phone from JavaScript (`mynx.js`), building an Android app (APK) |
| `/opt/mynx/docs/services.md` | background services: servers and daemons kept running by the app |
| `/opt/mynx/docs/usb.md` | USB serial devices (Arduino, ESP32, adapters, board tools' recipes), raw USB devices (libusb, picotool, UF2, JTAG) |
| `/opt/mynx/docs/bluetooth.md` | Bluetooth serial devices (SPP) and Bluetooth LE devices (GATT, BLE UART ports) |

## How it runs

- **proot, not a VM or container.** Linux system calls go straight to
  Android's kernel; proot only rewrites paths and fakes root.
- **Fake root.** You're uid 0 inside, but Android sees an ordinary app.
  You can't change the kernel, load modules, mount filesystems, or bind
  ports below 1024. There's no `sudo` (you don't need it).
- **No systemd or init.** Nothing starts on its own. Servers and
  daemons run as the app's background services instead (`mynx
  service`, see "Background services" in `/opt/mynx/docs/services.md`), or in a tab left open. Each
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
  `/storage/emulated/0`; to see other apps' files there, Android
  must allow it: `mynx storage allow`.
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
| Apps (HTML and JavaScript, on a screen of their own) | `/opt/mynx/apps` (`sketch`, `gadgets`), yours in `~/apps` (see "Apps" in `/opt/mynx/docs/apps.md`) |
| What each app may use from the phone | `~/.config/mynx/app-permissions.conf` (see "The phone from JavaScript" in `/opt/mynx/docs/apps.md`) |
| Terminal apps | `/opt/mynx/terminal-apps` (`compass`, `incline`, `torch`, `spectrum`, `dbmeter`, `tuner`, `metronome`, `serial`), yours in `~/terminal-apps` (and `~/apps`, from before) |
| Games | `/opt/neon-games` (`rogue`, `drive`, `flap`), yours in `~/games`; `play` runs one with its key bar |
| The app's tools: `mynx`, `menu`, `files`, `keybar`, `play`, the editors | `/opt/mynx/bin` |
| Built-in key bars and themes, to read or copy | `/opt/mynx/keybars`, `/opt/mynx/themes` |
| Licence texts of what the app bundles (`mynx about`) | `/opt/mynx/licenses` |
| USB serial ports (boards, adapters) | `/dev/serial/ttyUSB0`, `rfc2217://localhost:2217` (see "USB serial devices" in `/opt/mynx/docs/usb.md`) |
| Other USB devices, for libusb tools | `/dev/bus/usb/BBB/DDD`, `/sys/bus/usb/devices` (see "Raw USB devices" in `/opt/mynx/docs/usb.md`) |
| Bluetooth serial ports: which device is which port | `~/.config/mynx/bluetooth.conf`; the ports are `/dev/serial/rfcomm0`, `/dev/serial/nus0`, … (see `/opt/mynx/docs/bluetooth.md`) |

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
| `mynx open FILE` | open a file in mynx's viewer (text, code, Markdown, pictures, audio, video, PDF, HTML) or another app; asks which in a terminal, `--with mynx\|android` says, `--edit` opens text to edit (`/opt/mynx/docs/phone.md`) |
| `mynx vibrate`, `clipboard`, `share`, `location`, `sensor`, `battery`, `camera`, `torch`, `rotation`, `audio`, `sound` | the phone: vibration, clipboard, share sheet, GPS, sensors, battery, camera, flashlight, screen rotation, sound files and the sound device (`/opt/mynx/docs/phone.md`) |
| `mynx app` | apps in HTML and JavaScript: list, open, reload, log, home screen shortcut, permissions (`/opt/mynx/docs/apps.md`) |
| `mynx service` | background services: list, add, start, stop, log, open as a tab, autostart, adopt a tab (`/opt/mynx/docs/services.md`) |
| `mynx usb`, `uf2`, `fix-links` | USB serial ports and raw USB devices, a serial monitor, UF2 flashing (`/opt/mynx/docs/usb.md`) |
| `mynx bt`, `ble` | Bluetooth serial ports, Bluetooth LE devices (`/opt/mynx/docs/bluetooth.md`) |
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
  can read and change the phone's clipboard (see "The phone" in `/opt/mynx/docs/phone.md`).
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
- `android-bluetooth`: `on` (default) or `off`: whether programs
  here can use Bluetooth: serial ports (`mynx bt`; see "Bluetooth
  serial devices") and Bluetooth LE devices (`mynx ble`; see
  "Bluetooth LE devices" in `/opt/mynx/docs/bluetooth.md`).
- `sound-device`: `on` (default) or `off`: whether the app runs the
  sound device, so programs here play through the phone's speaker.
- `share-folder`: where files other apps share to this app are saved,
  a full path or one starting with `~/` (default `~/Shared`).
- `tab-swipe`: `on` (default) or `off`: whether a quick sideways
  swipe on the terminal goes to the next tab (swipe left) or the
  one before (swipe right). It stops at the first and last tab;
  scrolling, pinching and selecting text are left alone.
- `view`: `terminal` (default) or `touch`: what the app shows (the
  launcher menu's Settings → Touch view switches to it). `touch`
  opens on Home, the launcher menu's items as lists to tap, from the
  same `menu.conf` and app folders; programs open in a tab. Its
  Settings are lists to tap too: Appearance (the view and the theme,
  for both views), AI agents, Terminal, Phone access, Background, undo,
  System info and Help (Update all, GitHub setup and the key bar and
  menu editors still run in a tab). Terminal
  (top right) shows the tabs, and ⌂ on the tab strip or Back comes
  back to Home. In `touch`, Home's AI agents opens an installed Claude
  Code's chat history: New chat, then its chats from every folder
  (another folder's named on its row; it goes on there), newest
  first, marked while one works or waits for you. A chat shows
  messages, tool cards and Allow/Deny; Back goes to the history, and
  Back again to Home. Several chats can work at once, each its own
  Claude Code: one left while it works keeps going, one left idle is
  stopped and goes on (`--resume`) with its next message. The other
  agents run in a tab. In a chat's ⋮,
  Open in a tab moves it to a terminal tab (`claude --resume`); one
  session runs in one place at a time. Each of Claude's replies has
  Copy under it. When Claude Code makes or changes an app in `~/apps`
  or `~/terminal-apps`, writes an APK or a Markdown (`.md`) file, or
  makes a picture (PNG, JPEG, GIF, WebP…; a path its command names)
  or a PDF, or writes an HTML page outside the app folders,
  the chat shows a card for it: Open (a web app), Run (a terminal app,
  in a tab), Copy (a Markdown file's text), View (a picture, shown in
  the card too; a PDF; a page, rendered), View source (a page), Share. Tapping a picture, a card's or one attached to
  a message, shows it full screen (pinch or double-tap to zoom).
  Touch view's Files is a file manager to tap (nnn is in Terminal
  apps): long-press to select, then Copy, Cut, Rename, Delete
  (permanent) or Share; Paste here; Chat here and Terminal here start
  Claude Code's chat or a shell in the folder shown.
- `chat-mode`: `auto` (default), `default`, `plan` or `acceptEdits`:
  the permission mode new chats of touch view's Chat start in. `auto`
  lets Claude Code decide what's safe to run, asking less; `default`
  asks first. A mode picked in a chat stays with that chat, also after
  leaving it; the chat's "Use as default" sets this setting. Never
  `bypassPermissions`.
- `chat-model`: `default` (default) or a model's name (`opus`,
  `sonnet`, `haiku`, or a full one like `claude-sonnet-5-5`): the
  model new chats of touch view's Chat start with. `default` passes
  none, so Claude Code uses its own. A model picked in a chat stays
  with that chat, and a past chat keeps the one it ran; the chat's
  + → Model → "Default for new chats" sets this setting.
- `wakelock`: `auto` (default), `on` or `off`: whether the app keeps
  the phone's CPU awake, so servers and long jobs (builds, downloads)
  don't pause when the screen goes off. `auto` holds it only while a
  background service or a turn of touch view's Chat runs; `on`
  whenever the app runs. It uses more
  battery; the app's notification says "wakelock held" while it's on.
- `update-check`: `on` (default) or `off`: whether the app checks
  GitHub about once a day for a new version and tells you (see
  "Updates"). `mynx update` works either way.
- `serial-preload`: `on` (default) or `off`: whether programs here
  load the app's serial library (`LD_PRELOAD`), so tools like esptool
  and picocom work on `/dev/serial` ports (see "USB serial devices" in `/opt/mynx/docs/usb.md`).
  New tabs and services follow it.
- `serial-names`: `on` (default) or `off`: whether USB
  serial ports also appear as `/dev/ttyUSB0` and `/dev/ttyACM0`, with their USB
  details in `/sys/class/tty`, so tools find them by themselves. For
  that Debian gets a `/dev` of its own, with the phone's device files
  it needs (`null`, `tty`, `pts`, …); `off` shows Android's `/dev` as
  before. New tabs and services follow it.
- `open-pictures`, `open-markdown`, `open-code`, `open-text`,
  `open-audio`, `open-video`, `open-pdf`, `open-html`: `mynx`
  (default) or `android`: where each kind of file opens (Files, chat,
  `mynx open`): mynx's viewer or another app. See "Files and viewers" in `/opt/mynx/docs/phone.md`.

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
silences all agents at once. Touch view's chats notify the same way,
each with a notification of its own, when that chat isn't on screen
(Claude Code's switch and both settings apply); tapping one opens its
chat.

Your own scripts can notify too: `long-job && mynx notify "Done" "long-job finished"`.

**Signing in:** `BROWSER` points at `xdg-open`, which opens links in the
phone's browser, so an agent that opens a browser to sign in just works
(the app must be on screen). Tapping a link shown in the terminal opens
it too, even one spread over several rows.

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
| `apps` | the Apps menu: the apps, in HTML and JavaScript (see "Apps" in `/opt/mynx/docs/apps.md`), then **Add to home screen…** |
| `terminal-apps` | the Terminal apps menu (see "Terminal apps" under "More of the setup") |
| `games` | the Games menu |
| `settings` | the Settings menu: App settings, Touch view, System, Help |
| `app-settings` | the settings editors (`mynx edit`) |
| `agents` | AI agents: start one, or install it (`mynx agent start`) |
| `services` | the background services with their state; each offers Open, Start or Stop, Restart and Log |
| `system` | Update all, System info, and GitHub setup (`mynx github`) once GitHub is set up |
| `help` | Getting started (`mynx welcome`), Release notes (`mynx notes`), Report a bug (`mynx report`), About (`mynx about`) |
| `welcome` | the welcome page (`mynx welcome` prints it) |
| `touch-view` | switch the app to touch view (`mynx set view touch`); touch view leaves it out |
| `exit` | close the tab |
| `run COMMAND` | run a command in bash, e.g. `Top = run htop` |

Until `gh` is signed in and git's email is set, the main menu has
"Set up GitHub" (`mynx github`) after AI agents (last if there's no
AI agents item), whatever the menu file says.

To change it: `mynx menu edit`, edit the file, `mynx check` (it
checks the menu file too). Any executable file in `~/games` shows up
under Games, named after the file: `ln -s /path/to/game ~/games/my-game`
shows "My Game". The menu starts games with `play`. Terminal apps work
the same way from `/opt/mynx/terminal-apps`, `~/terminal-apps` and
`~/apps`, named by a `# label: Name` line near the top of the file.
Apps are folders instead (see "Apps" in `/opt/mynx/docs/apps.md`). A menu file from before
terminal apps (with `apps` but no `terminal-apps`) shows "Terminal
apps" at the end of the Apps list.

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
- **Terminal apps:** example programs that use the phone, in Python,
  to run, read and copy. Each is a command and an item in the menu's
  Terminal apps, shown with its own key bar; `--help` lists its keys.

  | Command | What it is | Uses |
  |---|---|---|
  | `compass` | a compass card with a bearing to follow | `mynx sensor compass` |
  | `incline` | a spirit level for flat or on an edge | `mynx sensor accelerometer` |
  | `torch` | the flashlight: strobe, SOS, Morse, … | `mynx torch` |
  | `spectrum` | a live sound spectrum (`--demo` without a mic) | the microphone |
  | `dbmeter` | a sound level meter | the microphone |
  | `tuner` | a guitar tuner (`--demo`) | the microphone, the speaker, numpy |
  | `metronome` | a metronome with tap and clap tempo | the speaker, the flashlight, vibration, numpy |
  | `serial` | a serial monitor for a USB or Bluetooth board (`mynx usb monitor`) | a `/dev/serial` port |

  The microphone apps read it through the sound device with `parec`
  (`/opt/mynx/terminal-apps/mic.py`). `tuner` and `metronome` need
  numpy and offer to install it (`apt install python3-numpy`) the
  first time. Settings they keep go in `~/.config/NAME/`. **Your own
  terminal app:** put an executable in `~/terminal-apps` (or link one
  there) with a `# label: Name` line near the top, and give it a bar:
  `mynx keybar edit NAME`; the menu runs it as `keybar NAME,shell
  FILE`. Executable files in `~/apps`, from before, show up too. For
  an app in HTML (see "Apps" in `/opt/mynx/docs/apps.md`) or an Android app (an APK, a home-screen
  widget) instead, see "Making an app".
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

- **An app** in HTML, CSS and JavaScript: a folder in `~/apps` that
  opens on a screen of its own from the menu's Apps (see "Apps" in `/opt/mynx/docs/apps.md`). No
  build and no install: edit a file and the open screen reloads, so
  it's the quickest to try on the phone. Touch, drawing, Web Audio and
  `localStorage` work as in a browser, a Python backend can serve it
  as a background service, and its JavaScript reaches the phone
  through `mynx.js`: sensors, location, camera, microphone, vibration,
  notifications, Bluetooth LE, serial ports (see "The phone from
  JavaScript"). It can have a home screen shortcut, but runs only
  inside mynx.
- **A terminal app** that lives in mynx: a program in
  `~/terminal-apps` with its own key bar, in the menu's Terminal apps
  (see "Terminal apps" under "More of the setup"). Quick to write and
  change, in Python, bash or anything Debian runs, and it reaches the
  phone through `mynx`: sensors, location, camera, microphone,
  flashlight, vibration, notifications. It runs only inside mynx.
- **An Android app** (an APK), installed on the phone like any other:
  its own icon, home-screen widgets, the whole Android API, and it
  runs without mynx. Each change is a build and an install, in plain
  Java (no Kotlin, Gradle or AndroidX libraries).

Hints: "game", "page", "like an artifact", "quick to try on the
phone", something to tap and draw on, a dashboard for the sensors →
an app. "Script", "tool", "uses my files or commands here" → a
terminal app. "Widget", "on my home screen", "give it to a friend" → an
Android app. Mixes work too: an app or an Android widget can show
what a program here serves (see "Apps" in `/opt/mynx/docs/apps.md`).

**Building an Android app**: the tools, a project's layout, its
`build.sh`, signing and installing are in "Building an Android app" in
`/opt/mynx/docs/apps.md`. Read it before you start one.
