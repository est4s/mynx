# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **Roadmap step 1 (*Core*) is done** and confirmed on the owner's phone:
  opening the app shows a `root@localhost` bash inside the built-in Debian
  13 (trixie); `apt install` works; `htop` draws; app updates keep the
  user's Debian.
- **App:** one full-screen Termux `TerminalView`, one session, run through
  proot (`prootLaunch()` from `core`). First launch unpacks the bundled
  rootfs into `filesDir/debian` (~5 s, progress screen). Blocked `/proc`
  files get static stand-ins from `filesDir/fake-proc`. Crashes are saved
  and shown on the next launch. APK: 30 MB.
- **Build:** GitHub Actions runs the TDD check and all tests, builds proot
  with the NDK (cached) and the rootfs from `debian:trixie` (arm64), then a
  debug APK. `scripts/deliver.sh` installs it on the phone.
- **Code:** `core/` has `ProotLaunch.kt`, `RootfsInstaller.kt` and
  `FakeProc.kt` (24 tests in all). `app/` is `MainActivity` +
  `TerminalClients.kt`.

---

## Next

### Roadmap step 2: Tabs

**Goal:** several terminals in a Windows Terminal-style tab strip, kept
alive by a background service. Scope: README "Multiple terminals, in tabs".
Profiles (tab colours per profile, the ⌄ profile menu) come in step 8;
swipe gestures are touch, so low priority (see AGENTS.md design rules).

Suggested order (each step ends in a build the owner can install):
1. **Background service.** A foreground service owns the
   `TerminalSession`s; `MainActivity` binds to it and attaches the current
   one. Sessions must survive leaving the app, rotation and the activity
   being destroyed (then the `configChanges` workaround is no longer what
   keeps the shell alive). Needs `FOREGROUND_SERVICE` +
   `FOREGROUND_SERVICE_SPECIAL_USE` (API 34+ requires a type), a
   persistent notification ("N terminals running", with an Exit action),
   and `POST_NOTIFICATIONS` (ask on Android 13+). Optional wakelock comes
   later with settings. The session client callbacks must not hold the
   activity once it's gone.
2. **Tab model in `core`, test-first:** open/close/select/move tabs, titles
   (from the shell's OSC title, user rename overrides it), what to select
   after closing, activity/bell flags on background tabs. Plain Kotlin;
   the service holds one instance.
3. **Tab strip UI:** a strip along the top with title, close and **+**;
   scrolls sideways when full. Keyboard-first: hardware-keyboard shortcuts
   (e.g. Ctrl+Shift+T new, Ctrl+Shift+W close, Ctrl+Tab /
   Ctrl+Shift+Tab switch, Ctrl+Shift+1…9). Tapping tabs works too.
4. **Activity dot and bell** on background tabs.
5. **Restore open tabs** (titles, working directories) after the app is
   killed. Store the list in a plain-text file (design rule), parsing in
   `core`.

#### Done when
- open three tabs, run `top` in one, switch away and back: it's still running
- leave the app for a few minutes, come back: all tabs are still there
- rotating the phone or closing the activity doesn't lose any session
- `exit` in a tab closes it; closing the last tab leaves a sensible state
- background output shows an activity dot

### Small open items
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- apt prints `debconf: unable to initialize frontend` warnings (no dialog
  program, no `Term::ReadLine`), harmless. Fix in step 3's rootfs
  customization (e.g. install `dialog`, or set the debconf frontend).
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

---

## Log

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
