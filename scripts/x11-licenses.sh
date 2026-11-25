#!/usr/bin/env bash
# Prints the licences of the libraries built into Termux:X11's
# libXlorie.so, from a checkout of it with its submodules:
# tools/licenses/X11-libraries.txt. build-x11.sh checks that file
# against the source it builds, so a new pin can't ship stale notices.
#
#   scripts/x11-licenses.sh <termux-x11 source>
set -euo pipefail

cpp=${1:?usage: x11-licenses.sh <termux-x11 source>}/lorie/src/main/cpp

cat <<'EOF'
The libraries built into Termux:X11's X server (libXlorie.so), as
pinned by the Termux:X11 commit mynx builds. Termux:X11's own code is
GPL-3.0 (its text is above); parts of it come from TigerVNC, under
GPL-2.0-or-later. Each library's licence and copyright notices follow,
from its COPYING file. libXlorie.so also has SHA-1 code by Steve Reid,
in the public domain.
EOF

section() {
    printf '\n%s\n%s\n\n' "$1" "$(printf -- '-%.0s' $(seq ${#1}))"
}

for part in "xserver:X.Org X server" "xorgproto:xorgproto (X protocol headers)" \
            "libxtrans:xtrans" "libx11:libX11 (the parts xkbcomp uses)" \
            "libxkbfile:libxkbfile" "xkbcomp:xkbcomp" "libxau:libXau" \
            "libxdmcp:libXdmcp" "libxfont:libXfont2" "libfontenc:libfontenc" \
            "libxshmfence:libxshmfence" "libxcvt:libxcvt" "pixman:pixman" \
            "libtirpc:libtirpc" "libepoxy:libepoxy (generated GL dispatch headers)" \
            "lorie/shm:libandroid-shmem (in Termux:X11's lorie/shm)"; do
    dir=${part%%:*}
    section "${part#*:}"
    files=("$cpp/$dir"/COPYING* "$cpp/$dir"/LICENSE)
    found=0
    for f in "${files[@]}"; do
        [[ -f $f ]] || continue
        found=1
        [[ ${#files[@]} -gt 2 && $f == */COPYING-* ]] && printf '%s:\n\n' "${f##*/COPYING-}"
        sed -e 's/[[:space:]]*$//' "$f"
        echo
    done
    [[ $found == 1 ]] || { echo "no licence file in $dir" >&2; exit 1; }
done
