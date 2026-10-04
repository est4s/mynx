# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **Roadmap step 3 (*Default setup*) is done** and confirmed on the
  phone: Neon colours and JetBrains Mono Nerd Font (user colours file in
  Debian), a customized Debian image (starship, eza, mc, nano, less,
  python3, the three games, no debconf warnings), the launcher menu on a
  fresh start, and agent docs in `/root`.
- **Roadmap step 4 (*File manager*) is done** and confirmed on the
  phone: nnn as `files` (detail mode, bookmarks, nano for text) and an
  always-visible two-row key bar above the keyboard that follows the
  running program (`keybar NAME cmd`), with pages to swipe and
  user-editable bar files.
- **Step 5 rethought by the owner (2026-10-04): no in-app keyboard.**
  Instead: key bars for games, bars any program or game can bring, and
  hold-to-repeat. Built, **waiting for the owner's check (needs a data
  clear)**. See "Next".
- **Roadmap step 2 (*Tabs*) is done** and confirmed on the phone: tab
  strip, background service, activity/bell marks, exit rule, and tabs
  (with their folders and names) restored after Android kills the app.
  Tabs can be renamed with a long-press (or Ctrl+Shift+R). Still
  untested: hardware keyboard shortcuts (owner has no keyboard, see
  "Hardware keyboard checks").
- **Roadmap step 1 (*Core*) is done** and confirmed on the owner's phone:
  opening the app shows a `root@localhost` bash inside the built-in Debian
  13 (trixie); `apt install` works; `htop` draws; app updates keep the
  user's Debian.
- **App:** a tab strip above one Termux `TerminalView` that shows the
  selected tab's session, each run through proot (`prootLaunch()` from
  `core`). Sessions live in `TerminalService` (foreground service), so they
  survive Back, rotation and leaving the app; its notification shows the
  tab count and has an **Exit** action. Tabs are saved to
  `filesDir/state/tabs` and restored on a cold start. First launch unpacks the bundled
  rootfs into `filesDir/debian` (~5 s, progress screen). Blocked `/proc`
  files get static stand-ins from `filesDir/fake-proc`. Crashes are saved
  and shown on the next launch. APK: 30 MB.
- **Build:** GitHub Actions runs the TDD check and all tests, builds proot
  with the NDK (cached) and the rootfs from `debian:trixie` (arm64), then a
  debug APK. `scripts/deliver.sh` installs it on the phone.
- **Code:** `core/` has `ProotLaunch.kt`, `RootfsInstaller.kt`,
  `FakeProc.kt`, `ServiceNotification.kt`, `Tabs.kt`, `TabShortcuts.kt`,
  `TabState.kt`, `HostPath.kt`, `ColorScheme.kt` and `KeyBar.kt` (100
  tests in all). `app/` also has `KeyBarView.kt`. `rootfs/` holds the
  Debian image (Dockerfile, `bin/` menu/files/keybar, dotfiles, games);
  `tests/shell/` has 40 bats tests. `app/` is
  `TerminalApp` (crash reporter), `TerminalService`, `MainActivity` and
  `TerminalClients.kt`.

---

## Next

### Roadmap step 4: File manager (nnn + key bar)

**Decided with the owner (2026-10-04):** no home-made file manager. The
owner tried nnn and lf in the app and liked both, nnn a bit more (more
info out of the box). What was missing: the shortcuts, so they're shown
as a **key bar** above the keyboard, **always visible**, with labels
that change with the running program. The README's File manager section
was rewritten to match (plus a Key bar section).

**4.1 nnn and `keybar` (rootfs). Built, in the same build as 4.2.**
**4.2 Key bar (app). Owner's first check (2026-10-04): "looks pretty
good"; asked for ↑ ↓ in nnn's bar and two rows instead of one sliding
row, with pages to swipe when two rows aren't enough. Done in 4.3.**
Checks from the first round:
- the bar sits under the terminal, right above the keyboard, and stays
  when the keyboard is hidden; neon colours; buttons scroll sideways if
  they don't fit
- the menu shows ↑ ↓ Select Back Quit, and they work
- menu → Files opens nnn in detail mode; the bar changes to Open Back
  Select Copy Move Rename Delete Search Places Quit; quitting nnn brings
  back the menu's bar, leaving the menu the shell's (Esc Tab Ctrl ← ↓ ↑ →
  Files Menu)
- in nnn: select a file in Download (Places → d), go to another folder,
  Copy pastes it there; Rename and Delete ask and work; Search filters
- shell bar: Files and Menu open them; ↑ recalls history; Tab completes;
  Ctrl lights up, then typing `c` interrupts `sleep 100`, and Ctrl goes
  dark again
- each tab keeps its own bar when switching tabs (nnn in one, shell in
  the other)
- typing on the system keyboard still works after pressing bar buttons
- a bar file with a bad line shows a "Problems in a key bar file" dialog:
  `mkdir -p ~/.config/pocket-terminal/keybars && printf 'Quit = q\noops\n'
  > ~/.config/pocket-terminal/keybars/nnn.conf`, leave the app and come
  back, run `files`: dialog, then a bar with only Quit. Delete the file.
