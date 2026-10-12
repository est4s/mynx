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

whats_new_setup() { # whats_new_setup VERSION [SEEN]: tools and state for it
    export MYNX_TOOLS="$BATS_TEST_TMPDIR/tools" HOME="$BATS_TEST_TMPDIR/home"
    unset XDG_STATE_HOME
    mkdir -p "$MYNX_TOOLS" "$HOME/.local/state/mynx"
    echo "$1" >"$MYNX_TOOLS/.version"
    [ -z "${2-}" ] || echo "$2" >"$HOME/.local/state/mynx/notes-seen"
    printf '#!/bin/sh\necho "mynx $*" >>"%s"\n' "$BATS_TEST_TMPDIR/ran" >"$STUBS/mynx"
}

@test "after an update the shell shows what's new once" {
    whats_new_setup 90-1760000000-0.2.0 0.1.1
    source "$SETUP"
    _mynx_whats_new
    [ "$(cat "$BATS_TEST_TMPDIR/ran")" = "$(printf 'mynx notes new\nmynx notes seen')" ]
}

@test "with this version seen, the shell starts no mynx" {
    whats_new_setup 90-1760000000-0.2.0 0.2.0
    source "$SETUP"
    _mynx_whats_new
    [ ! -e "$BATS_TEST_TMPDIR/ran" ]
}

@test "with nothing seen yet, mynx decides (and records it)" {
    whats_new_setup 90-1760000000-0.2.0
    source "$SETUP"
    _mynx_whats_new
    [ "$(cat "$BATS_TEST_TMPDIR/ran")" = "$(printf 'mynx notes new\nmynx notes seen')" ]
}

@test "tools with no version name, or none at all, start no mynx" {
    whats_new_setup 57-1700000000000
    source "$SETUP"
    _mynx_whats_new
    rm "$MYNX_TOOLS/.version"
    _mynx_whats_new
    [ ! -e "$BATS_TEST_TMPDIR/ran" ]
}
