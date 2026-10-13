#!/usr/bin/env bats
# The launcher menu (tools/bin/menu). Sourcing it loads the functions
# without starting the menu; running it with MENU_DRYRUN=1 prints the
# commands it would run instead of running them.

MENU="$BATS_TEST_DIRNAME/../../tools/bin/menu"

setup() {
    export HOME="$BATS_TEST_TMPDIR/home"
    unset XDG_CONFIG_HOME GH_CONFIG_DIR GIT_CONFIG_GLOBAL # CI's runner sets XDG_CONFIG_HOME
    export MENU_GAME_DIRS="$BATS_TEST_TMPDIR/games:$HOME/games"
    export MENU_APP_DIRS="$BATS_TEST_TMPDIR/apps:$HOME/apps"
    export MENU_UPDATE_FILE="$BATS_TEST_TMPDIR/update-available"
    mkdir -p "$HOME" "$BATS_TEST_TMPDIR/games" "$BATS_TEST_TMPDIR/apps"
    gh_set_up
}

gh_set_up() { # gh signed in and git's email set: GitHub setup moves to System
    mkdir -p "$HOME/.config/gh"
    printf 'github.com:\n    user: someone\n' >"$HOME/.config/gh/hosts.yml"
    printf '[user]\n\temail = 1+someone@users.noreply.github.com\n' >"$HOME/.gitconfig"
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
    [ "${ITEMS[*]}" = "Shell AI agents Services Apps Games Files Settings Exit" ]
    [ "${ACTS[*]}" = "exit menu:agents menu:services menu:apps menu:games files menu:settings quit_session" ]
}

@test "the user's menu file replaces the built-in one" {
    mkdir -p "$HOME/.config/mynx"
    printf '# mine\nShell = shell\n\n  Top = run htop -d 10\nBye = exit\n' >"$HOME/.config/mynx/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell Top Bye" ]
    [ "${ACTS[1]}" = "cmd:htop -d 10" ]
    [ "${ACTS[2]}" = quit_session ]
}

@test "a menu file with nothing usable falls back to the built-in items" {
    mkdir -p "$HOME/.config/mynx"
    printf 'oops\n' >"$HOME/.config/mynx/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell AI agents Services Apps Games Files Settings Exit" ]
}

@test "menu --check lists problems in a menu file" {
    printf 'Fine = files\nno equals sign\nX = fly\nThis label is far too long = shell\nY = run\n' >"$BATS_TEST_TMPDIR/menu.conf"
    run bash "$MENU" --check "$BATS_TEST_TMPDIR/menu.conf"
    [ "$status" -eq 1 ]
    [ "${lines[0]}" = "line 2: expected Label = action" ]
    [ "${lines[1]}" = "line 3: unknown action 'fly' (shell, files, apps, games, settings, app-settings, agents, services, system, help, welcome, exit or run COMMAND)" ]
    [ "${lines[2]}" = "line 4: label longer than 20 characters" ]
    [ "${lines[3]}" = "line 5: run needs a command" ]
}

@test "menu --check is quiet about a good file, and the built-in one is good" {
    run bash "$MENU" --check "$BATS_TEST_DIRNAME/../../tools/menu.conf"
    [ "$status" -eq 0 ]
    [ -z "$output" ]
}

@test "an available update comes first in the main menu, in the user's menu too" {
    echo 0.2.0 >"$MENU_UPDATE_FILE"
    source "$MENU"
    menu_items main
    [ "${ITEMS[0]}" = "Update available" ]
    [ "${ACTS[0]}" = appupdate ]
    [ "${ITEMS[1]}" = Shell ]
    mkdir -p "$HOME/.config/mynx"
    printf 'Shell = shell\n' >"$HOME/.config/mynx/menu.conf"
    menu_items main
    [ "${ITEMS[*]}" = "Update available Shell" ]
}

@test "Update available runs mynx update" {
    echo 0.2.0 >"$MENU_UPDATE_FILE"
    run keys 1
    [[ $output == *"RUN: mynx update"* ]]
}

@test "settings menu" {
    source "$MENU"
    menu_items settings
    [ "${ITEMS[*]}" = "App settings System Help" ]
    [ "${ACTS[*]}" = "settings menu:system menu:help" ]
}

