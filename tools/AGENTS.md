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
- **No systemd or init.** Services don't start on their own; run daemons
  in a tab and leave it open. Each tab is its own login bash.
- **Some `/proc` files are static stand-ins**, because Android hides the
  real ones: `/proc/stat`, `/proc/vmstat`, `/proc/uptime`,
  `/proc/loadavg`, `/proc/version`. CPU usage and uptime from tools like
  `htop` or `uptime` aren't real.
- **Network works** (DNS: 1.1.1.1 and 8.8.8.8 in `/etc/resolv.conf`).
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
| Shell setup: eza aliases, prompt, tab titles, menu hook | `~/.bashrc` |
| Prompt (starship) | `~/.config/starship.toml` |
| Menu state (last choices) | `~/.local/state/mynx/menu` |
| Apps | `/opt/mynx/apps` (`compass`, `incline`, `torch`, `spectrum`, `dbmeter`, `tuner`, `metronome`), yours in `~/apps` |
| Games | `/opt/neon-games` (`rogue`, `drive`, `flap`), yours in `~/games`; `play` runs one with its key bar |
| The app's tools: `mynx`, `menu`, `files`, `keybar`, `play`, the editors | `/opt/mynx/bin` |
| Built-in key bars and themes, to read or copy | `/opt/mynx/keybars`, `/opt/mynx/themes` |
| Licence texts of what the app bundles (`mynx about`) | `/opt/mynx/licenses` |

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
| `mynx vibrate [MS]` | vibrate the phone, 300 ms unless given (1 to 5000) |
| `mynx clipboard get` | print the phone's clipboard (the app must be on screen) |
| `mynx clipboard set [TEXT]` | copy TEXT to the phone's clipboard, or stdin when there's no TEXT |
| `mynx share FILE…` | send files to another app through Android's share sheet (the app must be on screen) |
| `mynx share --text [TEXT]` | send TEXT, or stdin when there's no TEXT, to another app |
| `mynx location` | where the phone is: one fix; `--stream` keeps printing them (see "The phone") |
| `mynx sensor list` | the phone's sensors, with their values and units |
| `mynx sensor NAME` | one reading of a sensor; `--stream` keeps printing them (see "The phone") |
| `mynx camera FILE` | take a photo with the phone's camera app, saved to FILE; `--quick front\|back` snaps one with no screen |
| `mynx torch on [PERCENT]\|off` | the phone's flashlight |
| `mynx rotation lock [portrait\|landscape]` | stop the screen turning while the program that ran it runs; `unlock`, `status` |
| `mynx audio play FILE` | play a sound file through the phone's speaker, until it ends |
| `mynx audio record FILE` | record from the microphone to FILE (`.m4a`, `.aac`, `.ogg`, `.opus`, `.wav`) until Ctrl+C or `--seconds N` |
| `mynx sound` | whether the sound device is on; `mynx sound start` starts it again, `mynx sound install` installs it (see "The phone") |
| `mynx agent list` | the AI agents: installed or not, notifications on or off (`--tsv` for scripts) |
| `mynx agent start [NAME]` | start an agent in this terminal; offers to install it first if it's missing |
| `mynx agent install NAME` | install `claude`, `codex` or `gemini` with its official installer (shows the commands and asks first; `--yes`, `--notify`/`--no-notify`, and for Codex `--sandbox-off`/`--sandbox-on`, skip the questions) |
| `mynx agent notify NAME on\|off` | an agent's phone notifications (adds or removes its hooks) |
| `mynx hook claude\|codex\|gemini` | run by the agents' hooks (see "AI agents"); reads the hook's JSON on stdin |
| `mynx edit` | the settings editors, for people (full screen) |
| `mynx welcome` | the welcome page new users see: what's here and how to get around |
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
- `wakelock`: `on` or `off` (default): whether the app keeps the
  phone's CPU awake while it runs, so long jobs (builds, downloads)
  don't pause when the screen goes off. It uses more battery; the
  app's notification says "wakelock held" while it's on.
- `update-check`: `on` (default) or `off`: whether the app checks
  GitHub about once a day for a new version and tells you (see
  "Updates"). `mynx update` works either way.

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

### The phone

Commands for the phone itself, and the sound device:

- `mynx vibrate [MS]`: a buzz, e.g. `make && mynx vibrate`.
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
| `system` | Update all, System info, GitHub setup (`mynx github`) |
| `help` | Getting started (`mynx welcome`), Report a bug (`mynx report`), About (`mynx about`) |
| `welcome` | the welcome page (`mynx welcome` prints it) |
| `exit` | close the tab |
| `run COMMAND` | run a command in bash, e.g. `Top = run htop` |

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
the editors use `mynx-edit`.

Built-in bars: `shell`, `nnn`, `menu`, `mynx-edit`, the games'
`neon-rogue`, `neon-drive`, `neon-flap`, `game` for any other game, the
apps' `compass`, `incline`, `torch`, `spectrum`, `dbmeter`, `tuner` and
`metronome`, and
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

  The microphone apps read it through the sound device with `parec`
  (`/opt/mynx/apps/mic.py`). `tuner` and `metronome` need numpy and
  offer to install it (`apt install python3-numpy`) the first time.
  Settings they keep go in `~/.config/NAME/`. **Your own app:** put an
  executable in `~/apps` (or link one there) with a `# label: Name`
  line near the top, and give it a bar: `mynx keybar edit NAME`; the
  menu runs it as `keybar NAME,shell FILE`.
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
  the user gives a tab (long-press it) wins over these.
- **Prompt hook:** the app sets `PROMPT_COMMAND` so it can reopen each
  tab in its folder after Android closes the app. If you change the
  prompt setup in `~/.bashrc`, keep whatever `PROMPT_COMMAND` the shell
  started with (starship keeps it on its own).
