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
- **USB serial bridge:** every phone test passes (entries 116-118:
  ESP32-C3, ESP32-CAM-MB, Arduino Nano and Pro Micro, two at once on
  a hub); merged (PR #33), not released yet. Part 2, a preload
  library so pyserial tools work on `/dev/serial/…`: built and
  tested off the phone (entry 120); the phone tests are "Next 0".
- **Step 8 (*Profiles*) is parked** by the maintainer (plan below).
- **Workflow:** trunk-based, `main` protected (PRs only, entry 83).
  CI builds PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`.


## Next

### 0. USB serial, part 2: a preload library for `/dev/serial` (branch `serial-preload`)

**Where it stands (entry 121):** PR #34, mynx dev 180: checks 1, 2,
3 and 5 pass on the C3. Left: 4 (Nano) and the CAM-MB with picocom's
`--lower-dtr --lower-rts`, then 6 (README), then merge. If a check fails, the helper's log
(`/tmp/.mynx/serial/NAME.log`) shows every DTR/RTS change ("DTR off,
RTS on"); a quick check by hand without CI: `CC=gcc
scripts/build-preload.sh /tmp/x.so; LD_PRELOAD=/tmp/x.so esptool
--port /dev/serial/ttyACM0 chip_id`. Unsure until the phone: whether
proot passes the control socket's `connect()` between two proots
(the sound server's socket does), and `TIOCGSERIAL` (not wrapped:
pyserial only uses it for low latency and odd baud rates).

The bridge (part 1) is merged (PR #33, 2026-10-17) and every phone
test passed (entries 116-118; the plan and the tests:
`git show d748955:docs/WORKLOG.md`). How it works and its gotchas:
AGENTS.md "USB serial bridge" and "Lessons".

**The problem:** Android's SELinux refuses some calls on a pty with
EACCES: `TIOCMGET/SET/BIS/BIC` (DTR, RTS), `TIOCEXCL`, `TCSBRK` (so
`tcdrain()`), `TCXONC`, `TIOCGSERIAL`, and `flock`. So pyserial
(esptool, PlatformIO, miniterm) can't open `/dev/serial/…` and must
use `rfc2217://localhost:2217`; picocom needs `--nolock`; avrdude
can't toggle DTR itself.

**Decided (maintainer, 2026-10-17): option 3, a global `LD_PRELOAD`.**
Rejected: leaving it as is (the URL only), an opt-in wrapper
(`serial-run esptool …`), patching proot (would cover Go programs too,
but more patches on proot's C).

**The design (agent's; change it if it doesn't hold up):**
- `/opt/mynx/lib/libmynx-serial.so`, a small C library (keep it to a
  few hundred lines: every program in Debian loads it).
- `prootLaunch` sets `LD_PRELOAD` to it in every proot (tabs,
  services, the helpers) **only when the file exists**: a missing
  preload makes ld.so warn on every program start. Off switch: a
  setting (`serial-preload = on | off`, default on) and `unset
  LD_PRELOAD` in a shell. Not `/etc/ld.so.preload`: that's the user's
  Debian.
- It wraps `ioctl`, `tcdrain`, `flock` (and `tcflow`, `tcsendbreak`
  if needed). Each calls the real one first (`dlsym(RTLD_NEXT, …)`)
  and only acts when that failed with EACCES **and** the fd is a
  `/dev/serial` pty (`/proc/self/fd/N` reads `/dev/pts/N`; a link in
  `/dev/serial/` points at it). Everything else passes through
  unchanged, errno kept.
  - `TIOCMSET/BIS/BIC`: ask the port's helper over a control socket
    (`/dev/serial/.NAME.ctl`), which sends RFC 2217 SET-CONTROL
    (DTR/RTS on/off), so esptool's reset really reaches the board.
    Keep "DTR first when raising, RTS first when dropping" (Lessons).
  - `TIOCMGET`: the lines as the helper last set them (plus CTS/DSR/CD
    if the helper learns them from NOTIFY-MODEMSTATE; else report
    them on).
  - `TIOCEXCL`/`TIOCNXCL`, `flock`: succeed (the helper allows one
    user at a time already).
  - `tcdrain`: wait until `TIOCOUTQ` says the pty's output is empty
    (and the helper has sent it, if that's easy).
- **glibc's `tcdrain()` doesn't go through the `ioctl` symbol** (it
  makes the system call inline), so wrapping `ioctl` alone won't
  catch it: wrap `tcdrain` itself. Python's `fcntl.ioctl` and
  `termios.tcdrain` call the libc symbols, so pyserial is covered.
- **Static programs** (Go: `arduino-cli monitor`) skip libc: not
  covered; say so in the guide.
- `serial-port` (Python) gets the control socket: a request
  (`set DTR 1`, `get`) → SET-CONTROL to the app, answer `ok`/the
  lines. While no program has the pty open nothing asks, so it can
  stay in the same `select()` loop.

**Build:** it must link against **Debian's glibc** (arm64), not
Android's bionic, so not with the NDK. In CI: `gcc-aarch64-linux-gnu`
on the runner, or inside the rootfs build's Debian container
(QEMU), then into `assets/tools.tar.xz` through
`scripts/pack-tools.sh` (`/opt/mynx/lib`). Both workflows (build and
release) build the same assets: change both.

**Tests (TDD):** the helper's control socket in
`tests/mynx/test_serial_port.py`; the library from Python tests that
compile it (skipped where there's no `cc`) and run a small program
under `LD_PRELOAD` against a fake: a real EACCES only happens on
Android, so a test hook (an env var naming the paths to treat as
refused, read only by the test build or under `MYNX_SERIAL_TEST`)
makes the real call fail. CI installs `gcc`. Add the C source and its
tests to `scripts/check-tdd.sh`. `prootLaunch` and the setting: core
tests.

**Working in mynx dev** (the maintainer's plan): only mynx dev has
`/dev/serial` and the board, and its Debian may get `gcc` (`apt
install gcc`; not in the release mynx's Debian, the maintainer's).
So build the `.so` there and try it by hand, `LD_PRELOAD=./x.so
esptool --port /dev/serial/ttyACM0 chip_id`, before CI. Setup there:
`mynx github`, clone the repo, `scripts/setup.sh`,
`openjdk-21-jdk-headless` and `bats` for the tests. Installing a new
mynx dev build restarts the app and ends the agent's session there
(`claude -c` resumes it). The two Debians share nothing but GitHub:
push before switching.

**Done when** (phone, mynx dev, ESP32-C3 with MicroPython and the
CH340 ESP32-CAM-MB):
1. `esptool --port /dev/serial/ttyACM0 chip_id` resets into the
   bootloader, prints the chip, resets back; `read_flash` of 4 MB
   matches one read over the URL (`cmp`).
2. `python3 -m serial.tools.miniterm /dev/serial/ttyACM0 115200`:
   the REPL answers; quitting leaves the board running (not in its
   bootloader).
3. `picocom -b 115200 /dev/serial/ttyACM0` without `--nolock` works;
   its start shows the modem lines without errors.
4. avrdude on a Nano (`/dev/serial/ttyUSB0`, `-c arduino`) no longer
   complains about DTR.
5. `serial-preload off` (new tabs): 1 fails as before; other programs
   don't notice the library at all (`ls`, `apt`, `python3`, `git`
   start with no warnings, on or off).
6. Then: the guide (pyserial tools work on the path, Go ones need the
   URL), AGENTS.md, README.

### 1. Smaller things

- **Maybe:** `CONTRIBUTING.md`, `SECURITY.md`.
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

---

## Log

### 2026-10-17 (121): Preload on the phone: the C3 passes 1, 2, 3, 5

mynx dev 180 (PR #34), ESP32-C3 with MicroPython on `ttyACM0`:
- **1:** `esptool --port /dev/serial/ttyACM0 chip_id` resets into the
  bootloader, runs the stub, hard-resets (the helper's log shows each
  DTR/RTS change); `read_flash` of 4 MB on the path (366 s) is
  identical to one over the URL. esptool says "Failed to get PID of a
  device on /dev/pts/4, using standard reset sequence" (no sysfs):
  harmless.
- **2:** pyserial on the path (as miniterm opens it): the REPL
  answers twice in a row, so the board stays out of its bootloader;
  `cts`, `dsr`, `cd` read True.
- **3:** picocom without `--nolock`: no errors, the REPL answers.
- **5:** without `LD_PRELOAD` pyserial fails at `TIOCMBIS` (EACCES)
  as before; with `serial-preload off` a new service gets no
  `LD_PRELOAD`, on gets the library; `ls`, `python3`, `git`, `apt`,
  `esptool` start silently with it.
- **Seen once, not this change:** the first 4 MB `read_flash` over
  the URL failed after 4 min ("Corrupt data, expected 0x1000 bytes but
  received 0xc72"), the app on screen; the retry was fine. Entry
  117's lost bytes are rarer with 256 queued reads, not gone.
- Left: 4 (avrdude on the Nano) and the ESP32-CAM-MB with `picocom
  --lower-dtr --lower-rts`; then the README (6).

### 2026-10-17 (120): USB serial part 2: the preload library

In mynx dev 175 (gcc installed there), with a subagent for the
Kotlin part. No board was plugged in, so nothing tried on hardware.
- `tools/src/libmynx-serial.c` (~230 lines): wraps `ioctl`,
  `tcdrain`, `tcflow`, `flock`; steps in only on EACCES for a
  `/dev/serial` pty. TIOCM GET/SET/BIS/BIC go to the helper;
  TIOCEXCL/NXCL, TCXONC, flock succeed; tcdrain (and TCSBRK≠0) waits
  for TIOCOUTQ to reach 0; a break (TCSBRK 0) stays refused.
- `serial-port`: a control socket `/dev/serial/.NAME.ctl`; the lines
  are state (both up while open, back up when the program closes),
  sent in the safe order (`control_changes()`: RTS drops first, DTR
  rises first, swapping passes through both off). A request before the
  helper noticed the open connects at once with those lines, so
  esptool's open doesn't flash both lines up first.
- Setting `serial-preload` (on), `prootLaunch(preload =)`,
  `serialPreload()`, wired in `startShell` (tabs and services; the
  helpers don't get it). Commit 9f1fb15 (subagent).
- CI: `scripts/build-preload.sh` (cross gcc), `PRELOAD=` to
  `pack-tools.sh`, in both workflows. The tests compile it with the
  runner's own cc.
- Tests: `test_serial_port.py` (control socket, order) and
  `test_serial_preload.py` (a program under the test build: lines,
  order, tcdrain, other files untouched, the real build silent).
  **TDD note:** the helper's tests failed first; the C library was
  written before its tests, which were then checked to fail with
  the wrappers disabled.
- The guide (`serial-preload`, the USB section) and AGENTS.md are
  updated as if it works; the README waits for the phone ("Done when" 6).

### 2026-10-17 (119): Part 2 of USB serial decided: a global LD_PRELOAD

PR #33 merged; local `main` updated. The maintainer chose option 3
of four for the forbidden pty ioctls: a small preload library loaded
into every proot (the others: the URL only, an opt-in wrapper,
patching proot). The design, its build (against Debian's glibc, not
the NDK), tests and "Done when" are in "Next 0". The maintainer
continues it in mynx dev with an agent there, on branch
`serial-preload`. The bridge's done plan and phone tests left "Next"
(`git show d748955:docs/WORKLOG.md`).

### 2026-10-17 (118): Third round: the C3 passes; a CH340 board

mynx dev 173 (entry 117's fixes), tested with the maintainer:
- **ESP32-C3 (USB-Serial-JTAG, `ttyACM0`):** 1, 2, 3, 4, 6, 7, 8
  (write, and `read_flash` of all 4 MB twice: identical, 366 s each,
  92 kbit/s, so 256 queued reads fixed it), 10, 11, 12 pass. The
  MicroPython REPL answers twice in a row over the URL and the pty,
  and the board stays out of its bootloader after each client (RTS
  first works). The reset button restarts the C3's USB: picocom ends
  ("read zero bytes from port", as on Linux) and the port is back in
  a second. One earlier 4 MB read stalled for good (no CPU, waiting
  on a read with no timeout) while the app may have been off screen;
  not seen again in two full runs.
- **ESP32-CAM on an ESP32-CAM-MB (CH340, 1a86:7523, `ttyUSB0`):** 1,
  2, 7 (`--no-stub`: Debian's esptool has no stub for the ESP32, S2
  or S3; baud change to 460800 works) and 8 (read 256 KB twice,
  identical) pass. **4:** the MB holds the chip in reset while DTR and
  RTS are both on (checked over the URL: both on, silent; both off,
  it boots), and opening the pty raises both, as Linux does. On a PC
  `picocom --lower-dtr --lower-rts` avoids it; those need the
  forbidden modem calls. **Maintainer's decision: leave it** and
  document miniterm with `--dtr 0 --rts 0`; part 2 (LD_PRELOAD) would
  make picocom's flags work later. Guide updated, also with
  `--no-stub`.
- **Fixed: an old helper reached the new port.** After an unplug (or
  a C3's reset) the old helper, stopped by SIGQUIT but still alive for
  a second, retried while a program held its pty, and connected to
  the re-plugged device's new server, raising DTR and RTS (log:
  "opened at 115200 baud" right after the new link). The helper now
  stops once `/dev/serial/NAME` no longer points at its pty (the app
  deletes it, a new helper replaces it). Tests in
  `test_serial_port.py`.
- **Later the same day (build 175):** an Arduino Pro Micro
  (2341:8037, `ttyACM0`, a MIDI sketch: its serial number reads
  "MIDI") connects; once `mynx usb` caught it allowed with no port
  yet, and printed nothing: now it says the app is opening it. An
  Arduino Nano (CH340): opening the pty resets it into optiboot, so
  `avrdude -c arduino -b 115200` reads all its flash (7 s, twice
  identical) despite its `TIOCMGET` errors: **5 and 9 pass.** **13
  passes:** the C3 and the Nano on a hub (with a power bank on its
  PD port) get ports 2218 and 2217; the C3's REPL answered 20 of 20
  over its URL while avrdude read the Nano through its pty.
- Noted, not done: a program on the pty while the URL is busy gets
  silence with no hint (the helper retries every 2 s; the log says
  "the app closed the port"). Could say "busy" in the log or
  `mynx usb`.

### 2026-10-17 (117): Second round on the phone; read_flash loses bytes

mynx dev 172 (entry 116's fixes), the same ESP32-C3, tested by the
agent from the maintainer's Debian (mynx dev's):
- **7 passes:** `esptool chip_id` with the stub ("Stub running"), so
  the read queue fixed the stub's replies.
- **8 (read only): `read_flash` fails** with "Corrupt data, expected
  0x1000 bytes but received 0xb54" after 20-90 KB, at a different
  point each run; each 4 KB block alone reads fine. A failed run can
  hang until killed; the port works again after. Likely cause: the
  stub sends 63-byte (short) packets on USB-Serial-JTAG, each ending
  a read, so 16 queued reads held only ~1 KB, and the device drops
  what isn't collected in time. Raised to 256 reads and the USB read
  thread to audio priority (compiles only in CI).
- **4:** picocom (`--nolock`) opens the pty and the helper connects
  (log: "opened at 115200 baud", "closed"); the URL is refused
  meanwhile. The board's output not seen yet (its firmware may not
  print; the reset button is the maintainer's). The guide's picocom
  line now has `--nolock`.
- **8 (writing) passes:** with the maintainer's OK, the agent backed
  up the board's sketch (`--no-stub read_flash 0 0x150000`, twice,
  identical: `~/esp32c3-backup.bin`), erased it and wrote MicroPython
  v1.29.0 (1.75 MB in 5.4 s, hash verified), then drew the "m" and
  "mynx" on its 0.42" OLED (SSD1306 72x40 at column 28, I2C 5/6)
  through the raw REPL over the URL. The maintainer saw it work.
- **Found: every client leaving put the C3 in its bootloader**, so
  the board went silent after one session (URL or pty). The server
  dropped DTR then RTS: (1,1) → (0,1) is the auto-reset's reset, and
  DTR on just before means download mode. Now RTS first (core test).
  Explains test 4's silence.

### 2026-10-17 (116): First phone tests of the USB serial bridge; two fixes

Tested PR #33's build (mynx dev 171) with an ESP32-C3 board (built-in
USB-Serial-JTAG, 303a:1001, so `ttyACM0`). Results in Next 0, "First
round". Fixed on `usb-serial` (subagents; not yet on the phone):
- **The ptys never reached the board:** `SerialServer` bound
  `InetAddress.getLoopbackAddress()`, which is `::1` on Android;
  `serial-port` connects to 127.0.0.1 and was refused every time.
  Now bound to 127.0.0.1 (test asserts it). `rfc2217://localhost`
  still works: pyserial's `create_connection` falls back to IPv4.
- **esptool's stub lost reply bytes** over the C3's USB-Serial-JTAG
  (only the first packet, `c0 01`, arrived; `--no-stub` was fine).
  Guess: the stub drops a packet the host doesn't collect at once.
  `UsbBridge` now keeps 16 reads queued (`setReadQueue`, as Linux's
  cdc-acm), and each port has its own `UsbDeviceConnection`
  (`requestWait()` is per connection). Compiles only in CI.
- Found: picocom's `flock` on the pty is refused ("cannot lock");
  `--nolock` works. Left for the ioctl shim or the guide.

### 2026-10-16 (115): USB serial bridge, planned and built (branch `usb-serial`)

The maintainer asked for a plan and as much of it built as possible
overnight, to test in the morning. The plan, its decisions and the
phone tests are "Next 0". Built test-first:
- core: `Rfc2217Session` and `SerialServer` (checked against
  pyserial 3.5's own client, from a copy in the scratchpad), `Usb.kt`,
  `prootLaunch(serialDir)`.
- `tools/lib/serial-port` (tests/mynx/test_serial_port.py drives it
  with a real pty and a fake server), `mynx usb`, `mynx about`'s
  credit.
- app: `UsbBridge`, `UsbActivity`, the device filter, the "ready"
  notification. Compiles only in CI.
- Found: Android forbids the modem ioctls and inotify on a pty
  (AGENTS.md "Lessons"); hence RFC 2217 for pyserial and, later, an
  ioctl shim (Next 0, part 2).
- Housekeeping: test 21 of the services passed (2026-10-13) and
  v0.2.0 is out; the finished services plan left "Next".

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

