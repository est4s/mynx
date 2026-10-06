#!/usr/bin/env bats
# The root .bashrc that ships in the Debian image (rootfs/root/.bashrc).

BASHRC="$BATS_TEST_DIRNAME/../../rootfs/root/.bashrc"

setup() {
    export HOME="$BATS_TEST_TMPDIR/home"
    mkdir -p "$HOME"
    # Only stubs and the basics, so the host's starship/eza don't leak in.
    STUBS="$BATS_TEST_TMPDIR/bin"
    mkdir -p "$STUBS"
    for tool in bash cat printf mkdir; do ln -sf "$(command -v $tool)" "$STUBS/$tool"; done
}

# Runs $1 in an interactive bash that has read the .bashrc. On its own
# line: bash expands aliases only in lines read after they're defined.
in_shell() {
    PATH="$STUBS" bash --norc --noprofile -i -c "source '$BASHRC'"$'\n'"$1" 2>/dev/null
}

stub() {
    printf '#!/bin/sh\n%s\n' "$2" >"$STUBS/$1"
    chmod +x "$STUBS/$1"
}

@test "tab title is the folder name" {
    mkdir -p "$HOME/src/app"
    run in_shell "cd '$HOME/src/app' && pc26_set_title"
    [ "$output" = $'\e]0;app\a' ]
}

@test "tab title is ~ at home and / at the root" {
    run in_shell "cd ~ && pc26_set_title && cd / && pc26_set_title"
    [ "$output" = $'\e]0;~\a\e]0;/\a' ]
}

@test "ls and friends use eza when it's installed" {
    stub eza 'echo "eza $*"'
    run in_shell "ls; ll; la; tree"
    [ "${lines[0]}" = "eza --icons=auto --group-directories-first" ]
    [ "${lines[1]}" = "eza -l --icons=auto --group-directories-first --git" ]
    [ "${lines[2]}" = "eza -la --icons=auto --group-directories-first --git" ]
    [ "${lines[3]}" = "eza --tree --icons=auto" ]
}

@test "no eza aliases without eza" {
    run in_shell "alias ls"
    [ "$status" -ne 0 ]
}

@test "without starship, the prompt sets the title and keeps the app's command" {
    export PROMPT_COMMAND='printf app'
    run in_shell 'cd ~ && eval "$PROMPT_COMMAND"'
    [ "$output" = $'\e]0;~\aapp' ]
}

@test "with starship, the prompt sets the title and keeps the app's command" {
    command -v starship >/dev/null || skip "starship not installed"
    ln -sf "$(command -v starship)" "$STUBS/starship"
    export PROMPT_COMMAND='printf app'
    run in_shell 'cd ~ && starship_precmd'
    [ "$output" = $'\e]0;~\aapp' ]
}

@test "nano is the editor" {
    run in_shell 'echo "$EDITOR $VISUAL"'
    [ "$output" = "nano nano" ]
}

@test "the menu runs with its key bar" {
    stub keybar 'echo "keybar $*"'
    run in_shell 'menu --no-boot'
    [ "$output" = "keybar menu menu --no-boot" ]
}

@test "home's local bin comes first on PATH" {
    run in_shell 'echo "$PATH"'
    [ "${output%%:*}" = "$HOME/.local/bin" ]
}

@test "opens the menu with the splash when the app asks, once" {
    stub keybar 'shift; exec "$@"'
    stub menu 'echo "menu $*"; echo "PC26_MENU=$PC26_MENU POCKET_MENU=$POCKET_MENU"'
    # The app also sets POCKET_MENU, for a .bashrc from before the rename.
    export PC26_MENU=1 POCKET_MENU=1
    run in_shell 'echo "after: [$PC26_MENU$POCKET_MENU]"'
    [ "${lines[0]}" = "menu --boot" ]
    [ "${lines[1]}" = "PC26_MENU= POCKET_MENU=" ]
    [ "${lines[2]}" = "after: []" ]
}

@test "no menu unless the app asks" {
    stub menu 'echo menu'
    run in_shell 'echo shell'
    [ "$output" = shell ]
}

@test "Exit in the opening menu closes the shell" {
    stub keybar 'shift; exec "$@"'
    stub menu 'exit 10'
    export PC26_MENU=1
    run in_shell 'echo still here'
    [ "$output" = "" ]
}

@test "Exit in a menu opened later closes the shell too" {
    stub keybar 'shift; exec "$@"'
    stub menu 'exit 10'
    run in_shell 'menu; echo still here'
    [ "$output" = "" ]
}

@test "leaving the menu for the shell keeps the shell" {
    stub keybar 'shift; exec "$@"'
    stub menu 'echo "menu $*"; exit 0'
    run in_shell 'menu --no-boot; echo still here'
    [ "${lines[0]}" = "menu --no-boot" ]
    [ "${lines[1]}" = "still here" ]
}
