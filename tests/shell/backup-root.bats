#!/usr/bin/env bats
# scripts/backup-root.sh and scripts/restore-root.sh: move root's home and
# the packages installed by hand from one Debian to a fresh one (an
# uninstall deletes the app's Debian). apt-mark and apt-get are stubs.

SCRIPTS="$BATS_TEST_DIRNAME/../../scripts"

setup() {
    export BACKUP_HOME="$BATS_TEST_TMPDIR/old/root"
    export RESTORE_HOME="$BATS_TEST_TMPDIR/new/root"
    DEST="$BATS_TEST_TMPDIR/phone/mynx-backup"
    mkdir -p "$BACKUP_HOME/mynx" "$RESTORE_HOME" "$BATS_TEST_TMPDIR/bin" "$BATS_TEST_TMPDIR/phone"
    echo 'my bashrc' >"$BACKUP_HOME/.bashrc"
    echo code >"$BACKUP_HOME/mynx/main.kt"
    # apt-mark lists the hand-installed packages; apt-get logs what it's asked.
    cat >"$BATS_TEST_TMPDIR/bin/apt-mark" <<'EOF'
#!/bin/sh
[ "$1" = showmanual ] && printf 'bats\ngh\nopenjdk-21-jdk-headless\n'
EOF
    cat >"$BATS_TEST_TMPDIR/bin/apt-get" <<EOF
#!/bin/sh
echo "\$*" >>"$BATS_TEST_TMPDIR/apt.log"
case "\$*" in *broken-pkg*) exit 100 ;; esac
EOF
    chmod +x "$BATS_TEST_TMPDIR/bin/"*
    export PATH="$BATS_TEST_TMPDIR/bin:$PATH"
}

backup() { bash "$SCRIPTS/backup-root.sh" "$DEST"; }

@test "backs up root's home, the package list and the restore script" {
    run backup
    [ "$status" -eq 0 ]
    [ -f "$DEST/root.tar.gz" ]
    [ "$(cat "$DEST/packages.txt")" = "$(printf 'bats\ngh\nopenjdk-21-jdk-headless')" ]
    cmp "$DEST/restore-root.sh" "$SCRIPTS/restore-root.sh"
    (cd "$DEST" && sha256sum -c --quiet SHA256SUMS)
    tar -tzf "$DEST/root.tar.gz" | grep -qx 'root/mynx/main.kt'
    [ ! -e "$DEST/root.tar.gz.part" ]
    [[ $output == *"Backed up"* ]]
}

@test "leaves out caches, proot's link files and old Claude Code versions" {
    mkdir -p "$BACKUP_HOME/.gradle/caches" "$BACKUP_HOME/.cache/pip" "$BACKUP_HOME/.local/bin" \
        "$BACKUP_HOME/.local/share/claude/versions"
    echo big >"$BACKUP_HOME/.gradle/caches/x"
    echo big >"$BACKUP_HOME/.cache/pip/y"
    echo junk >"$BACKUP_HOME/mynx/.l2s.tmp_obj0001"
    echo old >"$BACKUP_HOME/.local/share/claude/versions/2.1.292"
    echo new >"$BACKUP_HOME/.local/share/claude/versions/2.1.294"
    ln -s "$BACKUP_HOME/.local/share/claude/versions/2.1.294" "$BACKUP_HOME/.local/bin/claude"
    run backup
    [ "$status" -eq 0 ]
    list=$(tar -tzf "$DEST/root.tar.gz")
    [[ $list != *".gradle/caches"* ]]
    [[ $list != *".cache/pip"* ]]
    [[ $list != *".l2s"* ]]
    [[ $list != *"versions/2.1.292"* ]]
    [[ $list == *"versions/2.1.294"* ]]
    [[ $list == *".local/bin/claude"* ]]
}

@test "never overwrites a backup that's there" {
    mkdir -p "$DEST"
    echo earlier >"$DEST/root.tar.gz"
    run backup
    [ "$status" -ne 0 ]
    [[ $output == *"already exists"* ]]
    [ "$(cat "$DEST/root.tar.gz")" = earlier ]
}

@test "warns that the backup holds logins" {
    mkdir -p "$BACKUP_HOME/.config/gh" "$BACKUP_HOME/.claude"
    run backup
    [[ $output == *"~/.config/gh"* ]]
    [[ $output == *"~/.claude"* ]]
    [[ $output == *"delete"* ]]
}

@test "restores the home and installs the packages, after asking" {
    backup
    echo 'new image bashrc' >"$RESTORE_HOME/.bashrc"
    echo mine >"$RESTORE_HOME/only-new"
    run bash "$DEST/restore-root.sh" <<<"y"
    [ "$status" -eq 0 ]
    [[ $output == *"[y/N]"* ]]
    [ "$(cat "$RESTORE_HOME/.bashrc")" = 'my bashrc' ]
    [ "$(cat "$RESTORE_HOME/mynx/main.kt")" = code ]
    [ "$(cat "$RESTORE_HOME/only-new")" = mine ]
    grep -qx 'update' "$BATS_TEST_TMPDIR/apt.log"
    grep -qx 'install -y bats gh openjdk-21-jdk-headless' "$BATS_TEST_TMPDIR/apt.log"
    [[ $output == *"gh auth status"* ]]
    [[ $output == *"rm -r $DEST"* ]]
}

@test "restore changes nothing unless the answer is yes" {
    backup
    echo 'new image bashrc' >"$RESTORE_HOME/.bashrc"
    run bash "$DEST/restore-root.sh" <<<"n"
    [ "$status" -eq 0 ]
    [[ $output == *"Nothing was changed"* ]]
    [ "$(cat "$RESTORE_HOME/.bashrc")" = 'new image bashrc' ]
    [ ! -e "$BATS_TEST_TMPDIR/apt.log" ]
}

@test "restore --yes doesn't ask" {
    backup
    run bash "$DEST/restore-root.sh" --yes </dev/null
    [ "$status" -eq 0 ]
    [[ $output != *"[y/N]"* ]]
    [ -f "$RESTORE_HOME/mynx/main.kt" ]
}

@test "restore refuses a damaged backup" {
    backup
    echo extra >>"$DEST/root.tar.gz"
    run bash "$DEST/restore-root.sh" --yes
    [ "$status" -ne 0 ]
    [[ $output == *"damaged"* ]]
    [ ! -e "$RESTORE_HOME/mynx" ]
    [ ! -e "$BATS_TEST_TMPDIR/apt.log" ]
}

@test "restore still restores the home when a package won't install, and names it" {
    backup
    printf 'gh\nbroken-pkg\n' >"$DEST/packages.txt"
    (cd "$DEST" && sha256sum root.tar.gz packages.txt >SHA256SUMS)
    run bash "$DEST/restore-root.sh" --yes
    [ "$status" -eq 1 ]
    [ -f "$RESTORE_HOME/mynx/main.kt" ]
    grep -qx 'install -y gh' "$BATS_TEST_TMPDIR/apt.log"
    [[ $output == *"broken-pkg"* ]]
}

@test "refuses a backup folder inside the home it backs up" {
    run bash "$SCRIPTS/backup-root.sh" "$BACKUP_HOME/backup"
    [ "$status" -ne 0 ]
    [[ $output == *"inside $BACKUP_HOME"* ]]
    [ ! -e "$BACKUP_HOME/backup" ]
}
