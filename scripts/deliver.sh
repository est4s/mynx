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
# A new name for each run: Android won't let this app overwrite a file
# in Download that another app made, such as an uninstalled mynx. Our own
# older builds go (rm can't remove other apps' files, so it's quiet).
dl=/storage/emulated/0/Download
rm -f "$dl"/mynx-dev-*.apk 2>/dev/null || true
apk=$dl/mynx-dev-$run.apk
cp "$tmp"/*/*.apk "$apk"
echo "Copied to $apk"

[[ $open == 1 ]] || exit 0
# Only debug builds of the app (mynx dev) answer this; run from the
# release mynx, it's refused and the file has to be opened by hand.
if ! mynx install-apk "$apk"; then
    echo "Open Download/$(basename "$apk") in the Files app to install it."
    exit 1
fi
