#!/usr/bin/env bats
# The agent guide (tools/AGENTS.md → /opt/mynx/AGENTS.md, so
# it updates with the app) must keep up with the setup it describes. Root's
# home (rootfs/root/) has short AGENTS.md / CLAUDE.md files pointing to it.

ROOT="$BATS_TEST_DIRNAME/../../rootfs/root"
GUIDE="$BATS_TEST_DIRNAME/../../tools/AGENTS.md"

@test "the home's CLAUDE.md imports its AGENTS.md and the guide" {
    grep -qx '@AGENTS.md' "$ROOT/CLAUDE.md"
    grep -qx '@/opt/mynx/AGENTS.md' "$ROOT/CLAUDE.md"
}

@test "the home's AGENTS.md points to the guide" {
    grep -qF '/opt/mynx/AGENTS.md' "$ROOT/AGENTS.md"
}

@test "the guide mentions every file shipped in the home folder" {
    missing=()
    find "$ROOT" -type f >"$BATS_TEST_TMPDIR/files"
    while IFS= read -r f; do
        path="~/${f#"$ROOT"/}"
        case $path in "~/AGENTS.md" | "~/CLAUDE.md") continue ;; esac
        grep -qF "$path" "$GUIDE" || missing+=("$path")
    done <"$BATS_TEST_TMPDIR/files"
    [ "${#missing[@]}" -eq 0 ] || { echo "not in the guide: ${missing[*]}"; false; }
}

@test "the guide mentions the other things users can change" {
    for path in \
        "~/.config/mynx/colors.properties" \
        "~/.config/mynx/settings.conf" \
        "~/.config/mynx/menu.conf" \
        "~/.config/mynx/themes" \
        "/opt/mynx/menu.conf" \
        "/opt/mynx/themes" \
        "~/.local/state/mynx/menu" \
        "~/games" \
        "~/apps" \
        "/opt/mynx/apps" \
        "~/terminal-apps" \
        "/opt/mynx/terminal-apps" \
        "/opt/mynx/bin" \
        "\`menu\`" "\`files\`" "\`keybar\`" "\`play\`" \
        "NNN_BMS" \
        "/opt/mynx/keybars" \
        "/opt/neon-games"; do
        grep -qF "$path" "$GUIDE" || { echo "not in the guide: $path"; false; }
    done
}

@test "the guide says where phone storage is and which editors exist" {
    grep -qF '`/storage/emulated/0`' "$GUIDE"
    grep -qF '`nano`' "$GUIDE"
    grep -qF '`less`' "$GUIDE"
    # The image must actually ship what the docs promise.
    grep -Eq '^ +.*\bnano\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
    grep -Eq '^ +.*\bless\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
}

@test "the guide names every built-in key bar and theme" {
    bars="$BATS_TEST_DIRNAME/../../core/src/main/resources/io/github/est4s/terminal/core/keybars"
    for f in "$bars"/*.conf; do
        name=$(basename "$f" .conf)
        grep -qF "\`$name\`" "$GUIDE" || { echo "bar not in the guide: $name"; false; }
    done
    themes="$BATS_TEST_DIRNAME/../../core/src/main/resources/io/github/est4s/terminal/core/themes"
    for f in "$themes"/*.colors.properties; do
        name=$(basename "$f" .colors.properties)
        grep -qF "\`$name\`" "$GUIDE" || { echo "theme not in the guide: $name"; false; }
    done
}

@test "the guide documents every mynx command" {
    mynx="$BATS_TEST_DIRNAME/../../tools/bin/mynx"
    commands=$(python3 "$mynx" help | sed -n '/^Commands:/,/^$/s/^  \([a-z-]*\) .*/\1/p')
    [ -n "$commands" ]
    for name in $commands; do
        grep -qF "mynx $name" "$GUIDE" || { echo "not in the guide: mynx $name"; false; }
    done
}

