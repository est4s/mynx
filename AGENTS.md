# Agent guide

Context for AI agents (and humans) working on this repo. Read `README.md` first
for **what** the app does. This file covers **how** we build it and the decisions
already made. Status and next steps live in `docs/WORKLOG.md`.

Keep this file up to date: when a decision changes or a milestone lands, update
the relevant section in the same commit.

---

## Status, work log and next steps

**Read [`docs/WORKLOG.md`](docs/WORKLOG.md) before starting work.** It says
where the project stands, what was done in earlier sessions, and exactly what
to do next.

**Update it before you finish** a session or hand off:
- add a dated entry at the top of the log: what you did, decisions made (and
  why), anything left broken or half-done, commits
- rewrite "Next" so a fresh agent can continue without asking
- commit it together with the work it describes

## Naming

"Pocket Terminal" is a **working name**. It is already taken on the Play Store
and will change before release.
- Keep the display name in one place: `app/src/main/res/values/strings.xml`
  (`app_name`). Don't hardcode it elsewhere in code.
- The application ID `io.github.est4s.terminal` is deliberately name-neutral,
  because an ID can never change after publishing. **Never change it.**
- Avoid "Debian", "Linux" and "Termux" in any future product name; they're
  fine in descriptions.

---

## Development environment

The owner develops on a **phone**: Claude Code runs in Debian under
`proot-distro`, inside Termux (Play Store build), on a Pixel 10 (arm64,
Android 16). The terminal is about 56 columns wide in portrait.

Consequences:
- **No local Android builds.** Google's `aapt2` only exists for x86-64, so
  the `app` module can't build here; APKs come from **GitHub Actions**. Don't
  try to install the Android SDK locally. The plain-Kotlin `core` module
  **does** build and test locally: that's where the TDD loop runs.
- **No emulator, no `adb`** (yet). You can't see the screen or read logcat;
  the owner installs the build and reports back. Ask for specific observations
  ("what does the screen show after tapping X?") and make failures visible in
  the UI (show error text and stack traces on screen) rather than only in logs.
- JDK 21 is installed locally, which `./gradlew :core:test` uses.
- `gh` is logged in as `est4s` with the `workflow` scope.

### Build → install loop

1. Commit and push to `main` (or open a PR). `.github/workflows/build.yml`
   builds a debug APK (~2.5 min). Pushes that only change
   Markdown files skip the build.
2. Run `scripts/deliver.sh` from Debian on the phone. It waits for the latest
   run, downloads the APK, copies it to the phone's Download folder, indexes it
   and opens Android's installer. The owner just taps **Install**.

Notes on why the script does what it does:
- Files written into `/sdcard/Download` from proot are **not** visible in the
  Files app until indexed; the script runs Termux's `termux-media-scan`.
- `termux-open` with the APK MIME type opens the system installer directly.
- Termux tools are called from Debian with Termux's `PATH`, `PREFIX` and
  `LD_LIBRARY_PATH` set (`/data/data/com.termux/files/usr`).

---

## Build setup

| | |
|---|---|
| Android Gradle Plugin | 9.4.1, with **built-in Kotlin**: don't apply `org.jetbrains.kotlin.android` |
| Kotlin (`core`) | `org.jetbrains.kotlin.jvm` 2.4.20 |
| Gradle | 9.8.0 through the wrapper (`./gradlew`), same on the phone and in CI |
| JDK | 21 in CI; Java/Kotlin target 17 |
| SDK | `compileSdk`/`targetSdk` 36, `minSdk` 26 |
| Version code | `GITHUB_RUN_NUMBER`, so every CI build installs as an update |
| Signing | Shared debug key in `signing/debug.keystore` (password `android`) |

**Signing:** the debug key is committed on purpose, so every CI build installs
over the previous one and keeps the app's data. It's only for development.
Release builds will use a separate key stored as a GitHub secret. That key
must never be committed, and losing it means the app can never be updated.

**Dependencies:** prefer few, well-maintained ones. Plain Android views, no
Jetpack Compose (smaller APK, faster CI, and the terminal library is
View-based).

---

## Architecture (decided)

See README "How it works". The details:

- **App:** a single Kotlin app. Don't fork `termux-app`; most of it manages
  Termux's own package system, which has `/data/data/com.termux` baked into
  every binary. We don't use it: Debian brings its own packages.
