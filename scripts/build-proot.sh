#!/usr/bin/env bash
# Build Termux's proot fork for arm64 Android, with talloc linked statically.
# Output: libproot.so and libproot-loader.so in the given directory (they ship
# as native libraries because Android only executes files from the app's
# native library dir).
#
#   scripts/build-proot.sh <out-dir>
#
# Needs: ANDROID_NDK_HOME (CI), or CC/AR/STRIP/OBJCOPY/OBJDUMP set to an
# Android arm64 toolchain. Also git, make, python3, curl, readelf.
set -euo pipefail

PROOT_TAG=v5.1.107.96       # same release Termux ships
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
API=26                      # = minSdk

out=$(realpath -m "${1:?usage: build-proot.sh <out-dir>}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [[ -z ${CC:-} ]]; then
    bin=$(echo "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME or CC}"/toolchains/llvm/prebuilt/*/bin)
    export CC=$bin/aarch64-linux-android$API-clang
    export AR=$bin/llvm-ar STRIP=$bin/llvm-strip
    export OBJCOPY=$bin/llvm-objcopy OBJDUMP=$bin/llvm-objdump
fi

echo "== talloc $TALLOC_VERSION"
cd "$work"
curl -fsSL -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
echo "$TALLOC_SHA256  talloc.tar.gz" | sha256sum -c --quiet
tar xzf talloc.tar.gz
cd "talloc-$TALLOC_VERSION"
# waf can't run test programs when cross-compiling; answer its checks
# (the same answers Termux uses).
cat >cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
./configure --prefix="$work/talloc" --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt >/dev/null
make -j"$(nproc)" >/dev/null
mkdir -p "$work/talloc/lib" "$work/talloc/include"
"$AR" rcs "$work/talloc/lib/libtalloc.a" bin/default/talloc*.o
cp talloc.h "$work/talloc/include/"

echo "== proot $PROOT_TAG"
cd "$work"
git clone -q --depth 1 --branch "$PROOT_TAG" https://github.com/termux/proot.git
# Uses strcmp/memset without including string.h; current clang rejects that.
sed -i '1i #include <string.h>' proot/src/extension/ashmem_memfd/ashmem_memfd.c
# The loader is a separate file found through PROOT_LOADER at runtime: the
# bundled one would be extracted to app storage, where Android forbids exec.
# Flags go in through the environment: on the command line they would replace
# the makefile's own flags instead of adding to them.
CPPFLAGS="-I$work/talloc/include -DARG_MAX=131072" LDFLAGS="-L$work/talloc/lib" \
    make -C proot/src -j"$(nproc)" proot loader/loader PROOT_UNBUNDLE_LOADER=/nonexistent

mkdir -p "$out"
"$STRIP" -o "$out/libproot.so" proot/src/proot
cp proot/src/loader/loader "$out/libproot-loader.so"
echo "== built:"
ls -l "$out"
