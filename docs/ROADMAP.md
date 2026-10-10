# mynx roadmap and scope

The full scope of the app: what's built and what's planned. The
[README](../README.md) describes what ships today. Not built yet:
profiles (step 8), parts of the tab strip (profile colours, the ⌄
profile list, drag to reorder, tab overview, Duplicate), per-profile
permissions, opening files in other apps from `files`, tap support, and
development boards.

---

## Features

### Debian, built in
- Full Debian (arm64) userland running through `proot`. No root needed and
  nothing to install separately.
- First launch unpacks Debian with a progress screen; after that the app opens
  straight into a shell.
- Use `apt` as normal: install compilers, editors, Python, Node, and so on.
- Your files and installed packages survive app updates. Updates only add
  on top of an existing install and never overwrite it.

### Multiple terminals, in tabs
Terminals open in **browser-style tabs**, laid out like Windows Terminal:
- A **tab strip** along the top. Each tab shows the profile's icon, a title
  and a **×** close button.
- A **+** button opens a new tab in the current profile. The **⌄** next to it
  lists your profiles, so you can open a tab in any of them.
- Tab titles follow what's running (for example `vim notes.txt`), or you can
  rename a tab yourself.
- Tabs take their **profile's colour**, so you can tell profiles apart at a
  glance. You can also pick a colour for a single tab.
- **Drag to reorder** tabs. Long-press a tab for rename, colour, duplicate,
  close others.
- **Swipe** across the terminal to move to the next or previous tab.
- With many tabs, the strip scrolls sideways, and a tab overview shows them
  all at once.
- An **activity dot** on background tabs shows new output, and a bell icon
  marks a terminal bell.
- Tabs keep running in the background (foreground service with an optional
  wakelock: the `wakelock` setting), so long jobs survive switching apps.
  Open tabs are restored when you reopen the app.

