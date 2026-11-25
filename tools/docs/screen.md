# Another phone as a screen

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

A second phone (or tablet) running mynx can become a screen of this
one: this phone, the **host**, runs everything; the other, the
**screen**, shows what the host sends and sends its touches back.
Both need mynx and the same Wi-Fi (or one phone's hotspot). For now
the phones pair and keep a link; showing screens on the other phone
isn't there yet.

| Command | Does |
|---|---|
| `mynx screen pair` | on the host: show a six-digit code and wait for a phone to pair with it (Ctrl+C stops; five minutes at most) |
| `mynx screen pair CODE [ADDRESS]` | on the screen: pair with the host showing CODE; it's found on the Wi-Fi by itself, else give its address (`192.168.1.10`, `:PORT` if it isn't `screen-port`) |
| `mynx screen connect [NAME]` | on the screen: link again with a paired host, no code (NAME when it's paired with more than one) |
| `mynx screen disconnect` | end the link (the pair stays) |
| `mynx screen unpair NAME\|all` | forget a pair; a linked phone forgets it too. Pairing again needs a new code |
| `mynx screen place left\|right\|above\|below` | where the screen sits next to the host (the `screen-place` setting); run on the screen, it asks the host |
| `mynx screen [status]` | the link (which phone, its screen size, where it sits), why the last one ended, the pairs, whether this phone listens |

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
