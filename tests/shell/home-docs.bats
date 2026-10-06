#!/usr/bin/env bats
# The agent guide (tools/AGENTS.md → /opt/pc26/AGENTS.md, so
# it updates with the app) must keep up with the setup it describes. Root's
# home (rootfs/root/) has short AGENTS.md / CLAUDE.md files pointing to it.

ROOT="$BATS_TEST_DIRNAME/../../rootfs/root"
GUIDE="$BATS_TEST_DIRNAME/../../tools/AGENTS.md"

@test "the home's CLAUDE.md imports its AGENTS.md and the guide" {
    grep -qx '@AGENTS.md' "$ROOT/CLAUDE.md"
    grep -qx '@/opt/pc26/AGENTS.md' "$ROOT/CLAUDE.md"
}

@test "the home's AGENTS.md points to the guide" {
    grep -qF '/opt/pc26/AGENTS.md' "$ROOT/AGENTS.md"
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
        "~/.config/pc26/colors.properties" \
        "~/.config/pc26/settings.conf" \
        "~/.config/pc26/menu.conf" \
        "~/.config/pc26/themes" \
        "/opt/pc26/menu.conf" \
        "/opt/pc26/themes" \
        "~/.local/state/pc26/menu" \
        "~/games" \
        "/opt/pc26/bin" \
        "\`menu\`" "\`files\`" "\`keybar\`" "\`play\`" \
        "NNN_BMS" \
        "/opt/pc26/keybars" \
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

@test "the guide documents every pc26 command" {
    pc26="$BATS_TEST_DIRNAME/../../tools/bin/pc26"
    commands=$(python3 "$pc26" help | sed -n '/^Commands:/,/^$/s/^  \([a-z-]*\) .*/\1/p')
    [ -n "$commands" ]
    for name in $commands; do
        grep -qF "pc26 $name" "$GUIDE" || { echo "not in the guide: pc26 $name"; false; }
    done
}

@test "the guide documents every setting" {
    settings="$BATS_TEST_DIRNAME/../../core/src/main/kotlin/io/github/est4s/terminal/core/Settings.kt"
    keys=$(grep -A1 'SettingDef($' "$settings" | sed -n 's/^ *"\([a-z-]*\)",.*/\1/p')
    [ "$(wc -w <<<"$keys")" -ge 16 ]
    for key in $keys; do
        grep -qF -- "- \`$key\`:" "$GUIDE" || { echo "not in the guide: $key"; false; }
    done
}

@test "the guide says what makes room for the terminal in landscape" {
    grep -qF 'one row' "$GUIDE"
    grep -qF 'tab strip hides' "$GUIDE"
}