@test "system menu" {
    source "$MENU"
    menu_items system
    [ "${ITEMS[*]}" = "Update all System info GitHub setup" ]
    [ "${ACTS[*]}" = "update info github" ]
}

@test "help menu" {
    source "$MENU"
    menu_items help
    [ "${ITEMS[*]}" = "Getting started Release notes Report a bug About" ]
    [ "${ACTS[*]}" = "welcome notes report about" ]
}

@test "Release notes shows mynx notes" {
    run keys 732
    [[ $output == *"RUN: mynx notes"* ]]
}

@test "About shows mynx about" {
    run keys 734
    [[ $output == *"RUN: mynx about"* ]]
}

@test "Help has Getting started" {
    run keys 731xxxxxxqqq
    [[ $output == *"A real Debian on your phone"* ]]
}

@test "Report a bug runs mynx report" {
    run keys 733
    [[ $output == *"RUN: mynx report"* ]]
}

@test "GitHub setup runs mynx github" {
    run keys 723
    [[ $output == *"RUN: mynx github"* ]]
}

@test "until GitHub is set up, it's in the main menu after AI agents, not in System" {
    rm "$HOME/.config/gh/hosts.yml"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell AI agents Set up GitHub Services Apps Games Files Settings Exit" ]
    [ "${ACTS[2]}" = github ]
    menu_items system
    [ "${ITEMS[*]}" = "Update all System info" ]
}

@test "GitHub needs git's email too" {
    rm "$HOME/.gitconfig"
    source "$MENU"
    menu_items main
    [ "${ITEMS[2]}" = "Set up GitHub" ]
}

@test "GitHub setup follows gh's config folder" {
    export GH_CONFIG_DIR="$BATS_TEST_TMPDIR/gh"
    source "$MENU"
    menu_items main
    [ "${ITEMS[2]}" = "Set up GitHub" ]
    mkdir -p "$GH_CONFIG_DIR"
    cp "$HOME/.config/gh/hosts.yml" "$GH_CONFIG_DIR/"
    menu_items main
    [ "${ITEMS[2]}" = Services ]
}

@test "Set up GitHub comes last in a menu without AI agents" {
    rm "$HOME/.config/gh/hosts.yml"
    mkdir -p "$HOME/.config/mynx"
    printf 'Shell = shell\nBye = exit\n' >"$HOME/.config/mynx/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ITEMS[*]}" = "Shell Bye Set up GitHub" ]
}

@test "Set up GitHub runs mynx github" {
    rm "$HOME/.config/gh/hosts.yml"
    run keys 3
    [[ $output == *"RUN: mynx github"* ]]
}

