# Work log

Where the project stands, what's next, and what recent sessions did.
Newest entries first. Rules for keeping it up to date (and short): see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps). Older entries
are in git history: the full log up to entry 191, with every plan and
phone test, is `git show 22c111c:docs/WORKLOG.md` (up to 92:
`be3d232`, up to 84: `72b8512`).

---

## Current status

- **Released:** v0.1.0 to v0.4.0 (v0.4.0 on 2026-11-01: Bluetooth
  serial, BLE, BLE UART ports), signed with the release key
  (certificate SHA-256 `24b7fefa…2b40`). The maintainer's daily mynx
  is the release, updated through `mynx update`.
- **Done, confirmed on the phone and merged since:** web apps parts 1
  and 2 (PR #59, #64), the services count in the menu (#58), touch
  mode's Home and Services, Chat for Claude Code (#67-#81), touch
  mode's chat home with its drawer and the pixel style (#83),
  Chat's pictures (thumbnails #91, full screen and image cards #92),
  touch mode's Files (#93), viewers for text, code, Markdown, audio
  and video (#94), and a turn's changes in chat, PDF (with text
  selection) and HTML files (#95), with Find in PDFs and Replace in the text
  viewers (#96, into #95's branch), and the chat history listing
  every folder's chats (#98), and asking for all of the phone's
  storage (#99), and app and game logos in touch mode with Terminal
  apps moved into Apps (#100). Every
  roadmap step before them (Debian through proot to development boards
  and Bluetooth) is confirmed and released.
- **Workflow:** trunk-based, `main` protected (PRs only). CI builds
  PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`, `docs/ARCHITECTURE.md`.

## Next

### 1. Development boards: what's left (the maintainer picks)

- Raw USB hotplug while a program runs (libudev's monitor wants
  libudev-format messages from uid 0, a netlink address).
- Recipes not tried for lack of hardware: `dfu-util` (a board with a
  ROM DFU bootloader, e.g. a WeAct Black Pill STM32F411), a CMSIS-DAP
  probe (the RP2040 Zero with `debugprobe`, plus an SWD target),
  `probe-rs run` flashing the C3 (needs an esp-hal ELF; replaces
  MicroPython).
- UF2 not tried: an RP2350, TinyUF2 or Adafruit bootloaders (other
  `INFO_UF2.TXT` layouts, no MBR).

### 2. Touch mode: what's left

- Other agents in Chat (Codex's `app-server`, Gemini's ACP), which
  need accounts to test; the
  Model page lists fallback models until a session reports its own.

### 3. Smaller things

- **Maybe:** `CONTRIBUTING.md`, `SECURITY.md`.
- **Maybe, for Android apps built in Debian** (the guide's "Making an
  app"): a `mynx` command that fetches `r8.jar` and `android.jar`.
- The wakelock is a setting; a notification action to toggle it (like
  Termux) could come later if people want it.
- The interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

### Regression checks
- **Config:** `mynx theme set` for a dark and a light theme (strip
  and key bar readable); the theme editor's live preview and `r` reset;
  font size from the editor and from pinch (kept after a restart);
  cursor style and blink; editing and resetting a key bar; editing and
  resetting the menu (`run` items, and a failing command explains
  itself); `mynx check` lists problems without dialogs.
- **Key bars:** `files` (nnn bar, two rows, pages to swipe), game bars
  for `rogue`/`drive`/`flap` and the generic `game` bar, hold-to-repeat
  on arrows, each tab keeping its own bar.
- **Hardware keyboard, with another keyboard:** Ctrl+Shift+PgUp / PgDn
  move the selected tab left / right (the first keyboard's Fn+Up/Down
  sent nothing). Everything else passed (2026-10-17).

---

## Log

### 2026-11-22 (207): `mynx install-apk` in every build (PR #101; passed)

The maintainer chose to let release builds open Android's installer
for an APK built in Debian, so agents needn't serve it to the
browser (Android still asks before every install).
- App: `TerminalService` no longer refuses outside debug builds
  (`MynxRequests`' `installApk` is `openInstaller`), and a chat card's
  Install shows in every build. Core was already generic.
- `mynx install-apk FILE` is in `mynx help` and the guide's command
  table; `apps.md`'s "Installing" uses it, with the browser way kept
  for a mynx up to 0.4.0 (which says only debug builds can install).
- Tests: `test_mynx.py` (in help; a realistic refusal),
  `home-docs.bats` (the guide lists it). Docs: AGENTS.md, ARCHITECTURE.md,
  `deliver.sh`'s comment, the manifest and `ApkProvider` comments.
- CI: Robolectric's own download of its Android jar failed on both
  runs of this PR (an `IOException` from Maven Central, which
  answered fine from the phone), failing every screenshot test.
  Gradle then got 429 Too Many Requests for it too. So
  `app/build.gradle.kts` has Gradle fetch `android-all-instrumented`
  from Google's mirror of Maven Central (`settings.gradle.kts`, for
  that module only) and runs Robolectric offline from it. It needs
  two: SDK 34 for the tests and 35 (its newest) for the app's
  resources, each in a configuration of its own (in one, Gradle kept
  only 15). Test failures now print their messages in CI.
- In mynx dev (build 45003725619, with F-Droid's APK): on screen the
  installer opened (exit 0); off screen it said "the app must be on
  screen to open the installer" (exit 2). `mynx help` showed
  `install-apk` one column off, as it's longer than the name column:
  the column is two wider now, with a test that every line lines up.
  A chat card for an APK (Bash copying it) shows Install, which opens
  the installer. Release builds get it with the next release.

### 2026-11-22 (206): App and game logos in touch mode; Terminal apps moves into Apps (PR #100; passed)

The maintainer asked for the apps' logos in touch mode's menu, and for
the Terminal apps folder to move into Apps in both the terminal's
launcher and touch mode.
- Logos: `HomeItem.logo` (`HomeLogo.App`, `HomeLogo.Terminal`). An
  app's logo is its home screen shortcut's (`appShortcut()`:
  `app.conf`'s `icon`, else the letter on a theme colour), drawn by
  `appIcon()`, which is now shared with `AppShortcuts` (the
  shortcut's drawing is unchanged). Terminal apps gets the pixel
  terminal icon. 36 dp squares in a 2 dp border (`HomeView.logo()`).
  Screenshot `home-apps`.
- Terminal apps moves: it's no longer in `tools/menu.conf` or the
  built-in lists (`BUILT_IN_MENU`, the menu's fallback), so the
  existing rule adds it at the end of Apps, before "Add to home
  screen…". A user's own `menu.conf` with `terminal-apps` keeps it
  in the main menu (it's their file; the guide says so). The main
  menu's numbers after Apps move up by one.
- Games too (the maintainer, after the first build): touch mode's
  Games list shows logos, a picture beside the game with its name
  (`neon-flap.png` for `neon-flap.py`; PNG, WebP or JPEG), else the
  letter tile (`gameLogo()`, sharing `appShortcut()`'s rule). Games
  stay in the main menu. Screenshot `home-games`.
- Tests: `HomeTest`, `menu.bats` (key-number and Apps-list tests
  follow the new order). Docs: the guide, `usb.md`, `apps.md`,
  README, ARCHITECTURE.md, TOUCH-DESIGN.md.
- Passed in mynx dev (build 44959185452): touch mode's Apps shows logos (Sketch and
  Gadgets as letters; an app with an `icon` shows it); Terminal apps
  is last before "Add to home screen…" and opens; the drawer and
  Home's main list have no Terminal apps; Games shows letter tiles
  (a `~/games/NAME.png` beside a game shows instead); in the terminal's menu,
  Apps → Terminal apps works and the main menu has no Terminal apps.

### 2026-11-21 (205): Asking for all of the phone's storage (PR #99; passed)

The maintainer asked for the roadmap's last Files item: asking for
Android's storage permission, so the phone's storage shows in full.
Without it Debian saw folders under `/storage/emulated/0` but not
other apps' files (DCIM/Camera listed empty).
- Decisions (the lead's, for the phone test to settle): Android 11+
  "All files access" (`MANAGE_EXTERNAL_STORAGE`, as Termux does),
  the storage dialog before 11 (`requestLegacyExternalStorage` for
  10). Asked from Files' bar and `mynx storage allow`, never at first
  run. Play restricts the permission, as `REQUEST_INSTALL_PACKAGES`.
- Core: `Storage.kt` (`StorageAsk`, `storageAsk(sdk)`,
  `StorageAccess`, `storageBarShown()`), the `storage` request
  (`status`, `allow`: `access` all/some, `asked` settings/dialog).
  Tested.
- App: `TerminalService.storage` (`isExternalStorageManager()`; the
  app's All files access page, else the list of apps; the dialog
  before 11); Files' pink bar in `/storage` with **Allow**; Files
  lists again in `onResume`. Screenshot `files-storage`.
- `mynx storage [status] | allow`, tested; the guide (`phone.md`,
  the core guide's Phone storage line, a `home-docs.bats` check),
  README, ARCHITECTURE.md, TOUCH-DESIGN.md, the roadmap.
- Local `test_serial_port` timing tests fail here on `main` too
  (`BusyTest`, `ControlTest`, flaky under load); not this change.
- Passed in mynx dev (build 44943733920): Files → Phone (or Download): the bar shows;
  Allow opens Android's page for mynx dev; turn it on, Back: the bar
  goes and DCIM/Camera lists the photos; `mynx storage` says all
  files; `ls /storage/emulated/0/DCIM/Camera` in a tab (a tab opened
  before granting too); turning it off again (Android may restart the
  app) brings the bar back; `mynx storage allow` from a tab.

### 2026-11-21 (204): The chat history lists every folder's chats (PR #98; passed)

The maintainer asked for the roadmap's "chat history that lists other
folders' chats". A chat started from Files' "Chat here" left the
history once it was dropped, and resuming it in `/root` couldn't work.
- Core: `listClaudeSessions(projects, here, exists)` reads every
  folder under `~/.claude/projects` (the 50 newest overall); each
  session's folder is the first `cwd` its head names. Left out: a
  `cwd` whose Claude folder name isn't the one it's kept in, and
  folders that are gone (the phone had scratchpad temp folders from
  `claude -p` runs). `ChatHistoryRow.cwd` (null for the hub's own,
  `/root`), `LiveChat.cwd`, `ChatHub.open(id, cwd)` (else the cwd the
  last read found, else `/root`: a notification's tap),
  `chatFolderLabel()`. Tested.
- App: rows (history, drawer, chat home) open with their folder;
  the history's meta line ends with the folder (`~/site/`, cut in the
  middle when long); "No chats yet." `chat-history` screenshots have
  other-folder rows.
- Docs: ARCHITECTURE.md, TOUCH-DESIGN.md (Files' limits), the roadmap,
  the guide.
- Passed in mynx dev (build 44933764075): the maintainer tried it
  all ("all works"): Chat here's row naming its folder, resuming
  there (`pwd`), a notification tap, folders listed together, gone
  temp folders left out. (The first build failed: `MainActivity`
  lacked the `ChatHistoryRow` import, which only CI compiles.)

### 2026-11-21 (203): Viewers closed; roadmap cleaned up

The maintainer closed the viewer suggestions (opening files from
other apps, the diff view unwrapped): "we are all good there". Done
items left the roadmap (Viewers, touch mode's built parts); the
drawer's alternative moved to `docs/TOUCH-DESIGN.md`.

### 2026-11-21 (202): Find in PDFs, Replace in the text viewers (PR #96 into `more-viewers`; passed)

The maintainer asked for search in PDFs and Replace in the text
viewers' Find.
- Core: `PdfSearch.kt` (pages searched one at a time in order, the
  current match the first at or after the page in view, else the
  first; "2/7…" while searching; `pdfRevealBy()` to scroll a match
  into sight); `TextDoc.kt`'s `replaceMatch()`, `replaceAll()`,
  `matchAtOrAfter()`. Tested.
- App: `PdfView`'s Find bar (⋮ Find, Ctrl+F; Android 15+ only, via
  `searchText()` one page per worker task so renders go between;
  matches tinted, the current one pink); `TextViewerView`'s Replace row
  (⋮ Replace, Ctrl+H, ⇄ in Find; switches to Edit; each Replace/All is
  one Undo step). Screenshots `pdf-find` (stand-in matches) and
  `text-replace`.
- Docs: TOUCH-DESIGN.md, ARCHITECTURE.md, the guide (`phone.md`, its
  check in `home-docs.bats`).
- Passed in mynx dev (build 44925532382): the maintainer tried it all
  ("all works"). Phone checks were: PDF Find (type, count growing, ↑ ↓ across
  pages and zoomed in, Enter, Back/Esc closing it, typing letters like
  `g` in the field not opening Go to page); Replace in code, text and
  Markdown (from View: it switches to Edit), Replace then the next,
  All, Undo of each, Save; read-only (>1 MB) files have no Replace.

### 2026-11-21 (201): Selecting text in the PDF viewer (PR #95; passed)

The maintainer found they couldn't select text in a PDF. Android 15
(API 35) added text to `PdfRenderer.Page`, so on 15+ a long press
selects a word, two handles drag it within the page, and a bar has
Copy, Select all and ✕ (docs/TOUCH-DESIGN.md "PDF").
- Core: `PdfSelection.kt` (screen ↔ page points under the zoom, the
  handles and which one a touch grabs, the bar's place, joining the
  text, one request in flight), tested.
- App: `PdfPagesView` draws the selection and handles and handles the
  touches; `PdfViewActivity`'s `PdfText` (`@TargetApi(35)`, behind
  `SDK_INT >= 35`) calls `selectContent()` / `getTextContents()` on
  the renderer's worker, with `SelectionBoundary(Point)` (whole
  points). The activity now passes each page's width in points.
- Decisions: dragging a handle past the other selects the other way
  (the two points are sent in reading order); a drag on from the long
  press extends the word either way; the bar goes below the selection
  when there's no room above, at the top without either.
- Not compiled here (app/ builds only in CI); the `pdf-selection`
  screenshot draws a stand-in selection (Robolectric has no pdfium).
- To test on the phone: long press a word, drag both handles (also
  zoomed in), Copy and paste it, Select all, Ctrl+C, Back and a tap
  clearing it, pinching not selecting; on Android < 15 nothing
  changes.
- Passed in mynx dev (build 44920567754), with all of entry 200:
  the maintainer tried it all ("all works").

### 2026-11-21 (200): A turn's changes in chat, PDF and HTML files (PR #95; passed)

The maintainer asked for three of the viewers' "later" ideas: the diff
view in chat, a PDF viewer and HTML files. Choices were the lead's
(`docs/TOUCH-DESIGN.md`, "More viewers"), for the phone test to settle.
- Built by agents in waves against that design: core-diff (`Diff.kt`,
  `readFileEdits()`, tool call IDs on changed files), core-kinds (the
  PDF and HTML kinds, `open-pdf`/`open-html`, their cards and View
  source, `respondFolder()`, `mynx open`, the guide), app-diff
  (`DiffViewActivity`, `DiffView`, the card's `+N −M`), app-pdf
  (`PdfViewActivity`), app-html (`HtmlViewActivity`); the lead wired
  the opener (`pageApp()`, an HTML file to edit opens its source), the
  cards, and the docs.
- Found on the way: Claude's transcripts hold `originalFile` and
  `structuredPatch` for every Write and Edit, so diffs are exact; a
  served page's folder must hide dot files (`~/.ssh`).
- Limits: no text search in PDFs; selecting text needs Android 15
  (`PdfRenderer`'s text APIs, entry 201);
  all HTML files share one origin and its storage; a file a command
  also wrote in the turn gets no diff.

### 2026-11-21 (199): Viewers for text, code, Markdown, audio and video (PR #94; passed)

The maintainer asked for a Markdown viewer and editor, a notepad, a
code viewer and a media player, opening supported files, working in
chat as the image viewer does, with settings for default apps.
Decisions: mynx's viewers are the default; text, code and Markdown
open to view with an Edit button; `mynx open FILE` asks in a
terminal unless `--with` says (my addition, agreed: never without a
tty or with `--json`, so agents don't wait). Design:
`docs/TOUCH-DESIGN.md`, "Viewers"; architecture entry "Viewers".
- Built by agents in three waves in worktrees against that contract:
  core-open (`FileKinds`, the `open-*` settings, `open-file`
  requests, `Media`, `mynx open`, `xdg-open`, the guide), core-text
  (`TextDoc`, `Syntax`, chat's AUDIO/VIDEO/CHANGED_FILES cards),
  app-text (`TextViewActivity`, `TextViewerView`, `MarkdownViews`
  moved out of `ChatView`, `TextHistory`, Markdown files' pictures and
  links), app-media (`MediaViewActivity`, `MediaPlaybackService`,
  pixel icons), app-wiring (`Viewers.kt`, `openDebianFileWith()`,
  Files' Open with…, chat's chips and cards). The lead merged, unified
  the audio/video extensions, and checked builds in CI between waves
  (`workflow_dispatch` on the branch: both passed).
- Changed: every file Claude writes now gets checked when its call
  completes, and files with no other card go into one Changed files
  card per turn; Markdown cards are View, Copy, Share. Card buttons
  (View, Play) always use mynx's viewer; chips, Files and the changed
  files' rows follow the setting.
- The maintainer's first test (build 44891241402): everything passed
  but chat's cards, which a script and a Markdown file Claude wrote with
  `cat > FILE <<'EOF'` never got (only Write and Edit counted). Fixed:
  a command's `>`, `>>` and `tee` targets count when they changed in
  the turn (`commandWrites()`); cards move below the turn's last words
  when it ends (`withArtifactCards(toEnd)`); and code in replies that
  names a file that's there is an underlined link (`chatFilePath()`,
  `MarkdownViews`' `fileAt`), the maintainer's choices.
- Passed in mynx dev (build 44895957994): the maintainer tried it
  all ("all works").
- Limits: highlighting stops past 256 KB, editing past 1 MB; web
  pictures in Markdown aren't fetched; the Open with… dialog has no
  screenshot (dialogs aren't captured); TextHistory's undo has no
  app-side test beyond screenshots. One wave-1 test file (`SyntaxTest`)
  first ran against finished code, so it never failed for a reason.

### 2026-11-21 (198): Touch mode's Files (PR #93; passed)

The maintainer asked for a touch file manager: copy, move, delete,
and chat or a terminal in a folder. Decisions: it replaces nnn in
touch mode (nnn stays as "Files (nnn)" in Terminal apps), Delete is
permanent after a confirmation, it starts at `~` with the phone's
storage among the places. Built by two agents in parallel against a
contract in `docs/TOUCH-DESIGN.md` ("Files"): core (`FileBrowser.kt`:
listing, crumbs, places, names, jobs; `HomeAction.Files`;
`ChatHub.newChat(cwd)`) and app (`FilesView`, `runInDebian`,
`openDebianFile`, `shareDebianFiles`, `newSession(cwd)`,
`ChatPlace.FILES`, the `files*` screenshots).
- Every change is a `/bin/sh -c` job run in Debian, never
  `java.io.File`: proot's faked hard links (`.l2s.*`, never listed).
- Back stops at a place (`filesBack()`), not climbing into
  `/storage/emulated`.
- Pictures' rows show a thumbnail (`ChatThumbnails` with 40 dp
  squares, cached by path and file time), asked for only once the row
  is within a screen of sight: DCIM would decode every photo up front.
- Limits: a chat started elsewhere isn't in the history once it's
  done (the history lists `/root`'s); storage shows
  only what Android allows without a storage permission.
- Passed in mynx dev (build 44878894871): the maintainer tried it
  all ("all works great").

### 2026-11-21 (197): Pictures full screen, and image cards (merged, PR #92; passed)

The maintainer asked to see sent pictures full screen and the pictures
Claude makes in the chat.
- **Full screen:** tapping an image's chip (in the box or in a sent
  bubble) opens `ImageViewActivity`: the picture on black, pinch,
  double-tap and drag to zoom (core's `ImageZoom`, tested), a tap
  hides the bar (← and the name). Decoded off the main thread at
  `viewerSampleSize()` (core, tested), upright by EXIF; a file that's
  gone or can't be decoded says so on screen.
- **Image cards:** a new `ArtifactKind.IMAGE` (core, tested): a
  picture (`chatImageFile()`: jpg, png, gif, webp, heic, bmp; not SVG)
  written anywhere, or named by a Bash command and changed in the turn;
  one in an app folder stays the app's card. The card shows the
  picture (`ChatThumbnails.preview()`, up to 240 dp high) with View and
  Share; tapping the picture views it too. `ChatArtifact.version` (the
  file's time) changes the card when the picture is drawn again.
- Limits: a picture a script saves under a name its command doesn't
  show gets no card; the viewer has no Share (the card has).
- The cards screenshot (`chat-cards`) has an image card now.
- Passed in mynx dev (build 44828924601): sent photos open full
  screen from their chips, and a picture Claude drew got its card.

### 2026-11-20 (196): Image thumbnails on attachment chips (merged, PR #91; passed)

- Chat's attachment chips (in the box and in sent bubbles) show an
  image's picture, a 28 dp square, in place of the photo icon.
  `ChatThumbnails` (app) maps the Debian path to the app's file
  (`hostPath`), decodes it on one app-wide worker thread at
  `thumbnailSampleSize()` (core, tested: the largest power of two
  keeping the short side at the square's size), crops it square,
  turns it by its EXIF orientation and caches 64. A file that's gone
  or can't be decoded keeps the icon.
- The chat screenshots now attach a real (generated) portrait photo.
- Passed in mynx dev (build 44824382998): the thumbnails show in the
  box and in sent bubbles.

### 2026-11-20 (195): Touch mode cleanups in core (merged, PR #90; passed)

- The chat home's first prompt now waits for the sign-in check in
  core: `ChatController.sendWhenChecked(text, sent)` sends at once if
  the chat can run, or once the check (or a restart after signing in
  again) says it can, and drops the prompt if it can't, leaving the
  text in its box. `dropWaiting()` forgets it when the chat is hidden.
  MainActivity's `firstPrompt` is gone.
- `ChatHub` and `ChatController` expose `home` and `cwd`; MainActivity
  and ChatView no longer copy "/root".
- Passed in mynx dev (build 44814577070): Send from the chat home
  signed in (the prompt went, the box emptied) and signed out (the
  gate showed, the text stayed).

### 2026-11-20 (194): Roadmap cleaned up

- `docs/ROADMAP.md` keeps only what's planned: the done sections (web
  apps, development boards, Bluetooth) and touch mode's built parts
  are gone, listed in its "Done" line instead. Left: file manager,
  agent widget, touch mode's other agents and undecided ideas, more of
  the phone, raw USB hotplug.

### 2026-11-20 (193): Two flaky tests made sturdy (merged, PR #87)

- `ProcessAgentTransportTest`'s "a process that has died accepts
  nothing…" raced the transport's 2 s wait for stdout (entry 191's CI
  failure). Now `ProcessAgentTransport` takes `readerWaitMs` (default
  unchanged), the test sets it to 60 s, and the child left behind
  holds stdout until the test makes a flag file: the window no longer
  depends on the machine's speed. Passed three runs under CPU load.
- `test_undo_list` built 12:05 in the caller's `TZ` while the `mynx`
  it ran (clean env) used the system zone, so it failed on the phone
  (`TZ=Europe/Helsinki`). Both sides are UTC now; passes in any zone.

### 2026-11-20 (192): Instruction files slimmed (merged, PR #85; passed)

What every agent session loads was about 160 KB (~40k tokens), plus
this log's 126 KB at the start. The maintainer asked for all four
fixes:
- **This repo's sessions** no longer load the installed user guide
  through `/root/CLAUDE.md`: `.claude/settings.json` excludes it
  (`claudeMdExcludes`). It was the release's copy, older than
  `tools/AGENTS.md`.
- **`AGENTS.md`:** the architecture section (and the Termux and proot
  notes) moved to `docs/ARCHITECTURE.md`; `AGENTS.md` keeps the rules
  every session needs and an index. 79 KB → 27 KB.
- **This log:** 97 entries → 3; status and "Next" hold only what's
  open. 126 KB → about 8 KB. Everything is in `22c111c`.
- **The user guide** (`tools/AGENTS.md`, loaded by every user's
  agent): split into a core guide (35 KB, was 94 KB) and topic files
  `tools/docs/{phone,apps,services,usb,bluetooth}.md`
  (`/opt/mynx/docs/`), which the core table names. The `mynx` table
  keeps one row per family of hardware commands; the full rows open
  each topic file. `home-docs.bats` checks the guide and its topic
  files together, caps the core at 40 KB, and checks the core names
  every topic file. `pack-tools.sh` already ships all of `tools/`.
Passed: a fresh session here no longer lists `/root/CLAUDE.md` in
`/memory`; in mynx dev (build 44721124656), an agent asked how to flash
an ESP32 read `/opt/mynx/docs/usb.md` on its own.

### 2026-11-20 (191): Touch view in the launcher; Settings grouped by view (merged, PR #83)

The maintainer, after entry 190 passed: the terminal launcher had no
easy way into touch view (only `view` deep in `mynx edit`), and the
theme sat under touch Settings' Terminal though it colours both views.
Their rule: a setting both views use doesn't go under Terminal.
- Launcher: Settings → Touch view (maintainer's pick, after first
  trying it in the main menu) runs `mynx set view touch`, which the
  app applies at once. It's also a `touch-view` action a user's
  `menu.conf` can use; Home leaves it out, as it does Exit. In the
  shared fixture, so both suites check it; the editor's action list
  and the guide have it.
- Touch Settings (`HOME_SETTING_GROUPS`): Appearance (View, Theme),
  AI agents (permission mode, model, and notifications, which cover
  terminal agents too: hence not "Chat"), Terminal (text size, font,
  cursor, swipe, key bars and menu), then as before. Agent's grouping,
  from the maintainer's rule.
- Screenshots: `home-settings-appearance.png`, and
  `home-settings-agents.png` in place of `home-settings-chat.png`.
Merged without a phone round (maintainer's call: the screenshots
showed the groups, and Touch view runs the `mynx set view` the View
setting already uses). CI's first run failed on
`ProcessAgentTransportTest`'s "a process that has died…" (a timing
race: the exit was reported within its 1.8 s); the re-run passed.

### 2026-11-11 (190): Settings changes answer right; themes colour the touch screens (passed on the phone 2026-11-20)

The maintainer on entry 189's build: every change said "the app isn't
ready yet" though it applied, and the list only showed it after going
back; and themes should apply to the app too. The first was a bug:
`configRequest()` returns null on success, and the activity's `?:`
turned that into its "not ready" text, so `HomeView` took every change
as refused (a toast, no redraw). Now only a missing service says so.
- core, test-first: `pixelPalette()` (`PixelPalette.kt`): no colours
  file or Neon → `LOGO_PALETTE`; another theme → its background, text,
  colour 6 as the accent, 5 as the mark (13 its text), cards, lines and
  dim text mixed between background and text.
- app: `Pixel`'s colours read the current palette (`Pixel.use()`);
  `applyColors()` sets it and, when it changed with the screens built,
  `recreate()`s the activity, saving Home's lists in the service
  (`homeLevels`). The cached ripple colours became getters.
Left: CI, then the phone ("Next" 4).

