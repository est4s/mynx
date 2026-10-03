#!/usr/bin/env bash
# Export the official arm64 Debian image as the rootfs that ships in the APK.
#
#   scripts/build-rootfs.sh <out-dir>
#
# Writes debian-rootfs.tar.xz and debian-rootfs.txt (the exact image digest,
# so a later migration knows what a user's Debian started from).
# Needs docker; creating and exporting an arm64 container doesn't run it, so
# no QEMU is needed (yet: customizing the image later will need buildx/QEMU).
set -euo pipefail

IMAGE=debian:trixie

out=$(realpath -m "${1:?usage: build-rootfs.sh <out-dir>}")
work=$(mktemp -d)
cid=""
trap 'rm -rf "$work"; [[ -n $cid ]] && docker rm -f "$cid" >/dev/null' EXIT

docker pull -q --platform linux/arm64 "$IMAGE" >/dev/null
digest=$(docker image inspect --format '{{index .RepoDigests 0}}' "$IMAGE")
cid=$(docker create --platform linux/arm64 "$IMAGE")
docker export -o "$work/rootfs.tar" "$cid"
# Docker's marker file; meaningless outside a container.
tar --delete -f "$work/rootfs.tar" .dockerenv 2>/dev/null || true

mkdir -p "$out"
xz -T0 -9 -c "$work/rootfs.tar" >"$out/debian-rootfs.tar.xz"
printf 'image=%s\nbuilt=%s\n' "$digest" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$out/debian-rootfs.txt"

echo "== $digest"
ls -l "$work/rootfs.tar" "$out"
