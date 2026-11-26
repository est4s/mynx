# Work log

Where the project stands, what's next, and what recent sessions did.
Newest entries first. Rules for keeping it up to date (and short): see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps). Older entries
are in git history: the full log up to entry 191, with every plan and
phone test, is `git show 22c111c:docs/WORKLOG.md` (up to 92:
`be3d232`, up to 84: `72b8512`).

---

## Current status

- **Released:** v0.1.0 to v0.5.0 (v0.5.0 on 2026-11-23: web apps,
  touch mode with Home, Files, Chat for Claude Code and the viewers,
  all-files storage, app logos, `mynx install-apk` in release builds,
  `mynx android-sdk`; PRs #58-#102), signed with the release key
  (certificate SHA-256 `24b7fefa…2b40`). The maintainer's daily mynx
  is the release, updated through `mynx update`. Every roadmap step
  so far is confirmed on the phone and released.
- **Workflow:** trunk-based, `main` protected (PRs only). CI builds
  PRs; `scripts/deliver.sh` installs a build in mynx dev. Desktop
  mode is built on `desktop-dev`: branch from it, PRs into it
  (`AGENTS.md`, "Conventions").
- The code's layout and decisions: `AGENTS.md`, `docs/ARCHITECTURE.md`.

## Next

### 0a. Extended screen (on `screen-dev`)

Plan (maintainer's, 2026-11-26; roadmap "Extended screen"). Phone A
is the Pixel 10 (Android 17), phone B the OnePlus Nord 2 (Android
13). Each step a PR into `screen-dev`, by subagents.
1. **Spikes: done** (entry 224; PRs #117 and #118, never to be
   merged): a screen streamed as video, and a second X monitor.
2. **Core: passed on both phones** (entry 223, PR #119; Pixel 10
   as A, Nord 2 as B, 2026-11-26): pairing (a wrong code, then the
   right one), status, `place` from either phone, reconnecting
   without a code (after A's restart too), unpairing both,
   `screen-host off`. The defaults the agent picked stand (entry 223).
3. **3a, apps sent to B as video: passed on both phones** (entry
   225, PR #122; Pixel 10 as A, Nord 2 as B, 2026-11-26, with a test
   app of touch pad, buttons, text fields, a list and a load
   counter): send, touch (several fingers; "close enough, a bit
   laggy"), scrolling, typing from A's keyboard, A usable meanwhile,
   Back and show again, take back (storage survived), `mynx screen
   send`/`take` on both, the link dropping, 30 fps / 4 Mbit/s. The
   bridge doesn't answer on a sent app, as expected. The agent's
   decisions stand (entry 225).
4. **3b, terminal tabs on B as text: passed on both phones** (entry
   227, PR #123; Pixel 10 as A, Nord 2 as B, 2026-11-26): sending a
   tab and a new one, typing from A, htop / vim / less on B,
   resizing, scrollback and copy on B, a flood, every way of taking
   back, the link dropping, a shell's exit. The agent's decisions
   stand (entry 227).
5. **3c, the desktop on B: built, to test on both phones** (entry 228,
   PR #124, protocol version 4): a monitor of its own and the second
   half (`screen-desktop = extend | span`). The two-phone test is in
   entry 228.
6. **Then (left for later):** B linking again by itself (after a drop,
   at the app's start); the bridge (`mynx.js`) on a sent app; chats (the
   chat hub shows one chat at a time: it needs a second one shown),
   Files and viewers sent to B; typing with B's own keyboard (into
   apps; into terminals it works already through B's key bar and a
   hardware keyboard).

### 0. Desktop mode (on `desktop-dev`)

Plan (maintainer's, 2026-11-24; roadmap "Desktop mode"). Each step is
a PR into `desktop-dev`.
1. **Spike: done** (entry 215; draft PR #108, never to be merged).
2. **Steps 2-5 passed on the phone** (Pixel 10, 2026-11-25, entries
   216-219): settings and `mynx desktop`, the X server built in CI,
   the view (touchpad, keys, keyboard pan, rotation, no stale picture
   on reopening), Debian's side (XFCE install and start, fonts,
   sound, Firefox), the abstract socket refused, About.
3. **Clipboard: passed on the phone** (PR #114, entry 220). Direct
   touch as a touchscreen and the view switcher were built and rolled
   back (maintainer's decision, 2026-11-25); they're on branch
   `desktop-touch-clipboard-full` if wanted again.
4. **The desktop opens from either view: passed on the phone** (PR
   #115, entry 221).
5. **The Log panel keeps a selection and has Copy: passed on the
   phone** (PR #120, entry 226).
6. **Then, what's left:** start the X server and session again if
   either dies (now the notice offers Start); an external display
   (Android's desktop windowing) later; a last pass over
   `tools/docs/desktop.md`; then merge `desktop-dev` into `main`.

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

### 2026-11-26 (228): Extended screen, step 3c: the desktop on B

- Branch `screen-desktop` from `screen-dev`, PR #124 into `screen-dev`.
  Core and `mynx` tested here; the app's side and the X server patch
  only built in CI (screenshots looked at). Not tried on two phones by
  the agent. Rebuilt from spike #118 (`docs/ARCHITECTURE.md`, "Extended
  screen: the desktop on B").
- **The spike's 264x943:** both monitors were halves of *A's own*
  desktop view, not B's screen: +B split A's view in two, each half
  rounded down to the 16 px step (8 X px at 2 phone px an X pixel). On
  the Pixel 10 in portrait the view is 1080 x ~1886 phone px: 540 → 528
  px → 264 X px wide, 943 high. The numbers are portrait's (in
  landscape they'd be about 600 x 420), so it was taken in portrait or
  before the surfaces resized. Now B's monitor is B's screen at its own
  0.75 dp X pixel: a Nord 2 (1080 x 2400 at 480 dpi) is 536 x 1200.
- **Built:** `screen-desktop` (`extend` default, `span`), `mynx screen
  send desktop`, Home's Send list (Linux desktop first while the X
  server runs, its own section), the desktop bar's **→ NAME** / **Take
  back**; protocol version 4 (a Pointer message; merged after #123's 3); our patch's `mynx-b` placed by
  `screen-place`, moved live, B drawn whenever it watches (A's view
  shown or not), re-laid out after a server restart; the shared pointer
  (touchpad crosses at the shared edge); B's touches with `desktop-touch`
  and the same gestures; the typing bar for X on a click on B.
- **Agent's decisions, to confirm:**
  - B's part is B's whole screen at its own density's X pixel (the
    same physical size as A's within the 0.75 dp rounding), not scaled
    to A's density.
  - The pointer is our arrow on both phones (B draws it from Pointer
    messages: the X server draws no cursor into the second surface).
  - Typing: a click on B (not any touch) opens A's typing bar, unless
    A shows its own desktop view (then A's ⌨ types into the same X).
  - Span scales B's part to A's edge (keeping B's shape), so the wide
    monitor is a rectangle; a window can be cut by the phones' frames.
  - A desktop that's gone for 5 s takes B's part away; a quicker
    restart keeps it.
  - When a program moves `mynx-b` (XFCE's display settings), the
    server's word wins until the layout next changes.
- **First phone try** (run 45856698516): B showed only the pointer on
  black. The layout was right (`xrandr`), but nothing said whether the
  encoder started or the X server drew into its surface: those errors
  went to logcat, or nowhere. Now: the X server logs to `x11.log` what
  it does with B's surface (`mynx: the other phone's surface: …`, which
  EGL config draws into it, any EGL error, the first frame, and "N
  frames drawn into the other phone's surface in 5 s"), and the
  desktop's Log says the encoder started (or why not), its first part,
  and every 5 s how many frames and KiB it gave. Likely causes, fixed
  blind: the encoder's surface wants a recordable EGL config (tried
  first now, falling back to the view's), and an idle desktop drew
  nothing more after one frame (now a fresh frame about 10 times a
  second while B has a surface). Also: `mynx set screen-place`
  (by hand) now reaches the screen and the status (it only did through
  `mynx screen place`), and unreadable settings keep the last place
  instead of falling back to `right` (the try's layout went from above
  to right once; the Log line "the desktop's monitors: … (extend,
  SIDE)" now says which side was read).
- **Two-phone test** (Pixel 10 as A, Nord 2 as B, both on this build as
  mynx dev, paired; `mynx screen connect` on B; on A the desktop running
  with XFCE, `mynx desktop open`):
  1. A's desktop bar shows **→ Nord 2**; tap it. B shows the desktop
     full screen: an empty XFCE area (its own background), no panel yet.
     A's bar says **Take back**. `mynx screen status` on A lists
     "Linux desktop (showing)".
  2. In XFCE's terminal on A: `xrandr --listmonitors` shows 2 monitors,
     the second `mynx-b` 536/…x1200/…+W+0 (W = A's width in X px);
     `xrandr` lists mynx-b connected.
  3. Drag a window right across A's edge: it appears on B. Maximise it
     there: it fills B only; on A, maximise fills A only. XFCE Panel
     Preferences → Output: mynx-b moves a panel to B.
  4. On B (touchpad): one finger moves the pointer (B draws the arrow),
     a tap clicks, a two-finger tap right-clicks, two fingers scroll,
     hold then drag moves a window. Move the pointer past B's left
     edge: it shows on A, and back. `mynx set desktop-touch direct`:
     on B a tap clicks where the finger is, a long press right-clicks.
  5. Tap a text field (the XFCE terminal) on B: A opens a bar
     "Typing on Nord 2: Linux desktop"; type: it appears on B. Touch A:
     the bar goes. With A's own desktop view on screen, tapping on B
     opens no bar, and A's ⌨ types into the window focused (on B).
  6. `mynx screen place left` on A (or B): `mynx-b` moves to the left
     at once (`xrandr --listmonitors`: +0+0, A's at +536+0); the
     pointer now crosses at A's left edge. Then `above`, `below`.
  7. `mynx set screen-desktop span`: one monitor (`xrandr
     --listmonitors` shows 1, as wide as both); maximise a window: it
     spans both phones. `mynx set screen-desktop extend`: two again.
  8. Back on B: B's Home lists "Linux desktop · from Pixel 10"; tap it:
     the desktop shows again. Hide A's desktop view (← Terminal) and
     move windows on B: B keeps drawing.
  9. Take back (A's bar, or `mynx screen take desktop`): B goes back to
     its Home, `xrandr --listmonitors` shows 1; windows that were on B
     are on A.
  10. Send again, then `mynx screen disconnect` on B: same as 9 on A.
      Send again, then `mynx desktop stop` on A: within 5 s B's stream
      ends.
### 2026-11-26 (227): Extended screen, step 3b: terminal tabs on B as text

- Branch `screen-terms` from `screen-dev`, PR into `screen-dev`. Core,
  `mynx` and the guide tested here; the app's side only compiled in
  CI (its Robolectric tests, `MynxTerminalsTest`, run Termux's real
  emulator there). Not tried on two phones by the agent: "Next" 0a
  step 4 has the test.
- **What's there** (`docs/ARCHITECTURE.md`, "Extended screen:
  terminals on B"): protocol version 3 (TermStart, TermState,
  TermOutput, TermTitle, TermEnd from A; TermSize, TermInput,
  TermAck, TermTouched from B; TakeBack takes either kind);
  `termSnapshot` (a terminal's state as the bytes that draw it),
  `TermPacer` (flow control), `ShownTerm` (B's epochs and sizes),
  `Tabs.openFrom` (tabs from another phone, not numbered, not saved);
  `mynx screen send tab [N|new]`, status lists terminals; Home's Send
  list has New terminal and the tabs; the tab menu's Send to / Give
  back. App: `MynxTerminals` (Java in Termux's package: the tap, B's
  process-less session), `TermCaster`, `RemoteTerms`, `TermKeys`.
- **Agent's decisions, to confirm:**
  - **Bytes, not screen diffs:** B runs its own Termux emulator fed
    A's PTY output at A's size (which is B's: A's PTY takes the size
    B's view fits), after a full state (scrollback up to 1 MiB) on
    sending and after each resize. Native rendering, selection and
    scrollback on B, modes for free. A's emulator answers programs;
    B's answers are dropped (its mouse reports and pastes go).
  - **The tap** replaces the library's per-session main thread
    handler with a copy that tees (reflection on a final field, at
    each session's creation, so every tab can be sent). If Termux's
    library is upgraded, `MynxTerminals` must be checked against it.
  - A sent tab **leaves A's strip** (like apps leave A's screen);
    sending the last tab opens a fresh one on A. Services' tabs stay.
    Take back, B's ×, Give back or the link dropping bring it back
    with its name; its shell exiting with 0 closes it, with an error
    brings it back to A to read.
  - **Typing:** a finger down on B over the tab sends TermTouched; A's
    terminal view keys, soft keyboard, hardware keyboard and key bar
    then type into that session (the key bar's touches don't take
    typing back; any other touch on A does). A's strip shows "⌨ Nord
    2: title" (tap: typing back), and A's keyboard opens; if A shows
    Home or a chat instead, the typing bar from 3a does it. B draws a
    cyan line around the tab. B's key bar (and a hardware keyboard on
    B) type into it too; a tap there doesn't open B's soft keyboard
    (keyboard on A, as decided).
  - Flood control: 256 KiB unacked, then A drops output and sends a
    fresh state once B caught up.
  - Not carried by a state: tab stops, line-drawing charsets, the
    saved cursor, colours programs changed (B keeps its theme).
- **CI flake seen before this work**, not fixed: ScreenLinkTest "after
  a disconnect the screen comes back without a code" (run
  45821641692, `lastEnd` not "Nord 2 ended the link"); likely the
  host's read losing the Bye to a reset under load.

### 2026-11-26 (226): Desktop mode: the Log panel can be copied

- Found testing spike #118: Log set its text every second, undoing
  any selection and scrolling to the top. Now only new text is set,
  the panel keeps its place unless it was at the bottom, and Copy
  copies it all (`DesktopView.showLogs`, `DesktopLogTest`). Passed on
  the Pixel 10 (2026-11-26). Numbered 226: screen-dev has 222-225.

### 2026-11-26 (225): Extended screen, step 3a: apps sent to B as video

- Branch `screen-send` from `screen-dev`, PR into `screen-dev`. Core,
  `mynx` and the guide tested here; the app's side only compiled in
  CI (screenshots looked at). Not tried on two phones by the agent:
  "Next" 0a step 3 has the test.
- **What's there** (`docs/ARCHITECTURE.md`, "Extended screen: sending
  screens"): protocol version 2 (Video with flags and time, Touch with
  every finger, StreamStart/End, Watch, VideoAck, KeyFrame, TakeBack;
  a version 1 phone is told to update); `ScreenShare` in core (what's
  sent where, typing focus, the screen's streams), `VideoPacer` (flow
  control), `streamFit`/`TouchTrack` (B's view ↔ the video's pixels),
  `encoderSizes`; settings `screen-fps` (60) and `screen-bitrate` (8);
  `mynx screen send [app] NAME`, `mynx screen take [ID|NAME|all]`,
  status lists them; Home's **Send to NAME** list (Take back too) on
  A, "… · from NAME" rows on B. App: `ScreenCaster`, `SentAppScreen`
  (Presentation), `ScreenStreamView`, `ScreenKeysActivity`.
- **Agent's decisions, to confirm:**
  - Only apps (web apps and games) are sent in this step; chats need
    the chat hub to show two chats at once, so they're next.
  - A sent app is a **new page** on its own display, not the one that
    was on A: A's screen for it closes (its service kept), and Take
    back opens it again on A. Its localStorage stays (same WebView
    profile), anything only in memory doesn't. No bridge (`mynx.js`)
    there yet. Moving the live WebView or the activity itself to the
    display wasn't tried in the spike.
  - B shows a stream full screen as it arrives (when mynx is on screen
    there). **Back on B pauses it**: the host stops the encoder (the
    app keeps running, undrawn); B's Home lists it to show again, which
    restarts at a key frame. Leaving the app on B pauses it too.
  - Flow control: at most 6 frames unacked (B acks as it queues each
    to the decoder); more, and A drops frames until the next key
    frame, asked for once the acks drain the pipe (again after 1 s).
  - The video is B's screen size (rounded down to what A's encoder
    takes), at B's density; I-frames every 2 s, a still screen
    repeats at 10 fps.
  - Typing: a finger down on B makes that stream the one typed to (B
    draws a cyan line); when the tap left a text field focused there,
    A opens a typing bar (`ScreenKeysActivity`, A's keyboard), whose
    text goes as key events. Touching A outside it, Done or Back gives
    typing back to A. A's keyboard can't type into B's field without
    that bar (Android's keyboard types into A's focused window).
  - The link ending brings every sent app back to A (if Android lets
    the app start an activity then; else it's in Home as usual).
- **Tests:** the screen link's tests allow 10 s of silence, not 2:
  under the full suite on the phone a link sometimes went 2 s without
  a keep-alive and ended before the test's step (flaky "ended the
  link" asserts, seen before this work too).

### 2026-11-26 (224): Extended screen spikes: video and a second X monitor

- **Video, spike #117** (`screen-spike-video`, throwaway), both
  phones (A = Pixel 10, Android 17 / API 37; B = Nord 2, Android 13):
  - A VirtualDisplay without MediaProjection: PRESENTATION |
    OWN_CONTENT_ONLY, OWN_CONTENT_ONLY, PRESENTATION and 0 work;
    PUBLIC | OWN_CONTENT_ONLY fails (InvalidDisplayException), PUBLIC |
    PRESENTATION needs MediaProjection (SecurityException). So: a
    private PRESENTATION | OWN_CONTENT_ONLY display.
  - 1080x2400 at 120 fps, H.264; A's encoder took the low-latency
    keys; 6-14 Mbit/s. A's encode 20.6 ms, the network 8-11 ms (ping
    5.7-6.4 ms); B's decoder (c2.mtk.avc.decoder) took the
    low-latency keys but has no FEATURE_LowLatency: decode 29-34 ms.
    A drew → B showed: 60-75 ms (38-130); a touch on B → A drew → B
    showed: 67 ms (51-96). Touch dots followed, text was sharp, typing
    from A's keyboard into B's focused field worked.
  - Default 60 fps (B's screens are ≤120 Hz; fewer frames should cut
    the decoder's queue), fps and bitrate as settings.
  - Bug: on Android 13, `window.insetsController` before setContentView
    throws an NPE inside PhoneWindow (AGENTS.md "Lessons").
- **Second X monitor, spike #118** (Pixel only): xrandr showed two
  monitors, maximising stays in its half, an XFCE panel moved to
  `mynx-b` by output name, −B went back to one. **To check** when the
  desktop monitor step is built: each monitor said 264x943 px in
  landscape, which looks too small.

### 2026-11-26 (223): Extended screen: pairing and the link (step 2)

- Branch `screen-core` from `screen-dev`, PR into `screen-dev`. Core,
  `mynx` and the guide tested here; the app's side (`ScreenDevice`,
  NsdManager; the service's wiring) only compiled in CI. Not tried on
  two phones by the agent; the maintainer's two-phone test passed
  (2026-11-26, Pixel 10 and Nord 2: every step of "Next" 0a's list).
- **What's there** (`docs/ARCHITECTURE.md`, "Extended screen:
  pairing and the link"): SPAKE2 over P-256 (RFC 9382, its test
  vectors pass), the handshake, AES-GCM frames, the version 1
  messages (placeholders for video, touch, terminal text), the
  pairs in the app's own files, mDNS `_mynx-screen._tcp`, the
  layout of both phones as one screen, `mynx screen pair | connect
  | disconnect | unpair | place | status`, settings `screen-host`,
  `screen-port`, `screen-place`, touch view's Settings → Second
  screen. `ScreenLink` runs on plain JDK sockets, so two links pair,
  reconnect and unpair on loopback in core's tests.
- **Agent's decisions, to confirm** (the maintainer was asleep;
  conservative picks):
  - Six-digit codes, one try each (a new code after a wrong one),
    three wrong ones stop the pairing, five minutes at most. SPAKE2
    means no offline guessing, so six digits give 1 in a million
    per try.
  - Commands: `mynx screen pair` on A shows the code; `mynx screen
    pair CODE [ADDRESS]` on B joins (one verb, as Bluetooth). B comes
    back with `mynx screen connect`; nothing links again by itself
    yet (after a drop or a restart).
  - A listens (all interfaces, port 7391) only while a screen is
    paired or a pairing waits (`screen-host = auto`); `off` never.
  - Where B sits is A's setting `screen-place` (plain text, in
    Debian); B's `mynx screen place` asks A. Alignment exists in the
    layout model (start, centre, end) but has no setting yet.
  - The name shown is Android's device name (Settings → About), else
    the model.
  - One link at a time; a second screen is refused as busy.
  - Not constant-time P-256 (BigInteger): each scalar is used once.
- **Lesson** (AGENTS.md): several worktrees' Gradle daemons at once
  ran the phone out of memory and Android killed the app;
  `~/.gradle/gradle.properties` caps them now.

### 2026-11-26 (222): Extended screen planned, `screen-dev`

- Plan in "Next" 0a and the roadmap. Maintainer's choices: video for
  screens, text for terminals; typing to the screen touched last;
  the desktop as an extended monitor first; the Nord 2 as phone B;
  `screen-dev` from `desktop-dev` (AGENTS.md "Conventions"), which
  CI builds after merges as it does `desktop-dev`.

### 2026-11-25 (221): Desktop mode: the desktop opens from either view

- **Passed on the phone** (maintainer, 2026-11-26): the whole test,
  and its decisions stand.
- Branch `desktop-app` from `desktop-touch-clipboard` (= what PR #114
  merges), PR into `desktop-dev`. Core, `mynx` and the menu tested
  here; the app's side only compiled in CI (screenshots looked at).
- **Maintainer's decision (2026-11-25):** the desktop isn't a view,
  it's opened from either view like a program. `view` is `terminal |
  touch` again; an old `view = desktop` is the terminal, and the
  settings problem (`mynx check`, the app's dialog) says why and what
  to set (`VIEW_DESKTOP_GONE`). Gone: desktop as the app's start, Back
  on it leaving the app, the terminal's Back going to it, ▣ always.
- **Opening:** the menu action `desktop`, "Linux desktop" in
  `tools/menu.conf` after Files (the terminal menu runs `mynx desktop
  open`; touch view's ☰ lists it under Linux, with a pixel screen icon
  where the Terminal button has its prompt; `HomeAction.Desktop`,
  `HomeLogo.Desktop`), ▣ (while the X server runs), Ctrl+Shift+D,
  and `mynx desktop open [--json]` (`desktop-open`: refused while the
  app isn't on screen, as `app-open` is; answers with the status).
  **A `menu.conf` of the user's own doesn't get the item** (menus
  never merge new defaults); the guide says to add `Linux desktop =
  desktop`.
- **Leaving:** the desktop now lies over the screen it was opened
  from without hiding it, so Back, the bar's first button (was
  ☰ Tabs, now ← Terminal or ← Touch view: `desktopBackLabel()`) and
  Ctrl+Shift+D show that screen again as it was (a chat, Files'
  folder, a Home list). Nothing changes `view`. A chat under it
  notifies as if left; a recreated activity (theme change) reopens it
  (`TerminalService.desktopOpen`). Install opens its tab and shows the
  terminal whatever was under it.
- **Stop desktop:** a button at the top of the Log panel (only while
  it runs): stops it as `mynx desktop stop` does and goes back. Not on
  the bar, so a stray tap can't end the session.
- **Decisions to confirm (maintainer's):** the label "Linux desktop"
  and its place after Files; the back button naming where it goes
  (← Terminal / ← Touch view) instead of "← Back"; Stop desktop in
  Log, going back after; ▣ only while the X server runs (the menu
  item opens it otherwise); user menus not migrated.
- **Phone test** (mynx dev, XFCE installed):
  1. Terminal view, launcher menu: Linux desktop is after Files; pick
     it: the desktop opens (starts if stopped); its bar's first button
     reads "← Terminal". Back: the menu's tab again; the desktop runs
     (▣ on the strip). ▣: the desktop again; ← Terminal: the tab.
     Ctrl+Shift+D (hardware keyboard): there and back.
  2. In a tab, `mynx desktop open`: the desktop shows, the command
     prints "Desktop: opened, running on DISPLAY=:0 (XFCE)". In XFCE's
     terminal, `sleep 5; mynx desktop open` then leave mynx: refused
     ("the app must be on screen…").
  3. `mynx set view touch`; ☰: Linux desktop under Linux, with a
     screen icon; tap it: the desktop, its button "← Touch view";
     Back: the chat home. Open a chat, then Ctrl+Shift+D, then Back:
     the same chat, scrolled as it was. In Files, open a subfolder,
     Ctrl+Shift+D, then Back: Files at that folder.
  4. Log on the desktop: Stop desktop at the top; tap it: back where
     it was opened, ▣ gone, `mynx desktop` says not running.
  5. `mynx set view desktop`: refused; edit settings.conf to `view =
     desktop`, restart the app: the terminal, with a settings problem
     saying `view = desktop is gone…`; `mynx check` lists it.
  6. With the desktop open, `mynx theme set` from XFCE's terminal (a
     theme change rebuilds the screen): the desktop is still open.

### 2026-11-25 (220): Desktop mode: the clipboard

- PR #114, branch `desktop-touch-clipboard` from `desktop-dev`. Core
  tested here; the app's side only compiled in CI.
- **Built, then rolled back** (maintainer's decision, 2026-11-25,
  before trying them): direct touch as a phone's touchscreen (drag
  scrolls with fling, long press right-clicks or drags, pinch zooms
  our view; and a fix for a touchpad drag that got a second finger
  never releasing the button) and a view switcher on the desktop's
  bar (Terminal, Touch, Desktop setting `view`; Back to the view the
  desktop came from). Both are reverts on the PR; the full version
  is branch `desktop-touch-clipboard-full`. Getting from the desktop
  to touch view is still open.
- **Clipboard** (Termux:X11 24ce207's protocol, `clipboard.c` and
  `cmdentrypoint.cpp`): enable (11) per connection, which the server
  needs (off by default, off again when a connection closes); X → us:
  14 with a count and UTF-8 when a program takes CLIPBOARD; us → X:
  announce (12), then 14 at once; requests (13) answered with the
  same text. CLIPBOARD only (PRIMARY would mean every selection
  overwrites Android's clipboard). Limits: 64 KiB to X (the server
  reads it with one `read()`), 256 KiB from X (binder). Android's
  text goes to X when the desktop has the focus (Android 10+).
- **Passed on the phone** (maintainer, 2026-11-25): copying both
  ways, no repeats. `ä` and `✓` pasted
  into XFCE's terminal, `日本` showed as boxes: no CJK font in a bare
  Debian, so the guide names `fonts-noto-cjk` (not in `mynx desktop
  install`: ~50 MB).
- **Decisions to confirm (maintainer's):** the limits; CLIPBOARD
  only; Android's text sent to X at once on coming back (the server
  holds a copy), not only when X pastes.

### 2026-11-25 (219): Desktop mode, step 5: Debian's side

- **Passed on the phone** (Pixel 10, maintainer, 2026-11-25):
  everything in its test, no issues.
- Branch `desktop-debian` from `desktop-view` (PR into it). Core and
  `mynx` tested here; the app's Install button only compiled in CI.
- **Installing:** `mynx desktop install [--yes]` (like `mynx sound
  install`: shows the apt commands, asks) installs the status's new
  `install` list: `desktopPackages()` minus what dpkg lists as
  installed. XFCE: `xfce4 xfce4-terminal dbus-x11 tango-icon-theme`,
  both sessions `xkb-data xfonts-base`, all `--no-install-recommends`
  (xfce4 recommends `xorg`, Xorg's own server, plus polkit and
  more); ~70 packages on a bare trixie. tango because XFCE's default
  icon theme is Tango (xfce4-settings' xsettings.xml). Refusals now
  name `mynx desktop install`; the view's notice offers Install (a
  tab running it) instead of Start when a package is missing.
- **Fonts, the cause found:** Termux:X11 (24ce207,
  `cmdentrypoint.cpp`) takes the first of `<rootfs>/etc/X11/fonts`,
  `<rootfs>/usr/share/fonts/X11` that exists, and xfonts-base ships
  `/etc/X11/fonts/misc/xfonts-base.alias`, so the font path pointed
  at folders with no fonts. `x11Launch()` now passes `-fp` with the
  `/usr/share/fonts/X11/*` folders that have a `fonts.dir`, then
  `built-ins` (read at each start).
- **Sound:** nothing to change: the session is a `debianLaunch`, so
  it already had `PULSE_SERVER`. Not checked: XFCE autostarts
  `start-pulseaudio-x11`, which loads Pulse's X11 modules into our
  server (should be harmless).
- **Firefox (hypotheses, untested):** proot-distro gives every login
  `MOZ_FAKE_NO_SANDBOX=1`, `MOZ_SHM_NO_SEALS=1`, a `/dev/shm` bind
  and `--sysvipc`. mynx had none: here `shm_open` fails with ENOENT
  and `shmget` with ENOSYS. Now every proot has the two variables
  and the own `/dev` a `shm` folder (not with `serial-names = off`).
  No `--sysvipc` (decision, conservative): segments proot emulates
  would be unknown to the X server outside proot, so MIT-SHM attaches
  could fail where now clients see no SysV shm and fall back.
- **Decisions to confirm (maintainer's):** the package set; the two
  MOZ variables in every tab, not only the desktop's; Install opens a
  tab rather than installing from the view; `/dev/shm`'s files are
  never cleaned (they'd go at a reboot on Linux).

### 2026-11-25 (218): Desktop mode, step 4: the desktop view

- **Passed on the phone** (Pixel 10, maintainer, 2026-11-25):
  everything in its test, no issues; the maintainer liked that the
  keyboard covers the bottom while the focused field stays above it.
- Branch `desktop-view` from `desktop-build` (PR #112, base
  `desktop-build`). Rebuilt test-first from the spike: protocol,
  gestures, keys and layout logic in core (`X11Input.kt`,
  `DesktopScreen.kt`, `DesktopGestures.kt`, `DesktopKeys.kt`); the
  app's `DesktopLink` (binder, socket, in the service) and
  `DesktopView` are thin. CI compiled it and drew the screenshots
  (`desktop*.png`); nothing tried on the phone.
- **The spike's three faults:** (1) stale picture after reopening:
  the renderer uploads only damaged rows; a screen-size event with
  only the frame rate changed (rate+1, then the rate) makes the
  server build a new root buffer, drawn whole
  (`DesktopSurface.handover()`); if the phone still shows a stale
  frame, the cause is elsewhere. (2) The soft keyboard no longer
  resizes the X screen: the layer keeps its height and moves up to
  keep the pointer in sight (`keyboardPan()`; API 30+). (3) The
  screen lies between our bar and the key bar.
- **Decisions to confirm (maintainer's):** Ctrl+Shift+D switches
  desktop ⇄ terminal from any view (Windows Terminal uses it for
  duplicate tab, which mynx lacks); Back on the desktop leaves the
  app in desktop view, else goes to the terminal; ▣ on the strip
  (always in desktop view, else while the server runs); opening the
  desktop starts it when nothing runs and nothing is missing; an X
  pixel is 0.75 dp, whole phone pixels (2 on the Pixel 10, as the
  spike); natural scrolling (content follows the fingers), 40 X px a
  notch; three-finger tap = middle click; direct mode's long press =
  right click; our own arrow (the server's cursor needs its shared
  memfd, which we drop unmapped, so cursor shapes don't show); the
  hardware keyboard by scan code (X's layout is US unless
  `setxkbmap`); a keyboard's Esc that Android calls Back is Esc.
- **Key bars:** sticky `Alt` and `Super`, `Super+KEY` chords (the
  terminal gets the sticky Alt through `readAltKey`; Super does
  nothing there); built-in `desktop` bar (Esc Tab Ctrl Alt Super
  arrows); `KeyBarView` sends through a `KeyBarTarget`
  (`TerminalKeys`, or the desktop's). Sticky modifiers on the
  desktop also hold for the next click or scroll.
- Also: a USB/Bluetooth mouse points, clicks and scrolls; held keys
  are released when the desktop hides. Not done: clipboard sync,
  touch view's Home entry for the desktop (Ctrl+Shift+D and ▣ only).

### 2026-11-25 (217): Desktop mode, step 3: the X server built in CI

- **Passed on the phone** (Pixel 10, maintainer, 2026-11-25):
  everything in its test, no issues.
- Branch `desktop-build` from `desktop-dev`, PR into it. The spike's
  build script and patch, reviewed: `scripts/build-x11.sh` now
  installs the NDK it pins itself and checks its version, fails if no
  library was built, checks the licence notices and packs the source;
  outputs land where `DesktopProcesses` looks
  (`jniLibs/arm64-v8a/libXlorie.so`, `assets/x11/termux-x11.jar`) and
  the patch reads core's `MYNX_X11_LIB`/`MYNX_X11_PACKAGE`.
- **Patch:** the knock port (TCP 7892) listens on 127.0.0.1 only (it
  was every network). Kept, not turned off: the server rebroadcasts
  its binder each second until connected, so our view won't knock,
  but Termux:X11 also uses the bound port as a lock; a local knock
  only makes it send the broadcast to mynx again.
- **CI:** an X server step in `build.yml` and `release.yml`, cached
  on the scripts, patch and licence file;
  `scripts/check-x11-apk.sh` (bats-tested) fails a build whose APK
  lacks the library or jar or has `Lcom/termux/x11/` in mynx's dex.
- **Licences** (maintainer to confirm): About lists Termux:X11
  (GPL-3.0, commit 24ce207) and "X.Org X server and libraries"
  (21.1.26; pixman, libtirpc, libepoxy, libandroid-shmem too; bzip2
  is a submodule but not linked). Their notices are one file,
  `tools/licenses/X11-libraries.txt` (250 KB, from each `COPYING`,
  by `scripts/x11-licenses.sh`; the build fails if it's stale).
  Source: each release attaches `mynx-X.Y.Z-x11-source.tar.xz`
  (upstream tree with submodules, unpatched, plus our patch, scripts
  and a README), so no written offer. PR builds don't attach it.

### 2026-11-25 (216): Desktop mode, step 2: settings and `mynx desktop`

- Branch `desktop-core` from `desktop-dev`, PR into it. Rebuilt
  test-first from the spike's findings, none of its code copied.
- **Settings:** `view` takes `desktop` (the app shows the terminal
  for it until step 4); `desktop = xfce | none` (the session; `none`
  is the X server alone, for programs started with `DISPLAY=:0`);
  `desktop-touch = touchpad | direct` (maintainer's decision,
  touchpad default; named like `tab-swipe`, read by step 4's view).
  Touch view's Settings has a Desktop list for the two.
- **core** (`Desktop.kt`): `x11Launch()` is the spike's command line
  plus `-nolisten local` (`-ac` lets any client in, and Android's
  abstract sockets are shared by every app); `desktopSessionCommand()`
  waits for the socket, then `dbus-launch --exit-with-session
  startxfce4`; `Desktop` says what's missing (with the apt command),
  clears a stale `/tmp/.X0-lock`/socket, TERMs (then KILLs) a leftover
  `mynx-x11` and refuses when another program's server holds :0.
  `desktopRequests()` answers `desktop-start|stop|status`;
  `prootLaunch(display =)` puts `DISPLAY` in new tabs and services.
- **app:** `DesktopProcesses` runs the server (copying the jar
  read-only from the assets each start) and the session's proot; with
  no `libXlorie.so` or jar in the APK, `server` is null and the
  requests say so. Not compiled here (CI only).
- **`mynx desktop [status] | start | stop`** (`--json`): `start`
  returns once the socket takes connections, else points to
  `/tmp/.mynx/desktop/x11.log`. No `mynx desktop install`: installing
  XFCE is step 5; the refusal names `apt install xfce4 dbus-x11`.
- Docs: `tools/docs/desktop.md`, the guide's settings and command
  rows, ARCHITECTURE "Desktop mode".

### 2026-11-25 (215): Desktop spike: Termux:X11 runs in mynx

- Draft PR #108 (`desktop-spike`, throwaway). Termux:X11 @ 24ce207:
  `scripts/build-x11.sh` builds `libXlorie.so` (NDK 29.0.14206865,
  CMake, its 16 submodules) and a jar of only `CmdEntryPoint` + its
  AIDL stub (aidl, javac, d8; no Gradle), with a GPL patch in `x11/`
  (no activity classes needed, library path from `MYNX_X11_LIB`,
  app ID from `MYNX_X11_PACKAGE`, stretch the screen when no view
  was set). The app runs `app_process … CmdEntryPoint :0 -ac`
  outside proot with `TMPDIR=<rootfs>/tmp` (so the socket is
  Debian's `/tmp/.X11-unix/X0`; xkb and fonts are found from
  `dirname(TMPDIR)/usr/share`). The server broadcasts its binder to
  the app; our MIT side hands it the `Surface` (`windowChanged`)
  and writes 24-byte `lorieEvent`s to `getXConnection()`'s socket.
  CI checks no `Lcom/termux/x11/` class is in mynx's dex.
- **On the phone** (Pixel 10, maintainer): xeyes follows the finger,
  xterm draws and types (Backspace, Enter), sensible text size at
  SCALE=2, smooth scrolling at 120 Hz, rotation resizes, programs
  survive Home and Back. Wrong: the picture is stale after
  reopening until a resize (the server only redraws damage), the
  keyboard resizes the X screen (should pan), the screen is drawn
  under our button bar. Firefox crashed or showed no window; not
  looked into yet.
- Licence points for step 3: our side copies the binder's
  transaction order and the event layout (interface facts);
  releases must publish the patched server's source.

### 2026-11-24 (214): Desktop mode planned

- Plan in "Next" 0 and the roadmap: XFCE, touchpad by default with
  direct taps as a setting, no external display yet, VNC only maybe.

### 2026-11-24 (213): A `desktop-dev` branch for desktop mode

- The maintainer wants desktop mode kept off `main` while it's built:
  `desktop-dev` (cut from `main` at 2b4d7f4, pushed) takes its
  feature branches' PRs. `build.yml` builds pushes to it, as for
  `main`, so `scripts/deliver.sh` finds its runs after a merge.
  `AGENTS.md` ("Conventions") has the rule.

### 2026-11-24 (212): Files' dialogs in the pixel style

- Passed on the phone (2026-11-24); PR #105 merged.

- Rename, New folder and Delete in touch mode's Files now ask in
  `pixelDialog` (roadmap item, maintainer's request), as the chat
  rows' do: `askFileName()` and `confirmFilesDelete()`, top-level in
  `FilesView.kt` so the screenshots drive them.
- `pixelDialog` gained a note line under the field,
  `PixelDialog.setNote()` (hidden until set): `fileNameProblem()`'s
  reason goes there, since EditText's `error` popup takes the system
  theme. The chat rows don't use it.
- Left as AlertDialogs: Files' `tell()` (errors) and Open with (a
  list of choices).
- Screenshots: `files-rename`, `files-rename-refused`,
  `files-new-folder`, `files-delete`, `files-delete-many`. Not
  compiled here (CI only).

### 2026-11-24 (211): chat rows' menu in the pixel style, Pinned and Recents

- Passed on the phone (2026-11-24): the pixel menu, the Pinned and
  Recents groups and the pixel Rename and Delete dialogs. PR #104
  merged.

- The maintainer tried entry 210's build: pin, rename and delete all
  worked. They asked for the menu in the app's design, "Pin" for "Pin
  to top", and a Pinned group above the rest, called Recents.
- The long press now drops `pixelMenu` from the row (Android's
  dialog list took the system theme). The history and the drawer
  show Pinned (only when there are pins) and Recents headings; core's
  `ChatHome` gives the drawer `drawerPinned` (all pins) beside its 5
  recents (`drawerChats`), so pins no longer take recents' places.
- Then, at their request, Rename and Delete ask in a `pixelDialog`
  (`PixelStyle.kt`, beside `pixelMenu`): the menu's box, a styled
  field, Cancel and a filled main button (Delete's pink). Files'
  dialogs are still AlertDialogs: moving them to it is on the roadmap
  (Touch mode), at the maintainer's request.
  Screenshots: `chat-rename`, `chat-rename-blank`, `chat-delete`.

### 2026-11-24 (210): pin, rename and delete chats in touch mode

- The maintainer asked for a long press on a chat, in the history and
  in the drawer: pin to top, rename, delete. One menu
  (`app/.../ChatRowMenu.kt`, AlertDialogs as Files' Rename/Delete),
  `ChatRowActions` implemented in `MainActivity`'s `chatHomeActions`.
- Pins: app state (`ChatPins`, `filesDir/state/chat-pins`, beside
  `chat-picks`), not a setting; pinned rows lead `chatHistory()` and
  the drawer, and `listClaudeSessions(keep =)` lists them past the
  50-chat cap without reading every transcript's head.
- Rename writes Claude Code's own `custom-title` line (found in its
  binary, 2.1.331: `{"type":"custom-title","customTitle":…,
  "sessionId":…}`, last wins), so Claude's `/resume` agrees and names
  given with `/rename` show in mynx; read from each transcript's last
  64 KB. Not for a chat with no transcript yet ("Send it a prompt
  first").
- Delete runs in Debian (`rm -rf` of the transcript, its folder and
  its file history), refused while a chat holds a process on it.
- Core tests in `ClaudeHistoryTest`, `ChatHubTest`, `ChatHomeTest`;
  the screenshots now have a pinned row. Not committed yet.

### 2026-11-23 (209): v0.5.0 released

- Main's build for `8bd3d39` (the #102 merge) was green; tagged it
  `v0.5.0` and pushed the tag at the maintainer's request (they asked
  the agent to push it this time; the rule that only the maintainer
  tags stands otherwise). `release.yml` built and published
  `mynx-0.5.0.apk`.
- Status above now says v0.5.0; the list of merged-since features
  went into it.
- **To check on the phone:** `mynx update` in the release mynx finds
  0.5.0, installs it, and What's new shows its notes;
  `mynx install-apk` now works in the release build.

### 2026-11-22 (208): `mynx android-sdk` (PR #102; passed)

The maintainer asked for the other half of the Android-apps item: a
command that sets up what building an Android app in Debian needs,
instead of the guide's five lines of apt, curl and unzip.
- `mynx android-sdk [status] | install [--yes]`
  (`tools/lib/mynx/androidsdk.py`, the command in `cli.py`): status
  lists the build commands (aapt, zipalign, apksigner, javac) and the
  two files; install shows the apt commands for the missing packages
  and the downloads, asks once, then installs and downloads only
  what's missing. `r8.jar` 8.13.25 and `android.jar` (taken out of
  `platform-34-ext7_r03.zip`, the zip isn't kept) go into
  `~/androidsdk`, where the guide's `build.sh` looks. URLs, sizes and
  SHA-256 are pinned; a download is checked before it's moved into
  place, so a failed or wrong one leaves nothing. Python's zipfile
  does the unzip, so `unzip` isn't needed any more.
- `apt_install` is split into `apt_steps` and `run_steps`, so the
  command asks once for packages and downloads together.
- Tests: `test_androidsdk.py` (downloads from a local HTTP server:
  checked, from a zip, a mismatch or failure leaves nothing,
  progress, status, the pins), `test_mynx.py` (status, JSON, the plan
  and asking, only what's missing, apt failing, usage),
  `home-docs.bats` (the guide gives the command, not the old lines).
  Docs: the guide's table, `apps.md`'s "Building an Android app",
  ARCHITECTURE.md's new entry.
- Checked here: status read the maintainer's existing setup as ready,
  and a real install into an empty scratch home downloaded both files
  (`android.jar` the same as the one in use).
- In mynx dev (build 45074118407, an agent following a test prompt):
  status and JSON said what was missing; no answer changed nothing
  (exit 1); `install --yes` installed the packages and both files and
  said ready; again, it changed nothing. The agent then built a hello
  app from the guide and `mynx install-apk` opened the installer.
- The guide's build steps failed on that first try, so they're
  rewritten (checked by building a hello app here): `build.sh` is a
  whole script with `set -euo pipefail` (a failed step had still
  left a signed APK with no `classes.dex`, which would crash), makes
  its `build/` folders (aapt and D8 don't), and signs with the
  password from `keystore.pass`, so `apksigner` doesn't ask for it.
  There's a full `keytool` command, the smallest manifest
  (`minSdkVersion` = D8's `--min-api`, `android:exported="true"`)
  and Activity, and "a few seconds" for a build, not "a minute". The
  plan's `android.jar` line now reads "(out of a 60.3 MB zip)".
- The hello app opened on the phone, showing its text.

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

