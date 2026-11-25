#!/usr/bin/env bash
# Checks that an APK has Termux:X11's X server where DesktopProcesses
# looks for it (lib/arm64-v8a/libXlorie.so, assets/x11/termux-x11.jar),
# and that none of its (GPL) classes are in mynx's own classes*.dex:
# they belong in the jar, a separate program run by app_process.
#
#   scripts/check-x11-apk.sh <apk>
set -euo pipefail

apk=${1:?usage: check-x11-apk.sh <apk>}
dir=$(mktemp -d)
trap 'rm -rf -- "$dir"' EXIT

status=0
unzip -q -o "$apk" 'classes*.dex' -d "$dir"
for dex in "$dir"/classes*.dex; do
    if grep -q 'Lcom/termux/x11/' "$dex"; then
        echo "check-x11-apk.sh: Termux:X11 classes in mynx's ${dex##*/}" >&2
        status=1
    fi
done
for entry in lib/arm64-v8a/libXlorie.so assets/x11/termux-x11.jar; do
    if unzip -l "$apk" "$entry" >/dev/null 2>&1; then
        unzip -l "$apk" "$entry" | grep " $entry\$"
    else
        echo "check-x11-apk.sh: no $entry in the APK" >&2
        status=1
    fi
done
exit $status
