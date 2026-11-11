#!/usr/bin/env bash
# Download the fonts and their licences: the terminal's (JetBrains Mono
# Nerd Font Mono) and touch mode's headings and buttons (Pixelify Sans).
#
#   scripts/fetch-font.sh <out-dir>
#
# Writes JetBrainsMonoNerdFontMono-Regular.ttf, OFL.txt, PixelifySans.ttf
# and PixelifySans-OFL.txt. Each download is pinned by hash, so a changed
# upstream file fails the build.
set -euo pipefail

VERSION=v3.5.1
SHA256=04d5e8f903693f9dd13e16f867e994834e681eb3c72c0d337a770dcda09010cf
URL=https://github.com/ryanoasis/nerd-fonts/releases/download/$VERSION/JetBrainsMono.tar.xz

# Pixelify Sans 1.000 (a variable font, wght 400-700) from Google Fonts' repo.
PIXELIFY_COMMIT=8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5
PIXELIFY_SHA256=9ba86cd010a4de309d263ceff8e8044092c9db7efda869620cb9ff1c4389e8a5
PIXELIFY_OFL_SHA256=b66ba46f511a851ab09998b5a5a9fdbb102545a3864cb993095e1745996873a7
PIXELIFY_URL=https://raw.githubusercontent.com/google/fonts/$PIXELIFY_COMMIT/ofl/pixelifysans

out=$(realpath -m "${1:?usage: fetch-font.sh <out-dir>}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -fsSL --retry 3 -o "$work/font.tar.xz" "$URL"
echo "$SHA256  $work/font.tar.xz" | sha256sum -c --quiet
curl -fsSL --retry 3 -o "$work/PixelifySans.ttf" "$PIXELIFY_URL/PixelifySans%5Bwght%5D.ttf"
curl -fsSL --retry 3 -o "$work/PixelifySans-OFL.txt" "$PIXELIFY_URL/OFL.txt"
sha256sum -c --quiet <<SUMS
$PIXELIFY_SHA256  $work/PixelifySans.ttf
$PIXELIFY_OFL_SHA256  $work/PixelifySans-OFL.txt
SUMS
mkdir -p "$out"
tar -xJf "$work/font.tar.xz" -C "$out" JetBrainsMonoNerdFontMono-Regular.ttf OFL.txt
install -m 644 "$work/PixelifySans.ttf" "$work/PixelifySans-OFL.txt" "$out/"

echo "== JetBrains Mono Nerd Font $VERSION, Pixelify Sans ${PIXELIFY_COMMIT:0:7}"