- opening a text file in nnn opens nano

**4.3 Two-row key bar with pages. Done, owner confirmed ("works
perfectly").** Checks the owner ran:
- the bar is two rows: nnn `↑ ↓ Open Back Select Search` over `Copy Move
  Rename Delete Places Quit`; shell `Esc Tab Ctrl Files Menu` over
  `← ↓ ↑ →`; menu `↑ ↓ Select` over `Back Quit`
- buttons in a row share the width evenly; the terminal keeps its size
  when the bar changes (nnn ↔ shell)
- ↑ ↓ move in nnn
- pages: `mkdir -p ~/.config/pocket-terminal/keybars && for i in $(seq 1
  20); do echo "K$i = \"$i\""; done > ~/.config/pocket-terminal/keybars/shell.conf`,
  leave and come back: two rows of K-buttons, dots at the bottom, swipe
  left/right switches pages, taps still type the numbers. Delete the
  file afterwards.
- rotate to landscape: more buttons per row

### Roadmap step 5: Game and program key bars

**Decided by the owner (2026-10-04):** the key bar is good enough to
replace the planned in-app keyboard. Step 5 is now: a key bar for games,
bars that new programs and games can bring themselves, and press-and-
hold arrows that repeat. README updated (Key bar section, roadmap,
intro; the In-app keyboard section is gone).

**Built, not yet confirmed.** Owner clears the app's data, opens it,
and checks:
- `rogue`: the bar shows `← ↓ ↑ → Wait Explore` over `Potion Scroll
  Bomb Stairs Info Quit`; swipe left for `Help Continue New` / `Scores
  Rest`; title screen: New/Continue work; holding an arrow walks
  repeatedly, and stops when released; Quit (Q) saves and the shell's
  bar comes back
- `drive`: `← Brake Nitro →` over `Pause Again Quit`; holding ← / →
  steers continuously; Brake holds; Again restarts after a crash
- `flap`: `Flap` over `Quit`; each tap flaps
- the menu's Games start the same bars
- holding ↑ / ↓ in nnn and in the menu scrolls repeatedly; holding ← in
  the shell moves the cursor along the line
- a game with no bar of its own gets the generic bar: `printf
  '#!/bin/sh\nread -r x\n' > ~/games/test-game && chmod +x
  ~/games/test-game`, menu → Games → Test Game: `← ↓ ↑ → Space Enter` /
  `Esc y n q`; Enter ends it. Then `rm ~/games/test-game`.
- swiping pages while holding nothing still works; a swipe that starts
  on an arrow doesn't leave it repeating

### Hardware keyboard checks (later)
The owner has no hardware keyboard, so these are untested. Run them when
one is available (Bluetooth/USB keyboard), or with a soft keyboard that
sends real Ctrl/Alt key events (e.g. Hacker's Keyboard), or once the
in-app keyboard (step 5) can send these combos:
- Ctrl+Shift+T opens a tab; Ctrl+Shift+W closes the selected one
- Ctrl+Tab / Ctrl+Shift+Tab go to the next / previous tab, wrapping round
- Ctrl+Alt+1…9 jump to tab N (no-op past the last tab)
- Ctrl+Shift+PgUp / PgDn move the selected tab left / right
- Ctrl+Shift+R opens the rename dialog; Enter in it saves, Esc cancels
- holding a shortcut acts once (no row of new tabs)
- Ctrl+T, Ctrl+W, Tab, Ctrl+Alt+0 still reach the shell (e.g. Ctrl+W
  deletes a word in bash)
- Android doesn't swallow Ctrl+Tab or Ctrl+Alt+digits before the app sees
  them (if it does, pick other defaults in `tabShortcut()`)

### Small open items
- No wakelock yet (README: "foreground service with an optional
  wakelock"). The shell survived a few minutes with the screen off without
  one; add a notification action for it (like Termux) if long jobs stall.
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

---

## Log

### 2026-10-04 (17): step 5 rethought: game and program key bars

**Done**
- `core`, test-first (10 more tests): `KeyButton.repeat`. Arrows,
  PgUp/PgDn, Backspace and Delete repeat on their own; any button with
  `Repeat` on its line opts in (`Repeat` alone is "no keys").
  `keyBarNames()` replaces `keyBarName()`: a report can list fallbacks
  (`my-game,game`), invalid names dropped. `loadKeyBar(names)` takes the
  first that exists (user file, then built-in), else the shell's.
- Built-in bars for `neon-rogue` (17 buttons, 2 pages), `neon-drive`,
  `neon-flap` and a generic `game` bar. Nitro (↑) repeats too, like a
  held ↑ key (first test expectation said otherwise; the test was wrong).
- Debian (bats, `tests/shell/play.bats`): `play GAME` runs a game under
  `keybar <file name>,game`; `rogue`, `drive`, `flap` are scripts calling
  `play` (were symlinks); the menu's Games run `play`.
- App: `KeyBarView` repeats held buttons (send on touch, then every
  50 ms after 400 ms); a rebuild of the bar or a page swipe (touch
  cancel) stops the repeat, so a button can't keep repeating with no
  release coming.
- Docs: README rewritten around key bars; home `AGENTS.md` explains
  `play`, fallbacks, `Repeat`, and how programs (or agents setting one
  up) bring their own bar.

**Commits:** (pending)

### 2026-10-04 (16): step 4, nnn and the key bar

**Done**
- Owner compared the Debian-packaged file managers (nnn, lf, ranger,
  vifm, broot; yazi/xplr not packaged) and tried nnn and lf in the app.
  Decision: nnn, plus an always-visible key bar for its actions.
- 4.1, rootfs (bats, `tests/shell/files.bats` + menu/bashrc/docs tests):
  `files [dir]` = `keybar nnn nnn -de`, with `NNN_BMS` bookmarks (h home,
  d Download, p Pictures, c DCIM, r /) unless the user set their own.
  `keybar NAME cmd…` writes NAME to `$POCKET_KEYBAR_FILE` while cmd runs
  and restores the previous content (EXIT trap; nests; passes the exit
  code; just runs cmd outside the app). The menu's Files item runs
  `files`; the `menu` function runs under `keybar menu`. `EDITOR` and
  `VISUAL` are nano (nnn opens text files with it). nnn installed.
- 4.2, `core` test-first (16 tests): `KeyBar.kt` parses bar files
  (`label = keys`; named keys, single characters, `Ctrl+`/`Alt+`,
  `"quoted text"`, `Ctrl` alone = sticky; bad lines reported).
  `keyBarName()` validates the reported name (else `shell`).
  `loadKeyBar()`: user `NAME.conf` beats the built-in, unknown → shell.
  Built-ins (`shell`, `nnn`, `menu`) are core resources, also shipped to
  `/usr/share/pocket-terminal/keybars/` to copy from.
  `prootLaunch(keyBarFile)` sets `POCKET_KEYBAR_FILE`.
- 4.2, app: `TerminalService` gives each shell
  `/tmp/.pocket-terminal/keybar-N`. `KeyBarView` (under the terminal,
  so above the keyboard) reloads when that file's timestamp changes,
  checked on the visible tab's output, tab switches and `onStart`
  (forced, to pick up edited bar files). Named keys go through
  `TerminalView.handleKeyCode()` (right sequences in cursor-app mode),
  characters through `inputCodePoint()`, text through
  `session.write()`. Sticky Ctrl is `ViewClient.readControlKey()`.
  Haptic tick on press. Problems in a bar file show in a dialog once.
- `applyColors()` now always repaints the views and only skips the
  library update when the scheme is unchanged: a new view (second
  `showTerminal`) was left unpainted before.
- Docs: README File manager + Key bar sections and roadmap item 4; home
  `AGENTS.md` documents `files`, bookmarks, key bars and their format.

**Decisions**
- Bar changes travel through a per-tab file, not an escape code: the
  library drops unknown OSC codes with no hook for the app.
- Built-in bars live in the app (core resources), so app updates can
  improve them without a rootfs migration; users override per bar.
- Not yet: key repeat on held buttons (arrows), opening non-text files
  in Android apps (needs a Debian → app channel).
- 4.3 (owner's feedback): `keyBarPages()` in core, test-first (4
  tests): always two rows (an empty second row keeps its height, so the
  terminal never resizes), first half on top, extra buttons on more
  pages. The app picks buttons per row from the widest label (min 44 dp)
  and the bar's width; buttons in a row share the width. `KeyBarView` is
  now a `FrameLayout` showing one page; a sideways swipe past the touch
  slop switches pages (taps still reach buttons), dots drawn on the
  bottom edge. nnn's bar gained ↑ ↓; built-in bars reordered so the
  halves make sense as rows.

**Owner confirmed on the phone:** all 4.2 and 4.3 checks (two rows,
↑ ↓ in nnn, no terminal resize on bar changes, pages with dots and
swipe, landscape). **Step 4 done.**

**Commits:** `4c11f36`, `796502a`, `d3ff5f1`

### 2026-10-03 (15): step 3.4, agent docs in the home folder

**Done**
- `rootfs/root/AGENTS.md` (+ `CLAUDE.md` importing it), shipped as
  `/root/AGENTS.md`: how this Debian runs (proot, fake root, no
  systemd/sudo, static `/proc` files, network, apt, storage), and a table
  of the setup's files (`.bashrc`, `starship.toml`, colours file, menu
  and its state, games) with how to change each: colours format and
  apply-on-return, tab titles via OSC 0, adding games in `~/games`,
  keeping the app's `PROMPT_COMMAND`, app-owned paths that updates may
  replace.
- Test-first (3 bats tests, `tests/shell/home-docs.bats`): `CLAUDE.md`
  imports `AGENTS.md`; every file in `rootfs/root/` and the other
  user-facing paths are mentioned in `AGENTS.md`, so the docs can't
  silently fall behind. Repo AGENTS.md's design rule points at them.

**Owner's first check:** `less` didn't exist (the base image has no pager
or editor), so `less` and `nano` are now installed. `ls
/storage/emulated/0` lists the phone's folders, so the docs' claim that
storage needs a permission was wrong; replaced with the path.

**Owner confirmed on the phone** (after clearing data), together with
step 3's final checks: `CLAUDE.md` imports `AGENTS.md`, `less` and `nano`
work, menu on the first tab only, starship/eza/folder titles, no debconf
warnings, tab folder restore after force-stop. **Step 3 done.**

**Commits:** `4e53912`, `51dacd8`

### 2026-10-03 (14): step 3.3, launcher menu

**Done**
- `rootfs/bin/menu` → `/usr/local/bin/menu`: the owner's Termux menu,
  ported to run inside Debian. Main: Terminal, Files (`mc ~`), Games,
  System, Exit. System: Update all (`apt update && apt upgrade -y`),
  System info, Theme (neon/amber/phosphor). State in
  `~/.local/state/pocket-terminal/menu`. Games: executables in
  `/opt/neon-games` and `~/games` (`MENU_GAME_DIRS` overrides), labelled
  from file names, so the games are now installed as `neon-rogue.py`,
  `neon-drive.py`, `neon-flap.py`. Sets the tab title to `Menu`.
  Sourcing it only defines functions (for tests).
- Dropped from the Termux version: the Termux/Debian split, Back up
  Debian (used proot-distro), Edit menu (the menu is now a system file a
  later migration may replace), uptime (the app's `/proc/uptime` is a
  static fake), Termux/Android lines in the splash and System info
  (`getprop` isn't reachable from Debian). Splash only with `--boot`.
- Root's `.bashrc`: `menu` function (exit code 10 = Exit = close the
  shell, so the tab); runs `menu --boot` once when `POCKET_MENU` is set,
  then unsets it.
- `core`, test-first: `prootLaunch(openMenu = true)` adds `POCKET_MENU=1`
  (2 tests). `TerminalService` passes it only for the fresh-start tab
  (no saved tabs), not for new, restored or restarted tabs.
- Tests: 13 bats tests for the menu (`tests/shell/menu.bats`: items,
  games, state, theme, dry-run key presses for q/Exit/Files/Games/
  Update, splash), 5 more for the `.bashrc` hook. `check-tdd.sh` pairs
  `rootfs/bin/` with `tests/shell/`.

**Gotcha: bats-lite gave false passes.** It ran each test inside
`if ( … )`, where bash ignores `set -e` even in the subshell, so only a
test's last command could fail it. CI's real bats caught a wrong
expectation (game order). Fixed; the 3.2 `.bashrc` tests were rerun and
pass for real. The menu now sets `LC_COLLATE=C`, so games are listed in
the same order whatever the locale.

**Not ported, maybe later:** a "Claude Code" item (README: offer the
official installer, don't bundle; belongs with AI agent support), a
backup that works without proot-distro.

**Owner confirmed on the phone:** all the 3.3 checks above pass.

**Commits:** `726ade9`, `9969039`

### 2026-10-03 (13): step 3.2, customized rootfs

**Done**
- `rootfs/Dockerfile`, built by `scripts/build-rootfs.sh` with buildx for
  arm64 (CI adds `setup-qemu-action` and `setup-buildx-action`), on the
  `debian:trixie` digest it pulled (recorded in `debian-rootfs.txt` as
  before). Installs `apt-utils dialog eza mc python3 starship`
  (no recommends; apt lists removed). Copies `rootfs/root/` (`.bashrc`,
  `.config/starship.toml`) into `/root`, the Neon colours file from
  `core`'s resources (named build context `neon`) to
  `/root/.config/pocket-terminal/colors.properties`, and the games to
  `/opt/neon-games/` with `rogue`, `drive`, `flap` in `/usr/local/bin`.
- Root's `.bashrc`, test-first with bats (7 tests in
  `tests/shell/bashrc.bats`): eza aliases only when eza exists; tab title
  = folder name (`~` at home) via `pocket_set_title`; starship init with
  `starship_precmd_user_func`. Tested: the app's `PROMPT_COMMAND` (tab
  folder reporting) still runs with and without starship (starship moves
  it into `STARSHIP_PROMPT_COMMAND` and evals it). `LANG`, and
  `~/.local/bin` first on `PATH`.
- CI runs `bats tests/shell` after the Kotlin tests; `check-tdd.sh` pairs
  `rootfs/root/` with `tests/shell/`.
- `scripts/bats-lite.sh`: tiny bats stand-in for the phone (see AGENTS.md).

**Gotcha:** real bats can't run on the phone. It uses process
substitution, and proot-distro's `/dev/fd` is a frozen copy of one
process's fd folder (`cat <(echo hi)` fails). Might affect the app's own
Debian too; on the check list.

**Decisions**
- Games live in `/opt/neon-games/` (system-wide, not in `/root`), so a
  later app update can replace them with a migration without touching
  user files; scores stay in `~`.
- Title is the folder name only: tabs are narrow on a phone.
- No QEMU-built image locally (no docker on the phone): the Dockerfile is
  verified by the CI build and the owner's checks.

**Owner confirmed on the phone** (after clearing data): starship prompt
with icons, folder-named tabs, eza aliases, all three games, no debconf
warnings on `apt install`, Neon colours file in place, `mc`, tab folder
restore after force-stop, and `cat <(echo ok)` works in the app's Debian
(so the `/dev/fd` problem is only in the dev Debian). APK 50 MB (rootfs
`.tar.xz` 45.6 MB, was 28.4).

**Commits:** `de39952`

### 2026-10-03 (12): step 3.1, neon colours and font

**Done**
- `core`, test-first (9 tests): `ColorScheme.kt`. `parseColorScheme()`
  reads Termux's `colors.properties` (`background`, `foreground`,
  `cursor`, `color0`–`color255`, `#rrggbb`, `#`/`!` comments) on top of a
  base scheme; bad lines are skipped and reported with line numbers.
  `NEON` comes from the resource `neon.colors.properties` (the owner's
  Termux file with a user-facing header). `loadColorScheme(file)` lays a
  file over neon (neon alone if missing). `stripColors()` derives the tab
  strip colours: background, `color0` selected, cursor accent, `color6`
  mark, `color7` text.
