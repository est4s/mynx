#!/usr/bin/env bash
# SPIKE (desktop-spike branch, throwaway): build Termux:X11's X server
# (GPL-3.0) from source as a separate program for mynx.
# Output in the given directory:
#   libXlorie.so     the X server (native, ships in jniLibs like libproot.so)
#   termux-x11.jar   its Java entry point (com.termux.x11.CmdEntryPoint and the
#                    AIDL stub), a dex jar run by app_process; it ships as an
#                    asset and is never on mynx's own class path
# x11/termux-x11.patch (GPL-3.0, like the code it changes) adapts it to mynx.
#
#   scripts/build-x11.sh <out-dir>
#
# Needs: ANDROID_NDK_HOME, ANDROID_HOME (android.jar, aidl, d8), cmake, ninja,
# bison, python3, gcc (host), git, javac.
set -euo pipefail

TERMUX_X11_COMMIT=24ce2076fe68eff1599da286c5c162f7693c0501   # master, 2026-11-23
API=26                                                       # = minSdk

out=$(realpath -m "${1:?usage: build-x11.sh <out-dir>}")
repo=$(cd "$(dirname "$0")/.." && pwd)
work=${X11_WORK:-$(mktemp -d)}
ndk=${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}
sdk=${ANDROID_HOME:?set ANDROID_HOME}

echo "== termux-x11 $TERMUX_X11_COMMIT"
src=$work/termux-x11
if [[ ! -d $src/.git ]]; then
    git init -q "$src"
    git -C "$src" remote add origin https://github.com/termux/termux-x11
fi
git -C "$src" fetch -q --depth 1 origin "$TERMUX_X11_COMMIT"
git -C "$src" checkout -q --force FETCH_HEAD
for try in 1 2 3; do
    git -C "$src" submodule update --init --recursive --depth 1 --jobs 8 && break
    [[ $try == 3 ]] && exit 1
    sleep 15
done
git -C "$src" apply "$repo/x11/termux-x11.patch"

echo "== native (libXlorie.so)"
cmake -S "$src/lorie/src/main/cpp" -B "$work/cmake" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$work/lib"
ninja -C "$work/cmake" Xlorie
mkdir -p "$out"
lib=$(find "$work/lib" "$work/cmake" -name libXlorie.so -print -quit 2>/dev/null || true)
"$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin)/llvm-strip" -o "$out/libXlorie.so" "$lib"

echo "== Java (termux-x11.jar)"
platform=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
java=$work/java
rm -rf "$java" && mkdir -p "$java/gen" "$java/stubs" "$java/classes" "$java/stub-classes"
"$tools/aidl" -o"$java/gen" -I"$src/lorie/src/main/aidl" \
    "$src/lorie/src/main/aidl/com/termux/x11/ICmdEntryInterface.aidl"
# BuildConfig: the broadcast goes to the app that started the server.
cat >"$java/gen/BuildConfig.java" <<EOF
package com.termux.x11;
public final class BuildConfig {
    public static final String APPLICATION_ID = System.getenv("MYNX_X11_PACKAGE");
    public static final String COMMIT = "$TERMUX_X11_COMMIT";
}
EOF
# Compile-only: hidden Android APIs (their stub module) and androidx's @Keep.
mkdir -p "$java/stubs/androidx/annotation"
echo 'package androidx.annotation; public @interface Keep {}' >"$java/stubs/androidx/annotation/Keep.java"
stubsrc=$src/shell-loader/stub/src/main/java
javac -nowarn -d "$java/stub-classes" -cp "$platform/android.jar" \
    $(find "$stubsrc" "$java/stubs" -name '*.java')
javac -nowarn -source 11 -target 11 -d "$java/classes" \
    -bootclasspath "$platform/android.jar" -cp "$java/stub-classes" \
    "$src/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java" \
    $(find "$java/gen" -name '*.java')
"$tools/d8" --release --min-api $API --lib "$platform/android.jar" \
    --output "$out/termux-x11.jar" $(find "$java/classes" -name '*.class')

ls -l "$out"
