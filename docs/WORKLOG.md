# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **Roadmap step 2 (*Tabs*) in progress:** 2.1 (background service) done
  and confirmed on the phone; next is 2.2 (tab model in `core`).
- **Roadmap step 1 (*Core*) is done** and confirmed on the owner's phone:
  opening the app shows a `root@localhost` bash inside the built-in Debian
  13 (trixie); `apt install` works; `htop` draws; app updates keep the
  user's Debian.
- **App:** one full-screen Termux `TerminalView` showing one session, run
  through proot (`prootLaunch()` from `core`). The session lives in
  `TerminalService` (foreground service), so it survives Back, rotation and
  leaving the app; its notification has an **Exit** action. First launch unpacks the bundled
  rootfs into `filesDir/debian` (~5 s, progress screen). Blocked `/proc`
  files get static stand-ins from `filesDir/fake-proc`. Crashes are saved
  and shown on the next launch. APK: 30 MB.
- **Build:** GitHub Actions runs the TDD check and all tests, builds proot
  with the NDK (cached) and the rootfs from `debian:trixie` (arm64), then a
  debug APK. `scripts/deliver.sh` installs it on the phone.
- **Code:** `core/` has `ProotLaunch.kt`, `RootfsInstaller.kt`,
  `FakeProc.kt` and `ServiceNotification.kt` (27 tests in all). `app/` is
  `TerminalApp` (crash reporter), `TerminalService`, `MainActivity` and
  `TerminalClients.kt`.

---

## Next

### Roadmap step 2: Tabs

**Goal:** several terminals in a Windows Terminal-style tab strip, kept
alive by a background service, so long jobs survive leaving the app.

**Scope:** README "Multiple terminals, in tabs". Out of scope here:
- profiles: per-profile tab colours, the ⌄ profile menu, profile icons
  (step 8). Give each tab a single default colour for now.
- touch extras: swipe between tabs, drag to reorder, long-press menus.
  Keyboard-first rule (AGENTS.md); reordering by keyboard shortcut is
  enough.
- the in-app keyboard (step 5). Until then, shortcuts come from a hardware
  keyboard; tapping tabs and the **+** button must also work.

#### Where things stand (read the code first)
- `app/.../TerminalService.kt` owns the sessions in a plain
  `mutableListOf<TerminalSession>()`: `currentSession()` (last one, or a
  new one), `newSession()`, `restart(old)`, `exit()`. `startShell()` (moved
  here from the activity) builds the proot command and must run on the main
  thread. `showNotification()` (re)posts the foreground notification with
  `runningTerminalsText(sessions.size)`; call it whenever the count
  changes. `activity` is the attached `MainActivity` or null. 2.2 replaces
  the list with `Tabs<TerminalSession>`.
- `app/.../MainActivity.kt` starts and binds the service in
  `connectService()` (after first-run install), shows
  `service.currentSession()` in one `TerminalView`, and unbinds in
  `onDestroy()` without killing anything. Back = `moveTaskToBack(true)`.
  Also: first-run install screen, system-bar/IME insets (padding on the
  root `FrameLayout`, because `TerminalView` ignores its own padding),
  showing the last crash.
- `app/.../TerminalApp.kt`: `Application` with the crash reporter
  (`filesDir/last-crash.txt`).
- `app/.../TerminalClients.kt`: `SessionClient` (owned by the service,
  forwards screen updates to `service.activity` only for the session it
  shows) and `ViewClient` (tap → keyboard, pinch → font size, Enter
  restarts a finished shell via `activity.restartShell(session)`).
- `core/`: `prootLaunch()` (already takes `workDir`, useful for restoring
  tabs), `RootfsInstaller`, `writeFakeProc()`.

#### Facts about the Termux libraries (v0.118.3, checked in their source)
- **Create `TerminalSession`s on the main thread:** each one makes a
  `Handler` on the current thread's `Looper` and delivers output and exit
  events there.
- **A session's process starts only on its first `updateSize()`**, which
  `TerminalView.attachSession()` triggers once the view has a size. A tab
  that's never attached never starts. For background tabs (e.g. restored
  ones), call `session.updateSize(cols, rows, cellWidthPx, cellHeightPx)`
  yourself with the current view's values, or start them lazily when first
  selected.
- **Switching tabs** = `terminalView.attachSession(other)`, then
  `onScreenUpdated()`. Use one `TerminalView`, not one per tab.
- `session.updateTerminalSessionClient(client)` swaps a session's client,
  so the service can own the clients and forward to whichever activity is
  attached, or to none.