@test "a menu file can name the settings editors and the Help menu" {
    mkdir -p "$HOME/.config/mynx"
    printf 'Look = app-settings\nInfo = help\n' >"$HOME/.config/mynx/menu.conf"
    source "$MENU"
    menu_items main
    [ "${ACTS[*]}" = "settings menu:help" ]
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

app() { # app FILE [LABEL]: an executable app, labelled for the menu if given
    printf '#!/bin/sh\n%s\n' "${2:+# label: $2}" >"$1"
    chmod +x "$1"
}

@test "apps come from both folders, with their labels" {
    mkdir -p "$HOME/apps"
    app "$BATS_TEST_TMPDIR/apps/dbmeter" "Sound meter"
    app "$BATS_TEST_TMPDIR/apps/tuner" "Guitar tuner"
    printf 'not an app\n' >"$BATS_TEST_TMPDIR/apps/mic.py"
    app "$HOME/apps/my-tool.py"
    source "$MENU"
    menu_items apps
    [ "${ITEMS[*]}" = "Guitar tuner My Tool Sound meter" ]
    [ "${ACTS[0]}" = "app:$BATS_TEST_TMPDIR/apps/tuner" ]
    [ "${ACTS[1]}" = "app:$HOME/apps/my-tool.py" ]
}

@test "a label is cut to 20 characters" {
    app "$BATS_TEST_TMPDIR/apps/x" "A label that is much too long"
    source "$MENU"
    menu_items apps
    [ "${ITEMS[0]}" = "A label that is much" ]
}

@test "no apps" {
    source "$MENU"
    menu_items apps
    [ "${ITEMS[*]}" = "(no apps yet)" ]
    [ "${ACTS[*]}" = "none" ]
}

@test "Apps starts the chosen app under its key bar" {
    mkdir -p "$HOME/apps"
    app "$BATS_TEST_TMPDIR/apps/tuner" "Guitar tuner"
    app "$HOME/apps/my-tool.py"
    run keys 41
    [[ $output == *"RUN: keybar tuner,shell $BATS_TEST_TMPDIR/apps/tuner"* ]]
    run keys 42
    [[ $output == *"RUN: keybar my-tool,shell $HOME/apps/my-tool.py"* ]]
}

@test "Apps are sorted by label, wherever they are" {
    mkdir -p "$HOME/apps"
    app "$BATS_TEST_TMPDIR/apps/a-tool" "Zulu"
    app "$BATS_TEST_TMPDIR/apps/b-tool" "Alpha"
    app "$HOME/apps/c-tool" "Mike"
    source "$MENU"
    menu_items apps
    [ "${ITEMS[*]}" = "Alpha Mike Zulu" ]
    [ "${ACTS[0]}" = "app:$BATS_TEST_TMPDIR/apps/b-tool" ]
}

@test "menu --check accepts the apps action" {
    printf 'Apps = apps\n' >"$BATS_TEST_TMPDIR/menu.conf"
    run bash "$MENU" --check "$BATS_TEST_TMPDIR/menu.conf"
    [ "$status" -eq 0 ]
}

@test "state survives a restart" {
    source "$MENU"
    ST[sel_main]=2
    save_state
    unset ST; declare -A ST
    load_state
    [ "${ST[sel_main]}" = 2 ]
    [ -f "$HOME/.local/state/mynx/menu" ]
}

@test "colours come from the terminal's theme (the 16 basic colours)" {
    source "$MENU"
    [ "$C_TITLE" = $'\e[95m' ]
    [ "$C_SEL" = $'\e[1;30;105m' ]
}

@test "the boot splash names the theme" {
    source "$MENU"
    [ "$(theme_name)" = neon ]
    mkdir -p "$HOME/.config/mynx"
    printf '# theme: nord\nbackground=#000000\n' >"$HOME/.config/mynx/colors.properties"
    [ "$(theme_name)" = nord ]
    printf 'background=#000000\n' >"$HOME/.config/mynx/colors.properties"
    [ "$(theme_name)" = custom ]
}

@test "the title is mynx in small pixel letters, like the logo" {
    run keys q
    [[ $output == *"▟▀▀█▀▀▙ █  █ █▀▀▙ ▀▙▄▟▀"* ]]
    [[ $output == *"█  █  █ ▝▀▀█ █  █ ▄▛▀▜▄"* ]]
    [[ $output == *"         ▀▀▀"* ]]
    [[ $output != *"M Y N X"* ]]
}

@test "pages have the pixel title too" {
    source "$MENU"
    output=$(page "Confirm")
    [[ $output == *"▟▀▀█▀▀▙ █  █ █▀▀▙ ▀▙▄▟▀"* ]]
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
    run keys 6
    [[ $output == *"RUN: files"* ]]
}

@test "Games starts the chosen game" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run keys 51
    [[ $output == *"RUN: play $BATS_TEST_TMPDIR/games/neon-rogue.py"* ]]
}

@test "Settings → App settings opens the settings editors" {
    run keys 71
    [[ $output == *"RUN: mynx edit"* ]]
}

fake_mynx() { # a mynx that lists Claude Code as installed, Codex not
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    printf '#!/bin/sh\n[ "$*" = "agent list --tsv" ] && printf "claude\\tClaude Code\\tinstalled\\ncodex\\tCodex\\t\\n"\n' \
        >"$BATS_TEST_TMPDIR/bin/mynx"
    chmod +x "$BATS_TEST_TMPDIR/bin/mynx"
    export PATH="$BATS_TEST_TMPDIR/bin:$PATH"
}

@test "AI agents lists the agents, marking those to install" {
    fake_mynx
    source "$MENU"
    menu_items agents
    [ "${ITEMS[0]}" = "Claude Code" ]
    [ "${ITEMS[1]}" = "Codex (install)" ]
    [ "${ACTS[*]}" = "agent:claude agent:codex" ]
}

@test "picking an agent starts it (or offers to install it)" {
    fake_mynx
    run keys 22
    [[ $output == *"RUN: mynx agent start codex"* ]]
}

