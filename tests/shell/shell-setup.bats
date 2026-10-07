#!/usr/bin/env bats
# tools/shell.bash: the app's setup for interactive bash (loaded by
# /etc/profile.d/pc26.sh, which the app writes).

SETUP="$BATS_TEST_DIRNAME/../../tools/shell.bash"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    printf '#!/bin/sh\necho "keybar $*"\n' >"$STUBS/keybar"
    chmod +x "$STUBS/keybar"
    export PATH="$STUBS:/usr/bin:/bin"
}

agent() { # agent NAME: a fake installed agent
    printf '#!/bin/sh\n' >"$STUBS/$1"
    chmod +x "$STUBS/$1"
}

@test "typing an agent's name runs it under the agent key bar" {
    agent claude
    agent codex
    agent gemini
    source "$SETUP"
    run claude --resume "a b"
    [ "$output" = "keybar claude,agent claude --resume a b" ]
    [ "$(codex)" = "keybar codex,agent codex" ]
    [ "$(gemini)" = "keybar gemini,agent gemini" ]
}

@test "an agent that isn't installed says how to install it" {
    source "$SETUP"
    run claude
    [ "$status" -eq 127 ]
    [ "$output" = "claude isn't installed; pocket agent install claude installs it" ]
}
