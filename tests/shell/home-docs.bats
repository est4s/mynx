#!/usr/bin/env bats
# The agent docs shipped in root's home (rootfs/root/AGENTS.md, CLAUDE.md)
# must keep up with the setup they describe.

ROOT="$BATS_TEST_DIRNAME/../../rootfs/root"

@test "CLAUDE.md imports AGENTS.md" {
    grep -qx '@AGENTS.md' "$ROOT/CLAUDE.md"
}

@test "AGENTS.md mentions every file shipped in the home folder" {
    missing=()
    find "$ROOT" -type f >"$BATS_TEST_TMPDIR/files"
    while IFS= read -r f; do
        path="~/${f#"$ROOT"/}"
        case $path in "~/AGENTS.md" | "~/CLAUDE.md") continue ;; esac
        grep -qF "$path" "$ROOT/AGENTS.md" || missing+=("$path")
    done <"$BATS_TEST_TMPDIR/files"
    [ "${#missing[@]}" -eq 0 ] || { echo "not in AGENTS.md: ${missing[*]}"; false; }
}

@test "AGENTS.md mentions the other things users can change" {
    for path in \
        "~/.config/pocket-terminal/colors.properties" \
        "~/.local/state/pocket-terminal/menu" \
        "~/games" \
        "/usr/local/bin/menu" \
        "/opt/neon-games"; do
        grep -qF "$path" "$ROOT/AGENTS.md" || { echo "not in AGENTS.md: $path"; false; }
    done
}

@test "AGENTS.md says where phone storage is and which editors exist" {
    grep -qF '`/storage/emulated/0`' "$ROOT/AGENTS.md"
    grep -qF '`nano`' "$ROOT/AGENTS.md"
    grep -qF '`less`' "$ROOT/AGENTS.md"
    # The image must actually ship what the docs promise.
    grep -Eq '^ +.*\bnano\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
    grep -Eq '^ +.*\bless\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
}
