# Agent guide

Context for AI agents (and humans) working on this repo. Read `README.md` first
for **what** the app does (and `docs/ROADMAP.md` for the full scope). This file covers **how** we build it and the decisions
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

**Keep it short** (maintainer's decision, 2026-10-08): the log holds
recent work. Once an entry's work is confirmed on the phone and what it
taught is in this file (decisions, gotchas), the entry can go; done
plans leave "Next" when they're done. Git history keeps everything
(the full log up to entry 84: `git show 72b8512:docs/WORKLOG.md`).

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
   Download folder and opens Android's installer with `mynx install-apk`.
   The maintainer just taps **Install**. The build installs as **mynx
   Dev**, a separate app (see "Naming"): run from mynx dev, the app
   restarts as the new build; run from mynx, mynx dev installs or
   updates beside it.

Notes on why the script does what it does:
- `mynx install-apk FILE` (left out of `mynx help` and the user guide)
  asks the app to open the installer, through `ApkProvider`, which
  serves just that one file to it. Only **debug builds** answer it
  (`BuildConfig.DEBUG`); release builds use the same installer path for
  `mynx update` (see "Updates" under Architecture), so
  `REQUEST_INSTALL_PACKAGES` and `ApkProvider` are in the main manifest
  (maintainer's decision, 2026-10-08). Play restricts that permission: a
  Play build would need a variant without them.
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
GitHub Releases with generated notes (a `-suffix` tag makes a pre-release).
The release workflow must keep attaching `mynx-X.Y.Z.apk` under that
name: installed apps find their update by it (`apkName()`). Release
builds have no minify (R8 problems would only show on the phone).
The version name reaches Debian in the tools' `.version`
(`BUILD-INSTALLTIME-NAME`), which `mynx about` shows.

**Dependencies:** prefer few, well-maintained ones. Plain Android views, no
Jetpack Compose (smaller APK, faster CI, and the terminal library is
View-based).

---

## Architecture (decided)

See `docs/ROADMAP.md` "How it works". The details:

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
  colours 0-15. A user's `~/.config/mynx/colors.properties` is
  laid over Neon; `mynx theme set` writes a theme into it, marked
  `# theme: NAME`. The launcher menu uses the 16 basic colours, so it
  follows the theme.
- **Settings:** `~/.config/mynx/settings.conf`, `key = value`
  (`core/.../Settings.kt`: `SETTINGS` describes each; `setSetting()`
  changes one line and keeps the rest). Font size in dp (pinch saves it),
  a font file from Debian, cursor style and blink.
- **The app's tools** (`tools/` → `/opt/mynx`): `mynx` and
  the settings editors (`tools/lib/mynx/`), `menu` and its
  `menu.conf`, `files`, `keybar`, `play`, the game commands, and the
  agent guide `AGENTS.md`; `scripts/pack-tools.sh` adds core's built-in
  key bars and themes (to read and copy) and the home folder's
  `AGENTS.md`/`CLAUDE.md` pointers (`home/`). They belong to the app, not
  the user's Debian: CI packs them into `assets/tools.tar.xz`, and `TerminalService` unpacks them into
  `filesDir/tools` whenever the app version changes (`core/.../ToolsInstaller.kt`),
  then proot mounts that folder at `/opt/mynx`. Its `bin` goes
  first on the PATH (so it beats older copies left in a rootfs) through `/etc/profile.d/mynx.sh`, which the app
  rewrites at start (`writeToolsProfile()`): Debian's `/etc/profile`
  resets root's PATH, so the PATH the app passes in doesn't survive a
  login shell. So fixes to them reach installed Debians without a
  migration. Put new app-owned commands there, not in the rootfs.
- **Title art and icon:** the boot splash (`menu --boot`) plays
  `tools/lib/mynx-art` ("mynx" in quadrant-block pixels, cyan with a
  pink drop shadow; Neon's colours built in, a test checks them). The
  app icon is its "m" (maintainer's choice, 2026-10-09): the launcher
  foreground, the themed and notification icons (no shadow) and
  `docs/images/icon.svg` are its pixels, one wide and two tall, cut
  from `mynx-art --plain`. The README's title,
  `docs/images/mynx.svg`, is the whole art (`scripts/mynx-svg.py`).
  Change the art, regenerate them too.
- **Who checks what:** the program that reads a file checks it. The app
  (`core`) checks colours, themes, settings and key bars (`checkConfig`);
  the menu checks `menu.conf` (`menu --check FILE`); `mynx check`
  merges both.
- **`mynx` ↔ app:** request files, no sockets. `mynx` writes
  `ID.req` (renamed into place) to `$MYNX_REQUESTS`
  (`/tmp/.mynx/requests`); the service answers in `ID.reply`
  as JSON (`core/.../MynxRequests.kt`), woken by a `FileObserver`, with
  the activity's 250 ms poll as a fallback. Logic (checking, answers)
  stays in `core`; `mynx` only sends, waits and prints. Requests
  `mynx` triggers apply quietly (no dialogs: `mynx` prints the
  problems).
- **Requests answered later, and streams** (`core/.../LaterRequests.kt`,
  for GPS fixes, permission dialogs, sensor readings): a request named
  in `MynxRequests(later = …)` gets `ID.wait` at once (seconds to
  wait, or `stream`) and a `PendingReply` that answers in `ID.reply`
  when ready, appending stream readings to `ID.stream` (one JSON object
  per line). `mynx` cancels with `ID.cancel` (Ctrl+C, closed pipe);
  the service calls `sweep()` on that and every 2 s while requests are
  open, which also stops those whose `mynx` process (the number
  before `-` in the id) has gone. Values with line breaks (clipboard
  text) are sent percent-encoded.
- **Location** (`core/.../Location.kt`, `app/.../Locator.kt`):
  `locationRequests()` gives the `location` and `location-stream`
  [Later] requests; core parses options, picks providers from what
  Android allows (`locationProviders`) and writes fixes as JSON.
  `Locator` listens to LocationManager (GPS and network, first fix
  wins). Only "while in use" permission, asked through the activity,
  so locating must start on screen; while anything locates, the
  service adds the `location` foreground type (API 34+) so a stream
  keeps going in the background.
- **Sensors** (`core/.../Sensors.kt`, `app/.../SensorReader.kt`):
  `SENSOR_KINDS` maps Android's sensor types to names, value names and
  units (`compass` is worked out from the rotation vector in core);
  `sensorRequests()` gives `sensor-list`, `sensor` and `sensor-stream`
  and throttles streams to the asked rate. `SensorReader` listens on a
  thread of its own. Only the step sensors need a permission
  (physical activity), asked through the activity's
  `askPermissions()`, which location uses too.
- **Camera and flashlight** (`core/.../Camera.kt`,
  `app/.../CameraShooter.kt`): `cameraRequests()` gives the [Later]
  `camera` (the phone's camera app, `ACTION_IMAGE_CAPTURE`, 10 min) and
  `camera-quick` (Camera2 with no screen, 30 s); core checks the path
  and moves the finished photo from the app's cache into Debian, so a
  cancelled shot never touches the target. The camera app writes
  through `PhotoProvider` (one random name at a time); its result
  comes back through `MainActivity.startForResult()`. A quick shot
  runs a small YUV preview until `shotReady()` (exposure and focus
  settled, 0.3-2.5 s), then one JPEG turned by `jpegOrientation()`.
  Declaring CAMERA (for `--quick`) makes Android refuse the camera app
  too until it's granted, so both ask for it. `torch` is a plain
  request (`setTorchMode`, strength levels on API 33+), with no setting
  and no permission.
- **Sound files** (`core/.../Audio.kt`, `app/.../AudioPlayer.kt`,
  `app/.../AudioRecorder.kt`): `audioRequests()` gives `audio-play`
  and `audio-record`, both [Later] streams that run until they end or
  `mynx` stops them. The file's ending picks the format
  (`AudioFormat`): MediaRecorder for AAC and Opus, AudioRecord for
  WAV (header from core's `wavHeader()`). A recording goes to
  `.NAME.part` next to the target and core renames it when complete.
  Ctrl+C is the normal end of a recording, so a [Later]'s `onCancel`
  may still answer (`RecordReport.onStop` stops and reports before it
  returns), and `mynx` reads that answer (`answer_on_interrupt`).
  RECORD_AUDIO is asked through the activity; recording starts on
  screen and the service adds the `microphone` foreground type while
  anything records, like location.
- **Sound device** (`core/.../Sound.kt`, `app/.../SoundDevice.kt`,
  `tools/lib/sound-server`): PulseAudio in Debian, started by the
  service with the app (`sound-device` setting; an idle Pulse costs
  ~5.5 MB and no CPU, and it refuses to autospawn as root, so not on
  demand). It runs in a proot of its own (`prootLaunch(command = …)`),
  restarted with backoff (`ServerRestarts`), except exit 3: not
  installed (`mynx sound install` for Debians from before). Killing
  proot leaves Pulse running, so `sound-server` writes its pid to
  `sound/pid`: the app stops Pulse by it (`soundServerPid()` checks
  it's still `pulseaudio`), and so does the next start. Pulse
  plays into `module-pipe-sink` at `/tmp/.mynx/sound/out`
  (48 kHz s16 stereo); `PipePlayer` reads it in 20 ms chunks into a
  blocking AudioTrack, which paces the clockless pipe, pauses the
  track after 500 ms quiet and reopens the pipe when Pulse restarts.
  Tabs get `PULSE_SERVER`; ALSA programs follow through Debian's
  pulse plugin. **Microphone:** `module-pipe-source` `mic` reads
  `sound/in` (48 kHz mono). `tools/lib/sound-watch` (started by
  `sound-server`) writes Pulse's sources and source outputs to
  `sound/inputs` on every change; `MicFeeder` watches it and, through
  core's `micUsers()`/`micState()` (`Mic.kt`), opens an AudioRecord
  only while a program records from `mic` (maintainer's decision: the
  indicator shows only then). It feeds paced zeros (`Silence`) when
  it can't record, or the microphone fails (`MicInput` tries it
  again every 2 s): with no writer, Pulse gives recorders nothing and
  they hang. `sound-server` starts `sound-watch` just before it
  execs Pulse, so the watcher waits for its pid to become
  `pulseaudio`. The service adds the `microphone` type and names the
  programs in its notification (`micNotice()`).
- **Rotation lock** (`core/.../Rotation.kt`): `RotationLocks` holds
  each `mynx rotation lock` for a process (pid + start time from
  `/proc/PID/stat`, so a reused pid doesn't count); `mynx` sends its
  parent's pid. The service sweeps every second while locked and the
  activity sets `requestedOrientation` (LOCKED for "as it is").
- **Sharing** (`core/.../Sharing.kt`): `mynx share` files are served
  by `ShareProvider` (not exported, read-only) under a random token per
  share (`SharedFiles`, last 20 kept in memory), so receiving apps only
  reach the files they were granted. Incoming shares go to
  `ShareActivity`, which stays open while it copies (the read grant
  lasts as long as the activity) into the `share-folder` (`Inbox`:
  safe names, never overwrites) and posts a notification.
- **Updates** (`core/.../Updater.kt`, `Release.kt`, `Updates.kt`,
  `UpdateState.kt`): release builds only (`BuildConfig.DEBUG` off;
  mynx dev updates from CI). The check and download live in the app,
  not Debian, so a broken Debian can't block the update that would fix
  it. The service ticks hourly; `Updater.checkIfDue()` asks GitHub's
  latest-release API (no token: the repo is public) about once a day
  (an hour after a failure, at once if the clock went back), keeps
  what it found in `filesDir/state/update-state`, writes the newer
  version to `/tmp/.mynx/update-available` for the menu ("Update
  available" comes first while it's there) and posts a notification
  once per version; tapping it opens a tab running `mynx update`
  (`UPDATE_TAB_COMMAND`, like the Settings shortcut). `mynx update`
  sends the [Later] `update-check` (checks now, answers with the
  notes) and `update-install VERSION` (a stream of download progress,
  only the version the user saw): the APK goes to
  `cacheDir/updates/mynx-X.Y.Z.apk` through `downloadApk()` (size and
  GitHub's sha256 checked; a complete one is reused), then the
  installer opens through `ApkProvider`. Old downloads go at the next
  start. `update-check` (setting, on) only turns off the daily check.
- **Wakelock** (`wakelock` setting, off by default): `TerminalService`
  holds a `PARTIAL_WAKE_LOCK` ("mynx:service") while it runs and the
  setting is on, re-read with the sound setting (reloading requests)
  and when the app comes on screen; released on exit and `onDestroy`.
- **Launcher shortcut** (long-press the icon → Settings):
  `res/xml/shortcuts.xml` sends `io.github.est4s.terminal.SETTINGS`
  to `MainActivity`, which opens a new tab with core's
  `SETTINGS_TAB_COMMAND` once the service is connected. A tab can run
  a program first through `prootLaunch(command = runThenShell(…))`:
  a login bash runs it, then execs the usual login shell, so it works
  with any `.bashrc`.
- **Blocked `/proc` files:** Android hides some (`stat`, `vmstat`, …) from
  apps. The app probes them at each start and binds static stand-ins from
  `filesDir/fake-proc` over the blocked ones (`core/.../FakeProc.kt`).
- **Updates never overwrite the user's Debian.** The rootfs is unpacked once.
  App updates may only apply additive, versioned migrations. This is a hard
  rule: users' files and installed packages must survive every update.
- **Names:** the app, its command and its paths are all `mynx`
  (maintainer's decision, 2026-10-07). Nothing from before the rename is
  kept: no release had shipped, and the maintainer's Debian was moved over
  by hand.

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
- **Stop a proot with SIGQUIT** (`stopProot()`): it kills everything
  it runs, then exits. It ignores TERM and HUP, and SIGKILL (Termux's
  `finishIfRunning()`) leaves its programs running untraced: proot's
  seccomp filter stays on, every system call it would handle fails
  with ENOSYS, and they spin at full CPU. A proot can also hang with
  QUIT blocked while a program it traces sits stopped (seen once
  after a sound restart), so the force after the grace is
  `killProot()`: KILL what it traces (`TracerPid`), then proot.
  **Android's `Process.destroyForcibly()` only sends TERM** (its
  `UNIXProcess` doesn't override it); its pid comes from `toString()`
  (`processPid()`).
- **The host can't see a guest process's working directory:** proot
  tracks it itself, so `/proc/<pid>/cwd` stays at the folder proot was
  started in. Shells report their folder through `PROMPT_COMMAND` instead
  (`prootLaunch(cwdFile = …)`).
- App data paths come in two spellings (`/data/user/0/<app>` from
  `filesDir`, `/data/data/<app>` from the kernel). Don't compare paths by
  prefix across the two.
- **Don't `mv` a git repo (or anything with hard links) in proot.**
  proot fakes hard links with symlinks to `.l2s.*` files holding the
  absolute path, so a moved repo's loose objects all break ("bad object
  HEAD"), and those links can't be rewritten. Copy with `cp -rL` from
  the old place, delete the `.l2s.*` files in the copy, `git fsck`,
  then remove the original. tar is fine: proot shows each linked file
  as a plain one, so tar stores its contents, and the `.l2s.*` files
  can be left out (`scripts/backup-root.sh`).
- **On-device debugging without logcat:** write a trace file to
  `getExternalFilesDir(null)`; the maintainer can `cat` it from the app's own
  Debian under `/storage/emulated/0/Android/data/io.github.est4s.terminal/files/`.
  Remove it once the bug is fixed.

### Lessons from the phone

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
  media) without a media permission; test shares with files it made.
- **The first `assembleRelease` is the first time lint-vital runs**:
  debug builds skip it, so a release can fail on something debug
  builds never showed.

### Design rules from day one

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
  `tools/AGENTS.md` (`/opt/mynx/AGENTS.md`, updated with the
  app); root's home gets short `AGENTS.md` / `CLAUDE.md` files pointing
  to it (`rootfs/root/`), which are then the user's. When you add a
  feature that users can configure, update the guide in the same change;
  `tests/shell/home-docs.bats` fails if a home file, a built-in key bar
  or theme, or a `mynx` command isn't in it.
- **Don't bundle third-party agent CLIs** (Claude Code, Codex, …). Offer to
  install them with their official installers; users sign in with their own
  accounts.
- **Licence:** the app is MIT (maintainer's decision, 2026-10-08;
  GPL-3.0-only before, never released under it; `LICENSE`, copied to
  `tools/licenses/MIT.txt`). `mynx about` states it (`APP_LICENSE`, `APP_SOURCE` in
  `tools/lib/mynx/cli.py`). The name and icon aren't covered: forks
  use their own.
- **Licenses:** keep GPL components (proot) as separate executables and link
  their source from About (`mynx about`, the menu's System → About, in
  the terminal: maintainer's decision). Its credits are `COMPONENTS` in
  `tools/lib/mynx/cli.py`; the licence texts ship in `tools/licenses/`
  (`/opt/mynx/licenses`). When you add, upgrade or patch a bundled
  component (proot or talloc versions in `scripts/build-proot.sh`, the
  Termux libraries, the font), update both in the same change.

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
  push or merge at all; others fork and open PRs.
- **Releases:** only the maintainer tags a release (`docs/RELEASING.md`); an
  agent never creates or pushes `v*` tags.
- **README:** short, for users: what ships today, install, licence.
  The full scope and roadmap are `docs/ROADMAP.md`; when the maintainer
  changes scope, update it in the same commit, and the README when a
  feature ships.
- Don't describe in user-facing docs how this app itself is developed.
- In docs, call the person who runs the project **the maintainer**
  (not "the owner"; the maintainer's choice, 2026-10-08).
- Keep code comments sparse and about *why*, matching the existing files.
