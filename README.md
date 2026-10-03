# Pocket Terminal

A standalone Android terminal app with **Debian Linux built in**. Install the
app, open it, and you're at a real Debian shell, with no root, no Termux and
no setup scripts to paste.

On top of that it ships a ready-to-use, fully customizable setup: a launcher
menu, themes, a terminal-first in-app keyboard, multiple terminals, and
**shareable profiles**, so you can send your whole setup to a friend and they
can install it with one tap.

It's also built for **AI coding agents** like Claude Code: the setup is
documented for them and fully scriptable, so an agent can customize it for
you.

> **Status:** planning. Nothing is built yet; this README describes the
> intended scope.

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
  wakelock), so long jobs survive switching apps. Open tabs are restored when
  you reopen the app.

### In-app keyboard
A keyboard drawn inside the app. It's not a system keyboard, so the rest of the
phone keeps its usual one.
- **Terminal layout:** Esc, Ctrl, Alt, Tab, arrows and symbols like
  `| ~ / - _ $` always within reach.
- **Shortcut keys:** one tap to send a command or key combo (`git status`,
  Ctrl+C, …).
- **Game mode:** D-pad and action buttons with real press and release, for
  terminal games that need hold-to-move.
- **Switch to the system keyboard** with one key whenever you want swipe typing,
  voice input or other languages.
- Layouts, key sizes, haptics and sounds are customizable.

### File manager
A terminal file manager (`pocket files`) designed for a phone screen.
Midnight Commander is powerful but built for wide desktop terminals; this
one is made for a narrow portrait screen and thumbs.
- **One pane, full width:** a single file list that fits about 40–60
  columns, instead of two cramped side-by-side panels. Long names are
  shortened in the middle so the extension stays visible.
- **Path bar** at the top always shows where you are; Backspace goes back
  up.
- **Action bar** at the bottom with the common actions (copy, move, rename,
  delete, new, search) as large labels. It changes with what's selected,
  so there are no hidden shortcuts to memorise.
- **Keyboard-driven:** arrows to move, Enter to open, Space to select,
  using the arrow keys on the in-app keyboard.
- **Tap support (later):** tap to open, long-press to select, swipe to
  scroll. It will be off by default, with a toggle in settings.
- **Multi-select** with a clear count, then copy/move/delete/zip in one go.
  Copy and move ask for the destination with the same browser.
- **Preview** text, code and images (as coloured block pixels) full-screen,
  instead of in a cramped side panel.
- **Quick places:** home, Debian root, phone storage (Downloads, Pictures,
  …) and your own bookmarks.
- **Open with:** edit in your terminal editor, open in an Android app, or
  share to another app.
- **Open terminal here** opens a new tab in the current folder;
  `pocket files .` in any terminal opens the file manager there.
- **Search** by name, sort, filter, show/hide hidden files.
- Uses your profile's theme and colours.

### Customize everything
- **Themes:** colour schemes, fonts (Nerd Fonts supported), font size,
  cursor style.
- **Keyboard:** edit layouts and shortcut keys, or create your own.
- **Launcher menu:** a "Pocket Terminal" start menu (Terminal, Files, Games,
  System, …) that you can edit, reorder or turn off.
- **Shell:** your own dotfiles, prompt, aliases and packages.

### Profiles: share your setup
A **profile** is a complete setup: theme, font, keyboard layouts, launcher
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
- **camera:** take photos from the front or back camera
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
- Where everything lives: profile, theme, keyboard layouts, launcher menu,
  file manager settings, dotfiles.
- The file formats, with examples, and what each setting does.
- Every `pocket` command, including the Android ones (notifications, camera,
  location, sensors, clipboard, share).
- How to apply a change, check it and undo it.

**An agent-friendly setup:**
- **Plain-text config.** Everything you can change in the settings screens
  is stored in readable, commented files that an agent can edit directly.
- **A `pocket` command for everything.** Anything the app can do, a script
  can do: `pocket theme set neon`, `pocket profile export`, `pocket keyboard
  reload`, … with `--json` output for scripts and agents.
- **Live reload.** Config changes apply without restarting the app.
- **Checks before applying.** `pocket config check` validates edits and
  explains mistakes, so a broken edit never breaks the app.
- **Undo.** Config is snapshotted before each change; `pocket undo` rolls
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
  `pocket usb` command; covered by the agent docs, so an agent can write,
  build and flash firmware.

### Included extras
- The default **Neon** profile comes with a synthwave theme and a few terminal
  games, all playable with the in-app keyboard.
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
- **APK downloads** from GitHub Releases.
- **Google Play** later, once the app is stable.

---

## Roadmap
1. **Core:** app opens a terminal into the built-in Debian.
2. **Tabs:** Windows Terminal-style tab strip, sessions, background service.
3. **Default setup:** Neon theme, fonts, launcher menu, games.
4. **File manager:** phone-sized, keyboard-driven terminal file manager.
5. **In-app keyboard:** terminal layout, shortcuts, game mode.
6. **Customization:** theme, keyboard and menu editors.
7. **Agent support:** `AGENTS.md`/`CLAUDE.md`, `pocket` CLI, config check
   and undo, agent notifications. The agent docs are updated with every
   later feature.
8. **Profiles:** multiple profiles, switching, export/import.
9. **Android integration:** notifications, clipboard, share, storage,
   location, camera, sensors.
10. **Polish:** first-run experience, settings, icon, signed releases.
11. **Later:**
    - optional tap support in the file manager (settings toggle)
    - development boards: USB serial bridge, flashing Arduino/ESP32/UF2
      boards, serial monitor tab

---

## License
To be decided. Bundled third-party components keep their own licenses
(`proot` is GPL-2.0; its source will be linked from the app).
