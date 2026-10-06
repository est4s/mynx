#!/usr/bin/env bats
# `pocket` (tools/bin/pocket): pc26's name before the rename. Agent hooks
# in old installs run `pocket hook NAME`, so it must behave exactly like
# pc26: same output, stdin, exit status and parent process.

BIN="$BATS_TEST_DIRNAME/../../tools/bin"

setup() {
    DIR="$BATS_TEST_TMPDIR/bin"
    mkdir -p "$DIR"
    cp "$BIN/pocket" "$DIR/"
    printf '#!/bin/sh\necho "pc26 $* ppid=$PPID stdin=$(cat)"\nexit 3\n' >"$DIR/pc26"
    chmod +x "$DIR/pc26"
}

@test "pocket runs the pc26 next to it with the same arguments and stdin" {
    run sh -c 'echo "{}" | "$1/pocket" hook "claude code"' sh "$DIR"
    [ "$status" -eq 3 ]
    [[ $output == "pc26 hook claude code ppid="*" stdin={}" ]]
}

@test "pocket runs on the PATH too" {
    run env PATH="$DIR:/usr/bin:/bin" sh -c 'pocket version </dev/null'
    [[ $output == "pc26 version "* ]]
}

@test "pocket replaces itself with pc26, which then has the caller as parent" {
    # pc26 rotation lock holds the screen for its parent.
    run bash -c 'echo "$$"; "$1/pocket" rotation </dev/null' bash "$DIR"
    [ "${lines[1]}" = "pc26 rotation ppid=${lines[0]} stdin=" ]
}

@test "pocket prints nothing of its own" {
    run sh -c '"$1/pocket" 2>&1 </dev/null' sh "$DIR"
    [ "${#lines[@]}" -eq 1 ]
    [[ $output == "pc26  ppid="* ]]
}