- **Terminal:** Termux's `terminal-emulator` and `terminal-view` libraries
  (Apache 2.0) from JitPack:
  `com.github.termux.termux-app:terminal-view:v0.118.3` (pulls in
  `terminal-emulator`, which includes the native `libtermux.so` used to start
  the shell on a pseudo-terminal).
- **proot:** Termux's Android-patched fork, `github.com/termux/proot`
  (GPL-2.0, actively maintained). Build it in CI with the Android NDK (talloc
  linked statically) and ship it as **native libraries**:
  - `libproot.so` (the proot executable)
  - `libproot-loader.so` (its loader; point `PROOT_LOADER` at it)

  Android only lets apps targeting API 29+ execute files from the app's
  native library directory, not from its data directory. Set
  `packaging.jniLibs.useLegacyPackaging = true` so the libraries are extracted
  to `applicationInfo.nativeLibraryDir`, and run them from there.
- **Debian:** an arm64 rootfs built in CI by `scripts/build-rootfs.sh`
  from `rootfs/Dockerfile` (buildx + QEMU, `--output type=tar`) on top of
  the official `debian:trixie` image, pinned to the digest it pulled. The
  Dockerfile adds packages (nnn, python3, …), root's dotfiles
  (`rootfs/root/`) and the games (`rootfs/games/` → `/opt/neon-games/`).
  The app's own commands are **not** in the image (see "The app's
  tools"). Ship it compressed in the APK's assets and unpack it on first
  launch into the app's private storage.
- **Terminal font:** JetBrains Mono Nerd Font Mono (OFL-1.1), downloaded
  in CI by `scripts/fetch-font.sh` (release pinned by hash) into
  `app/src/main/assets/fonts/` with its `OFL.txt`; never committed.
- **Colours:** Termux's `colors.properties` format, parsed in `core`
  (`ColorScheme.kt`). Built-in themes are
  `core/src/main/resources/.../themes/NAME.colors.properties`, listed in
  `BUILT_IN_THEMES` (Android can't list resources; a test checks the list
  matches the files). Each sets background, foreground, cursor and
  colours 0-15. A user's `~/.config/pocket-terminal/colors.properties` is
  laid over Neon; `pocket theme set` writes a theme into it, marked
  `# theme: NAME`. The launcher menu uses the 16 basic colours, so it
  follows the theme.
- **Settings:** `~/.config/pocket-terminal/settings.conf`, `key = value`
  (`core/.../Settings.kt`: `SETTINGS` describes each; `setSetting()`
  changes one line and keeps the rest). Font size in dp (pinch saves it),
  a font file from Debian, cursor style and blink.
- **The app's tools** (`tools/` → `/opt/pocket-terminal`): `pocket` and
  the settings editors (`tools/lib/pocket_terminal/`), `menu` and its
  `menu.conf`, `files`, `keybar`, `play`, the game commands, and the
  agent guide `AGENTS.md`; `scripts/pack-tools.sh` adds core's built-in
  key bars and themes (to read and copy) and the home folder's
  `AGENTS.md`/`CLAUDE.md` pointers (`home/`). They belong to the app, not
  the user's Debian: CI packs them into `assets/tools.tar.xz`, and `TerminalService` unpacks them into
  `filesDir/tools` whenever the app version changes (`core/.../ToolsInstaller.kt`),
  then proot mounts that folder at `/opt/pocket-terminal`. Its `bin` goes
  first on the PATH (so it beats older copies left in a rootfs) through `/etc/profile.d/pocket-terminal.sh`, which the app
  rewrites at start (`writeToolsProfile()`): Debian's `/etc/profile`
  resets root's PATH, so the PATH the app passes in doesn't survive a
  login shell. So fixes to them reach installed Debians without a
  migration. Put new app-owned commands there, not in the rootfs.
- **Who checks what:** the program that reads a file checks it. The app
  (`core`) checks colours, themes, settings and key bars (`checkConfig`);
  the menu checks `menu.conf` (`menu --check FILE`); `pocket check`
  merges both.
- **`pocket` ↔ app:** request files, no sockets. `pocket` writes
  `ID.req` (renamed into place) to `$POCKET_REQUESTS`
  (`/tmp/.pocket-terminal/requests`); the service answers in `ID.reply`
  as JSON (`core/.../PocketRequests.kt`), woken by a `FileObserver`, with
  the activity's 250 ms poll as a fallback. Logic (checking, answers)
  stays in `core`; `pocket` only sends, waits and prints. Requests
  `pocket` triggers apply quietly (no dialogs: `pocket` prints the
  problems).
