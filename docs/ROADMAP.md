# mynx roadmap

What's planned. The [README](../README.md) describes what ships today;
the work log (`docs/WORKLOG.md`) has the current status and next steps.

**Done:** Debian through proot, tabs, the default setup (theme, font,
launcher menu, games), the file manager and key bars, customization
(`mynx`, the editors, undo), agent support, Android integration, and
polish (first run, icon, updates from GitHub Releases).

---

## Next

- **First release, v0.1.0:** signed APKs on GitHub Releases, built by CI
  from version tags.
- **What's new and release notes:** after every update, a page with the
  notes of the versions since the one before; "Release notes" in the
  menu, working offline.

## Planned

### Guided GitHub setup
`mynx github` (and a menu item), optional and never part of the first
run: installs `git` and `gh`, signs in through the browser (the
one-time code copied to the clipboard), sets git's name and GitHub's
noreply email, and ends with a summary. mynx never stores a token. With
`gh` signed in, `mynx report` files bug reports itself.

### Background services
Hidden terminal tabs for servers and daemons (a web server, `sshd`, a
database, a bot), since there's no init to run them:
- Start a command in a tab that doesn't show in the strip; it keeps
  running in the background like any tab.
- A list to see them, open one to read its output or type into it,
  stop or restart it.
- Optionally start them when the app starts, and restart one that
  exits.

### Profiles *(parked)*
A **profile** is a complete setup: theme, font, key bars, launcher menu,
dotfiles, package list and setup script.
- Several profiles on one phone ("Neon", "Minimal", "Python dev"), a
  profile per tab and a default one for new tabs.
- **Export** a profile as one small, readable text file (the setup
  *recipe*, not a whole Linux system); **import** shows exactly what it
  will install and run before anything runs.
- Later: a separate Debian per profile, and full backup/restore of an
  environment.

The design and the parts are in the work log ("Parked: roadmap step 8").

### Tab strip
- Tabs coloured by profile, or a colour for a single tab.
- **⌄** next to **+**: open a tab in any profile.
- Drag to reorder, a tab overview, Duplicate.

### File manager
- Open other files in an Android app, share, "open terminal here" in a
  new tab.
- Tap support, off by default behind a setting (the app is
  keyboard-first).

### Android integration
- Turn each phone permission on or off per profile (today: one
  `android-*` setting each).

### Development boards
Flash and talk to boards like **Arduino** and **ESP32** over a USB-C OTG
cable, using standard tools.
- A **USB serial bridge** in the app: Android's USB API to a normal serial
  port in Debian (`/dev/ttyUSB0`). CH340, CP210x, FTDI, ATmega16U2 and
  native-USB ESP32-S2/S3/C3 boards.
- `arduino-cli`, PlatformIO, `esptool`, `avrdude`, `picocom` against that
  port; AVR and ESP32 toolchains run on arm64.
- Bootloader reset (DTR/RTS) passed through; holding BOOT as a fallback.
  A pseudo-terminal can't carry DTR/RTS, so either serve RFC 2217 on
  localhost (pyserial and `esptool` take `rfc2217://`) or have proot
  catch the modem ioctls on that port.
- **UF2 boards** (Raspberry Pi Pico, …): copy firmware to the board's
  drive. **Raw USB** (STM32 DFU, …) through `libusb`
  (`libusb_wrap_sys_device` on the fd Android hands the app).
- A serial monitor tab, a "board connected" notification, `mynx usb`,
  and the agent guide, so an agent can write, build and flash firmware.

### Bluetooth
Android doesn't let apps reach the Bluetooth hardware, so BlueZ can't
run; the app uses Android's Bluetooth API. Build the USB serial bridge
first.
- **Bluetooth serial** (classic SPP): HC-05/HC-06, ESP32
  `BluetoothSerial`, … as a serial port in Debian, through the same
  bridge.
- **BLE:** `mynx ble` scans, connects, reads, writes and subscribes, with
  `--json` and streams like `mynx sensor`. Nordic UART devices can also
  be a serial port.

### Google Play
Once the app is stable. Needs a version-code scheme that only goes up,
and a build without `REQUEST_INSTALL_PACKAGES`.

---

## How it works

| Part | Approach |
|---|---|
| App | Kotlin, a single native Android app |
| Terminal | Termux's `terminal-emulator` / `terminal-view` libraries (Apache 2.0) |
| Linux | Termux's Android-patched `proot`, shipped as a native library so it can run on modern Android |
| Debian | A prebuilt arm64 rootfs, customized at build time and unpacked on first launch |
| Profiles | A recipe file (config + dotfiles + package list + script) applied to a Debian environment |
| Builds | GitHub Actions builds and signs the APK |

### Limitations
Because Debian runs through `proot` rather than a virtual machine:
- no `systemd`, Docker or kernel modules
- heavy work (big builds, package installs) is slower than on a PC
- arm64 devices only
