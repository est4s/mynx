#!/usr/bin/env bats
# The agent guide (tools/AGENTS.md → /opt/pocket-terminal/AGENTS.md, so
# it updates with the app) must keep up with the setup it describes. Root's
# home (rootfs/root/) has short AGENTS.md / CLAUDE.md files pointing to it.

ROOT="$BATS_TEST_DIRNAME/../../rootfs/root"
GUIDE="$BATS_TEST_DIRNAME/../../tools/AGENTS.md"

@test "the home's CLAUDE.md imports its AGENTS.md and the guide" {
    grep -qx '@AGENTS.md' "$ROOT/CLAUDE.md"
    grep -qx '@/opt/pocket-terminal/AGENTS.md' "$ROOT/CLAUDE.md"
}

@test "the home's AGENTS.md points to the guide" {
    grep -qF '/opt/pocket-terminal/AGENTS.md' "$ROOT/AGENTS.md"
}

@test "the guide mentions every file shipped in the home folder" {
    missing=()
    find "$ROOT" -type f >"$BATS_TEST_TMPDIR/files"
    while IFS= read -r f; do
        path="~/${f#"$ROOT"/}"
        case $path in "~/AGENTS.md" | "~/CLAUDE.md") continue ;; esac
        grep -qF "$path" "$GUIDE" || missing+=("$path")
    done <"$BATS_TEST_TMPDIR/files"
    [ "${#missing[@]}" -eq 0 ] || { echo "not in the guide: ${missing[*]}"; false; }
}

@test "the guide mentions the other things users can change" {
    for path in \
        "~/.config/pocket-terminal/colors.properties" \
        "~/.config/pocket-terminal/settings.conf" \
        "~/.config/pocket-terminal/menu.conf" \
        "~/.config/pocket-terminal/themes" \
        "/opt/pocket-terminal/menu.conf" \
        "/opt/pocket-terminal/themes" \
        "~/.local/state/pocket-terminal/menu" \
        "~/games" \
        "/opt/pocket-terminal/bin" \
        "\`menu\`" "\`files\`" "\`keybar\`" "\`play\`" \
        "NNN_BMS" \
        "/opt/pocket-terminal/keybars" \
        "/opt/neon-games"; do
        grep -qF "$path" "$GUIDE" || { echo "not in the guide: $path"; false; }
    done
}

@test "the guide says where phone storage is and which editors exist" {
    grep -qF '`/storage/emulated/0`' "$GUIDE"
    grep -qF '`nano`' "$GUIDE"
    grep -qF '`less`' "$GUIDE"
    # The image must actually ship what the docs promise.
    grep -Eq '^ +.*\bnano\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
    grep -Eq '^ +.*\bless\b' "$BATS_TEST_DIRNAME/../../rootfs/Dockerfile"
}

@test "the guide names every built-in key bar and theme" {
    bars="$BATS_TEST_DIRNAME/../../core/src/main/resources/io/github/est4s/terminal/core/keybars"
    for f in "$bars"/*.conf; do
        name=$(basename "$f" .conf)
        grep -qF "\`$name\`" "$GUIDE" || { echo "bar not in the guide: $name"; false; }
    done
    themes="$BATS_TEST_DIRNAME/../../core/src/main/resources/io/github/est4s/terminal/core/themes"
    for f in "$themes"/*.colors.properties; do
        name=$(basename "$f" .colors.properties)
        grep -qF "\`$name\`" "$GUIDE" || { echo "theme not in the guide: $name"; false; }
    done
}

@test "the guide documents every pocket command" {
    pocket="$BATS_TEST_DIRNAME/../../tools/bin/pocket"
    commands=$(python3 "$pocket" help | sed -n '/^Commands:/,/^$/s/^  \([a-z-]*\) .*/\1/p')
    [ -n "$commands" ]
    for name in $commands; do
        grep -qF "pocket $name" "$GUIDE" || { echo "not in the guide: pocket $name"; false; }
    done
}