- App: `MainActivity.applyColors()` reads
  `~/.config/pocket-terminal/colors.properties` from the user's Debian on
  start and every time the app comes back to the front; only a changed
  scheme is applied (writes `TerminalColors.COLOR_SCHEME`, resets each
  tab's colours, recolours strip and background), so colours programs set
  by escape codes survive app switches. Problems show in a dialog once.
- Font: JetBrains Mono Nerd Font Mono for the terminal and the tab strip.
  `scripts/fetch-font.sh` downloads Nerd Fonts v3.5.1's
  `JetBrainsMono.tar.xz` (sha256-pinned) in CI and unpacks the TTF and
  `OFL.txt` into `app/src/main/assets/fonts/` (gitignored). The owner's
  Termux font is byte-identical to the release file (same sha256).
  Builds without the font fall back to monospace.

**Decisions**
- Font downloaded in CI, not committed: same pattern as proot and the
  rootfs; the hash pin keeps it reproducible. APK grows by ~1.2 MB
  (2.6 MB TTF, compressed).
- Reading the user's colours file now rather than waiting for 3.2: it
  was small, and lets the owner try theme edits right away.
- No new shell tests: `fetch-font.sh` is a build script verified by the
  CI build, like `build-proot.sh`.

**Not verified:** `app/` can't compile here; written against the
library's published sources (v0.118.3). Risk to watch: `NEON` loads a
Java resource from the `core` jar; if Android doesn't package it, the app
crashes on start and the crash dialog will say so.

**False alarm, white background:** the first palette check command in
the list left SGR background colour 15 (white) set, so typed text and a
`clear` came out white. Not an app bug; the command now ends with
`\e[0m`. On the way, the window behind the terminal is now painted in
the scheme background too, like Termux (the library never paints
default-background cells). `scripts/deliver.sh` now waits for the run
of HEAD: right after a push it had picked the previous run.

**Owner confirmed on the phone:** neon palette, Nerd Font icons, strip
colours, pinch zoom, a colours file in Debian recolours every tab on
return, a bad line shows the problems dialog, deleting the file brings
neon back.

**Commits:** `b39ecac`, `3339497`, `e4e7ab6`

### 2026-10-03 (11): rename tabs

Owner asked for renaming without a hardware keyboard before starting
step 3, so the README's "long-press a tab for rename" came forward (the
rest of that long-press menu, colour/duplicate/close others, is still
later).

