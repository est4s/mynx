# Screenshots for the README

A guide for taking the README's screenshots on the phone, written so
an agent running in the app's Debian can walk the owner through it.

## Before you start

- Portrait, **Neon** theme (`mynx theme set neon`), default font
  size.
- Nothing personal on screen: no notifications (turn on Do Not
  Disturb), no private file names, prompts or chat text. These go in a
  public repo.
- Android saves screenshots in
  `/storage/emulated/0/Pictures/Screenshots/`. That folder holds the
  owner's other screenshots too: **only look at files newer than the
  start of the session** (e.g. `touch` a marker file first, then
  `find … -newer marker`), never browse or open older ones.

## The shots

Take them one at a time, and check each with the owner before the
next.

1. **Menu:** a fresh tab with the launcher menu (`menu`).
2. **Files:** `files` in the home folder, with the nnn key bar
   showing.
3. **AI agent:** Claude Code (or another agent) working on something
   harmless, with the agent key bar.
4. **A game:** `rogue` mid-game, with its key bar.
5. **Themes:** `mynx edit` → Theme, the cursor on a theme other
   than Neon, so the live preview shows.
6. *(Optional)* a phone feature: `mynx sensor compass --stream`,
   or `mynx location` with the coordinates hidden.

## Putting them in the README

- Copy the chosen files to `docs/images/` with plain names
  (`menu.png`, `files.png`, `agent.png`, `game.png`, `themes.png`).
- Shrink them: `apt install pngquant`, then
  `pngquant --quality 65-85 --strip --ext .png --force docs/images/*.png`.
  Aim for under ~300 KB each.
- README layout (keep it short; owner's request): the icon
  (`docs/images/icon.svg`, already there) centred above the title, then
  one row of 3-4 screenshots in an HTML table, each `width="200"`,
  with a one-word caption, right after the intro. More shots go in a
  second row, not more text.
- Commit them with `PRIVACY.md` and the README change, when the owner
  asks.
