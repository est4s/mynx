# This Debian

Notes for AI agents (and people) working in this terminal. You're root in
a Debian 13 (trixie) arm64 system that runs inside an Android app on a
phone, through proot. The screen is narrow: about 56 columns in portrait.

This guide is `/opt/pc26/AGENTS.md`. It belongs to the app and
is updated with it; `~/AGENTS.md` is yours for your own notes.

**People will ask you to customize their setup** (theme, font, key bars,
the menu). Every setting is a plain-text file in
`~/.config/pc26/`, and the `pc26` command changes, checks
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
| Settings: font size, font, cursor | `~/.config/pc26/settings.conf` |
| Terminal colours | `~/.config/pc26/colors.properties` |
| Your own themes | `~/.config/pc26/themes/` |
| Key bars above the keyboard | `~/.config/pc26/keybars/` |
| Launcher menu items | `~/.config/pc26/menu.conf` (else `/opt/pc26/menu.conf`) |
| Shell setup: eza aliases, prompt, tab titles, menu hook | `~/.bashrc` |
| Prompt (starship) | `~/.config/starship.toml` |
| Menu state (last choices) | `~/.local/state/pc26/menu` |
| Games | `/opt/neon-games` (`rogue`, `drive`, `flap`), yours in `~/games`; `play` runs one with its key bar |
| The app's tools: `pc26`, `menu`, `files`, `keybar`, `play`, the editors | `/opt/pc26/bin` |
| Built-in key bars and themes, to read or copy | `/opt/pc26/keybars`, `/opt/pc26/themes` |
| Licence texts of what the app bundles (`pc26 about`) | `/opt/pc26/licenses` |

`/opt/pc26` belongs to the app and is replaced on every app
update; don't change it. Its `bin` comes first on the PATH. Your changes
go in your home folder (`~/.local/bin` comes before it).

Old names from before the app was called PC-26 still work: `pocket`
runs `pc26`, `/opt/pocket-terminal` is the same folder as `/opt/pc26`,
and in a Debian set up before then `~/.config/pocket-terminal` links to
`~/.config/pc26`. Use the new names in anything you write.

## Changing settings: `pc26`

**After editing any file in `~/.config/pc26/` by hand, run
`pc26 check`**: the app applies the change right away (no need to
leave the app) and lists problems by file and line. Bad lines are
skipped, never fatal. Exit codes: 0 no problems, 1 problems found, 2 an
error (for example not running inside the app). Fix what it lists and
run it again. The `set`, `theme`, `keybar` and `menu` commands apply on
their own.

