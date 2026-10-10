#!/usr/bin/env bats
# tools/shell.bash: the app's setup for interactive bash (loaded by
# /etc/profile.d/mynx.sh, which the app writes).

SETUP="$BATS_TEST_DIRNAME/../../tools/shell.bash"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    printf '#!/bin/sh\necho "mynx $*"\n' >"$STUBS/mynx"
    chmod +x "$STUBS/mynx"
    export PATH="$STUBS:/usr/bin:/bin"
}

agent() { # agent NAME: a fake installed agent
    printf '#!/bin/sh\n' >"$STUBS/$1"
    chmod +x "$STUBS/$1"
}

@test "typing an agent's name starts it like mynx agent start" {
    # Which sets up its key bar, and for Claude Code a messaging socket
    # and fullscreen.
    agent claude
    agent codex
    agent gemini
    source "$SETUP"
    run claude --resume "a b"
    [ "$output" = "mynx agent start claude --resume a b" ]
    [ "$(codex)" = "mynx agent start codex" ]
    [ "$(gemini)" = "mynx agent start gemini" ]
}

@test "an agent that isn't installed says how to install it" {
    source "$SETUP"
    run claude
    [ "$status" -eq 127 ]
    [ "$output" = "claude isn't installed; mynx agent install claude installs it" ]
}
