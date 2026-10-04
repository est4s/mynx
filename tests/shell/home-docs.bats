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
        "/opt/pocket-terminal/bin/menu" \
        "/opt/pocket-terminal/bin/files" \
        "/opt/pocket-terminal/bin/keybar" \
        "NNN_BMS" \
        "/opt/pocket-terminal/keybars" \
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

@test "AGENTS.md names every built-in key bar" {
    bars="$BATS_TEST_DIRNAME/../../core/src/main/resources/io/github/est4s/terminal/core/keybars"
    for f in "$bars"/*.conf; do
        name=$(basename "$f" .conf)
        grep -qF "\`$name\`" "$ROOT/AGENTS.md" || { echo "bar not in AGENTS.md: $name"; false; }
    done
}

@test "AGENTS.md documents pocket and every pocket command" {
    pocket="$BATS_TEST_DIRNAME/../../tools/bin/pocket"
    grep -qF "/opt/pocket-terminal" "$ROOT/AGENTS.md"
    commands=$(python3 "$pocket" help | sed -n '/^Commands:/,/^$/s/^  \([a-z-]*\) .*/\1/p')
    [ -n "$commands" ]
    for name in $commands; do
        grep -qF "pocket $name" "$ROOT/AGENTS.md" || { echo "not in AGENTS.md: pocket $name"; false; }
    done
}
