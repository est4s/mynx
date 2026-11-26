# Agent guide

Context for AI agents (and humans) working on this repo. Read `README.md` first
for **what** the app does (and `docs/ROADMAP.md` for what's planned). This file covers **how** we build it and the decisions
already made. Status and next steps live in `docs/WORKLOG.md`.

Keep this file up to date: when a decision changes or a milestone lands, update
the relevant section (here or in `docs/ARCHITECTURE.md`) in the same commit.
Keep it short too: it's loaded into every session, so subsystem details go
in `docs/ARCHITECTURE.md`, not here.

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

**Keep it short** (maintainer's decision, 2026-10-08): the log holds
recent work. Once an entry's work is confirmed on the phone and what it
taught is in this file (decisions, gotchas), the entry can go; done
plans leave "Next" when they're done. Git history keeps everything
(the full log up to entry 92: `git show be3d232:docs/WORKLOG.md`).

## Naming

The app is called **mynx**, and so are its command, **`mynx`**, and
its paths: `/opt/mynx`, `~/.config/mynx`, `/tmp/.mynx`, the `MYNX_*`
variables (maintainer's decision, 2026-10-07: one name everywhere; people
and agents use the same command). The name is written in small letters
wherever it's shown, at the start of a sentence too: "mynx", "mynx dev"
(maintainer's decision, 2026-10-09). Code names (`MynxRequests`, the
`MYNX_*` variables) keep their case. Earlier working names were dropped
without a trace in the code; git history has them.
- Keep the display name in one place: `app/src/main/res/values/strings.xml`
  (`app_name`). Don't hardcode it elsewhere in code.
- The application ID `io.github.est4s.terminal` is deliberately name-neutral,
  because an ID can never change after publishing. **Never change it.**
- Debug builds are a separate app, **mynx dev**
  (`io.github.est4s.terminal.dev`, maintainer's decision, 2026-10-08), so
  they install beside the release with a Debian of their own. Its name is
  `app_name` in `app/src/debug/res/values/strings.xml`. Anything that
  names the app ID follows `${applicationId}` / `BuildConfig.APPLICATION_ID`;
  the launcher shortcut can't, so `app/src/debug/res/xml/shortcuts.xml`
  is a copy with the dev ID.
- Avoid "Debian", "Linux" and "Termux" in any future product name; they're
  fine in descriptions.

---

## Development environment

The maintainer develops on a **phone**: Claude Code runs in the app's own
Debian, on a Pixel 10 (arm64, Android 16). The terminal is about 56
columns wide in portrait.

Consequences:
- **No local Android builds.** Google's `aapt2` only exists for x86-64, so
  the `app` module can't build here; APKs come from **GitHub Actions**. Don't
  try to install the Android SDK locally. The plain-Kotlin `core` module
  **does** build and test locally: that's where the TDD loop runs.
- **No emulator, no `adb`** (yet). You can't see the screen or read logcat;
  the maintainer installs the build and reports back. Ask for specific observations
  ("what does the screen show after tapping X?") and make failures visible in
  the UI (show error text and stack traces on screen) rather than only in logs.
- JDK 21 (`openjdk-21-jdk-headless`) and `bats` are installed locally;
  `./gradlew :core:test` uses the JDK.
- `gh` is logged in as `est4s` with the `workflow` scope.

### Build → install loop

1. Commit on a feature branch, push it and open a PR (`main` takes no
   direct pushes, see "Branches"). `.github/workflows/build.yml`
   builds a debug APK for the PR and again on `main` after the merge
   (~7.5 min). Markdown-only changes skip the build.
2. Run `scripts/deliver.sh` in the app's Debian, with the app on screen.
   It waits for HEAD's run (on the current branch: a PR's, or
   `main`'s; or give it a run ID), downloads the APK, copies it to the phone's
   Download folder as `mynx-dev-RUN.apk` and opens Android's installer
   with `mynx install-apk`. The maintainer just taps **Install**. The
   build installs as **mynx dev**, a separate app (see "Naming"): run
   from mynx dev, the app restarts as the new build. Run from the
   release mynx (where the maintainer develops), mynx dev installs or
   updates beside it; a release up to 0.4.0 can't open the installer,
   so the script says to open the file in the Files app.

Notes on why the script does what it does:
- `mynx install-apk FILE` asks the app to open the installer, through
  `ApkProvider`, which serves just that one file to it. It's also how
  users' agents install apps they build in Debian (the guide's
  "Building an Android app"), so every build answers it (maintainer's
  decision, 2026-11-22; up to 0.4.0 only debug builds did): Android
  still asks the user before each install. `mynx update` uses the same
  path, and `REQUEST_INSTALL_PACKAGES` and `ApkProvider` are in the
  main manifest (maintainer's decision, 2026-10-08). Play restricts
  that permission: a Play build would need a variant without them.
- A new file name for each run: Android won't let the app overwrite a
  file in Download that another app made (the old debug-signed mynx
  left `mynx-build.apk`). The script deletes its own older builds.
- The first time, Android asks to allow the app to install apps; the
  request opens that setting and says to try again.
- If the request fails (an older build, the app not on screen), the APK
  is still in Download: open it from the Files app.

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
| Version name | `MYNX_VERSION_NAME` (the release workflow sets it from the tag, `v0.1.0` → `0.1.0`), else `0.0.1` |
| Signing | Debug: shared key in `signing/debug.keystore` (password `android`). Release: the maintainer's key from GitHub secrets |

**Signing:** the debug key is committed on purpose, so every CI build installs
over the previous one and keeps the app's data. It's only for development.
Release builds use a separate key: `.github/workflows/release.yml` decodes it
from the `MYNX_RELEASE_*` secrets into a temp file and passes it to Gradle in
`MYNX_RELEASE_KEYSTORE`, `MYNX_RELEASE_STORE_PASSWORD`,
`MYNX_RELEASE_KEY_ALIAS` and `MYNX_RELEASE_KEY_PASSWORD`; without them a
release build is unsigned and debug builds don't notice. That key must never
be committed (`.gitignore` blocks `*.jks`, `*.keystore` but the debug one),
and losing it means the app can never be updated. The maintainer makes and keeps
it: `docs/RELEASING.md`. Debug and release builds have the same application
ID but different keys, so one can't install over the other (uninstall
first, which deletes Debian).

**Releases:** push a tag `vX.Y.Z` → `release.yml` runs the tests, builds
the same assets as `build.yml` (its steps are copied: change both),
`assembleRelease`, checks the signature and publishes `mynx-X.Y.Z.apk` on
GitHub Releases with generated notes (a `-suffix` tag makes a pre-release),
beside `mynx-X.Y.Z-x11-source.tar.xz`, the GPL X server's corresponding
source (`scripts/build-x11.sh` makes it).
The release workflow must keep attaching `mynx-X.Y.Z.apk` under that
name: installed apps find their update by it (`apkName()`). Release
builds have no minify (R8 problems would only show on the phone).
The version name reaches Debian in the tools' `.version`
(`BUILD-INSTALLTIME-NAME`), which `mynx about` shows.

**Docker Hub:** both workflows sign in with the `DOCKERHUB_USERNAME`
and `DOCKERHUB_TOKEN` secrets (a read-only token the maintainer made,
2026-10-09) before pulling `debian:trixie`: anonymous pulls share the
runner's IP limit and ran out. Without the secrets (forks) they pull
anonymously.

**Robolectric** runs offline: Gradle fetches the Androids it uses
(`robolectricAndroids` in `app/build.gradle.kts`: 34 for the tests, 35
for the app's resources) from Google's mirror
of Maven Central (`settings.gradle.kts`), since Maven Central answered
CI with 429 Too Many Requests (2026-11-22). Upgrading Robolectric
means changing those versions too.

**Dependencies:** prefer few, well-maintained ones. Plain Android views, no
Jetpack Compose (smaller APK, faster CI, and the terminal library is
View-based).

---

## Architecture

The full design, subsystem by subsystem, is
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (see also
`docs/ROADMAP.md` "How it works"). **Read the entries for what you're
changing before you change it**, and update them in the same commit.
Many subsystems' contracts are in their KDoc, which the entry names.

What every session needs:
- **App:** one Kotlin app on Termux's `terminal-view` library (not a
  fork of termux-app), proot shipped as native libraries
  (`libproot.so`, run from `nativeLibraryDir`), Debian unpacked from
  the APK's assets on first launch.
- **Logic goes in `core`** (plain Kotlin, tested here); `app/` is a
  thin Android layer. `mynx` only sends requests and prints: the app
  answers them in `core/.../MynxRequests.kt` (request files in
  `/tmp/.mynx/requests`, no sockets).
- **App-owned commands go in `tools/`** (→ `/opt/mynx`, replaced on
  every update), not in the rootfs. The user guide is
  `tools/AGENTS.md` plus `tools/docs/*.md`: keep the core guide small
  (`home-docs.bats` caps it at 40 KB) and put a feature's details in
  its topic file.
- **Updates never overwrite the user's Debian.** The rootfs is
  unpacked once; app updates may only apply additive, versioned
  migrations. Users' files and packages must survive every update.
- **proot:** stop one with SIGQUIT, never KILL (its programs keep
  running untraced and spin). **Don't `mv` a git repo** (or anything
  with hard links) here: proot fakes hard links with absolute
  symlinks, so a moved repo breaks; copy with `cp -rL` instead.

Index of `docs/ARCHITECTURE.md`: app, terminal, proot, Debian, fonts,
colours, settings, the app's tools, title art and icon, volume keys,
who checks what, `mynx` ↔ app, requests answered later and streams,
location, sensors, camera and flashlight, sound files, sound device,
USB serial bridge, serial preload, serial port names, proot's
mynx_serial and mynx_links extensions, raw USB, UF2, Android SDK, Bluetooth serial,
Bluetooth LE, BLE UART ports, rotation lock, vibration, sharing,
updates, release notes, time zone, wakelock, background services, desktop mode,
extended screen (pairing and the link), web
apps (part 1, and part 2: the bridge), touch mode's Home, touch
mode's Files, viewers, more viewers, chat protocol, launcher shortcut, blocked `/proc` files, battery stand-in;
then Termux library notes and proot notes.

## Lessons from the phone

Bugs that took a phone round to find; keep them from coming back.
- **`app/` only compiles in CI**, so read Kotlin for what the compiler
  would catch: e.g. `var tabs = newTabs()`, where `newTabs()`'s lambda
  reads `tabs`, is a recursive type inference error.
- **Code in `tools/` finds its files relative to itself**, never
  through `/opt/mynx`: on the phone that's the installed copy, so tests
  pass there against old code (CI has no `/opt/mynx`).
- **A view with no height loses focus for good:** Android takes focus
  from it and doesn't give it back, so typing goes nowhere. Landscape
  with the keyboard up left the terminal no rows; the key bar now drops
  to one row and the tab strip hides first (`fitAroundTerminal()`),
  and the terminal takes focus back when it has height again.
- **Insets:** the root view pads for the system bars, the keyboard
  *and* `displayCutout()` (API 30+); without the cutout, landscape text
  ran under the camera hole while the keyboard didn't.
- **Shares from a browser** carry the link as `EXTRA_TEXT` and the
  site's icon in the ClipData as a preview: with text and no
  `EXTRA_STREAM`, the clip isn't a file to save (`filesToSave()`).
- **The app can't read `/storage/emulated/0/DCIM`** (or other apps'
  files) until All files access is on (`mynx storage allow`); test
  shares with files it made.
