#!/usr/bin/env bash
# Minimal stand-in for bats, for the phone: real bats needs process
# substitution, and the dev Debian's /dev/fd (proot-distro) is broken.
# CI runs real bats. Supports @test, setup, run, skip, $output, $lines,
# $status, $BATS_TEST_DIRNAME and $BATS_TEST_TMPDIR; nothing else.
#
#   scripts/bats-lite.sh tests/shell/*.bats
set -uo pipefail

run() {
    local errexit=0
    output=$("$@" 2>&1) && status=0 || status=$?
    IFS=$'\n' read -r -d '' -a lines <<<"$output" || true
    return 0
}
skip() { echo "skip: $*" >&3; exit 0; }

failed=0 total=0
for file in "$@"; do
    BATS_TEST_DIRNAME=$(cd "$(dirname "$file")" && pwd)
    names=()
    src=""
    while IFS= read -r line; do
        if [[ $line =~ ^@test\ \"(.*)\"\ \{$ ]]; then
            names+=("${BASH_REMATCH[1]}")
            line="bats_test_${#names[@]}() {"
        fi
        src+="$line"$'\n'
    done <"$file"
    for i in "${!names[@]}"; do
        total=$((total + 1))
        tmp=$(mktemp -d)
        # Not inside `if`: bash ignores set -e there, even in a subshell,
        # and a failed [ ] would no longer fail the test.
        ( BATS_TEST_TMPDIR=$tmp; set +u -e; eval "$src"
          if declare -F setup >/dev/null; then setup; fi
          "bats_test_$((i + 1))" ) 3>&1 >"$tmp.log" 2>&1
        if [[ $? == 0 ]]; then
            echo "ok   ${names[i]}"
        else
            echo "FAIL ${names[i]}"; sed 's/^/     /' "$tmp.log"; failed=$((failed + 1))
        fi
        rm -rf "$tmp" "$tmp.log"
    done
done
echo "$total tests, $failed failed"
[[ $failed == 0 ]]