**Done**
- `core`, test-first (5 tests): `Tabs.renameInput()` (the shown title)
  and `Tabs.applyRenameInput()` (trimmed; blank = automatic title; saving
  the shown title unchanged doesn't pin it), `TabAction.Rename` on
  Ctrl+Shift+R (Windows Terminal has no default; Ctrl+R still reaches
  bash).
- Long-press a tab → "Rename tab" dialog with the keyboard up; IME Done
  saves; **Automatic** button only on renamed tabs.

**Owner confirmed on the phone:** rename shows in the strip; clearing goes
back to "Tab N"; renamed tabs keep their names after a force-stop (the
step 2 restore check that couldn't be run before); a rename beats the
shell's OSC title and **Automatic** brings the shell title back; typing
works after cancelling.

**Commits:** `e37ac70`

### 2026-10-03 (10): step 2.5, restore tabs; step 2 done

**Done**
- `core`, test-first: `TabState.kt` (`SavedTabs`, plain-text
  `serialize()` / `parseSavedTabs()`, `Tabs.snapshot()` /
  `Tabs.restore()`), `HostPath.kt` (`hostPath()`), `prootLaunch(cwdFile)`.
  Broken or unknown-version files are ignored (fresh start).
- `TerminalService` saves `filesDir/state/tabs` on every tab change and
  when the activity stops; a cold start restores order, renames, selected
  tab and folders, with fresh shells. Restored background tabs start when
  first shown; their folder is kept in `knownCwd` until then. A missing
  folder falls back to `/root`. Exit and closing the last tab delete the
  file. Enter-restart of a failed shell reuses its folder.

**Gotcha: proot hides the shell's folder from the host.** The first
version read `/proc/<shell pid>/cwd`: on the phone it always showed the
app's files dir (the cwd proot itself was started in), whatever the
shell `cd`'d to, because proot tracks the guest cwd itself. (It also
spells app data `/data/data/…` where `filesDir` says `/data/user/0/…`.)
Found with a temporary on-device trace written to the app's external
files dir, readable from Debian under `/storage/emulated/0/Android/data/`
(a handy debugging channel, since there's no logcat). Now each shell
reports its folder: `PROMPT_COMMAND` writes `$PWD` to
`/tmp/.pocket-terminal/cwd-N`, which the service reads.

**Owner confirmed on the phone:** after force-stop, 3 tabs come back with
the same tab selected and `pwd` = `/etc` and `/tmp/x` in the right tabs;
a deleted folder opens in `/root` without errors; Exit then reopen gives
one fresh tab; `PROMPT_COMMAND` shows the printf line, prompt otherwise
unchanged.

**Not tested:** restoring renamed tabs (no rename UI; covered by core
tests). Hardware keyboard shortcuts (see "Hardware keyboard checks").

**Commits:** `d860de5`, `3f18912`, `eaed2ae`

### 2026-10-03 (9): steps 2.3 and 2.4, tab strip, shortcuts, marks

**Done**
- `core`: `TabShortcuts.kt`, test-first (7 tests): Windows Terminal's
  default tab shortcuts → `TabAction`, exact modifier match so other
  combos reach the shell.
- Tab strip above the terminal: title (max 160 dp, ellipsized) and × per
  tab, then +; selected tab highlighted (purple background, pink text);
  scrolls sideways and keeps the selected tab in view. Strip views aren't
  focusable, so tapping them leaves input on the terminal.
- Cyan ● (output) and 🔔 (bell) on background tabs, from the `Tabs` marks
  (2.4, folded in since the model already had the flags).
- `ViewClient.onKeyDown` runs tab shortcuts before the terminal sees the
  key; key repeats are consumed but ignored.

**Owner confirmed on the phone:** + opens and selects tabs; `top` keeps
running across switches; ● and 🔔 appear on background tabs; closing a
middle tab selects its right neighbour; closing the last tab closes the
app; "Tab N" titles; typing still reaches the terminal after tapping the
strip. Hardware keyboard shortcuts not tested (no keyboard), listed under
"Hardware keyboard checks".

**Commits:** `3560f8a`

### 2026-10-03 (8): step 2.2, tab model and exit rule

**Done**
- `core`: `Tabs<S>` and `closesOnExit()`, test-first (21 tests): open
  after current, close selects right else left, wrap-around next/previous,
  move left/right, shell title / rename / "Tab N" titles with stable
  numbers, activity and bell marks for background tabs, change callback
  (marks only report when they flip, since output arrives constantly).
- `TerminalService` holds its sessions in `Tabs`. Exit code 0 closes the
  tab; other codes (negative = signal) keep it open with the library's own
  `[Process completed (code N) - press Enter]` (the library already prints
  it, so the app adds nothing). Closing the last tab = `exit()`.
- Notification updates use `NotificationManager.notify()`; only
  `onStartCommand` calls `startForeground()`.

**Notes**
- First push failed to compile in CI: `var tabs = newTabs()` where the
  lambda in `newTabs()` reads `tabs` is a recursive type inference error.
  Fixed with explicit types (`01cb1ac`). `app/` can't compile on the
  phone, so read Kotlin carefully for inference cycles like this.

**Owner confirmed on the phone:** `exit` closes the app and the
notification; `exit 1` stays open with the message and Enter restarts;
killing a child shell changes nothing; notification Exit, Back and
rotation still work.

**Commits:** `ed95a18`, `01cb1ac`

### 2026-10-03 (7): step 2.1, background service

**Done**
- `core`: `runningTerminalsText(count)` for the notification, test-first
  (3 tests).
- `TerminalService`: foreground service (`specialUse`, with the subtype
  property) that owns the sessions. Notification "N terminal(s) running",
  low importance, opens the app; **Exit** kills all sessions, stops the
  service and calls `finishAndRemoveTask()` on the attached activity.
  Returns `START_NOT_STICKY`: a restarted service can't bring dead shells
  back (2.5 restores tabs instead).
- `MainActivity` starts the service with `startForegroundService()` while
  visible, binds, and shows the service's session. `onDestroy()` only
  unbinds. Back moves the task to the back (`OnBackInvokedDispatcher` on
  API 33+, `onBackPressed()` below). Asks for `POST_NOTIFICATIONS` on API
  33+; works without it.
- `SessionClient` belongs to the service and forwards to the attached
  activity, so sessions never hold a destroyed activity.
- Crash reporter moved to `TerminalApp` (`Application`) so it covers the
  service too. White `>_` notification icon (`ic_notification.xml`).

**Owner confirmed on the phone:** notification permission prompt and
"1 terminal running" with Exit; `htop` keeps running after Back and
reopening; rotation keeps the session; shell alive after a few minutes
with the screen off; Exit removes app and notification, reopening gives a
fresh shell.

**Notes**
- `scripts/deliver.sh` once failed with `unexpected EOF` from the GitHub
  API while polling (network hiccup, build was fine); rerunning it worked.

**Commits:** `8dbace9`

### 2026-10-03 (6): step 1.5, Debian starts; step 1 done

**Done**
- The terminal runs `prootLaunch()`'s command (login bash in Debian, fake
  root) instead of Android's `sh` (`0fdf4eb`).
- `apt update` failed to resolve hosts: the app lacked
  `android.permission.INTERNET`, so Android denied every socket (`52e497d`).
- `htop` failed with `Cannot open /proc/stat: Permission denied`.
  `writeFakeProc()` (core, test-first) writes static stand-ins for
  `/proc/{loadavg,stat,uptime,version,vmstat}` into `filesDir/fake-proc`,
  only for files the app can't read (probed at each start with a real
  read), and `prootLaunch(fakeProc = …)` binds them over the originals.
  On the owner's Android 16 all five are blocked (`9e5c713`).
