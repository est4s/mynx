# Another phone as a screen

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

A second phone (or tablet) running mynx can become a screen of this
one: this phone, the **host**, runs everything; the other, the
**screen**, shows what the host sends and sends its touches back.
Both need mynx and the same Wi-Fi (or one phone's hotspot). Apps
(web apps and games, `mynx app`) and terminal tabs can be sent to the
screen; chats and the desktop can't be yet.

| Command | Does |
|---|---|
| `mynx screen pair` | on the host: show a six-digit code and wait for a phone to pair with it (Ctrl+C stops; five minutes at most) |
| `mynx screen pair CODE [ADDRESS]` | on the screen: pair with the host showing CODE; it's found on the Wi-Fi by itself, else give its address (`192.168.1.10`, `:PORT` if it isn't `screen-port`) |
| `mynx screen connect [NAME]` | on the screen: link again with a paired host, no code (NAME when it's paired with more than one) |
| `mynx screen disconnect` | end the link (the pair stays) |
| `mynx screen unpair NAME\|all` | forget a pair; a linked phone forgets it too. Pairing again needs a new code |
| `mynx screen place left\|right\|above\|below` | where the screen sits next to the host (the `screen-place` setting); run on the screen, it asks the host |
| `mynx screen send [app] NAME` | on the host: show app NAME on the screen (it leaves this phone's screen; `mynx app list` names them) |
| `mynx screen send tab [N\|new]` | on the host: move terminal tab N (1 is the first in the strip; none: the tab this runs in) to the screen, or start a new one there |
| `mynx screen take [ID\|NAME\|all]` | bring sent screens back to this phone (all by default); on the screen, it asks the host to |
| `mynx screen [status]` | the link (which phone, its screen size, where it sits), what's sent there and where typing goes, why the last one ended, the pairs, whether this phone listens |

Each takes `--json`.

- **Pairing:** the host shows the code, the screen types it. Each
  code takes one try: after a wrong one the host shows a new code,
  and three wrong codes stop the pairing. The phones then keep a key
  for each other in the app's own files (not in Debian), so linking
  again needs no code; `unpair` forgets it.
- **Security:** the code is checked with SPAKE2 (a password-
  authenticated key exchange): someone else on the Wi-Fi who
  watches the pairing learns nothing to guess the code from, and has
  one guess per code. Everything after the handshake is encrypted
  and authenticated (AES-GCM, fresh keys for each link).
- **Finding the host:** it advertises itself over mDNS as
  `_mynx-screen._tcp` while it listens; networks that block mDNS
  need the address (`mynx screen pair` shows the host's).
- The link notices a phone that has gone within 15 seconds.

## Sending a screen

- **From the host:** touch mode's Home starts with **Send to NAME**
  while a screen is linked: tap an app, and it moves to the other
  phone; the same list has **Take back** for each one sent. Or `mynx
  screen send app NAME`. The host keeps working on anything else
  meanwhile.
- **On the screen:** what the host sends shows full screen at once
  (when mynx is on screen there). Back returns to the screen's own
  Home, which lists each sent screen at its top ("from NAME"): tap to
  show it again. While it isn't shown, the host stops sending its
  video (the app keeps running there). Touches on it go to the host.
- **Typing:** typing goes to the screen touched last. Tapping a text
  field on the sent screen opens a bar with the keyboard on the host:
  what you type there goes into that field (Enter, Delete and the
  arrows too). Touching the host's screen, Done or Back closes it and
  typing is the host's again. The screen shows a line around the sent
  screen while it gets the typing. Typing with the screen's own
  keyboard isn't there yet.
- **How it travels:** the host draws the app on an off-screen display
  of its own, the size of the other phone's screen, encodes it as
  H.264 and sends it over the encrypted link; when the screen falls
  behind, frames are dropped, not queued, so it stays current.
- **Taking back:** `mynx screen take`, Home's **Take back**, or the
  link ending brings it back to the host; the app's page loads again
  there (what it keeps in its own storage stays).
- A sent app has no bridge to the phone yet (`mynx.js`'s calls wait).

## Sending a terminal tab

- **From the host:** Home's **Send to NAME** list has
  **New terminal** and this phone's tabs; or long-press a tab in the strip,
  **Send to NAME**; or `mynx screen send tab N` (`new`, or no number
  for the tab it runs in). The tab leaves this phone's strip (sending
  the last one opens a fresh one here). Its shell and programs keep
  running on the host: only the text travels.
- **On the screen:** it's a tab there called "HOST: title", selected
  as it arrives, and listed on Home ("from HOST"). The screen draws it
  with its own font, colours and key bar; scrolling back, selecting
  and copying happen there. Its size is the screen's: the host's
  terminal takes the rows and columns that fit there (rotating or
  zooming the screen resizes it, as on one phone). Full-screen
  programs (vim, htop, less) work, mouse included.
- **Typing:** touch the tab on the screen and the host's typing goes
  to it: the host's keyboard, its key bar and a hardware keyboard on
  it. The host's strip starts with "⌨ NAME: title" while it does, and
  the screen draws a line around the tab. Touching the host's
  terminal (or that label) takes typing back. The screen's key bar
  types into the tab too; tapping it doesn't open the screen's
  keyboard.
- **Taking back:** `mynx screen take`, Home's **Take back**, the tab's
  ×, or **Give back** in its long-press menu on the screen: it's a tab
  on the host again, as it was. The link ending brings every tab back.
  A shell that exits closes its tab there (with an error, it comes
  back to the host, to read why).
- **How it travels:** the host sends what's on the terminal (its
  scrollback too), then the bytes its programs write, which the
  screen's terminal draws itself. A screen that falls behind (a flood
  of output) gets the terminal as it is now, not the backlog.
- A service's tab stays on the host.

Settings (`mynx set KEY VALUE`, in `~/.config/mynx/settings.conf`):

- `screen-host`: `auto` (default) or `off`: whether this phone takes
  the phones it paired with as screens. `auto` listens on the
  network (TCP `screen-port`) only while a screen is paired or a
  pairing waits; `off` never listens and refuses pairing.
- `screen-port`: the TCP port the host listens on, `7391` by default
  (1024-65535).
- `screen-place`: `left`, `right` (default), `above` or `below`: where
  the screen sits next to this phone; `mynx screen place` changes it
  and tells a linked screen.
- `screen-fps`: how many frames a second a sent screen has at most,
  `60` by default (10-120); fewer are lighter on the Wi-Fi and the
  screen's decoder.
- `screen-bitrate`: a sent screen's video in Mbit/s, `8` by default
  (1-40); more is sharper when much moves.
