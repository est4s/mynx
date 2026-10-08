#!/usr/bin/env bash
# TDD guard: production code must change together with its tests.
# Reads changed file paths on stdin. Exits 1 if code under a tested source set
# changed without any change to that source set's tests.
#
# Used by .githooks/pre-commit (staged files) and CI (each pushed commit).
set -euo pipefail

# source dir -> test dir. Add a line when a new tested module or test suite appears.
pairs=(
    "core/src/main/ core/src/test/"
    "rootfs/root/ tests/shell/"
    "tools/ tests/"
    "scripts/backup-root.sh tests/shell/"
    "scripts/restore-root.sh tests/shell/"
)

files=$(cat)
status=0
for pair in "${pairs[@]}"; do
    read -r src test <<<"$pair"
    if grep -q "^$src" <<<"$files" && ! grep -q "^$test" <<<"$files"; then
        echo "TDD: $src changed but $test did not."
        status=1
    fi
done
exit $status
