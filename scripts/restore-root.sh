#!/usr/bin/env bash
# Restores a backup made by backup-root.sh into this Debian: root's home
# (its files replace those of the same name; others stay) and the packages
# installed by hand. Run it from the backup's folder, where backup-root.sh
# put it:
#
#   bash /storage/emulated/0/mynx-backup/restore-root.sh [--yes]
#
# RESTORE_HOME overrides /root (tests).
set -euo pipefail

home=${RESTORE_HOME:-/root}
dir=$(cd "$(dirname "$0")" && pwd)
yes=
for arg in "$@"; do
    case $arg in
        --yes) yes=1 ;;
        *) echo "usage: bash restore-root.sh [--yes]" >&2; exit 2 ;;
    esac
done

if ! (cd "$dir" && sha256sum -c --quiet SHA256SUMS) >/dev/null 2>&1; then
    echo "The backup in $dir is damaged or incomplete (SHA256SUMS doesn't match)." >&2
    echo "Nothing was changed." >&2
    exit 1
fi
mapfile -t packages < <(grep -v '^[[:space:]]*$' "$dir/packages.txt")

echo "This restores $home from $dir ($(du -h "$dir/root.tar.gz" | cut -f1)):"
echo "files in the backup replace those of the same name. Then it installs"
echo "${#packages[@]} packages: ${packages[*]}"
if [[ -z $yes ]]; then
    printf 'Go ahead? [y/N] '
    read -r answer || answer=
    if [[ ! $answer =~ ^[yY] ]]; then
        echo "Nothing was changed."
        exit 0
    fi
fi

mkdir -p "$home"
tar -C "$home" --strip-components=1 -xzpf "$dir/root.tar.gz"
echo "Restored $home."

failed=()
if ((${#packages[@]})); then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    # One at a time only if all at once fails, to name the ones that won't install.
    if ! apt-get install -y "${packages[@]}"; then
        for p in "${packages[@]}"; do
            apt-get install -y "$p" || failed+=("$p")
        done
    fi
fi

echo
if ((${#failed[@]})); then
    echo "These packages didn't install: ${failed[*]}"
    echo "Try them again with apt install once the rest is checked."
fi
echo "Check that your logins came back: gh auth status, and start claude."
echo "Then open a new tab (the restored ~/.bashrc applies there), and delete"
echo "the backup, which holds those logins: rm -r $dir"
((${#failed[@]} == 0)) || exit 1
