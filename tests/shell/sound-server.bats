#!/usr/bin/env bats
# `sound-server` (tools/lib/sound-server): the app runs it at start to
# give Debian a sound device, PulseAudio playing into a pipe it reads.

SERVER="$BATS_TEST_DIRNAME/../../tools/lib/sound-server"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    export POCKET_SOUND_DIR="$BATS_TEST_TMPDIR/sound"
}

stub_pulseaudio() {
    printf '#!/bin/sh\nprintf "%%s\\n" "$@"\n' >"$STUBS/pulseaudio"
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
    [[ $output == *"module-pipe-sink file=$POCKET_SOUND_DIR/out format=s16le rate=48000 channels=2"* ]]
    [[ $output == *"module-native-protocol-unix auth-anonymous=1 socket=$POCKET_SOUND_DIR/native"* ]]
    [ -d "$POCKET_SOUND_DIR" ]
}
