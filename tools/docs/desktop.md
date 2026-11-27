# Desktop mode

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx desktop [status]` | whether the X server and the desktop run, and what stops a start (a missing package, with the command that installs it) |
| `mynx desktop open` | show the desktop on the phone, over the screen you're on (it starts if it isn't running); the app must be on screen |
| `mynx desktop start` | start the X server on `DISPLAY=:0`, then the desktop the `desktop` setting names; returns once the server takes connections. Run again, it only starts a desktop that has ended |
| `mynx desktop stop` | stop the desktop and the X server |
| `mynx desktop install [--yes]` | install the Debian packages the `desktop` setting's desktop needs (shows the apt commands and asks first) |

Each but `install` takes `--json`.

Debian's graphical programs run on an X server the app ships
(Termux:X11, a separate program: see `mynx about`). It runs outside
proot, with its socket at `/tmp/.X11-unix/X0`; nothing but this
Debian can connect to it. Its log is `/tmp/.mynx/desktop/x11.log`,
the desktop's `/tmp/.mynx/desktop/session.log`.

- **The desktop** is the `desktop` setting: `xfce` (default) runs
  XFCE (`dbus-launch --exit-with-session startxfce4`), `none` the
  X server alone. XFCE isn't in a new Debian: `mynx desktop install`
  installs it (`--no-install-recommends`: xfce4's recommends would
  bring Xorg's own server): `xfce4`, `xfce4-terminal`, `dbus-x11`
  and `tango-icon-theme` (XFCE's default icons), and for either
  setting `xkb-data` (the server's keyboard layouts) and
  `xfonts-base`. The status's `install` lists what's missing; the
  desktop's notice offers Install, which runs it in a new tab.
- **Fonts:** GTK and Qt programs draw with fontconfig's fonts
  (`apt install fonts-noto` for more scripts). Programs that use the
  X server's own fonts (xterm, xfontsel) need `xfonts-base`: the
  server reads Debian's `/usr/share/fonts/X11/*` folders that have a
  `fonts.dir` when it starts, so after installing more, run
  `xset fp rehash` (in `x11-xserver-utils`) or stop and start the
  desktop.
- **Sound:** the desktop, and everything started from it, gets
  `PULSE_SERVER` as tabs do, so programs play through the sound
  device (`/opt/mynx/docs/phone.md`; `mynx sound install` for a
  Debian from before it).
- **`DISPLAY=:0`** is set in tabs and services started while the X
  server runs, so a program started there (`xterm &`, `xeyes &`)
  opens on the desktop. In a tab from before, `export DISPLAY=:0`.
- **Display :0 is the server's.** A server left from an earlier run
  of the app is stopped at a start; another X server on :0 (say
  `Xvfb :0`) is left alone, and the start says so: use another
  display for it.
- **Firefox** (`apt install firefox-esr`): its sandbox can't work
  under proot, so tabs and the desktop have `MOZ_FAKE_NO_SANDBOX=1`
  and `MOZ_SHM_NO_SEALS=1` (as proot-distro sets), and Debian's
  `/dev` has a `/dev/shm` for shared memory. If it still crashes at
  start, try `MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1
  firefox-esr`, then `MOZ_CRASHREPORTER_DISABLE=1 firefox-esr` in a
  tab with `DISPLAY=:0` to see its own error, and
  `firefox-esr --safe-mode`. With `serial-names = off`, Debian sees
  Android's `/dev`, which has no `/dev/shm`.

## The desktop on screen

- **Opening it:** the desktop is a place you open, like a program,
  from either view: Linux desktop in the launcher menu (terminal view)
  or in touch view's ☰ (under Linux), ▣ on the tab strip (while the
  X server runs), Ctrl+Shift+D on a hardware keyboard, or `mynx
  desktop open`. A `menu.conf` of your own from before has no Linux
  desktop: add `Linux desktop = desktop` (`mynx menu edit`).
- **Leaving it:** Back, the bar's first button (top left: ← Terminal
  or ← Touch view, where it goes) and Ctrl+Shift+D go back to the
  screen you opened it from, as you left it. The desktop keeps
  running meanwhile. **Shut down** (in ⋮) or `mynx desktop stop`
  ends it.
- **The ⋮ menu** (top right, or Ctrl+Shift+M on a hardware
  keyboard), so nothing needs leaving the desktop:
  - **Touch: Touchpad | Direct** switches `desktop-touch` (below) at
    once and saves it, as `mynx set desktop-touch …` does; the menu
    stays open to show it.
  - **Log** shows the logs (below); **Hide log** hides them.
  - **Restart desktop** stops the desktop and starts it again (as
    `mynx desktop stop`, then `start`).
  - **Shut down** asks first: the row turns pink,
    "Tap again to shut down", for 3 seconds; a second tap stops the
    desktop (as `mynx desktop stop`) and goes back to where you
    opened it from.
  - **Other phone** (only while a phone is linked as this one's
    screen, `/opt/mynx/docs/screen.md`): **Screen sits: Left | Right |
    Above | Below** (`screen-place`, as `mynx screen place`) and
    **Desktop there: Own monitor | Half of one** (`screen-desktop`
    `extend` or `span`, as `mynx set`), both applied at once with the
    menu kept open; **Align screens…** lines the two pictures up
    (sending the desktop there first if it isn't).
  Restart and Shut down show only while something runs. From the
  keyboard: Up, Down (or Tab, Shift+Tab) choose a row, Left and Right
  a row's choice, Enter (or Space) does it (Shut down: Enter twice),
  Esc or Back closes the menu. While it's open, keys don't reach the
  desktop. Ctrl+Shift+M is the app's on the desktop, as Ctrl+Shift+D
  is (a program's own Ctrl+Shift+M, as Firefox's responsive design
  mode, can't be reached there).
- **Starting:** opening the desktop starts it (as `mynx desktop
  start`) if nothing runs and nothing is missing. Otherwise it says
  why there's no picture: what to install, a start that failed, the
  server not answering yet, with Start to try again and the logs.
  **Log** (in ⋮) shows them at any time: the app's own lines,
  then the ends of `x11.log` and `session.log`; **Copy** copies
  them all, and you can select part of them. The desktop keeps
  running when you leave the app or go back from it; it stops
  with Shut down, `mynx desktop stop`, or when the app exits.
- **The screen** is the X server's, at about 0.75 dp a pixel (2 phone
  pixels on most phones), between the app's bar and the key bar.
  Turning the phone resizes it (RandR: programs see a new screen
  size). The soft keyboard doesn't: the screen moves up instead, so
  the pointer stays in sight.
- **Touch, `desktop-touch = touchpad`** (default): the screen is a
  touchpad. One finger moves the pointer (further when it moves
  fast); a tap is a left click, two taps a double click; a
  two-finger tap is a right click, a three-finger tap a middle click
  (X's paste of the selection); two fingers moving scroll, the
  content following them; to drag, hold a finger still for half a
  second, or tap and then touch again and move.
- **Touch, `desktop-touch = direct`**: the pointer is where you
  touch. A tap clicks there, a finger that moves drags from where it
  went down, a long press is a right click, and two fingers scroll
  or, tapped, right-click. A change (⋮'s Touch, `mynx set
  desktop-touch`) applies at once.
- **The clipboard** is shared, text only: what a program on the
  desktop copies (to X's clipboard, as Ctrl+C or a Copy menu does, not
  a mere selection) is on the phone's clipboard, and what you copy in
  another app pastes on the desktop (Ctrl+V or Paste) once you're back
  on it (Android lets an app read the clipboard only while it's in
  front). Up to 64 KB goes to the desktop (longer text is cut), up to
  256 KB comes from it (longer isn't copied; Log says so). A
  selection's middle-click paste stays X's own. Text in a script
  Debian has no font for (Chinese, Japanese, Korean) shows as boxes:
  `apt install fonts-noto-cjk` (about 50 MB), then reopen the program.
- **Another phone as more screen:** with a phone linked as this one's
  screen (`/opt/mynx/docs/screen.md`), **→ NAME** on the bar, or
  `mynx screen send desktop`, makes it a second monitor of the desktop,
  or the second half of one (`screen-desktop`); **Take back** ends it.
- **A mouse** (USB or Bluetooth) points and clicks as on a computer,
  its wheel scrolls.
- **Keys:** a hardware keyboard's keys go to the X server as they
  are (by key, not by character, so its layout is the server's: US
  unless you run `setxkbmap de` or another in Debian), Ctrl and Alt
  chords too. Ctrl+Shift+D and Ctrl+Shift+M (⋮) stay the app's;
  Back and the volume keys stay the phone's, but a keyboard's Esc
  key is Esc. ⌨ (top right) shows the soft keyboard, which types
  characters (any language), Enter, Tab and Backspace. The pointer is the app's own
  arrow: programs can't change its shape.
- **The key bar** is `desktop`: Esc, Tab, Ctrl, Alt, Super and the
  arrows. Ctrl, Alt and Super are sticky: tap one, then a key, a
  character on the soft keyboard, a click (Ctrl+click) or a scroll
  (Ctrl+scroll zooms in many programs). `mynx keybar edit desktop`
  changes it; on this bar `Super` alone is sticky and `Super+Up` is
  a chord, as `Ctrl+…` is (the format is the guide's "Key bars").
