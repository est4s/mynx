# Architecture (decided)

How the app is built, subsystem by subsystem, and the decisions behind
it. `AGENTS.md` has the rules every session needs and an index of this
file; read the entries for the part you are changing. When a decision
changes or a subsystem lands, update its entry here in the same commit.


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
- **Fonts:** the terminal's is JetBrains Mono Nerd Font Mono (OFL-1.1);
  touch mode's headings, labels and buttons are Pixelify Sans (OFL-1.1,
  a variable font, `wght` 400-700; maintainer's decision, 2026-11-11),
  through `PixelStyle.kt`'s `Pixel.label()`. `scripts/fetch-font.sh`
  downloads both in CI (the Nerd Font's release and Google Fonts' file
  at a pinned commit, each pinned by hash) into
  `app/src/main/assets/fonts/`, with `OFL.txt` and
  `PixelifySans-OFL.txt`; never committed. CI caches that folder (keyed
  on the script), so a GitHub download outage only matters when a
  version changes.
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
  login shell. It also puts `~/.local/bin` before them (the
  user's own commands win), since services' login shells skip what
  `~/.bashrc` sets. So fixes to them reach installed Debians without a
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
  **Serial monitor** (`tools/terminal-apps/serial`, `mynx usb monitor`,
  bar `serial`): a terminal app like the others, so the menu's
  Terminal apps lists it; it
  opens the `/dev/serial` path and sets DTR/RTS with plain
  `TIOCMBIS`/`TIOCMBIC`, which the preload passes to the helper. No
  new-tab request: open a tab and run it (or Terminal apps). Its resets follow
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
  not with the NDK: CI builds it in Debian's arm64 image with its gcc
  (QEMU, as the rootfs; the runner's Ubuntu mirror hung the old cross
  compiler's install) and `pack-tools.sh` takes it as `PRELOAD`. Tests build it with
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
- **Raw USB** (`core/.../UsbDevices.kt`, `app/.../UsbFds.kt`,
  `proot/mynx_usb/`; agent's design, 2026-10-25, spike entry 141, PR
  #47): Android won't let apps open `/dev/bus/usb/*` (SELinux: no
  `open` on `usb_device`), but the fd `UsbManager.openDevice()` gives
  takes the usbfs ioctls. `UsbFds` serves a fresh one per request on
  a Unix socket in Debian's own `/dev` (`/dev/bus/usb/.ctl`; `BBB/DDD\n`
  in, `ok\n` with the fd by `SCM_RIGHTS` or `err ERRNO\n` out; the
  peer's uid checked), so usbfs claims are per program. It seeks the
  fd to 0 first: Android read the descriptors through it, and the copy
  shares the offset. **proot's `mynx_usb`** (`MYNX_USB_SOCKET`) turns
  a program's `open`/`openat` of a node beside that socket into
  chained `socket`, `connect`, `write`, `recvmsg`, `close` made by the
  program (memory below its stack pointer), so the fd lands in it, for
  programs that skip libc too (nusb, Go); only such an open stops at
  its exit (`sysexit_pending`, as link2symlink: `FILTER_SYSEXIT` would
  stop every open twice). A node whose bus folder is missing gets
  proot's own ENOENT before the extension sees it. **Finding devices:**
  Debian's libusb enumerates through libudev, which lists `/sys/bus`
  (Android refuses that) and reads `usb/devices/*/uevent` and the
  `subsystem` link, so the own `/dev` comes with an own `/sys/bus`
  (`OwnDev.sysBus`, `filesDir/sys-bus`): `writeUsbDevices()` lays
  out each device the app may use (no MAJOR/MINOR in `uevent`:
  libudev's enumerator skips a device with a number and no udev
  database entry; a folder per interface, as nusb reads), and each of
  `HOST_BUSES` the phone has is bound into it.
  `SYSTEMD_DEVICE_VERIFY_SYSFS=0` stops libudev refusing folders that
  aren't on a real sysfs. The stand-in is rewritten on Android's
  attach, detach and permission broadcasts and the app's USB refresh;
  `usb-allow` answers after it. **Not yet:** hotplug while a program
  runs (libudev's monitor wants libudev-format messages from uid 0).
- **UF2** (`tools/lib/mynx/uf2.py`, `mynx uf2`; maintainer's decision,
  2026-10-26: in Debian over raw USB, not in the app): Android binds
  `usb-storage` to a bootloader's drive and won't mount it, so `mynx`
  opens the device's node, lets the kernel's driver go
  (`USBDEVFS_DISCONNECT` through `USBDEVFS_IOCTL`, on `EBUSY` from the
  claim), and speaks bulk-only transport itself: TEST UNIT READY, READ
  CAPACITY, the FAT (an MBR first on the RP2040's), then each UF2
  block in its own sector from the data area with WRITE(10), 32
  sectors a command (UF2 bootloaders check every written sector for
  the magic). Safety: only a drive with `INFO_UF2.TXT` in its root
  folder is written; a known board (`BOARD_FAMILIES`) refuses other
  chips' families, which its bootloader would skip silently. A failed
  status after the last block is success: the board restarts first.
  Tested natively against a fake drive (`tests/mynx/test_uf2.py`);
  the usbfs ioctls only on the phone.
- **Android SDK** (`tools/lib/mynx/androidsdk.py`, `mynx android-sdk`;
  maintainer's decision, 2026-11-22): the guide's Android apps build
  with Debian's `aapt`, `zipalign`, `apksigner` and JDK (apt, shown and
  asked first) plus two of Google's files, `r8.jar` and Android 14's
  `android.jar` (taken out of the platform zip), downloaded into
  `~/androidsdk` where `build.sh` looks. Their URLs, sizes and SHA-256
  are pinned in `FILES`; a download is checked before it's moved into
  place, so a failed or wrong one leaves nothing. Only what's missing
  is installed. Tested against a local HTTP server
  (`tests/mynx/test_androidsdk.py`).
- **Bluetooth serial** (`core/.../Bluetooth.kt`, `app/.../BtBridge.kt`,
  `BtLine.kt`, `ServedPort.kt`; maintainer's
  decisions, 2026-10-26, entry 147: Classic SPP, Linux's `rfcomm
  bind`, pairing in Android's settings): a bound device is a port
  like a USB one (`/dev/serial/rfcommN`, RFC 2217 from 2317,
  `FIRST_BLUETOOTH_TCP_PORT`: bound ports are served from the start,
  and in USB's range would move the first USB port off the 2217
  recipes name) that costs nothing until used. The link opens when a client
  arrives and closes when it leaves (`SerialLine.clientArrived()`/
  `clientLeft()`): after the greeting and on the client's thread, as
  `serial-port` takes a close before the greeting for "busy" and the
  link takes seconds; a link that drops ends its client
  (`SerialServer.endClient()`). Bindings are config
  (`bluetooth.conf`, `rfcomm0 = ADDRESS`, as Linux's old
  `rfcomm.conf`), so restarts keep them and `mynx check` and undo
  cover them. `bluetoothRequests()` answers `bt-*` through a
  `BluetoothHost`; a bind may wait for the permission dialog before
  it writes, so a [Later]'s change is observed when it answers.
  `/dev/rfcommN`, a virtual tty in `/sys/class/tty` (no `device`) and
  uevents (major 216), as Linux makes them: pyserial globs
  `/dev/rfcomm*`. The app serves a port while the setting, the
  permission (`BLUETOOTH_CONNECT`, asked by `mynx bt`, never by `mynx
  usb`: `bt-list --no-ask`) and Bluetooth itself allow, re-read on
  Bluetooth's state broadcasts, reloading requests and coming on
  screen. Each port is a `ServedPort` (server, helper, link; shared
  with `UsbBridge`; the service, not a bridge, empties the shared
  ports folder at start). `BtLine` tries an authenticated link, then
  an unauthenticated one (old HC-05s), and closes a link still
  opening when its client leaves. After a failed link it refuses new
  ones at once for 2 s, doubling to 30 s, so a board that's off isn't
  paged without pause; `serial-port` backs off the same way (1 s,
  doubling to 30 s) when the app closes a connection before answering
  its setup (`Unescaper.answered`: the server answers only once the
  line is up), so its log isn't a line a second either. No `cancelDiscovery()`: it
  needs the scan permission.
- **Bluetooth LE** (`core/.../Ble.kt`, `BleRequests.kt`,
  `BleValues.kt`, `app/.../BleCentral.kt`; maintainer's decisions,
  2026-10-26, entry 151): no BlueZ (Android keeps the controller), so
  `mynx ble` is the interface. `Ble.kt`'s KDoc is the contract: the
  `ble-*` requests' arguments and JSON, and `BleHost`/`BleLink`,
  which only scan, connect and run one GATT operation at a time
  (`BleCentral` queues them on a `HandlerThread`, each with a 10 s
  timeout; connect asks MTU 517, then discovers, 30 s in all).
  Everything else is core's: UUIDs and known names, value formats,
  DEVICE by address or name (paired, or seen by a scan since the app
  started), the 5-scans-in-30-s count (Android then scans silently
  finding nothing), refusing an unfiltered scan in the background,
  and links: each request connects and closes its own, unless a
  `ble-connect` stream holds one for that device, which the others
  use. A name `ScanFilter` is exact, so `BleCentral` passes the name
  only in the background; in front core matches part of it.
  Permissions: `BLUETOOTH_SCAN` (`neverForLocation`) and `_CONNECT`
  on 31+, location (and it on) below. Built by four subagents in
  parallel against that contract (work log, entry 151); every phone
  test passed with an ESP32-C3 (entry 153, PR #53). A peripheral
  that vanishes (unplugged) may come back as status 0, not the
  supervision timeout's 8, so `linkStatus()` words 0 for both.
- **BLE UART ports** (`core/.../BleUart.kt`, `app/.../BtBridge.kt`;
  maintainer's decisions, 2026-10-26, entry 154: `mynx ble bind`,
  Nordic UART and HM-10): a `nusN = ADDRESS [random]` line in
  `bluetooth.conf` is served as `rfcommN` is (`ServedPort`,
  `/dev/serial/nusN`, RFC 2217 from 2417, `FIRST_NUS_TCP_PORT`;
  `/dev/nusN` and a virtual tty, no uevents: Linux has no such tty).
  Its line is core's `BleUartLine` over `BleHost`, so it's tested
  against fakes: it links when a client arrives (connect, `bleUart()`
  picks Nordic's service, then HM-10's `ffe0`/`ffe1`, subscribe),
  writes in MTU-3 chunks one at a time (no-response when it can),
  ends the client on a drop and backs off 2 s doubling to 30 s. The
  port's link is its own `BleCentral` connection beside any `mynx
  ble` request's; Android shares the device's notify setting between
  them, so ending a `notify` leaves it on while a port listens. On
  the phone (entry 156) an unplugged device took about 6 s to show as
  a drop (the link's supervision timeout), and echoes ran at
  15-32 KB/s with MTU 517.
  `ble-bind` connects once to check for a UART. Built by four
  subagents against the contract (entry 155).
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
  open `SERVICES_TAB_COMMAND` (`menu services`). The launcher
  menu's Services item shows the same count from a file the app
  writes on every change (`SERVICES_ACTIVE`,
  `/tmp/.mynx/services-active`, gone at 0): asking `mynx service
  list` would take half a second each time the menu redraws. `service-set`
  (`setServiceKey()`) changes one key of a saved service's block, for
  `mynx service autostart` and the menu's Autostart item.
  `onTerminalCursorStateChange` doesn't say whose terminal changed, so
  the blinker always asks the shown one.
- **Apps (web apps, part 1)** (`core/.../WebApps.kt`, whose KDoc is
  the contract; `app/.../WebAppActivity.kt`; `tools/lib/mynx/`'s
  `mynx app`; maintainer's decisions, 2026-11-08, work log entry 162):
  an app is a folder `~/apps/NAME/` or `/opt/mynx/apps/NAME/` with an
  `index.html` or an `app.conf` with `url` (NAME a DNS label: it's in
  the origin). Static apps are served at `https://NAME.mynx.invalid/`
  from the folder by `shouldInterceptRequest` through core's
  `respond()`, which resolves links as Debian would (guest paths;
  `.l2s.*` targets are host paths) and 404s anything outside the
  folder. Served apps load their `url` (cleartext only for 127.0.0.1
  and localhost, `network_security_config.xml`); `WebAppLauncher`
  starts their service if stopped, waits 15 s for the port, and stops
  it when the screen finishes only if it started it (never an
  autostart one), so `wakelock = auto` doesn't hold the CPU for a
  forgotten backend. Each app is an activity task of its own
  (`documentLaunchMode="intoExisting"`, data `mynx-app://NAME`).
  The screen has no bar of its own (maintainer's decision,
  2026-11-08): the page fills it inside the root's insets padding
  (system bars, cutout, keyboard), recents shows the name, back
  asks the page (`window.androidBack`, the name Pixel Hero's APK
  already used; `true` means handled), goes back, then closes,
  `mynx app reload` reloads it.
  Static apps reload when core's folder fingerprint changes (polled
  once a second off the main thread while on screen). Console, load
  and HTTP errors go to `/tmp/.mynx/apps/NAME.log` (`WebAppLog`).
  The bridge is part 2 (below). Today's programs in a tab are
  **terminal apps** (`tools/terminal-apps/`, `~/terminal-apps`, and
  old Debians' executables in `~/apps`), a folder at the end of Apps
  in both menus unless `menu.conf` lists `terminal-apps` itself
  (maintainer's decision, 2026-11-22); the menu's bash scan of apps
  must give core's labels (both tested on `tests/fixtures/apps/`).
- **Apps (web apps, part 2): the bridge** (`core/.../Bridge.kt`,
  `AppPermissions.kt`, `BridgeSerial.kt`, `Rfc2217Client.kt`, whose
  KDoc is the contract; `app/.../WebAppActivity.kt`,
  `FrontActivity.kt`, `AppShortcuts.kt`, `BridgeSockets.kt`;
  `tools/web/mynx.js`; maintainer's decisions, 2026-11-08, entry 167):
  pages load `https://mynx.invalid/mynx.js` (served by the intercept)
  and call `mynx.location()` and the rest, named like the commands.
  **Getting a port:** mynx.js asks with a `fetch` of
  `https://mynx.invalid/hello`; the intercept answers 204 and, if the
  view's current origin is the app's and `Bridge.refusal()` says go
  (a served app only while its own service runs: its origin is just a
  localhost port), posts one `createWebMessageChannel` port to that
  origin, a `BridgeSession`; not `addJavascriptInterface`, which every
  frame and origin sees. Posting at page commit/finish lost scripts
  loaded late. Every message is checked against the current origin
  again; all of a page's sessions close when it goes, cancelling what
  they started. **The seam:** `PendingReply` writes to a `ReplySink`
  (the file one is `mynx`'s, pinned byte for byte);
  `MynxRequests.call()` runs a request in-process, and `BRIDGE_CALLS`
  is the allow-list (no config requests). **Permissions:**
  `~/.config/mynx/app-permissions.conf` (`[app]`, `perm =
  allow|deny`), asked once on the app's screen for location, camera,
  microphone, clipboard reading, notifications, Bluetooth and serial;
  services are only the app's own, with no prompt. Android's
  permission dialogs and the camera's result go through whichever
  `FrontActivity` is in front, so they work from an app's screen.
  **Serial:** the bridge is an RFC 2217 client of the port's own
  server, so one user at a time stays as it is. Also `fullscreen =
  no|yes|edge` (edge runs under the cutout), `screen-awake`, and
  pinned shortcuts (`mynx app shortcut`, the menu's "Add to home
  screen…") that start mynx and the app's service.
- **Touch mode's Home** (`core/.../Home.kt`, `app/.../HomeView.kt`;
  the `view` setting): `HomeMenus` builds the launcher menu's lists
  from the same files, with the same rules as `menu_items` in
  `tools/bin/menu` (`tests/fixtures/menu.conf` is read by both test
  suites). Anything that runs a program is a tab running `menu --act
  ACTION`, so both views run the same code; apps open their own screen.
  `HomeView` lies over the terminal, which keeps its size so tabs start
  as usual. Apps' rows carry a `HomeLogo` (maintainer's decision,
  2026-11-22): each app's is its home screen shortcut's `appShortcut()`,
  drawn by the same `appIcon()`, and the Terminal apps folder's is the
  pixel terminal icon. Games get one too, by the same rules
  (`gameLogo()`): a picture beside the game with its name, else the
  letter tile. Exit isn't listed. Services is a list of its own:
  `HomeMenus` takes a snapshot (`ServiceManager.homeServices()`) and
  `HomeAction.Service` goes to `ServiceManager.act()`, both on the main
  thread; `ServiceHost.changed` refreshes it while it shows, and in
  touch view the strip's `● N` opens it. **Settings, System info and
  Help are touch lists** (`HomeSettings.kt`; maintainer's decision,
  2026-11-11): each setting by group, its choices, a theme list with
  swatches, Undo, text typed in a page of `HomeView`'s own. A change
  is a `HomeAction.Config`, the `mynx` request run in the app
  (`TerminalService.configRequest()`), so undo records it and it
  applies as `mynx set` does; Update all, GitHub setup, the key bar
  and menu editors and About's full list stay terminal programs in a
  tab. AI agents → Claude Code
  (`HomeAction.Chat`) opens the agent's home, the chat history
  (`app/.../ChatHistoryView.kt`, over the terminal too): New chat, then
  `ChatHub.rows()`; a chat's Back goes to it, its Back to Home's list
  (maintainer's decision, 2026-11-10). **A row's long press**, there
  and in the drawer (`ChatRowMenu.kt`; maintainer's request,
  2026-11-24): `pixelMenu` with Pin, Rename, Delete, which ask in a
  `pixelDialog` (`PixelStyle.kt`: Android's AlertDialog takes the
  system theme; Files' dialogs still do). Pins are app
  state (`ChatPins`, `filesDir/state/chat-pins`, a session id a line);
  pinned rows lead `chatHistory()`, both screens show them under
  Pinned above Recents (the drawer's are `ChatHome.drawerPinned`, all
  of them, beside its 5 recents), and they're listed
  past the 50-chat cap (`listClaudeSessions`' `keep`). Rename appends
  Claude's own `custom-title` line to the transcript
  (`appendClaudeTitle`; last one wins, as `/rename` writes it), and
  the list reads it from the transcript's last 64 KB
  (`claudeCustomTitle`), so names given in a terminal show too.
  Delete is `ChatHub.deleteJob()`: `rm -rf` in Debian of the
  transcript, its folder beside it and `~/.claude/file-history/ID`,
  refused while a chat holds a process on it. The service keeps which chat
  screen shows (`chatPlace`) for a recreated activity.
  **Chat home** (maintainer's decision, 2026-11-11; the spec is
  `docs/TOUCH-DESIGN.md`): touch view starts on `ChatHomeView`
  (`ChatPlace.HOME`), with the rest in its `DrawerView` behind ☰;
  core's `chatHome()` decides what both list. Everything backs out to
  it. **Pixel style** (`PixelStyle.kt`, `Pixel`): the terminal theme's
  colours (maintainer, 2026-11-11: themes apply to the app too), as
  core's `pixelPalette()` derives them; Neon, the default, keeps the
  logo's palette. A theme change rebuilds the activity (`recreate()`),
  Home back on its list (`TerminalService.homeLevels`), since views
  read `Pixel`'s colours as they're built. Pixelify Sans for labels, buttons and
  headings, JetBrains Mono for text; square boxes, 2 dp borders, the
  pink hard shadow only on a screen's main action; block icons.
- **Touch mode's Files** (`core/.../FileBrowser.kt`,
  `app/.../FilesView.kt`; the spec is `docs/TOUCH-DESIGN.md`, "Files";
  maintainer's decision, 2026-11-21): the drawer's Files
  (`HomeAction.Files`) opens it in place of nnn, which stays in
  Terminal apps. Listing reads through `WebApps` (read-only, links
  resolved the Debian way). **Every change runs in Debian**: core
  turns copy, move, rename, delete and new folder into a `FileJob`
  (`/bin/sh -c` with quoted paths), which `TerminalService.runInDebian()`
  runs in a proot of its own; never `java.io.File`, because proot's
  `--link2symlink` fakes hard links with `.l2s.*` files holding host
  paths, and `deleteRecursively()` follows links. Delete is permanent,
  after a confirmation. Chat here is `ChatHub.newChat(cwd)`; Terminal
  here a tab started in the folder. Pictures' thumbnails come from a
  `ChatThumbnails` of its own (40 dp), only for rows near sight.
  **The phone's storage** (`core/.../Storage.kt`; maintainer's
  roadmap item, 2026-11-21): without a permission Android shows the
  app (so proot) only folders and its own files under `/storage`.
  `TerminalService.storage` (a `StorageAccess`) asks: Android 11+
  "All files access" (`MANAGE_EXTERNAL_STORAGE`, a settings page:
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, else the list of
  all apps), before it the storage permissions' dialog
  (`requestLegacyExternalStorage` for Android 10's raw paths). `mynx
  storage [allow]` and Files' bar (`storageBarShown()`, in
  `/storage` while not granted) use it; Files lists again in
  `onResume`, back from the settings. Play restricts the permission:
  a Play build would need a variant without it.
- **Viewers** (`core/.../FileKinds.kt`, `Viewers.kt`, `TextDoc.kt`,
  `TextHistory.kt`, `Syntax.kt`, `Media.kt`; `app/.../TextViewActivity.kt`,
  `TextViewerView.kt`, `MarkdownViews.kt`, `MediaViewActivity.kt`,
  `MediaPlaybackService.kt`; the spec is `docs/TOUCH-DESIGN.md`,
  "Viewers"; maintainer's decisions, 2026-11-21): mynx's own viewers
  open supported files by default. `fileKindOf()` names the kind
  (picture, Markdown, code, text, audio, video; `fileKindOfFile()`
  adds a link's target and text sniffed from the first 8 KB), the
  `open-*` settings pick `mynx` or `android`, and `viewerFor()` the
  activity. **One opener**, `TerminalService.openDebianFileWith()`,
  serves Files, chat's chips and cards, `mynx open` (`open-file`) and
  the text viewer's links; Android's choice stays `openDebianFile()`.
  `mynx open FILE` asks in a terminal (`cli.py`, after
  `open-file-kind`), never with `--json` or without a tty, so agents
  don't wait. The viewers bind `TerminalService` (flags 0) and
  register as `FrontActivity`, or opening another app from them fails.
  **Saving runs in Debian**: the text goes to `/tmp/.mynx/saves/`, then
  `cat > file` in place (`saveJob()`), keeping links, owner and mode;
  `TextDoc` keeps line endings, a BOM and the final newline as found.
  Files over 1 MB (`TEXT_LIMIT`) open read-only; highlighting stops
  past 256 KB (many spans make a TextView slow). Syntax colours come
  from the terminal theme's palette with contrast ≥ 3 (`syntaxColor()`),
  in chat's code blocks too. Media plays from a `FileInputStream`'s fd
  (the media server can't open the app's files by path); audio lives
  in a foreground service (`mediaPlayback`, a framework `MediaSession`,
  notification ID 9000), video in the activity on a `SurfaceView`.
- **More viewers: a turn's changes, PDF, HTML** (`core/.../Diff.kt`,
  `DiffView.kt`, `ClaudeHistory.kt`'s `readFileEdits()`, `Pdf.kt`,
  `WebApps.kt`'s `respondFolder()`; `app/.../DiffViewActivity.kt`,
  `DiffView.kt`, `PdfViewActivity.kt`, `HtmlViewActivity.kt`; the spec
  is `docs/TOUCH-DESIGN.md`, "More viewers"). **Diffs come from the
  transcript**, not the stream: each Write and Edit result there has
  `originalFile` and a `structuredPatch`, read by tool call ID
  (`ChangedFileRow.toolIds`, from the hints) when a row needs it, off
  the main thread. A turn's diff of a file is before = its first
  call's `originalFile`, after = its last call's result (patch applied),
  diffed again in core (Myers, linear space), so several edits read as
  one. A file a command also wrote has no IDs: its row opens the file.
  **PDF:** Android's `PdfRenderer`, one page open at a time, so every
  render goes through one worker; pages near sight only. Selecting
  text needs Android 15 (`selectContent()`, `getTextContents()` on
  the same worker, `PdfText`); core's `PdfSelection.kt` maps touches
  to a page's PDF points and places the handles and the bar; one
  selection request in flight (`PdfLatest`). Find (Android 15 too)
  runs `searchText()` one page per worker task, so renders go between;
  core's `PdfSearch` collects the matches and picks the current one,
  `pdfRevealBy()` scrolls to it. **HTML files**
  are served from their folder on `page.files.mynx.invalid`
  (`HTML_FILE_HOST`; can't clash with an app), confined as static apps
  are, and dot files are never served (a page in `~` with web access
  could otherwise read `~/.ssh`); no `mynx.js` bridge. All HTML files
  share that origin (and its storage). A page in `~/apps/NAME/` opens
  as its app (`pageApp()`); an HTML file to edit opens its source.
- **Chat protocol** (`core/.../AgentSession.kt`, `ClaudeSession.kt`,
  `ClaudeLaunch.kt`, `Conversation.kt`; maintainer's decision,
  2026-11-10: Claude's stream-json first, behind a thin session with
  ACP's vocabulary): plain-Kotlin layer for the Chat screen. The KDoc
  of `AgentSession`, `AgentTransport`, `claudeLaunch` and
  `Conversation.reduce` is the contract (threads, process lifetime,
  stopping); tests replay real scrubbed lines from
  `core/src/test/resources/chat/`. The app side (passed on the phone, 2026-11-10):
  `app/.../AgentProcess.kt` is an `AgentTransport` over pipes (core's
  `ProcessAgentTransport`, tested with real shells); core's
  `ChatController` is one chat for life (a new one, or a past session
  resumed), each with its own process: checks `claude auth status`
  before the first prompt, starts the process on it (`--session-id`,
  then `--resume`). **Several at once** (`core/.../ChatHub.kt`, its KDoc
  is the contract; maintainer's decision, 2026-11-10): the hub, held by
  `TerminalService` (`chats`), owns them and survives the activity;
  `newChat()`/`open(id)` show one (the held controller for a live
  session, never a second one on it). A chat left while busy (a turn
  runs or a card waits) keeps working; one left idle, or going idle in
  the background, has its process stopped to spare memory (its next
  prompt resumes it); one not shown with no process left is dropped
  (the history shows its transcript), but only once its process has
  exited. `ChatShared` gives all of them the hand-off tabs' holds, the
  models listed and a passed sign-in check. The history's rows
  (`chatHistory()`): live chats (working, waiting for you, idle)
  merged with the transcripts, a live one in its transcript's place,
  new ones not in a transcript first. `app/.../ChatView.kt` draws the
  shown chat (one row per `ChatItem`,
  only changed rows are touched, `core/.../ChatScreen.kt`). **Layout**
  (maintainer's request, 2026-11-10: like the Claude app; in the pixel
  style since 2026-11-11, as the chat home, after
  `docs/images/touch/chat-pixel.png`): ← and the title over "claude
  code · mode", the context meter (`UsageRing`, a square bar over the
  percentage from `usageRing()`; tap: the Usage sheet from
  `usageRows()`), ⋮ (Open in a tab, in `pixelMenu()`: the touch
  screens have no Terminal buttons, the drawer has one; maintainer,
  2026-11-11); the box is the cyan
  one with the shadow: the input, +, the mic and one square button
  that is Stop (pink) while a turn runs and the box is empty, Send
  (cyan) otherwise, an empty square when it can't send (it follows
  each keystroke). Cards waiting for an answer (permission, question,
  plan) and artifact cards get the cyan border and the shadow; tools
  are quiet outlined rows. + is the Add context sheet (`ChatSheet.kt`, an overlay
  inside the view so screenshots draw it): Camera, Photos and Files
  (`ChatFiles.kt`, copied into the share folder as shares are, sent as
  paths, `promptWithAttachments()`), Permission (this chat's mode, and
  the `chat-mode` default) and Model. The mic is `SpeechRecognizer`
  (`ChatVoice.kt`; the `<queries>` entries make it visible on API
  30+), else the speech activity, else hidden. **Stopping**
  (`stopAgentProot`): off the main thread, SIGTERM to the agent itself
  (the tracee whose parent is proot, so it can end its children: KILL
  leaves them running), then after 5 s `stopProot`'s QUIT and its force.
  The agent and `claude auth status` run through a login shell
  (`bash --login -c 'exec "$@"'`): it gets what tabs and services get,
  and `exec` keeps it proot's direct child; what a profile prints is
  ignored (`agentBinary()` finds the path, the same lookup as Home's).
  A permission card shows `PermissionRequest.review`, built from the
  input sent back as `updatedInput`, never just the model's
  description. **Queued prompts:** one sent during a turn is
  `PromptQueued`, drawn dimmed after the rows (`Conversation.queued`),
  until Claude's echo of it (`--replay-user-messages`; after a tool's
  result, or as the next turn, queued ones joined by line breaks) makes
  it a `UserMessage` where Claude took it; Stop doesn't drop them (they
  run next, tested with 2.1.321), the process ending leaves them "Not
  sent", and transcripts keep mid-turn ones as `queued_command`
  attachments. A running chat keeps the app up like a service
  (`ChatHub.live`, any chat's); `wakelock = auto` also holds while a
  turn runs and no card waits (`ChatHub.working`, told to the host on
  change). **Notifications** (`core/.../ChatNotices.kt`): as the hooks
  (silenced by `MYNX_CHAT=1`) would, unless that chat is on screen: a
  turn of `agent-notify-after` or more ends, or a card waits; one
  notification per chat, in its own slot (`CHAT_NOTICE_ID` + 0 to 15,
  above web apps' 5000-5999), opening that chat (`EXTRA_CHAT_SESSION`),
  cleared when it's shown; it follows `agent-notify`
  and Claude's hooks being on (`claudeNotifyHooks`, as `mynx agent
  list` reads them). **Always allow** sends only the suggested
  `addRules` (`allow`), for the session, and its label names each rule;
  a suggested mode or directory would grant more than the button says,
  and no rule means no Always. **Markdown** (`core/.../Markdown.kt`,
  KDoc is the contract): agent messages only, parsed with
  `IncrementalMarkdown` (only the last two blocks are parsed again);
  `mdRows()` flattens them into `ChatView`'s views, one per row, reused
  by index; inline markers show as typed until closed, so a stray `**`
  stays right in the finished text; links are http(s) only. Redraws:
  at most about 10 a second (`chatRenderDelay`). **History**
  (`core/.../ClaudeHistory.kt`): `--resume` replays nothing, so a
  resumed chat reads its transcript (`readClaudeHistory`, off the main
  thread, line by line, last 300 rows) into the same `ChatItem`s.
  The history lists every folder's sessions (`listClaudeSessions` over
  `claudeProjectsDir`: the 50 newest, first 16 KB each for the title
  and the folder, the first `cwd` a line names); a row outside the
  hub's folder names it (`ChatHistoryRow.cwd`) and `open(id, cwd)`
  resumes it there. A session is left out if its `cwd` doesn't map to
  the folder it's kept in (`--resume` there wouldn't find it) or is
  gone (scripts' temp folders). Claude names that folder by turning
  every character but ASCII letters and digits into `-` (read in its
  binary). **Model and mode** (`core/.../ChatSettings.kt`): Model
  and Permission in + go to a running process (`set_model`,
  `set_permission_mode`) and are every launch's `--model` and
  `--permission-mode` for that chat (and its hand-off tab's) once
  Claude takes them (a refused one is dropped). What Claude reports
  (init, `initialize`, status lines) is only shown: the mode flag is
  only the user's pick among `CHAT_MODES`, dropped when Claude reports
  another or a plan is approved; `claudeLaunch` refuses any other mode,
  so `bypassPermissions` is never launched. The picks are kept per
  session in `ChatShared` (`filesDir/state/chat-picks`, a line each),
  so a chat left idle (its controller dropped) and opened again keeps
  them: `--resume` restores the model, not the mode. A new chat, or a
  past one with no picks kept, starts in the `chat-mode` setting
  (default `auto`, read through `ChatHost.chatDefaultMode()`; "Use as
  default" writes it). A new chat (not a past one: `--resume` restores
  its model) also starts with the `chat-model` setting (default
  `default`, no flag: Claude's own; `ChatHost.chatDefaultModel()`), which
  the Model sheet's "Default for new chats" writes (maintainer's
  request, 2026-11-11: Claude's own default isn't always wanted).
  Each of Claude's replies has Copy under it (its Markdown). **Usage** (`core/.../ChatUsage.kt`): each
  top-level `assistant` line's usage (input + cache read + cache
  written) is the chat's context, `result.modelUsage[model].contextWindow`
  its window (kept per model for resumed chats, whose context comes
  from the transcript's last answer); `rate_limit_event`'s
  `unifiedWindows` are the plan's, shared by all chats; `usageRows()`
  lays out the sheet. **Attachments** (`ChatAttachments.kt`): files
  the app copies into Debian go as `[Attached image|file: PATH]` lines
  after the text and a blank line (Claude reads them with Read), so
  echoes and transcripts keep them; `splitAttachments()` reads them
  back, and titles leave them out. An image's chip shows the
  picture (`ChatThumbnails`: decoded off the main thread at
  `thumbnailSampleSize()`, cropped square, turned by its EXIF
  orientation, cached; the icon until then or if it can't be read).
  Tapping an image's chip (in the box or a sent bubble) opens it full
  screen: `ImageViewActivity` (`app/.../ImageViewActivity.kt`), on black,
  decoded off the main thread at `viewerSampleSize()` (core: long side
  within twice the screen's, at most 4096), upright by EXIF; pinch,
  double-tap and drag go through core's `ImageZoom` (fitted, never
  blown up, up to 8×, kept on screen); a tap hides the bar (← and the
  name); a file it can't show says so on screen.
  **One session, one program:** nothing stops two programs
  driving one session (the transcript forks), so a session has one
  controller at a time (the hub's), which stops its process and waits
  for `ProcessEnded` before a new launch or "To tab" runs anything; the
  hand-off tab (`claudeHandOffCommand`:
  `mynx agent start claude --resume ID` with the picks, then a file saying it's
  done, then the shell) holds the session until that file appears (a
  FileObserver on the folder sees it) or the tab ends (closed before
  its shell started counts), and a prompt meanwhile asks to take it
  back (closing the tab) instead of resuming. A session resumed by
  hand in that tab afterwards isn't tracked. **Artifact cards**
  (`core/.../ChatArtifacts.kt`, its top comment is the contract): a
  turn's completed Write/Edit/NotebookEdit paths, and Bash's paths in
  the app folders or ending `.apk` (only if changed since the turn
  started), and written `.md` files anywhere (Copy: the file's text,
  up to 256 KB; Share), and pictures (`chatImageFile()`) outside the app
  folders, written or named by Bash and changed (View: full screen;
  Share; the card shows the picture, `ChatThumbnails.preview()`, keyed
  by the file's time, `ChatArtifact.version`, so one drawn again changes
  its card), are checked on disk off the main thread with Home's rules
  (`WebApps`, `terminalAppLabel`); one `ChatItem.Artifact` per app per
  turn, changed in place; history gets them too (written files only).
  Run and Add to home screen are `menu --act` tabs; Install is
  `mynx install-apk`'s request.
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

## Termux library notes (v0.118.3, checked in their source)
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

## proot notes
- **Stop a proot with SIGQUIT** (`stopProot()`): it kills everything
  it runs, then exits. It ignores TERM and HUP, and SIGKILL (Termux's
  `finishIfRunning()`) leaves its programs running untraced: proot's
  seccomp filter stays on, every system call it would handle fails
  with ENOSYS, and they spin at full CPU. A proot can also hang with
  QUIT blocked while a program it traces sits stopped (seen once
  after a sound restart), so the force after the grace is
  `killProot()`: KILL what it traces (`TracerPid`), then proot.
  A session that never started has pid 0 and an ended one -1: never
  look up tracees for those (every untraced process has `TracerPid 0`,
  the app included). **Android's `Process.destroyForcibly()` only sends TERM** (its
  `UNIXProcess` doesn't override it); its pid comes from `toString()`
  (`processPid()`).
- **The host can't see a guest process's working directory:** proot
  tracks it itself, so `/proc/<pid>/cwd` stays at the folder proot was
  started in. Shells report their folder through `PROMPT_COMMAND` instead
  (`prootLaunch(cwdFile = …)`).
- App data paths come in two spellings (`/data/user/0/<app>` from
  `filesDir`, `/data/data/<app>` from the kernel). Don't compare paths by
  prefix across the two. proot gives a bind's host path its real
  spelling, so the paths an extension sees are `/data/data/…` while
  the variables the app passes say `/data/user/0/…`: mynx_usb
  resolves its socket's folder (`realpath`) before comparing (its
  first phone run missed every open).
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