@test "the guide documents every setting" {
    settings="$BATS_TEST_DIRNAME/../../core/src/main/kotlin/io/github/est4s/terminal/core/Settings.kt"
    keys=$(grep -A1 'SettingDef($' "$settings" | sed -n 's/^ *"\([a-z-]*\)",.*/\1/p')
    [ "$(wc -w <<<"$keys")" -ge 17 ]
    for key in $keys; do
        grep -qF -- "- \`$key\`:" "$GUIDE" || { echo "not in the guide: $key"; false; }
    done
}

@test "the guide says what makes room for the terminal in landscape" {
    grep -qF 'one row' "$GUIDE"
    grep -qF 'tab strip hides' "$GUIDE"
}

@test "the guide says the time zone is the phone's, and how to change it" {
    grep -qF 'time zone is the phone' "$GUIDE"
    grep -qF 'export TZ=' "$GUIDE"
}

@test "the guide says Linux tools see the battery through a stand-in" {
    grep -qF '/sys/class/power_supply' "$GUIDE"
}

@test "the guide explains background services: the file, exit codes, ports, wakelock" {
    grep -qF '~/.config/mynx/services.conf' "$GUIDE"
    grep -qF '128+N' "$GUIDE"
    grep -qF 'sshd -p' "$GUIDE"
    grep -qF 'wakelock = auto' "$GUIDE"
}

@test "the guide gives wakelock's three values, auto by default" {
    grep -qF '`wakelock`: `auto` (default), `on` or `off`' "$GUIDE"
    run grep -qiE 'wakelock.*(`off` \(default\)|off by default)' "$GUIDE"
    [ "$status" -ne 0 ]
}

@test "the guide says Chat notifies as the hooks do, and holds the wakelock during a turn" {
    grep -qF "Touch view's chats notify the same way" "$GUIDE"
    grep -qF 'tapping one opens its' "$GUIDE"
    grep -qF "a turn of touch view's Chat runs" "$GUIDE"
}

@test "the guide explains service tabs: the mark, hiding, the strip and notification, Run as service" {
    grep -qF '● NAME' "$GUIDE"
    grep -qF '**Hide**, **Restart** and **Stop**' "$GUIDE"
    grep -qF '`● N`' "$GUIDE"
    grep -qF '**Services** button' "$GUIDE"
    grep -qF 'menu services' "$GUIDE"
    grep -qF '**Run as' "$GUIDE"
}

@test "the guide says how a key bar sets the volume keys" {
    grep -qF 'VolumeUp' "$GUIDE"
    grep -qF 'VolumeDown' "$GUIDE"
    grep -qF 'changes the volume as usual' "$GUIDE"
}

@test "the guide explains USB serial ports: both ways in, allowing, and what Android forbids" {
    for text in "/dev/serial/ttyUSB0" "rfc2217://localhost:2217" "mynx usb allow" \
        "one user at a time" "TIOCMSET" "libmynx-serial.so" "serial-preload" "serial-names" "/dev/ttyACM0" "list_ports" "Go ones" "/tmp/.mynx/serial/NAME.log" \
        "Ctrl+G twice" "waiting for download"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide's board recipes: each tool on a /dev/serial port, and what proot breaks" {
    for text in "esptool --port" "mpremote connect /dev/serial/" "arduino-cli upload -p" \
        "mynx fix-links ~/.arduino15" "upload_port" "avrdude -p" "1200 baud touch" \
        "ESP32 toolchains" "mynx usb monitor"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide says how to stop Android killing big builds, per Android version" {
    for text in "Disable child process restrictions" "settings_enable_monitor_phantom_procs" \
        "max_phantom_processes" "Wireless debugging" "Function not implemented"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide gives Android 13 the device_config way, which OnePlus phones allow" {
    grep -qF "Android 12, 12L and 13 (no such switch)" "$GUIDE"
    grep -qF "WRITE_SECURE_SETTINGS" "$GUIDE"
    grep -qF "Disable permission monitoring" "$GUIDE"
}

@test "the guide's PlatformIO recipe builds for the ESP32" {
    ! grep -qF "don't install here yet" "$GUIDE"
    grep -qF "Its ESP32 toolchains install and build" "$GUIDE"
}

