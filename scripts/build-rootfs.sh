#!/usr/bin/env bash
# Build the arm64 Debian rootfs that ships in the APK from rootfs/Dockerfile
# (packages, dotfiles, games on top of the official debian:trixie image).
#
#   scripts/build-rootfs.sh <out-dir>
#
# Writes debian-rootfs.tar.xz and debian-rootfs.txt (the exact base image
# digest, so a later migration knows what a user's Debian started from).
# Needs docker with buildx, and QEMU for arm64 on an x86 host (CI sets it up).
set -euo pipefail

IMAGE=debian:trixie

repo=$(cd "$(dirname "$0")/.." && pwd)
out=$(realpath -m "${1:?usage: build-rootfs.sh <out-dir>}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

docker pull -q --platform linux/arm64 "$IMAGE" >/dev/null
digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$IMAGE")
docker buildx build --platform linux/arm64 \
    --build-arg "BASE=$digest" \
    --output "type=tar,dest=$work/rootfs.tar" \
    "$repo/rootfs"
# Docker's marker file, if any; meaningless outside a container.
tar --delete -f "$work/rootfs.tar" .dockerenv 2>/dev/null || true

mkdir -p "$out"
xz -T0 -9 -c "$work/rootfs.tar" >"$out/debian-rootfs.tar.xz"
printf 'image=%s\nbuilt=%s\n' "$digest" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$out/debian-rootfs.txt"

echo "== $digest"
ls -l "$work/rootfs.tar" "$out"
