# The Debian that ships in the APK. Built for arm64 by scripts/build-rootfs.sh,
# which passes the base image digest.
ARG BASE=debian:trixie
FROM ${BASE}

# apt-utils and dialog: without them every apt install in the app prints
# debconf warnings. less and nano: the base image has no pager or editor.
# curl: the AI agents' official installers (pocket agent install) need it.
# procps: ps, top, free; Codex runs ps to track its background server.
# pulseaudio and the rest: the sound device (the app runs PulseAudio on
# pipes; ALSA programs reach it through libasound2-plugins). Keep the
# list in step with `pocket sound install` (tools/lib/pocket_terminal/cli.py).
RUN DEBIAN_FRONTEND=noninteractive apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        apt-utils ca-certificates curl dialog eza htop less mc nano nnn procps python3 starship \
        pulseaudio pulseaudio-utils libasound2-plugins alsa-utils \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin

COPY root/ /root/

# The app's commands (menu, files, keybar, play, pocket, …) aren't in the
# image: the app mounts them at /opt/pocket-terminal (see tools/).

# The menu lists games by file name: neon-rogue.py → "Neon Rogue".
COPY games/ /opt/neon-games/
# ~/games: the menu lists the user's own games from here (empty at first).
RUN chmod 755 /opt/neon-games/*.py \
 && mkdir -p /root/games
