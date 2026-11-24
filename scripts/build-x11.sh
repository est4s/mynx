#!/usr/bin/env bash
# Builds Termux:X11's X server (GPL-3.0) from source, as a separate
# program for desktop mode, like build-proot.sh builds proot. Writes to
# <out-dir>:
#   libXlorie.so     the X server; CI puts it in jniLibs/arm64-v8a, so it's
#                    in the app's native library dir (MYNX_X11_LIB)
#   termux-x11.jar   its Java entry point, only com.termux.x11.CmdEntryPoint
#                    and its AIDL stub, as dex, run by app_process; CI puts
#                    it in the assets' x11/, never on mynx's class path
#   x11-source.tar.xz  the corresponding source (GPL-3.0 section 6):
#                    Termux:X11 at the commit below with its submodules,
#                    mynx's patch and this script; releases attach it
# x11/termux-x11.patch (GPL-3.0, like the code it changes) adapts it to
# mynx. tools/licenses/X11-libraries.txt must match the libraries'
# notices at this commit (scripts/x11-licenses.sh writes it).
#
#   scripts/build-x11.sh <out-dir>
#
# Needs ANDROID_HOME (android.jar, aidl, d8; sdkmanager installs the NDK
# when ANDROID_NDK_HOME isn't set and it's missing), cmake, ninja, bison,
# python3, a host gcc, git, javac, xz.
set -euo pipefail

TERMUX_X11_COMMIT=24ce2076fe68eff1599da286c5c162f7693c0501   # master, 2026-11-23
NDK_VERSION=29.0.14206865                                    # Termux:X11's ndkVersion
API=26                                                       # = minSdk

out=$(realpath -m "${1:?usage: build-x11.sh <out-dir>}")
repo=$(cd "$(dirname "$0")/.." && pwd)
work=${X11_WORK:-$(mktemp -d)}
sdk=${ANDROID_HOME:?set ANDROID_HOME}
ndk=${ANDROID_NDK_HOME:-$sdk/ndk/$NDK_VERSION}
if [[ ! -d $ndk ]]; then
    yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION" >/dev/null
fi
grep -q "Pkg.Revision = $NDK_VERSION" "$ndk/source.properties" ||
    { echo "build-x11.sh: $ndk isn't NDK $NDK_VERSION" >&2; exit 1; }

echo "== termux-x11 $TERMUX_X11_COMMIT"
src=$work/termux-x11
if [[ ! -d $src/.git ]]; then
    git init -q "$src"
    git -C "$src" remote add origin https://github.com/termux/termux-x11
fi
git -C "$src" fetch -q --depth 1 origin "$TERMUX_X11_COMMIT"
git -C "$src" checkout -q --force FETCH_HEAD
for try in 1 2 3; do
    git -C "$src" submodule update --init --recursive --force --depth 1 --jobs 8 && break
    [[ $try == 3 ]] && exit 1
    sleep 15
done

echo "== licences"
if ! diff -q <("$repo/scripts/x11-licenses.sh" "$src") "$repo/tools/licenses/X11-libraries.txt" >/dev/null; then
    echo "build-x11.sh: tools/licenses/X11-libraries.txt is out of date: run" >&2
    echo "  scripts/x11-licenses.sh $src > tools/licenses/X11-libraries.txt" >&2
    exit 1
fi

# Before the patch, and before CMake patches the submodules itself:
# what Termux:X11 publishes, plus our patch and this script beside it.
echo "== source (x11-source.tar.xz)"
mkdir -p "$out"
name=termux-x11-${TERMUX_X11_COMMIT:0:7}
rm -rf "$work/mynx" && mkdir -p "$work/mynx"
cp "$repo/x11/termux-x11.patch" "$repo/scripts/build-x11.sh" "$repo/scripts/x11-licenses.sh" "$work/mynx/"
cat >"$work/mynx/README" <<EOF
The source of the X server in mynx's desktop mode (libXlorie.so and
termux-x11.jar): Termux:X11 (https://github.com/termux/termux-x11) at
commit $TERMUX_X11_COMMIT with its submodules, under the GPL-3.0 (LICENSE),
and mynx's changes to it (https://github.com/est4s/mynx):

  termux-x11.patch  the changes, applied with patch -p1 in this folder's parent
  build-x11.sh      the build: NDK $NDK_VERSION, CMake and Ninja for
                    libXlorie.so (arm64-v8a, API $API); aidl, javac and d8
                    for the jar
  x11-licenses.sh   collects the libraries' licence notices
EOF
tar -C "$work" --exclude=.git --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime=@0 --transform="s|^termux-x11|$name|S;s|^mynx|$name/mynx|S" \
    -cf - termux-x11 mynx | xz -T0 -6 >"$out/x11-source.tar.xz"

git -C "$src" apply "$repo/x11/termux-x11.patch"

echo "== native (libXlorie.so)"
cmake -S "$src/lorie/src/main/cpp" -B "$work/cmake" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$work/lib"
ninja -C "$work/cmake" Xlorie
lib=$(find "$work/lib" "$work/cmake" -name libXlorie.so -print -quit 2>/dev/null || true)
[[ -n $lib ]] || { echo "build-x11.sh: no libXlorie.so built" >&2; exit 1; }
"$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin)/llvm-strip" -o "$out/libXlorie.so" "$lib"

echo "== Java (termux-x11.jar)"
platform=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
java=$work/java
rm -rf "$java" && mkdir -p "$java/gen" "$java/stubs" "$java/classes" "$java/stub-classes"
"$tools/aidl" -p"$platform/framework.aidl" -o"$java/gen" -I"$src/lorie/src/main/aidl" \
    "$src/lorie/src/main/aidl/com/termux/x11/ICmdEntryInterface.aidl"
# The broadcast with the server's binder goes to the app that started it.
cat >"$java/gen/BuildConfig.java" <<EOF
package com.termux.x11;
public final class BuildConfig {
    public static final String APPLICATION_ID = System.getenv("MYNX_X11_PACKAGE");
    public static final String COMMIT = "$TERMUX_X11_COMMIT";
}
EOF
# Compile-only: hidden Android APIs (Termux:X11's stub module) and androidx's @Keep.
mkdir -p "$java/stubs/androidx/annotation"
echo 'package androidx.annotation; public @interface Keep {}' >"$java/stubs/androidx/annotation/Keep.java"
stubsrc=$src/shell-loader/stub/src/main/java
javac -nowarn -d "$java/stub-classes" -cp "$platform/android.jar" \
    $(find "$stubsrc" "$java/stubs" -name '*.java')
# --release 11: the JDK's own classes (javac needs its LambdaMetafactory), Android's on the class path.
javac -nowarn --release 11 -d "$java/classes" \
    -cp "$platform/android.jar:$java/stub-classes" \
    "$src/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java" \
    $(find "$java/gen" -name '*.java')
"$tools/d8" --release --min-api $API --lib "$platform/android.jar" \
    --output "$out/termux-x11.jar" $(find "$java/classes" -name '*.class')

ls -l "$out"
