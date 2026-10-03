# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **Roadmap step 2 (*Tabs*) is done** and confirmed on the phone: tab
  strip, background service, activity/bell marks, exit rule, and tabs
  (with their folders and names) restored after Android kills the app.
  Tabs can be renamed with a long-press (or Ctrl+Shift+R). Still
  untested: hardware keyboard shortcuts (owner has no keyboard, see
  "Hardware keyboard checks").
- **Step 3 (*Default setup*) in progress.** 3.1 (neon colours and Nerd
  Font) and 3.2 (customized rootfs: starship, eza, games, mc, no debconf
  warnings) and 3.3 (launcher menu) are done and confirmed on the phone.
  3.4 (agent docs in the home folder) is built, **waiting for the
  owner's check (needs a data clear)**; then step 3's final "Done when"
  pass. See "Next".
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
  `TabState.kt`, `HostPath.kt` and `ColorScheme.kt` (82 tests in all). `app/` is
  `TerminalApp` (crash reporter), `TerminalService`, `MainActivity` and
  `TerminalClients.kt`.

---

## Next

### Roadmap step 3: Default setup

**Goal (README roadmap):** Neon theme, fonts, launcher menu, games: what
a fresh install looks like. Out of scope: editors for any of it (step 6),
profiles (step 8), the in-app keyboard (step 5).

#### Decisions (owner, 2026-10-03)
- Ship all of the owner's Termux setup: **neon colours + JetBrains Mono
  Nerd Font**, **starship prompt + eza aliases**, the **launcher menu**,
  and the **games** (Neon Rogue, Neon Drive, neonflap).
- The launcher menu opens **in the first tab of a fresh start only**; new
  tabs and restored tabs go straight to a shell; `menu` opens it anytime.
- **Existing installs:** the owner clears the app's data once to get the
  new image (deletes their in-app Debian; they agreed). No migration code
  in this step. Migrations come later, before real users.

#### Sources (on the phone, outside this repo)
- Colours: Termux `~/.termux/colors.properties` (21 lines: background,
  foreground, cursor, color0–15). Reachable from Debian at
  `/data/data/com.termux/files/home/.termux/`.
- Font: `~/.termux/font.ttf` there (JetBrainsMono Nerd Font Mono, 2.6 MB).
  Prefer downloading the official release in CI or committing it with its
  licence (OFL-1.1); check which file variant the owner's is.
- Starship: Debian `/root/.config/starship.toml` (48 lines, neon palette).
  eza aliases: Debian `/root/.bashrc` (ls/ll/la/tree).
- Menu: Termux `~/bin/menu` (424 lines of bash, keyboard-only). It drives
  Termux and `proot-distro` (shebang, `DEB=(proot-distro login …)`, Termux
  rootfs paths, backup via proot-distro, Termux version in the status).
  The port runs *inside* Debian: drop the Termux/Debian split (Terminal =
  exit to the shell), drop or rework backup and update (update = `apt
  update && apt upgrade`), keep themes, state file, boot splash, Games and
  Files (mc).
- Games: Debian `/root/games/{neonrogue,neondrive,neonflap}/*.py`, single
  Python curses files. Copy into the repo (owner agreed).
- Packages, all in Debian trixie: `starship` 1.22.1, `eza` 0.21.0,
  `python3-minimal` 3.13 (curses needs `python3`, check), `mc`, `dialog`.

#### Suggested order (each ends in a build the owner installs)

**3.1 Neon colours and font (app side). Done, owner confirmed.**
Checks the owner ran (kept for regressions):
- terminal background is dark purple (`#14101f`), text off-white, cursor
  pink; `for i in $(seq 0 15); do printf '\e[48;5;%sm  ' $i; done; printf '\e[0m\n'`
  shows the neon palette
- the font is JetBrains Mono: `echo -e '\ue0b0 \uf07b \uf120'` shows
  Nerd Font icons (powerline arrow, folder, terminal), not boxes
- tab strip: same dark background, selected tab pink on `#241b35`, others
  light grey, cyan activity dot
- pinch zoom still works (font stays JetBrains Mono)
- `mkdir -p ~/.config/pocket-terminal && echo background=#000000 >
  ~/.config/pocket-terminal/colors.properties`, leave the app and come
  back: background turns black in every tab, rest stays neon
- add a line `oops` to that file, leave and come back: a "Problems in
  colors.properties" dialog says `line 2: expected key=value`; it doesn't
  come back on the next return unless the problems change
- delete the file, leave and come back: neon again

Original plan for 3.1:
- `core`, test-first: parse the Termux `colors.properties` format into a
  colour scheme (`#rrggbb`, `background`, `foreground`, `cursor`,
  `color0`–`color255`); bad lines are reported, not fatal. The neon scheme
  is the built-in default.