### File manager
`files` opens [nnn](https://github.com/jarun/nnn), a fast one-pane
terminal file manager that fits a narrow portrait screen, set up for the
phone. Midnight Commander is still installed, but it's built for wide
desktop terminals and a full keyboard.
- **Detail mode** by default: sizes, dates and permissions in one list.
- **Quick places** on `b`: home, Download, Pictures, DCIM and `/`, plus
  your own bookmarks.
- **Key bar:** while nnn runs, the bar above the keyboard shows its
  actions as labelled buttons (↑, ↓, Open, Back, Select, Search, Copy,
  Move, Rename, Delete, Places, Quit), so there are no shortcuts to memorise.
- Text files open in your terminal editor (nano by default).
- Later: open other files in an Android app, share, and "open terminal
  here" in a new tab; tap support (off by default, behind a setting).

### Key bar
Two rows of labelled buttons between the terminal and the keyboard,
always visible, so the phone's own keyboard is all you need for typing
(swipe, voice, any language).
- **Follows what's running:** the shell gets Esc, Tab, a sticky Ctrl,
  arrows, Files and Menu; the file manager and the menu get their own
  actions.
- **Games:** each game gets its own controls (Neon Rogue: arrows, Wait,
  Explore, Potion, …; Neon Drive: steer, Brake, Nitro), and any other
  game a generic D-pad bar.
- **Hold to repeat:** arrows (and any button marked to) repeat while
  held, and send as soon as they're touched.
- **Pages:** if a bar has more buttons than fit, swipe sideways for the
  rest.
- **Any program or game can bring its own:** bars are plain-text files;
  `keybar NAME command` shows bar `NAME` while the command runs, and
  `play game` runs a game with the bar named after it.

### Customize everything
- **Themes:** colour schemes, fonts (Nerd Fonts supported), font size,
  cursor style.
- **Key bars:** edit the built-in bars or add your own, for any program.
- **Launcher menu:** a "mynx" start menu (Shell, AI agents, Apps, Games,
  Files, Settings, System, …) that you can edit and reorder, with items that run
  any command.
- **Editors in the terminal:** `mynx edit` (or the menu's Settings, or
  long-press the app icon → Settings, which opens them in a new tab) for
  the theme (with live preview), font and cursor, key bars and the menu.
  Every change is also a `mynx` command, so an AI agent can make it.
- **Shell:** your own dotfiles, prompt, aliases and packages.

### Profiles: share your setup
A **profile** is a complete setup: theme, font, key bars, launcher
menu, dotfiles, package list and setup scripts.
- **Several profiles** on one phone (for example "Neon", "Minimal",
  "Python dev"), with fast switching between them.
- Each profile can have its **own separate Debian environment**, or share one
  with other profiles.
- **Export** a profile as a single small file to send through any app. It
  stores the setup *recipe*, not a whole Linux system.
- **Import** a profile file to install it as a new profile. The app shows you
  exactly what it will install and run before anything runs.
- Optional full **backup/restore** of a profile's Debian environment for
  moving to a new phone.

### Android integration
Commands available inside Debian:
- notifications, vibration
- clipboard copy/paste
- share files or text to other apps, open URLs
- access to phone storage (with permission)
- **location:** GPS position, one-off or as a stream
- **camera:** take photos from the front or back camera; the flashlight
  on and off
- **sound:** play audio files and record from the microphone, and a sound
  device so Linux programs play through the phone's speaker and hear its
  microphone
- **sensors:** read accelerometer, gyroscope, compass, light, proximity
  and other sensors, one-off or as a stream

Each of these asks for Android permission the first time it's used, and you can
turn any of them off per profile.

### Built for AI coding agents
CLI agents like Claude Code are first-class citizens. An agent running in a
terminal tab should be able to understand and change your setup as easily as
you can.

**Setup docs for agents.** Every Debian environment ships with documentation
written for agents, kept in sync with the app version and the active profile:
- `AGENTS.md` and `CLAUDE.md` in your home folder, so Claude Code, Codex,
  Gemini CLI and other agents pick them up automatically.
- What the environment is (Debian under `proot` on Android) and its limits
  (no `systemd`, Docker or root kernel features), so agents don't waste time
  on things that can't work.
- Where everything lives: profile, theme, key bars, launcher menu,
  file manager settings, dotfiles.
- The file formats, with examples, and what each setting does.
- Every `mynx` command, including the Android ones (notifications, camera,
  location, sensors, clipboard, share).
- How to apply a change, check it and undo it.

**An agent-friendly setup:**
- **Plain-text config.** Everything you can change in the settings screens
  is stored in readable, commented files that an agent can edit directly.
- **A `mynx` command for everything.** Anything the app can do, a script
  can do: `mynx theme set neon`, `mynx set font-size 14`,
  `mynx profile export`, … with `--json` output for scripts and agents.
- **Live reload.** Config changes apply without restarting the app.
- **Checks before applying.** `mynx check` validates edits and
  explains mistakes, so a broken edit never breaks the app.
- **Undo.** Config is snapshotted before each change; `mynx undo` rolls
  back the last one.
- **Agent notifications.** A phone notification when an agent finishes or
  needs your input, so you can switch apps while it works.
- **One-tap install** of popular agent CLIs (Claude Code, Codex, Gemini
  CLI) through their official installers; you sign in with your own
  account.

### Development boards *(later)*
Flash and talk to boards like **Arduino** and **ESP32** over a USB-C OTG
cable, using standard tools.
- A **USB serial bridge** in the app connects to the board through Android's
  USB API and gives Debian a normal serial port (`/dev/ttyUSB0`). This covers
  CH340, CP210x, FTDI, ATmega16U2 and native-USB ESP32-S2/S3/C3 boards.
- Standard tools work against that serial port: `arduino-cli`, PlatformIO,
  `esptool`, `avrdude`, `picocom`. Toolchains (AVR, ESP32) run on arm64 inside
  Debian.
- Automatic reset into the bootloader (DTR/RTS) is passed through to the
  board; holding BOOT works as a fallback.
- **UF2 boards** (Raspberry Pi Pico, …): copy firmware to the board's USB
  drive.
- **Raw USB** devices (STM32 DFU, …) for tools that support it via `libusb`.
- **Serial monitor tab**, a "board connected" notification and a
  `mynx usb` command; covered by the agent docs, so an agent can write,
  build and flash firmware.

### Bluetooth *(later)*
Talk to Bluetooth devices from Debian, through the app. Android doesn't
let apps reach the Bluetooth hardware, so BlueZ and `bluetoothctl` can't
run; the app uses Android's Bluetooth API instead.
- **Bluetooth serial** (classic SPP): HC-05/HC-06 modules, ESP32
  `BluetoothSerial` and the like appear in Debian as a serial port, through
  the same bridge as USB serial, so `picocom` and other serial tools work.
- **Bluetooth Low Energy:** `mynx ble` scans for devices, connects, and
  reads, writes and subscribes to characteristics, with `--json` and
  streams like `mynx sensor`. Devices with the Nordic UART service can
  also appear as a serial port.
- Covered by the agent docs, so an agent can find a device and talk to it.
- Bluetooth keyboards and headphones already work through Android (the
  sound device plays through whatever Android plays to).

### Included extras
- The default **Neon** profile comes with a synthwave theme and a few terminal
  games, each with its own key bar.
- Optional one-tap install of popular tools and AI agent CLIs (see above).

---

## How it works

| Part | Approach |
|---|---|
| App | Kotlin, a single native Android app |
| Terminal | Termux's `terminal-emulator` / `terminal-view` libraries (Apache 2.0) |
| Linux | Termux's Android-patched `proot`, shipped as a native library so it can run on modern Android |
| Debian | A prebuilt arm64 rootfs, customized at build time and unpacked on first launch |
| Profiles | A recipe file (config + dotfiles + package list + scripts) applied to a Debian environment |
| Builds | GitHub Actions builds and signs the APK |

### Limitations
Because Debian runs through `proot` rather than a virtual machine:
- no `systemd`, Docker or kernel modules
- heavy work (big builds, package installs) is slower than on a PC
- arm64 devices only

---

## Distribution
- **APK downloads** from GitHub Releases, signed, built by CI from version tags.
- **Updates from the app:** it checks GitHub Releases about once a day,
  says when there's a new version (a notification and the launcher
  menu), and `mynx update` downloads and installs it, keeping Debian.
