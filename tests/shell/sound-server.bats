#!/usr/bin/env bats
# `sound-server` (tools/lib/sound-server): the app runs it at start to
# give Debian a sound device, PulseAudio playing into a pipe it reads.

SERVER="$BATS_TEST_DIRNAME/../../tools/lib/sound-server"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    export MYNX_SOUND_DIR="$BATS_TEST_TMPDIR/sound"
}

teardown() {
    [ -z "$OLD" ] || kill "$OLD" 2>/dev/null || true
}

stub_pulseaudio() {
    printf '#!/bin/sh\nprintf "%%s\\n" "$@"\necho "pid=$$"\n' >"$STUBS/pulseaudio"
    chmod +x "$STUBS/pulseaudio"
}

@test "sound-server says so when PulseAudio isn't installed" {
    PATH="$STUBS" run "$SERVER"
    [ "$status" -eq 3 ]
}

@test "sound-server runs PulseAudio in the foreground without its own config" {
    stub_pulseaudio
    PATH="$STUBS:$PATH" run "$SERVER"
    [ "$status" -eq 0 ]
    [[ $'\n'"$output"$'\n' == *$'\n-n\n'* ]]
    [[ $output == *"--daemonize=no"* ]]
    [[ $output == *"--exit-idle-time=-1"* ]]
}

@test "sound-server plays into the pipe and listens on the socket" {
    stub_pulseaudio
    PATH="$STUBS:$PATH" run "$SERVER"
    [[ $output == *"module-pipe-sink file=$MYNX_SOUND_DIR/out format=s16le rate=48000 channels=2"* ]]
    [[ $output == *"module-native-protocol-unix auth-anonymous=1 socket=$MYNX_SOUND_DIR/native"* ]]
    [ -d "$MYNX_SOUND_DIR" ]
}

@test "sound-server leaves Pulse's pid in the pid file, for the app to stop it" {
    stub_pulseaudio
    PATH="$STUBS:$PATH" run "$SERVER"
    [ "${lines[-1]}" = "pid=$(cat "$MYNX_SOUND_DIR/pid")" ]
}

# Ended: the test shell hasn't reaped it, so a zombie counts.
gone() {
    [ ! -e "/proc/$1" ] || grep -q '^State:.*Z' "/proc/$1/status"
}

# A copy of sleep named pulseaudio, as an older server left running.
old_server() {
    mkdir -p "$BATS_TEST_TMPDIR/old" "$MYNX_SOUND_DIR"
    cp "$(command -v sleep)" "$BATS_TEST_TMPDIR/old/$1"
    "$BATS_TEST_TMPDIR/old/$1" 30 &
    OLD=$!
    echo $! >"$MYNX_SOUND_DIR/pid"
}

@test "sound-server stops an older Pulse left running first" {
    stub_pulseaudio
    old_server pulseaudio
    old=$(cat "$MYNX_SOUND_DIR/pid")
    PATH="$STUBS:$PATH" run "$SERVER"
    [ "$status" -eq 0 ]
    gone "$old"
}

@test "sound-server leaves alone another program with the old pid" {
    stub_pulseaudio
    old_server not-pulse
    old=$(cat "$MYNX_SOUND_DIR/pid")
    PATH="$STUBS:$PATH" run "$SERVER"
    kill -0 "$old"
}

@test "sound-server records from a pipe the app writes the microphone into" {
    stub_pulseaudio
    PATH="$STUBS:$PATH" run "$SERVER"
    [[ $output == *"module-pipe-source file=$MYNX_SOUND_DIR/in format=s16le rate=48000 channels=1 source_name=mic"* ]]
}

@test "sound-server starts the watcher with Pulse's pid" {
    stub_pulseaudio
    lib="$BATS_TEST_TMPDIR/lib"
    mkdir -p "$lib"
    cp "$SERVER" "$lib/sound-server"
    printf '#!/bin/sh\necho "$1" >"%s"\n' "$BATS_TEST_TMPDIR/watched" >"$lib/sound-watch"
    chmod +x "$lib/sound-watch"
    PATH="$STUBS:$PATH" run "$lib/sound-server"
    sleep 0.3
    [ "${lines[-1]}" = "pid=$(cat "$BATS_TEST_TMPDIR/watched")" ]
}
