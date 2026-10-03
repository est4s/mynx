#!/usr/bin/env bash
# Download the terminal font (JetBrains Mono Nerd Font Mono) and its licence.
#
#   scripts/fetch-font.sh <out-dir>
#
# Writes JetBrainsMonoNerdFontMono-Regular.ttf and OFL.txt. The release
# archive is pinned by hash, so a changed upstream file fails the build.
set -euo pipefail

VERSION=v3.5.1
SHA256=04d5e8f903693f9dd13e16f867e994834e681eb3c72c0d337a770dcda09010cf
URL=https://github.com/ryanoasis/nerd-fonts/releases/download/$VERSION/JetBrainsMono.tar.xz

out=$(realpath -m "${1:?usage: fetch-font.sh <out-dir>}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -fsSL --retry 3 -o "$work/font.tar.xz" "$URL"
echo "$SHA256  $work/font.tar.xz" | sha256sum -c --quiet
mkdir -p "$out"
tar -xJf "$work/font.tar.xz" -C "$out" JetBrainsMonoNerdFontMono-Regular.ttf OFL.txt

echo "== JetBrains Mono Nerd Font $VERSION"
