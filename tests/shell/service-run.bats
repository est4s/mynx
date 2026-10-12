#!/usr/bin/env bats
# `service-run NAME [COMMAND]` (tools/lib/service-run): the app runs it
# in a service's hidden terminal. It runs the service's command in a
# process group of its own that has the terminal, leaves that group's id
# in NAME.pid and the command's exit code in NAME.exit (proot reports a
# death by signal as exit 0, so the app reads the code from there).
# Tested for real on a pseudo-terminal: this Debian runs under proot too.

RUN="$BATS_TEST_DIRNAME/../../tools/lib/service-run"

setup() {
    export HOME="$BATS_TEST_TMPDIR/home"
    export MYNX_SERVICES_DIR="$BATS_TEST_TMPDIR/services"
    mkdir -p "$HOME/.config/mynx"
    # on-tty.py [--send TEXT] [--term] -- ARGV…: runs ARGV as a session
    # leader on a terminal of its own. Once the command's group (from
    # web.pid) has the terminal, it types TEXT or sends TERM to that
    # group. Prints what the terminal showed; exits with ARGV's code.
    cat >"$BATS_TEST_TMPDIR/on-tty.py" <<'EOF'
import os, pty, signal, sys, time
args = sys.argv[1:]
send = term = None
while args[0] != "--":
    opt = args.pop(0)
    if opt == "--send":
        send = args.pop(0).encode().decode("unicode_escape").encode()
    elif opt == "--term":
        term = True
args.pop(0)
pid, fd = pty.fork()
if pid == 0:
    os.execvp(args[0], args)
pidfile = os.path.join(os.environ["MYNX_SERVICES_DIR"], "web.pid")
out = b""
if send or term:
    deadline = time.monotonic() + 10
    while True:
        try:
            group = int(open(pidfile).read())
            if os.tcgetpgrp(fd) == group:
                break
        except (OSError, ValueError):
            pass
        if time.monotonic() > deadline:
            sys.exit("the command's group never got the terminal")
        time.sleep(0.02)
    time.sleep(0.2)
    if send:
        os.write(fd, send)
    if term:
        os.killpg(group, signal.SIGTERM)
while True:
    try:
        data = os.read(fd, 1024)
    except OSError:
        break
    if not data:
        break
    out += data
sys.stdout.write(out.decode(errors="replace").replace("\r", ""))
_, status = os.waitpid(pid, 0)
sys.exit(os.waitstatus_to_exitcode(status))
EOF
}

service() { # service NAME COMMAND [cwd]: a block in services.conf
    {
        printf '[%s]\ncommand = %s\n' "$1" "$2"
        [ -z "$3" ] || printf 'cwd = %s\n' "$3"
        printf '\n'
    } >>"$HOME/.config/mynx/services.conf"
}

pty() {
    python3 "$BATS_TEST_TMPDIR/on-tty.py" "$@"
}

@test "it runs the service's command from services.conf and leaves its exit code" {
    service other 'exit 9'
    service web 'echo "hello from web"; exit 7'
    run pty -- "$RUN" web
    [ "$status" -eq 7 ]
    [[ $output == *"hello from web"* ]]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 7 ]
}

@test "the command runs in the service's folder, home by default" {
    mkdir -p "$HOME/site"
    service web 'pwd' '~/site'
    run pty -- "$RUN" web
    [[ $output == *"$HOME/site"* ]]
    service api 'pwd'
    run pty -- "$RUN" api
    [[ $output == *"$HOME"* ]]
}

@test "a command given on the line runs instead (an adopted tab's)" {
    run pty -- "$RUN" web 'echo given; exit 4'
    [ "$status" -eq 4 ]
    [[ $output == *given* ]]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 4 ]
}

@test "keys typed reach the command: it has the terminal" {
    service web 'read -r line; echo "got $line"; exit 3'
    run pty --send 'hello\n' -- "$RUN" web
    [ "$status" -eq 3 ]
    [[ $output == *"got hello"* ]]
}

@test "Ctrl+C stops the command, not the wrapper, which says so" {
    service web 'sleep 30'
    run pty --send '\x03' -- "$RUN" web
    [ "$status" -eq 130 ]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 130 ]
}

@test "TERM to the group in web.pid stops the whole pipeline; the wrapper reports 143" {
    service web 'sleep 31 | sleep 32'
    run pty --term -- "$RUN" web
    [ "$status" -eq 143 ]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 143 ]
    [[ $output == *"web stopped by signal 15"* ]]
    ! pgrep -f 'sleep 3[12]'
}

@test "the group isn't the wrapper's, and web.pid goes when the command ends" {
    service web 'echo "wrapper $PPID group $(ps -o pgid= $$)"; cat "$MYNX_SERVICES_DIR/web.pid"; echo'
    run pty -- "$RUN" web
    [[ $output =~ wrapper\ ([0-9]+)\ group\ +([0-9]+) ]]
    [ "${BASH_REMATCH[1]}" != "${BASH_REMATCH[2]}" ]
    [[ $output == *$'\n'"${BASH_REMATCH[2]}"$'\n'* ]]
    [ ! -e "$MYNX_SERVICES_DIR/web.pid" ]
}

@test "an old exit code goes when the service starts again" {
    mkdir -p "$MYNX_SERVICES_DIR"
    echo 1 >"$MYNX_SERVICES_DIR/web.exit"
    service web 'test -e "$MYNX_SERVICES_DIR/web.exit" && echo stale'
    run pty -- "$RUN" web
    [[ $output != *stale* ]]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 1 ]
}

@test "a missing service fails clearly, with an exit code for the app" {
    service api 'true'
    run pty -- "$RUN" web
    [ "$status" -eq 2 ]
    [[ $output == *"no service named web in ~/.config/mynx/services.conf"* ]]
    [ "$(cat "$MYNX_SERVICES_DIR/web.exit")" = 2 ]
}

@test "a service with no command, or a missing folder, fails clearly" {
    printf '[web]\ncwd = ~\n' >"$HOME/.config/mynx/services.conf"
    run pty -- "$RUN" web
    [ "$status" -eq 2 ]
    [[ $output == *"web has no command"* ]]
    service api 'true' '~/nowhere'
    run pty -- "$RUN" api
    [ "$status" -eq 2 ]
    [[ $output == *"no folder $HOME/nowhere"* ]]
}

@test "usage" {
    run "$RUN"
    [ "$status" -eq 2 ]
    [[ $output == *"usage: service-run NAME [COMMAND]"* ]]
}

@test "comments, blank lines and spacing in services.conf don't matter" {
    printf '# mine\n\n  [web]  \n  # the server\ncommand=echo  a=b  \n' >"$HOME/.config/mynx/services.conf"
    run pty -- "$RUN" web
    [ "$status" -eq 0 ]
    [[ $output == *"a=b"* ]]
}

@test "Ctrl+Z doesn't leave the service stopped" {
    service web 'read -r line; echo "got [$line]"'
    run pty --send '\x1ax\n' -- "$RUN" web
    [ "$status" -eq 0 ]
    [[ $output == *"got ["*"x]"* ]]
}