- A `workflow_dispatch` rebuild installed as an update kept `htop`.

**Owner confirmed on the phone:** short opening screen, `apt install`
works, `htop` draws, updates keep installed packages. All of step 1's
"Done when" checks pass.

**Decisions**
- Fake `/proc` files live outside the user's Debian (app-managed, rewritten
  each start), so nothing is written into the user's rootfs.

**Commits:** `0fdf4eb`, `52e497d`, `9e5c713`

### 2026-10-03 (5): step 1.4, first-run install

**Done**
- `core`: `RootfsInstaller(baseDir)`, test-first (13 tests). Unpacks the
  `.tar.xz` (commons-compress 1.28.0 + xz 1.12) into `debian.partial`,
  writes `etc/resolv.conf` (1.1.1.1, 8.8.8.8) and `etc/hosts`, then renames
  it to `debian`. Hard links become copies; symlinks are kept verbatim;
  device nodes/FIFOs are skipped; the owner always keeps rw (and x on
  dirs) so apt can replace files later. Refuses `..` paths and writing
  through symlinks; refuses to install over an existing `debian`; clearing a
  stale `debian.partial` doesn't follow symlinks. Progress in percent of
  compressed bytes read.
- Tried it on the real CI rootfs here (throwaway test, not committed):
  4414 files, 294 symlinks, ~3 min under proot on the phone; looked right.
