# This Debian

Notes for AI agents (and people) working in this terminal. You're root in
a Debian 13 (trixie) arm64 system that runs inside an Android app on a
phone, through proot. The screen is narrow: about 56 columns in portrait.

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
  manager and editor.

## The setup

| What | Where |
|---|---|
| Shell setup: eza aliases, prompt, tab titles, menu hook | `~/.bashrc` |
| Prompt (starship) | `~/.config/starship.toml` |
| Terminal colours | `~/.config/pocket-terminal/colors.properties` |
| Launcher menu | `/opt/pocket-terminal/bin/menu` |
| File manager (nnn) | `/opt/pocket-terminal/bin/files` |
| Key bars above the keyboard | `~/.config/pocket-terminal/keybars/` |
| Menu state (last choices, menu theme) | `~/.local/state/pocket-terminal/menu` |
| Games | `/opt/neon-games` (`rogue`, `drive`, `flap`), yours in `~/games`; `play` runs one with its key bar |
| The app's tools: `pocket`, editors, themes | `/opt/pocket-terminal` |

- **Terminal colours:** one `key=#rrggbb` per line: `background`,
  `foreground`, `cursor`, `color0` to `color255`. Keys left out keep the
  Neon colour. Run `pocket check` to apply (or leave the app and come
  back). Bad lines are shown in a dialog
  and skipped. Delete the file to go back to Neon.
- **Font:** JetBrains Mono Nerd Font, so Nerd Font icons work in prompts
  and `ls`. It's built into the app; there's no setting for it yet.
- **Tab titles:** a program sets its tab's title with the escape code
  `\e]0;title\a`. The prompt sets it to the current folder name. A name
  the user gives a tab (long-press it) wins over these.
- **Menu:** `menu` opens it (the app opens it in the first tab when it
  starts fresh). Its Exit item closes the tab. Any executable file in
  `~/games` shows up under Games, named after the file:
  `ln -s /path/to/game ~/games/my-game` shows "My Game". The menu starts
  games with `play`.
- **File manager:** `files [folder]` runs nnn in detail mode (`?` lists
  its keys). Quick places on `b`: `h` home, `d` Download, `p` Pictures,
  `c` DCIM, `r` `/`; set `NNN_BMS` in `~/.bashrc` for your own (format
  `key:path;key:path`). Text files open in `$EDITOR` (nano).
- **Key bar:** two rows of buttons above the keyboard, always visible
  (more than fit go on pages: swipe sideways). Holding an arrow (or
  any button marked `Repeat`) repeats it. Which bar shows depends on
  what's running: `keybar NAME[,FALLBACK…] command…`
  (`/opt/pocket-terminal/bin/keybar`) shows the first of those bars that exists
  while the command runs, then the previous one; with no command
  running it's the shell's bar. `files` uses `nnn`, `menu` uses `menu`.
  Built-in bars: `shell`, `nnn`, `menu`, the games' `neon-rogue`,
  `neon-drive`, `neon-flap`, and `game` for any other game. A file
  `~/.config/pocket-terminal/keybars/NAME.conf` replaces or adds one
  (format in "Key bar files" below).
- **Games and key bars:** `play GAME [args]` (`/opt/pocket-terminal/bin/play`) runs
  a game with the bar named after its file (`my-game.py` → `my-game`),
  else the generic `game` bar. `rogue`, `drive` and `flap` use it.
- **Prompt hook:** the app sets `PROMPT_COMMAND` so it can reopen each
  tab in its folder after Android closes the app. If you change the
  prompt setup in `~/.bashrc`, keep whatever `PROMPT_COMMAND` the shell
  started with (starship keeps it on its own).
- `/opt/pocket-terminal/bin/menu`, `files`, `keybar`, `play`, the game commands
  and `/opt/neon-games` belong
  to the app and may be replaced by an app update; put your changes in
  your home folder.

## Changing settings: `pocket`

Every setting is a plain-text file in `~/.config/pocket-terminal/`, and
`pocket` (`/opt/pocket-terminal/bin/pocket`) tells the app about them.
**After editing any of those files, run `pocket check`**: the app
applies the change right away (no need to leave the app) and lists
problems by file and line. Exit code 0 means no problems, 1 problems
found, 2 an error (for example not running inside the app). Fix what
it lists and run it again.

- `pocket check`: apply the config now and list problems.
- `pocket version`: the version of the app's tools.
- `pocket help`: all commands.
- `--json` on any command prints `{"ok": true, ...}` or
  `{"ok": false, "error": "..."}`, for scripts.

`/opt/pocket-terminal` belongs to the app and is replaced on every app
update; don't change it.

## Key bar files

The built-in bars are in `/opt/pocket-terminal/keybars/`. To
change one, copy it to
`~/.config/pocket-terminal/keybars/` and edit the copy; a new
`NAME.conf` there adds a bar for `keybar NAME …`. Run `pocket check`
to apply. Buttons fill two rows in file order, the first half on
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
- Modifiers: `Ctrl+R`, `Alt+Left`, `Ctrl+Alt+Delete`.
- `"text"` is typed as is (`\"` for a quote).
- `Ctrl` on its own is a sticky Ctrl for the next key typed.
- `Repeat` anywhere on the line: the button repeats while held. Arrows,
  PgUp/PgDn, Backspace and Delete repeat anyway. Repeating buttons send
  as soon as they're touched; others on release.
- Bad lines are skipped and shown in a dialog.

**Giving a program or game its own bar** (also what an installer or an
agent setting up a program should do): write
`~/.config/pocket-terminal/keybars/NAME.conf`, then run it under that
bar: `keybar NAME program`, or `play my-game` for a game (bar named
after the file). An alias in `~/.bashrc` makes it stick:
`alias htop='keybar htop htop'`. Fallbacks: `keybar my-tool,game …`
shows the generic game bar until `my-tool.conf` exists.
