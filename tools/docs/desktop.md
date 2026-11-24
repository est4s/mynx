# Desktop mode

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx desktop [status]` | whether the X server and the desktop run, and what stops a start (a missing package, with the command that installs it) |
| `mynx desktop start` | start the X server on `DISPLAY=:0`, then the desktop the `desktop` setting names; returns once the server takes connections. Run again, it only starts a desktop that has ended |
| `mynx desktop stop` | stop the desktop and the X server |

Each takes `--json`.

Debian's graphical programs run on an X server the app ships
(Termux:X11, a separate program: see `mynx about`). It runs outside
proot, with its socket at `/tmp/.X11-unix/X0`; nothing but this
Debian can connect to it. Its log is `/tmp/.mynx/desktop/x11.log`,
the desktop's `/tmp/.mynx/desktop/session.log`.

- **The desktop** is the `desktop` setting: `xfce` (default) runs
  XFCE (`dbus-launch --exit-with-session startxfce4`), `none` the
  X server alone. XFCE isn't in a new Debian: `apt install xfce4
  dbus-x11` installs it. The server needs `xkb-data` either way.
- **`DISPLAY=:0`** is set in tabs and services started while the X
  server runs, so a program started there (`xterm &`, `xeyes &`)
  opens on the desktop. In a tab from before, `export DISPLAY=:0`.
- **Display :0 is the server's.** A server left from an earlier run
  of the app is stopped at a start; another X server on :0 (say
  `Xvfb :0`) is left alone, and the start says so: use another
  display for it.
- **The view:** `mynx set view desktop` makes the desktop what the
  app shows (for now it shows the terminal there). `desktop-touch`
  says what a finger does there: `touchpad` (default) moves a
  pointer (tap to click, two fingers to scroll or right-click),
  `direct` clicks where you tap.
