#!/usr/bin/env bash
# Download the latest CI build of the APK and hand it to Android's installer.
# Runs in Debian under proot on the phone (see AGENTS.md, "Build → install loop").
#
#   scripts/deliver.sh            wait for HEAD's run on main, then install
#   scripts/deliver.sh <run-id>   use a specific run
#   scripts/deliver.sh --no-open  copy and index only, don't open the installer
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
    for _ in $(seq 30); do
        run=$(gh run list --branch main --workflow build.yml --commit "$head" -L 1 \
            --json databaseId -q '.[0].databaseId')
        [[ -n $run ]] && break
        sleep 2
    done
    [[ -n $run ]] || { echo "No build found for $(git rev-parse --short HEAD)."; exit 1; }
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
apk=/sdcard/Download/pocket-terminal-debug.apk
cp "$tmp"/*/*.apk "$apk"
echo "Copied to $apk"

# Termux tools, called from Debian.
T=/data/data/com.termux/files/usr
termux() { env PATH="$T/bin:$PATH" PREFIX="$T" LD_LIBRARY_PATH="$T/lib" "$T/bin/$@"; }

termux termux-media-scan /storage/emulated/0/Download/pocket-terminal-debug.apk >/dev/null
if [[ $open == 1 ]]; then
    termux termux-open --content-type application/vnd.android.package-archive \
        /storage/emulated/0/Download/pocket-terminal-debug.apk
    echo "Installer opened on the phone."
fi