- **Titles:** OSC 0/2 reach `TerminalSessionClient.onTitleChanged()`, and
  `session.title` gives the current title. Debian's `/root/.bashrc` doesn't
  set one, so titles stay empty unless the shell sends them; fall back to
  "Debian" / "Tab N". (Step 3's dotfiles can add a title-setting prompt.)
- **No OSC 7** (working-directory reporting) in this emulator. To restore
  each tab's folder, either read the shell's cwd from the host side (proot
  is `session.pid`, bash is its child; `/proc/<pid>/cwd` would be the host
  path, so strip the rootfs prefix: needs a check on the phone), or have
  the shell write `$PWD` to a per-tab state file from `PROMPT_COMMAND`. If
  it gets fiddly, restore tabs in `/root` and leave cwd restore as an open
  item.
- `onBell()` and `onTextChanged()` arrive per session: use them for the
  bell icon and the activity dot on background tabs.
- `ViewClient.onKeyDown(keyCode, event, session)` sees each key before the
  terminal does. Return `true` to consume a shortcut.

#### Suggested order (each step ends in a build the owner installs and checks)

**2.1 Background service.** ✅ Done (`8dbace9`), see the log.

**2.2 Tab model in `core`, test-first.**
- A plain-Kotlin `Tabs` class, generic over the session type so `core`
  stays Android-free (e.g. `Tabs<S>`):
  - open (after the current tab), close, select, move left/right
  - titles: shell title, with a user rename that overrides it until cleared
  - which tab gets selected after a close (suggest: the one to the right,
    else the left one)
  - activity and bell flags, cleared when a tab is selected
- **When a shell exits:** follow Windows Terminal's default ("graceful"):
  close the tab if the exit code is 0, otherwise keep it open showing
  `[Process completed (code N) - press Enter]`, where Enter restarts it.
  This rule belongs in `core`, tested.
- **Closing the last tab:** stop the service and finish the activity.
  Reopening the app starts with one fresh tab.

**2.3 Tab strip UI.**
- A strip along the top of the terminal (inside the inset root, above the
  `TerminalView`): each tab shows its title and a close ×, then a **+**.
  It scrolls sideways when full and keeps the selected tab visible.
- Plain Android views, no Compose (AGENTS.md). Highlight the current tab;
  one default accent colour for now.
- Keyboard shortcuts (hardware keyboard, handled in `ViewClient.onKeyDown`):

  | Shortcut | Action |
  |---|---|
  | Ctrl+Shift+T | new tab |
  | Ctrl+Shift+W | close tab |
  | Ctrl+Tab / Ctrl+Shift+Tab | next / previous tab |
  | Ctrl+Alt+1…9 | jump to tab N |
  | Ctrl+Shift+PgUp / PgDn | move tab left / right |

  Keep the shortcut → action mapping in `core` (tested), so the in-app
  keyboard (step 5) and the customization step reuse it.

**2.4 Activity dot and bell.** A dot on background tabs that printed
output; a bell icon after `\a`. Driven by the flags in the `core` model.

**2.5 Restore tabs after the app is killed.**
- Save the tab list (order, user renames, selected tab, cwd if available)
  whenever it changes; restore on a cold start.
- This is app state, not a user setting, so keep it in app storage (e.g.
  `filesDir/state/tabs`), but as readable plain text. Parse and serialize
  in `core`, tested, and ignore broken files instead of crashing.
- Restored tabs start fresh shells (old processes are gone).

#### Done when (the owner checks these on the phone)
- three tabs open; `top` runs in one; switch away and back: still running
- leave the app for a few minutes (screen off too), come back: every tab
  is still there and still running
- rotating the phone, or pressing Back and reopening, loses no session
- the notification shows the tab count; **Exit** really stops everything
- `exit` in a tab closes it; `exit 1` leaves it open with the message, and
  Enter restarts it; closing the last tab closes the app cleanly
- a background tab that prints output shows the activity dot;
  `sleep 3; printf '\a'` in a background tab shows the bell
- after force-stopping the app (Android settings), reopening restores the
  tabs and their names
- every shortcut in the table works with a hardware keyboard, if the owner
  has one (otherwise check by tapping)

#### Working with the owner
- Keep each sub-step small; it ends in a build the owner installs. Ask
  before each commit and push (AGENTS.md); after pushing, run
  `scripts/deliver.sh` so the installer opens on the phone, then list
  concrete checks.
- There's no logcat on the phone: show errors on screen. The crash
  reporter in `MainActivity` stays; move it to an `Application` class if
  the service needs it too.
- Update this worklog (log entry + "Next") before finishing.

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
