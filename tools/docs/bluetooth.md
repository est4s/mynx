# Bluetooth

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx bt [list]` | Bluetooth: the paired devices, and each bound port's `/dev/serial` path, `rfc2217://` URL and whether it's linked |
| `mynx bt bind DEVICE [PORT]` | make a paired device (its name or address) a serial port, `rfcomm0` unless PORT names another; saved in `bluetooth.conf` |
| `mynx bt unbind PORT\|DEVICE` | remove a port (or a device's ports) and its line in `bluetooth.conf` |
| `mynx bt settings` | open Android's Bluetooth settings, to pair a device |
| `mynx ble scan [--seconds N] [--name TEXT] [--service UUID] [--all]` | the Bluetooth LE devices around: address, signal, name, services; `--stream` until Ctrl+C |
| `mynx ble services DEVICE` | a device's services and characteristics, with their properties |
| `mynx ble read DEVICE CHAR [--as FORMAT]` | read a characteristic's value |
| `mynx ble write DEVICE CHAR VALUE [--as FORMAT] [--no-response]` | write a characteristic |
| `mynx ble notify DEVICE CHAR [--as FORMAT]` | print each value a characteristic sends, until Ctrl+C |
| `mynx ble connect DEVICE` | hold a link to a device until Ctrl+C; other `mynx ble` commands on it use it |
| `mynx ble bind DEVICE [nusN]` | make a Nordic UART or HM-10 device a serial port, `nus0` unless nusN names another; saved in `bluetooth.conf` |
| `mynx ble unbind PORT\|DEVICE` | remove a `nus` port (or a device's) and its line in `bluetooth.conf` |

## Bluetooth serial devices

Boards and modules with Classic Bluetooth's serial port profile
(SPP): an original ESP32 running `BluetoothSerial`, an HC-05 or
HC-06 module, a GPS or OBD-II adapter. Each paired device you bind
becomes a serial port in Debian, like a USB one.

- **Pairing** stays in Android's settings: `mynx bt settings` opens
  them (Settings → Connected devices). `mynx bt` lists the paired
  devices, each with its port once it's bound.
- **Binding:** `mynx bt bind DEVICE`, with the device's name or
  address (`mynx bt bind mynx-spp`, `mynx bt bind
  D4:E9:F4:A2:4A:02`), makes it `rfcomm0` (the next one `rfcomm1`;
  `mynx bt bind DEVICE rfcomm3` picks the name). It prints the port's
  path and URL. The binding is a line in `~/.config/mynx/bluetooth.conf`
  (`rfcomm0 = D4:E9:F4:A2:4A:02`), so it stays after a restart, `mynx
  check` checks it and `mynx undo` takes it back. `mynx bt unbind
  rfcomm0` (or the device) removes it.
- **The port** works like a USB one (see "USB serial devices" in `/opt/mynx/docs/usb.md`):
  `/dev/serial/rfcomm0`, also `/dev/rfcomm0` for tools that look for
  ports (pyserial's `list_ports`), and `rfc2217://localhost:2317`
  (2318 for the next, …; USB ports keep 2217 and up). `picocom -b
  115200 /dev/serial/rfcomm0`, `python3 -m serial.tools.miniterm
  rfc2217://localhost:2317`, pyserial, `mynx usb monitor rfcomm0`;
  `mynx usb url rfcomm0` and `mynx usb path rfcomm0` print them.
- **The link** opens when a program opens the port, and closes when
  the last one leaves, as Linux's `rfcomm bind`: the phone and the
  board aren't connected while nothing uses it, and nothing
  reconnects in the background. When a link fails or drops, a program
  on the URL sees the port close; one with the path open gets nothing
  until the link opens again, which is tried again while it keeps the
  port open, waiting longer after each failure (up to 30 s). `mynx bt`
  says why. It shows each port as `linked`,
  `not linked` or the last error, and `in use` and `waiting` as `mynx
  usb` does.
- **No baud rate, DTR or RTS over Bluetooth:** SPP has none, so what
  programs set is accepted and changes nothing (the board's own baud
  rate is its business). So no resets: esptool can't put a board into
  its bootloader this way. Flash over USB; the monitor's reset and
  bootloader keys say they do nothing here.
- **Permission:** the first use asks for Android's Bluetooth
  permission ("Nearby devices"), so the app must be on screen.
  Bluetooth turned off, the permission refused, or `android-bluetooth
  off` each refuse with the reason; turn Bluetooth on in Android
  (the app can't).
- Bluetooth Low Energy devices (most watches, sensors, BLE UART
  modules like the HM-10) have no SPP: `mynx bt` marks them
  "Bluetooth LE only", and `mynx ble` talks to them (see "Bluetooth
  LE devices"). A BLE UART module like the HM-10
  can be a port with `mynx ble bind`: `nus0`, listed by `mynx bt`
  with the `rfcomm` ones.

## Bluetooth LE devices

Sensors, watches, heart rate straps, beacons, and boards running a
GATT server (an ESP32's NimBLE or MicroPython's `bluetooth` module):
`mynx ble` finds them, reads and writes their characteristics, and
prints what they send. Android keeps the Bluetooth controller to
itself, so Debian has no BlueZ: `bluetoothctl`, `gatttool`, `bleak`
and other tools built on BlueZ don't work here. `mynx ble` (with
`--json` for scripts) is the way in, as `mynx sensor` is for the
sensors.

- **Finding devices:** `mynx ble scan` listens for 5 s
  (`--seconds N`, 1 to 60) and lists what it heard, strongest
  first: address, signal (dBm), name, the services it advertises
  (`180f`: 16-bit UUIDs are shown short) and maker data (company ID
  and bytes). Devices with no name and no services are left out
  unless `--all`. `--name TEXT` keeps the names containing TEXT (any
  case), `--service UUID` those advertising a service. `--stream`
  prints each device as it's heard (at most once a second each),
  until Ctrl+C. `--json` gives `devices`: `address`,
  `address_type`, `name`, `rssi`, `services`, `manufacturer`
  (`company`, `hex`), `connectable`, `time`; with `--stream`, one
  object per line.
- **DEVICE** is an address (`D4:E9:F4:A2:4A:06`, any case) or a
  name: a paired device's (`mynx bt` lists them) or one the last scan
  heard, exact, else in any case. Pairing stays in Android's
  settings (`mynx bt settings`); a device that needs it makes
  Android ask on its own.
- **The table:** `mynx ble services DEVICE` connects and shows the
  services, each with its characteristics, their properties (`read`,
  `write`, `write-no-response`, `notify`, `indicate`) and known
  names (`battery-level`, `nus-rx`, …). `--json` gives `device`,
  `mtu` and `services` (`uuid`, `name`, `characteristics`: `uuid`,
  `name`, `properties`, `descriptors`).
- **CHAR** is a full UUID, a 16-bit one (`2a19`), a known name
  (`battery-level`), or `SERVICE/CHAR` when two services have it.
- **Values:** `--as FORMAT` says how to show or write the bytes:
  `hex` (`0a 1b`), `text` (UTF-8), `u8`, `u16`, `u32`, `i8`, `i16`,
  `i32` (little-endian, as GATT has them), `float32`. Reading and
  notify show `hex` unless the characteristic is a known one with its
  own (`battery-level` is `u8`); other formats come with the bytes in
  hex too. A write takes `hex` unless given; `0a1b` and `0a 1b` both
  work.
  - `mynx ble read mynx-ble battery-level` prints `87 (hex 57)`.
    `--json` gives `device`, `char`, `name`, `as`, `value` (a number
    for the number formats) and `hex`.
  - `mynx ble write mynx-ble nus-rx 'hello' --as text` writes it
    and says how many bytes; `--no-response` writes without waiting
    for the device's answer, for characteristics with
    `write-no-response`. A value longer than the link's MTU less 3
    is refused with the limit.
  - `mynx ble notify mynx-ble nus-tx --as text` subscribes (notify,
    else indicate) and prints each value with its time, until Ctrl+C;
    when the link drops, it ends with the reason. `--json`: one
    object per line (`char`, `name`, `as`, `value`, `hex`, `time`).
- **Links:** each command connects, does its job and disconnects,
  so a few seconds each. `mynx ble connect DEVICE` in another tab
  holds a link until Ctrl+C, printing when it's connected (with the
  MTU) and when it drops; while it holds one, the other `mynx ble`
  commands on that device use it, and are quick. Nothing stays
  connected in the background.
- **Android's limits:**
  - At most 5 scans in 30 s: Android silently finds nothing after
    that, so the 6th is refused, saying how long to wait.
  - With the screen off or the app in the background, Android only
    finds devices a scan filters for: use `--name` or `--service`.
  - **Permission:** the first use asks for Android's "Nearby
    devices" permission, so the app must be on screen. On Android 8
    to 11, scanning needs location instead: allowed, and turned on
    in the phone's settings, or scans find nothing. Bluetooth turned
    off, the permission refused, or `android-bluetooth off` each
    refuse with the reason.
- **Serial port:** a device with a UART service, Nordic UART
  (`nus-rx`, `nus-tx`) or an HM-10 module's (`ffe0`, `ffe1`), can be
  a serial port, as a Classic one is `rfcomm0` (see "Bluetooth
  serial devices"). `mynx ble bind DEVICE` connects once to check it
  has one, then makes it `nus0` (the next one `nus1`; `mynx ble bind
  DEVICE nus3` picks the name) and prints its path and URL:
  `/dev/serial/nus0`, also `/dev/nus0`, and
  `rfc2217://localhost:2417` (2418 for the next, …). `mynx usb
  monitor nus0`, picocom, pyserial and `mynx usb url nus0` work as
  on `rfcomm0`.
  - The binding is a line in `bluetooth.conf`, `nus0 = ADDRESS`
    (with ` random` after it for a device whose address is random),
    so it stays after a restart; `mynx check` checks it and `mynx
    undo` takes it back. `mynx ble unbind nus0` (or the device)
    removes it. `mynx bt` lists the port, marked "BLE UART".
  - The link opens when a program opens the port and closes when the
    last one leaves, as for `rfcomm` ports; a link that fails is
    tried again after a wait.
  - Data goes in chunks of the link's MTU less 3 bytes, one after
    another, so it's slower than USB.
  - No baud rate, DTR or RTS: what programs set changes nothing, and
    the monitor's reset keys do nothing.
  - pyserial's `list_ports` doesn't list it: name the path.
