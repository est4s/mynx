#!/usr/bin/env bats
# scripts/pack-tools.sh: what the tools archive holds.

setup() {
    repo=$(cd "$BATS_TEST_DIRNAME/../.." && pwd)
    out=$(mktemp -d)
}

teardown() {
    rm -rf -- "$out"
}

@test "the tools archive has the apps' mynx.js" {
    run "$repo/scripts/pack-tools.sh" "$out"
    [ "$status" -eq 0 ]
    run tar -tJf "$out/tools.tar.xz" web/mynx.js
    [ "$status" -eq 0 ]
    [ "$output" = "web/mynx.js" ]
}
