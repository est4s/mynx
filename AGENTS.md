# Agent guide

Context for AI agents (and humans) working on this repo. Read `README.md` first
for **what** the app does (and `docs/ROADMAP.md` for what's planned). This file covers **how** we build it and the decisions
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
   release mynx (where the maintainer develops), the installer can't
   open: the script says to open the file in the Files app, and mynx
   dev installs or updates beside it.

Notes on why the script does what it does:
- `mynx install-apk FILE` (left out of `mynx help` and the user guide)
  asks the app to open the installer, through `ApkProvider`, which
  serves just that one file to it. Only **debug builds** answer it
  (`BuildConfig.DEBUG`); release builds use the same installer path for
  `mynx update` (see "Updates" under Architecture), so
  `REQUEST_INSTALL_PACKAGES` and `ApkProvider` are in the main manifest
  (maintainer's decision, 2026-10-08). Play restricts that permission: a
  Play build would need a variant without them.
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
GitHub Releases with generated notes (a `-suffix` tag makes a pre-release).
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
  `docs/images/mynx.svg`, and the setup screen's
  (`res/drawable/mynx_title.xml`, `--vector`) are the whole art
  (`scripts/mynx-svg.py`; `test_art.py` fails if they're out of date).
  Change the art, regenerate them too.
- **Volume keys** (maintainer's design, 2026-10-11): a key bar's
  `VolumeUp`/`VolumeDown` lines (`ParsedKeyBar.volumeUp`/`volumeDown`,
  not buttons) say what the volume keys send while that bar shows.
  `MainActivity.dispatchKeyEvent()` hands them to `KeyBarView`, which
  asks core's `volumeKeyAction()`: keys the bar doesn't set pass on
  (the volume changes), Android's own key repeats are swallowed and the
  bar repeats with its button timing, only for lines with `Repeat`. No
  built-in bar sets them; no long press, no global setting (decided).
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
- **USB serial bridge** (`core/.../Rfc2217.kt`, `SerialServer.kt`,
  `Usb.kt`, `app/.../UsbBridge.kt`, `UsbActivity.kt`,
  `tools/lib/serial-port`; agent's design, 2026-10-16, PR #33): the
  app drives the device with usb-serial-for-android (JitPack, MIT,
  credited in `mynx about`) and serves each port over RFC 2217 on
  127.0.0.1 from 2217 (`SerialServer`: one client at a time, others
  refused; a client leaving drops DTR and RTS, as a close does on
  Linux; writes go through a queue thread so neither the USB reader
  nor the session waits on the network). For programs that want a
  device file, the app runs `serial-port NAME TCPPORT` per port in a
  proot of its own (restarted like the sound server): it makes a pty,
  links it as `/dev/serial/NAME` (the app's `filesDir/serial`, bound
  there in every proot, `prootLaunch(serialDir =)`; proot makes up
  the folder inside the host's `/dev`, as proot-distro's `/dev/shm`),
  and is the RFC 2217 client only while a program has the pty open
  (`POLLHUP` on the master says nobody has; polled ten times a
  second), following its termios (read from the master, which
  shares them). Names follow Linux (`ttyName()`: `ttyACM` for CDC
  ACM, else `ttyUSB`). Permission: `res/xml/usb_serial_devices.xml`
  makes Android offer to open the app (`UsbActivity`, which tells
  `MainActivity`, which refreshes the bridge), and `mynx usb allow`
  asks with `requestPermission` (a mutable, explicit PendingIntent).
  Requests: `usb-list`, `usb-allow` (`usbRequests()`).
  **Serial monitor** (`tools/apps/serial`, `mynx usb monitor`, bar
  `serial`): an app like the others, so the menu's Apps lists it; it
  opens the `/dev/serial` path and sets DTR/RTS with plain
  `TIOCMBIS`/`TIOCMBIC`, which the preload passes to the helper. No
  new-tab request: open a tab and run it (or Apps). Its resets follow
  esptool's sequences (`reset()`); the plain reset pulses RTS (an
  ESP32's EN) and then DTR (an Arduino's reset capacitor).
- **Serial preload** (`tools/src/libmynx-serial.c`,
  `scripts/build-preload.sh`; maintainer's decision, 2026-10-17: a
  global `LD_PRELOAD`, not a wrapper or a proot patch): Android refuses
  the modem-line ioctls, `TIOCEXCL`, `tcdrain`, `tcflow` and `flock` on
  a pty. The library wraps `ioctl`, `tcdrain`, `tcflow` and `flock`;
  each calls the real one first and acts only on EACCES for a
  `/dev/serial` pty (`/proc/self/fd/N` matched against the links),
  else returns as it was, errno kept. DTR/RTS go to `serial-port`'s
  control socket (`/dev/serial/.NAME.ctl`, one line per connection:
  `get|set|bis|bic BITS` → `ok BITS`), which sends SET-CONTROL in the
  safe order (`control_changes()`). `prootLaunch(preload =)` sets
  `LD_PRELOAD` inside `env -i` only (never proot's own environment:
  Android's linker), and only when the tools have the file and
  `serial-preload` is on (`serialPreload()`). Built for Debian's glibc,
  not with the NDK: CI cross-compiles with `gcc-aarch64-linux-gnu` and
  `pack-tools.sh` takes it as `PRELOAD`. Tests build it with
  `-DMYNX_SERIAL_TEST`, which refuses those calls itself on a port
  (a desktop allows them) and reads `MYNX_SERIAL_DIR`.
- **Serial port names** (`core/.../SerialNames.kt`; agent's design,
  2026-10-18, the `serial-names` setting, on since the phone
  tests of 2026-10-19): tools find ports by
  listing `/dev` (pyserial's `list_ports`, go.bug.st/serial's
  enumerator for arduino-cli) and read their USB details from
  `/sys/class/tty/NAME/device`. Android doesn't let apps list its
  `/dev`, proot's binds are fixed when it starts (a bind whose host
  path is missing is dropped with a warning; proot doesn't emulate
  `getdents`, so binds never show in a listing), and ports come and
  go. So tabs and services get a `/dev` of their own
  (`prootLaunch(ownDev =)`): `filesDir/dev`, with each of
  `HOST_DEVICES` the phone has bound in (a placeholder file or folder
  for each, so listings show it; never the phone's `ttyS*`), Linux's
  `fd`/`stdin`/… links, and a link `ttyACM0` → `serial/ttyACM0` per
  port, which the app makes and removes as ports change
  (`writeSerialNames()`, from `UsbBridge`'s `changed`); proot follows
  a link's guest-path contents, so it changes with no new bind.
  `filesDir/sys-tty` at `/sys/class/tty` is laid out as the kernel's
  (CDC ACM: `device` is the interface, subsystem `usb`; else the
  port under its interface, subsystem `usb-serial`; `idVendor`,
  `idProduct`, `serial`, `manufacturer`, `product` on the device).
  The helpers keep Android's `/dev`. Off: as before.
- **proot's mynx_serial extension** (`proot/mynx_serial/`,
  `proot/proot.patch`; agent's design, 2026-10-19): static programs
  (Go's: arduino-cli's `monitor` and `board list`) don't load the
  preload, so proot steps in, turned on by `MYNX_SERIAL_DIR` (the
  host's ports folder) in proot's environment (`prootLaunch(serialDir
  =)`). `scripts/build-proot.sh` copies the folder into proot's
  `src/extension/` and applies the patch (the makefile, the callback's
  declaration, `cli.c`'s check of the variable). `ports.c` is what it
  decides (tested natively, `tests/mynx/test_proot_serial.py`);
  `mynx_serial.c` only moves registers and memory, so it's tested on
  the phone. **ioctl:** a refused request on an fd whose
  `/proc/PID/fd` link is a port's pty is answered at the enter stage
  (the helper asked from proot, the call voided, 0 poked at the exit);
  the preload's real call then succeeds, so with both nothing happens
  twice. `tcdrain` doesn't wait (every tracee is stopped while proot
  works); `flock` and `tcflow` aren't covered. **uevents:** Android
  refuses `socket(AF_NETLINK, …, NETLINK_KOBJECT_UEVENT)` itself, so
  that call becomes a Unix datagram socket, bound by a chained
  `bind()` at `DIR/.uevent/PID-N` (the address written below the
  stack pointer, as `sysvipc` does); the program's own
  `bind(AF_NETLINK)` on it (known by its inode) is voided. The app's
  `UeventSender` sends core's `SerialUevents` (the kernel's `add` and
  `remove` messages for the ports' ttys, SEQNUM last) to every socket
  there after `writeSerialNames()`, off the main thread, deleting the
  ones that refuse. Termux's proot already stops at `ioctl` and
  `socket` exits, so it costs nothing new. A nested proot can't run
  in the app's Debian (the inner loader crashes under the outer's
  ptrace), so proot itself isn't tested here.
- **proot's mynx_links extension** (`proot/mynx_links/`; agent's
  design, 2026-10-20): link2symlink fakes a hard link as a symlink
  that every path call dereferences (`lstat`: a regular file,
  `readlink`: EINVAL), but `getdents64` comes from the kernel and says
  `DT_LNK`, so `shutil.copytree(symlinks=True)` fails on it
  (PlatformIO's ESP32 toolchains). At `getdents64`'s exit it gives
  each faked hard link the type of its file (`links.c`, decided as
  link2symlink's `resolve_faked_hard_link()` does; tested natively,
  `tests/mynx/test_proot_links.py`). Turned on with `--link2symlink`
  (the patch's `handle_option_link2symlink()`), not by a variable.
  Every `getdents64` now stops in proot (one `readlink` per symlink
  listed); the old 32-bit `getdents` isn't covered.
- **Rotation lock** (`core/.../Rotation.kt`): `RotationLocks` holds
  each `mynx rotation lock` for a process (pid + start time from
  `/proc/PID/stat`, so a reused pid doesn't count); `mynx` sends its
  parent's pid. The service sweeps every second while locked and the
  activity sets `requestedOrientation` (LOCKED for "as it is").
- **Vibration** (`core/.../Vibration.kt`, `app/.../Haptics.kt`):
  `Vibrations` checks everything against the `VibratorInfo` the app
  reports (`PhoneHaptics`: amplitude control, effect support, primitives
  and their lengths, by API level) and refuses what the phone can't do
  with the reason, then has `Haptics` play a `Vibration`. The plain
  `vibrate` request (returns at once) goes through
  `Vibrations.oneShot`; `vibrate-effect`, `-wave` and `-chain` are
  [Later] streams answered when the vibration should be over (Android
  has no "ended" callback: core works out the length, from the
  primitives' durations where Android gives them, else
  `estimateMs`, and the app's `Handler` times it); `vibrate-list` and
  `vibrate-stop` are [Later]s answered at once, so all of it stays in
  one file. One vibration at a time: a new one or `stop` answers the
  waiting one `"stopped": true`, and only the current one's Ctrl+C
  cancels the vibrator. API 33+ vibrates as `USAGE_MEDIA`, so the
  phone's media vibration setting applies.
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
- **Release notes** (maintainer's decision, 2026-10-12: bundled at
  build time, so they work offline): `scripts/release-notes.py` writes
  every published release's notes from GitHub's API into the tools
  (`release-notes.json`, through `pack-tools.sh`). `release.yml` also
  has GitHub generate the new tag's notes first and publishes the
  release with that same text (`--notes-file`, which sets no title, so
  it passes `--title` too); `build.yml` only warns
  if the API fails. `mynx notes` shows them (`tools/lib/mynx/notes.py`
  turns GitHub's Markdown into terminal lines). **What's new:** after
  an update the app restores the tabs, so the menu's boot doesn't run;
  instead `tools/shell.bash` compares the tools' version name with
  `~/.local/state/mynx/notes-seen` at each interactive login and, only
  when they differ, runs `mynx notes new` and `mynx notes seen`. With
  nothing seen yet, a Debian whose menu never ran (a new install, which
  gets the welcome page) shows nothing; an older one shows this
  version's notes. Debug builds are all `0.0.1`, so mynx dev never
  shows What's new.
- **Time zone** (`core/.../TimeZone.kt`): each tab gets `TZ` from
  Android's default zone, read as the tab starts (`phoneTimeZone()`).
  By name when Debian has its zoneinfo file, else a fixed POSIX offset
  (`<+03>-3`). Debian's `/etc/localtime` is left alone (the user's
  Debian), so programs started outside a tab, like the sound server,
  stay on UTC.
- **Wakelock** (`wakelock = auto | on | off`, default `auto`):
  `TerminalService` holds a `PARTIAL_WAKE_LOCK` ("mynx:service") while
  a background service runs (`auto`) or whenever it runs (`on`;
  `Wakelock.held()`), re-read when the services change, with the sound
  setting (reloading requests) and when the app comes on screen;
  released on exit and `onDestroy`.
- **Background services** (`core/.../Services.kt`, `ServicesModel.kt`,
  `ServiceManager.kt`, `app/.../ServiceSessions.kt`,
  `tools/lib/service-run`): each service is a `TerminalSession` no tab
  holds, started with `updateSize(80, 24, …)` (a session only starts
  on its first size). `ServiceManager` answers the `service-*`
  requests and drives the app through `ServiceHost`. **Service tabs:**
  a `Tab` with `service = NAME`; `Tabs.openService` shows one,
  `followService` swaps in a restarted service's session
  (`ServiceHost.started`), closing one only hides it (`setOpen`), its
  exit never closes or restarts it, and they aren't saved in
  `state/tabs`. Its title comes from core (`serviceTabTitle`,
  `ServiceManager.tabTitle`) and its bar falls back to `service`
  (`keyBarNames(fallback =)`). Long-press: Hide/Restart/Stop, or for a
  shell's tab Rename/Run as service (`ServiceManager.adopt`); the
  foreground command comes from `/proc` (`foregroundCommand()`: the
  shell's `tpgid`, that group leader's `cmdline`). The strip's
  `● N` (`servicesMark()`) and the notification's Services button
  open `SERVICES_TAB_COMMAND` (`menu services`). `service-set`
  (`setServiceKey()`) changes one key of a saved service's block, for
  `mynx service autostart` and the menu's Autostart item.
  `onTerminalCursorStateChange` doesn't say whose terminal changed, so
  the blinker always asks the shown one.
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
- **Battery stand-in** (`core/.../PowerSupply.kt`): Android blocks
  `/sys/class/power_supply`, so the service keeps a battery stream of
  its own (on changes, and every 30 s for the current) writing
  `filesDir/power-supply` in the kernel's names and units, and every
  tab's proot binds it there. Files are renamed into place and only
  when changed; values the phone stops giving lose their file.
  `current_now` is the current's size, as laptop drivers give it
  (maintainer's decision, 2026-10-11): the kernel's docs say negative
  while discharging, but psutil then counts negative time left.
  `charge_full` is worked out (charge left ÷ level): acpi reckons
  the level from it.
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
  **Installers do it too:** arduino-cli unpacks each toolchain into a
  temporary folder and renames it into place, so avr-gcc's linked
  binaries (`avr-g++`, `avr/bin/as`) dangle ("fork/exec …: no such
  file", `lstat` gives EPERM). Pointing them at the moved `.l2s.*`
  files again doesn't help (proot still refuses `lstat`); replacing
  each name with a copy of its file does: `mynx fix-links DIR`
  (`tools/lib/mynx/links.py`, the user guide's recipes). **Listings:**
  proot's `getdents` reported a faked hard link as `DT_LNK` while
  `readlink` on it gives EINVAL, so Python's `shutil.copytree(…,
  symlinks=True)` failed on any tree with hard links (PlatformIO's
  ESP32 toolchains): the mynx_links extension (above) reports its
  file's type instead.
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
  collect it first (it runs at audio priority). Android stops
  queueing at some number (~256 on the maintainer's phone): the library's read
  queue then threw from `read()` after taking the packet, so bytes
  were lost (4096 reads broke `chip_id`), and it turned each empty
  packet into a GET_STATUS of up to 200 ms. `UsbReads` queues as many
  as Android takes, keeps a refused one to queue again later, passes
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
  their source from About (`mynx about`, the menu's Settings → Help → About, in
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
  push or merge at all; others fork and open PRs. GitHub deletes a
  PR's branch when it's merged (the repo's auto-delete setting, on
  since 2026-10-13, maintainer's decision); delete the local branch
  yourself.
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
