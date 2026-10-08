#!/usr/bin/env bash
# Download the latest CI build of the APK and hand it to Android's installer.
# Runs in the app's own Debian on the phone (see AGENTS.md, "Build → install loop").
#
#   scripts/deliver.sh            wait for HEAD's run (main, or the PR of this
#                                 branch), then install
#   scripts/deliver.sh <run-id>   use a specific run
#   scripts/deliver.sh --no-open  copy to Download only, don't open the installer
set -euo pipefail

open=1 run=""
for arg in "$@"; do
    case $arg in
        --no-open) open=0 ;;
        *) run=$arg ;;
    esac
done

cd "$(dirname "$0")/.."
# The run for HEAD, not just the latest: right after a push, GitHub may not
# have registered the new run yet, and the latest is the previous build.
if [[ -z $run ]]; then
    head=$(git rev-parse HEAD)
    # A PR's runs carry its branch's name.
    branch=$(git branch --show-current)
    for _ in $(seq 30); do
        run=$(gh run list --branch "${branch:-main}" --workflow build.yml --commit "$head" -L 1 \
            --json databaseId -q '.[0].databaseId')
        [[ -n $run ]] && break
        sleep 2
    done
    [[ -n $run ]] || {
        echo "No build found for $(git rev-parse --short HEAD) on ${branch:-main}."
        echo "Only main and pull requests build: push, and open a PR for a branch."
        exit 1
    }
fi

echo "Waiting for run $run…"
gh run watch "$run" --exit-status >/dev/null || {
    echo "Build failed:"
    gh run view "$run" --log-failed | tail -40
    exit 1
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -D "$tmp"
# Not the name Termux used: Android won't let this app overwrite
# another app's file in Download.
apk=/storage/emulated/0/Download/mynx-build.apk
cp "$tmp"/*/*.apk "$apk"
echo "Copied to $apk"

[[ $open == 1 ]] || exit 0
# Debug builds of the app answer this; older or release builds can't.
if ! mynx install-apk "$apk"; then
    echo "Open Download/$(basename "$apk") in the Files app to install it,"
    echo "or try again: mynx install-apk $apk"
    exit 1
fi