- **Blocked `/proc` files:** Android hides some (`stat`, `vmstat`, …) from
  apps. The app probes them at each start and binds static stand-ins from
  `filesDir/fake-proc` over the blocked ones (`core/.../FakeProc.kt`).
- **Updates never overwrite the user's Debian.** The rootfs is unpacked once.
  App updates may only apply additive, versioned migrations. This is a hard
  rule: users' files and installed packages must survive every update.

### Termux library notes (v0.118.3, checked in their source)
- **Create `TerminalSession`s on the main thread:** each makes a `Handler`
  on the current thread's `Looper` and delivers output and exit events
  there.
- **A session's process starts on its first `updateSize()`**, which
  `TerminalView.attachSession()` triggers once the view has a size. A
  session that's never attached never starts (restored background tabs
  start when first shown). `pid` is 0 before that and -1 after exit.
- One `TerminalView` for all tabs: switch with `attachSession(other)`.
- `ViewClient.onKeyDown()` sees each key before the terminal; return
  `true` to consume it.
- The library prints `[Process completed (code N) - press Enter]` itself.
- No OSC 7 (working directory reporting). OSC 0/2 reach `onTitleChanged()`.
- Default colours are one static array,
  `TerminalColors.COLOR_SCHEME.mDefaultColors` (0-255 palette, then
  `TextStyle.COLOR_INDEX_FOREGROUND/BACKGROUND/CURSOR`). Each emulator
  copies it when it starts; `emulator.mColors.reset()` re-copies it.
- `TerminalView.setTypeface()` needs `setTextSize()` called first.
- **Cursor style** comes from `TerminalSessionClient.getTerminalCursorStyle()`,
  read by each emulator on reset; call `emulator.setCursorStyle()` to
  re-read it. Programs can still change it (DECSCUSR). **Blinking:**
  `TerminalView.setTerminalCursorBlinkerRate()` (0 or 100-2000 ms), then
  `setTerminalCursorBlinkerState(true, true)`; stop it in `onStop`.

### proot notes
- **The host can't see a guest process's working directory:** proot
  tracks it itself, so `/proc/<pid>/cwd` stays at the folder proot was
  started in. Shells report their folder through `PROMPT_COMMAND` instead
  (`prootLaunch(cwdFile = …)`).
- App data paths come in two spellings (`/data/user/0/<app>` from
  `filesDir`, `/data/data/<app>` from the kernel). Don't compare paths by
  prefix across the two.
- **`/dev/fd` in the dev Debian (proot-distro) is a frozen copy of one
  process's fd folder**, so `cat <(echo hi)` fails there. The app's own
  Debian is fine (checked on the phone in 3.2).
- **On-device debugging without logcat:** write a trace file to
  `getExternalFilesDir(null)`; the owner can `cat` it from the app's own
  Debian under `/storage/emulated/0/Android/data/io.github.est4s.terminal/files/`.
  Remove it once the bug is fixed.

### Design rules from day one

These come from the README scope and apply to every feature:
- **Keyboard-first.** Everything must work from the keyboard (the in-app
  keyboard, once it exists). Tap/touch support is a low priority: add it later,
  off by default, behind a settings toggle.
- **Plain-text config.** Every setting lives in a readable, commented text
  file inside Debian, so users and AI agents can edit it.
- **A `pocket` command for everything** the settings UI can do, with `--json`
  output. Settings editors are terminal programs (owner's decision,
  2026-10-04), built on `pocket`, so an AI agent can change everything a
  person can. `tests/shell/home-docs.bats` fails if a `pocket` command
  isn't in the home `AGENTS.md`.
- **Agent docs ship with the app.** The guide to the setup is
  `tools/AGENTS.md` (`/opt/pocket-terminal/AGENTS.md`, updated with the
  app); root's home gets short `AGENTS.md` / `CLAUDE.md` files pointing
  to it (`rootfs/root/`), which are then the user's. When you add a
  feature that users can configure, update the guide in the same change;
  `tests/shell/home-docs.bats` fails if a home file, a built-in key bar
  or theme, or a `pocket` command isn't in it.
