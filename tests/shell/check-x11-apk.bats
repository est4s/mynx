#!/usr/bin/env bats
# scripts/check-x11-apk.sh: the X server is in the APK, and only as a
# separate program (its GPL classes never in mynx's own dex).

setup() {
    repo=$(cd "$BATS_TEST_DIRNAME/../.." && pwd)
    dir=$(mktemp -d)
}

teardown() {
    rm -rf -- "$dir"
}

# apk NAME=CONTENT...: a zip with those entries.
apk() {
    python3 - "$dir/app.apk" "$@" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    for entry in sys.argv[2:]:
        name, _, content = entry.partition("=")
        z.writestr(name, content)
EOF
}

@test "an APK with the server beside mynx's own classes passes" {
    apk "classes.dex=Lio/github/est4s/terminal/MainActivity;" \
        "classes2.dex=Lkotlin/Unit;" \
        "lib/arm64-v8a/libXlorie.so=elf" "assets/x11/termux-x11.jar=Lcom/termux/x11/CmdEntryPoint;"
    run "$repo/scripts/check-x11-apk.sh" "$dir/app.apk"
    [ "$status" -eq 0 ]
    [[ $output == *lib/arm64-v8a/libXlorie.so* ]]
    [[ $output == *assets/x11/termux-x11.jar* ]]
}

@test "Termux:X11 classes in any of mynx's dex files fail" {
    apk "classes.dex=Lio/github/est4s/terminal/MainActivity;" \
        "classes2.dex=Lcom/termux/x11/LorieView;" \
        "lib/arm64-v8a/libXlorie.so=elf" "assets/x11/termux-x11.jar=jar"
    run "$repo/scripts/check-x11-apk.sh" "$dir/app.apk"
    [ "$status" -eq 1 ]
    [[ $output == *classes2.dex* ]]
}

@test "an APK without the library fails" {
    apk "classes.dex=x" "assets/x11/termux-x11.jar=jar" "lib/x86_64/libXlorie.so=elf"
    run "$repo/scripts/check-x11-apk.sh" "$dir/app.apk"
    [ "$status" -eq 1 ]
    [[ $output == *lib/arm64-v8a/libXlorie.so* ]]
}

@test "an APK without the jar fails" {
    apk "classes.dex=x" "lib/arm64-v8a/libXlorie.so=elf" "assets/termux-x11.jar=jar"
    run "$repo/scripts/check-x11-apk.sh" "$dir/app.apk"
    [ "$status" -eq 1 ]
    [[ $output == *assets/x11/termux-x11.jar* ]]
}
