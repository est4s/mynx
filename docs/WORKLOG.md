# Work log

Where the project stands, what's next, and what recent sessions did.
Newest entries first. Rules for keeping it up to date (and short): see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps). Older entries
are in git history: the full log up to entry 84 is
`git show 72b8512:docs/WORKLOG.md`.

---

## Current status

- **Done and confirmed on the phone:** roadmap steps 1-7, 9 and 10
  (`docs/ROADMAP.md`): Debian through proot, tabs, the default setup,
  the file manager and key bars, customization (`mynx`, the editors),
  agent support, Android integration (clipboard, sharing, location,
  sensors, camera, flashlight, sound in and out, rotation lock), and
  polish (welcome page, wakelock, Settings shortcut, `mynx about`,
  themed icon, the title art and "m" icon). Also: the rename to mynx,
  bug reports (`mynx report`), and **mynx dev**, debug builds as a
  separate app (entry 82).
- **Built, waiting for a release to test:** app updates from GitHub
  releases (`mynx update`, the daily check, entry 81). mynx dev
  doesn't update from releases, so only a release can try them.
- **Not released yet:** no `v*` tag, and the release key isn't made.
- **Step 8 (*Profiles*) is parked** by the maintainer (plan below).
- **Workflow:** trunk-based, `main` protected (PRs only, entry 83).
  CI builds PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`.


## Next

### 0. Finish PR #12 (`readme-logo`)

- Everything in entry 89 is confirmed on the phone.
- **Docker Hub sign-in:** the maintainer makes a new read-only token
  and runs `mynx clipboard get | gh secret set DOCKERHUB_TOKEN` (check
  `DOCKERHUB_USERNAME`, `est4s`, is their Docker Hub name). Then a
  build's "Sign in to Docker Hub" step should print "Login Succeeded",
  not the warning.
- Then merge.

### 1. The first release, v0.1.0

**First, the maintainer's changes before the release** (2026-10-08:
"a couple more things"; they'll say what, with a fresh agent). Then:

1. **The release key** (`docs/RELEASING.md` 1-3): the maintainer makes
   it, backs it up offline in two places, and sets the four
   `MYNX_RELEASE_*` secrets. An agent can guide (it did on
   2026-10-08) but never sees the password.
2. **Tag `v0.1.0`** (the maintainer; RELEASING.md 4). The run is the
   first `assembleRelease`, so the first time lint-vital runs: fix
   anything fatal on `main` and move the tag as RELEASING.md says. The
   release page should have `mynx-0.1.0.apk`.
3. **The move** (RELEASING.md, "Debug and release builds on one phone"):
   the maintainer's mynx is a debug build with the release's ID, so
   the release can't install over it. `scripts/backup-root.sh`,
   uninstall, install the release, `restore-root.sh` (entry 84). Then:
   `mynx about` says 0.1.0, `mynx install-apk` refuses (debug only).
   From then on: develop in mynx, test in mynx dev.
   **After the move** (maintainer's decision, 2026-10-08): keep the two
   scripts (a new phone or a reinstall needs them again), but trim
   RELEASING.md's "Debug and release builds on one phone" to mynx dev
   plus a short general note ("moving to a new phone: `backup-root.sh`,
   then `restore-root.sh`"), drop its builds-up-to-87 steps, and drop
   this item.

### 2. Test updates for real: v0.1.1

Merge any change and tag `v0.1.1`. In mynx 0.1.0, `mynx update`
checks at once: it should show 0.1.1 with its notes and size; `y`
downloads, and the installer opens (the first time Android asks to
allow installing apps: allow, run it again). After **Update**, mynx
comes back as 0.1.1 with tabs and Debian intact. Within a day the
notification and the menu's "Update available" should come on their
own (with `update-check` on).

### 3. What's new and release notes (planned, not built)

Maintainer's decision (2026-10-08): after every update, a What's new
page shows the notes of the versions since the one before (the tools'
version change already marks an update), and the menu gets "Release
notes": all of GitHub's release notes. Proposed: the release workflow
writes the notes of every release so far, plus the new one, into the
tools (`/opt/mynx/release-notes`), so both work offline and match
GitHub. Generated notes are commit subjects; maybe the maintainer
edits them in the release before tagging (decide then).

### 4. Guided GitHub setup (planned, not built)

Maintainer's decision (2026-10-08): a quick, optional GitHub setup,
never part of the first run. Proposed shape (agreed in principle):
- `mynx github` and a menu item (e.g. under System).
- Installs `git` and `gh` from Debian if missing, asking first.
- `gh auth login` in the browser; copy the one-time code to the
  clipboard (`mynx clipboard set`) so it only needs pasting.
- Sets git's `user.name` and `user.email` from the account, with
  GitHub's noreply address so the real email stays out of commits.
- Ends with a summary: who's signed in, what was set.
- mynx never stores a token: `gh` keeps its own login.

With `gh` signed in, `mynx report` already files issues itself, so this
makes bug reports one tap.

### 5. Smaller things

- **Bug reports, not tried yet:** the browser path without gh (`gh
  auth logout`, then `y` opens GitHub's filled-in new-issue page, `n`
  says "Nothing was sent."), and a report after a crash.
- **Maybe:** `CONTRIBUTING.md`, `SECURITY.md`.
- **Before Play (not urgent):** `release.yml` has its own
  `GITHUB_RUN_NUMBER`, so release version codes are far below debug
  ones; Play needs codes that only go up: pick a scheme (from the tag,
  or an offset) before the first upload. Play also restricts
  `REQUEST_INSTALL_PACKAGES`: a Play build needs a variant without it.

### Parked: roadmap step 8, Profiles

**Parked by the maintainer 2026-10-04** ("not such an important feature to
work on yet"): step 9 comes first. The plan below stands for when it's
picked up again.

README roadmap: "multiple profiles, switching, export/import"; README
section "Profiles: share your setup". Planned with the maintainer
2026-10-04. Build test-first, with a maintainer test after each part, as
in step 7.

**Maintainer's decisions (2026-10-04):**
- **All profiles share the one Debian** in step 8. A separate Debian
  per profile and full backup/restore of an environment come in a
  later step.
- **A profile per tab**, plus an **app-wide default profile**: new
  tabs and a fresh start use it; **⌄** next to **+** opens a tab in
  any profile.
- **Export is one plain-text file**: manifest, package list, setup
  script and config files inline, readable before import. Fonts are
  referenced (apt package or URL), never embedded.
- **Dotfiles: a shell snippet per profile** (aliases, prompt, env),
  sourced by `~/.bashrc` in that profile's tabs. The user's own
  dotfiles stay theirs.

**Design (agent's proposal, change it if the maintainer objects):**
- A profile is `~/.config/mynx/profiles/NAME/`, with the
  same layout as the config folder (`settings.conf`,
  `colors.properties`, `keybars/`, `menu.conf`) plus `profile.conf`
  (label, tab colour, icon), `packages` (apt names), `setup.sh` and
  `bashrc`. A profile only holds what it changes: its files lie over
  the top-level config (per file; `settings.conf` per key), so the
  existing config is the `default` profile and current installs keep
  working unchanged.
- `default-profile = NAME` in the top-level `settings.conf`.
- Each saved tab records its profile (`profile = NAME` in
  `filesDir/state/tabs`). The tab's shell gets `MYNX_PROFILE`;
  `mynx` commands act on that profile unless given `--profile NAME`
  (`--profile default` for the top level).
- Undo and `mynx check` cover the profile folders too.

**Parts:**
1. **8.1 Model and commands:** layering in `core` (tested);
   `mynx profile list/show/new/copy/rename/delete/default`, `--json`;
   `--profile` on the config commands; `mynx check` per profile.
2. **8.2 Profiles in the app:** tabs remember their profile and use
   its theme, font and key bars; the strip shows its colour; **⌄**
   opens a tab in a chosen profile; `MYNX_PROFILE` in the shell.
3. **8.3 Recipe:** `packages`, `setup.sh`, `bashrc` snippet (sourced
   via `/opt/mynx/shell.bash`); `mynx profile apply NAME`
   installs the packages and runs the script after showing them and
   asking.
4. **8.4 Export/import:** `mynx profile export NAME [FILE]` writes
   the text file; `mynx profile import FILE` shows everything it
   will write, install and run, asks, then adds the profile (applying
   it is a separate yes). Name clashes ask for a new name.
5. **8.5 Editors and docs:** a Profiles editor in `mynx edit`, a
   menu item, the agent guide (`tools/AGENTS.md`) and README.

### Regression checks (steps 4-6)
- **Step 6:** `mynx theme set` for a dark and a light theme (strip
  and key bar readable); the theme editor's live preview and `r` reset;
  font size from the editor and from pinch (kept after a restart);
  cursor style and blink; editing and resetting a key bar; editing and
  resetting the menu (`run` items, and a failing command explains
  itself); `mynx check` lists problems without dialogs.
- **Steps 4-5:** `files` (nnn bar, two rows, pages to swipe), game bars
  for `rogue`/`drive`/`flap` and the generic `game` bar, hold-to-repeat
  on arrows, each tab keeping its own bar.

### Hardware keyboard checks (later)
The maintainer has no hardware keyboard, so these are untested. Run them when
one is available (Bluetooth/USB keyboard), or with a soft keyboard that
sends real Ctrl/Alt key events (e.g. Hacker's Keyboard):
- Ctrl+Shift+T opens a tab; Ctrl+Shift+W closes the selected one
- Ctrl+Tab / Ctrl+Shift+Tab go to the next / previous tab, wrapping round
- Ctrl+Alt+1…9 jump to tab N (no-op past the last tab)
- Ctrl+Shift+PgUp / PgDn move the selected tab left / right
- Ctrl+Shift+R opens the rename dialog; Enter in it saves, Esc cancels
- holding a shortcut acts once (no row of new tabs)
- Ctrl+T, Ctrl+W, Tab, Ctrl+Alt+0 still reach the shell (e.g. Ctrl+W
  deletes a word in bash)
- Android doesn't swallow Ctrl+Tab or Ctrl+Alt+digits before the app sees
  them (if it does, pick other defaults in `tabShortcut()`)

### Small open items
- The wakelock is a setting (10.2); a notification action to toggle it
  (like Termux) could come later if people want it.
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

---

## Log

### 2026-10-10 (92): The agent bar takes the maintainer's layout (confirmed, PR #14)

Maintainer's request: the built-in `agent` bar becomes the copy they
use (`~/.config/mynx/keybars/agent.conf`): Esc Ctrl ↑ Tab Newline / on
top, ← ↓ → Enter Mode Ctrl+C below. New: Ctrl and Enter; ↑ sits above
↓. `KeyBarTest` pins the labels. Confirmed on the phone in mynx dev.

### 2026-10-10 (91): Codex asks to turn its sandbox off (confirmed, PR #13)

Codex in mynx dev couldn't run any command: "error building bubblewrap
command: cannot establish app-server socket mount isolation".
Reproduced with Codex 0.162.1 in a scratch home: its bundled `bwrap`
fails ("Can't read /proc/sys/kernel/overflowuid"), the legacy Landlock
mode panics (it requires bubblewrap too), and only
`danger-full-access` works. Maintainer's decision: tell the user during
the install that Codex only runs with its sandbox off, say briefly what
the sandbox is for, and ask; on no, ask whether to install it anyway
with the sandbox on (else nothing is installed). Built:
`Agent.sandbox` (Codex: `~/.codex/config.toml`), `sandbox_off()` puts
`sandbox_mode = "danger-full-access"` at the top (replacing a
top-level one, before any `[table]`), `--sandbox-off`/`--sandbox-on`
for scripts. Guide and README updated: the README says Claude Code
works (tested), Codex works with its sandbox off (tested), Gemini CLI
isn't tested (maintainer's wording). Already installed Codexes aren't
touched; the guide says how to add the line.

First phone round: Codex's installer asked its own "Start Codex now?"
and ran Codex inside the install step; quitting it exited 1, so mynx
called the install failed and never wrote the line (commands still
failed). Now the installer runs with `CODEX_NON_INTERACTIVE=1`
(`Agent.installer_env`) and mynx asks to start it, after the line is
written. Second round confirmed: the install finishes, mynx starts
Codex, and its commands run (so Codex reads `sandbox_mode` from
`config.toml`).

### 2026-10-10 (90): README for users only (PR #12)

Maintainer's request: only what's useful to users, like popular apps'
READMEs. Dropped the Roadmap section (one "Want to help?" line links
`docs/ROADMAP.md`); features ordered by what people come for, without
internals; numbered install steps; new "Getting started", "Good to
know" (proot, `wakelock`) and "Help and bug reports" (`mynx report`).
No Credits section (maintainer's decision): `mynx about` lists the
bundled components. Then the screenshots, taken by the
maintainer in mynx dev and chosen together: two rows of four under the
pitch (menu, agent, compass, drive; tuner, torch, spectrum, themes),
pngquant'ed to 16-71 KB in `docs/images/`. `docs/SCREENSHOTS.md` now
describes these.

### 2026-10-09 (89): The name in small letters, the pixel title, the setup logo (confirmed, PR #12)

Maintainer's decisions: the name is written "mynx" everywhere it's
shown (the app's label, "mynx dev", `mynx about` and update messages,
the User-Agent, the docs; code names keep their case), and the menu's
title is "mynx" in small pixel letters like the logo, without the
shadow (3 rows, 23 columns, `TITLE_ART` in `tools/bin/menu`; the boot
splash's text fallback is "m y n x"). The README's title is the whole
logo, `docs/images/mynx.svg`, made by `scripts/mynx-svg.py`. To check
on the phone (all confirmed): the launcher label "mynx dev", the
menu's title and pages (Getting started still fits), `mynx about`.

Then (same PR): the setup screen ("Setting up Debian…", first start)
shows the whole logo, as in the README, in place of the old
"▓▒░ MYNX ░▒▓" text: a vector drawable made by `scripts/mynx-svg.py
--vector`, kept in step by a test. **Confirmed** on the phone
(build of `9d2a359`, after clearing mynx dev's storage).

CI then failed twice on Docker Hub's anonymous pull limit (shared
runner IPs). The maintainer made a read-only Docker Hub token; both
workflows now `docker login` with the `DOCKERHUB_*` secrets.
The step retries and falls back to an anonymous pull with a warning
(Docker's auth server timed out for a while that night). **Not
working yet:** Docker Hub answers "incorrect username or password",
probably because `DOCKERHUB_TOKEN` holds a token since deleted (the
first one was pasted into a command by mistake and revoked). See
Next.

### 2026-10-09 (88): Menu order (confirmed, #10)

One of the changes before the release. The session that started it
crashed after writing the tests; the next one finished it. The main
menu is now Shell (was Terminal), AI agents, Apps, Games, Files,
Settings, System, Exit; Getting started moved into System (after
System info); Apps are sorted by label across `/opt/mynx/apps` and
`~/apps`. A user's own `menu.conf` is untouched. Welcome page, guide
and roadmap follow. Confirmed in Mynx Dev (build 99) after `mynx menu
reset`: a copied `menu.conf` keeps the old order, by design.

### 2026-10-09 (87): The new splash, and its "m" as the icon (confirmed)

The maintainer made a new title art, `mynx-art` ("mynx" in
quadrant-block pixels, cyan letters, pink drop shadow, animated). It's
now `tools/lib/mynx-art`, and the boot splash (`menu --boot`) shows it
in place of the glitching text title, with the boot log under it. A
key skips the art and the rest of the boot (exit 10); on a screen under
52 columns, or if the art fails, the old text title comes back. Its
Neon colours are built in (a test checks them against Neon's file) so
it doesn't read `/opt/mynx`. The maintainer's own copy in
`~/.local/bin` is theirs to keep or delete.

The maintainer also picked the "m" from it as the logo, over a stacked
"my/nx", the "x" alone and `>_` redrawn (concepts:
https://claude.ai/artifact/8woxiTt7jqsab8AAFMX5Pi). The launcher
foreground, the themed (monochrome) icon, the notification icon and
`docs/images/icon.svg` are now its pixels (1 wide, 2 tall, rows
overlapping a hair so no seams show), cut from `mynx-art --plain`;
the themed and notification icons leave the shadow out. PR #8,
confirmed on the phone in Mynx Dev; the details are in AGENTS.md.

### 2026-10-09 (86): Bluetooth on the roadmap

Maintainer's decision: Bluetooth joins the "later" plans
(`docs/ROADMAP.md`, "Bluetooth", and step 11). Debian can't reach the
Bluetooth hardware (no BlueZ under proot on Android), so it goes
through the app: classic serial (SPP) over the USB serial bridge, so
build that bridge first, and BLE as `mynx ble` requests and streams.
Ideas for the USB bridge from the same talk: a pseudo-terminal bound
at `/dev/ttyUSB0`; DTR/RTS (bootloader reset) can't cross a
pseudo-terminal, so either serve RFC 2217 on localhost (pyserial and
`esptool` take `rfc2217://`) or have proot catch the modem ioctls on
that port. Raw USB: `libusb_wrap_sys_device` on the fd Android hands
the app. Docs only, nothing built.

