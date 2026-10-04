#!/usr/bin/env bash
# Packs tools/ (pocket, the editors, themes) into OUT_DIR/tools.tar.xz for
# the APK. The app unpacks it on each update; Debian sees it at
# /opt/pocket-terminal.
#
#   scripts/pack-tools.sh app/src/main/assets
set -euo pipefail

out=${1:?usage: pack-tools.sh OUT_DIR}
src=$(cd "$(dirname "$0")/../tools" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)
# Top-level names, not ".": an entry for the archive root itself is pointless.
cd "$src"
shopt -s dotglob
tar --owner=0 --group=0 --numeric-owner --sort=name -cJf "$out/tools.tar.xz" -- *