@test "the guide says arduino-cli's Go tools work: board list finds ports, monitor talks" {
    grep -qF '`arduino-cli board list` (also `board list --watch`)' "$GUIDE"
    grep -qF 'arduino-cli monitor
    -p /dev/serial/ttyUSB0' "$GUIDE"
    ! grep -qF 'is a Go program: use' "$GUIDE"
}

@test "the guide gives serial-names on by default, and how to turn it off" {
    grep -qF '`serial-names`: `on` (default) or `off`' "$GUIDE"
    grep -qF 'mynx set serial-names off' "$GUIDE"
}

@test "the guide says to ask which kind of app, and how to build an Android one" {
    for text in "## Making an app" "ask which kind" "~/apps" "~/terminal-apps" "**An app** in HTML" \
        "apt install aapt zipalign apksigner openjdk-21-jdk-headless" \
        "https://dl.google.com/android/maven2/com/android/tools/r8/" \
        "https://dl.google.com/android/repository/platform-34-" \
        "com.android.tools.r8.D8" "apksigner sign" "versionCode" \
        "mynx open" "cleartextTrafficPermitted" "updatePeriodMillis"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide says services find commands in ~/.local/bin" {
    grep -qF 'the login PATH (with `~/.local/bin`)' "$GUIDE"
}