### 2026-10-08 (85): Docs cleaned up

The maintainer asked to drop what's old or no longer relevant from the
docs (git history keeps it).
- Work log: status rewritten as a short summary; "Next" keeps only
  open work (the release, testing updates, What's new, GitHub setup,
  small items) and the parked step 8 plan; the done step 9 and 10
  plans and entries 1-79 are gone (`git show 72b8512:docs/WORKLOG.md`
  has them).
- AGENTS.md: a "Lessons from the phone" section keeps the bugs that
  only showed on the phone (focus with no height, the cutout,
  browser shares, DCIM, lint-vital, `tools/` paths, Kotlin inference
  in CI); a "Keep it short" rule for the log; the old proot-distro
  dev setup is gone, and so is `scripts/bats-lite.sh` (only that setup
  needed it).
- **PRIVACY.md was wrong since the updates (entry 81):** it said the
  app connects to nothing. It now says what the daily update check
  sends (only the app's version; GitHub sees the IP), that downloads
  only happen on a yes, how to turn the check off, the install-apps
  permission, and what a bug report contains and when it's sent.
- ROADMAP and README mention updates; ROADMAP marks the done steps.
- RELEASING.md (PR #6): `gh secret set` needs `-R est4s/mynx` (run
  from the key's folder, outside the repo); the key steps now say to
  make it in your own tab (never through an agent), save the SHA-256
  fingerprint, check a backup opens, and delete it from the phone
  afterwards. The maintainer hasn't made the key yet.

### 2026-10-08 (84): Backup and restore for the move to the release

The maintainer asked for scripts for plan step 4 (uninstall the
debug-signed Mynx, install v0.1.0). `scripts/backup-root.sh [FOLDER]`
and `scripts/restore-root.sh [--yes]` (bats: `backup-root.bats`; the
TDD check pairs both with `tests/shell/`). Docs: RELEASING.md.
- Backup: `/root` as `root.tar.gz` without `~/.gradle`, `~/.cache`,
  proot's `.l2s.*` files and Claude Code versions other than the one
  `~/.local/bin/claude` points to; `apt-mark showmanual` as
  `packages.txt`; `SHA256SUMS`; `restore-root.sh` beside it. Refuses
  a folder that isn't empty, or one inside the home.
- Restore: checks `SHA256SUMS`, asks, extracts over `/root` (other
  files stay), `apt-get update` and installs the packages (one by one
  only if all at once fails, to name the failures).
- **proot's hard links survive tar:** proot shows each linked file as
  a plain file, so tar stores its contents; the `.l2s.*` files it keeps
  behind them can be left out. Checked: a tar of the repo without them
  passes `git fsck`.
- **Tried on this Debian:** backed up the real `/root` in 52 s, 156 MB
  (from 1.6 GB), restored it into a scratch home with apt stubbed: the
  repo is intact, the `claude` link works, `gh`'s login is back. The
  scratch copies were deleted (they hold logins).

### 2026-10-08 (83): `main` is protected

The maintainer asked for branch protection and picked "PRs only".
Ruleset "Protect main" (id 24724125): PRs required (0 approvals),
the `build` check required, no force-push or deletion; admins bypass
only when merging a PR (Markdown-only PRs never get a `build` check).
`scripts/deliver.sh` now finds the run of the current branch (a PR's)
as well as `main`'s. AGENTS.md: the build loop and "Branches".
`updates-core` goes in as the first PR.

### 2026-10-08 (82): Mynx Dev and the updates branch confirmed

CI run 37777568798 (`updates-core`, started by hand: branch pushes
don't build) was green, the first compile of the app side. Delivered
with `scripts/deliver.sh 37777568798`; it updated the Mynx Dev already
installed from `main`, not Mynx. The maintainer confirmed: Mynx Dev
installs beside Mynx with its own Debian (entry 80), its Settings
shortcut opens the editors in Mynx Dev, `mynx share` and `mynx camera`
work there, `mynx update` refuses ("this build of the app doesn't
update from GitHub releases") and `mynx settings` lists `update-check`.

### 2026-10-08 (81): App updates, built (branch `updates-core`)

Plan step 2 ("Updates" in "Next"), test-first, on the branch
`updates-core` (worktree `.claude/worktrees/agent-a6b292baa176cfe22`).
The first session crashed after the core pieces; nothing was lost.
- Core: a small JSON reader, semver `Version`, `parseRelease()` for
  GitHub's latest-release JSON (`mynx-X.Y.Z.apk` asset, sha256
  digest), `fetchLatestRelease()`, `downloadApk()` (through `.part`,
  size and sha256 checked, redirects followed by hand), `UpdateState`
  (daily, an hour after a failure, notify once per version), and
  `Updater`, which ties them together: background checks, the menu's
  notice file, and the [Later] `update-check` and `update-install`
  requests (progress streamed, a checked download reused, only the
  version the user saw is installed).
- Setting `update-check` (on): only the daily check; `mynx update`
  works either way.
- App: `TerminalService` ticks hourly, posts "Mynx X is available"
  (channel "App updates"); tapping it opens a tab running `mynx
  update` (`UPDATE_TAB_COMMAND`; MainActivity's settings-tab code is
  now `tabToOpen`). `REQUEST_INSTALL_PACKAGES` and `ApkProvider` moved
  to the main manifest (`app/src/debug/AndroidManifest.xml` is gone);
  `install-apk` stays debug-only. Release builds only: Mynx Dev
  refuses ("this build of the app doesn't update from GitHub
  releases").
- `mynx update [--check] [--yes] [--json]`; the menu starts with
  "Update available" while `/tmp/.mynx/update-available` exists.
- Docs: `tools/AGENTS.md` ("Updates", the setting, the command),
  AGENTS.md (architecture, why the permission is in the main
  manifest).
- The `app` module isn't compiled locally: the first CI build of the
  branch is its first compile.

### 2026-10-08 (80): Mynx Dev, a separate debug app

Planned app updates with the maintainer (see "Next"). Release APKs
can't install over debug builds (different keys), so the maintainer
asked for debug builds as a separate app, to have both on the phone,
and for a branch flow; offered git-flow (a `dev` branch) or
trunk-based. **Decided: trunk-based** (feature branch → PR → test the
PR's build in Mynx Dev → merge), switched on after v0.1.0. Also
decided: a What's new page after every update and release notes in
the menu.
- `app/build.gradle.kts`: debug `applicationIdSuffix = ".dev"`;
  `app/src/debug/res/values/strings.xml`: "Mynx Dev".
- Provider authorities (`share`, `photo`, `apk`) follow the ID:
  `${applicationId}` in the manifests, `BuildConfig.APPLICATION_ID` in
  code. Two installed apps can't share an authority.
- The launcher shortcut's XML can't use the ID, so
  `app/src/debug/res/xml/shortcuts.xml` is a copy naming the dev ID.
- Intent actions (`…SETTINGS`, `…EXIT`, `…SHELL`) stay: they're sent
  to an explicit package or component.
- Docs: AGENTS.md (naming, install loop, branches), RELEASING.md.
- Not checked on the phone yet (no local Android builds).
