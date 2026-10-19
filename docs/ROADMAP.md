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
- **Next: Go programs.** Static programs skip the preload library:
  `arduino-cli monitor` needs the URL, and `arduino-cli board list`
  finds nothing, since its discovery needs a USB event socket Android
  refuses apps. Both need proot to step in (the modem calls on a
  `/dev/serial` port, and a stand-in for the uevent socket).
- PlatformIO's ESP32 toolchains don't install: proot's `getdents` says
  a faked hard link is a symlink while `readlink` fails on it (a proot
  patch). arduino-cli's ESP32 core works meanwhile.
- **UF2 boards** (Raspberry Pi Pico, …): copy firmware to the board's
  drive. **Raw USB** (STM32 DFU, `picotool`, CMSIS-DAP probes for
  OpenOCD and probe-rs) through `libusb` (`libusb_wrap_sys_device` on
  the fd Android hands the app).

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
- Bluetooth headphones and speakers need nothing: the sound device
  plays through Android, which sends it to them.

### Google Play
Once the app is stable. Needs a version-code scheme that only goes up,
and a build without `REQUEST_INSTALL_PACKAGES` (and without the SMS and
call permissions, see "More of the phone").

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
