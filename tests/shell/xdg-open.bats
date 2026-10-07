#!/usr/bin/env bats
# `xdg-open` (tools/bin/xdg-open): programs open links with it, or with
# $BROWSER, which the app points at it.

BIN="$BATS_TEST_DIRNAME/../../tools/bin"

setup() {
    STUBS="$BATS_TEST_TMPDIR/stubs"
    mkdir -p "$STUBS"
    printf '#!/bin/sh\necho "mynx $*"\n' >"$STUBS/mynx"
    chmod +x "$STUBS/mynx"
    export PATH="$STUBS:$BIN:$PATH"
}

@test "xdg-open asks the app to open the link" {
    run xdg-open "https://claude.ai/oauth?x=1&y=2"
    [ "$output" = "mynx open https://claude.ai/oauth?x=1&y=2" ]
}

@test "xdg-open without a link says how to use it" {
    run xdg-open
    [ "$status" -ne 0 ]
    [[ $output == *"usage: xdg-open URL"* ]]
}