@test "moving through AI agents doesn't ask mynx again on each key" {
    fake_mynx
    sed -i "2i echo call >>'$BATS_TEST_TMPDIR/calls'" "$BATS_TEST_TMPDIR/bin/mynx"
    run keys 2jjjkqq
    [ "$status" -eq 0 ]
    [ "$(wc -l <"$BATS_TEST_TMPDIR/calls")" -eq 1 ]
}

@test "AI agents without a working mynx says so" {
    export PATH="$BATS_TEST_TMPDIR/empty:/usr/bin:/bin"
    source "$MENU"
    menu_items agents
    [ "${ACTS[*]}" = none ]
}

services_mynx() { # a mynx with two services, web running and db stopped
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    cat >"$BATS_TEST_TMPDIR/bin/mynx" <<'EOF'
#!/bin/sh
case "$*" in
"service list --json")
    printf '%s\n' '{"ok": true, "services": [{"name": "web", "state": "running", "saved": true, "autostart": true},' \
        '{"name": "database-server", "state": "stopped", "saved": true, "autostart": false, "exit_code": 1},' \
        '{"name": "tab", "state": "running", "saved": false}]}' ;;
"service log web -n 1000") printf 'Serving HTTP\nGET /\n' ;;
"service log db -n 1000") echo "mynx: no service named db" >&2; exit 2 ;;
*) echo "mynx $*" ;;
esac
EOF
    chmod +x "$BATS_TEST_TMPDIR/bin/mynx"
    export PATH="$BATS_TEST_TMPDIR/bin:$PATH"
}

@test "Services lists the services with their state" {
    services_mynx
    source "$MENU"
    menu_items services
    [ "${ITEMS[0]}" = "web         running" ]
    [ "${ITEMS[1]}" = "database-se stopped" ]
    [ "${ITEMS[2]}" = "tab*        running" ]
    [ "${ACTS[*]}" = "service:web service:database-server service:tab" ]
}

no_services_mynx() {
    mkdir -p "$BATS_TEST_TMPDIR/bin"
    printf '#!/bin/sh\necho %s\n' "'{\"ok\": true, \"services\": []}'" >"$BATS_TEST_TMPDIR/bin/mynx"
    chmod +x "$BATS_TEST_TMPDIR/bin/mynx"
    export PATH="$BATS_TEST_TMPDIR/bin:$PATH"
}

@test "with no services, Services offers a how-to" {
    no_services_mynx
    source "$MENU"
    menu_items services
    [ "${ITEMS[*]}" = "(no services yet)" ]
    [ "${ACTS[*]}" = svchelp ]
}

@test "the how-to says how to add a service" {
    no_services_mynx
    run keys '31xqq'
    [[ $output == *"mynx service add web -- python3 -m http.server 8080"* ]]
}

@test "Services without a working mynx says so" {
    export PATH="$BATS_TEST_TMPDIR/empty:/usr/bin:/bin"
    source "$MENU"
    menu_items services
    [ "${ITEMS[*]}" = "(mynx isn't working)" ]
}

@test "a service offers Open, Stop or Start, Restart, Log and Autostart" {
    services_mynx
    source "$MENU"
    SERVICE=web
    menu_items service
    [ "${ITEMS[*]}" = "Open Stop Restart Log Autostart: on" ]
    [ "${ACTS[*]}" = "svc:open svc:stop svc:restart svclog svcauto:off" ]
    SERVICE=database-server
    menu_items service
    [ "${ITEMS[*]}" = "Open Start Restart Log Autostart: off" ]
    [ "${ACTS[*]}" = "svc:open svc:start svc:restart svclog svcauto:on" ]
}

@test "an unsaved service has no Autostart: it isn't in services.conf" {
    services_mynx
    source "$MENU"
    SERVICE=tab
    menu_items service
    [ "${ITEMS[*]}" = "Open Stop Restart Log" ]
}

@test "Autostart switches it" {
    services_mynx
    run keys 315
    [[ $output == *"RUN: mynx service autostart web off"* ]]
    run keys 325
    [[ $output == *"RUN: mynx service autostart database-server on"* ]]
}

@test "picking a service and an action runs mynx service" {
    services_mynx
    run keys 312
    [[ $output == *"RUN: mynx service stop web"* ]]
    run keys 321
    [[ $output == *"RUN: mynx service open database-server"* ]]
    run keys 322
    [[ $output == *"RUN: mynx service start database-server"* ]]
}

