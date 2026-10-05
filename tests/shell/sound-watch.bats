#!/usr/bin/env bats
# `sound-watch` (tools/lib/sound-watch): started by `sound-server`, it
# tells the app which programs record from Pulse, so the app opens the
# phone's microphone only while one does.

WATCH="$BATS_TEST_DIRNAME/../../tools/lib/sound-watch"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    export POCKET_SOUND_DIR="$BATS_TEST_TMPDIR/sound"
    mkdir -p "$POCKET_SOUND_DIR"
    : >"$POCKET_SOUND_DIR/native"
    COUNT="$BATS_TEST_TMPDIR/lists"
    export COUNT
}

teardown() {
    [ -z "$PULSE" ] || kill "$PULSE" 2>/dev/null || true
    [ -z "$WATCHER" ] || kill "$WATCHER" 2>/dev/null || true
}

# A fake pactl: `list` prints which listing and how many so far;
# `subscribe` prints the events in $EVENTS, a second apart, then ends.
stub_pactl() {
    cat >"$STUBS/pactl" <<'EOF'
#!/bin/sh
case "$1" in
list)
    [ "$2" = short ] && echo "1	mic	module-pipe-source.c" && echo "server $PULSE_SERVER" && exit 0
    echo x >>"$COUNT"
    echo "Source Output #$(wc -l <"$COUNT")" ;;
subscribe)
    printf '%s\n' "$EVENTS" | while read -r e; do sleep 0.3; echo "$e"; done ;;
esac
EOF
    chmod +x "$STUBS/pactl"
}

# A copy of sleep named pulseaudio, as the server the watcher follows.
pulse() {
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    cp "$(command -v sleep)" "$BATS_TEST_TMPDIR/bin/pulseaudio"
    "$BATS_TEST_TMPDIR/bin/pulseaudio" "$1" &
    PULSE=$!
}

lists() {
    [ -e "$COUNT" ] && wc -l <"$COUNT" || echo 0
}

@test "sound-watch lists the sources and recording programs once Pulse is up" {
    stub_pactl
    pulse 2
    EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 1
    [[ $(cat "$POCKET_SOUND_DIR/inputs") == *"1	mic	module-pipe-source.c"* ]]
    [[ $(cat "$POCKET_SOUND_DIR/inputs") == *"Source Output #1"* ]]
}

@test "sound-watch asks its own Pulse, whatever PULSE_SERVER the tab has" {
    stub_pactl
    pulse 2
    PULSE_SERVER=unix:/elsewhere EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 1
    [[ $(cat "$POCKET_SOUND_DIR/inputs") == *"server unix:$POCKET_SOUND_DIR/native"* ]]
}

@test "sound-watch lists them again when a recording starts or stops" {
    stub_pactl
    pulse 3
    EVENTS="Event 'new' on source-output #0
Event 'change' on sink-input #2
Event 'remove' on source-output #0" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 1.6
    [ "$(lists)" -eq 3 ]
    [[ $(cat "$POCKET_SOUND_DIR/inputs") == *"Source Output #3"* ]]
}

@test "sound-watch waits for Pulse's socket" {
    stub_pactl
    rm "$POCKET_SOUND_DIR/native"
    pulse 3
    EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 0.6
    [ "$(lists)" -eq 0 ]
    : >"$POCKET_SOUND_DIR/native"
    sleep 0.6
    [ "$(lists)" -ge 1 ]
}

@test "sound-watch clears the list and ends when Pulse has stopped" {
    stub_pactl
    pulse 1
    EVENTS="" PATH="$STUBS:$PATH" run timeout 5 "$WATCH" "$PULSE"
    [ "$status" -eq 0 ]
    [ ! -e "$POCKET_SOUND_DIR/inputs" ]
}

@test "sound-watch ends at once for a pid that isn't Pulse" {
    stub_pactl
    sleep 5 &
    other=$!
    EVENTS="" PATH="$STUBS:$PATH" run timeout 2 "$WATCH" "$other"
    kill "$other"
    [ "$status" -eq 0 ]
    [ "$(lists)" -eq 0 ]
}

@test "with the real PulseAudio, a recording program shows up and mic is the default source" {
    command -v pulseaudio >/dev/null && command -v arecord >/dev/null || skip "PulseAudio isn't installed"
    "$BATS_TEST_DIRNAME/../../tools/lib/sound-server" 2>/dev/null &
    for _ in $(seq 50); do [ -e "$POCKET_SOUND_DIR/inputs" ] && break; sleep 0.1; done
    PULSE="$(cat "$POCKET_SOUND_DIR/pid")"
    export PULSE_SERVER="unix:$POCKET_SOUND_DIR/native"
    [ "$(pactl get-default-source)" = mic ]
    timeout 2 arecord -q -f S16_LE -r 48000 -c 1 /dev/null 2>/dev/null &
    for _ in $(seq 30); do grep -q 'Source Output' "$POCKET_SOUND_DIR/inputs" && break; sleep 0.1; done
    grep -q '^1	mic	' "$POCKET_SOUND_DIR/inputs"
    grep -q 'application.process.id' "$POCKET_SOUND_DIR/inputs"
    wait %2 || true
}
