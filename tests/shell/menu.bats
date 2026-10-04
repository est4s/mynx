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
    [ "${ITEMS[*]}" = "Terminal Files Games Settings System Exit" ]
    [ "${ACTS[*]}" = "exit files menu:games settings menu:system quit_session" ]
}

@test "the user's menu file replaces the built-in one" {
    mkdir -p "$HOME/.config/pocket-terminal"
    printf '# mine\nShell = shell\n\n  Top = run htop -d 10\nBye = exit\n' >"$HOME/.config/pocket-terminal/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell Top Bye" ]
    [ "${ACTS[1]}" = "cmd:htop -d 10" ]
    [ "${ACTS[2]}" = quit_session ]
}

@test "a menu file with nothing usable falls back to the built-in items" {
    mkdir -p "$HOME/.config/pocket-terminal"
    printf 'oops\n' >"$HOME/.config/pocket-terminal/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Terminal Files Games Settings System Exit" ]
}

@test "menu --check lists problems in a menu file" {
    printf 'Fine = files\nno equals sign\nX = fly\nThis label is far too long = shell\nY = run\n' >"$BATS_TEST_TMPDIR/menu.conf"
    run bash "$MENU" --check "$BATS_TEST_TMPDIR/menu.conf"
    [ "$status" -eq 1 ]
    [ "${lines[0]}" = "line 2: expected Label = action" ]
    [ "${lines[1]}" = "line 3: unknown action 'fly' (shell, files, games, settings, system, exit or run COMMAND)" ]
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
    [ "${ITEMS[*]}" = "Update all System info" ]
    [ "${ACTS[*]}" = "update info" ]
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
    [ -f "$HOME/.local/state/pocket-terminal/menu" ]
}

@test "colours come from the terminal's theme (the 16 basic colours)" {
    source "$MENU"
    [ "$C_TITLE" = $'\e[95m' ]
    [ "$C_SEL" = $'\e[1;30;105m' ]
}

@test "the boot splash names the theme" {
    source "$MENU"
    [ "$(theme_name)" = neon ]
    mkdir -p "$HOME/.config/pocket-terminal"
    printf '# theme: nord\nbackground=#000000\n' >"$HOME/.config/pocket-terminal/colors.properties"
    [ "$(theme_name)" = nord ]
    printf 'background=#000000\n' >"$HOME/.config/pocket-terminal/colors.properties"
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
    run keys 6
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

@test "a run item runs its command in bash" {
    mkdir -p "$HOME/.config/pocket-terminal"
    printf 'Top = run htop -d 10\n' >"$HOME/.config/pocket-terminal/menu.conf"
    run keys 1
    [[ $output == *"RUN: bash -c htop -d 10"* ]]
}

@test "Update all runs apt" {
    run keys 51
    [[ $output == *"RUN: bash -c apt update && apt upgrade -y"* ]]
}

@test "the boot splash lists Debian and the games" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run bash -c "(sleep 3; printf q) | bash '$MENU' --boot"
    [[ $output == *"debian"* ]]
    [[ $output == *"games: 1 found"* ]]
}