@test "the guide says how tools reach raw USB devices" {
    for text in "### Raw USB devices" "/dev/bus/usb/BBB/DDD" "/sys/bus/usb/devices" \
        "picotool load -x" "mynx usb allow DEVICE" "driver: null" "tick \"always\" once per board" \
        "/tmp/.mynx/serial/usb.log"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide says how to use Bluetooth serial devices" {
    for text in "### Bluetooth serial devices" "mynx bt settings" "mynx bt bind DEVICE" \
        "~/.config/mynx/bluetooth.conf" "/dev/serial/rfcomm0" "/dev/rfcomm0" "mynx usb monitor rfcomm0" \
        "as Linux's \`rfcomm bind\`" "No baud rate, DTR or RTS over Bluetooth" "\`android-bluetooth\`" \
        "app must be on screen" "Bluetooth Low Energy" "rfc2217://localhost:2317" \
        "USB ports keep 2217" "waiting longer after each failure"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide says how to use Bluetooth LE devices" {
    for text in "### Bluetooth LE devices" "mynx ble scan" "mynx ble services DEVICE" "mynx ble connect DEVICE" \
        "--no-response" "SERVICE/CHAR" "float32" "At most 5 scans in 30 s" "use \`--name\` or \`--service\`" \
        "Android 8" "no BlueZ" "\`bluetoothctl\`" "Nordic UART"; do
        grep -qF -- "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide says how a BLE UART device becomes a serial port" {
    for text in "mynx ble bind DEVICE [nusN]" "mynx ble unbind PORT\\|DEVICE" "/dev/serial/nus0" "/dev/nus0" \
        "rfc2217://localhost:2417" "nus0 = " " random" "HM-10" "MTU less 3" "list_ports" \
        "can be a port with \`mynx ble bind\`" "\`/dev/serial/rfcomm0\`, \`/dev/serial/nus0\`"; do
        grep -qF -- "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
    run grep -qF "comes later" "$GUIDE"
    [ "$status" -ne 0 ]
}

@test "the guide says how to debug an ESP32-C3 over its built-in JTAG" {
    for text in "board/esp32c3-builtin.cfg" "openocd-esp32/releases" "gdb-multiarch" \
        "riscv:rv32" "probe-rs-tools-aarch64-unknown-linux-gnu" "ESP JTAG" \
        "interface is busy (errno 16)"; do
        grep -qF "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide explains apps: the folder, app.conf, where they run, the log, served apps" {
    for text in "### Apps" "\`~/apps/NAME/\`" "\`app.conf\`" "https://NAME.mynx.invalid/" \
        "/tmp/.mynx/apps/NAME.log" "mynx app log NAME" "\`--- opened\`" "/opt/mynx/apps/sketch" \
        "**only if opening it started it**" "\`autostart = yes\`" "The phone from JavaScript" \
        "reloads by itself" "\`terminal-apps\`" "Terminal apps → Serial monitor"; do
        grep -qF -- "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
}

@test "the guide explains the phone from JavaScript: loading mynx.js, errors, the origin, permissions" {
    for text in "#### The phone from JavaScript" '<script src="https://mynx.invalid/mynx.js"></script>' \
        "for await" "s.stop()" "\`denied\`" "\`refused\`" "\`closed\`" "\`no-bridge\`" \
        "**while its own \`service\` runs**" "~/.config/mynx/app-permissions.conf" \
        "mynx app permissions NAME reset" "16 MB" "env(safe-area-inset-top)" "\`screen-awake = yes\`" \
        "mynx app shortcut NAME" "**Add to home screen…**" "/opt/mynx/apps/gadgets"; do
        grep -qF -- "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
    run grep -qF "No phone features" "$GUIDE"
    [ "$status" -ne 0 ]
}

@test "the guide's calls table names every call apps are offered, and their permissions" {
    bridge="$BATS_TEST_DIRNAME/../../core/src/main/kotlin/io/github/est4s/terminal/core/Bridge.kt"
    # Each BRIDGE_CALLS line's JS function (its first word, before "(") must be in the guide.
    while IFS= read -r js; do
        grep -qF -- "\`$js(" "$GUIDE" || grep -qF -- ".${js##*.}(" "$GUIDE" ||
            { echo "not in the guide: $js"; false; }
    done < <(sed -n '/^val BRIDGE_CALLS/,/^)/p' "$bridge" | grep -oE '"(mynx|port)\.[a-zA-Z.]+\(' |
        tr -d '"(' | sort -u)
    for word in location camera microphone clipboard notifications bluetooth serial; do
        grep -qE -- "^\| \`mynx\..*\| $word \|$" "$GUIDE" || { echo "no call needs $word in the table"; false; }
    done
}

@test "the guide says Claude Code opens its chat history in touch view, and Back goes back through it" {
    grep -qF -- "Code's chat history: New chat" "$GUIDE"
    grep -qF -- "Back goes to the history" "$GUIDE"
}

@test "the guide says chats run at once, and an idle one left is stopped" {
    grep -qF -- "Several chats can work at once" "$GUIDE"
    grep -qF -- "one left idle is" "$GUIDE"
}

@test "the guide says new chats start in auto, and a chat keeps its own mode" {
    grep -qF -- '`chat-mode`: `auto` (default)' "$GUIDE"
    grep -qF -- "A mode picked in a chat stays with that chat" "$GUIDE"
}

@test "the guide says a chat can go on in a tab, one session in one place" {
    grep -qF -- "Open in a tab moves it to a terminal tab" "$GUIDE"
}

@test "the guide says the chat shows a card for an app the agent makes" {
    grep -qF -- "the chat shows a card for it: Open (a web app)" "$GUIDE"
}

@test "the guide says a Markdown file Claude writes gets a card with Copy" {
    grep -qF -- "Copy (a Markdown file's text)" "$GUIDE"
}

@test "the guide says touch view's Settings are lists to tap" {
    grep -qF -- "Settings are lists to tap too" "$GUIDE"
}

@test "the guide says a page can take the back key first: window.androidBack" {
    grep -qF -- "window.androidBack" "$GUIDE"
    grep -qF -- "window.androidBack" "$BATS_TEST_DIRNAME/../../app/src/main/java/io/github/est4s/terminal/WebAppActivity.kt"
}

@test "the guide says an app's screen has no bar: back and recents close it, mynx app reload reloads it" {
    for text in "with no bar" "swiping it away in recents" "Reload it with \`mynx app reload NAME\`"; do
        grep -qF -- "$text" "$GUIDE" || { echo "not in the guide: $text"; false; }
    done
    for text in "slim bar" "Open in browser"; do
        ! grep -qF -- "$text" "$GUIDE" || { echo "still in the guide: $text"; false; }
    done
}
