#!/usr/bin/env bash
# Packs the app's tools for the APK into OUT_DIR/tools.tar.xz: tools/
# (pc26, menu, the editors, …) plus core's built-in key bars and
# themes, for reading and copying. The app unpacks it on each update;
# Debian sees it at /opt/pc26.
#
#   scripts/pack-tools.sh app/src/main/assets
set -euo pipefail

out=${1:?usage: pack-tools.sh OUT_DIR}
repo=$(cd "$(dirname "$0")/.." && pwd)
resources=$repo/core/src/main/resources/io/github/est4s/terminal/core
mkdir -p "$out"
out=$(cd "$out" && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

cp -a "$repo/tools/." "$stage/"
cp -a "$resources/keybars" "$resources/themes" "$stage/"
# The home folder's pointers to the guide, for Debians installed before it.
mkdir "$stage/home"
cp "$repo/rootfs/root/AGENTS.md" "$repo/rootfs/root/CLAUDE.md" "$stage/home/"
find "$stage" -name __pycache__ -prune -exec rm -rf {} +
# Top-level names, not ".": an entry for the archive root itself is pointless.
cd "$stage"
shopt -s dotglob
tar --owner=0 --group=0 --numeric-owner --sort=name -cJf "$out/tools.tar.xz" -- *
