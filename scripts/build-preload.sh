#!/usr/bin/env bash
# Builds libmynx-serial.so (tools/src/libmynx-serial.c), the library the
# app preloads into Debian's programs for /dev/serial ports, into OUT.
# It links against Debian's arm64 glibc, not Android's bionic (so not
# with the NDK): CI cross-compiles with gcc-aarch64-linux-gnu; in the
# app's Debian, CC=gcc.
#
#   scripts/build-preload.sh build/libmynx-serial.so
set -euo pipefail

out=${1:?usage: build-preload.sh OUT.so}
repo=$(cd "$(dirname "$0")/.." && pwd)
"${CC:-aarch64-linux-gnu-gcc}" -shared -fPIC -O2 -Wall -Werror -s \
    -o "$out" "$repo/tools/src/libmynx-serial.c" -ldl
