# mynx roadmap

What's planned. The [README](../README.md) describes what ships today;
the work log (`docs/WORKLOG.md`) has the current status and next steps.

**Done:** Debian through proot, tabs, the default setup (theme, font,
launcher menu, games), the file manager and key bars, customization
(`mynx`, the editors, undo), agent support, Android integration,
polish (first run, icon, updates from GitHub Releases), background
services, development boards (USB serial, raw USB, UF2), Bluetooth
(serial, BLE, BLE UART ports), web apps, and touch mode's Home and
Chat for Claude Code (with artifact cards and the hand-off to a tab).

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
What's left of the second way to use mynx (`view = touch`): Home, and
Chat for Claude Code with its cards and hand-off, are built.
- **Other agents in Chat:** Codex (its `app-server`) and Gemini (ACP),
  with the same artifact cards and "Open in terminal" hand-off
  (`codex resume` and the others' equivalents).
- **Chat first, with a drawer** (maintainer's decision, 2026-11-11):
  the alternative was a launcher button next to the prompt box,
  opening a sheet of Home's items from the bottom; if it's chosen
  instead, switch before touch mode's first release, while nobody has
  learned the drawer. Design: `docs/TOUCH-DESIGN.md`.
- **Later, not decided** (discussed 2026-11-11): a
  "run, open or ask" box (open an app, run a command in a tab, or
  ask the agent); a launcher mode (mynx as Android's home app, off by
  default, with Android's apps beside Debian's); themes that change
  the launcher too (wallpaper, a bar, terminal panels).
- The agent widget could open straight into a chat.

### Desktop mode
Debian's graphical programs (a desktop such as XFCE, a browser, an
IDE) on the phone, through **Termux:X11**, an X server for Android.
- A third view, `view = desktop`, beside `terminal` and `touch`, and
  switching between them like the other two.
- **Shipped like proot** (maintainer's decision, 2026-11-20):
  Termux:X11 is GPL-3.0, so it stays a separate program, built from
  its source in CI (like `scripts/build-proot.sh`), shipped as a
  native library run from `nativeLibraryDir`, credited in About
  (`COMPONENTS`) with its licence in `tools/licenses/` and its source
  linked. Nothing for users to install.
- `mynx desktop start | stop`, with a session that starts a chosen
  desktop and sets `DISPLAY` in new tabs; sound through the existing
  sound device.
- Works with an external display and a keyboard and mouse (Android's
  desktop windowing), where it's most useful.
- To check first, in a spike: whether its X server runs for an app
  that isn't Termux, from under proot, and how fast it draws; and how
  its Android side (the view that shows the screen, Java) stays
  separate too: a process of its own talking to ours, not GPL code
  in mynx's own classes.

### Extended screen
Another Android phone (or tablet) next to the first one makes the
screen bigger: one picture spread across both.
- **In desktop mode:** one desktop spans both phones placed side by
  side: its left half shows on one phone, its right half on the
  other. Windows can be dragged from one to the other and the pointer
  crosses the edge, as with two monitors on a PC. The X server draws
  one wide screen with two monitors (RandR), sized to the two
  displays, and the second phone shows its half.
- Also, outside desktop mode: the second phone shows a tab or a web
  app while the first keeps the rest. Terminal tabs travel as text
  (the terminal's output), not pictures, so they stay sharp and light.
- The second phone runs mynx (or opens a page in its browser) and
  pairs with the first over Wi-Fi (the same network or a hotspot),
  by a code or QR code; perhaps USB later.
- Typing and touch on either phone go to what it shows.
- To check: how fast the second half can be sent (compressed frames
  of the changed areas over Wi-Fi), and lining the halves up when the
  phones' screens differ in size or density.

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
- **Raw USB hotplug:** devices coming and going while a tool runs
  (libudev's monitor wants libudev-format messages from uid 0, a
  netlink address).

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
