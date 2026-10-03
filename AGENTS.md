# Agent guide

Context for AI agents (and humans) working on this repo. Read `README.md` first
for **what** the app does. This file covers **how** we build it, the decisions
already made, and what to do next.

Keep this file up to date: when a decision changes or a milestone lands, update
the relevant section in the same commit.

---

## Status

- **Done:** app skeleton + CI. The app is a single screen that says "build
  pipeline works" and shows its version and build number. It installs and runs
  on the owner's phone.
- **TDD setup done:** `core` module with tests, local test loop on the
  phone, pre-commit hook and CI checks (see [TDD](#tdd-required)).
- **Next:** roadmap step 1, *Core*. See [Next step](#next-step-roadmap-1-core).

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
- **Debian:** an arm64 rootfs built in CI from the official Docker Hub image
  (`debian:trixie`, `--platform linux/arm64`, then `docker export`). Later
  steps customize it at build time (packages, dotfiles, launcher menu, games)
  with a Dockerfile and buildx/QEMU. Ship it compressed in the APK's assets
  and unpack it on first launch into the app's private storage.
- **Updates never overwrite the user's Debian.** The rootfs is unpacked once.
  App updates may only apply additive, versioned migrations. This is a hard
  rule: users' files and installed packages must survive every update.

### Design rules from day one

These come from the README scope and apply to every feature:
- **Keyboard-first.** Everything must work from the keyboard (the in-app
  keyboard, once it exists). Tap/touch support is a low priority: add it later,
  off by default, behind a settings toggle.
- **Plain-text config.** Every setting lives in a readable, commented text
  file inside Debian, so users and AI agents can edit it.
- **A `pocket` command for everything** the settings UI can do, with `--json`
  output.
- **Agent docs ship with the app.** Each Debian environment gets `AGENTS.md` /
  `CLAUDE.md` in the home folder describing the setup. When you add a feature
  that users can configure, update those docs in the same change.
- **Don't bundle third-party agent CLIs** (Claude Code, Codex, …). Offer to
  install them with their official installers; users sign in with their own
  accounts.
- **Licenses:** keep GPL components (proot) as separate executables and link
  their source from the app's About screen.

---

## Next step: roadmap 1, Core

**Goal:** opening the app shows a terminal running `bash` inside the built-in
Debian.

Suggested order (each step should end in a build the owner can install).
Logic goes into `core` test-first; the Android and CI parts are spikes per
[Spikes](#spikes). The proot command is already done:
`core/.../ProotLaunch.kt`, built by TDD as the first example.

1. **Terminal view with a local shell.** Add the terminal libraries, put a
   `TerminalView` on screen and start `/system/bin/sh` in a `TerminalSession`.
   Proves keyboard input, rendering and colours. Show the soft keyboard on tap
   for now; the in-app keyboard comes in step 5.
2. **proot in the APK.** New CI job: clone `termux/proot` at a pinned commit,
   build `libproot.so` and `libproot-loader.so` for `arm64-v8a` with the NDK,
   cache the result and put them in `app/src/main/jniLibs/arm64-v8a/` before
   the Gradle build (don't commit the binaries). Check: run
   `libproot.so --version` in the terminal.
3. **Debian rootfs in the APK.** New CI step: export `debian:trixie` (arm64)
   to a `.tar.xz` and put it in `app/src/main/assets/` before the Gradle build.
   Watch the APK size; the bare rootfs should be ~30 MB compressed.
4. **First-run install.** Unpack the rootfs into `filesDir/debian/` with a
   progress screen. Use `org.apache.commons:commons-compress` +
   `org.tukaani:xz`. The unpacking logic belongs in `core`, test-first
   (feed it small test archives). Handle symlinks and file modes; convert hard links into
   copies or symlinks (Android's storage doesn't allow them for apps). Write a
   marker file only after everything succeeds, so an interrupted install
   restarts cleanly. Then write `/etc/resolv.conf` (e.g. `1.1.1.1`, `8.8.8.8`;
   Android has none) and `/etc/hosts`.
5. **Start Debian** with the command from `prootLaunch()` in `core`, which
   builds roughly:
   ```
   libproot.so --kill-on-exit --link2symlink -0 \
     -r <filesDir>/debian -w /root \
     -b /dev -b /proc -b /sys -b /storage \
     /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 \
       PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
       /bin/bash --login
   ```
   with `PROOT_LOADER` and `PROOT_TMP_DIR` (a folder in `cacheDir`) in the
   environment. Android blocks some `/proc` files; if tools break, bind fake
   ones like `proot-distro` does (`/proc/loadavg`, `/proc/stat`,
   `/proc/uptime`, `/proc/version`, `/proc/vmstat`).

**Done when:**
- a fresh install shows a progress screen, then a `root@…` bash prompt
- `apt update && apt install -y htop` works, and `htop` draws correctly
- installing the next build as an update keeps installed packages and files
- errors (failed unpack, proot crash) appear on screen with details, not as a
  silent crash

Then continue with roadmap step 2 (tabs and the background service).

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
  layouts, file manager logic, and so on. Tests in `core/src/test/`
  (`kotlin.test` on JUnit 5).
- **`app/`**: a thin Android layer that connects `core` to Android (views,
  services, permissions, USB, camera). Keep logic out of it. When Android
  code needs tests, add Robolectric tests in `app/src/test/`; they only run
  in CI.
- **Shell code** (the `pocket` CLI, rootfs build scripts): tests with `bats`
  in `tests/shell/`, runnable here in Debian. Add the suite and its CI step
  with the first shell feature.

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