- `app`: `MainActivity` shows a progress screen and unpacks on a background
  thread if `filesDir/debian` is missing, then shows the terminal. Errors
  show the stack trace on screen with a Retry button.

**Decisions**
- "Installed" = the final `debian` dir exists, made by an atomic rename at
  the end, instead of a separate marker file: same guarantee, one less
  thing to get out of sync.

**Owner confirmed on the phone:** progress screen then terminal, ~5 s;
`os-release` says trixie; `resolv.conf` written. Interrupt-and-reopen not
tried yet.

**Commits:** `3aede1b`

### 2026-10-03 (4): step 1.3, Debian rootfs in the APK

**Done**
- `scripts/build-rootfs.sh <out-dir>`: `docker pull --platform linux/arm64
  debian:trixie`, `docker create` + `docker export` (no container runs, so
  no QEMU), drops `.dockerenv`, `xz -T0 -9`. Also writes
  `debian-rootfs.txt` (`image=<repo digest>`, `built=<UTC time>`).
- CI runs it into `app/src/main/assets/` (gitignored), ~50 s, no cache.
- `androidResources.noCompress += "xz"`; the APK artifact uploads with
  `compression-level: 0`.
- Sizes: tar 146 MB, `.tar.xz` 28.4 MB (stored), APK 30 MB.

