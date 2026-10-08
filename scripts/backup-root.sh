#!/usr/bin/env bash
# Backs up root's home and the list of packages installed by hand, to move
# them into a fresh Debian (uninstalling the app deletes its Debian). The
# backup goes to phone storage, which survives the uninstall, with
# restore-root.sh beside it: run that in the new Debian.
#
#   scripts/backup-root.sh [FOLDER]   default /storage/emulated/0/mynx-backup
#
# Left out: caches that rebuild themselves (~/.gradle, ~/.cache), proot's
# .l2s.* link files (tar reads what they stand for), and Claude Code
# versions other than the one in use. BACKUP_HOME overrides /root (tests).
set -euo pipefail

home=${BACKUP_HOME:-/root}
dest=${1:-/storage/emulated/0/mynx-backup}
here=$(cd "$(dirname "$0")" && pwd)
name=$(basename "$home")

case $(realpath -m "$dest")/ in
    "$(realpath -m "$home")"/*)
        echo "$dest is inside $home, so the backup would hold itself: pick a folder outside it." >&2
        exit 1 ;;
esac
if [[ -e $dest && -n $(ls -A "$dest" 2>/dev/null) ]]; then
    echo "$dest already exists: move it away, or give another folder." >&2
    exit 1
fi
mkdir -p "$dest"

excludes=(--exclude="$name/.gradle" --exclude="$name/.cache" --exclude='.l2s.*')
versions=$home/.local/share/claude/versions
if [[ -d $versions ]]; then
    current=$(basename "$(readlink -f "$home/.local/bin/claude" 2>/dev/null || true)")
    for v in "$versions"/*; do
        [[ $(basename "$v") == "$current" ]] || excludes+=(--exclude="$name/.local/share/claude/versions/$(basename "$v")")
    done
fi

apt-mark showmanual >"$dest/packages.txt"

echo "Backing up $home to $dest…"
# Exit 1 is "a file changed while it was read" (logs of running programs): fine.
status=0
tar -C "$(dirname "$home")" -czf "$dest/root.tar.gz.part" "${excludes[@]}" \
    --warning=no-file-changed "$name" || status=$?
if ((status > 1)); then
    rm -f "$dest/root.tar.gz.part"
    echo "tar failed (exit $status); nothing was backed up." >&2
    exit 1
fi
gzip -t "$dest/root.tar.gz.part"
mv "$dest/root.tar.gz.part" "$dest/root.tar.gz"
cp "$here/restore-root.sh" "$dest/restore-root.sh"
(cd "$dest" && sha256sum root.tar.gz packages.txt >SHA256SUMS)

echo "Backed up $(du -h "$dest/root.tar.gz" | cut -f1) and $(wc -l <"$dest/packages.txt") packages to $dest."
logins=()
for f in .config/gh .claude .claude.json .ssh; do
    [[ -e $home/$f ]] && logins+=("~/$f")
done
if ((${#logins[@]})); then
    echo
    echo "It holds logins and keys (${logins[*]}). Other apps that can read"
    echo "phone storage can read it: delete it once it's restored."
fi
echo
echo "In the new Debian: bash $dest/restore-root.sh"
