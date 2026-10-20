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
