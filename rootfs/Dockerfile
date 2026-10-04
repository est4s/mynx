# The Debian that ships in the APK. Built for arm64 by scripts/build-rootfs.sh,
# which passes the base image digest and the `core` build context (core's
# resources: the built-in key bars).
ARG BASE=debian:trixie
FROM ${BASE}

# apt-utils and dialog: without them every apt install in the app prints
# debconf warnings. less and nano: the base image has no pager or editor.
RUN DEBIAN_FRONTEND=noninteractive apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        apt-utils dialog eza less mc nano nnn python3 starship \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin

COPY root/ /root/
# The app's built-in key bars, to copy from (the app reads its own copy).
COPY --from=core keybars/ /usr/share/pocket-terminal/keybars/

# menu, files (nnn for the phone), keybar, play and the game commands
# (rogue, drive, flap).
COPY bin/ /usr/local/bin/

# The menu lists games by file name: neon-rogue.py → "Neon Rogue".
COPY games/ /opt/neon-games/
# ~/games: the menu lists the user's own games from here (empty at first).
RUN chmod 755 /usr/local/bin/* /opt/neon-games/*.py \
 && mkdir -p /root/games
