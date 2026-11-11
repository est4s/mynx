# mynx roadmap

What's planned. The [README](../README.md) describes what ships today;
the work log (`docs/WORKLOG.md`) has the current status and next steps.

**Done:** Debian through proot, tabs, the default setup (theme, font,
launcher menu, games), the file manager and key bars, customization
(`mynx`, the editors, undo), agent support, Android integration,
polish (first run, icon, updates from GitHub Releases), and background
services.

---

## Planned

### File manager
- Open other files in an Android app, share, "open terminal here" in a
  new tab.
- Tap support, off by default behind a setting (the app is
  keyboard-first).

### Agent widget
A home screen widget that starts a chat with an AI agent, even when
the app isn't running. Widgets can't hold a text field, so like
Claude's widget it's a set of shortcuts: a bar that looks like a
prompt line ("Ask Claude…") with a mic button at its end.
- **The text box** opens a new tab with the default agent (`mynx
  agent start`, which offers to install it if it's missing). It's an
  intent action like the Settings shortcut's, so `tabToOpen` already
  covers a cold start and a first run that installs Debian.
- **The mic** does the same, then listens with Android's speech
  recognizer (a "Listening…" overlay) and types what was said into
  the agent's prompt without pressing Enter, for every agent.
  Claude Code's own `/voice` is hold-to-talk and Claude-only.
- New settings: `default-agent` (`claude`, `codex`, `gemini`), and
  perhaps `agent-model`, passed as `--model`.
- The widget takes the theme's colours; mynx dev (`.dev`) gets its own
  copy, like the shortcuts.
- To check: whether each agent keeps what's typed while it starts.

### Touch mode
A second way to use mynx, for tapping instead of typing: the
launcher's items as native screens, and AI agents as a chat like
Claude's app. The terminal stays the default (mynx is keyboard-first);
a setting picks the view, `view = terminal | touch` (perhaps `desktop` later),
and either view switches to the other.
- **Home:** the launcher menu's items as native lists, read from the
  same `menu.conf` and app folders, acting through the same `mynx`
  commands, so both views always show the same things. The settings
  editors, games and other terminal programs open in a tab.
- **Chat:** an agent conversation with streamed replies, Markdown and
  code blocks, and its permission questions as Allow / Deny buttons.
  The agent runs headless and speaks a structured protocol instead of
  its full-screen terminal interface: ACP (the Agent Client Protocol;
  Gemini CLI speaks it, Claude Code and Codex through adapters), or
  each agent's own JSON stream if an adapter falls short. Users sign
  in with their own accounts, as in the terminal.
- **Artifact cards:** when an agent makes or changes an app in
  `~/apps` or `~/terminal-apps` (seen in its file writes, or declared with a `mynx`
  command), the chat shows a card: Run (a terminal app in a tab),
  Open (a web app, below), Share, or Install (an APK). **Built**
  for Claude Code's chat (2026-11-10), passed on the phone; Install
  only in debug builds (release builds offer Share).
- **Open in terminal:** hands the conversation to a tab that resumes
  it (`claude --resume ID` and the others' equivalents); the chat lets
  go of it first, so two programs never drive one session.
- **Home built** (2026-11-09): `view = touch`, passed on the phone.
  The native Services list and Chat for Claude Code passed on the
  phone (2026-11-10). Several chats at once, with the chat history
  as the agent's home, queued prompts, and a layout like Claude's app
  (+ sheet with files and permission, usage ring, dictation): passed
  on the phone (2026-11-10).
- **Chat first, with a drawer** (maintainer's decision, 2026-11-11,
  option 1A of the design study): in touch view, mynx opens on the
  agent's chat home: a prompt box at the bottom, the chats working or
  waiting for you above it. The rest of the app is one menu, a drawer
  behind ☰ (tap only: a swipe from the left edge is Android's Back):
  New chat, the latest chats and All chats, then Shell, Apps, Games,
  Files, Services (with its count), Settings, and switching to the
  terminal view. Its lists come from `core` (`HomeMenus` and the
  chats), so the drawer stays a thin view; the chat history stays a
  screen of its own. A launcher sheet next to the prompt box (option
  1B) was the alternative; if it's chosen instead, switch before touch
  mode's first release, while nobody has learned the drawer.
- **Later, not decided** (the design study, 2026-11-11): a
  "run, open or ask" box (open an app, run a command in a tab, or
  ask the agent); a launcher mode (mynx as Android's home app, off by
  default, with Android's apps beside Debian's); themes that change
  the launcher too (wallpaper, a bar, terminal panels); a second
  phone as an extra screen for a tab or an app.
- Sign-in is the terminal's: Chat opens a tab when it's needed
  (maintainer's decision, 2026-11-09).
- The agent widget could open straight into a chat.
- **Size:** the biggest feature yet, and mostly in `app/`, which only
  the phone can check: about 6-8 PRs. Order: a spike (does ACP or
  Claude's stream-json run under proot; do sign-in and resume work?),
  then Home, then Chat for Claude Code, then the cards, the hand-off
  and the other agents. The protocol and transcript logic go in
  `core`, test-first. Screenshots in CI (Robolectric) save phone
  rounds: CI uploads PNGs of Home and Chat (`AGENTS.md`, "Running
  tests").

### Web apps
**Part 1 done** (2026-11-08, PR #59, in the next release): the view, served apps, what
counts as one, the menu's Apps and Terminal apps, `mynx app`, and the
guide. **Part 2 done** (2026-11-08, PR #64, in the next release): the
bridge (`mynx.js`), permissions per app, home screen shortcuts, full
screen and screen awake.

A third kind of app beside terminal apps and APKs (the agent guide's
"Making an app"): HTML and JavaScript in `~/apps/NAME`, opened inside
mynx with no build and no install, like Claude's artifacts. Today an
agent can already serve one from a service (`python3 -m http.server`)
and open it in the phone's browser; this makes them part of mynx.
- **A web app view:** a WebView screen of its own per app (its own
  entry in recents, no bar: the back key and `mynx app reload`),
  each app served under an
  origin of its own (`https://NAME.mynx.invalid/`, answered by the
  app from the folder) that sees only its own folder, never the rest
  of Debian. It reloads by itself when the app's files change.
- **Served apps:** an app may instead be served by a background
  service on `127.0.0.1` (a Python backend); opening it starts the
  service, and closing it stops the service if opening started it.
- **What counts as one:** a folder in `~/apps` with an `index.html`,
  its name and icon from an optional `app.conf` (else the page's
  `<title>`), decided in core. The menu's Apps (terminal apps move
  to `~/terminal-apps` and a Terminal apps item) and touch mode's
  Home list them; `mynx app
  open NAME` opens one from the terminal, so
  agents can too. This doesn't need touch mode and can ship first.
- **The phone through a bridge:** a `mynx` JavaScript object
  (`mynx.js`) that sends the requests `mynx` already sends, answered
  by the same code in core: everything mynx has for the phone
  (location, sensors, camera, microphone and sound, vibration,
  flashlight, battery, clipboard, share, notifications, rotation
  lock), Bluetooth LE, USB and Bluetooth serial ports, and background
  services; streams as async iterators. Not settings, raw USB or
  UF2. Static and served apps get it, only on the app's own origin.
- **Permissions per app:** each app asks the first time for the
  private ones (location, camera, microphone, reading the clipboard,
  notifications, Bluetooth, serial ports, services: "Weather wants
  your location"); the answers live in a plain-text config, covered
  by `mynx check` and undo, changed with `mynx app permissions`.
  The `android-*` settings and Android's dialogs still apply.
- **Home screen shortcuts:** "Add to home screen" (Android's pinned
  shortcuts, with the app's icon) opens mynx straight into the app,
  starting its service as needed.
- **Full screen and screen awake:** `app.conf` keys to hide the
  system bars (games) and keep the screen on while the app is in
  front.
- **The agent guide** says how to make one and what the bridge
  offers, so agents build them unasked; "Making an app" then offers
  three kinds.
- **Not an APK:** a web app only runs inside mynx and only reaches
  what the bridge offers; no widgets, no opening other apps' files or
  shares, no background work of its own. For those, an APK, as today.
- **Order:** part 1 is the view, served apps, the listing, `mynx
  app` and the guide (without phone features; it also gives touch
  mode's cards something to open; planned in the work log, entry
  162), then the bridge, permissions and shortcuts.

### More of the phone
Further `mynx` commands for the phone's hardware, through Android's
APIs like `mynx sensor` and `mynx camera`, each with `--json` and an
`android-*` setting where it needs a permission.
- **Screen:** keep it on while a program runs (owned by that process,
  like `mynx rotation lock`), and the app's own brightness. The
  phone's brightness needs Android's "modify system settings", which
  the user turns on by hand.
- **Biometric confirm:** a fingerprint or face prompt that answers yes
  or no, for scripts to guard an action (the app must be on screen).
- **Hardware keystore:** keys made in Android's keystore that never
  leave the phone. First use: an `ssh-agent` in Debian that signs with
  them.
- **NFC:** read and write tags (NDEF, and raw ISO-DEP), only while the
  app is on screen; later, the phone as an NFC card (host card
  emulation).
- **Wi-Fi:** the connection (network name, signal, speed, frequency)
  and scans for networks. Scans need the location permission, and
  Android allows about four every two minutes. No monitor mode.
- **Cellular:** signal and network type, send and read SMS, start a
  call. Google Play only allows the SMS and call permissions for a
  phone's default SMS or phone app, so those stay out of a Play build.
- **Video:** record to a file (`mynx camera --video`), with exposure
  and focus settings for photos too. Later, a live stream into Debian
  over a local socket or HTTP (MJPEG), since there's no `/dev/video0`
  without kernel modules.

### Development boards
Flash and talk to boards like **Arduino** and **ESP32** over a USB-C OTG
cable, using standard tools.
- A **USB serial bridge** in the app *(PR #33 and #34; tested with an
  ESP32-C3, an ESP32-CAM-MB, an Arduino Nano and a Pro Micro)*:
  Android's USB API (usb-serial-for-android) to serial ports in Debian.
  Each port is a pseudo-terminal at `/dev/serial/ttyUSB0` and RFC 2217
  at `rfc2217://localhost:2217`, which carries the bootloader reset
  (DTR/RTS). CH340, CP210x, FTDI, PL2303 and CDC ACM (ATmega16U2,
  native-USB ESP32-S2/S3/C3, Pico). A preload library steps in where
  Android forbids the modem calls on a pseudo-terminal, so pyserial
  tools (`esptool`, miniterm, PlatformIO), `picocom` and `avrdude`
  work on the path.
- **Port names, a serial monitor and board tools** *(PR #36; tested
  with an ESP32-C3, an Arduino Nano and a Pro Micro on a hub)*: ports
  are also `/dev/ttyACM0` and `/dev/ttyUSB0`, with their USB details in
  `/sys/class/tty`, so pyserial's port list, esptool and PlatformIO find
  them (`serial-names`, on). `mynx usb monitor` with its key bar
  (resets, bootloader, baud, hex, timestamps). `mynx fix-links` for
  arduino-cli's toolchains, and the agent guide's recipes (esptool,
  mpremote, arduino-cli, PlatformIO, avrdude, the 1200 baud touch), so
  an agent can write, build and flash firmware.
- **Go programs** *(PR #37; tested with an Arduino Nano and an
  ESP32-C3)*: static programs skip the
  preload library, so proot steps in (an extension of ours): the modem
  calls on a `/dev/serial` port, for `arduino-cli monitor`, and a
  stand-in for the kernel's USB event socket Android refuses apps, to
  which the app sends ports coming and going, for `arduino-cli board
  list`.
- PlatformIO's ESP32 toolchains install and build (PR #39): proot's
  `getdents` said a faked hard link is a symlink while `readlink`
  failed on it; a proot extension now gives its file's type.
- **Raw USB** *(PR #47; the spike tested with an RP2040 Zero)*:
  tools that drive a device themselves (`picotool`, `lsusb`, pyusb,
  `dfu-util`, OpenOCD, probe-rs) open `/dev/bus/usb/BBB/DDD` and find
  devices in `/sys/bus/usb` as on Linux. The app opens the device
  through Android and hands the fd to the program (a proot extension
  of ours), so unpatched tools work, Rust and Go ones too. `picotool
  load` flashes a Pico; OpenOCD (Espressif's), GDB and probe-rs
  debug an ESP32-C3 over its built-in JTAG beside its serial port.
  Later: devices coming and going while a tool runs.
- **UF2 boards** *(PR #49; tested with an RP2040 Zero)*: copy
  firmware to the board's drive. Android sees the drive but won't
  mount it (an RP2040's shows as damaged), so `mynx uf2 flash` writes
  the UF2 itself, over raw USB.

### Bluetooth
Android doesn't let apps reach the Bluetooth hardware, so BlueZ can't
run; the app uses Android's Bluetooth API. Build the USB serial bridge
first.
- **Bluetooth serial** (classic SPP) *(PR #52; tested with an
  ESP32-CAM's ESP32)*:
  HC-05/HC-06, ESP32 `BluetoothSerial`, … as a serial port in Debian
  (`/dev/serial/rfcomm0`, RFC 2217), through the same bridge. Paired
  in Android's settings, bound with `mynx bt bind`; the link opens
  while a program uses the port, as Linux's `rfcomm bind`.
- **BLE** *(PR #53; tested with an ESP32-C3)*: `mynx ble` scans, connects, reads, writes
  and subscribes, with `--json` and streams like `mynx sensor`.
- **BLE UART** devices as a serial port *(PR #54; tested with an ESP32-C3)*: Nordic UART
  and HM-10-style modules, bound with `mynx ble bind`, become
  `/dev/serial/nus0` (RFC 2217 from 2417), linked while a program
  uses the port.
- Bluetooth headphones and speakers need nothing: the sound device
  plays through Android, which sends it to them.

---

## How it works

| Part | Approach |
|---|---|
| App | Kotlin, a single native Android app |
| Terminal | Termux's `terminal-emulator` / `terminal-view` libraries (Apache 2.0) |
| Linux | Termux's Android-patched `proot`, shipped as a native library so it can run on modern Android |
| Debian | A prebuilt arm64 rootfs, customized at build time and unpacked on first launch |
| Builds | GitHub Actions builds and signs the APK |

### Limitations
Because Debian runs through `proot` rather than a virtual machine:
- no `systemd`, Docker or kernel modules
- heavy work (big builds, package installs) is slower than on a PC
- arm64 devices only
