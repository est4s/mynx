#!/usr/bin/env bats
# The launcher menu (tools/bin/menu). Sourcing it loads the functions
# without starting the menu; running it with MENU_DRYRUN=1 prints the
# commands it would run instead of running them.

MENU="$BATS_TEST_DIRNAME/../../tools/bin/menu"

setup() {
    export HOME="$BATS_TEST_TMPDIR/home"
    export MENU_GAME_DIRS="$BATS_TEST_TMPDIR/games:$HOME/games"
    mkdir -p "$HOME" "$BATS_TEST_TMPDIR/games"
}

game() {
    printf '#!/bin/sh\n' >"$1"
    chmod +x "$1"
}

# Runs the menu with $1 as the keys typed.
keys() {
    printf "$1" | MENU_DRYRUN=1 bash "$MENU" --no-boot 2>&1
}

@test "main menu items come from the built-in menu file" {
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Terminal Files Games Settings AI agents System Getting started Exit" ]
    [ "${ACTS[*]}" = "exit files menu:games settings menu:agents menu:system welcome quit_session" ]
}

@test "the user's menu file replaces the built-in one" {
    mkdir -p "$HOME/.config/pc26"
    printf '# mine\nShell = shell\n\n  Top = run htop -d 10\nBye = exit\n' >"$HOME/.config/pc26/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell Top Bye" ]
    [ "${ACTS[1]}" = "cmd:htop -d 10" ]
    [ "${ACTS[2]}" = quit_session ]
}

@test "a menu file with nothing usable falls back to the built-in items" {
    mkdir -p "$HOME/.config/pc26"
    printf 'oops\n' >"$HOME/.config/pc26/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Terminal Files Games Settings AI agents System Getting started Exit" ]
}

@test "menu --check lists problems in a menu file" {
    printf 'Fine = files\nno equals sign\nX = fly\nThis label is far too long = shell\nY = run\n' >"$BATS_TEST_TMPDIR/menu.conf"
    run bash "$MENU" --check "$BATS_TEST_TMPDIR/menu.conf"
    [ "$status" -eq 1 ]
    [ "${lines[0]}" = "line 2: expected Label = action" ]
    [ "${lines[1]}" = "line 3: unknown action 'fly' (shell, files, games, settings, agents, system, welcome, exit or run COMMAND)" ]
    [ "${lines[2]}" = "line 4: label longer than 20 characters" ]
    [ "${lines[3]}" = "line 5: run needs a command" ]
}

@test "menu --check is quiet about a good file, and the built-in one is good" {
    run bash "$MENU" --check "$BATS_TEST_DIRNAME/../../tools/menu.conf"
    [ "$status" -eq 0 ]
    [ -z "$output" ]
}

@test "system menu" {
    source "$MENU"
    menu_items system
    [ "${ITEMS[*]}" = "Update all System info About" ]
    [ "${ACTS[*]}" = "update info about" ]
}

@test "About shows pocket about" {
    run keys 63
    [[ $output == *"RUN: pocket about"* ]]
}

@test "games come from both folders, named after their files" {
    mkdir -p "$HOME/games" "$BATS_TEST_TMPDIR/games/src"
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    game "$BATS_TEST_TMPDIR/games/neon_flap"
    game "$HOME/games/my-game.sh"
    source "$MENU"
    menu_items games
    # Byte order in each folder, whatever the locale: - sorts before _.
    [ "${ITEMS[*]}" = "Neon Rogue Neon Flap My Game" ]
    [ "${ACTS[1]}" = "game:$BATS_TEST_TMPDIR/games/neon_flap" ]
    [ "${ACTS[2]}" = "game:$HOME/games/my-game.sh" ]
}

@test "no games" {
    source "$MENU"
    menu_items games
    [ "${ITEMS[*]}" = "(no games yet)" ]
    [ "${ACTS[*]}" = "none" ]
}

@test "state survives a restart" {
    source "$MENU"
    ST[sel_main]=2
    save_state
    unset ST; declare -A ST
    load_state
    [ "${ST[sel_main]}" = 2 ]
    [ -f "$HOME/.local/state/pc26/menu" ]
}

@test "colours come from the terminal's theme (the 16 basic colours)" {
    source "$MENU"
    [ "$C_TITLE" = $'\e[95m' ]
    [ "$C_SEL" = $'\e[1;30;105m' ]
}

@test "the boot splash names the theme" {
    source "$MENU"
    [ "$(theme_name)" = neon ]
    mkdir -p "$HOME/.config/pc26"
    printf '# theme: nord\nbackground=#000000\n' >"$HOME/.config/pc26/colors.properties"
    [ "$(theme_name)" = nord ]
    printf 'background=#000000\n' >"$HOME/.config/pc26/colors.properties"
    [ "$(theme_name)" = custom ]
}

@test "human sizes" {
    source "$MENU"
    [ "$(human_kb 524288)" = 512M ]
    [ "$(human_kb 13107200)" = 12.5G ]
}