- **Google Play** later, once the app is stable.

---

## Roadmap
Steps 1-7, 9 and 10 are done; step 8 is parked.

1. **Core:** app opens a terminal into the built-in Debian.
2. **Tabs:** Windows Terminal-style tab strip, sessions, background service.
3. **Default setup:** Neon theme, fonts, launcher menu, games.
4. **File manager:** nnn set up for the phone, and the key bar above the
   keyboard.
5. **Game and program key bars:** game controls, hold to repeat, bars for
   any program or game (replaces the in-app keyboard).
6. **Customization:** theme, key bar and menu editors (terminal programs),
   and the first `mynx` commands they're built on.
7. **Agent support:** undo for config changes, agent notifications,
   one-tap install of agent CLIs. (The agent guide, the `mynx` CLI and
   config checks came with step 6.) The agent docs are updated with
   every later feature.
8. **Profiles:** multiple profiles, switching, export/import.
9. **Android integration:** notifications, clipboard, share, storage,
   location, sensors, camera and flashlight, speaker and microphone.
10. **Polish:** first-run experience, settings, icon, signed releases,
    updates from GitHub Releases.
11. **Later:**
    - optional tap support in the file manager (settings toggle)
    - development boards: USB serial bridge, flashing Arduino/ESP32/UF2
      boards, serial monitor tab
    - Bluetooth: serial (SPP) through the same bridge, BLE through
      `mynx ble`