**Notes**
- The recorded digest is the multi-arch index digest of `debian:trixie`
  (`RepoDigests`), which identifies the release; the arm64 manifest digest
  differs.
- The tar has docker's empty `etc/hostname`, `etc/hosts`, `etc/resolv.conf`
  placeholders; 1.4 writes real `resolv.conf` and `hosts`.

**Commits:** `5acb33f`

### 2026-10-03 (3): step 1.2, proot in the APK

**Done**
- `scripts/build-proot.sh <out-dir>` builds Termux's proot `v5.1.107.96`
  (the release Termux ships) for arm64, API 26, with talloc 2.5.0 linked
  statically. CI runs it with the runner's NDK (29.0.14206865), caches
  `build/proot` keyed on the script hash + NDK version, and copies the two
  `.so` files into `app/src/main/jniLibs/arm64-v8a/` (gitignored).
- `packaging.jniLibs.useLegacyPackaging = true`, so they're extracted to
  `nativeLibraryDir` and can be executed.
- The test shell has `nativeLibraryDir` on `PATH` plus `PROOT_LOADER` and
  `PROOT_TMP_DIR` (`cacheDir/proot`).
- Confirmed on the phone: `libproot.so --version` prints the banner
  (`process_vm = yes, seccomp_filter = yes`); `libproot.so -0 /system/bin/id`
  prints `uid=0(root)`, so tracing through the unbundled loader works.

