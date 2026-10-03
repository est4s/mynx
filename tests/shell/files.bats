#!/usr/bin/env bats
# `keybar` (tells the app which key bar to show while a command runs) and
# `files` (nnn set up for a phone screen), both in rootfs/bin/.

BIN="$BATS_TEST_DIRNAME/../../rootfs/bin"

setup() {
    export POCKET_KEYBAR_FILE="$BATS_TEST_TMPDIR/keybar"
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    export PATH="$STUBS:$BIN:$PATH"
}

stub() {
    printf '#!/bin/sh\n%s\n' "$2" >"$STUBS/$1"
    chmod +x "$STUBS/$1"
}

@test "keybar shows the bar while the command runs" {
    run keybar nnn cat "$POCKET_KEYBAR_FILE"
    [ "$output" = nnn ]
}

@test "keybar puts the previous bar back afterwards" {
    echo menu >"$POCKET_KEYBAR_FILE"
    keybar nnn true
    [ "$(cat "$POCKET_KEYBAR_FILE")" = menu ]
}

@test "keybar goes back to no bar (the shell's) if there was none" {
    keybar nnn true
    [ "$(cat "$POCKET_KEYBAR_FILE")" = "" ]
}

@test "keybar nests" {
    run keybar menu keybar nnn cat "$POCKET_KEYBAR_FILE"
    [ "$output" = nnn ]
    [ "$(cat "$POCKET_KEYBAR_FILE")" = "" ]
}

@test "keybar passes on the command's exit code" {
    run keybar nnn sh -c 'exit 3'
    [ "$status" -eq 3 ]
    [ "$(cat "$POCKET_KEYBAR_FILE")" = "" ]
}

@test "keybar just runs the command outside the app" {
    unset POCKET_KEYBAR_FILE
    run keybar nnn echo hi
    [ "$output" = hi ]
}

@test "files runs nnn in detail mode with the nnn key bar" {
    stub nnn 'echo "nnn $* [$(cat "$POCKET_KEYBAR_FILE")]"'
    run files /tmp
    [ "$output" = "nnn -de /tmp [nnn]" ]
}

@test "files has bookmarks for home, phone folders and /" {
    stub nnn 'echo "$NNN_BMS"'
    run files
    [ "$output" = "h:~;d:/storage/emulated/0/Download;p:/storage/emulated/0/Pictures;c:/storage/emulated/0/DCIM;r:/" ]
}

@test "files keeps the user's own bookmarks" {
    stub nnn 'echo "$NNN_BMS"'
    NNN_BMS='x:/tmp' run files
    [ "$output" = "x:/tmp" ]
}