- **Codex's sandbox can't run under proot:** its bundled `bwrap`
  fails ("Can't read /proc/sys/kernel/overflowuid") and the Landlock
  mode needs bubblewrap too, so only `danger-full-access` works.
  Installing it asks to turn the sandbox off (`Agent.sandbox`), and
  its installer runs with `CODEX_NON_INTERACTIVE=1`: otherwise it
  starts Codex inside the install step.
- **Claude Code under proot** (`agents.launch()`): its cross-session
  messaging needs `--messaging-socket-path` (no uid map, so it can't
  check its own socket folder's owner), and its fullscreen "boot
  canary" turns fullscreen off for the version after two starts that
  die before the first healthy frame (`fullscreenAutoDisabled` in
  `~/.claude.json`; closing a tab while it starts is enough), leaving
  the inline mode that scrolls away. So `mynx agent start` (which the
  shell's `claude` function calls) adds the flag and
  `CLAUDE_CODE_NO_FLICKER=1`, unless settings.json says
  `"tui": "default"`.
- **Android forbids the modem ioctls on a pty** (`TIOCMGET`/`TIOCMSET`/
  `TIOCMBIS`, `TIOCEXCL`, `TCSBRK` so `tcdrain`, `TCXONC`,
  `TIOCGSERIAL`: EACCES, from SELinux), `flock` (picocom needs
  `--nolock`), and inotify on `/dev/pts/N`
  too. `TCFLSH`, `FIONREAD`, `TIOCOUTQ` and termios work. So pyserial
  can't open a pty (its open sets DTR and only ignores EINVAL and
  ENOTTY); it takes the `rfc2217://` URL instead.
- **glibc's `tcdrain()`, `tcflow()`, `tcsendbreak()` and `flock()`
  make the system call themselves**, not through the `ioctl` symbol,
  so a preload must wrap each of them; Python's `fcntl` and `termios`
  call the libc symbols, so pyserial goes through the wrappers.
- **Android lets apps `access()` some `/dev` files it won't let them
  `stat()`** (`/dev/full`, `/dev/fuse` on a OnePlus Nord 2, Android 13), so
  `File.exists()` says yes and proot drops the bind with a warning:
  probe with `Os.stat`, as proot does.
- **USB strings can end in a NUL** (an ESP32-C3's `Espressif\0`):
  Android passes them on, so they go through `usbString()`.
- **Bind `127.0.0.1`, not `getLoopbackAddress()`**: on Android that's
  `::1` (on a desktop JVM it's 127.0.0.1, so tests pass), and
  `serial-port` connects to 127.0.0.1: every `/dev/serial` pty was
  refused while `rfc2217://localhost` worked.
- **Keep many USB reads queued, our own** (`UsbBridge`'s `UsbReads`):
  with one read at a time, an ESP32-C3's USB-Serial-JTAG under
  esptool's stub sent only the first packet of each reply (`c0 01`)
  and lost the rest (the ROM loader, `--no-stub`, was fine); 16 fixed
  that, but `read_flash` still lost bytes. The stub flushes after
  every byte, so each read holds one byte. Linux queues each read
  again in the kernel as it completes; on Android our thread must
  collect it first (it runs at audio priority). Android sometimes
  refuses to queue a read again (past ~256 on the Nord 2): the
  library's read queue then threw from `read()` after taking the
  packet, so bytes were lost (4096 reads broke `chip_id`), and it
  turned each empty packet into a GET_STATUS of up to 200 ms.
  `UsbReads` queues as many as Android takes, keeps a refused one to queue again later, passes
  empty packets by, and strips FTDI's 2 status bytes itself (the
  library's FTDI `read` did). Each port gets its own
  `UsbDeviceConnection`: `requestWait()` returns any request of the
  connection, so ports sharing one would take each other's data.
- **Drop RTS before DTR** (`SerialServer`, a client leaving): DTR
  off with RTS on resets an ESP32 through the usual auto-reset
  circuit and a C3's USB-Serial-JTAG, into the bootloader when DTR
  was on just before, so every client that left (miniterm, a pty
  program) left the board in download mode. Linux changes both
  lines in one request; usb-serial-for-android sets them one at a
  time, so the order matters. Raising (the helper): DTR first.
- **`LocalSocket` creates its socket in `connect()`**: setting
  `soTimeout` first throws "socket not created" (`UeventSender`
  failed on every send that way).
- **Android 12+ kills big builds** (the phantom process killer): a
  parallel compile (PlatformIO's ESP32 build) got a tab's proot, and
  once a service's too, killed while the app stayed up, which looks
  like a proot crash: its tracees keep running and fail with ENOSYS.
  Rule that out before suspecting a proot change; the user guide
  ("How it runs") says how to turn the limit off per Android version.
  On the Nord 2 (Android 13), `settings put global
  settings_enable_monitor_phantom_procs false` is refused from adb
  (OnePlus withholds `WRITE_SECURE_SETTINGS`), but `device_config put
  activity_manager max_phantom_processes 2147483647` (with sync off)
  works: two C3 builds at once then passed (entry 138).
- **Android reads a USB device's descriptors through the fd it
  gives** (`openDevice()`), so it's at the end (offset 73 for an
  RP2040 in BOOTSEL), and a dup shares that: a program reading its
  node from the start, as on Linux, got 0 bytes. `UsbFds` seeks it
  back to 0. The RP2040's BOOTSEL drive shows as "damaged USB
  storage": Android won't mount it.
- **Tests run under the build being tested:** core's tests made a
  temp `…/dev/bus/usb/001/004`, and the spike's proot took it for a
  device (any path ending so). Match proot's paths exactly (the
  extension now takes only nodes beside the app's socket).
- **Tests that make links must not clean up with
  `deleteRecursively()`**: it follows a link to a folder, and tests
  run in the maintainer's own Debian, so a test linking to
  `/root/pixel-hero/assets` emptied it on every run. Use
  `deleteTree()` (`WebAppsTest.kt`), which deletes links themselves.
- **Several Gradle daemons at once run the phone out of memory**
  (4 GB each, one per worktree), and Android kills the app, with the
  agents in it. `~/.gradle/gradle.properties` caps them locally (1 GB,
  Kotlin's 512 MB, 10 minutes idle); run one Gradle at a time, and not
  beside the Python suite.
- **The first `assembleRelease` is the first time lint-vital runs**:
  debug builds skip it, so a release can fail on something debug
  builds never showed.

## Design rules from day one

These come from the scope (`docs/ROADMAP.md`) and apply to every feature:
- **Keyboard-first.** Everything must work from the keyboard (the in-app
  keyboard, once it exists). Tap/touch support is a low priority: add it later,
  off by default, behind a settings toggle. Exception (maintainer's decision,
  2026-10-05): swiping between tabs (`tab-swipe`) is on by default.
- **Plain-text config.** Every setting lives in a readable, commented text
  file inside Debian, so users and AI agents can edit it.
- **A `mynx` command for everything** the settings UI can do, with `--json`
  output. Settings editors are terminal programs (maintainer's decision,
  2026-10-04), built on `mynx`, so an AI agent can change everything a
  person can. `tests/shell/home-docs.bats` fails if a `mynx` command
  isn't in the home `AGENTS.md`.
- **Agent docs ship with the app.** The guide to the setup is
  `tools/AGENTS.md` (`/opt/mynx/AGENTS.md`, updated with the app),
  which every user's agent loads, so it stays small: what most tasks
  need, and a table of topic files in `tools/docs/`
  (`/opt/mynx/docs/`: the phone, apps, services, USB, Bluetooth) that
  agents read when the task comes up. Root's home gets short
  `AGENTS.md` / `CLAUDE.md` files pointing to it (`rootfs/root/`),
  which are then the user's. When you add a feature that users can
  configure, update the guide in the same change, its details in the
  topic file; `tests/shell/home-docs.bats` fails if a home file, a
  built-in key bar or theme, or a `mynx` command isn't in the guide
  and its topic files, or the core guide passes 40 KB.
- **Don't bundle third-party agent CLIs** (Claude Code, Codex, …). Offer to
  install them with their official installers; users sign in with their own
  accounts.
- **Licence:** the app is MIT (maintainer's decision, 2026-10-08;
  GPL-3.0-only before, never released under it; `LICENSE`, copied to
  `tools/licenses/MIT.txt`). `mynx about` states it (`APP_LICENSE`, `APP_SOURCE` in
  `tools/lib/mynx/cli.py`). The name and icon aren't covered: forks
  use their own.
- **Licenses:** keep GPL components (proot) as separate executables and link
  their source from About (`mynx about`, the menu's Settings → Help → About, in
  the terminal: maintainer's decision). Its credits are `COMPONENTS` in
  `tools/lib/mynx/cli.py`; the licence texts ship in `tools/licenses/`
  (`/opt/mynx/licenses`). When you add, upgrade or patch a bundled
  component (proot or talloc versions in `scripts/build-proot.sh`, the
  Termux:X11 commit in `scripts/build-x11.sh`, the Termux libraries, the
  font), update both in the same change. For Termux:X11 that includes
  `tools/licenses/X11-libraries.txt`, which its build checks.

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
  config parsing and checking, undo snapshots, migrations, the proot
  command, rootfs unpacking, keyboard layouts and key bars, and so on.
  (The file manager is nnn, set up in the rootfs.) Tests in
  `core/src/test/` (`kotlin.test` on JUnit 5).
- **`app/`**: a thin Android layer that connects `core` to Android (views,
  services, permissions, USB, camera). Keep logic out of it. When Android
  code needs tests, add Robolectric tests in `app/src/test/`; they only run
  in CI.
- **`mynx` and the editors** (`tools/lib/mynx/`, Python 3
  from Debian, standard library only): tests with `unittest` in
  `tests/mynx/` (`python3 -m unittest discover -s tests/mynx`, about
  1.5 min on the phone). They run `mynx` against a fake app that
  answers requests; `test_editors.py` drives the curses editors in a
  pseudo-terminal (send application cursor keys, `\x1bOA`, not
  `\x1b[A`). Keep file logic in `models.py`, unit-tested.
- **Shell code** (the menu and other commands in `tools/bin/`, root's
  dotfiles in `rootfs/root/`):
  tests with `bats` in `tests/shell/` (`bats tests/shell/*.bats`, here
  and in CI). Build scripts are checked by the CI build itself.

When you add a tested source set, add its `source test` pair to
`scripts/check-tdd.sh`.

### Running tests
- **On the phone:** `./gradlew :core:test`, about 15 s with a warm Gradle
  daemon (the first run downloads dependencies and takes a few minutes).
  Without an Android SDK, Gradle only configures `:core`; that's expected.
- **In CI:** `./gradlew test` runs every suite before the APK is built. A
  failing test means no APK.
- **Screenshots:** the app's Robolectric tests (`app/src/test/`,
  `Screenshots.kt`) draw touch mode's Home and Chat with sample data
  at phone sizes (412 dp and 360 dp wide, landscape) into
  `app/build/screenshots/`, which CI uploads as the `screenshots`
  artifact, even when a test fails: `gh run download RUN -n
  screenshots -D DIR`, then look at the PNGs. Robolectric renders them
  (its native graphics, Android 14), not a phone, so fonts and small
  details may differ; nothing compares them. Add a screen there when
  you change a view.

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
  it isn't obvious. Commit and push only when the maintainer asks.
- **Branches: trunk-based** (maintainer's decision, 2026-10-08): each
  feature on a short-lived branch, a PR into `main`, CI builds it, the
  maintainer tests that build in mynx dev, then it's merged. No
  long-lived `dev` branch. **`main` is protected** by the ruleset
  "Protect main" (on since 2026-10-08, maintainer's choice of "PRs
  only"): no direct pushes for anyone, the maintainer and agents
  included; a PR needs the `build` check green (no approvals: a solo
  maintainer can't approve their own PR); no force-pushes or deleting
  `main`. Admins may merge a PR without the check, which Markdown-only
  PRs need (they don't build). So work-log commits go through a PR
  too, usually with the work they describe. Only collaborators can
  push or merge at all; others fork and open PRs. GitHub deletes a
  PR's branch when it's merged (the repo's auto-delete setting, on
  since 2026-10-13, maintainer's decision); delete the local branch
  yourself.
- **`desktop-dev`** (maintainer's decision, 2026-11-24): desktop
  mode is built on a longer-lived branch, `desktop-dev`, cut from
  `main`. Its feature branches are cut from `desktop-dev` and their
  PRs go into it (`gh pr create --base desktop-dev`), not `main`; CI
  builds them and `desktop-dev` after a merge, like `main`. Merge
  `main` into it now and then; it goes into `main` in one PR when the
  maintainer says desktop mode is ready.
- **`screen-dev`** (maintainer's decision, 2026-11-26): the extended
  screen (a second phone running mynx as a screen of the first) is
  built the same way on `screen-dev`, cut from `desktop-dev` (its
  desktop part needs desktop mode): feature branches from it, PRs
  into it (`--base screen-dev`). Merge `desktop-dev` into it now and
  then.
- **Docs before a merge** (maintainer's rule, 2026-10-20): never
  merge a branch without first updating the docs it makes stale (the
  work log's status and "Next", the roadmap, the README, this file),
  in that PR.
- **Releases:** only the maintainer tags a release (`docs/RELEASING.md`); an
  agent never creates or pushes `v*` tags.
- **README:** short, for users: what ships today, install, licence.
  What's planned is `docs/ROADMAP.md`; when the maintainer
  changes scope, update it in the same commit, and the README when a
  feature ships.
- Don't describe in user-facing docs how this app itself is developed.
- In docs, call the person who runs the project **the maintainer**
  (not "the owner"; the maintainer's choice, 2026-10-08).
- Keep code comments sparse and about *why*, matching the existing files.
