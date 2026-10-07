#!/usr/bin/env bats
# `sound-watch` (tools/lib/sound-watch): started by `sound-server`, it
# tells the app which programs record from Pulse, so the app opens the
# phone's microphone only while one does.

WATCH="$BATS_TEST_DIRNAME/../../tools/lib/sound-watch"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    export PC26_SOUND_DIR="$BATS_TEST_TMPDIR/sound"
    mkdir -p "$PC26_SOUND_DIR"
    : >"$PC26_SOUND_DIR/native"
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

# A fake pactl whose `subscribe` takes a second to connect, as on the
# phone, and then only reports what happens after: a client connecting
# (`stat`). `list` shows a recording once $RECORDING exists.
stub_pactl_connecting() {
    export RECORDING="$BATS_TEST_TMPDIR/recording" CLIENTS="$BATS_TEST_TMPDIR/clients"
    : >"$CLIENTS"
    cat >"$STUBS/pactl" <<'EOF'
#!/bin/sh
case "$1" in
list)
    [ "$2" = short ] && echo "1	mic	module-pipe-source.c" && exit 0
    if [ -e "$RECORDING" ]; then echo "Source Output #0"; fi ;;
stat) echo x >>"$CLIENTS" ;;
subscribe)
    sleep 1
    seen=$(wc -l <"$CLIENTS")
    for _ in $(seq 40); do
        sleep 0.1
        now=$(wc -l <"$CLIENTS")
        [ "$now" -gt "$seen" ] && echo "Event 'new' on client #$now"
        seen=$now
    done ;;
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

# The first listing: up to 5 s, as the phone can be slow.
wait_inputs() {
    for _ in $(seq 50); do [ -e "$PC26_SOUND_DIR/inputs" ] && return; sleep 0.1; done
}

lists() {
    [ -e "$COUNT" ] && wc -l <"$COUNT" || echo 0
}

# sound-server starts the watcher with its own pid, then execs Pulse:
# a busy phone can run the watcher's first check before that exec.
@test "sound-watch waits for its server to become Pulse" {
    stub_pactl
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    cp "$(command -v sleep)" "$BATS_TEST_TMPDIR/bin/pulseaudio"
    printf '#!/bin/sh\nsleep 0.5\nexec "%s" 3\n' "$BATS_TEST_TMPDIR/bin/pulseaudio" >"$BATS_TEST_TMPDIR/bin/server"
    chmod +x "$BATS_TEST_TMPDIR/bin/server"
    "$BATS_TEST_TMPDIR/bin/server" &
    PULSE=$!
    EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    wait_inputs
    [[ $(cat "$PC26_SOUND_DIR/inputs") == *"1	mic	module-pipe-source.c"* ]]
}

@test "sound-watch lists the sources and recording programs once Pulse is up" {
    stub_pactl
    pulse 2
    EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    wait_inputs
    [[ $(cat "$PC26_SOUND_DIR/inputs") == *"1	mic	module-pipe-source.c"* ]]
    [[ $(cat "$PC26_SOUND_DIR/inputs") == *"Source Output #"* ]]
}

@test "sound-watch asks its own Pulse, whatever PULSE_SERVER the tab has" {
    stub_pactl
    pulse 2
    PULSE_SERVER=unix:/elsewhere EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 1
    [[ $(cat "$PC26_SOUND_DIR/inputs") == *"server unix:$PC26_SOUND_DIR/native"* ]]
}

@test "sound-watch lists them again when a recording starts or stops" {
    stub_pactl
    pulse 10
    EVENTS="Event 'new' on source-output #0
Event 'change' on sink-input #2
Event 'remove' on source-output #0" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    for _ in $(seq 50); do [ "$(lists)" -ge 3 ] && break; sleep 0.1; done
    sleep 0.5
    [ "$(lists)" -eq 3 ]
    [[ $(cat "$PC26_SOUND_DIR/inputs") == *"Source Output #3"* ]]
}

# `pocket sound start` returns once Pulse answers, and a recording
# started right then came before the watcher's subscription: nothing
# listed it until the next source event, ~5 s later.
@test "sound-watch sees a recording that starts while it subscribes" {
    stub_pactl_connecting
    pulse 10
    PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    for _ in $(seq 30); do [ -e "$PC26_SOUND_DIR/inputs" ] && break; sleep 0.1; done
    : >"$RECORDING"
    for _ in $(seq 50); do grep -q 'Source Output #0' "$PC26_SOUND_DIR/inputs" && break; sleep 0.1; done
    grep -q 'Source Output #0' "$PC26_SOUND_DIR/inputs"
}

@test "sound-watch waits for Pulse's socket" {
    stub_pactl
    rm "$PC26_SOUND_DIR/native"
    pulse 3
    EVENTS="" PATH="$STUBS:$PATH" "$WATCH" "$PULSE" &
    WATCHER=$!
    sleep 0.6
    [ "$(lists)" -eq 0 ]
    : >"$PC26_SOUND_DIR/native"
    for _ in $(seq 50); do [ "$(lists)" -ge 1 ] && break; sleep 0.1; done
    [ "$(lists)" -ge 1 ]
}

@test "sound-watch clears the list and ends when Pulse has stopped" {
    stub_pactl
    pulse 1
    EVENTS="" PATH="$STUBS:$PATH" run timeout 5 "$WATCH" "$PULSE"
    [ "$status" -eq 0 ]
    [ ! -e "$PC26_SOUND_DIR/inputs" ]
}

@test "sound-watch gives up on a pid that doesn't become Pulse" {
    stub_pactl
    sleep 20 &
    other=$!
    EVENTS="" PATH="$STUBS:$PATH" run timeout 15 "$WATCH" "$other"
    kill "$other"
    [ "$status" -eq 0 ]
    [ "$(lists)" -eq 0 ]
}

@test "with the real PulseAudio, a recording program shows up and mic is the default source" {
    command -v pulseaudio >/dev/null && command -v arecord >/dev/null || skip "PulseAudio isn't installed"
    "$BATS_TEST_DIRNAME/../../tools/lib/sound-server" 2>/dev/null &
    for _ in $(seq 50); do [ -e "$PC26_SOUND_DIR/inputs" ] && break; sleep 0.1; done
    PULSE="$(cat "$PC26_SOUND_DIR/pid")"
    export PULSE_SERVER="unix:$PC26_SOUND_DIR/native"
    [ "$(pactl get-default-source)" = mic ]
    timeout 2 arecord -q -f S16_LE -r 48000 -c 1 /dev/null 2>/dev/null &
    for _ in $(seq 30); do grep -q 'Source Output' "$PC26_SOUND_DIR/inputs" && break; sleep 0.1; done
    grep -q '^1	mic	' "$PC26_SOUND_DIR/inputs"
    grep -q 'application.process.id' "$PC26_SOUND_DIR/inputs"
    wait %2 || true
}
