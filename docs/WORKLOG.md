# Work log

Where the project stands, what's next, and what recent sessions did.
Newest entries first. Rules for keeping it up to date (and short): see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps). Older entries
are in git history: the full log up to entry 92 is
`git show be3d232:docs/WORKLOG.md` (up to 84: `72b8512`).

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

### 0. Docker Hub sign-in

Both secrets are set, but builds still say "unauthorized: incorrect
username or password" and pull anonymously (run 38036141815). The
token is probably one since revoked, or `DOCKERHUB_USERNAME` (`est4s`)
isn't the maintainer's Docker Hub name. Fix: a new read-only token,
`mynx clipboard get | gh secret set DOCKERHUB_TOKEN`; the next build's
"Sign in to Docker Hub" step should print "Login Succeeded".

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

### 4. Guided GitHub setup: try it on the phone

Built (entries 94-95, branch `github-setup`). In mynx dev, the
main menu's "Set up GitHub" (System → GitHub setup once done):
- **Confirmed (2026-10-10, build of `1f8b0db`):** signed out, "Set up
  GitHub" shows in the main menu after AI agents.
- **Signed out:** after a moment `gh` shows the one-time code, with
  "(The code is copied: paste it on GitHub's page.)" under it; Enter
  opens the browser, paste, approve. Then the summary: the account,
  git's name and noreply email. `git push` to a repo should work with
  no password.
- **Signed in:** it asks "Switch to another account?"; `n` keeps it.
- **git name/email already set to something else:** it asks first.
- **Without git or gh** (`apt remove gh`): it offers to install them.
- Things to watch: the key presses reach `gh` (Enter, Ctrl+C), the
  screen isn't garbled, nothing odd after `gh` ends.

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

### 2026-10-10 (95): Menu: a Settings submenu (branch `github-setup`)

The main menu loses System: Settings is now a submenu, App settings
(`mynx edit`), System (Update all, System info, GitHub setup) and Help
(Getting started, Report a bug, About). New `menu.conf` actions:
`settings` is the submenu, `app-settings` the editors, `help` the Help
menu. Until gh is signed in (its `hosts.yml`, following
`GH_CONFIG_DIR`) and git's email is set, the main menu has "Set up
GitHub" after AI agents (last without them, whatever `menu.conf`
says) and System leaves it out; then it moves to System (maintainer's
decision, after first dropping it). Docs (README, PRIVACY,
`tools/AGENTS.md`, `welcome.txt`, AGENTS.md) follow. The session that
started this crashed; the next one finished the docs. Not yet tried on
the phone.

### 2026-10-10 (94): Guided GitHub setup, built (branch `github-setup`)

`mynx github` and System → GitHub setup (menu item at the end of
System, so "Report a bug" keeps key 5). It installs `git` and `gh`
from Debian if missing (asking; `apt_install()` now shared with
`mynx sound install`), runs `gh auth login --web --hostname
github.com --git-protocol https` on a pseudo-terminal of its own
(`run_in_pty()`): it passes keys in, reads the `one-time code: XXXX-XXXX`
line from gh's output, sends it to `clipboard-set` and adds a line
saying so. Already signed in: asks to switch (maintainer's decision
this session). Sets git's name (account name, else login) and email
(`ID+login@users.noreply.github.com`), asking before replacing others;
`--yes` installs and replaces without asking and keeps the account.
Then `gh auth setup-git` and a summary. `mynx github status [--json]`
changes nothing.
- **Why the credential helper is set before signing in:** with no
  gh helper in git's config, gh first asks "Authenticate Git with your
  GitHub credentials?" (and queries the cursor position, which needs a
  real terminal). `gh_for_git()` adds `""` and `!gh auth git-credential`
  for `https://github.com`, so gh skips it.
- Checked against the real gh 2.46 here (scratch `GH_CONFIG_DIR` and
  HOME, signed in nowhere): the code was copied and the note shows
  right under the code line. Not yet tried on the phone with a real
  sign-in: Next 4.
- Tests: `tests/mynx/test_mynx.py` (fake gh and git on a bare PATH),
  `tests/shell/menu.bats`. The roadmap's "Guided GitHub setup" left
  `docs/ROADMAP.md`; README and `tools/AGENTS.md` describe it.

### 2026-10-10 (93): Docs cleaned up again

Maintainer's request: drop what isn't needed. `docs/SCREENSHOTS.md`
is gone (the images stay: the README shows them). `docs/ROADMAP.md`
now holds only what's planned (next, profiles, tab strip and file
manager extras, per-profile permissions, boards, Bluetooth, Play) plus
"How it works"; the built features are the README's. The USB/BLE
ideas from entry 86 moved into it. The maintainer added two plans:
the guided GitHub setup (moved from "Next" to "Planned") and hidden
tabs for background services and servers (new, not designed yet). Confirmed entries 80, 82, 83 and
85-92 left the log (Codex's sandbox went into AGENTS.md's lessons);
"Next 0" is now just the Docker Hub sign-in, which still fails.

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