- **Don't bundle third-party agent CLIs** (Claude Code, Codex, …). Offer to
  install them with their official installers; users sign in with their own
  accounts.
- **Licenses:** keep GPL components (proot) as separate executables and link
  their source from the app's About screen.

---

## TDD (required)

This project is built test-first. **Every agent follows this workflow;** the
hooks and CI enforce it.

### First time in a clone
Run `scripts/setup.sh`. It enables the git hooks in `.githooks/`. (Claude Code
does this automatically through `.claude/settings.json`.)

### The loop
1. **Red:** write a test for the next small piece of behaviour. Run it and
   **see it fail** for the right reason. Don't skip this: a test that never
   failed proves nothing.
2. **Green:** write the simplest code that makes it pass.
3. **Refactor:** clean up with all tests green.
4. Commit. Tests and the code they drive go in the **same commit**.

**Bugs:** first write a test that reproduces the bug and fails, then fix it.

### Where code and tests go
- **`core/`**: plain Kotlin, no Android imports. Put **all logic** here:
  config parsing and checking, the profile format, export/import, undo
  snapshots, migrations, the proot command, rootfs unpacking, keyboard
  layouts and key bars, and so on. (The file manager is nnn, set up in
  the rootfs.) Tests in `core/src/test/`
  (`kotlin.test` on JUnit 5).
- **`app/`**: a thin Android layer that connects `core` to Android (views,
  services, permissions, USB, camera). Keep logic out of it. When Android
  code needs tests, add Robolectric tests in `app/src/test/`; they only run
  in CI.
- **`pocket` and the editors** (`tools/lib/pocket_terminal/`, Python 3
  from Debian, standard library only): tests with `unittest` in
  `tests/pocket/` (`python3 -m unittest discover -s tests/pocket`, about
  1.5 min on the phone). They run `pocket` against a fake app that
  answers requests; `test_editors.py` drives the curses editors in a
  pseudo-terminal (send application cursor keys, `\x1bOA`, not
  `\x1b[A`). Keep file logic in `models.py`, unit-tested.
- **Shell code** (the menu and other commands in `tools/bin/`, root's
  dotfiles in `rootfs/root/`):
  tests with `bats` in `tests/shell/`. CI runs real bats. **On the phone
  run `scripts/bats-lite.sh tests/shell/*.bats`**: real bats needs process
  substitution, which the dev Debian's `/dev/fd` breaks (see "proot
  notes"); bats-lite supports only `@test`, `setup`, `run`, `skip`,
  `$output`, `$lines`, `$status` and the temp/dir variables. Build scripts
  are checked by the CI build itself.

When you add a tested source set, add its `source test` pair to
`scripts/check-tdd.sh`.

### Running tests
- **On the phone:** `./gradlew :core:test`, about 15 s with a warm Gradle
  daemon (the first run downloads dependencies and takes a few minutes).
  Without an Android SDK, Gradle only configures `:core`; that's expected.
- **In CI:** `./gradlew test` runs every suite before the APK is built. A
  failing test means no APK.

### What enforces it
- **`.githooks/pre-commit`** blocks a commit that changes `core/src/main/`
  without touching `core/src/test/`, and runs the core tests when Kotlin or
  Gradle files change.
- **CI** runs the same check on every pushed commit, then all tests.
- **Pure refactors** (behaviour unchanged, covered by existing tests): commit
  with `TDD_REFACTOR=1 git commit …` and add the line `TDD: refactor` to the
  commit message. Use this honestly; it's for refactors only.
- **Never** bypass the hook with `--no-verify`, and never delete or weaken a
  test to make it pass. If a test is wrong, say so and fix the test in its own
  commit, explaining why.

### Spikes
Some things are about finding out what Android allows (getting proot to run,
the terminal rendering) and can't sensibly be test-driven up front. For those:
spike on a separate branch, learn what works, then **throw the spike away**
and rebuild it test-first on `main`, moving the logic into `core`. The
on-device behaviour that can't be unit-tested gets an entry in the step's
"Done when" checks.

---

## Conventions

- **Commits:** short imperative subject line, with a body explaining why when
  it isn't obvious. Commit and push only when the owner asks.
- **README:** it's the product scope. When the owner changes scope, update the
  README and its roadmap in the same commit.
- Don't describe in user-facing docs how this app itself is developed.
- Keep code comments sparse and about *why*, matching the existing files.
