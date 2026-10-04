#!/usr/bin/env bats
# `play` (runs a game with its key bar) and the game commands, in tools/bin/.

BIN="$BATS_TEST_DIRNAME/../../tools/bin"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    printf '#!/bin/sh\necho "keybar $*"\n' >"$STUBS/keybar"
    chmod +x "$STUBS/keybar"
    export PATH="$STUBS:$BIN:$PATH"
}

@test "play uses the bar named after the game's file, else the game bar" {
    run play /opt/neon-games/neon-rogue.py --fast
    [ "$output" = "keybar neon-rogue,game /opt/neon-games/neon-rogue.py --fast" ]
}

@test "play finds a game on PATH" {
    printf '#!/bin/sh\n' >"$STUBS/my-game.sh"
    chmod +x "$STUBS/my-game.sh"
    run play my-game.sh
    [ "$output" = "keybar my-game,game $STUBS/my-game.sh" ]
}

@test "play says so when there's no such game" {
    run play no-such-game
    [ "$status" -eq 127 ]
    [[ $output == *"no-such-game: not found"* ]]
}

@test "rogue, drive and flap play the Neon games" {
    for game in rogue:neon-rogue drive:neon-drive flap:neon-flap; do
        run "${game%%:*}" x
        [ "$output" = "keybar ${game#*:},game /opt/neon-games/${game#*:}.py x" ]
    done
}