@test "menu services starts at the Services list" {
    services_mynx
    run bash -c "printf 12 | MENU_DRYRUN=1 bash '$MENU' services 2>&1"
    [[ $output == *"RUN: mynx service stop web"* ]]
}

@test "leaving the Services list started there leaves the menu" {
    services_mynx
    run bash -c "printf 'q' | MENU_DRYRUN=1 timeout 5 bash '$MENU' services 2>&1"
    [ "$status" -eq 0 ]
    [[ $output != *"Shell"* ]]
}

@test "a service's log opens in the pager, at its end" {
    services_mynx
    run keys 314
    [[ $output == *"RUN: keybar pager,shell less +G"* ]]
}

@test "a service action's answer stays on screen, waiting for one key" {
    source "$MENU"
    pause() { echo PAUSED; }
    SERVICE=web
    run() { return 0; }
    [ "$(act_svc stop | grep -c PAUSED)" -eq 1 ]
    [ "$(act_svc open | grep -c PAUSED)" -eq 0 ]
    run() { return 2; }
    [ "$(act_svc stop | grep -c PAUSED)" -eq 1 ]
    [ "$(act_svc open | grep -c PAUSED)" -eq 1 ]
}

@test "a log that can't be read says why" {
    services_mynx
    source "$MENU"
    pause() { echo PAUSED; }
    SERVICE=db
    output=$(act_svclog)
    [[ $output == *"no service named db"* ]]
    [[ $output == *PAUSED* ]]
}

@test "a run item runs its command in bash" {
    mkdir -p "$HOME/.config/mynx"
    printf 'Top = run htop -d 10\n' >"$HOME/.config/mynx/menu.conf"
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
    run keys 721
    [[ $output == *"RUN: bash -c apt update && apt upgrade -y"* ]]
}

@test "the boot splash lists Debian and the games" {
    game "$BATS_TEST_TMPDIR/games/neon-rogue.py"
    run bash -c "(sleep 3; printf q) | bash '$MENU' --boot"
    [[ $output == *"debian"* ]]
    [[ $output == *"games: 1 found"* ]]
}

@test "the boot splash shows the title art in place of the title" {
    source "$MENU"
    boot_wait() { true; }
    output=$(boot </dev/null)
    [[ $output == *"█"* ]]
    [[ $output != *"m y n x"* ]]
    [[ $output == *"mynx online"* ]]
}

@test "the boot splash falls back to the title when the art fails" {
    export MENU_ART=false
    source "$MENU"
    boot_wait() { true; }
    output=$(boot </dev/null)
    [[ $output == *"m y n x"* ]]
    [[ $output == *"mynx online"* ]]
}

@test "the boot splash shows the title on a screen too narrow for the art" {
    source "$MENU"
    boot_wait() { true; }
    term_cols() { COLS=40; }
    output=$(boot </dev/null)
    [[ $output != *"█"* ]]
    [[ $output == *"m y n x"* ]]
}

@test "a key that skips the art skips the whole boot splash" {
    printf '#!/bin/sh\nexit 10\n' >"$BATS_TEST_TMPDIR/art"
    chmod +x "$BATS_TEST_TMPDIR/art"
    export MENU_ART="$BATS_TEST_TMPDIR/art"
    source "$MENU"
    boot_wait() { true; }
    output=$(boot </dev/null)
    [[ $output != *"mynx online"* ]]
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
    [[ $output == *"mynx welcome."* ]]
}

@test "a welcome page taller than the screen comes in pages" {
    source "$MENU"
    term_rows() { ROWS=12; }
    output=$(welcome_page <<<"xx")
    [[ $output == *"A real Debian on your phone"* ]]
    [[ $output == *"more"* ]]
    [[ $output == *"mynx welcome."* ]]
}

@test "the first boot shows the welcome page, later ones don't" {
    run bash -c "(sleep 2; printf x; sleep 0.5; printf q) | bash '$MENU' --boot"
    [[ $output == *"A real Debian on your phone"* ]]
    grep -qx 'welcomed=1' "$HOME/.local/state/mynx/menu"
    run bash -c "(sleep 2; printf q) | bash '$MENU' --boot"
    [[ $output != *"A real Debian on your phone"* ]]
}