**Decisions / gotchas**
- Loader unbundled (`PROOT_UNBUNDLE_LOADER`, path given via `PROOT_LOADER`):
  the bundled loader is extracted to app storage, where exec is forbidden.
- The 32-bit loader gets built (the makefile requires it on arm64) but isn't
  shipped: the Pixel 10 has no 32-bit support, and the rootfs is arm64-only.
- No `libandroid-shmem` (Termux links it for SysV shm); proot's own sysvipc
  extension is enough for now.
- Build fixes: proot's `ashmem_memfd.c` lacks `#include <string.h>` (patched
  in by `sed`; hence the `-dirty` version suffix; force-including it
  globally breaks the freestanding loader). talloc needs libreplace's objects
  in the static archive (`rep_memset_explicit`; no `memset_explicit` before
  API 34).
- Building locally with Termux's clang fails differently (its headers hide
  `memset_explicit` but waf's link check finds it) and takes ~20 min under
  proot; iterate in CI instead.
- No bats tests for the build script: it's verified by the CI build and the
  on-device check above.

**Commits:** `eef890e`, `2263916`, `515cfc9`, `0bda401`

### 2026-10-03 (2): step 1.1, terminal view with a local shell

**Done**
- Added Termux `terminal-view:v0.118.3` from JitPack (repo limited to the
  `com.github.termux.termux-app` group). API checked against the published
  sources jar, since `app/` can't compile on the phone.
- `MainActivity` is now a `TerminalView` + `TerminalSession` running
  `/system/bin/sh` (cwd and `HOME` = `filesDir`). `TerminalClients.kt` holds
  the session/view client callbacks: tap shows the soft keyboard, pinch
  changes the font size, clipboard copy/paste, Enter restarts a finished shell.
- Edge-to-edge (targetSdk 35+ ignores `adjustResize`): a `FrameLayout`
  around the terminal pads itself by the system bar and IME insets.
  `TerminalView` ignores its own padding (it draws from 0,0), so padding the
  view directly put the first row under the status bar.
- `configChanges` on the activity so rotation/keyboard changes don't recreate
  it and kill the shell (the background service in step 2 replaces this).
- Uncaught exceptions are written to `filesDir/last-crash.txt` and shown in
  a dialog on next launch (no logcat on the dev phone).
- `abiFilters = arm64-v8a`: proot and the rootfs will be arm64 only.

**Decisions**
- No spike branch: this step is pure Android glue with no logic for `core`,
  so there was nothing to rebuild test-first. No tests added for it.

**Owner confirmed on the phone:** keyboard, rendering, colours, pinch zoom,
rotation and shell restart work. The first build drew under the status bar;
fixed in `d7952ea` and confirmed (status bar and keyboard both clear).

**Commits:** `40dc32a`, `d7952ea`

### 2026-10-03: scope, build pipeline, TDD

**Done**
- Wrote the product scope in `README.md` with the owner: built-in Debian,
  Windows Terminal-style tabs, in-app keyboard, phone-sized keyboard-driven
  terminal file manager (`pocket files`), customization, shareable profiles,
  Android integration (incl. location, camera, sensors), AI agent support,
  development boards (later). Roadmap of 11 steps.
- Android app skeleton, GitHub Actions build, shared debug signing key.
  First APK installed and confirmed working on the phone.
- `AGENTS.md` (architecture, environment, design rules), `CLAUDE.md`
  importing it, `scripts/deliver.sh` for the build → install loop.
- TDD setup: plain-Kotlin `core` module, Gradle wrapper, pre-commit hook,
  CI TDD check, `scripts/setup.sh`, Claude Code `SessionStart` hook.
  `prootLaunch()` built test-first as the first example.

**Decisions**
- Build in GitHub Actions, not on the phone (no arm64 `aapt2`). `core`
  tests run on the phone.
- Reuse Termux's terminal libraries and its proot fork; don't fork the
  Termux app.
- Debian rootfs from the official `debian:trixie` arm64 Docker image.
- App ID `io.github.est4s.terminal` is name-neutral; "Pocket Terminal" is a
  working name (taken on the Play Store), to be renamed before release.
- Keyboard-first everywhere; tap support later, behind a settings toggle.
- Owner tried Android's built-in Linux Terminal (VM): too slow. The app
  stays proot-based.
- Repo is private for now; docs-only pushes skip CI to save build minutes.

**Commits:** `a658457` … `6cb11fd`

**Open issues:** none.
