# The Debian that ships in the APK. Built for arm64 by scripts/build-rootfs.sh,
# which passes the base image digest and the `neon` build context.
ARG BASE=debian:trixie
FROM ${BASE}

# apt-utils and dialog: without them every apt install in the app prints
# debconf warnings.
RUN DEBIAN_FRONTEND=noninteractive apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        apt-utils dialog eza mc python3 starship \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin

COPY root/ /root/
COPY --from=neon neon.colors.properties /root/.config/pocket-terminal/colors.properties

COPY bin/menu /usr/local/bin/menu

# The menu lists games by file name: neon-rogue.py → "Neon Rogue".
COPY games/ /opt/neon-games/
RUN chmod 755 /usr/local/bin/menu /opt/neon-games/*.py \
 && ln -s /opt/neon-games/neon-rogue.py /usr/local/bin/rogue \
 && ln -s /opt/neon-games/neon-drive.py /usr/local/bin/drive \
 && ln -s /opt/neon-games/neon-flap.py /usr/local/bin/flap
