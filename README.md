# Pocket Terminal

A standalone Android terminal app with **Debian Linux built in**. Install the
app, open it, and you're at a real Debian shell, with no root, no Termux and
no setup scripts to paste.

On top of that it ships a ready-to-use, fully customizable setup: a launcher
menu, themes, a terminal-first in-app keyboard, multiple terminals, and
**shareable profiles**, so you can send your whole setup to a friend and they
can install it with one tap.

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

### Multiple terminals
- Run as many terminal sessions as you like, side by side.
- Switch with tabs, a swipe gesture or a session drawer; rename and close
  sessions.
- Sessions keep running in the background (foreground service with an
  optional wakelock), so long jobs survive switching apps.
- Open a new session in the current profile or a different one.

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

### Included extras
- The default **Neon** profile comes with a synthwave theme and a few terminal
  games, all playable with the in-app keyboard.
- Optional one-tap install of popular tools, such as Claude Code (installed
  through its official installer; you sign in with your own account).

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
2. **Multiple terminals:** sessions, tabs, background service.
3. **Default setup:** Neon theme, fonts, launcher menu, games.
4. **In-app keyboard:** terminal layout, shortcuts, game mode.
5. **Customization:** theme, keyboard and menu editors.
6. **Profiles:** multiple profiles, switching, export/import.
7. **Android integration:** notifications, clipboard, share, storage.
8. **Polish:** first-run experience, settings, icon, signed releases.

---

## License
To be decided. Bundled third-party components keep their own licenses
(`proot` is GPL-2.0; its source will be linked from the app).