| Command | Does |
|---|---|
| `pc26 check` | apply the config now and list problems |
| `pc26 settings` | list the settings with values, defaults and descriptions |
| `pc26 get KEY` | one setting's value |
| `pc26 set KEY VALUE` | change a setting, e.g. `pc26 set font-size 14` |
| `pc26 reset KEY` | a setting back to its default; `pc26 reset all` for all of them |
| `pc26 theme list` | the themes (`*` marks the one in use) |
| `pc26 theme set NAME` | switch theme, e.g. `pc26 theme set nord` |
| `pc26 theme show NAME` | a theme's colours file |
| `pc26 theme reset` | back to the default theme (deletes the colours file) |
| `pc26 keybar list` | key bars: built-in, edited (your copy) or yours |
| `pc26 keybar show NAME` | the bar file in use |
| `pc26 keybar edit NAME` | copy a built-in bar (or start a new one) in `~/.config/pc26/keybars/` to edit |
| `pc26 keybar reset NAME` | delete your copy of a built-in bar |
| `pc26 menu show` | the launcher menu file in use |
| `pc26 menu edit` | copy the built-in menu to `~/.config/pc26/menu.conf` to edit |
| `pc26 menu reset` | delete your menu file (back to the built-in one) |
| `pc26 notify TITLE [TEXT]` | a phone notification; tapping it opens this tab. `--if-away` skips it while this tab is on screen |
| `pc26 undo` | take back the last config change (`pc26 undo --list` shows what it can take back) |
| `pc26 open URL` | open a web link in the phone's browser (also `xdg-open URL`) |
| `pc26 vibrate [MS]` | vibrate the phone, 300 ms unless given (1 to 5000) |
| `pc26 clipboard get` | print the phone's clipboard (the app must be on screen) |
| `pc26 clipboard set [TEXT]` | copy TEXT to the phone's clipboard, or stdin when there's no TEXT |
| `pc26 share FILE…` | send files to another app through Android's share sheet (the app must be on screen) |
| `pc26 share --text [TEXT]` | send TEXT, or stdin when there's no TEXT, to another app |
| `pc26 location` | where the phone is: one fix; `--stream` keeps printing them (see "The phone") |
| `pc26 sensor list` | the phone's sensors, with their values and units |
| `pc26 sensor NAME` | one reading of a sensor; `--stream` keeps printing them (see "The phone") |
| `pc26 camera FILE` | take a photo with the phone's camera app, saved to FILE; `--quick front\|back` snaps one with no screen |
| `pc26 torch on [PERCENT]\|off` | the phone's flashlight |
| `pc26 rotation lock [portrait\|landscape]` | stop the screen turning while the program that ran it runs; `unlock`, `status` |
| `pc26 audio play FILE` | play a sound file through the phone's speaker, until it ends |
| `pc26 audio record FILE` | record from the microphone to FILE (`.m4a`, `.aac`, `.ogg`, `.opus`, `.wav`) until Ctrl+C or `--seconds N` |
| `pc26 sound` | whether the sound device is on; `pc26 sound start` starts it again, `pc26 sound install` installs it (see "The phone") |
| `pc26 agent list` | the AI agents: installed or not, notifications on or off (`--tsv` for scripts) |
| `pc26 agent start [NAME]` | start an agent in this terminal; offers to install it first if it's missing |
| `pc26 agent install NAME` | install `claude`, `codex` or `gemini` with its official installer (shows the commands and asks first; `--yes`, `--notify`/`--no-notify` skip the questions) |
| `pc26 agent notify NAME on\|off` | an agent's phone notifications (adds or removes its hooks) |
| `pc26 hook claude\|codex\|gemini` | run by the agents' hooks (see "AI agents"); reads the hook's JSON on stdin |
| `pc26 edit` | the settings editors, for people (full screen) |
| `pc26 welcome` | the welcome page new users see: what's here and how to get around |
| `pc26 about` | the app's version, what it's made with (proot, Termux's terminal, the font, Debian), their licences and source links; in `less` on a terminal |
| `pc26 version` | the version of the app's tools |
| `pc26 help` | all commands |

`--json` on any command prints `{"ok": true, ...}` or
`{"ok": false, "error": "..."}`, for scripts.

`pc26 edit` (also the menu's Settings item) opens editors for people:
theme (moving through the list previews each theme live), settings
(font, cursor, agent notifications, undo), key bars, the launcher menu,
a config check and "Undo last change". In each, `r` puts things back
to the default. Don't run it yourself: it's interactive. Use the
commands above.

### Settings (`settings.conf`)

One `key = value` per line; `pc26 settings` describes each.

- `font-size`: 6 to 40 (default 12). Pinching the terminal changes it
  and saves it here.
- `font`: `default` (JetBrains Mono Nerd Font, which has the icons the
  prompt and `ls` use) or the full path of a `.ttf`/`.otf` file in
  Debian, e.g. after `apt install fonts-hack`:
  `pc26 set font /usr/share/fonts/truetype/hack/Hack-Regular.ttf`.
  Other fonts don't show Nerd Font icons.
- `cursor-style`: `block`, `underline` or `bar`.
- `cursor-blink`: `on` or `off`.
- `agent-notify`: `on` (default) or `off`: phone notifications from AI
  agents (see "AI agents").
- `agent-notify-after`: 0 to 3600 seconds (default 30). A finished
  agent turn only notifies if it took at least this long.
- `undo-keep`: 0 to 20 (default 1): how many changes `pc26 undo` can
  take back. 0 turns undo off.
- `android-clipboard`: `on` (default) or `off`: whether programs here
  can read and change the phone's clipboard (see "The phone").
- `android-share`: `on` (default) or `off`: whether programs here can
  open Android's share sheet (`pc26 share`).
- `android-location`: `on` (default) or `off`: whether programs here
  can ask for the phone's location (`pc26 location`).
- `android-sensors`: `on` (default) or `off`: whether programs here
  can read the phone's sensors (`pc26 sensor`).
- `android-camera`: `on` (default) or `off`: whether programs here
  can take photos (`pc26 camera`).
- `android-microphone`: `on` (default) or `off`: whether programs
  here can record from the microphone (`pc26 audio record`, and
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

### Undo

Every change to `~/.config/pc26/` is recorded so `pc26
undo` can take it back, however it was made: `pc26` commands, the
settings editors, the app (pinching the font size), or by hand. Edits by
hand are recorded at the next `pc26 check`, app start or `pc26
undo`, so a file you broke by hand can be undone even if you never
checked it. `pc26 undo` takes back one change at a time, newest first,
as far back as `undo-keep` allows; `pc26 undo --list` names them.
Fonts in `fonts/` and files over 256 KB aren't recorded. The copies live
in `~/.local/state/pc26/undo/`.

### AI agents

The menu's **AI agents** item lists them: picking an installed one
starts it, picking one marked "(install)" offers to install it first
(`pc26 agent start NAME` does the same). `pc26 agent install NAME`
installs an agent CLI with its own official installer, never a copy bundled with
the app; the user signs in with their own account:

| Name | Agent | Installer | Its hooks |
|---|---|---|---|
| `claude` | Claude Code | `curl -fsSL https://claude.ai/install.sh \| bash` | `~/.claude/settings.json` |
| `codex` | Codex | `curl -fsSL https://chatgpt.com/codex/install.sh \| sh` | `~/.codex/hooks.json` |
| `gemini` | Gemini CLI | `npm install -g @google/gemini-cli` (needs Node.js 20+: installs `nodejs npm` first) | `~/.gemini/settings.json` |

`curl` is installed first if it's missing. Afterwards it asks whether
to turn on the agent's notifications.

**Notifications:** with them on, the agent's hooks run `pc26 hook
NAME`, which posts a phone notification when a turn that took
`agent-notify-after` seconds or more ends ("Your turn"), and whenever
the agent needs permission or input. Nothing is shown while you're
looking at that agent's tab, and tapping a notification opens it. The
hook never fails, so it can't disturb the agent. `pc26 agent notify
NAME on|off` (or the Settings editor) adds or removes the hook entries
in the agent's config file and leaves everything else in it alone;
that file is the only record of whether they're on. `agent-notify off`
silences all agents at once.

Your own scripts can notify too: `long-job && pc26 notify "Done" "long-job finished"`.

**Signing in:** `BROWSER` points at `xdg-open`, which opens links in the
phone's browser, so an agent that opens a browser to sign in just works
(the app must be on screen). Tapping a link shown in the terminal opens
it too, even one spread over several rows.

### The phone

Commands for the phone itself, and the sound device:

- `pc26 vibrate [MS]`: a buzz, e.g. `make && pc26 vibrate`.
- `pc26 clipboard set [TEXT]` copies TEXT, or what's piped in, line
  breaks and all: `git log -1 | pc26 clipboard set`.
  `pc26 clipboard get` prints it as it is, with no line break added
  unless it goes to the terminal. Android only lets the app on screen
  read the clipboard, so `get` fails while the app is in the
  background; `set` works anyway. `android-clipboard off` blocks both.
- `pc26 share FILE…` opens Android's share sheet to send files to
  another app (mail, chat, …): `pc26 share report.pdf photo.jpg`.
  `pc26 share --text TEXT` sends text, or what's piped in. The app
  must be on screen. `android-share off` blocks it.
- `pc26 location` prints one fix: `60.1695213, 24.9354471 ±12 m
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
- `pc26 sensor list` names the phone's sensors: `accelerometer`,
  `gyroscope`, `magnetic-field`, `light`, `proximity`, `pressure`,
  `gravity`, `rotation-vector`, `step-counter`, … as the phone has
  them, and `compass` (azimuth clockwise from magnetic north, pitch
  and roll, in degrees, worked out from `rotation-vector`). `pc26
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
- `pc26 camera FILE` opens the phone's camera app; the photo you
  take is saved to FILE as a JPEG (replacing it), and `pc26` prints
  `Saved /root/photo.jpg (2.3 MB)`. Backing out of the camera app
  leaves FILE alone ("no photo was taken"). `--quick back` (or
  `front`) takes the photo straight away with no screen, for scripts
  and agents: exposure and focus get a moment to settle, then it's
  saved, within a few seconds. `--json` gives `file` and `bytes`. Android asks
  to allow the camera the first time (for both ways), and only lets
  the app on screen use it, so the app must be on screen.
  `android-camera off` blocks it.
- `pc26 torch on` and `pc26 torch off` turn the flashlight on and
  off. `pc26 torch on 30` sets a strength
  in percent, on phones whose flashlight has levels (Android 13+).
  Both work with the app in the background. Opening the camera turns
  it off.
- `pc26 rotation lock` stops the screen turning with the phone,
  e.g. for a program that reads the tilt sensors: it stays as it is,
  or `pc26 rotation lock portrait` (`landscape`) turns it to that
  side. The lock belongs to the program that ran `pc26` (the shell,
  when you type it) and ends when that program ends, so a crash
  can't leave the screen stuck; `--pid PID` gives it to another
  process. `pc26 rotation unlock` ends every lock; `pc26 rotation`
  says whether it's locked (`--json`: `locked` is `no`, `current`,
  `portrait` or `landscape`). From a program: run `pc26 rotation
  lock` once at start.
- `pc26 audio play FILE` plays a sound file (mp3, ogg, wav, m4a,
  flac, … whatever Android can play) and returns when it ends; Ctrl+C
  stops it. It works with the app in the background. `--json` gives
  `file` and `seconds`.
- `pc26 audio record FILE` records from the microphone until Ctrl+C,
  or for `--seconds N`, then prints `Saved /root/memo.m4a (240 KB,
  15 s)`. The file's ending picks the format: `.m4a` or `.aac` (AAC),
  `.ogg` or `.opus` (Opus, Android 10+), `.wav` (16-bit PCM, for
  tools like whisper.cpp). Mono; `--rate HZ` (8000 to 48000) sets the
  sample rate, e.g. `pc26 audio record --rate 16000 note.wav`. FILE
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
  (`unix:/tmp/.pc26/sound/native`). Don't start your own
  `pulseaudio`. `pc26 sound` says whether it's on; `pc26 sound
  start` starts it again and returns once programs can connect;
  Debians set up before the sound device need `pc26 sound
  install` once (it runs `apt-get install
  pulseaudio …`, asking first; `--yes` skips that). PulseAudio's
  output is in `/tmp/.pc26/sound/server.log`.
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
- `pc26 notify` and `pc26 open`: see "AI agents" above.

### Themes and colours

Built-in themes: `neon` (the default), `amber`, `phosphor`, `dracula`,
`nord`, `gruvbox-dark`, `solarized-dark`, `solarized-light`,
`catppuccin-mocha`, `tokyo-night`. `pc26 theme set NAME` writes the
theme into `~/.config/pc26/colors.properties`, with
`# theme: NAME` on its first line; with no colours file the app shows
Neon.

- **Colours file:** one `key=#rrggbb` per line: `background`,
  `foreground`, `cursor`, `color0` to `color255` (0-7 normal, 8-15
  bright). Keys left out keep the Neon colour. Edit it, then
  `pc26 check`.
- **Your own theme:** write
  `~/.config/pc26/themes/NAME.colors.properties` (same
  format; start from `pc26 theme show neon`), then
  `pc26 theme set NAME`. Yours wins over a built-in one with the same
  name.
- The tab strip, key bar and launcher menu take their colours from the
  theme (the menu uses the 16 basic colours).

### Launcher menu

`menu` opens it (the app opens it in the first tab when it starts
fresh; the very first time, it shows the welcome page,
`/opt/pc26/welcome.txt`, after the boot splash). Its items come from `~/.config/pc26/menu.conf` if it
exists, else `/opt/pc26/menu.conf`. One `Label = action` per
line, in order; labels up to 20 characters. Actions:

| Action | Does |
|---|---|
| `shell` | leave the menu for the shell |
| `files` | the file manager |
| `games` | the Games menu |
| `settings` | the settings editors (`pc26 edit`) |
| `agents` | AI agents: start one, or install it (`pc26 agent start`) |
| `system` | Update all, System info, About (`pc26 about`) |
| `welcome` | the welcome page (`pc26 welcome` prints it) |
| `exit` | close the tab |
| `run COMMAND` | run a command in bash, e.g. `Top = run htop` |

To change it: `pc26 menu edit`, edit the file, `pc26 check` (it
checks the menu file too). Any executable file in `~/games` shows up
under Games, named after the file: `ln -s /path/to/game ~/games/my-game`
shows "My Game". The menu starts games with `play`.

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
the editors use `pc26-edit`.

Built-in bars: `shell`, `nnn`, `menu`, `pc26-edit`, the games'
`neon-rogue`, `neon-drive`, `neon-flap`, `game` for any other game, and
`agent` for AI agents (`claude`, `codex` and `gemini` typed in the shell,
or started with `pc26 agent start`; an agent's own bar, e.g. `claude`,
wins when it exists). Typing them by name works through bash functions
from `/opt/pc26/shell.bash`; `unset -f claude` in `~/.bashrc`
turns that off.
A file `~/.config/pc26/keybars/NAME.conf` replaces or adds
one. To change a built-in bar: `pc26 keybar edit NAME`, edit the copy,
`pc26 check`. Buttons fill two rows in file order, the first half on
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
agent setting up a program should do): `pc26 keybar edit NAME`, write
the buttons, `pc26 check`, then run it under that bar:
`keybar NAME program`, or `play my-game` for a game (bar named after
the file). An alias in `~/.bashrc` makes it stick:
`alias htop='keybar htop htop'`. Fallbacks: `keybar my-tool,game …`
shows the generic game bar until `my-tool.conf` exists.

## More of the setup

- **File manager:** `files [folder]` runs nnn in detail mode (`?` lists
  its keys). Quick places on `b`: `h` home, `d` Download, `p` Pictures,
  `c` DCIM, `r` `/`; set `NNN_BMS` in `~/.bashrc` for your own (format
  `key:path;key:path`). Text files open in `$EDITOR` (nano).
- **Games:** `play GAME [args]` runs a game with the bar named after its
  file (`my-game.py` → `my-game`), else the generic `game` bar. `rogue`,
  `drive` and `flap` use it.
- **Tab titles:** a program sets its tab's title with the escape code
  `\e]0;title\a`. The prompt sets it to the current folder name. A name
  the user gives a tab (long-press it) wins over these.
- **Prompt hook:** the app sets `PROMPT_COMMAND` so it can reopen each
  tab in its folder after Android closes the app. If you change the
  prompt setup in `~/.bashrc`, keep whatever `PROMPT_COMMAND` the shell
  started with (starship keeps it on its own).
