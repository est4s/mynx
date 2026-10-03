# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **App:** a full-screen Termux `TerminalView` running Android's
  `/system/bin/sh` (step 1.1, waiting for on-device confirmation). Crashes are
  saved and shown in a dialog on the next launch.
- **Build:** GitHub Actions runs the TDD check and all tests, then builds a
  debug APK on every push to `main`. `scripts/deliver.sh` installs it on the
  phone.
- **Code:** `core/` has the proot launch command (`ProotLaunch.kt`, 7
  tests). Nothing in `app/` uses `core` yet.
- **Roadmap:** working on step 1, *Core*.

---

## Next

### Roadmap step 1: Core

**Goal:** opening the app shows a terminal running `bash` inside the built-in
Debian.

Suggested order (each step should end in a build the owner can install).
Logic goes into `core` test-first; the Android and CI parts are spikes per
[Spikes](../AGENTS.md#spikes). The proot command is already done:
`core/.../ProotLaunch.kt`, built by TDD as the first example.

1. ~~**Terminal view with a local shell.**~~ Code done, see log
   2026-10-03 (2). **Owner checks on the phone:** tap shows the keyboard and
   typing works; `ls -la /system/bin | head` renders; colours work
   (`printf '\e[31mred \e[32mgreen \e[0m\n'`); pinch zooms the font;
   rotating keeps the session; `exit` then Enter starts a new shell; the
   terminal isn't hidden behind the status bar or the keyboard. Fix anything
   reported before moving on.
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

#### Done when
- a fresh install shows a progress screen, then a `root@…` bash prompt
- `apt update && apt install -y htop` works, and `htop` draws correctly
- installing the next build as an update keeps installed packages and files
- errors (failed unpack, proot crash) appear on screen with details, not as a
  silent crash

Then continue with roadmap step 2 (tabs and the background service).

---

## Log

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
rotation and shell restart work. The first build drew under the status bar
(fixed above; confirm the fix and that the keyboard doesn't cover the bottom
rows).

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