- App: apply it to sessions (check the library's `TerminalColors` /
  `TerminalColorScheme` API: Termux does
  `TerminalColors.COLOR_SCHEME.updateWith(props)` then resets each
  emulator's colours), use the font via `TerminalView.setTypeface()`, and
  match the strip/background colours to the scheme.
- Plain-text config rule: plan for the scheme to also be read from a file
  in Debian (e.g. `~/.config/pocket-terminal/colors.properties`) so users
  and agents can edit it; reading it can wait for 3.2's rootfs if simpler.

**3.2 Customized rootfs. Done, owner confirmed.** Checks the owner ran
after clearing the app's data (kept for regressions):
- prompt is starship: a Debian logo, `~` in cyan, pink `❯`; icons render
- the tab is named `~`, and after `cd /etc` it's `etc`
- `ls`, `ll`, `la`, `tree` show eza output with icons
- `rogue`, `drive`, `flap` start the games; quitting returns to the shell
- `apt update && apt install -y cowsay` prints no `debconf:` warnings
- `cat ~/.config/pocket-terminal/colors.properties` shows the Neon file;
  colours are still neon
- `mc` opens Midnight Commander (F10 quits)
- folder restore still works: `cd /etc` in a second tab, force-stop the
  app, reopen: both tabs back, the second in `/etc`
- `cat <(echo ok)` prints `ok` (process substitution: broken in the dev
  Debian, see AGENTS.md "proot notes"; if broken here too, fix it in the
  proot binds)
- APK size (50 MB after 3.2)

Original plan for 3.2:
- Ship the neon theme file into Debian as
  `/root/.config/pocket-terminal/colors.properties`: copy it from
  `core/src/main/resources/io/github/est4s/terminal/core/neon.colors.properties`
  (one source; its header comment is written for users).
- Replace `docker export debian:trixie` with a Dockerfile built for arm64
  with buildx + QEMU in CI (AGENTS.md). Install the packages above, set
  `DEBIAN_FRONTEND`/debconf so apt stops warning, add the games
  (`/usr/local/games/` or `/opt/…`, with `rogue`, `drive`, `flap`
  commands), root's dotfiles (`.bashrc` with starship init, eza aliases,
  a title-setting prompt so tabs get names, `LANG`), `starship.toml`.
- Check: `PROMPT_COMMAND` from the app must survive starship's init (tab
  folder restore). Check the APK size; keep `xz -9`.
- Bats tests for any shell scripts added (AGENTS.md: add the suite and CI
  step with the first shell feature).

**3.3 Launcher menu. Done, owner confirmed.** Checks the owner ran after
clearing the app's data (kept for regressions):
- the boot splash plays (glitching title, `[ OK ]` lines incl. `debian
  13.x` and `games: 3 found`), then the menu; the tab is named `Menu`
- status line: time · Debian 13.x · free space
- 1–5, j/k and Enter work; Games lists Neon Drive, Neon Flap, Neon Rogue;
  each starts and returns to the menu
- Files opens `mc` in `~`; System → Update all runs apt and waits for a
  key; System info shows Debian, kernel, memory, storage and size; Theme
  cycles neon → amber → phosphor and is remembered by `menu`
- Terminal (or q) drops to the shell; `menu` reopens it without the
  splash; Exit closes the tab (and the app, if it was the last one)
- `+` opens a tab with a plain shell, no menu
- force-stop and reopen: tabs come back as shells, no menu; notification
  Exit, then reopen: the menu again

Original plan for 3.3:
- Port `menu` into the rootfs (`/usr/local/bin/menu`). The app tells the
  first shell of a fresh start to open it (e.g. an env var through
  `prootLaunch`, tested in `core`); `.bashrc` runs `menu` when it's set.
- bats tests for the menu's non-interactive parts (state file, items).

**3.4 Agent docs in the home folder. Built, not yet confirmed.** Owner
clears the app's data, opens it, and checks:
- `ls ~` shows `AGENTS.md` and `CLAUDE.md`; `cat ~/CLAUDE.md` prints
  `@AGENTS.md`; `less ~/AGENTS.md` reads well at phone width
- (optional) an AI agent started in `~` picks it up, e.g. asks it "where
  do I change the terminal colours?"

Original plan for 3.4: a first `/root/AGENTS.md` + `CLAUDE.md` describing
the setup (design rule in AGENTS.md).

#### Done when (the owner checks these on the phone, after clearing data)
- the terminal uses the neon colours and the Nerd Font (icons in the
  starship prompt render)
- the first tab opens the menu; new tabs open a shell; `menu` works
- Games → each game starts and returns to the menu
- `ls`/`ll` use eza; prompt is starship; tabs show a title, not "Tab N"
- `apt install` shows no debconf warnings
- tab folder restore still works (force-stop test from step 2)

#### Where things stand (read the code first)
- `app/.../TerminalService.kt` owns `tabs: Tabs<TerminalSession>`, starts
  shells with `startShell(cwd)` (proot via `prootLaunch()`), saves tabs to
  `filesDir/state/tabs` (`saveTabs()`, on every change and on the
  activity's `onStop`) and restores them in `currentSession()`. Each shell
  gets `PROMPT_COMMAND` writing `$PWD` to `/tmp/.pocket-terminal/cwd-N`
  (Debian path), read by `cwdOf()`; the dir is cleared when the service
  starts. `exit()` deletes the state file.
- `app/.../MainActivity.kt`: install screen, tab strip (`renderStrip()`,
  long-press → `showRename()` dialog), `onTabAction()`, insets, crash
  dialog. Colours are constants at the top
  (`ACCENT`, `MARK`, …). The terminal font is the default monospace.
- `core/`: `ProotLaunch.kt` (argv/env incl. `cwdFile`), `RootfsInstaller`,
  `FakeProc`, `Tabs`, `TabShortcuts`, `TabState`, `HostPath`.
- Rootfs: `scripts/build-rootfs.sh` builds `rootfs/Dockerfile` (3.2).
  The menu goes in there too (e.g. `rootfs/bin/menu` →
  `/usr/local/bin/menu`), its bats tests in `tests/shell/`.

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
- apt prints `debconf: unable to initialize frontend` warnings (no dialog
  program, no `Term::ReadLine`), harmless. Fix in step 3's rootfs
  customization (e.g. install `dialog`, or set the debconf frontend).
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

---

## Log

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

**Not verified:** "most of `/storage` needs a permission" is from
Android's rules, not tried in the app; correct it if the owner finds
otherwise.

**Commits:** (pending)

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
