# Work log

Where the project stands, what's next, and what recent sessions did.
Newest entries first. Rules for keeping it up to date (and short): see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps). Older entries
are in git history: the full log up to entry 92 is
`git show be3d232:docs/WORKLOG.md` (up to 84: `72b8512`).

---

## Current status

- **Done and confirmed on the phone:** roadmap steps 1-7, 9 and 10
  (`docs/ROADMAP.md`): Debian through proot, tabs, the default setup,
  the file manager and key bars, customization (`mynx`, the editors),
  agent support, Android integration (clipboard, sharing, location,
  sensors, camera, flashlight, sound in and out, rotation lock), and
  polish (welcome page, wakelock, Settings shortcut, `mynx about`,
  themed icon, the title art and "m" icon). Also: the rename to mynx,
  bug reports (`mynx report`), and **mynx dev**, debug builds as a
  separate app (entry 82).
- **Released:** v0.1.0 and v0.1.1 (2026-10-10), v0.1.2 (2026-10-12),
  v0.2.0 (2026-10-13, background services), signed with the
  release key (certificate SHA-256 `24b7fefa…2b40`). The maintainer's
  daily mynx is the release, and it updated 0.1.0 → 0.1.1 → 0.1.2
  through `mynx update` (entry 81's updates, confirmed); 0.1.2 came
  from the daily check's notification and menu item (entry 106).
- **Battery** (`mynx battery`, the `/sys/class/power_supply`
  stand-in): confirmed and merged (entries 99-100).
- **Vibration patterns** (`mynx vibrate effect/wave/chain/list/stop`):
  confirmed on the phone (entry 102, PR #24).
- **Volume keys** (a key bar's `VolumeUp`/`VolumeDown` lines):
  confirmed on the phone (entry 103, PR #25).
- **Release notes** (`mynx notes`, Help → Release notes, the `pager`
  bar) and What's new after an update: confirmed on the phone with
  v0.1.2 (PR #26, entry 106).
- **Guided GitHub setup** (`mynx github`) and **bug reports**
  (`mynx report`): every path confirmed on the phone (2026-10-12).
- **Background services:** all 21 phone tests passed (the last,
  autostart on and off, on 2026-10-13); merged (PR #30, #32) and
  released in v0.2.0. The plan left "Next" (git history has it:
  `git show 8ea1266:docs/WORKLOG.md`).
- **USB serial:** the bridge (PR #33) and the preload library for
  `/dev/serial` (PR #34) are merged; every phone test passed with an
  ESP32-C3, an ESP32-CAM-MB, an Arduino Nano and a Pro Micro (two at
  once on a hub). Not released yet. The plans and tests:
  `git show d748955:docs/WORKLOG.md` (bridge),
  `git show 46bd16c:docs/WORKLOG.md` (preload, entries 115-122).
- **USB serial part 3** (serial monitor, board tools, port names):
  merged (PR #36). Not released yet.
- **Go programs** (proot's `mynx_serial` extension: `arduino-cli
  board list` and `monitor`): every phone test passed (entry 129);
  merged (PR #37). Not released yet.
- **PlatformIO's ESP32 toolchains** (proot's `mynx_links`
  extension): a C3 project builds on the phone (entry 132); merged
  (PR #39). Not released yet.
- **Small serial fixes** (PR #42, entry 136): the bridge's own read
  queue (`UsbReads`), the busy hint and the no-port retry; the 4 MB
  `read_flash` on the C3, the Nano, a Pro Micro upload and the busy
  hint passed on the phone (build 214); FTDI not tried. Not released
  yet.
- **Android's process limit:** confirmed on the Nord 2 (entry
  138): the `device_config` commands stop the kills; the guide now
  gives them for Android 12-13.
- **Services and `~/.local/bin`:** fixed and confirmed on the phone
  (PR #46, entry 139). Not released yet.
- **Step 8 (*Profiles*) is parked** by the maintainer (plan below).
- **Workflow:** trunk-based, `main` protected (PRs only, entry 83).
  CI builds PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`.


## Next

### 0. Development boards (suggested order; the maintainer picks)

1. UF2 boards and raw USB (`libusb`): the roadmap's last items, a
   milestone of their own. Plan it with the maintainer first.

### Then, release v0.3.0 (the maintainer's)

Moved after the development boards by the maintainer (2026-10-25),
so the release takes them too. USB serial parts 1-3, Go programs,
PlatformIO's ESP32 toolchains, the small serial fixes (PR #33, #34,
#36, #37, #39, #42), the process-limit guide (#45) and services'
`~/.local/bin` (#46) are tested on the phone, unreleased. Only the
maintainer tags (`docs/RELEASING.md`). Go programs' test plan and how the tests ran:
`git show 2eaa373:docs/WORKLOG.md`.

### 1. Smaller things

- **Maybe:** `CONTRIBUTING.md`, `SECURITY.md`.
- **Maybe, for Android apps built in Debian** (guide: "Making an
  app", entry 134): a `mynx` command that opens the installer for an
  APK in release builds too (`install-apk` answers only in debug),
  so agents needn't serve it to the browser; and one that fetches
  `r8.jar` and `android.jar`.
- **Before Play (not urgent):** `release.yml` has its own
  `GITHUB_RUN_NUMBER`, so release version codes are far below debug
  ones; Play needs codes that only go up: pick a scheme (from the tag,
  or an offset) before the first upload. Play also restricts
  `REQUEST_INSTALL_PACKAGES`: a Play build needs a variant without it.

### Parked: roadmap step 8, Profiles

**Parked by the maintainer 2026-10-04** ("not such an important feature to
work on yet"): step 9 comes first. The plan below stands for when it's
picked up again.

README roadmap: "multiple profiles, switching, export/import"; README
section "Profiles: share your setup". Planned with the maintainer
2026-10-04. Build test-first, with a maintainer test after each part, as
in step 7.

**Maintainer's decisions (2026-10-04):**
- **All profiles share the one Debian** in step 8. A separate Debian
  per profile and full backup/restore of an environment come in a
  later step.
- **A profile per tab**, plus an **app-wide default profile**: new
  tabs and a fresh start use it; **⌄** next to **+** opens a tab in
  any profile.
- **Export is one plain-text file**: manifest, package list, setup
  script and config files inline, readable before import. Fonts are
  referenced (apt package or URL), never embedded.
- **Dotfiles: a shell snippet per profile** (aliases, prompt, env),
  sourced by `~/.bashrc` in that profile's tabs. The user's own
  dotfiles stay theirs.

**Design (agent's proposal, change it if the maintainer objects):**
- A profile is `~/.config/mynx/profiles/NAME/`, with the
  same layout as the config folder (`settings.conf`,
  `colors.properties`, `keybars/`, `menu.conf`) plus `profile.conf`
  (label, tab colour, icon), `packages` (apt names), `setup.sh` and
  `bashrc`. A profile only holds what it changes: its files lie over
  the top-level config (per file; `settings.conf` per key), so the
  existing config is the `default` profile and current installs keep
  working unchanged.
- `default-profile = NAME` in the top-level `settings.conf`.
- Each saved tab records its profile (`profile = NAME` in
  `filesDir/state/tabs`). The tab's shell gets `MYNX_PROFILE`;
  `mynx` commands act on that profile unless given `--profile NAME`
  (`--profile default` for the top level).
- Undo and `mynx check` cover the profile folders too.

**Parts:**
1. **8.1 Model and commands:** layering in `core` (tested);
   `mynx profile list/show/new/copy/rename/delete/default`, `--json`;
   `--profile` on the config commands; `mynx check` per profile.
2. **8.2 Profiles in the app:** tabs remember their profile and use
   its theme, font and key bars; the strip shows its colour; **⌄**
   opens a tab in a chosen profile; `MYNX_PROFILE` in the shell.
3. **8.3 Recipe:** `packages`, `setup.sh`, `bashrc` snippet (sourced
   via `/opt/mynx/shell.bash`); `mynx profile apply NAME`
   installs the packages and runs the script after showing them and
   asking.
4. **8.4 Export/import:** `mynx profile export NAME [FILE]` writes
   the text file; `mynx profile import FILE` shows everything it
   will write, install and run, asks, then adds the profile (applying
   it is a separate yes). Name clashes ask for a new name.
5. **8.5 Editors and docs:** a Profiles editor in `mynx edit`, a
   menu item, the agent guide (`tools/AGENTS.md`) and README.

### Regression checks (steps 4-6)
- **Step 6:** `mynx theme set` for a dark and a light theme (strip
  and key bar readable); the theme editor's live preview and `r` reset;
  font size from the editor and from pinch (kept after a restart);
  cursor style and blink; editing and resetting a key bar; editing and
  resetting the menu (`run` items, and a failing command explains
  itself); `mynx check` lists problems without dialogs.
- **Steps 4-5:** `files` (nnn bar, two rows, pages to swipe), game bars
  for `rogue`/`drive`/`flap` and the generic `game` bar, hold-to-repeat
  on arrows, each tab keeping its own bar.

### Hardware keyboard checks
Tried 2026-10-17 with the maintainer's first external keyboard (mynx
dev 175). **Pass:** Ctrl+Shift+T and Ctrl+Shift+W; holding Ctrl+Shift+T
opens one tab; Ctrl+Tab and Ctrl+Shift+Tab, wrapping round; Ctrl+Alt+1…9
(nothing past the last tab), and Android swallows none of them;
Ctrl+Shift+R, with Enter saving and Esc cancelling; Ctrl+W, Ctrl+T and
Ctrl+Alt+0 still reach bash.

**Left, with another keyboard:** Ctrl+Shift+PgUp / PgDn move the
selected tab left / right. Not testable: this keyboard's Fn+Up/Down
sends nothing at all (`cat -v` printed nothing, even without
Ctrl+Shift).

### Small open items
- The wakelock is a setting (10.2); a notification action to toggle it
  (like Termux) could come later if people want it.
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).
- `test_undo_list` (tests/mynx) fails in the phone's time zone (it
  expects UTC times; CI is UTC): `TZ=UTC` passes it.

---

## Log

### 2026-10-25 (139): services get `~/.local/bin` (branch `service-path`)

- Found in entry 138: a service runs `bash --login -c`, whose
  `~/.bashrc` returns before it adds `~/.local/bin`, so `pio`,
  `arduino-cli` and pipx tools weren't found.
- Fix: `/etc/profile.d/mynx.sh` (`writeToolsProfile()`, rewritten at
  every app start, so existing Debians get it too) puts
  `~/.local/bin` first when the folder exists, once. Root's default
  `.bashrc` no longer adds it a second time (old ones still do:
  harmless). Guide's services table says so. Tests:
  `ToolsInstallerTest`, `bashrc.bats`, `home-docs.bats`.
- Confirmed on the phone (mynx dev, PR #46's build): a service's
  `command -v pio` gives `/root/.local/bin/pio`, and its PATH has
  `~/.local/bin` once, first.

### 2026-10-25 (138): Android's process limit on the Nord 2 (branch `process-limit-guide`)

In mynx dev build 214, adb over Wireless debugging from the app
(`adb pair localhost:…`, run by the maintainer with the dialog open;
from a screenshot the code had already expired).
- Before: `settings_enable_monitor_phantom_procs` and
  `max_phantom_processes` both unset (Android's defaults).
- The guide's Android 12L/13 command, `settings put global
  settings_enable_monitor_phantom_procs false`, failed: "Permission
  denial: writing to settings requires
  android.permission.WRITE_SECURE_SETTINGS" (OnePlus wants Developer
  options → Disable permission monitoring for that). The Android 12
  pair worked: `device_config set_sync_disabled_for_tests
  persistent`, `device_config put activity_manager
  max_phantom_processes 2147483647`.
- Then a clean `pio run` of `~/sketches/pio-c3` as a service (118 s),
  and two at once, a service's and this tab's, where both proots were
  killed before (143 s and 113 s): all passed, nothing killed. Not
  checked: that the setting survives a reboot.
- Guide: Android 12, 12L and 13 use `device_config`; the `settings`
  way is mentioned with the OnePlus refusal. Test in
  `home-docs.bats`. AGENTS.md's lesson updated.
- Found: services don't get `~/.local/bin` (fixed in entry 139).

### 2026-10-25 (137): the release moves after the development boards

- At the maintainer's request, "Next" now has the development boards
  (the process limit, UF2 and raw USB) before releasing v0.3.0. Docs
  only.

### 2026-10-25 (136): Small serial fixes (branch `serial-small`)

On mynx dev build 209, a Nano, a C3 and an Arduino Micro on a hub.
- **A busy port's silence, fixed:** a program opening `/dev/serial/X`
  while a URL client had the port got nothing, and the helper logged
  "opened" / "the app closed the port" every second. The app's server
  greets a client it takes and closes on one it refuses, so
  `serial-port` tells them apart (`refused()`), logs "busy" once and
  leaves `.X.waiting` in the ports folder; `mynx usb` shows "waiting:
  a program has the URL" (`waiting` in `--json`, `SerialPortInfo`).
  Checked by hand on the Nano with the new helper.
- **Problem 6 ("no port yet"), likely cause fixed:** `usb-allow`
  answered at once for any allowed device (with no DEVICE it said
  nothing was waiting), so `mynx usb allow` never made the bridge look
  again; and the 40 minutes ended when the app came on screen (onStart
  refreshes), so Android probably allowed it with no broadcast reaching
  the bridge. Now an allowed device with no port counts as waiting and
  is opened again; the bridge looks again every second for 30 s after
  an attach while a device isn't allowed; a driver with no port is a
  shown error. Not reproduced, so not confirmed.
- **The 4 MB `read_flash` stalls (open):** on the C3 over the URL,
  with the phone idle, it ran to ~600 KB at ~8 KB/s, then nothing for
  6 min, no error (esptool waits). After killing it, the URL refused
  clients for some seconds (the old client still "busy"). A run under
  load hung too. The library's queued read re-queues each request
  before returning and turns an empty packet into a 200 ms GET_STATUS
  that throws when unanswered; nothing there explains a stall alone.
  So a temporary trace (`PortTrace`): each port's
  `/tmp/.mynx/serial/NAME.trace` gets a line a second while anything
  moves (bytes and reads in, empty reads, biggest read, bytes and
  writes out, read errors with their text, time since the last read,
  whether a client is on, a USB write stuck), plus configure and purge.
- **Build 212, the trace:** every packet from the C3 is 1 byte
  (7-11 K reads a second, "biggest 1 B"): esptool 4.7's stub flushes
  USB-Serial-JTAG after each character (`stub_tx_one_char()`). So 256
  queued reads held 256 bytes, ~30 ms. It stalled at 72% (3.04 MB)
  right after a second with 465 empty reads (each costs the library a
  GET_STATUS of up to 200 ms on the read thread) and a 219 ms gap;
  then no line at all: nothing in or out, no write stuck, no read
  error. Lost bytes, then esptool and the stub each wait for the
  other. Now 4096 reads of one packet each (~0.5 s of 1-byte packets,
  the same 256 KB). If that's not enough: skip the library's
  GET_STATUS on empty reads. Also confirmed on 212: `mynx usb allow`
  reopened the Nano after "couldn't open it … Failed send cmd [init
  #1]" (it used to answer at once).
- Boards dropped off the hub by themselves now and then (the Micro and
  Nano 4 min after the stall): the hub's power, as in entry 126.
- **Build 213 (4096 queued reads), worse:** `read_flash` never
  connected ("Wrong boot mode"), and `chip_id` failed right after the
  stub started ("Packet content transfer stopped (received 3
  bytes)"). The trace's errors, all `Queueing USB request failed`,
  with bursts of at most 255 reads: Android stops queueing at ~256,
  and the library's `read()` queues the completed read again *after*
  copying its packet and throws when that fails, so the packet is
  lost. Now `UsbReads` in `UsbBridge` instead of the library's read
  queue: up to 1024 reads, as many as Android takes; a refused one
  waits to be queued again (counted in the trace); empty packets
  pass with no GET_STATUS; FTDI's 2 status bytes stripped there.
- **Build 214 (`UsbReads`), passed:** Android took all 1024 reads
  at open but refused 17 re-queues 10 s later, all at once while
  idle (the other boards being plugged in); none during the test.
  The 4 MB `read_flash` on the C3 over the URL: 4,194,304 bytes in
  583 s (57.6 kbit/s, esptool's MD5 check passed), 0 read errors.
  The Nano (CH340) read its sketch's output cleanly at 9600. The busy
  hint: with the URL held, `mynx usb` showed `/dev/serial/ttyUSB0
  (waiting: a program has the URL)` and the helper logged "busy".
  "No port yet" didn't show again (not confirmed). Trace removed.
- **Pro Micro on build 214, after the merge:** `touch-flash.sh` (the
  1200 baud touch, then avrdude to the bootloader): the Caterina
  bootloader (2341:0036) had a port 1.5 s after the touch (still
  allowed from "always"), avrdude wrote and verified 4238 bytes, and
  the board came back running Tick (`tick 23`, `tick 24`, … at 9600).
  The bootloader's empty packets no longer cost a GET_STATUS. No FTDI
  board has been tried with `UsbReads` (its 2 status bytes are now
  stripped in `UsbReads`).
- Commits: busy hint, no-port fix, trace, 4096 reads, own reads,
  trace removed.

### 2026-10-21 (135): agent widget on the roadmap (branch `roadmap-agent-widget`)

- At the maintainer's request: "Agent widget" in `docs/ROADMAP.md`, a
  home screen widget like Claude's (shortcuts, not a text field) whose
  text box opens a tab with the default agent and whose mic also
  listens with Android's speech recognizer. Planned only; no code.

### 2026-10-21 (134): the guide on making apps (branch `guide-making-apps`)

- At the maintainer's request, after an agent built an Android widget
  in the app's Debian (Debian's `aapt`, `javac`, Google's `d8` and
  `android.jar`, `apksigner`) and found the guide didn't cover it.
  New section "Making an app": ask whether the user wants a terminal
  app (`~/apps`) or an Android app; the toolchain, with Google's
  download URLs (checked: the same files, by size and sha256, as the
  ones that built the widget); signing, installing through the
  browser, no logcat, permissions, plain `http`, widgets' limits; an
  Android app talking to a service in Debian. The Apps bullet points
  to it; a test in `home-docs.bats`.

### 2026-10-20 (133): PR #39 merged

- At the maintainer's request, with the adb check of entry 132 still
  open (now "Next" item 1). The docs went into the PR first.

### 2026-10-20 (132): PR #39 on the phone; Android kills big builds

- **Build 206 (6dffadb), in mynx dev on the maintainer's OnePlus Nord
  2 (Android 13):** `scandir` says `f`/`g` aren't symlinks and
  `copytree` works; `ls`, `find`, `git status` as before; `ls -R /usr`
  32,073 entries in 3.7 s. A service walking `~/.platformio` five
  times (16,688 files) survived; none of its 78 listed symlinks is a
  faked hard link. **`pio run` for `esp32-c3-devkitm-1`:** every
  toolchain installed and the build succeeded (61 files, 117 s,
  `firmware.bin`).
- **First fix (6dffadb):** CI's NDK build failed on `IFTODT` (glibc
  only); glibc's definition where libc has none, and a test building
  `links.c` without it.
- **Android kills big builds:** three `pio run`s ended with proot
  killed (tracees left running, ENOSYS: "Function not implemented"),
  once two proots at once (a tab's and a service's); the third time
  only the tab's (Claude Code's) and the build finished. The app
  stayed up. Taken as Android 12+'s phantom process killer (the
  maintainer saw it on another phone and fixed it with a setting);
  the Nord 2's Android 13 has no switch in Developer options, so the
  user guide ("How it runs") gives each version's way, adb included
  (wireless debugging from the app itself). **Not yet confirmed:**
  that those adb commands stop it on this phone.
- **Seen, not fixed (not from this PR, as far as the code says):**
  `cp -a` and `tar` fail on a `.l2s.*` intermediate link ("Too many
  levels of symbolic links": link2symlink's `lstat` says regular
  file); PlatformIO's cleanup of its temporary folders says
  "Directory not empty" (the guide calls it harmless: installs finish).
- Guide: PlatformIO's ESP32 recipe, Android's process limit; tests in
  `home-docs.bats`. Roadmap: the PlatformIO item.

### 2026-10-20 (131): faked hard links as regular files in listings

- **Problem:** link2symlink's faked hard link is a symlink on the host;
  every path call dereferences it (`lstat`: regular, `readlink`:
  EINVAL), but `getdents64` comes from the kernel: `DT_LNK`. So
  `os.scandir` says symlink, `shutil.copytree(symlinks=True)` calls
  `readlink` and gets EINVAL, and PlatformIO's ESP32 toolchains fail
  to install. Reproduced in this Debian with `ln a/f a/g` and
  `copytree`.
- **Fix:** a new proot extension, `proot/mynx_links/` (AGENTS.md
  "proot's mynx_links extension"): at `getdents64`'s exit, each
  `DT_LNK` record whose symlink leads to `.l2s.*` and on to a file
  gets that file's type. Same decision as link2symlink's
  `resolve_faked_hard_link()`, so broken ones and the `.l2s.*` links
  themselves stay `DT_LNK`. Turned on by `--link2symlink` (the patch
  adds it to `handle_option_link2symlink()`). The About credit says
  so.
- **Checked here:** `links.c` against real `getdents64` buffers
  (`tests/mynx/test_proot_links.py`, 7 tests, seen failing with the
  fix taken out); the patched proot builds natively with gcc, and
  both new files with `-Wall -Wextra -Werror`. Not run: a nested
  proot can't run here.
- **Phone tests (after CI builds it), in mynx dev:**
  1. `mkdir -p /tmp/t/a && cd /tmp/t && echo hi >a/f && ln a/f a/g`,
     then `python3 -c "import os; print([(e.name, e.is_symlink())
     for e in os.scandir('a')])"`: `f` and `g` False (were True).
  2. `python3 -c "import shutil; shutil.copytree('a', 'b',
     symlinks=True)"` works; `cat b/g` says hi.
  3. `ls -la a`, `find a -type l` (only `.l2s.f0001`), `git status`
     in a repo, `cp -a`, `tar`: as before.
  4. `pipx install platformio`, a project with `board = esp32-c3-devkitm-1`,
     `pio run`: the toolchains install and it builds (the real goal;
     another obstacle may show up after this one).
  5. Nothing feels slower (`ls -R /usr | wc -l`, a big `git status`):
     every `getdents64` now stops in proot.

### 2026-10-20 (130): PR #37 merged; docs caught up

- PR #37 merged at the maintainer's request (9eafb45), before the work
  log and roadmap were updated, so this PR does that. **Maintainer's
  rule:** never merge a branch without first updating the docs it
  makes stale (work log, roadmap, README, AGENTS.md), in that PR.

### 2026-10-20 (129): Go programs: every phone test passed (build 201)

- Build 200's log showed why replugs sent nothing: `LocalSocket`
  creates its socket in `connect()`, and `soTimeout` was set before
  it ("socket not created"). Fixed in 2eaa373 (AGENTS.md's lessons).
- Build 201: `board list --watch` printed `remove /dev/ttyUSB0` and
  then `add /dev/ttyUSB0` as the Nano was unplugged and plugged back;
  `uevents.log` says "sent" for both. The log stays, beside the
  helpers' logs.

### 2026-10-20 (128): Go programs on the phone (build 199)

- **Pass:** a uevent socket is made and bound; without the preload
  proot answers TIOCM*, TIOCEXCL and tcdrain with the preload's
  values and the bytes reach the port (a fake port in a service);
  with both, PROGRAM passes whole. With the Nano and the C3:
  `arduino-cli board list` lists both (VID:PID right), `arduino-cli
  monitor` shows Tick (opening resets the Nano: DTR through proot)
  and the C3's REPL (`print(6*7)` → 42); `esptool chip_id` with and
  without the preload; pyserial resets the Nano by DTR with no
  preload.
- **Fails:** `board list --watch` gets no event on a replug. A
  message sent by hand (`connect` + `send` from Debian; `sendto` with
  a path isn't translated by proot: ENOENT) reached it, and dead
  sockets weren't deleted, so the app's sender got nowhere and said
  nothing. It now logs to `/tmp/.mynx/serial/uevents.log`.
- `serial-names` was off in mynx dev's Debian (from before it became
  the default); turned on for these tests.

### 2026-10-20 (127): Go programs, proot's mynx_serial extension (branch `go-serial`)

- Why: Go programs are static, so the preload never loads; Android
  refuses arduino-cli's discovery `socket(AF_NETLINK, SOCK_RAW,
  NETLINK_KOBJECT_UEVENT)` itself (EACCES, checked here), and
  go.bug.st/serial's open fails on `TIOCEXCL`.
- Done, test-first where it can be: `proot/mynx_serial/ports.c`
  (refused requests, the port by its pty, asking the helper, the
  socket's path; `test_proot_serial.py`, against the real
  `serial-port`), `mynx_serial.c` (the proot glue, built here with
  gcc against Termux's proot: it compiles, but can't run), the patch
  and `build-proot.sh`, the cache key (`proot/**`), core's
  `SerialUevents` and `ueventSockets()`, `MYNX_SERIAL_DIR` in
  `prootLaunch`'s env, the app's `UeventSender`, the guides and `mynx
  about` (our extension is compiled into GPL proot: its source is
  linked there).
- Decisions: the uevent stand-in is a Unix datagram socket per
  program, sent to by the app (so `read()` gets one message at a
  time, as from the kernel); only tty `add`/`remove`, no USB device
  events. `tcdrain` doesn't wait under proot. The preload stays: it
  covers `flock`, `tcflow` and a waiting `tcdrain`.
- Tried and dropped: running a natively built proot inside the app's
  proot, to test the extension here: its loader crashes (signal 11)
  right after the first execve under the outer proot's ptrace.
- Local test runs: `test_undo_list` fails on `main` too (it compares
  clock times, off by the phone's time zone). `SerialServerTest`'s
  "a client leaving drops RTS and then DTR" failed under load: a real
  race (the client was assigned after its greeting went out), fixed
  in its own commit, bb9dfb4. A few `test_mynx` ones failed once each
  under load and pass when run again.
- Installed in the maintainer's Debian for this: `gcc`, `make`,
  `gawk`, `libtalloc-dev`.

### 2026-10-18 (126): USB serial part 3, an agent's phone tests

On mynx dev build 186, a C3 and a Nano plugged in. The agent drove
the monitor on a pty (a script sending keys) and ran part C as a
throwaway service (services get the own `/dev` as new tabs do, and
the agent's tab kept Android's).
- **Passed:** monitor tests 1-4, 7, 9 (the reset is refused with
  "can't reset: Permission denied (serial-preload off?)"), Ctrl+T `h`
  `e` `n` `D` `R`, an unknown key, Ctrl+]; arduino-cli (fix-links
  "fixed 24 links", Tick compiled and uploaded); mpremote by path and
  URL; in the own `/dev`: null, urandom, tty, ptys, `<( )`, apt, git,
  sound.
- **Fixed, test-first:** (1) the port picker crashed ("prompt string
  cannot contain null characters"): the C3's USB strings end in a NUL
  (`Espressif\0`), which Android passes on; `usbString()` drops them
  (also the double space in `mynx usb`, and sysfs's `product`). (2)
  No `/dev/ttyACM0` or `/sys/class/tty` entries: `writeSerialNames()`
  wrote into `filesDir/dev` before any tab had made it, failing
  quietly (`runCatching`), and nothing changed afterwards; it makes
  the folder now. (3) proot warned "can't sanitize binding /dev/full"
  (and `fuse`), leaving empty files: Android allows `access()` on them
  but not `stat()`; the probe uses `Os.stat`, and `prepareOwnDev()`
  removes placeholders for devices the probe no longer finds.
- Turning DTR off with RTS on (Ctrl+T `D`) puts the C3 into download
  mode: its USB-Serial-JTAG's reset logic, not a bug.
- **Later, on build 187 (run 39111972962):** 6 passes (a numbered
  list of all three); part C in a throwaway service: 13 (no proot
  warnings, no `full`/`fuse`), 14's links, 15 (`list_ports`: all three
  with VID:PID, serial, product), 16 (esptool tries the Nano first,
  ~10 s, then finds the C3) and 17 pass. **18 can't pass:** arduino-cli's
  serial-discovery (Go, static) needs a `NETLINK_KOBJECT_UEVENT`
  socket even for a plain START, and Android refuses apps one
  (checked: EACCES from `socket()`); the guide no longer promises
  `board list`. The stty touch now reaches the Pro Micro; its
  bootloader failed at "dropping DTR": Caterina refuses CDC's
  line-state request, which Linux's cdc-acm shrugs off. `UsbLine`
  now does too (e34c12f, not yet on the phone).
- **Build 189 (2026-10-19):** the bootloader now gets a port (same
  name, `ttyACM0`, as the sketch it replaces), and answers avr109 by
  hand (`S` → `CATERIN`, `V` → `10`), but avrdude got "programmer is
  not responding": after avrdude's first byte, ESC, which Caterina
  ignores, nothing came back while writes still arrived (`E` made it
  leave in 0.6 s). usb-serial-for-android checks the device with
  GET_STATUS (200 ms) after an empty packet and throws when it
  doesn't answer; the read loop took that as an unplug and stopped.
  It now keeps reading while the port is open (refresh() still
  closes an unplugged one). Not yet on the phone. `~/sketches`:
  `touch-flash.sh PORT` (touch, wait for 2341:0036, avrdude),
  `probe-boot.py`, `esc-exit.py`.
- **Build 191:** test 11 passes: the stty touch, the bootloader's
  port, avrdude flashed and verified Tick (4238 bytes), and it prints
  at 9600. But the sketch's port after the flash (the board restarting
  by itself) sat at "the app is opening it" again, `mynx usb allow`
  didn't help, a replug did. The code shows no way to be allowed, in
  `open` and portless without an error, so a temporary trace
  (`bridge.log` in `/tmp/.mynx/serial`, `UsbBridge.trace()`) logs each
  broadcast, refresh, open and close. Next: flash again
  (`~/sketches/touch-flash.sh`), read the trace, fix, remove it. The
  phone in these tests is a OnePlus Nord 2 (DN2103), Android 13.
- **The maintainer's tests, build 192 (2026-10-19), all pass:** the
  `serial` bar (1), its buttons (8), the C3 unplugged and back while
  monitoring (5), `serial-names on` in a new tab (13: no proot
  warnings, no `full`/`fuse`, htop, mc, claude), a name gone and back
  on unplug in an open tab (14), one board unplugged from the hub
  removes only its name (19), `serial-names off` as before (20).
  Aside: mc needs F10 to quit, which the phone's keyboard lacks: Esc 0
  works (no mc key bar yet).
- **Build 192 (with the trace):** five flashes in a row, the sketch's
  port came back each time (bootloader detached, sketch attached
  0.3 s later, served within 40 ms): problem 6 didn't show. The trace
  caught the Nano dropping off and coming back by itself 9 s after a
  flash (the hub's power, as the Pro Micro restarts?), handled fine;
  such a drop during an attach may be problem 6. The trace was removed
  (maintainer's decision); `git show a35033d` has it, to add again if
  the stuck port comes back.
- **Pro Micro (test 11), three problems.** (4) The 1200 baud touch
  never reached it: `stty -F … 1200` opens, sets and closes between
  two of `serial-port`'s looks (10 a second), so it saw nothing; and
  the monitor's Ctrl+T `b` (baud, then DTR off) reached the board as
  DTR off, then 1200 (lines go at once, settings at the next poll).
  Fixed in the helper, test-first: a line change sends changed
  settings first, and settings changed while nobody seemed to have it
  open are replayed as an open and a close (held `QUICK_OPEN`, 0.2 s:
  with none the board didn't react; 0.1 s worked, tried with the
  fixed helper run by hand). (5) Its bootloader (2341:0036
  "Arduino Leonardo", Caterina) is never served: "couldn't open it:
  controlTransfer failed", every time, so avrdude has no port. Not
  understood; the error now names the step (`openDevice()`), so the
  next build says which. (6) Once plugged in, it sat at "no port yet;
  the app is opening it" for about 40 minutes (also seen once at build
  175), `mynx usb allow` didn't help; then it had `ttyACM1`. Not
  understood either.

### 2026-10-18 (125): Serial monitor, board tools, one PR (branch `usb-serial-3`)

Overnight, at the maintainer's request (good night, test in the
morning); two subagents, merged by the main agent. Not on the phone.
- **Serial monitor:** `tools/apps/serial` (Apps → Serial monitor),
  `mynx usb monitor [PORT]`, the `serial` key bar. Ctrl+T commands as
  in miniterm; reset (RTS off, DTR off, an RTS pulse, a DTR pulse),
  bootloader (esptool's classic or USB-JTAG sequence by vendor, else
  the 1200-baud touch), reopens a port that comes back. No `--tab`:
  no request opens a tab with a command yet.
- **`mynx fix-links DIR…`:** arduino-cli unpacks into a temp folder
  and renames it, which breaks proot's fake hard links (AGENTS.md
  "proot notes"); replacing each with a copy works. arduino-cli 1.5.2
  then builds Blink for the Nano, Leonardo and C3 (esp32 core 1.5 GB,
  13 min first build). PlatformIO 6.2.0 builds AVR, but its ESP32
  toolchains fail to install (`copytree(symlinks=True)`: proot's
  getdents says DT_LNK for a fake hard link, `readlink` says EINVAL):
  the guide sends ESP32 users to arduino-cli.
- Recipes in the guide's USB section: esptool, mpremote, arduino-cli,
  PlatformIO, avrdude.
- Merged with entry 124 (port names) into one PR. **Main agent's
  change:** `serial-names` off by default for this round, since a
  broken own `/dev` would break every new tab with no shell left to
  turn it off; the plan turns it on from a running tab.
- **Found in CI, fixed:** the monitor's tests needed `keybar` on the
  PATH (the phone has `/opt/mynx/bin`, CI doesn't); and a race in
  `SerialServer`: it freed the port before dropping a leaving
  client's RTS and DTR, so a new client could have its lines dropped
  (the drop-order test flaked on it). Now freed after; test added.
- **Build:** `Download/mynx-dev-39082299921.apk` (PR #36's run
  39082299921), copied with `deliver.sh --no-open`: open it from the
  Files app to install mynx dev.
- Left out: UF2 and raw USB (Android-side work: mass storage, the
  USB fd into Debian), static Go programs (needs a proot patch).
- Known flaky under load (passed alone): `test_undo_list` (needs
  `TZ=`), `test_audio_play_stops_quietly_on_ctrl_c`, a
  `SerialServerTest` timing test.

### 2026-10-18 (124): Serial port names tools find (branch `serial-names`)

Overnight, a subagent, not on the phone yet. `/dev/ttyACM0` and
`/dev/ttyUSB0`, and a `/sys/class/tty` stand-in with each port's USB
details, so `list_ports`, esptool with no `--port`, PlatformIO and
`arduino-cli board list` find the ports (setting `serial-names`).
- **Why a `/dev` of Debian's own:** pyserial (`glob('/dev/ttyACM*')`)
  and go.bug.st/serial (`ReadDir("/dev")`) list `/dev`, and Android
  refuses that to apps (checked: EACCES, also `/dev/pts`). Binds don't
  show in listings (proot has no `getdents` emulation, `glue.c` says
  so), and a bind whose host path is missing at start is dropped
  (`new_binding()` realpaths it), so per-name binds over Android's
  `/dev` can't come and go. A folder of ours at `/dev`, with the
  phone's device files bound into it, is listable and its links change
  freely. Rejected: a proot patch (`getdents` for binds), wrapping
  `opendir` in the preload library (every program, and Go skips it).
- **Fallback:** `serial-names off` (new tabs) gives Android's `/dev`
  back, exactly as before; the helpers never get the own `/dev`.
- **Checked off the phone:** pyserial 3.5's `list_ports_linux` run
  against a written tree (paths swapped) gives `USB VID:PID=303A:1001
  SER=AA LOCATION=1-1:1.0` and `1A86:7523 LOCATION=1-2`, with
  products as descriptions. go.bug.st/serial's code read, not run.
- **Unsure until the phone:** whether anything in Debian needed a
  host `/dev` entry not in `HOST_DEVICES` (only those the phone has
  are bound; `ls /dev` shows what's there); proot following a link in
  the bound folder that's made after it started (it should: it's how
  `/dev/serial` links work). esptool still warns "Failed to get PID
  … /dev/pts/N" (it follows the link): harmless.

### 2026-10-18 (123): USB serial docs cleaned up

PR #34 is merged, so its plan (Next 0) and entries 115-122 left the
log (`git show 46bd16c:docs/WORKLOG.md`); their lessons are in
AGENTS.md. Two loose ends went to "Small open items". The roadmap's
development boards section says what's done and what's left.

### 2026-10-13 (114): Autostart on and off

The maintainer asked, before v0.2.0, for a way to switch a saved
service's autostart without editing the file (branch
`service-autostart`):
- core: `setServiceKey()` changes a block's `key = value` line, or adds
  one after its last setting; the `service-set NAME KEY=VALUE` request
  uses it (saved services only, an undo step, a running one keeps
  running).
- `mynx service autostart NAME on|off`; the menu's Services → NAME has
  "Autostart: on/off", which switches it (not for unsaved services).
- The guide says what starts with the app: autostart ones, plus those
  running when Android closed it; after Exit only autostart ones.
- AGENTS.md: GitHub deletes merged PR branches (setting on, 2026-10-13).
Phone test 21 (in "Next 0") is still to do. Then the maintainer
releases v0.2.0.

### 2026-10-13 (113): Services in the README and roadmap

PR #30 merged (eeb9b21). The README lists background services among
what ships; the roadmap moves them from Planned to Done. Next: the
maintainer plans v0.2.0 after a day or two of services in mynx dev.

### 2026-10-13 (112): The review's two leftovers, fixed

Maintainer's request, by two agents in parallel, merged on
`services`:
- A service whose block is deleted by hand while it runs is
  `orphaned`: unsaved until it ends, then gone from the list
  (`ServicesModel.kt`, e8999de). Adopted services stay listed after
  they end, as before.
- A reload of `services.conf` that changes anything calls
  `host.changed()` once (`ServiceManager.reload()`, 045381c), and the
  app rereads the file on `mynx check` and the other reloading
  requests, `undo` included (682a1d7).
Phone test 20 checks both: passed on the phone (run
38378514995). PR #30 marked ready and merged.

### 2026-10-13 (111): Background services confirmed on the phone

The maintainer ran all 19 phone tests in "Next 0" on mynx dev (run
38324395547's build): all pass. One bug found and fixed (6f47077,
run 38348748133 green): `mynx` crashed (FileNotFoundError on
`ID.wait`) when the app answered a [Later] at once, since it writes
`ID.wait`, then `ID.reply`, then deletes `ID.wait`. The race was
old, but every `service-*` request answers that way. Test 6's
expectation was wrong, not the code: a program that catches Ctrl+C
and exits 0 isn't restarted under `on-failure`; kept as it is (the
long-press Restart is for restarting). Then the two review items
were fixed (entry 112).

### 2026-10-13 (110): Review of the services branch, seams fixed

Reviewed `main...services` for bugs at the seams between the four
parts. CLI ↔ core requests and reply fields, the menu's `list --json`,
`serviceCommand` quoting and the `NAME.pid`/`NAME.exit` folder (same
`rootfs + /tmp/.mynx/services` on both sides) all match. Fixed,
test-first:
- `service-run`: a command given on the line (an unsaved/adopted
  service) ran at home; it now runs in the folder the app starts it in
  (the tab's). A name defined twice ran the last block; now the first,
  as core reads it.
- `ServiceManager`: any exception in a `service-*` request (not only
  `ServiceRefused`) is a refusal. Before, it escaped `processPending`
  before the `.req` was deleted, failed again at every poll and held
  up the requests after it.
- Menu: a failed Start/Stop/Restart waited for two keys.
Left for later, then fixed in entry 112: a hand-deleted block's
service stayed listed after it ended, and `list`'s reload didn't
refresh the app.

### 2026-10-13 (109): Service tabs, the strip's mark, Run as service (parts 2, 5, 6)

Not yet tried on the phone. Core: `Tab.service`, `Tabs.openService`/
`followService`/`makeService`/`setServiceTitles`/`closeServicesExcept`,
service tabs left out of `state/tabs`; `serviceTabTitle()` and
`ServiceManager.tabTitle()` ("● web", "○ web: exit 1", "restarting",
"failed", "stopped"); `keyBarNames(fallback =)` (the `service` bar);
`ServiceHost.started()` (an open tab follows a restart) and
`adopt(name, shell)`; `ServiceManager.start/stop/restart/adopt(name)`
for the app; `foregroundCommand()` (`/proc`: the shell's `tpgid`, the
group leader's `cmdline`) and `suggestServiceName()`;
`servicesMark()`, `SERVICES_TAB_COMMAND` (`menu services`, a new
entry point of the menu). App: service tabs (× hides), long-press
menus (Rename / Run as service; Hide / Restart / Stop or Start), `● N`
right of +, the notification's Services button, service sessions'
cwd/keybar files removed once unused, the cursor blinker asks the
shown terminal. Decisions: an adopted tab stays open as the service's
tab; Hide/Restart/Stop live in the long-press menu (no app-handled key
bar action); a tab adopted by `mynx service adopt` from inside runs
`mynx` at that moment, so its command is `bash` until saved, and an
unsaved service restarts with its current foreground command. Docs:
tools/AGENTS.md, AGENTS.md architecture, README (wakelock auto).

### 2026-10-13 (108): Services in the app (parts 1 and 3, branch `services`)

Not yet tried on the phone. Core: `prootLaunch(service =)` sets
`MYNX_SERVICE`; `serviceCommand()`/`shellQuote()` run the wrapper;
`serviceWorkDir()`; `ServiceManager.stopAll()`/`active`;
`serviceGaveUpText()`; `wakelock = auto|on|off` (default auto, the
`Wakelock` enum); `runningTerminalsText(services =)`. App:
`ServiceSessions` (the `ServiceHost`: hidden sessions started with
`updateSize(80, 24, 10, 20)`, TERM to the group then `stop()`;
`open`/`adopt` refuse until parts 2 and 6), wired in
`TerminalService`: requests, launch at start, `state/services`,
"Services" notification channel, lifetime with no tabs (the activity
finishes; the app exits when the last service ends), Exit stops them
(TERM, 3 s, then onDestroy forces). `tools/AGENTS.md`'s `wakelock`
line still says on/off: part 4 (docs) updates it.

### 2026-10-12 (107): Background services planned, spike on the phone

Planned with the maintainer ("Next 0": decisions, design, request
protocol, six parts). A throwaway spike (PR #29, branch
`spike-hidden-session`, closed unmerged) showed that a hidden session
runs and keeps running with the screen off, and that proot reports a
death by signal as exit 0, which led to the `service-run` wrapper.
Work is split for agents overnight on the branch `services`.

### 2026-10-12 (106): v0.1.2 confirmed on the phone

The maintainer's daily mynx found v0.1.2 by itself: the notification
came and the menu showed "Update available"; the menu item ran
`mynx update` and it installed. Afterwards the restored tab showed
"What's new in mynx" with v0.1.2's notes, once; a new tab showed
nothing; Help → Release notes lists v0.1.2, v0.1.1 and v0.1.0.
`notes-seen` holds `0.1.2` and the update marker is gone. With that,
entries 104 (release notes, What's new) and 105 (v0.1.2, the release
title fix in `release.yml`) left the log: what they decided is in
`AGENTS.md` ("Release notes", "Releases"), the rest in git history.
"Next" item 1 (check What's new) is done.

### 2026-10-11 (103): Volume keys (branch `volume-keys`)

The maintainer's approved design, built test-first:
- A key bar's `VolumeUp`/`VolumeDown` lines (label in any case) say
  what the phone's volume keys send while that bar shows; they aren't
  buttons and take no room. Same syntax as a button's keys; they
  repeat only with `Repeat` (unlike arrow buttons).
  `core/.../KeyBar.kt`: `ParsedKeyBar`/`LoadedKeyBar` gain
  `volumeUp`/`volumeDown`; a second line for the same key is a
  problem ("VolumeUp is already set on line N"), the first good one
  counts; bad keys are problems as for buttons, so `mynx check`
  reports them. `volumeKeyAction()` decides: Pass (bar doesn't set
  it, the volume changes), Press, Swallow (Android's repeats of a held
  key), Release.
- app: `MainActivity.dispatchKeyEvent()` gives volume keys to
  `KeyBarView.onVolumeKey()` before any view, so it works whatever has
  focus; it sends with the buttons' `press()` (sticky Ctrl included)
  and repeats with their timer (400 ms, then every 50 ms). The repeat
  also stops when the window loses focus (a key released outside the
  app).
- No built-in bar sets them; no long press, no setting (decided).
- Docs: tools/AGENTS.md "Key bars", README, AGENTS.md architecture;
  ROADMAP: Volume keys left "Next".
- Added after review: `KeyBarView.refresh()` stops a held key's
  repeat when the bar changes (a program switching bars mid-hold
  sent the release to Android, and the repeat ran on).
- **Confirmed on the phone** (2026-10-12, mynx dev, build of
  `c7f5a34`): the shell bar with `VolumeUp = Up Repeat` and
  `VolumeDown = Down`: short presses move history, only VolumeUp
  repeats when held; `files` still changes the volume and the shell
  gets them back after; keyboard up and down; `mynx check` reports a
  duplicate line.

### 2026-10-11 (102): Vibration patterns (branch `vibration-patterns`)

The maintainer's approved design, built test-first:
- `mynx vibrate [MS] [--strength PCT]`, `effect NAME`, `wave STEP…
  [--repeat]`, `chain PRIM…`, `list`, `stop`. Plain MS still returns
  at once (`vibrate`, now with `strength=PCT`); effect, wave and chain
  wait until the vibration ends; Ctrl+C stops it (a repeating wave's
  Ctrl+C exits 0, the others 130 like `audio play`).
- `core/.../Vibration.kt`: `Vibrations` checks steps, primitives,
  limits (60 s, 1000 wave steps, 100 primitives, 10 s pauses) and API
  levels against the app's `VibratorInfo`, refusing what the phone
  can't do with a reason; works out the length (primitive durations
  from Android 12, else estimates; effects are estimates, Android
  doesn't say) and answers when it's over. One vibration at a time:
  a new one or `stop` answers the waiting one `"stopped": true`.
- `app/.../Haptics.kt` (`PhoneHaptics`) replaces
  `TerminalService.vibrate()`; Android 13+ vibrates with
  `VibrationAttributes` `USAGE_MEDIA` (design said "USAGE_TOUCH or
  similar": touch is UI feedback and goes off with the phone's touch
  feedback setting; media vibration is what app content uses).
- **Deviation:** `vibrate-list` and `vibrate-stop` are [Later]
  requests answered at once rather than plain ones, so stop can end a
  waiting vibration and everything stays in `Vibration.kt`; to `mynx`
  they look the same.
- No new setting; the metronome is untouched.
- Unrelated: `test_undo_list` fails when `TZ` isn't UTC (this tab's
  is Europe/Helsinki); CI is UTC.
- **Confirmed on the phone** (mynx dev, PR #24's build): `list`
  shows strength control, all four effects and all eight primitives
  with lengths (click 29 ms … slow-rise 500); each effect, a wave with
  pauses, `--repeat` with Ctrl+C, `stop` from another tab and a chain
  all work. In Vibrate mode `effect click` still clicks and the
  metronome still buzzes, so `USAGE_MEDIA` stays. Mute not tried.
- ROADMAP: Battery left "Next" (it shipped in #21-#22).

### 2026-10-11 (101): Docker Hub sign-in, backup docs, housekeeping

- **Docker Hub:** the token secret was wrong. The maintainer made a
  new read-only token (`mynx-build`), set from the clipboard with
  `gh secret set DOCKERHUB_TOKEN`; a re-run of main's build (run
  38149539484) printed "Login Succeeded".
- **Backup:** `backup-root.sh` defaults to `Download/mynx-backup`
  (apps can't make top-level folders) and ends with the restore
  path: share the four files to mynx from the Files app, then `bash
  ~/Shared/restore-root.sh` (a reinstalled app can't read the old
  one's files in Download). `restore-root.sh` now says to delete the
  four files, not `rm -r` the folder, which is now `~/Shared`.
  `BACKUP_STORAGE` lets the tests use a fake phone storage.
  **Confirmed on the phone:** a scratch backup from mynx, its four
  files shared to mynx dev, landed in `~/Shared` under their own
  names; `restore-root.sh` checked the sums, `n` changed nothing, `y`
  restored and installed all 22 packages (3m46s with the JDK).
- Both scripts give the tarball's own size (`stat`, `numfmt`):
  `du -h` showed a 159-byte one as 4.0K (whole blocks), and "from
  /root/Shared (4.0K)" read as the folder's size.
- `docs/RELEASING.md`: "Debug and release builds on one phone" cut
  to "mynx dev beside the release" and "Moving to a new phone"; the
  builds-up-to-87 steps are gone.
- Work log: the move, v0.1.1 and the update test are done
  (maintainer: 0.1.0 → 0.1.1 came through `mynx update`).

### 2026-10-11 (100): The battery stand-in, step 2 (branch `battery-sysfs`)

Picked up after a crashed session: its core work was uncommitted
but complete. A Gradle daemon from that session was left untraced
by proot ("Function not implemented" on every file call, see
"proot notes") and hung every build until it was killed.
- `core/.../PowerSupply.kt`: `powerSupplyFiles()` turns a
  `BatteryReading` into `battery/` (type, present, status, health,
  capacity, technology, temp, voltage_now, current_now/avg,
  charge_now, charge_counter, cycle_count, time_to_full_now, uevent)
  and `ac`/`usb`/`wireless` (type, online, uevent);
  `writePowerSupply()` updates a folder in place. Battery.kt's unit
  helpers became `internal` so both share them.
- `prootLaunch(powerSupply = …)` binds it at
  `/sys/class/power_supply`. `TerminalService` keeps a battery
  stream (every 30 s too) writing `filesDir/power-supply` from
  `onCreate` until `onDestroy`; shells bind it. The sound server's
  proot doesn't.
- Docs: the user guide's `mynx battery` item, AGENTS.md
  architecture.
- On the phone (run 38139887743): streams, `--every`, `--json` work;
  `ac/online` follows a USB-C charger (Android calls it AC). `acpi -b`
  said 0% (no `charge_full`: now worked out from charge ÷ level) and
  psutil a negative `secsleft` (now `current_now` is the size only,
  maintainer's decision). Both fixed and **confirmed** (run
  38145343394): `acpi -b` 52%, psutil a positive `secsleft`,
  starship's battery module shows.

### 2026-10-11 (99): `mynx battery`, step 1 of 2

Roadmap "Next" (PR #19/#20): battery, vibration patterns, volume keys.
`mynx battery` shows everything Android gives without a permission:
level, status and plug, time to full, temperature, health, voltage,
current now and on average, charge (mAh), cycles (Android 14+),
technology, battery saver and the thermal status. `--stream` prints a
line when something worth knowing changes (not voltage or current
alone), `--every SECONDS` at least that often; `--json` with
snake_case keys. Core (`Battery.kt`) turns Android's raw numbers into
JSON and handles the phone's "don't know" values (`Int.MIN_VALUE`,
-1, 0) and volts-for-millivolts; while discharging the current always
flows out (phones disagree on its sign); no unit guessing for µA vs mA.
`BatteryReader` reads the sticky battery broadcast, BatteryManager and
PowerManager on the main thread. Decided (maintainer): no
`android-battery` setting (no permission, like torch and vibrate).
Tests: `BatteryRequestTest`, `test_mynx.py` (battery). Not yet on the
phone. Seen while testing: `test_undo_list` fails unless `TZ=UTC`
(depends on the tab's time zone since entry 98) and
`test_audio_play_stops_quietly_on_ctrl_c` is flaky; both untouched.

### 2026-10-10 (98): The phone's time zone in Debian

Debian ran on UTC: nothing told it the phone's zone. Each tab now gets
`TZ` from Android's default zone (`debianTimeZone()` in core's
`TimeZone.kt`): the zone's name when Debian has its zoneinfo file,
else a fixed POSIX offset (`<+0530>-5:30`, checked with `date`).
Decided: `TZ` in each tab rather than pointing `/etc/localtime` at the
zone, which would change the user's Debian; `TZ` in `.bashrc` still
wins. Tabs read the zone when they start, so after travelling only new
tabs follow. Tests: `TimeZoneTest`, `ProotLaunchTest`, `home-docs.bats`. Also installed
the JDK, missing since the move. Confirmed on the phone (mynx dev,
run 38067778668): a new tab shows local time.

### 2026-10-10 (97): Claude Code: messaging socket and fullscreen

After the move, Claude Code in the fresh Debian said "Cross-session
messaging is off" (no uid map under proot), and its input no longer
stuck to the bottom: its boot canary had recorded two fullscreen starts
that died early and turned fullscreen off for 2.1.296
(`fullscreenAutoDisabled` in `~/.claude.json`). Now
`mynx agent start NAME [ARGS…]` passes ARGS, and for Claude Code adds
`--messaging-socket-path ~/.cache/mynx/claude-msg/PID-TIME.sock`
(stale ones cleared) and `CLAUDE_CODE_NO_FLICKER=1` unless
settings.json has `"tui": "default"` (what `/tui default` saves) or
the variable is set. The shell's `claude`/`codex`/`gemini` functions
now call `mynx agent start`, which already did the key bar. Decided:
force fullscreen rather than clear the canary's record in
`~/.claude.json`, which running sessions rewrite. Tests:
`test_agents.py` (`LaunchTest`, `StaleSocketsTest`), `test_mynx.py`,
`shell-setup.bats`. Confirmed on the phone (mynx dev, PR #18 build):
`claude` from the shell and the menu starts with no messaging warning,
input at the bottom; after `/tui default` a new `claude` is inline, as
asked. The maintainer's `~/.local/claude-wrap` (see `~/AGENTS.md`) can
go once a release has this.
Also `scripts/deliver.sh`: after the move it failed copying to
`Download/mynx-build.apk`, which the uninstalled debug-signed mynx made
and this one can't overwrite. Each run is now `mynx-dev-RUN.apk` (its
own older ones deleted), and from the release mynx, where
`install-apk` is refused, it says to open the file in Files. Tried on
the phone with run 38058304575.

### 2026-10-10 (96): The first release, v0.1.0

The maintainer made the release key in their own tab (RSA 4096,
PKCS12, alias `mynx`), backed it up on two offline copies (checked on
Windows with KeyStore Explorer: same file hash and certificate
fingerprint), set the four `MYNX_RELEASE_*` secrets and pushed
`v0.1.0` at `cbbacc2`. The release run (38050617435) passed first time,
lint-vital included, and published `mynx-0.1.0.apk` (106 MB); its
signature check printed certificate SHA-256 `24b7fefa…2b40`, matching
the key. The key was deleted from the phone.

Lessons:
- Long commands pasted into a 56-column tab broke at the screen edge
  and ran in halves; guide with short lines (or variables) instead.
- `backup-root.sh` can't use its default folder, and another app can't
  read what it writes to `Download` (Next, item 1.2). The maintainer
  chose to move without a backup; the backup was deleted.
- Docker Hub sign-in still fails (item 0); builds pass anonymously.

### 2026-10-10 (95): Menu: a Settings submenu (branch `github-setup`)

The main menu loses System: Settings is now a submenu, App settings
(`mynx edit`), System (Update all, System info, GitHub setup) and Help
(Getting started, Report a bug, About). New `menu.conf` actions:
`settings` is the submenu, `app-settings` the editors, `help` the Help
menu. Until gh is signed in (its `hosts.yml`, following
`GH_CONFIG_DIR`) and git's email is set, the main menu has "Set up
GitHub" after AI agents (last without them, whatever `menu.conf`
says) and System leaves it out; then it moves to System (maintainer's
decision, after first dropping it). Docs (README, PRIVACY,
`tools/AGENTS.md`, `welcome.txt`, AGENTS.md) follow. The session that
started this crashed; the next one finished the docs. Not yet tried on
the phone.

### 2026-10-10 (94): Guided GitHub setup, built (branch `github-setup`)

`mynx github` and System → GitHub setup (menu item at the end of
System, so "Report a bug" keeps key 5). It installs `git` and `gh`
from Debian if missing (asking; `apt_install()` now shared with
`mynx sound install`), runs `gh auth login --web --hostname
github.com --git-protocol https` on a pseudo-terminal of its own
(`run_in_pty()`): it passes keys in, reads the `one-time code: XXXX-XXXX`
line from gh's output, sends it to `clipboard-set` and adds a line
saying so. Already signed in: asks to switch (maintainer's decision
this session). Sets git's name (account name, else login) and email
(`ID+login@users.noreply.github.com`), asking before replacing others;
`--yes` installs and replaces without asking and keeps the account.
Then `gh auth setup-git` and a summary. `mynx github status [--json]`
changes nothing.
- **Why the credential helper is set before signing in:** with no
  gh helper in git's config, gh first asks "Authenticate Git with your
  GitHub credentials?" (and queries the cursor position, which needs a
  real terminal). `gh_for_git()` adds `""` and `!gh auth git-credential`
  for `https://github.com`, so gh skips it.
- Checked against the real gh 2.46 here (scratch `GH_CONFIG_DIR` and
  HOME, signed in nowhere): the code was copied and the note shows
  right under the code line. Not yet tried on the phone with a real
  sign-in: Next 4.
- Tests: `tests/mynx/test_mynx.py` (fake gh and git on a bare PATH),
  `tests/shell/menu.bats`. The roadmap's "Guided GitHub setup" left
  `docs/ROADMAP.md`; README and `tools/AGENTS.md` describe it.

### 2026-10-10 (93): Docs cleaned up again

Maintainer's request: drop what isn't needed. `docs/SCREENSHOTS.md`
is gone (the images stay: the README shows them). `docs/ROADMAP.md`
now holds only what's planned (next, profiles, tab strip and file
manager extras, per-profile permissions, boards, Bluetooth, Play) plus
"How it works"; the built features are the README's. The USB/BLE
ideas from entry 86 moved into it. The maintainer added two plans:
the guided GitHub setup (moved from "Next" to "Planned") and hidden
tabs for background services and servers (new, not designed yet). Confirmed entries 80, 82, 83 and
85-92 left the log (Codex's sandbox went into AGENTS.md's lessons);
"Next 0" is now just the Docker Hub sign-in, which still fails.

### 2026-10-08 (84): Backup and restore for the move to the release

The maintainer asked for scripts for plan step 4 (uninstall the
debug-signed Mynx, install v0.1.0). `scripts/backup-root.sh [FOLDER]`
and `scripts/restore-root.sh [--yes]` (bats: `backup-root.bats`; the
TDD check pairs both with `tests/shell/`). Docs: RELEASING.md.
- Backup: `/root` as `root.tar.gz` without `~/.gradle`, `~/.cache`,
  proot's `.l2s.*` files and Claude Code versions other than the one
  `~/.local/bin/claude` points to; `apt-mark showmanual` as
  `packages.txt`; `SHA256SUMS`; `restore-root.sh` beside it. Refuses
  a folder that isn't empty, or one inside the home.
- Restore: checks `SHA256SUMS`, asks, extracts over `/root` (other
  files stay), `apt-get update` and installs the packages (one by one
  only if all at once fails, to name the failures).
- **proot's hard links survive tar:** proot shows each linked file as
  a plain file, so tar stores its contents; the `.l2s.*` files it keeps
  behind them can be left out. Checked: a tar of the repo without them
  passes `git fsck`.
- **Tried on this Debian:** backed up the real `/root` in 52 s, 156 MB
  (from 1.6 GB), restored it into a scratch home with apt stubbed: the
  repo is intact, the `claude` link works, `gh`'s login is back. The
  scratch copies were deleted (they hold logins).

### 2026-10-08 (81): App updates, built (branch `updates-core`)

Plan step 2 ("Updates" in "Next"), test-first, on the branch
`updates-core` (worktree `.claude/worktrees/agent-a6b292baa176cfe22`).
The first session crashed after the core pieces; nothing was lost.
- Core: a small JSON reader, semver `Version`, `parseRelease()` for
  GitHub's latest-release JSON (`mynx-X.Y.Z.apk` asset, sha256
  digest), `fetchLatestRelease()`, `downloadApk()` (through `.part`,
  size and sha256 checked, redirects followed by hand), `UpdateState`
  (daily, an hour after a failure, notify once per version), and
  `Updater`, which ties them together: background checks, the menu's
  notice file, and the [Later] `update-check` and `update-install`
  requests (progress streamed, a checked download reused, only the
  version the user saw is installed).
- Setting `update-check` (on): only the daily check; `mynx update`
  works either way.
- App: `TerminalService` ticks hourly, posts "Mynx X is available"
  (channel "App updates"); tapping it opens a tab running `mynx
  update` (`UPDATE_TAB_COMMAND`; MainActivity's settings-tab code is
  now `tabToOpen`). `REQUEST_INSTALL_PACKAGES` and `ApkProvider` moved
  to the main manifest (`app/src/debug/AndroidManifest.xml` is gone);
  `install-apk` stays debug-only. Release builds only: Mynx Dev
  refuses ("this build of the app doesn't update from GitHub
  releases").
- `mynx update [--check] [--yes] [--json]`; the menu starts with
  "Update available" while `/tmp/.mynx/update-available` exists.
- Docs: `tools/AGENTS.md` ("Updates", the setting, the command),
  AGENTS.md (architecture, why the permission is in the main
  manifest).
- The `app` module isn't compiled locally: the first CI build of the
  branch is its first compile.

