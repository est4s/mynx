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
- **Released:** v0.1.0 and v0.1.1 (2026-10-10), v0.1.2 (2026-10-12), signed with the
  release key (certificate SHA-256 `24b7fefa…2b40`). The maintainer's
  daily mynx is the release, and it updated 0.1.0 → 0.1.1 → 0.1.2
  through `mynx update` (entry 81's updates, confirmed); 0.1.2 came
  from the daily check's notification and menu item (entry 106).
- **Battery** (`mynx battery`, the `/sys/class/power_supply`
  stand-in): confirmed and merged (entries 99-100).
- **Vibration patterns** (`mynx vibrate effect/wave/chain/list/stop`):
  confirmed on the phone (entry 102, PR #24).
- **Volume keys** (a key bar's `VolumeUp`/`VolumeDown` lines):
  confirmed on the phone (entry 103, PR #25).
- **Release notes** (`mynx notes`, Help → Release notes, the `pager`
  bar) and What's new after an update: confirmed on the phone with
  v0.1.2 (PR #26, entry 106).
- **Guided GitHub setup** (`mynx github`) and **bug reports**
  (`mynx report`): every path confirmed on the phone (2026-10-12).
- **Step 8 (*Profiles*) is parked** by the maintainer (plan below).
- **Workflow:** trunk-based, `main` protected (PRs only, entry 83).
  CI builds PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`.


## Next

### 0. Background services (built on branch `services`, PR #30; all phone tests passed 2026-10-13, entry 111)

Roadmap "Background services": hidden tabs for servers and daemons.
Build test-first, with a maintainer test on the phone after each part.

**Maintainer's decisions (2026-10-12):**
- **Defined in a config file**, `~/.config/mynx/services.conf`,
  which `mynx service add/remove` edit and `mynx check` checks.
- **Opening a service shows it as a tab**, marked as a service, with
  a `service` key bar. Closing that tab only hides it again; only
  Stop ends it.
- **Autostart is when the app starts.** Not at phone boot. Services
  that were running also come back after Android kills the app.
- **`wakelock = auto`**, the new default: hold the CPU awake only
  while a service runs. `on` and `off` stay as they are.

**Design (agent's proposal; change it if the maintainer objects):**
- **File format:** one `[name]` block per service, keys:
  `command` (run by `bash --login -c`, so the PATH and `~/.profile`
  apply), `cwd` (default `~`), `autostart = yes|no` (default no),
  `restart = no|on-failure|always` (default on-failure),
  `restart-limit` (quick failures in a row before giving up, default
  5, 0 = never give up), `restart-delay` (first wait in seconds,
  default 1, doubling up to 60), `stop-timeout` (seconds, default
  10). Names: `[a-z0-9-]`, up to 20.
  Parsing and checking go in core's `Services.kt`, which joins
  `checkConfig` and undo like the other files.
- **A service is a `TerminalSession`**, kept in the service's own
  list (`Services<S>` in core, generic like `Tabs<S>`), not in
  `Tabs`. It has a pty, 2000 lines of scrollback, and keys can be
  typed into it. It gets the same `prootLaunch` as a tab (tools, TZ,
  sound, power supply, `MYNX_SHELL`), plus `MYNX_SERVICE=NAME`.
  **Gotcha:** a session only starts on its first `updateSize()`,
  which normally comes from `attachSession()`. A hidden one would
  never start, so the service calls `updateSize(80, 24)` itself.
  Check the 0.118.3 signature first.
- **The wrapper, `tools/lib/service-run NAME`** (from the spike,
  below): the session runs `bash --login -c 'service-run NAME'`, and
  the wrapper reads the command from `services.conf` (or is given it,
  for an adopted tab). It runs the command in a process group of its
  own that keeps the terminal, so typing and Ctrl+C reach it. It
  writes the group's id to `/tmp/.mynx/services/NAME.pid`, and when
  the command ends it writes its exit code to `NAME.exit` (128+N for
  signal N). The app takes the exit code from that file, never from
  the session.
- **Stopping cleanly:** databases need TERM, and proot ignores it.
  The app sends TERM to the group in `NAME.pid`, waits up to
  `stop-timeout`, then the usual `stopProot()` (QUIT, then
  `killProot()`, which the spike showed leaves nothing behind).
- **Restarts:** generalise `ServerRestarts` (backoff 1 s to 60 s,
  reset after 30 s up) into `RestartPolicy`, with the service's own
  `restart`, `restart-delay` and `restart-limit`. `on-failure`
  restarts on a non-zero exit, `always` on any exit. After
  `restart-limit` quick failures in a row it gives up and posts a phone notification ("web exited
  (code 1), not restarting"), tapping it opens the service. A
  service stopped by `mynx` is never restarted.
- **State:** `filesDir/state/services` records which services run.
  On a fresh start the service runs those plus the `autostart`
  ones. Exit in the notification clears it, as it does the tabs.
- **Lifetime:** closing the last tab no longer exits while services
  run. The app stays in the background, and the notification says
  "2 terminals · 1 service" (`runningTerminalsText()`). Opening the
  app with no tabs opens a fresh one as usual.
- **Wakelock:** `wakelock = auto | on | off`. `auto` holds it while
  any service runs, using the existing `applyWakelockSetting()`.
  An old `on`/`off` keeps meaning what it did.
- **Commands** (`mynx service`, all with `--json`), as requests to
  the app answered in core:
  `list` (name, state, pid, uptime, restarts, last exit code),
  `start/stop/restart NAME`, `log NAME [-n N]` (the session's
  transcript, from the emulator), `open NAME` (shows it as a tab;
  the app must be on screen), `add NAME [--autostart]
  [--restart …] [--cwd DIR] -- COMMAND…`, and `remove NAME` (stops
  it first). `start` with no saved service of that name refuses.
- **In the app:** an open service is a `Tab` with `service = NAME`.
  The strip shows it with a mark (e.g. `● web`). Closing it hides it
  and doesn't stop it. Its exit doesn't close the tab: the status
  shows in the title until it restarts. `keybar` picks the `service`
  bar: Hide, Restart, Stop, Ctrl+C, arrows.
- **Menu:** a `services` action, "Services" in the built-in menu
  after AI agents. It lists them with their state; picking one
  offers Open, Start/Stop, Restart and Log. It shows a short how-to
  when there are none.
- **Seeing them from anywhere** (maintainer's decision, 2026-10-12):
  while any service runs, the strip shows `● N` next to **+**;
  tapping it opens the Services list (the menu's, in a new tab).
  The app's ongoing notification gets a **Services** button that
  does the same.
- **Turning a tab into a service** (maintainer: nice to have, last
  part): the tab's long-press menu gets "Run as service", and
  `mynx service adopt NAME` does it from inside the tab
  (`MYNX_SHELL` says which). The session moves from `Tabs` to the
  services as it is, so nothing restarts. It's an *unsaved*
  service: `restart = no`, not in `services.conf`, and it ends if
  Android kills the app (a running process can't be brought back).
  `list` marks it unsaved. Its foreground command can be read from
  `/proc` (bash's `tpgid` → that process's `cmdline`), so "Save"
  can offer a `services.conf` block with that command and the
  folder, to edit before saving.
- **Ports:** nothing below 1024 (proot's fake root). The guide
  says so, with `sshd -p 8022` as the example.

**Spike results** (2026-10-12, PR #29, mynx dev, closed unmerged):
- A session started with `updateSize(80, 24, 10, 20)` and never
  attached runs: `python3 -m http.server 8080` served the phone's
  browser, also after 4 min with the screen off. Its transcript can
  be read (`emulator.screen.transcriptText`).
- A death by signal reads as exit 0: proot reports
  "terminated with signal 15" and exits 0, even with
  `cmd; exit $?` (TERM to all tracees kills that bash too). Normal
  codes come through (`exit 7` → 7). Hence the wrapper.
- `stopProot()` on a running service leaves no process behind.

**Request protocol** (the contract between core, `mynx` and the
app; args are strings, replies JSON with `ok`):
- `service-list` → `services`: objects with `name`, `saved`
  (false for an adopted tab), `command`, `cwd`, `autostart`,
  `restart`, `state` (`running`, `stopping`, `stopped`, `failed`
  when it gave up), `pid`, `started` (ms since 1970 or null),
  `exit_code` (last, or null), `restarts`, `open` (shown as a tab).
- `service-start NAME`, `service-restart NAME`: answered once it's
  started. `service-stop NAME`: a [Later], answered once it has
  stopped (`stop-timeout` and the force included).
- `service-log NAME [LINES]` → `log` (the transcript, last LINES,
  default 100).
- `service-open NAME`: shows it as a tab (app on screen).
- `service-add NAME KEY=VALUE…`: `command=…` required; `cwd`,
  `autostart`, `restart`, `restart-limit`, `restart-delay`,
  `stop-timeout` optional. Refuses a name that exists.
  `service-remove NAME`: stops it, removes its block, keeps the rest
  of the file and its comments.
- `service-adopt NAME SHELL`: the tab whose `MYNX_SHELL` is SHELL
  becomes the unsaved service NAME. `service-save NAME [command=…]`
  writes an adopted one to `services.conf`.
- Errors as elsewhere: `ok: false`, `error` ("no service named
  web", "web is already running", …).

**Parts:**
1. **Core and commands:** `Services.kt` (format, check, add/remove
   keeping the rest of the file), `RestartPolicy`, the requests,
   `mynx service list/start/stop/restart/log/add/remove`. App:
   hidden sessions, clean stop, restarts, notifications. Phone test:
   `python3 -m http.server 8080` as a service, reached from the
   phone's browser, stopped and restarted, killed to see a restart.
2. **Opening as a tab:** `mynx service open`, the strip mark, hide
   on close, the `service` key bar.
3. **Autostart, restore, lifetime and wakelock:** the state file,
   staying alive with no tabs, `wakelock = auto`, the notification
   text. Phone test: swipe the app away, reopen it, and see the
   services back.
4. **Menu and docs:** the Services menu, `tools/AGENTS.md` (a
   "Background services" section and the commands table), README,
   AGENTS.md architecture, and the roadmap entry leaves.
5. **Strip indicator and notification button:** `● N` next to
   **+**, the notification's Services button.
6. **Turning a tab into a service (nice to have):** "Run as
   service", `mynx service adopt NAME`, and saving it to
   `services.conf` from its foreground command.

**Phone tests** (mynx dev, PR #30's build; numbered so results can
be reported by number):

*Setup.* In mynx dev: `mynx service add web -- python3 -m http.server
8080`, then `mynx service list` (web, stopped).

*A. Running hidden (part 1)*
1. `mynx service start web`; `mynx service list` shows it running;
   `mynx open http://localhost:8080` lists the home folder.
2. `mynx service log web` shows the server's lines.
3. `mynx service stop web` returns within a second or two;
   `list` shows `stopped (143)`.
4. Restart: `mynx service start web`, then in a tab
   `pkill -f http.server`: it comes back within a few seconds
   (`list`: restarts 1).
5. Gave up: `mynx service add bad --restart-limit 2 -- false`,
   `mynx service start bad`: after the restarts, a notification
   "bad stopped (code 1), not restarting"; `list` shows failed.
   Then `mynx service remove bad`.

*B. Opening as a tab (part 2)*
6. `mynx service open web` (or Services → web → Open): a tab
   `● web` with the service bar, the server's output. Ctrl+C in it:
   Python's server catches it and exits 0, so `on-failure` doesn't
   restart it ("○ web: exit 0", Enter does nothing); long-press →
   Start runs it again in the same tab.
7. × on that tab hides it; the browser still loads; `list` says not
   open.
8. Long-press the opened tab: Hide, Restart, Stop work; after Stop
   the title is "○ web: stopped" and the menu offers Start.

*C. Coming back, lifetime, wakelock (part 3)*
9. With web running, swipe the app away and reopen: shell tabs come
   back, web runs hidden (`list`, browser). This is the first test
   of a hidden session that was never shown after a restart.
10. Close the last tab with web running: the app goes, the
   notification stays ("No terminals · 1 service"). Reopen: a fresh
   tab. Stop web: with no tabs left, the app exits.
11. Exit from the notification with web running; reopen after 5 s:
   no service, and `ps aux` shows no python or leftover proot.
   Exit and reopen within 3 s: a fresh tab, the notification stays.
12. `mynx get wakelock` says `auto`; the notification says "wakelock
   held" only while a service runs.

*D. Menu, mark, notification button (parts 4-5)*
13. With web running, `● 1` shows right of **+**; tapping it opens
   the Services list. The notification's Services button does the
   same, also with the app in the background.
14. Services → web: Open, Stop/Start, Restart and Log work; Log
   opens in the pager.

*E. Run as service (part 6)*
15. In a tab: `cd ~/Shared && python3 -m http.server 9000`, long-press
   → Run as service. The name is prefilled `python3`; type `web2`.
   The tab becomes `● web2`; `list` shows `web2*`.
16. Long-press → Restart: it still serves `~/Shared` (browser on
   :9000). `mynx service save web2` writes it to `services.conf`.
17. `mynx service remove web2` with its tab open: the tab closes
   after the stop.

*F. Small checks*
18. Run `htop` as a hidden service (`mynx service add top -- htop`,
   start): the shown tab's cursor keeps blinking. Remove it after.
19. After several restarts, `ls /tmp/.mynx/` doesn't collect
   `cwd-N`/`keybar-N` files.

**Open, for the maintainer** (minor, from the review, not fixed):
- A block deleted by hand from `services.conf` while it runs stays
  in `list` as unsaved after it ends, until `mynx service remove`
  or an app restart.
- `mynx service list` reloads `services.conf` but doesn't refresh
  the app: the strip mark and tabs of services removed by hand catch
  up at the next service change.

### 1. Smaller things

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

### 2026-10-13 (111): Background services confirmed on the phone

The maintainer ran all 19 phone tests in "Next 0" on mynx dev (run
38324395547's build): all pass. One bug found and fixed (6f47077,
run 38348748133 green): `mynx` crashed (FileNotFoundError on
`ID.wait`) when the app answered a [Later] at once, since it writes
`ID.wait`, then `ID.reply`, then deletes `ID.wait`. The race was
old, but every `service-*` request answers that way. Test 6's
expectation was wrong, not the code: a program that catches Ctrl+C
and exits 0 isn't restarted under `on-failure`; kept as it is (the
long-press Restart is for restarting). Still open: the two minor
review items under "Open, for the maintainer"; then merging PR #30.

### 2026-10-13 (110): Review of the services branch, seams fixed

Reviewed `main...services` for bugs at the seams between the four
parts. CLI ↔ core requests and reply fields, the menu's `list --json`,
`serviceCommand` quoting and the `NAME.pid`/`NAME.exit` folder (same
`rootfs + /tmp/.mynx/services` on both sides) all match. Fixed,
test-first:
- `service-run`: a command given on the line (an unsaved/adopted
  service) ran at home; it now runs in the folder the app starts it in
  (the tab's). A name defined twice ran the last block; now the first,
  as core reads it.
- `ServiceManager`: any exception in a `service-*` request (not only
  `ServiceRefused`) is a refusal. Before, it escaped `processPending`
  before the `.req` was deleted, failed again at every poll and held
  up the requests after it.
- Menu: a failed Start/Stop/Restart waited for two keys.
Not changed: a service whose block is deleted by hand while it runs
stays listed as unsaved after it ends (like an adopted one), and
`list`'s reload doesn't refresh tabs or the mark until the next change.
Phone tests for parts 1-6 are still pending.

### 2026-10-13 (109): Service tabs, the strip's mark, Run as service (parts 2, 5, 6)

Not yet tried on the phone. Core: `Tab.service`, `Tabs.openService`/
`followService`/`makeService`/`setServiceTitles`/`closeServicesExcept`,
service tabs left out of `state/tabs`; `serviceTabTitle()` and
`ServiceManager.tabTitle()` ("● web", "○ web: exit 1", "restarting",
"failed", "stopped"); `keyBarNames(fallback =)` (the `service` bar);
`ServiceHost.started()` (an open tab follows a restart) and
`adopt(name, shell)`; `ServiceManager.start/stop/restart/adopt(name)`
for the app; `foregroundCommand()` (`/proc`: the shell's `tpgid`, the
group leader's `cmdline`) and `suggestServiceName()`;
`servicesMark()`, `SERVICES_TAB_COMMAND` (`menu services`, a new
entry point of the menu). App: service tabs (× hides), long-press
menus (Rename / Run as service; Hide / Restart / Stop or Start), `● N`
right of +, the notification's Services button, service sessions'
cwd/keybar files removed once unused, the cursor blinker asks the
shown terminal. Decisions: an adopted tab stays open as the service's
tab; Hide/Restart/Stop live in the long-press menu (no app-handled key
bar action); a tab adopted by `mynx service adopt` from inside runs
`mynx` at that moment, so its command is `bash` until saved, and an
unsaved service restarts with its current foreground command. Docs:
tools/AGENTS.md, AGENTS.md architecture, README (wakelock auto).

### 2026-10-13 (108): Services in the app (parts 1 and 3, branch `services`)

Not yet tried on the phone. Core: `prootLaunch(service =)` sets
`MYNX_SERVICE`; `serviceCommand()`/`shellQuote()` run the wrapper;
`serviceWorkDir()`; `ServiceManager.stopAll()`/`active`;
`serviceGaveUpText()`; `wakelock = auto|on|off` (default auto, the
`Wakelock` enum); `runningTerminalsText(services =)`. App:
`ServiceSessions` (the `ServiceHost`: hidden sessions started with
`updateSize(80, 24, 10, 20)`, TERM to the group then `stop()`;
`open`/`adopt` refuse until parts 2 and 6), wired in
`TerminalService`: requests, launch at start, `state/services`,
"Services" notification channel, lifetime with no tabs (the activity
finishes; the app exits when the last service ends), Exit stops them
(TERM, 3 s, then onDestroy forces). `tools/AGENTS.md`'s `wakelock`
line still says on/off: part 4 (docs) updates it.

### 2026-10-12 (107): Background services planned, spike on the phone

Planned with the maintainer ("Next 0": decisions, design, request
protocol, six parts). A throwaway spike (PR #29, branch
`spike-hidden-session`, closed unmerged) showed that a hidden session
runs and keeps running with the screen off, and that proot reports a
death by signal as exit 0, which led to the `service-run` wrapper.
Work is split for agents overnight on the branch `services`.

### 2026-10-12 (106): v0.1.2 confirmed on the phone

The maintainer's daily mynx found v0.1.2 by itself: the notification
came and the menu showed "Update available"; the menu item ran
`mynx update` and it installed. Afterwards the restored tab showed
"What's new in mynx" with v0.1.2's notes, once; a new tab showed
nothing; Help → Release notes lists v0.1.2, v0.1.1 and v0.1.0.
`notes-seen` holds `0.1.2` and the update marker is gone. With that,
entries 104 (release notes, What's new) and 105 (v0.1.2, the release
title fix in `release.yml`) left the log: what they decided is in
`AGENTS.md` ("Release notes", "Releases"), the rest in git history.
"Next" item 1 (check What's new) is done.

### 2026-10-11 (103): Volume keys (branch `volume-keys`)

The maintainer's approved design, built test-first:
- A key bar's `VolumeUp`/`VolumeDown` lines (label in any case) say
  what the phone's volume keys send while that bar shows; they aren't
  buttons and take no room. Same syntax as a button's keys; they
  repeat only with `Repeat` (unlike arrow buttons).
  `core/.../KeyBar.kt`: `ParsedKeyBar`/`LoadedKeyBar` gain
  `volumeUp`/`volumeDown`; a second line for the same key is a
  problem ("VolumeUp is already set on line N"), the first good one
  counts; bad keys are problems as for buttons, so `mynx check`
  reports them. `volumeKeyAction()` decides: Pass (bar doesn't set
  it, the volume changes), Press, Swallow (Android's repeats of a held
  key), Release.
- app: `MainActivity.dispatchKeyEvent()` gives volume keys to
  `KeyBarView.onVolumeKey()` before any view, so it works whatever has
  focus; it sends with the buttons' `press()` (sticky Ctrl included)
  and repeats with their timer (400 ms, then every 50 ms). The repeat
  also stops when the window loses focus (a key released outside the
  app).
- No built-in bar sets them; no long press, no setting (decided).
- Docs: tools/AGENTS.md "Key bars", README, AGENTS.md architecture;
  ROADMAP: Volume keys left "Next".
- Added after review: `KeyBarView.refresh()` stops a held key's
  repeat when the bar changes (a program switching bars mid-hold
  sent the release to Android, and the repeat ran on).
- **Confirmed on the phone** (2026-10-12, mynx dev, build of
  `c7f5a34`): the shell bar with `VolumeUp = Up Repeat` and
  `VolumeDown = Down`: short presses move history, only VolumeUp
  repeats when held; `files` still changes the volume and the shell
  gets them back after; keyboard up and down; `mynx check` reports a
  duplicate line.

### 2026-10-11 (102): Vibration patterns (branch `vibration-patterns`)

The maintainer's approved design, built test-first:
- `mynx vibrate [MS] [--strength PCT]`, `effect NAME`, `wave STEP…
  [--repeat]`, `chain PRIM…`, `list`, `stop`. Plain MS still returns
  at once (`vibrate`, now with `strength=PCT`); effect, wave and chain
  wait until the vibration ends; Ctrl+C stops it (a repeating wave's
  Ctrl+C exits 0, the others 130 like `audio play`).
- `core/.../Vibration.kt`: `Vibrations` checks steps, primitives,
  limits (60 s, 1000 wave steps, 100 primitives, 10 s pauses) and API
  levels against the app's `VibratorInfo`, refusing what the phone
  can't do with a reason; works out the length (primitive durations
  from Android 12, else estimates; effects are estimates, Android
  doesn't say) and answers when it's over. One vibration at a time:
  a new one or `stop` answers the waiting one `"stopped": true`.
- `app/.../Haptics.kt` (`PhoneHaptics`) replaces
  `TerminalService.vibrate()`; Android 13+ vibrates with
  `VibrationAttributes` `USAGE_MEDIA` (design said "USAGE_TOUCH or
  similar": touch is UI feedback and goes off with the phone's touch
  feedback setting; media vibration is what app content uses).
- **Deviation:** `vibrate-list` and `vibrate-stop` are [Later]
  requests answered at once rather than plain ones, so stop can end a
  waiting vibration and everything stays in `Vibration.kt`; to `mynx`
  they look the same.
- No new setting; the metronome is untouched.
- Unrelated: `test_undo_list` fails when `TZ` isn't UTC (this tab's
  is Europe/Helsinki); CI is UTC.
- **Confirmed on the phone** (mynx dev, PR #24's build): `list`
  shows strength control, all four effects and all eight primitives
  with lengths (click 29 ms … slow-rise 500); each effect, a wave with
  pauses, `--repeat` with Ctrl+C, `stop` from another tab and a chain
  all work. In Vibrate mode `effect click` still clicks and the
  metronome still buzzes, so `USAGE_MEDIA` stays. Mute not tried.
- ROADMAP: Battery left "Next" (it shipped in #21-#22).

### 2026-10-11 (101): Docker Hub sign-in, backup docs, housekeeping

- **Docker Hub:** the token secret was wrong. The maintainer made a
  new read-only token (`mynx-build`), set from the clipboard with
  `gh secret set DOCKERHUB_TOKEN`; a re-run of main's build (run
  38149539484) printed "Login Succeeded".
- **Backup:** `backup-root.sh` defaults to `Download/mynx-backup`
  (apps can't make top-level folders) and ends with the restore
  path: share the four files to mynx from the Files app, then `bash
  ~/Shared/restore-root.sh` (a reinstalled app can't read the old
  one's files in Download). `restore-root.sh` now says to delete the
  four files, not `rm -r` the folder, which is now `~/Shared`.
  `BACKUP_STORAGE` lets the tests use a fake phone storage.
  **Confirmed on the phone:** a scratch backup from mynx, its four
  files shared to mynx dev, landed in `~/Shared` under their own
  names; `restore-root.sh` checked the sums, `n` changed nothing, `y`
  restored and installed all 22 packages (3m46s with the JDK).
- Both scripts give the tarball's own size (`stat`, `numfmt`):
  `du -h` showed a 159-byte one as 4.0K (whole blocks), and "from
  /root/Shared (4.0K)" read as the folder's size.
- `docs/RELEASING.md`: "Debug and release builds on one phone" cut
  to "mynx dev beside the release" and "Moving to a new phone"; the
  builds-up-to-87 steps are gone.
- Work log: the move, v0.1.1 and the update test are done
  (maintainer: 0.1.0 → 0.1.1 came through `mynx update`).

### 2026-10-11 (100): The battery stand-in, step 2 (branch `battery-sysfs`)

Picked up after a crashed session: its core work was uncommitted
but complete. A Gradle daemon from that session was left untraced
by proot ("Function not implemented" on every file call, see
"proot notes") and hung every build until it was killed.
- `core/.../PowerSupply.kt`: `powerSupplyFiles()` turns a
  `BatteryReading` into `battery/` (type, present, status, health,
  capacity, technology, temp, voltage_now, current_now/avg,
  charge_now, charge_counter, cycle_count, time_to_full_now, uevent)
  and `ac`/`usb`/`wireless` (type, online, uevent);
  `writePowerSupply()` updates a folder in place. Battery.kt's unit
  helpers became `internal` so both share them.
- `prootLaunch(powerSupply = …)` binds it at
  `/sys/class/power_supply`. `TerminalService` keeps a battery
  stream (every 30 s too) writing `filesDir/power-supply` from
  `onCreate` until `onDestroy`; shells bind it. The sound server's
  proot doesn't.
- Docs: the user guide's `mynx battery` item, AGENTS.md
  architecture.
- On the phone (run 38139887743): streams, `--every`, `--json` work;
  `ac/online` follows a USB-C charger (Android calls it AC). `acpi -b`
  said 0% (no `charge_full`: now worked out from charge ÷ level) and
  psutil a negative `secsleft` (now `current_now` is the size only,
  maintainer's decision). Both fixed and **confirmed** (run
  38145343394): `acpi -b` 52%, psutil a positive `secsleft`,
  starship's battery module shows.

### 2026-10-11 (99): `mynx battery`, step 1 of 2

Roadmap "Next" (PR #19/#20): battery, vibration patterns, volume keys.
`mynx battery` shows everything Android gives without a permission:
level, status and plug, time to full, temperature, health, voltage,
current now and on average, charge (mAh), cycles (Android 14+),
technology, battery saver and the thermal status. `--stream` prints a
line when something worth knowing changes (not voltage or current
alone), `--every SECONDS` at least that often; `--json` with
snake_case keys. Core (`Battery.kt`) turns Android's raw numbers into
JSON and handles the phone's "don't know" values (`Int.MIN_VALUE`,
-1, 0) and volts-for-millivolts; while discharging the current always
flows out (phones disagree on its sign); no unit guessing for µA vs mA.
`BatteryReader` reads the sticky battery broadcast, BatteryManager and
PowerManager on the main thread. Decided (maintainer): no
`android-battery` setting (no permission, like torch and vibrate).
Tests: `BatteryRequestTest`, `test_mynx.py` (battery). Not yet on the
phone. Seen while testing: `test_undo_list` fails unless `TZ=UTC`
(depends on the tab's time zone since entry 98) and
`test_audio_play_stops_quietly_on_ctrl_c` is flaky; both untouched.

### 2026-10-10 (98): The phone's time zone in Debian

Debian ran on UTC: nothing told it the phone's zone. Each tab now gets
`TZ` from Android's default zone (`debianTimeZone()` in core's
`TimeZone.kt`): the zone's name when Debian has its zoneinfo file,
else a fixed POSIX offset (`<+0530>-5:30`, checked with `date`).
Decided: `TZ` in each tab rather than pointing `/etc/localtime` at the
zone, which would change the user's Debian; `TZ` in `.bashrc` still
wins. Tabs read the zone when they start, so after travelling only new
tabs follow. Tests: `TimeZoneTest`, `ProotLaunchTest`, `home-docs.bats`. Also installed
the JDK, missing since the move. Confirmed on the phone (mynx dev,
run 38067778668): a new tab shows local time.

### 2026-10-10 (97): Claude Code: messaging socket and fullscreen

After the move, Claude Code in the fresh Debian said "Cross-session
messaging is off" (no uid map under proot), and its input no longer
stuck to the bottom: its boot canary had recorded two fullscreen starts
that died early and turned fullscreen off for 2.1.296
(`fullscreenAutoDisabled` in `~/.claude.json`). Now
`mynx agent start NAME [ARGS…]` passes ARGS, and for Claude Code adds
`--messaging-socket-path ~/.cache/mynx/claude-msg/PID-TIME.sock`
(stale ones cleared) and `CLAUDE_CODE_NO_FLICKER=1` unless
settings.json has `"tui": "default"` (what `/tui default` saves) or
the variable is set. The shell's `claude`/`codex`/`gemini` functions
now call `mynx agent start`, which already did the key bar. Decided:
force fullscreen rather than clear the canary's record in
`~/.claude.json`, which running sessions rewrite. Tests:
`test_agents.py` (`LaunchTest`, `StaleSocketsTest`), `test_mynx.py`,
`shell-setup.bats`. Confirmed on the phone (mynx dev, PR #18 build):
`claude` from the shell and the menu starts with no messaging warning,
input at the bottom; after `/tui default` a new `claude` is inline, as
asked. The maintainer's `~/.local/claude-wrap` (see `~/AGENTS.md`) can
go once a release has this.
Also `scripts/deliver.sh`: after the move it failed copying to
`Download/mynx-build.apk`, which the uninstalled debug-signed mynx made
and this one can't overwrite. Each run is now `mynx-dev-RUN.apk` (its
own older ones deleted), and from the release mynx, where
`install-apk` is refused, it says to open the file in Files. Tried on
the phone with run 38058304575.

### 2026-10-10 (96): The first release, v0.1.0

The maintainer made the release key in their own tab (RSA 4096,
PKCS12, alias `mynx`), backed it up on two offline copies (checked on
Windows with KeyStore Explorer: same file hash and certificate
fingerprint), set the four `MYNX_RELEASE_*` secrets and pushed
`v0.1.0` at `cbbacc2`. The release run (38050617435) passed first time,
lint-vital included, and published `mynx-0.1.0.apk` (106 MB); its
signature check printed certificate SHA-256 `24b7fefa…2b40`, matching
the key. The key was deleted from the phone.

Lessons:
- Long commands pasted into a 56-column tab broke at the screen edge
  and ran in halves; guide with short lines (or variables) instead.
- `backup-root.sh` can't use its default folder, and another app can't
  read what it writes to `Download` (Next, item 1.2). The maintainer
  chose to move without a backup; the backup was deleted.
- Docker Hub sign-in still fails (item 0); builds pass anonymously.

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