@test "q leaves the menu for the shell" {
    run keys q
    [ "$status" -eq 0 ]
}

@test "Exit asks the shell to close the tab" {
    run keys 8
    [ "$status" -eq 10 ]
}

@test "Files opens the file manager" {
    run keys 2
    [[ $output == *"RUN: files"* ]]
}

@test "Games starts the chosen game" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run keys 31
    [[ $output == *"RUN: play $BATS_TEST_TMPDIR/games/neon-rogue.py"* ]]
}

@test "Settings opens the settings editor" {
    run keys 4
    [[ $output == *"RUN: pocket edit"* ]]
}

fake_pc26() { # a pocket that lists Claude Code as installed, Codex not
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    printf '#!/bin/sh\n[ "$*" = "agent list --tsv" ] && printf "claude\\tClaude Code\\tinstalled\\ncodex\\tCodex\\t\\n"\n' \
        >"$BATS_TEST_TMPDIR/bin/pocket"
    chmod +x "$BATS_TEST_TMPDIR/bin/pocket"
    export PATH="$BATS_TEST_TMPDIR/bin:$PATH"
}

@test "AI agents lists the agents, marking those to install" {
    fake_pc26
    source "$MENU"
    menu_items agents
    [ "${ITEMS[0]}" = "Claude Code" ]
    [ "${ITEMS[1]}" = "Codex (install)" ]
    [ "${ACTS[*]}" = "agent:claude agent:codex" ]
}

@test "picking an agent starts it (or offers to install it)" {
    fake_pc26
    run keys 52
    [[ $output == *"RUN: pocket agent start codex"* ]]
}

@test "moving through AI agents doesn't ask pocket again on each key" {
    fake_pc26
    sed -i "2i echo call >>'$BATS_TEST_TMPDIR/calls'" "$BATS_TEST_TMPDIR/bin/pocket"
    run keys 5jjjkqq
    [ "$status" -eq 0 ]
    [ "$(wc -l <"$BATS_TEST_TMPDIR/calls")" -eq 1 ]
}

@test "AI agents without a working pocket says so" {
    export PATH="$BATS_TEST_TMPDIR/empty:/usr/bin:/bin"
    source "$MENU"
    menu_items agents
    [ "${ACTS[*]}" = none ]
}

@test "a run item runs its command in bash" {
    mkdir -p "$HOME/.config/pc26"
    printf 'Top = run htop -d 10\n' >"$HOME/.config/pc26/menu.conf"
    run keys 1
    [[ $output == *"RUN: bash -c htop -d 10"* ]]
}

@test "a run item that fails says so and waits, instead of flashing back" {
    source "$MENU"
    run() { return 127; }
    pause() { echo PAUSED; }
    output=$(act_cmd "htop -d 10")
    [[ $output == *"htop: command not found"* ]]
    [[ $output == *PAUSED* ]]
    run() { return 3; }
    output=$(act_cmd "false")
    [[ $output == *"failed (exit 3)"* ]]
    run() { return 0; }
    [ -z "$(act_cmd true)" ]
}

@test "Update all runs apt" {
    run keys 61
    [[ $output == *"RUN: bash -c apt update && apt upgrade -y"* ]]
}

@test "the boot splash lists Debian and the games" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run bash -c "(sleep 3; printf q) | bash '$MENU' --boot"
    [[ $output == *"debian"* ]]
    [[ $output == *"games: 1 found"* ]]
}

@test "the welcome page fits the phone's width" {
    local line n=0
    export LC_ALL=C.UTF-8
    while IFS= read -r line; do
        n=$((n + 1))
        (( ${#line} <= 48 )) || { echo "line $n is ${#line} wide"; false; }
    done <"$BATS_TEST_DIRNAME/../../tools/welcome.txt"
}

@test "Getting started shows the welcome page" {
    source "$MENU"
    term_rows() { ROWS=60; }
    output=$(welcome_page <<<x)  # the menu's own run() replaces bats' run
    [[ $output == *"Getting started"* ]]
    [[ $output == *"A real Debian on your phone"* ]]
    [[ $output == *"pocket welcome."* ]]
}

@test "a welcome page taller than the screen comes in pages" {
    source "$MENU"
    term_rows() { ROWS=12; }
    output=$(welcome_page <<<"xx")
    [[ $output == *"A real Debian on your phone"* ]]
    [[ $output == *"more"* ]]
    [[ $output == *"pocket welcome."* ]]
}

@test "the first boot shows the welcome page, later ones don't" {
    run bash -c "(sleep 2; printf x; sleep 0.5; printf q) | bash '$MENU' --boot"
    [[ $output == *"A real Debian on your phone"* ]]
    grep -qx 'welcomed=1' "$HOME/.local/state/pc26/menu"
    run bash -c "(sleep 2; printf q) | bash '$MENU' --boot"
    [[ $output != *"A real Debian on your phone"* ]]
}
