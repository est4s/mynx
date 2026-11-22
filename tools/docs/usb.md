# USB devices

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx usb [list]` | USB devices: serial ones' `/dev/serial` paths and `rfc2217://` URLs, or why they have none; others' `/dev/bus/usb` node |
| `mynx usb allow [DEVICE]` | ask Android to let the app use a USB device (the app must be on screen) |
| `mynx usb url\|path [PORT]` | one port's `rfc2217://` URL or `/dev/serial` path, for scripts: `esptool --port "$(mynx usb url)" …`; Bluetooth ports (`rfcomm0`) too |
| `mynx usb monitor [PORT] [--baud N] [--lower]` | a serial monitor on a port (a Bluetooth one too), in this tab (the `serial` app): Ctrl+T then a key resets the board, changes the baud, … |
| `mynx uf2 [list]` | the UF2 bootloader drives plugged in (a Pico in BOOTSEL, …), with the board each says it is |
| `mynx uf2 flash FILE [DEVICE]` | flash a UF2 file onto a board's bootloader drive, which Android won't mount; DEVICE is `vendor:product` or its node |
| `mynx fix-links DIR…` | repair the hard links an installer broke by moving the folder it unpacked (arduino-cli's toolchains: see "USB serial devices") |

## USB serial devices

Boards like Arduino and ESP32, and USB-to-serial adapters, plugged in
with a USB-C OTG cable or adapter, become serial ports in Debian. The
app drives them through Android (no kernel drivers here): CDC ACM
devices (Arduino Uno and Mega's ATmega16U2, Leonardo, ESP32-S2, -S3,
-C3 and -C6's own USB, Raspberry Pi Pico, STM32's virtual COM port),
and the CH340/CH341, CP210x, FTDI and PL2303 chips.

- **Allowing it:** when one is plugged in, Android asks whether to
  open mynx for it; OK lets the app use it until it's unplugged
  ("always" remembers that device). One Android didn't ask about:
  `mynx usb allow [DEVICE]`, with the app on screen; it also opens
  again one that stays at "no port yet". A notification
  says when its ports are ready, and where.
- **Each port, two ways:**
  - `/dev/serial/ttyUSB0` (`ttyACM0` for CDC ACM devices; the next
    one gets 1): a terminal device for programs that open a file:
    `picocom -b 115200 /dev/serial/ttyUSB0`, `screen`, `minicom`,
    `cat`, `stty`, `avrdude`, pyserial and the tools built on it
    (`esptool --port /dev/serial/ttyACM0 …`, miniterm, PlatformIO), a
    script. Opening it raises DTR and RTS, as on Linux (which resets
    an Arduino Uno), and closing it drops them; programs can set them
    too (esptool's reset into the bootloader). The baud rate, data
    bits, parity and stop bits follow what the program sets (9600 8N1
    until one does).
  - `rfc2217://localhost:2217` (2218 for the next port, …): the same
    port over RFC 2217, which pyserial speaks, also for programs the
    serial library can't reach (see below): `esptool --port
    "rfc2217://localhost:2217" …` (reset into the bootloader and
    back), PlatformIO (`upload_port` and `monitor_port`),
    `python3 -m serial.tools.miniterm rfc2217://localhost:2217 115200`.
  A port has one user at a time: while a program has its
  `/dev/serial` file open, its URL is busy, and the other way round.
  A program that opens the file while the URL is in use gets nothing
  until the URL's program lets go; `mynx usb` shows the path as
  "waiting", the helper's log says "busy", and `mynx usb monitor`
  says so on screen, and again when the port is free.
- **What Android doesn't allow, and the serial library:** Android
  refuses programs the modem-line calls on a terminal device
  (`TIOCMGET`, `TIOCMSET`, `TIOCMBIS`, `TIOCMBIC`, `TIOCEXCL`,
  `tcdrain`, `tcflow`) and `flock` on it ("Permission denied"). The
  app's library, `/opt/mynx/lib/libmynx-serial.so`, loaded into every
  program through `LD_PRELOAD` (the `serial-preload` setting), steps
  in only when one of those fails on a `/dev/serial` port: DTR and RTS
  go to the port's helper (its control socket is
  `/dev/serial/.NAME.ctl`), which sets them on the board; CTS, DSR and
  CD always read as on; locks and `tcdrain` succeed. Sending a break
  stays refused. Static programs, which don't load it (Go ones, such
  as `arduino-cli monitor`), get the same from proot itself, for the
  modem-line calls, `TIOCEXCL` and `tcdrain` (which doesn't wait);
  `flock` and `tcflow` stay refused for them. With `serial-preload
  off`, or `unset LD_PRELOAD`, every program gets just that:
  picocom then needs `--nolock`.
- **Finding the ports:** each port is also `/dev/ttyUSB0` or
  `/dev/ttyACM0` (a link to its `/dev/serial` file), and
  `/sys/class/tty/NAME` has its USB details as on Linux (vendor and
  product IDs, serial number, maker and name). So tools that look for
  ports find them by themselves: `python3 -m serial.tools.list_ports
  -v`, `esptool` with no `--port`, PlatformIO's auto-detect,
  `arduino-cli board list` (also `board list --watch`). Android
  doesn't give apps the kernel's USB event socket, which tools watch
  for ports coming and going: proot gives a program that opens it a
  stand-in, and the app sends it the kernel's `add` and `remove`
  messages for the ports' ttys (no others). It's the `serial-names` setting (on;
  `mynx set serial-names off`, then a new tab, turns it off): for it,
  Debian has a `/dev` of its own, which the app fills (the
  phone's `null`, `zero`, `random`, `urandom`, `tty`, `ptmx`, `pts`,
  the GPU's files, …; not its own `ttyS*` ports). With it off, or in
  a tab opened while it was off, name the port: `/dev/serial/…`.
  esptool still says "Failed to get PID of a device on /dev/pts/N"
  (it follows the link to the terminal): harmless, it uses the usual
  reset.
- **Boards held in reset while the port is open:** some boards'
  auto-reset circuit (the ESP32-CAM-MB's, for one) keeps the chip in
  reset while DTR and RTS are both on, and opening a `/dev/serial`
  file raises them, as on Linux. Lower them as on a PC:
  `python3 -m serial.tools.miniterm --dtr 0 --rts 0
  /dev/serial/ttyUSB0 115200` resets the board and lets it boot
  (PlatformIO: `monitor_dtr = 0`, `monitor_rts = 0`). `picocom
  --lower-dtr --lower-rts …` opens without resetting it; Ctrl+A
  Ctrl+G twice (RTS up, then down) resets it. A board stuck in
  "waiting for download" was reset with DTR still on: reset it that
  way.
- `mynx usb` lists the devices with their ports, paths and URLs;
  `mynx usb url` and `mynx usb path` print the only port's (or
  `PORT`'s) for scripts. `--json` gives `devices`: `id`, `ids`
  (vendor:product), `manufacturer`, `product`, `serial`, `driver`
  (null for a device no serial driver drives: see "Raw USB devices"),
  `allowed`, `error` and `ports` (`name`, `path`, `url`, `tcp_port`,
  `in_use`, and `waiting`: its file is open but the URL has the port).
- The tools: `apt install picocom` (or `minicom`, `screen`),
  `python3-serial` (pyserial, miniterm), `esptool`, `avrdude`;
  PlatformIO with `pip`, `arduino-cli` from Arduino. Debian's
  `esptool` has no flasher stub for the original ESP32, S2 and S3
  ("No such file … stub_flasher_32.json"): add `--no-stub` (slower),
  or install esptool with `pip`.
- Each port's helper (it makes the terminal device) logs to
  `/tmp/.mynx/serial/NAME.log`.
- The URL is on localhost: other apps on the phone could reach it
  too, while mynx runs and the device is plugged in.
- **Serial monitor:** `mynx usb monitor [PORT] [--baud N]` (the
  `serial` command, the menu's Apps → Terminal apps → Serial monitor) shows what the
  board sends and sends what you type, Enter as CR, at 115200 baud
  unless given. With several ports it asks which. Its key bar,
  `serial`, has Esc, Ctrl, Tab, the arrows, Ctrl+C, Ctrl+D (a
  MicroPython REPL's) and its commands, Ctrl+T then a key: `q` quit
  (Ctrl+] too), `r` reset the board (an RTS pulse for an ESP32's EN,
  then a DTR pulse for an Arduino's reset), `b` into the bootloader
  (esptool's sequence; the 1200 baud touch for Arduino's native USB
  and the Pico), `+`/`-` baud, `x` hex, `s` timestamps, `e` local
  echo, `n` line ending (CR, LF, CR LF), `D`/`R` DTR/RTS on or off,
  `h` the keys. Opening raises DTR and RTS, as on Linux; `--lower`
  drops them (DTR first: the ESP32-CAM-MB boots). When the port goes
  away (unplugged, a board restarting its USB) it waits and opens it
  again. DTR and RTS go to the port's helper, through the serial
  library or proot.
- **Recipes** (name the port: `/dev/serial/…` or its URL, from
  `mynx usb`; `mynx usb path` prints the only one's):
  - **esptool** (`apt install esptool`): `esptool --port
    /dev/serial/ttyACM0 chip_id`; flash with `esptool --port … write_flash
    0x0 firmware.bin` (`--no-stub` for the original ESP32, S2 and S3
    with Debian's esptool, see above). Then `mynx usb monitor`.
  - **MicroPython** with `mpremote` (`apt install pipx`, `pipx install
    mpremote`): `mpremote connect /dev/serial/ttyACM0 ls`, `… cp
    main.py :`, `… repl`; the URL works too. Its shortcuts (`a0`,
    `u0`, `auto`) look for `/dev/ttyACM0` and don't find it.
  - **arduino-cli** (Arduino's installer: `curl -fsSL
    https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh
    | BINDIR=~/.local/bin sh`): `arduino-cli core install arduino:avr`,
    then **`mynx fix-links ~/.arduino15`** (after every core or tool
    install: see below), `arduino-cli compile -b
    arduino:avr:nano:cpu=atmega328old Blink` and `arduino-cli upload -p
    /dev/serial/ttyUSB0 -b arduino:avr:nano:cpu=atmega328old Blink`
    (it runs avrdude, which the serial library covers);
    `arduino-cli board list` finds the board, and `arduino-cli monitor
    -p /dev/serial/ttyUSB0 -c baudrate=115200` shows its output, as
    `mynx usb monitor` does. Boards that
    upload after a 1200 baud touch (Leonardo, Micro, Pro Micro) jump
    to a bootloader that is a new USB device for about 8 s: Android
    asks for it again (tick "always" once) and it gets a port of its
    own; upload to that one with avrdude: `stty -F
    /dev/serial/ttyACM0 1200` (the touch), then `avrdude -p atmega32u4
    -c avr109 -P /dev/serial/ttyACM0 -U flash:w:Blink.ino.hex` with the
    port `mynx usb` shows then (`arduino-cli compile --output-dir .`
    writes the `.hex`).
  - **PlatformIO** (`apt install pipx`, `pipx install platformio`):
    in `platformio.ini`, `upload_port = /dev/serial/ttyUSB0` and
    `monitor_port = /dev/serial/ttyUSB0` (or the URL), since it can't
    find ports itself; `pio run -t upload`, `pio device monitor`.
    Its AVR toolchain installs and builds.
    Its ESP32 toolchains install and build (`platform = espressif32`;
    for the C3, about 6 GB in `~/.platformio` and a first build of
    about 2 minutes); a big build needs Android's process limit off
    (see "How it runs" in `/opt/mynx/AGENTS.md`). While installing, PlatformIO may say
    "Directory not empty" and "Please manually remove" for its own
    temporary folders: harmless.
  - **avrdude** (`apt install avrdude`): `avrdude -p atmega328p -c
    arduino -b 115200 -P /dev/serial/ttyUSB0 -U flash:w:sketch.hex`
    (57600 for old Nano bootloaders); opening the port resets the
    board into its bootloader.
- **`mynx fix-links DIR…`:** proot can't make hard links on Android,
  so it fakes them with symlinks that hold absolute paths (to
  `.l2s.*` files). An installer that unpacks into a temporary folder
  and then moves it breaks them: arduino-cli's toolchains fail with
  "fork/exec …/avr-g++: no such file or directory" and the files
  can't even be listed ("Operation not permitted"). `mynx fix-links
  ~/.arduino15` makes each broken name a copy of its file. `--json`
  gives `fixed` (how many, by folder).

## Raw USB devices

Tools that drive a USB device themselves, through libusb or usbfs,
work too: `picotool` for a Raspberry Pi Pico (RP2040, RP2350) in
BOOTSEL, `dfu-util` for STM32's DFU bootloader, OpenOCD and probe-rs
with a debug probe, `lsusb`, pyusb, your own libusb programs. Android
doesn't let apps open `/dev/bus/usb`, so the app opens the device
through Android and hands it to the program that opens its node.

- **Allowing it:** as for serial devices: OK on Android's "open mynx?"
  (it asks for Raspberry Pi's boards and STM32's DFU bootloader), or
  `mynx usb allow DEVICE` (`vendor:product`, as `mynx usb` shows)
  with the app on screen, for any other. `mynx usb` lists it as `raw
  USB` with its node; `--json` gives it `driver: null`.
- **Finding it:** each allowed device is in `/sys/bus/usb/devices`
  (laid out as Linux's sysfs: IDs, strings, descriptors, a folder per
  interface) with its node `/dev/bus/usb/BBB/DDD`, so tools find it
  by themselves: `lsusb`, `lsusb -v -d 2e8a:0003`, libusb's device
  list. A serial device is there too, so a tool can use its other
  interfaces (an ESP32-C3's JTAG) while its port is served. It's part
  of `serial-names` (on): Debian then has a `/sys/bus` of its own,
  with the phone's other buses (`platform`, `cpu`, …) in it.
- **A board that restarts is a new device:** after flashing,
  `picotool load -x` and `dfu-util` boards come back with a new
  address, and Android asks again; tick "always" once per board so it
  doesn't. A program that's running doesn't see devices come and go:
  run it again.
- **Recipes:**
  - **UF2 boards** (a Pico in BOOTSEL, boards with TinyUF2 or
    Adafruit's UF2 bootloader): Android sees the bootloader's drive
    but says it's damaged and doesn't mount it, so copying the file
    to it isn't possible; `mynx uf2 flash firmware.uf2` writes it to
    the drive itself, and the board starts it (MicroPython's UF2 then
    gives a serial port, `ttyACM0`). `mynx uf2` lists the drives and
    the board each says it is (its `INFO_UF2.TXT`). It only writes to
    a drive with that file, so a USB stick is never touched, and it
    refuses a file for another chip (an RP2350's on an RP2040) before
    writing. `--json` gives `label`, `board`, `model`, `blocks`,
    `bytes` and `seconds`. Boards other than Raspberry Pi's need
    `mynx usb allow vendor:product` first.
  - **Pico** (`apt install picotool`): hold BOOTSEL while plugging it
    in, then `picotool info -a`; `picotool load -x firmware.uf2`
    flashes it, checks it and starts it, as `mynx uf2 flash` does.
  - **STM32 DFU** (not tried yet; `apt install dfu-util`): `dfu-util -l`, then
    `dfu-util -a 0 -s 0x08000000:leave -D firmware.bin`.
  - **ESP32-C3's built-in JTAG** (`303a:1001`, the same device as
    its `ttyACM0`; the C6's, S3's and H2's are the same part, not
    tried yet, with their own `board/…-builtin.cfg`): debug the chip
    over its own USB, with no probe. The serial port keeps working beside it (a
    MicroPython REPL answered while the chip was halted and resumed);
    one debugger at a time ("interface is busy (errno 16)" means
    another has it). Debian's OpenOCD has no `esp_usb_jtag`, so use
    Espressif's:
    - **openocd-esp32:** the `linux-arm64` build from
      `github.com/espressif/openocd-esp32/releases` (check it against
      the release's `checksum.sha256`), unpacked anywhere:
      `openocd-esp32/bin/openocd -f board/esp32c3-builtin.cfg`
      serves GDB on port 3333 and its commands on 4444 (`halt`,
      `reg pc`, `resume`, `reset run`, `flash probe 0`).
    - **GDB** (`apt install gdb-multiarch`), with OpenOCD running:
      `gdb-multiarch -ex 'set architecture riscv:rv32' -ex 'target
      extended-remote :3333' firmware.elf` (the ESP-IDF build's ELF,
      for symbols). `monitor reset halt` from GDB.
    - **probe-rs:** the `probe-rs-tools-aarch64-unknown-linux-gnu`
      build from `github.com/probe-rs/probe-rs/releases`:
      `probe-rs list` shows `ESP JTAG`, `probe-rs info` detects the
      chip; `probe-rs reset --chip esp32c3`, `probe-rs read --chip
      esp32c3 b32 0x42000000 4`; `probe-rs run --chip esp32c3
      firmware.elf` should flash and run an esp-hal program (not
      tried yet; it replaces what's on the board).
  - **Debug probes** (not tried yet): Debian's OpenOCD (`apt install
    openocd`) with a CMSIS-DAP probe (a Pico running Raspberry Pi's
    debugprobe): `openocd -f interface/cmsis-dap.cfg -f
    target/rp2040.cfg`.
- The app's log for it: `/tmp/.mynx/serial/usb.log`.
