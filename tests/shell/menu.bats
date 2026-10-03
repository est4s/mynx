#!/usr/bin/env bats
# The launcher menu (rootfs/bin/menu). Sourcing it loads the functions
# without starting the menu; running it with MENU_DRYRUN=1 prints the
# commands it would run instead of running them.

MENU="$BATS_TEST_DIRNAME/../../rootfs/bin/menu"

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

@test "main menu items" {
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Terminal Files Games System Exit" ]
    [ "${ACTS[*]}" = "exit files menu:games menu:system quit_session" ]
}

@test "system menu shows the theme" {
    source "$MENU"
    ST[theme]=amber
    menu_items system
    [ "${ITEMS[*]}" = "Update all System info Theme: amber" ]
    [ "${ACTS[*]}" = "update info theme" ]
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
    ST[theme]=phosphor
    ST[sel_main]=2
    save_state
    unset ST; declare -A ST
    load_state
    [ "${ST[theme]}" = phosphor ]
    [ "${ST[sel_main]}" = 2 ]
    [ -f "$HOME/.local/state/pocket-terminal/menu" ]
}

@test "theme cycles neon, amber, phosphor and is saved" {
    source "$MENU"
    act_theme; [ "${ST[theme]}" = amber ]
    act_theme; [ "${ST[theme]}" = phosphor ]
    act_theme; [ "${ST[theme]}" = neon ]
    grep -qx 'theme=neon' "$HOME/.local/state/pocket-terminal/menu"
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
    run keys 5
    [ "$status" -eq 10 ]
}

@test "Files opens mc at home" {
    run keys 2
    [[ $output == *"RUN: mc $HOME"* ]]
}

@test "Games starts the chosen game" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run keys 31
    [[ $output == *"RUN: $BATS_TEST_TMPDIR/games/neon-rogue.py"* ]]
}

@test "Update all runs apt" {
    run keys 41
    [[ $output == *"RUN: bash -c apt update && apt upgrade -y"* ]]
}

@test "the boot splash lists Debian and the games" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run bash -c "(sleep 3; printf q) | bash '$MENU' --boot"
    [[ $output == *"debian"* ]]
    [[ $output == *"games: 1 found"* ]]
}
