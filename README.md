# PC-26

An Android terminal app with **Debian Linux built in**. Install it, open
it, and you're at a real Debian shell: no root, no Termux, no setup
scripts.

> **Status:** in development, not released yet.

## What you get

- **Debian 13 (arm64)** through `proot`. `apt install` whatever you
  need; your files and packages survive app updates.
- **Tabs** that keep running in the background and come back when you
  reopen the app. Swipe sideways to switch.
- **A key bar** above the phone's keyboard (Esc, Tab, Ctrl, arrows, …)
  that changes with what's running: the file manager, games, AI agents
  and your own programs can each have their own.
- **A ready setup:** a launcher menu, a file manager
  ([nnn](https://github.com/jarun/nnn)), ten themes, a Nerd Font, the
  starship prompt and a few terminal games.
- **Example apps** that use the phone: a compass, a spirit level, a
  flashlight, a sound spectrum, a sound meter, a guitar tuner and a
  metronome, in Python, to run, read and copy.
- **Plain-text config** in `~/.config/pc26/`, changed with the `pocket`
  command or its editors (`pocket edit`), checked before it applies,
  with undo.
- **The phone from the shell:** notifications, clipboard, share,
  camera and flashlight, location, sensors, and a sound device, so
  Linux programs play through the speaker and record from the
  microphone.
- **Built for AI agents:** one-tap install of Claude Code, Codex or
  Gemini CLI (official installers, your own account), a guide in
  `~/AGENTS.md` so they can change your setup for you, and a phone
  notification when they finish or need you.

## Install

Needs an **arm64** phone with **Android 8** or newer. Download the APK
from [Releases](../../releases) and open it. The first launch unpacks
Debian; after that the app opens straight into the menu.

## Limitations

Debian runs through `proot`, not a virtual machine: no `systemd`,
Docker or kernel modules, and heavy builds are slower than on a PC.

## Roadmap

Next: shareable setup profiles, and flashing Arduino/ESP32 boards over
USB. See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## License

PC-26 is free software under the **GNU General Public License,
version 3** (GPL-3.0-only): see [`LICENSE`](LICENSE). If you distribute
a changed version, publish its full source under the same licence, and
please give it its own name and icon.

Bundled components keep their own licences: `proot` (GPL-2.0-or-later,
run as a separate program) with `talloc` (LGPL-3.0-or-later), Termux's
terminal libraries (Apache-2.0), JetBrains Mono Nerd Font (OFL-1.1) and
Debian's packages. `pocket about` lists them with their sources.
