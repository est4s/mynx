# Screenshots for the README

A guide for taking the README's screenshots on the phone, written so
an agent running in the app's Debian can walk the maintainer through it.

## Before you start

- Portrait, **Neon** theme (`mynx theme set neon`), default font
  size.
- Nothing personal on screen: no notifications (turn on Do Not
  Disturb), no private file names, prompts or chat text. These go in a
  public repo.
- Android saves screenshots in
  `/storage/emulated/0/Pictures/Screenshots/`, which also holds the
  maintainer's other screenshots. Once they're shared to `~/Shared`,
  **only look at files newer than the start of the session** (`touch`
  a marker file first, then `find … -newer marker`), never older ones.

## The shots

The README shows eight, in two rows (taken 2026-10-10, mynx dev):
**menu** (keyboard off), **agent** (Claude Code building something
harmless, agent key bar), **compass**, **drive** (mid-game),
**tuner**, **torch**, **spectrum**, and **themes** (`mynx edit` →
Theme, the cursor on phosphor so the preview shows). To replace one,
take it the same way and keep its file name.

Android's screenshots folder isn't readable from Debian (no media
permission): share the shots to mynx from Photos or Files, and they
land in `~/Shared`.

## Putting them in the README

- Copy the chosen files to `docs/images/` with plain names
  (`menu.png`, `agent.png`, …).
- Shrink them: `apt install pngquant`, then
  `pngquant --quality 65-85 --strip --ext .png --force docs/images/*.png`.
  Aim for under ~300 KB each.
- README layout: a table of two rows of four right after the intro,
  each `width="200"` with a one-word caption.
- Commit them with the README change, on a branch with a PR (`main`
  takes no direct pushes), when the maintainer asks.
