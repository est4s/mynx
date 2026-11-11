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
- **Released:** v0.1.0 and v0.1.1 (2026-10-10), v0.1.2 (2026-10-12),
  v0.2.0 (2026-10-13, background services), v0.3.0 (2026-10-26,
  development boards: USB serial, raw USB, UF2), v0.4.0 (2026-11-01,
  Bluetooth serial, BLE, BLE UART ports), signed with the
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
- **Background services:** all 21 phone tests passed (the last,
  autostart on and off, on 2026-10-13); merged (PR #30, #32) and
  released in v0.2.0. The plan left "Next" (git history has it:
  `git show 8ea1266:docs/WORKLOG.md`).
- **USB serial:** the bridge (PR #33) and the preload library for
  `/dev/serial` (PR #34) are merged; every phone test passed with an
  ESP32-C3, an ESP32-CAM-MB, an Arduino Nano and a Pro Micro (two at
  once on a hub). Released in v0.3.0. The plans and tests:
  `git show d748955:docs/WORKLOG.md` (bridge),
  `git show 46bd16c:docs/WORKLOG.md` (preload, entries 115-122).
- **USB serial part 3** (serial monitor, board tools, port names):
  merged (PR #36). Released in v0.3.0.
- **Go programs** (proot's `mynx_serial` extension: `arduino-cli
  board list` and `monitor`): every phone test passed (entry 129);
  merged (PR #37). Released in v0.3.0.
- **PlatformIO's ESP32 toolchains** (proot's `mynx_links`
  extension): a C3 project builds on the phone (entry 132); merged
  (PR #39). Released in v0.3.0.
- **Small serial fixes** (PR #42, entry 136): the bridge's own read
  queue (`UsbReads`), the busy hint and the no-port retry; the 4 MB
  `read_flash` on the C3, the Nano, a Pro Micro upload and the busy
  hint passed on the phone (build 214); FTDI not tried. Released in v0.3.0.
- **Android's process limit:** confirmed on the Nord 2 (entry
  138): the `device_config` commands stop the kills; the guide now
  gives them for Android 12-13.
- **Services and `~/.local/bin`:** fixed and confirmed on the phone
  (PR #46, entry 139). Released in v0.3.0.
- **Raw USB:** the spike worked on the phone with an RP2040 Zero
  (entry 141: `picotool info` and `load`, libusb, `lsusb`, pyusb);
  rebuilt test-first and merged (PR #47, entry 142); every phone
  test passed. Released in v0.3.0.
- **UF2** (`mynx uf2 flash`): every phone test passed with the
  RP2040 Zero; merged (PR #49, entry 144). Released in v0.3.0.
- **Bluetooth serial** (SPP, `mynx bt`): every phone test passed with
  the ESP32-CAM (entry 149); merged (PR #52). The plan and its tests:
  `git show 0280cfa:docs/WORKLOG.md`.
- **Bluetooth LE** (`mynx ble`): built by four subagents against a
  contract (entries 151-152); every phone test passed with the
  ESP32-C3 as `mynx-ble` (entry 153) but refusing the permission
  (revoking it kills the app, and the session ran in it; core's tests
  cover it). PR #53. The plan and its tests:
  `git show 49b137b:docs/WORKLOG.md`.
- **BLE UART ports** (`mynx ble bind`, `nus0`): built overnight by
  subagents (entry 155); all 10 phone tests passed with the C3
  (entry 156), and the re-bind check (entry 157). Merged (PR #54).
  The plan and its tests: `git show 3539809:docs/WORKLOG.md`.
- **Web apps, part 1** (`~/apps/NAME/`, `mynx app`, terminal apps
  in `~/terminal-apps`): all 13 phone tests passed (entry 164); PR
  #59.
- **Web apps, part 2** (the bridge, `mynx.js`, permissions per app,
  shortcuts, `fullscreen`, `screen-awake`): 22 phone tests passed
  (entry 169); PR #64. The plan and its tests: `git show
  044f80f:docs/WORKLOG.md`.
- **Services count in the launcher menu** (`Services ● 2`): all 4
  phone tests passed (entry 161); merged (PR #58).
- **Workflow:** trunk-based, `main` protected (PRs only, entry 83).
  CI builds PRs; `scripts/deliver.sh` installs a build in mynx dev.
- The code's layout and decisions: `AGENTS.md`.


## Next

### 0. Screenshots of touch mode (entry 179)
They render in CI (first run 42743298241) and show real text and
cards. Fetch them with `gh run download RUN -n screenshots -D DIR`
to look at a screen change before a phone round.

### 1. Development boards (leftovers; the maintainer picks)

The roadmap's last items, planned with the maintainer 2026-10-25
(entry 140). **Maintainer's decisions:** raw USB first, then UF2;
drives only for flashing UF2 (no general read/write of USB drives,
e.g. CircuitPython's CIRCUITPY). Test hardware: an **RP2040 Zero**
(BOOTSEL: a UF2 drive and the PICOBOOT interface `picotool` uses)
and the **ESP32-C3** (its USB-Serial-JTAG: OpenOCD over raw USB,
beside its serial port on the same device).

**Design (agent's proposal, change it if the maintainer objects):**
Android won't let apps open `/dev/bus/usb/*` (SELinux: no `open` on
`usb_device`), but the fd from `UsbManager.openDevice()` takes the
usbfs ioctls libusb sends (Termux's `termux-usb` +
`libusb_wrap_sys_device`). Aim: unpatched tools work.
- **The app hands out fds:** a Unix socket (under the ports folder,
  e.g. `/dev/bus/usb/.ctl`); per request `BBB/DDD` it opens the
  device again and sends a fresh fd with `SCM_RIGHTS`, so each
  program has its own (usbfs claims are per fd, so the serial
  bridge's CDC interface and OpenOCD's JTAG interface coexist).
- **Debian sees devices:** placeholder `/dev/bus/usb/BBB/DDD` in the
  own `/dev`, and `/sys/bus/usb/devices/B-P` with `descriptors`
  (`getRawDescriptors()`), `busnum`, `devnum`, `speed`, `idVendor`,
  `idProduct`, strings, so libusb, pyusb and `nusb` enumerate
  without opening (as `/sys/class/tty`, `SerialNames.kt`). Logic in
  core.
- **proot's `mynx_usb` extension:** `open`/`openat` of
  `/dev/bus/usb/BBB/DDD` becomes chained `socket` + `connect` +
  `recvmsg` (as mynx_serial's chained `bind`), returning the
  received fd. proot, not the preload, because probe-rs's `nusb`
  (rustix) and Go make raw system calls. Decisions natively tested
  (`tests/mynx/test_proot_usb.py`), register moving on the phone.
- **Hotplug:** the uevent stand-in also sends the kernel's `usb`
  device `add`/`remove` messages (`SerialUevents` grows).
- **Permission and listing:** `mynx usb` lists every allowed USB
  device, not just serial ones; `mynx usb allow` takes any; the
  device filter adds known boards (RP2040 BOOTSEL `2e8a:0003`, STM32
  DFU `0483:df11`, ESP USB-JTAG `303a:1001` is there already).
- **Risks:** a device that resets or reboots (picotool, DFU) comes
  back as a new one and needs Android's permission again ("always"
  once); a phone whose kernel binds a driver to an interface (tools
  must detach it, `USBDEVFS_DISCONNECT`); 4.19 kernels have no
  `pidfd_getfd`/seccomp `addfd`, hence the chained calls.
- **UF2, after raw USB:** first find out whether the phone mounts
  the BOOTSEL drive itself (Android's OTG storage) and the app can
  write it; if so, show it in Debian as `/media/LABEL`, so `cp
  fw.uf2 /media/RPI-RP2/` and tools that look for `INFO_UF2.TXT`
  work. Else (or as well) `mynx uf2 flash FILE`: the app writes the
  file's 512-byte blocks with SCSI `WRITE(10)` past the FAT area
  over bulk-only transport (UF2 bootloaders take blocks at any
  sector), so no FAT library. A Pico also flashes through
  `picotool` once raw USB works.

**Order:**
1. **Spikes:** done (entry 141); the spike branch is deleted.
   Raw USB works through chained calls in proot; Android sees the
   BOOTSEL drive but calls it damaged and doesn't mount it, so UF2
   is the app's own writer (or `picotool`).
2. **Raw USB:** merged (PR #47, entry 142). Its phone tests:
   `git show 9a4718c:docs/WORKLOG.md`. Later parts: hotplug
   (libudev's monitor: libudev-format messages, sender uid 0, a
   netlink address), and the recipes as hardware allows. The C3's
   built-in JTAG passed with no code change (entry 146, branch
   `c3-jtag`). Left: `dfu-util` (needs a board with a ROM DFU
   bootloader, e.g. a WeAct Black Pill STM32F411) and a CMSIS-DAP
   probe (the RP2040 Zero with `debugprobe`, plus an SWD target);
   `probe-rs run` flashing the C3 (needs an esp-hal ELF, replaces
   MicroPython).
3. **UF2: merged (PR #49**, entry 144; maintainer's decision: in
   Debian over raw USB, not in the app). `mynx uf2 [list]` and
   `mynx uf2 flash FILE [DEVICE]` (`tools/lib/mynx/uf2.py`). Done on
   the phone with the RP2040 Zero: list, the refusals (not UF2, an
   RP2350 file, an unknown device) and a real flash (MicroPython,
   1302 blocks in 3.7 s, back as `ttyACM0`). On PR #49's build
   (run 40304818170) in mynx dev: `mynx uf2` from `/opt/mynx`, and
   the maintainer's flash in a tab showed `writing... 1302/1302`.
   All passed. Not tried: an RP2350, TinyUF2 or Adafruit
   bootloaders (other `INFO_UF2.TXT` layouts, no MBR).

### 2. Smaller things

- **Maybe:** `CONTRIBUTING.md`, `SECURITY.md`.
- **Maybe, for Android apps built in Debian** (guide: "Making an
  app", entry 134): a `mynx` command that opens the installer for an
  APK in release builds too (`install-apk` answers only in debug),
  so agents needn't serve it to the browser; and one that fetches
  `r8.jar` and `android.jar`.

### 3. Touch mode: Chat for Claude Code, done

Passed on the phone and merged (2026-11-10): the Chat stack (#67-#76,
entries 172-182), then queued prompts, parallel chats with the history
as the agent's home, picks kept per chat, Auto as the default
(`chat-mode`), and the Claude-app layout (#78-#81, entries 183-184).

Left: other agents (Codex's `app-server`, Gemini's ACP), which need
accounts to test; image thumbnails on attachment chips; the Model page
lists fallback models until a session reports its own.

### 4. Touch mode: chat first, with a drawer (entry 186, in progress)

Decided by the maintainer (`docs/ROADMAP.md`, Touch mode); the spec is
`docs/TOUCH-DESIGN.md`. **Style: pixel, with Pixelify Sans** for
labels, buttons and headings and JetBrains Mono for text (maintainer,
2026-11-11); the touch screens use the logo's fixed palette, not the
terminal theme.
1. **PR 1, written** (branch `touch-chat-home`, entry 186): the font,
   `PixelStyle.kt`, core's `chatHome()`, `ChatHomeView` with its
   `DrawerView`, the wiring (`ChatPlace.HOME`, Back) and
   `ChatHomeScreenshotTest`. Not compiled yet: push, fix what CI
   finds, look at the `screenshots` artifact (`chat-home*.png`), then
   a phone round:
   - start in touch view: the chat home, no keyboard; ☰ opens the
     drawer, the scrim and Back close it; ● N opens Services;
   - Send from the home: a new chat with that prompt sent (also on a
     fresh start, when the sign-in check is still running); + and the
     mic carry the text into the new chat;
   - the drawer's chats, All chats, each Linux item (lists open Home's
     list, whose Back returns to the chat home), Terminal;
   - Back from a chat, the history, a Home list and the terminal lands
     on the chat home; from it, the app is left;
   - the app recreated (rotate) comes back to the same screen;
   - Claude Code not installed: the Install button.
   Passed on the phone (maintainer, 2026-11-11: "everything seems to
   be working").
2. **PR 2, written** (entry 187, on the same branch): the chat, its
   sheets and the history in the pixel style, with block icons. Look
   at CI's `chat*.png` and `chat-history*.png`, then on the phone: a
   chat while working (Stop pink, the box's shadow), a permission card
   and its buttons, + and Usage, the history's rows and New chat.
   Then (entry 188): the default model, Copy, Markdown cards, Home's
   lists and ⋮ in the pixel style, no Terminal buttons. On the phone:
   + → Model → Default for new chats (then a new chat starts with it:
   the line under the title), Copy under a reply, a card after Claude
   writes a `.md` file (Copy, Share), ⋮ → Open in a tab, the drawer's
   Linux lists. Entry 188 compiled and its screenshots looked right
   (run 43074583752).
   Then (entry 189): Settings, System info and Help as touch lists.
   Look at CI's `home-settings*.png`, `home-themes.png`,
   `home-setting-*.png`, `home-system-info.png`,
   `home-release-notes.png`, `home-help.png` and `home-report.png`,
   then on the phone: drawer → Settings; flip an on/off setting (Swipe
   between tabs), pick a theme (the terminal changes), type a text
   size (a bad one shows the reason), Undo → Undo last change, System
   info, Help's pages, Report a bug → Next opens a tab with the
   report; the default model's first choice reads "Claude Code
   decides".
3. **Maybe, in core:** a first prompt waiting for the sign-in check
   (MainActivity's `firstPrompt` for now: `ChatController.send()`
   refuses while `CHECKING`); `ChatHub`'s home and cwd made public
   (MainActivity copies "/root").

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

### Hardware keyboard checks
Tried 2026-10-17 with the maintainer's first external keyboard (mynx
dev 175). **Pass:** Ctrl+Shift+T and Ctrl+Shift+W; holding Ctrl+Shift+T
opens one tab; Ctrl+Tab and Ctrl+Shift+Tab, wrapping round; Ctrl+Alt+1…9
(nothing past the last tab), and Android swallows none of them;
Ctrl+Shift+R, with Enter saving and Esc cancelling; Ctrl+W, Ctrl+T and
Ctrl+Alt+0 still reach bash.

**Left, with another keyboard:** Ctrl+Shift+PgUp / PgDn move the
selected tab left / right. Not testable: this keyboard's Fn+Up/Down
sends nothing at all (`cat -v` printed nothing, even without
Ctrl+Shift).

### Small open items
- The wakelock is a setting (10.2); a notification action to toggle it
  (like Termux) could come later if people want it.
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).
- `test_undo_list` (tests/mynx) fails in the phone's time zone (it
  expects UTC times; CI is UTC): `TZ=UTC` passes it.

---

## Log

### 2026-11-11 (189): Settings as touch lists; "Claude Code decides" (written, not compiled)

The maintainer: the default model's "Default" choice didn't make sense
(renamed, their pick: "Claude Code decides", saying it may change), and
everything under Settings should be touch menus, not terminal programs
(their pick: menus, with Update all and GitHub setup still in a tab).
Entry 188's CI first failed installing `bats` (the runners couldn't
resolve `security.ubuntu.com`, `apt-get update` didn't help): CI now
takes bats-core v1.13.0 from GitHub, pinned by hash, in `build.yml` and
`release.yml`. The guide then lacked `chat-model` (the docs test
caught it).
- core, test-first: `chatDefaultModelChoices()`; `HomeSettings.kt`:
  Settings' lists (`HOME_SETTING_GROUPS`, every setting in one, labels),
  choices pages, Theme (colours as swatches), Undo (its steps),
  System info (`SystemFacts`), Help (a touch Getting started, release
  notes made plain, About, Report a bug typed in). `HomeItem` gained
  `detail`, `checked`, `swatch` and `kind`; `HomeAction` gained
  `Config` (a `mynx` request), `Input` and `Link`;
  `homeInputAction()` says what typed text does.
- app: `TerminalService.configRequest()` runs a request in-process as
  `mynx` does (undo records it) and applies it as `processRequests()`
  does; `HomeView` draws the new rows and a text input page.
Left: CI's compile and screenshots, then the phone ("Next" 4).

### 2026-11-11 (188): Default model, Copy, Markdown cards, the rest in pixel (written, not compiled)

The maintainer, after trying entry 187's build: ⋮'s Model… was
useless (+ has Model), there was no way to set the default model (theirs
picked up a model they didn't want), they wanted Copy on Markdown, all
menus in the pixel style, and no Terminal buttons (the drawer has one).
Asked which Copy: both, under each reply and on cards for `.md` files.
- core, test-first: the `chat-model` setting (`default`: no flag; else
  a name `--model` takes); `ChatHost.chatDefaultModel()` and
  `setChatDefaultModel()`, `ChatState.defaultModel`,
  `ChatController`/`ChatHub.setDefaultModel()`. Only new chats take it:
  a past one resumes with its own model. `ArtifactKind.MARKDOWN`: a
  written `.md`/`.markdown` file anywhere (not one a command names),
  looked for as soon as it's written; actions Copy and Share.
- app: the Model sheet's "Default for new chats" page; Copy under each
  reply (its Markdown) and on Markdown cards (the service reads the
  file, 256 KB at most); ⋮ is a `pixelMenu()` with Open in a tab;
  `HomeView` in the pixel style; no Terminal in Home, the history or ⋮.
Left: CI's compile and screenshots, then the phone ("Next" 4).

### 2026-11-11 (187): Chat, its sheets and the history in the pixel style (written, not compiled)

The maintainer tried PR 1 on the phone ("everything seems to be
working") and asked for the chat and the history to match. On the same
branch, by the lead alone:
- `ChatView` takes `Pixel.COLORS` (the palette as `StripColors`), not
  the theme's; all its text is JetBrains Mono, labels and buttons
  Pixelify Sans, Markdown headings too (`PixelFontSpan`: the
  `TypefaceSpan(Typeface)` constructor is API 28+). Inline code is
  cyan (the text is all mono already). Boxes are square with 2 dp
  borders; the input box, cards waiting for an answer and artifact
  cards are cyan with the pink shadow (artifacts with a pink square of
  the name's first letters, as in the mock-up). The header's ring is a
  square meter with the percentage under it.
- `ChatSheet`: square sheet with a cyan top line, block icons
  (camera, image, file, bolt, chip, refresh, check: new `PixelIcon`s),
  square usage bars. `res/drawable/chat_*.xml` deleted.
- `ChatHistoryView` rewritten like the chat home's Continue rows,
  titled "Chats", New chat cyan with the shadow; it no longer takes
  colours.
- `Pixel.scrollbar()`: a square thumb in the line colour (API 29+),
  also on the drawer and chat home (PR 1's grey scrollbar nit).
Left: CI's compile and screenshots, then the phone ("Next" 4).

### 2026-11-11 (186): Chat home and drawer, pixel style (PR 1 written, not compiled)

The maintainer chose the pixel style with Pixelify Sans and asked for
the work to go to subagents. The lead wrote contracts (core's model,
the `Pixel` kit, the views' API, the wiring) and four subagents built
them in parallel on one branch, each in its own files:
- core: `ChatHome.kt` (`chatHome()`: Continue rows, at most 3, working
  or waiting; the drawer's chats, at most 5, live first; the Linux
  items from Home's main list; the footer "claude code · auto · ~/"),
  test-first, 9 tests.
- `scripts/fetch-font.sh` also fetches Pixelify Sans (google/fonts at
  `8b0a1d0`, a variable font, sha256 pinned) as `PixelifySans.ttf`;
  credited in `COMPONENTS` with `OFL-1.1-pixelify-sans.txt`.
  `PixelStyle.kt`: the palette, both fonts (falling back when the
  assets are missing), square boxes with the pink hard shadow, block
  icons on a 20-unit grid.
- `ChatHomeView.kt`, `DrawerView.kt`: no slide animation (the
  screenshots would catch it half way).
- MainActivity: touch view starts on the chat home (`ChatPlace.HOME`),
  everything backs out to it, HomeView's `leave` returns there from
  its first level; a Send from the home waits for the new chat's
  sign-in check (`firstPrompt`).
- CI fetches the fonts before the tests now, so the screenshots use
  them (both workflows).
Left: CI's compile, the screenshots and the phone round ("Next" 4).

### 2026-11-11 (185): Touch mode design study; chat first with a drawer chosen

A design study of touch mode (no code), as phone mock-ups on a
design canvas in the maintainer's claude.ai account (private). The
chosen screens are in the repo as pictures with a spec,
`docs/TOUCH-DESIGN.md`. Six layouts:
chat first with a drawer, chat first with a launcher sheet beside the
prompt box, chat as the first tab; and launcher first as a home grid
with a dock, a dashboard, or a command bar. A second version of each
in a style taken from the logo, and the launcher in three themes. The
maintainer chose **chat first with a drawer** (roadmap, Touch mode;
"Next" 4). Also discussed, not decided (in the roadmap's "Later"
line): a launcher mode (a second activity with the Home category, off
by default, asking for the home role; must never wait for Debian),
themes as folders with a `launcher.conf`, and a second phone as a
screen (pairing by QR code with pinned TLS keys; web apps fetched from
the main phone at the same origin, so the bridge works; bridge calls
split between the phones). Rough sizes given: chat first 3-4 PRs,
launcher mode 2-3, launcher themes 5-6, a second phone 8-12.
Docs only.

### 2026-11-10 (184): Chat looks like Claude's app; picks kept; Auto by default (passed on the phone)

Asked by the maintainer after testing entry 183 (with screenshots of
Claude's app): the mode didn't stay after leaving a chat; Auto as the
default, changeable; the layout of Claude's app, with permission and
files behind +, Terminal in ⋮, a usage ring and its sheet; a mic; one
Send/Stop button. Built by two subagents against a contract (core #80,
app #81 with #80 merged in); every part passed on the phone.
- **Picks kept** (the bug: leaving an idle chat dropped its controller,
  and `--resume` restores the model but not the mode): `ChatShared`
  keeps each session's model and mode in `filesDir/state/chat-picks`.
- **Auto:** `CHAT_MODES` starts with `auto` (`--permission-mode auto`,
  checked on 2.1.321); the `chat-mode` setting (default `auto`) is
  every new chat's mode, also set from + → Permission → Default.
- **Usage** (`ChatUsage.kt`): context from each top-level assistant
  line's usage over `result.modelUsage`'s `contextWindow`; plan limits
  from `rate_limit_event`'s `unifiedWindows` (five_hour, seven_day),
  shared by all chats.
- **Attachments** (`ChatAttachments.kt`): Camera, Photos, Files copied
  into the share folder by `Inbox`, sent as `[Attached file: PATH]`
  lines that Claude reads with Read.
- **Layout** (`ChatSheet.kt`, `ChatFiles.kt`, `ChatVoice.kt`): header
  ← / title over "Claude Code · mode" / ring / ⋮ (Model…, To tab,
  Terminal); a pill with +, the box, the mic (SpeechRecognizer, else
  the RECOGNIZE_SPEECH activity), and Send that is Stop while a turn
  runs and the box is empty.

### 2026-11-10 (183): Several chats at once, history as home, queued prompts (passed on the phone)

The maintainer's asks after the phone test (entry 182), built by two
subagents in parallel and merged here (draft PRs #78 and #79, one
branch):
- **Queued prompts** (#78): Claude runs with `--replay-user-messages`,
  so it echoes each prompt when it takes it in. One sent during a turn
  is `PromptQueued`, drawn dimmed under "Queued" after the rows, and
  becomes a user row where the echo comes. Tried on the real CLI
  (2.1.321, Haiku): queued during a tool call, it's echoed after the
  tool's result in the same turn; after `result`, as a turn of its
  own, several joined by line breaks. Stop doesn't drop them: the
  interrupt answers `still_queued: []`, then Claude runs them as the
  next turn. A process that ends first leaves them "Not sent".
  Transcripts keep mid-turn ones as `queued_command` attachments, now
  read back.
- **Parallel chats** (#79, `core/.../ChatHub.kt`, its KDoc the
  contract): a `ChatController` is one chat for life; the hub keeps
  several. New chat or opening another never stops a busy one; a chat
  not shown and idle has its process stopped (memory), resumed on its
  next prompt. Never two controllers on one session. Shared: tab holds,
  the models list, the last good sign-in check.
- **History as home:** Home → AI agents → Claude Code opens
  `ChatHistoryView` (New chat, then live chats marked Working / Waiting
  for you, then past ones). Back: chat → history → Home's list. ⋮ keeps
  Model…, Mode…, To tab. One notification per chat (6000-6015).
- Also: AGENTS.md's proot notes say why `killProot` skips pid 0 and -1
  (the fix came in with the stack; its own PR, #73, was closed).

Left: an idle chat left for Terminal (not the history) keeps its
process; a dropped idle chat loses unsent text in its box. The mode
not staying was fixed in entry 184.

### 2026-11-10 (182): The Chat stack passed on the phone, merged

The maintainer ran the test prompt and the checks by hand: every part
of #67-#76 worked (sign-in "really well"). Found, for the next PR: a
queued prompt doesn't show, and a second chat can't start while one
works. Merged bottom-up; #73 too.

### 2026-11-10 (181): Chat: model and permission mode (passed on the phone)

Chat's ⋮ has **Model…** and **Mode…** (single-choice dialogs, the
current one checked), and a line under the title says both ("Opus 5.5
· Accept edits"), the mode in the mark colour and bold when it isn't
the default. `core/.../ChatSettings.kt`: `CHAT_MODES` (Default (asks),
Plan (plan first), Accept edits: never `bypassPermissions`, which
`setMode` also refuses), `chatModelChoices()` (the `initialize`
answer's models, kept across chats; before any process, Default and
the aliases `opus`, `sonnet`, `haiku`), `chatStatusLine()` (names ids
like `claude-haiku-5-5` "Haiku 5.5"). `ChatController.setModel`/
`setMode` send `set_model`/`set_permission_mode` to a running process
and are the `--model`/`--permission-mode` flags of every launch of
that chat (nothing chosen: no flags); a new or another chat starts
from the defaults. `ClaudeSession` tells `ModeChanged`/`ModelChanged`
once a control request is answered with success, the mode from the
`initialize` answer's `current_permission_mode` and each init's
`permissionMode`, and from a `system`/`status` line's `permissionMode`
(the SDK's shape, not seen in a run). After review, the controller
only *shows* what Claude reports: the launch flag (and the hand-off
tab's) is only the user's own pick among `CHAT_MODES`, kept once
Claude takes it (`ModeRefused`/`ModelRefused` on an error answer drop
a refused pick; a pick waits for the launch with no process), dropped
when Claude reports another mode, or when a plan is approved (shown as
the default until the next init); `claudeLaunch` and
`claudeHandOffCommand` refuse any other mode, `bypassPermissions`
above all. "To tab" passes the picks (`--model`, `--permission-mode`).

From real runs: `initialize`'s `models` (`value` `default`, `opus`)
and `current_permission_mode`, init's `permissionMode`. Assumed:
`--model sonnet`/`haiku`/`default` (Claude Code's documented
aliases), a success `control_response` to the two requests, and the
status line's mode. An approved plan's switch out of plan mode: no
line says so in the spike, so the controller drops a `plan` pick and
the shown mode then (no flag on a relaunch). A live run showed
`--resume` restores the session's model (init said `claude-haiku-5-5`
for a haiku chat) but not its mode, so with no pick Model… ticks the
reported model (`chatModelMenu()`: by value or alias, else an extra
entry), and a resumed chat shows its transcript's last
`message.model` (`readClaudeTranscript()`) until init, else no model
rather than "Default model"; still only a pick is ever `--model`.

Phone checks: before the first prompt, Model… lists Default, Opus,
Sonnet, Haiku and Mode… three modes; pick Sonnet and Plan, send: the
line says "Sonnet · Plan" and Claude plans; after the first turn
Model… lists what Claude reported; switching mode mid-chat changes
the line and Claude's behaviour next turn; Restart keeps both; an
approved plan: what the line says during and after that turn.

### 2026-11-10 (180): Chat: wakelock during a turn, and notifications (passed on the phone)

**Wakelock:** `wakelock = auto` now also holds while a chat turn runs
and no card waits (`ChatController.working`; `Wakelock.held(…,
chatRunning)`). The controller tells the host only when it changes
(`ChatHost.workingChanged`), so the service doesn't read settings.conf
on every chunk; the notification's "wakelock held" follows, and
`refreshNotification()` now also runs with no tabs while a chat is
live. Not while a card waits: Claude only waits on stdin then, and the
phone wakes when the user answers (the maintainer may prefer holding
then too: one condition in `working`).

**Notifications** (`core/.../ChatNotices.kt`, `chatNotice()`): unless
Chat is on screen (the activity visible and Chat shown), a turn that
took `agent-notify-after` or more ends ("Your turn: " and the first
line of the reply, Markdown marks dropped; "The turn failed: …" for an
error; none for Stop), and a card waits ("Wants to run Bash", "Asks:
…", "Has a plan for you to approve"), at once. Title "Claude Code",
as the hooks'. `agent-notify off` silences them, and so does Claude's
own switch: they follow the hooks being in `~/.claude/settings.json`
(`claudeNotifyHooks`, as `mynx agent list` decides "notifications
on"), since that file is the only record of `mynx agent notify claude
off` (decided: one switch per agent, as everywhere; a Claude installed
by hand without hooks doesn't notify). The service posts one
notification (`CHAT_NOTICE_ID`, the programs' channel, shared helper
`noticeChannel()`), replaced by the next; `ChatController.opened()`
clears it; tapping it sends `ACTION_CHAT`, and `MainActivity` shows
Chat (touch view only). No commits yet.

Phone checks: with the screen off, a long turn finishes (wakelock
held shows, then goes); a 30 s+ turn with the app in the background
notifies with the reply's first line, and a short one doesn't; a
permission card notifies at once; nothing while Chat is on screen, but
from Home it does; tapping opens Chat and clears it; `mynx set
agent-notify off` and `mynx agent notify claude off` each silence it.

### 2026-11-10 (179): Screenshots of touch mode in CI

Robolectric tests that draw touch mode's screens with sample data, so
an agent can see them without a phone: `app/src/test/.../`
`Screenshots.kt` (an activity holds the view in a `FixedFrame` at an
exact pixel size, the main looper is idled, then `view.draw()` into a
bitmap, saved as PNG), `HomeScreenshotTest` (main list, Services with
2 services, a service's page, the main list at 360 dp) and
`ChatScreenshotTest` (a turn with a user prompt, a Markdown reply with
heading, list, code, table and link, a running tool, a Bash permission,
a question, a web app card, the box; the same 7000 px tall to see every
row; a short chat at 360 dp; landscape 2400x1080, and 2400x450 as with
the keyboard up). 1080x2400 at 420 dpi (2.625, 412 dp), neon's colours,
the default font. Chat is fed `ChatState`s through `show()` with no
controller; Home a real `HomeMenus` over an empty temp rootfs. No
production code changed.

Dependencies: `org.robolectric:robolectric:4.14.1` and
`junit:junit:4.13.2` (`testImplementation` only), `@Config(sdk = [34])`
and `@GraphicsMode(NATIVE)`; `isIncludeAndroidResources`, and the
tests get the folder as the `mynx.screenshots` system property.
`build.yml` uploads `app/build/screenshots/` as `screenshots` after the
tests (`if: always()`, warn if empty); `release.yml` doesn't. `app/`
can't build here, so none of this has run yet.

If CI fails: Robolectric downloads `android-all-instrumented` for SDK
34 on the first run (network); a newer Robolectric (4.16 has SDK 36)
if 4.14.1 trips over AGP 9; `Config.application` is a plain
`Application` so `TerminalApp` isn't used; blank PNGs mean native
graphics didn't draw (look for a warning in the test report).

### 2026-11-10 (177): Chat: artifact cards (passed on the phone)

When Claude Code makes or changes an app, the chat shows a card
(`ChatItem.Artifact`, `core/.../ChatArtifacts.kt`). Hints: a turn's
completed Write/Edit/NotebookEdit paths (relative to the chat's
folder, `~/`), and Bash paths in `~/apps`, `~/terminal-apps` or
ending `.apk`, which count only if changed since the turn started (2 s
slack), so `cat ~/apps/x/index.html` gives no card. Checked on disk off
the main thread after such a tool completes and at the turn's end
(then all written paths, so a file written through a link in `~/apps`
finds its app). Web apps by `WebApps` (only `~/apps`), terminal apps
as the menu (executable file, `# label:`; `terminalAppLabel` and
`isExecutable` moved out of `HomeMenus`), APKs anywhere. One card per
app per turn, changed in place; a check that ends after the next
prompt is dropped. History adds each turn's cards at its end (written
files only: no turn times). Buttons: web app Open (as Home) and Add
to home screen (`menu --act shortcut:`), terminal app Run (`menu
--act app:`), APK Install (debug builds only, as `mynx install-apk`;
release builds say to Share it), all Share (the web app's
`index.html`, else `app.conf`). `app/` isn't compiled yet.

Review fixes: New now waits for the old process's exit like Chats and
To tab (picking the same chat again could run it twice), and a stop's
exit that leaves nothing running calls `ended()` (the app may go);
Chats, New and To tab moved into a ⋮ menu (To tab greyed, tapping
says why); a past chat asks first while a turn runs or a card waits
(`ChatState.busy`); a hand-off tab's done file is seen by a
FileObserver, and a closed tab whose shell never started lets go;
`killProot` does nothing for pid 0 or -1 (closing a never-started tab
killed by TracerPid 0: every untraced process of the app).

### 2026-11-10 (176): Chat: history, past chats and hand-off to a tab (passed on the phone)

`--resume` replays no history, so `core/.../ClaudeHistory.kt` reads a
transcript into the chat's rows (prompts, agent text, tool cards done
or failed with output; skips meta, sidechain, compaction, thinking,
Claude's own user lines; last 300 rows after one notice). Header:
**Chats** lists `/root`'s past sessions (title = first prompt, from
each file's first 16 KB; the newest 50), tapping one stops the chat's
process, shows its history and resumes it with the next prompt; **To
tab** stops the process, waits for its exit, opens a tab running
`mynx agent start claude --resume ID` and hides Chat. While that tab
may run it (until its claude exits and writes a done file, or the tab
ends), a prompt in Chat asks "still open in a tab": Take back (closes
the tab, then reloads the transcript) / Cancel. Found: Claude names a
project folder by turning every non-alphanumeric into `-` (not only
`/`), now `claudeTranscriptDir`'s rule. Restart already kept the
rows; a test says so. Not tracked: a session the user resumed in a tab
by hand. `app/` isn't compiled yet.

### 2026-11-10 (175): Chat: Markdown, and Always allow says what it grants (passed on the phone)

**Always allow** sent the suggested mode too, so tapping it on a
`mkdir` would have switched the session to accepting edits: now only
`addRules`, for the session, labelled with each rule (`Always allow
this session: Bash(mkdir *)`); no rule, no Always. **Markdown:**
`core/.../Markdown.kt` (blocks, GitHub tables, inline spans; tested at
every prefix of a sample, and incremental against whole parses);
`ChatView` draws agent messages from `mdRows()`, one view per row,
reused by index; Copy on code blocks; tables and code scroll sideways;
links open in the browser, http(s) only. Decided: inline markers show
as typed until closed, so a stray `**` is right in the finished text
(a marker can show for a moment while streaming). Redraws are at most
about 10 a second, the first at once. `app/` isn't compiled yet.
Plans stay plain text, as do user messages and tool output.

### 2026-11-10 (174): Chat screen for Claude Code (passed on the phone)

The Android side on 173's core. `AgentProcess` (a proot over pipes),
`ChatController` in core (auth check, one chat, `--session-id` then
`--resume`, rows on the main thread), `ChatView` over the terminal as
Home is, entry through a new `HomeAction.Chat`. Stopping is
`stopAgentProot`: TERM the agent (the tracee whose parent is proot),
5 s later QUIT through proot, then the force. Sign-in/Install open a
tab and Back returns to the chat to check again. It runs in a tab's
environment through a login shell that execs the agent. `app/`
compiles only in CI; it hasn't been compiled yet. No wakelock for a
running chat. A review's 12 fixes followed, still unbuilt: permission
cards show the real input (`review`, capped, "Show all"), selection
gives the focus back to the box, the input keeps its height
(`chatFit`), picks and expansion survive redraws, a running chat keeps
the app up, one binary lookup, only an init-backed turn is resumable.

### 2026-11-10 (173): Chat's protocol layer in core (passed on the phone)

The plain-Kotlin layer the Chat screen will sit on: `AgentSession` and
`AgentTransport` with ACP's vocabulary, `ClaudeSession` over Claude's
stream-json, `claudeLaunch` and `parseClaudeAuthStatus`, and the
`Conversation` reducer. Tests replay real scrubbed lines of the spike's
runs (`core/src/test/resources/chat/`); the real `claude` wasn't run.
No Android yet. A chat keeps the user's Claude settings (leaving them
out to silence mynx's hooks would drop their permission rules and
`CLAUDE.md`); `MYNX_CHAT=1` makes `mynx hook` stay quiet instead. The
spike's policy question about subscription sign-in is still open.

### 2026-11-09 (172): Touch mode's native Services list (passed on the phone)

Home's Services is now a list of its own, not a terminal tab: each
service with its state, a page per service (Open, Start or Stop,
Restart, Log, Autostart), updating live. Core: `HomeMenus`' snapshot,
`ServiceManager.homeServices()` and `act()`, with tests; "(no services
yet)" opens the how-to, as the terminal's does. In touch view the
strip's `● N` opens this list (Back goes to the main one); terminal
view and the notification's button are as they were. `HomeView`,
`MainActivity` and `TerminalService` only compile in CI.

### 2026-11-09 (171): Touch mode's Home (passed on the phone)

The maintainer's phone test passed every step. It found one bug, not
Home's: Back in an app with screens of its own (Pixel Hero) closed the
screen, since a canvas game has no page history. `WebAppActivity` now
asks `window.androidBack` first, the hook Pixel Hero's APK already
calls, so the game needs no change. Android-side only, no unit test;
its phone checks passed too (run 42694512941). Pixel Hero's menu taps
now count on finger-up, so a back swipe no longer taps what's under it
(in `~/pixel-hero`, not this repo).

The `view` setting (`terminal`, default, or `touch`) and Home:
`HomeMenus` in core builds the launcher menu's lists from the same
files, `HomeView` draws them over the terminal. `menu --act ACTION`
is new, so tabs Home opens run the menu's own code. Core and menu
tests pass; `HomeView` and `MainActivity` only compile in CI.
Agent's choices, for the maintainer to change: Exit isn't listed,
Services is still the terminal's list, a finished program's tab stays
at a shell, lists use the phone's font.

### 2026-11-08 (169): Web apps part 2 passed on the phone

All 22 phone tests passed in mynx dev (test pages, not in the repo:
a static `bridge` app and served `served8`/`noserv8`). BLE and USB
serial used the C3, with a new MicroPython `main.py` (`mynx-ble`:
battery level, Nordic UART echo and counter, USB REPL left free).
Not tried: test 13's DTR reset on the Nano and a Bluetooth port
(`rfcomm0`), so `set()` and opening `rfcomm0` from a page are
unproven on the phone. The plan said `mynx usb monitor` "says busy",
but it opened and waited silently; it now prints `ttyACM0 is busy:
another program has it; waiting`, then `ttyACM0 is free` (044f80f,
from `serial-port`'s `.NAME.waiting` mark).
Shortcuts only appear on the home screen, not the app drawer:
Android's pinned shortcuts can't add a launcher entry. Noise left as
it is: another site's `favicon.ico` 404 is logged, on purpose
(`WebAppsTest`). Build CI 42524100077.

### 2026-11-08 (168): Web apps part 2 built by subagents

Part 0 (the contract: `Bridge.kt`, `AppPermissions.kt`, the
`ReplySink` seam, `mynx.js`'s JSDoc), then A (core), B (the app:
the channel, `FrontActivity`, the prompt, shortcuts, `fullscreen`
yes/edge, `screen-awake`), C (`mynx.js` tested under node against the
CLI's argument lines, `mynx app permissions`/`shortcut`, the menu's
"Add to home screen…", the `gadgets` example, the guide) and D (serial
from JavaScript: `Rfc2217Client`, `bridgeSerial`) in worktrees,
merged into `webapps-2`. The contract changed once during the build:
the two ports per page load became one per `mynx.invalid/hello`
(dropped calls during loading; late scripts got none). D also made
the bridge's log lines match the KDoc. Decisions where the contract
was open are in its KDoc. AGENTS.md has the Architecture entry. Known
unrelated test failures here: `test_undo_list` (time zone), and the
serial-port busy test once (flaky; passes alone). Not yet compiled:
`app/` (CI).

### 2026-11-08 (167): Web apps part 2 planned

The maintainer decided part 2's scope: the bridge offers everything
mynx has for the phone (BLE, serial ports and services too; not
config, raw USB or UF2), to static and served apps on their own
origin only; private features are asked once per app, the answers in
a plain-text file with `mynx app permissions`; home screen shortcuts;
`app.conf` keys for full screen and screen awake. The agent's design
(a `mynx.js` with promises and async-iterator streams, a
`WebMessageChannel` posted to the app's origin, a `ReplySink` seam so
bridge calls reach the same core handlers in-process,
`app-permissions.conf`, serial as an RFC 2217 client of the port's
own server) and the parts for subagents are "Next", 0. Roadmap
updated. No code.
Then the maintainer answered the plan's questions: services only the
app's own (no prompt), quick photos first, base64 up to 16 MB, an
"Add to home screen…" item in the menu's Apps, `fullscreen = yes`
(cutout kept clear) and `= edge` (covering it), no "only this time", and served apps get the bridge only
while their own service runs.

### 2026-11-08 (166): No top bar on an app's screen

Maintainer's decision: `WebAppActivity` loses its bar (name, back,
reload, ⋮ with Open in browser and Close); the page fills the screen
like an installed PWA. Recents shows the name, the back key goes back
then closes, static apps reload by themselves and `mynx app reload`
reloads any, swiping from recents closes it (and stops a service the
open started). No setting brings it back. The root is now a
`FrameLayout` holding only the WebView, padded for the system bars,
cutout and keyboard as before; the window keeps the theme's
background. Guide, roadmap and AGENTS.md updated. **Passed on the
phone** (PR #62's build, run 42473850694, in mynx dev): `probe` and
`pixel-hero` with no bar, back closing them; the keyboard over a
text field wasn't tried (no app has one). Merged (PR #62).

### 2026-11-08 (165): No favicon 404 in an app's log

The WebView asks for `/favicon.ico` itself, so every open of an app
with no icon logged `error /favicon.ico HTTP 404`. Core's new
`WebAppLog.httpError()` writes HTTP errors and skips a 404 for exactly
`ORIGIN/favicon.ico` (another path, a query, another status or origin
still log); `WebAppActivity.onReceivedHttpError` calls it. Also
from this session: CI caches the terminal font (PR #60), after
GitHub's release downloads gave 504s and failed builds. **Passed on
the phone** (PR #61's build, run 42471508940, in mynx dev): opening
`probe`, which has no favicon, logged only `probe loaded`.

### 2026-11-08 (164): Web apps part 1 passed on the phone

All 13 phone tests passed on PR #59's build (run 42462436006) in
mynx dev: the menu's Apps and Terminal apps, own screens and recents
entries, storage per app, confinement (404 for `..`, links out of the
folder, absolute links), auto-reload, the log, Pixel Hero as an app
(music in time, scores kept), a served app's service started and
stopped only when the open started it, the 15 s port timeout, the
background refusal, `mynx check`, old Debians' `~/apps` executables
and `menu.conf`, and swiping from recents (Android finishes the
activity: the service stopped, 143). **A test bug:** `WebAppsTest`
linked to real paths (`/root/pixel-hero/assets`) and cleaned up with
`deleteRecursively()`, which follows links: every core test run on
the phone emptied `~/pixel-hero/assets`. Restored from its APK;
fixed with `deleteTree()` (0816a72), lesson in AGENTS.md. Files from
the release mynx's Download can't be read by mynx dev (another app):
served the test setup over 127.0.0.1 instead. Follow-ups in "Next".

### 2026-11-08 (163): Web apps part 1 built by subagents

Part 0 (the contract, `WebApps.kt`'s KDoc, fakes, fixtures in
`tests/fixtures/apps/`), then A (core), B (the app's screen, the
service's `WebAppHost`) and C (`mynx app`, the terminal apps' move to
`tools/terminal-apps/`, the menu's Apps and Terminal apps, the
`sketch` example, the guide) in worktrees, merged into `webapps`.
Decisions where the contract was open are in its KDoc (A: a started
service is remembered only once its screen opened, a cancelled open
stops what it started, autostart services are never stopped; B: Open
in browser only for served apps, links to other sites open in the
view). Integration: the menu ignored a served app's `<title>` while
core used it; the menu now follows core. AGENTS.md has the
Architecture entry. Not yet compiled: `app/` (CI).

### 2026-11-08 (162): Web apps part 1 planned for subagents

The maintainer picked web apps and decided with the agent how they
work: an own screen per app (not a tab), auto-reload on file
changes, and apps served by a background service in part 1, the
service started on open and stopped on close only if opening started
it (the wakelock). A rhythm game like `~/pixel-hero` needs no
service: it's phone test 7. Then the maintainer named them: web
apps are **apps** (`~/apps`, the menu's Apps, `mynx app …`) and
today's apps **terminal apps** (`~/terminal-apps`, their own menu
item); old `~/apps` programs keep working, nothing is moved. The agent's design (origin
`https://NAME.mynx.invalid/` from our own intercept, `app.conf`
plain text, the log in `/tmp/.mynx/apps/`, `mynx app …`) and the
parts for subagents are "Next", 0. The roadmap's "Web apps" says the
same. No code.

### 2026-11-08 (161): Services count in the launcher menu

At the maintainer's request, the main menu's Services item shows how
many services run, as the strip's `● N` (`Services ● 2`; a user's
label is shortened to 20 columns at a word). Agent's design: the app
writes `serviceManager.active` to `/tmp/.mynx/services-active` (core's
`SERVICES_ACTIVE`, `writeServicesActive()`) on every services change,
at start (clearing a count the last run left) and on exit (deleted at
0); the menu reads it (`count_services`). Asking `mynx service list
--json` measured 0.5 s on the phone, paid on every main-menu redraw.
Tests: `ServiceFilesTest`, three in `menu.bats`. The app side only
compiles in CI. Also brought the log up to date: v0.4.0 in the
status, Play's item gone from "Smaller things" (Play left the
roadmap, entry 160). CI's runner moved to Ubuntu 26.04, whose
coreutils is one multicall binary, so the sound tests' copy of
`sleep` named `pulseaudio` refused to run; they now fake Pulse with
a script of that name (the scripts only check its comm). On build
run 42423781994 the maintainer passed all 4 phone tests (no count,
`● 1` as the strip, `● 2` and back, no stale count after Exit);
merged as PR #58.

### 2026-11-08 (160): Roadmap trimmed

At the maintainer's request, "Profiles", "Tab strip", "Android
integration" (per-profile permissions) and "Google Play" left
`docs/ROADMAP.md`, and the parked Profiles plan left "Next" (it's in
`git show 07287c5:docs/WORKLOG.md`). No code.

### 2026-11-07 (159): Touch mode and web apps planned

The maintainer asked about a Claude-app-style view of mynx and agents'
apps opening inside it; at their request, both went into
`docs/ROADMAP.md` ("Touch mode", "Web apps"). Name "touch mode"
(agent's suggestion: "app" already means `~/apps`, the APKs and mynx
itself), setting `view = terminal | touch`. Not yet decided by the
maintainer: whether to start, and in which order; the roadmap's
order is the agent's proposal. Also talked about, not planned: a
desktop mode (an X server, VNC first, a tiling window manager) and
Chromium in Debian (`--no-sandbox`); untried on the phone. No code.

### 2026-11-01 (158): Docs after PR #54, release v0.4.0

PR #54 (BLE UART ports) was merged as `5fc1d43`; its finished plan
left "Next" (`git show 3539809:docs/WORKLOG.md` has it). The
maintainer asked for this docs PR and then the `v0.4.0` tag.

### 2026-10-27 (157): A re-bind checks the device again

The maintainer asked for it in branch `nus` (entry 156 found it).
`ble-bind` on an address already bound as `nusN` now connects like a
first bind, with the line's ` random` (no scan after a restart),
answers the port with its real `kind`, and leaves the file alone;
with no UART it's refused, "… (it's bound to nus3: mynx ble unbind
nus3)". Conflicts (another `nusN` asked, an `rfcomm` binding) are
still refused before connecting. Core tests in `BleBindTest`. On
build 250: re-binding `nus0` said "Nordic UART" with the file
unchanged, `nus1` was refused ("already bound to nus0"), and with the
C3 running a script with no UART it was refused naming `nus0`, the
line kept. Killing `mpremote … run` left the C3 in "waiting for
download"; a reset with DTR low (pyserial, RTS pulse) booted it.

### 2026-10-27 (156): BLE UART ports on the phone (build 249)

The 10 phone tests with the C3, all passed. 1: `mynx ble bind
mynx-ble` made `nus0` (Nordic UART), `mynx bt` listed it, `mynx
check` clean; `mynx-plain` (a script with only the battery service,
run from RAM) was refused, "has no Nordic UART or HM-10 service",
rc 2. 2: the maintainer's `mynx usb monitor nus0` echoed typed lines.
3-4: pyserial on the path and the URL, picocom on the path; 4 KB of
base64 came back identical on both, 32 KB too (more than the C3's
2 KB buffer), at 15-32 KB/s. 5: "not linked" after each client
closed. 6: unplugged with the URL open, the echoes stopped and about
6 s later (the link's supervision timeout) the client got "connection
failed", `mynx bt` saying "the link dropped: the device went away or
closed the link"; with the path open, it linked again by itself
after the C3 came back (46 s gap) and the echo went on. 7: `hm10.py`
run from RAM, `mynx ble bind mynx-hm10` said HM-10, 4 KB and 32 KB
identical. 8: `ble unbind nus0` removed the line and port, `mynx
undo` brought both back. 9: `android-bluetooth off` and Bluetooth off
each took the port away (path gone, URL refused), `mynx bt` saying
why; both came back on. 10: after the app was closed and reopened,
`nus0` was served and echoed. Found: binding an address already bound
answers without connecting, so `mynx ble bind mynx-plain` "succeeded"
while `nus0` already held the C3's address (the same board under
another script). The wakelock is back to `auto`.

### 2026-10-27 (155): BLE UART ports built by subagents (branch `nus`)

Four subagents against the contract (4b5ea76). A, core (6 commits):
`bleUart()`, `BleUartLine` (fakes; 45 s connect and 20 s op limits
past the host's; a drop backs off too; a client leaving mid-link
records nothing), `nusN [random]` lines, `ble-bind`/`ble-unbind`
(`bleRequests` takes the `BluetoothHost`; a device bound as the
other kind is refused by both binds; `ble-unbind` works with
Bluetooth off), `bt-bind` refusing `nusN`, `writeSerialNames(…,
bluetooth =)` and `SerialUevents` skipping nus names. B, app
(committed by the main agent, `rfcomm =` renamed `bluetooth =`):
`BtBridge` serves nus ports through the `BleCentral`; `BleCentral`
keeps a device's notifications on while another link listens. C,
CLI and guide (2 commits): `mynx ble bind`/`unbind`, `mynx bt`
marks nus ports "(BLE UART)", the monitor's reset keys, the guide's
"Serial port" bullet; LE-only paired devices now say "Bluetooth LE
only (mynx ble)". D, `~/sketches/ble-peripheral`: `echo.py` (queued,
MTU-sized echo; no more `echo: ` prefix), `main.py`, `hm10.py`
(`mynx-hm10`); not flashed (the C3 was unplugged). Suites: core,
bats 178, Python 500 (one flaky vibrate test, see Status). README,
roadmap, AGENTS.md updated.

### 2026-10-26 (154): BLE UART ports planned (branch `nus`)

The maintainer asked for Nordic UART planned for subagents, to be
built overnight, and answered four questions (`mynx ble bind`;
Nordic UART and HM-10; push and a draft PR allowed; wakelock on).
Plan in "Next", 0; the contract is commit 4b5ea76 (`BleUart.kt`,
`BtBinding.random`). Earlier, PR #53 (BLE) was merged after the
drop-reason fix passed on build 247.

### 2026-10-26 (153): BLE on the phone (build 246)

The phone tests with the C3 as `mynx-ble`, run from inside mynx dev:
scan (all four services, no prompt: "Nearby devices" was allowed for
Bluetooth serial), services (known names, MTU 512), read (u8, text,
hex), write (text, hex, `--no-response`), notify (the counter until
Ctrl+C), a held link (`connect`: reads 0.41 s against 1.3-1.7 s), the
6th scan refused ("try again in 23 s"), the C3 unplugged mid-notify
(ends, rc 2), Bluetooth off and `android-bluetooth off` refused with
the reason, and with the screen off `--name mynx-ble` found it while
an unfiltered scan was refused. All passed. Not tried: refusing the
permission (revoking it kills the app). The C3 stops advertising
while connected, so scans don't see it while a link is held. The
unplug came back as status 0, not 8, so the reason said only
"disconnected": now "the device went away or closed the link". The
plan left "Next" (`git show 49b137b:docs/WORKLOG.md`); what's left
of BLE is "Next", 0.

### 2026-10-26 (152): BLE built by subagents (branch `ble`)

Part 0, the contract (`Ble.kt`: `BleHost`, `BleLink`, the requests'
JSON in KDoc, fakes in `BleTest.kt`), then four subagents at once in
worktrees: A core (`BleRequests.kt`, `BleValues.kt`, 6 commits, 29
tests), B app (`BleCentral.kt`, manifest, wiring; its commit waited
for A's Gradle, since the hook runs core's tests), C `mynx ble` and
the guide (17 tests), D the C3's MicroPython peripheral (flashed;
MicroPython on the ESP32 gets no subscribe event, so phone test 5
changed). Each worktree came up on `main`, not `ble`: they reset to
the contract themselves. Merged into `ble`; core, Python (493) and
bats (177) green. Decisions A made where the contract was open are
in `BleRequests.kt`'s KDoc (refusal order, unfiltered background
scans refused, names from every scan since start). Docs: README,
roadmap, AGENTS.md, the guide's examples renamed to `mynx-ble`.

### 2026-10-26 (151): BLE planned for subagents

The maintainer asked for a BLE plan that subagents can build ("plan
ble to work with subagents"). Read Bluetooth serial's plan and code
(`Bluetooth.kt`'s `BluetoothHost`, `BtBridge`) and wrote "Next", 0:
`mynx ble scan/services/read/write/notify/connect`, logic in core's
`Ble.kt` behind a `BleHost`, a contract commit first, then four
subagents in worktrees (core, app, CLI and guide, a MicroPython test
peripheral for the C3), integration and phone tests by the main
agent. The maintainer answered its three questions (per-command
connections plus `connect`, Nordic UART in a second PR, names
allowed). Nothing built yet.

### 2026-10-26 (150): helper back-off; PR #52 merged

The maintainer asked to fix phone test 6's log noise before the
merge: `serial-port` now waits 1 s, doubling to 30 s, when the app
closes a connection before answering its setup (a Bluetooth link that
failed); `Unescaper.answered` tells, since the server answers only
once the line is up (tests in `test_serial_port.py`). Docs for the
merge: status, the plan out of "Next", roadmap. Merged as PR #52.

### 2026-10-26 (149): Bluetooth serial on the phone (build 241)

PR #52's build 241 (run 40356576455) in mynx dev, with the ESP32-CAM
running the `mynx-spp` sketch, paired by the maintainer. Run by the
agent from mynx dev:
- **1** `mynx bt` lists `mynx-spp` (the permission was granted). Pass.
- **2** `mynx bt bind mynx-spp` → `rfcomm0`, port 2317, the config
  line, `/dev/rfcomm0`, `/sys/class/tty/rfcomm0` (`216:0`). Pass.
- **3** pyserial on `/dev/serial/rfcomm0`: opened in 0.1 s, ticks,
  `echo: hello from mynx`, unlinked after close. Pass.
- **4** pyserial on `rfc2217://localhost:2317`: opened in 1.3 s
  (under pyserial's 3 s negotiation limit, so the worry in "Next"
  didn't show), same data; `list_ports` shows `/dev/rfcomm0`. Pass.
- **5** `mynx usb monitor rfcomm0`: ticks; Ctrl+T r says it does
  nothing over Bluetooth. Pass. (Typed lines got no echo: the
  sketch ends lines on LF, the monitor sends CR; Ctrl+T n.)
- **9** `ttyUSB0` and `rfcomm0` at once: the board's USB log shows
  `BT connected`, `BT rx: both at once`, `BT disconnected`. Pass.
- **7, 8 (in part)** `android-bluetooth off` refuses and unserves the
  port, `on` brings it back; `unbind` removes port, names and line;
  `mynx undo` restores them. Pass.

With the maintainer:
- **6** picocom on `rfcomm0`, board unplugged: `mynx bt` shows "the
  link dropped"; plugged back in, `linked, in use` again on its own.
  Pass. While it was off, `serial-port` reconnected every second and
  logged "the app closed the port" each time: the app refuses at once
  during its back-off (no radio), so only log noise.
- **7** Exit and reopen mynx dev: `rfcomm0` bound and served again.
  Pass.
- **8** Bluetooth off: "Bluetooth is off: turn it on in Android.",
  `rfcomm0` listed with no URL and no `/dev/serial` link. Pass.
  Refusing the permission wasn't tried (it was granted already).
Also the CI fix: the preload library is now built in Debian's image
(the runner's Ubuntu mirror hung three builds); the build passed.

### 2026-10-26 (148): Bluetooth serial built (branch `bt-serial`)

The maintainer asked for the work to be split among subagents; it
was, in worktrees merged back into `bt-serial`:
- **Core** (agent): `Bluetooth.kt` (`bluetooth.conf` parsed, checked
  by `mynx check`, edited by bind/unbind; `bt-list`, `bt-bind`,
  `bt-unbind`, `bt-settings` through `BluetoothHost`;
  `android-bluetooth`), `SerialLine.clientArrived()`/`clientLeft()`
  and `SerialServer.endClient()`, rfcomm names and uevents. Also a
  fix outside Bluetooth: a [Later] request's config change is now
  recorded for undo when it answers late (a bind that waited for the
  permission dialog), so `ConfigHistory` is synchronized.
- **Tools** (agent): `mynx bt`, `tools/lib/mynx/ports.py` (USB and
  served Bluetooth ports for `mynx usb url|path|monitor`), the
  monitor's resets refused over Bluetooth, the guide's "Bluetooth
  serial devices", README. Then (me) `bt-list --no-ask` there, so a
  USB command never opens the Bluetooth permission dialog.
- **App** (me): `ServedPort` out of `UsbBridge` (refactor), `BtLine`,
  `BtBridge`, the wiring in `TerminalService`, the manifest
  (`BLUETOOTH_CONNECT`; `BLUETOOTH` up to API 30). Reviewed by an
  agent reading it as the compiler would, since only CI builds it:
  no compile errors found; fixed what it found: a link left open
  when its client left just as it linked, a board that's off paged
  every second (now a back-off, 2 s doubling to 30 s), Bluetooth
  ports taking 2217 from USB at start (now from 2317), a
  `cancelDiscovery()` that always failed (scan permission; dropped),
  a socket leaked on `SecurityException`, and the shared ports
  folder emptied by `UsbBridge` (now the service).
- **Test sketch** (agent): `~/sketches/esp32-spp` (PlatformIO,
  espressif32 8.0.0, Arduino-ESP32 3.3.12, which warns BluetoothSerial
  leaves in 4.0), built in 3m19s on the phone and flashed.

Tests: core, `tests/mynx` (335), bats (176) pass. Known, not ours:
`test_undo_list` fails in a tab whose `TZ` isn't UTC (the test's
`mynx` runs without it; passes with `TZ=UTC`), and a `shell-setup.bats`
test failed once and passed again.

### 2026-10-26 (147): Bluetooth serial planned (branch `bt-serial`)

The maintainer asked for the next step and picked Bluetooth, SPP
first, tested with the ESP32-CAM (`esptool chip_id` on `ttyUSB0`:
ESP32-D0WD-V3, features WiFi and BT). The plan is "Next" section 0:
`rfcomm bind`-style ports through the existing bridge
(`SerialLine`), bindings in `bluetooth.conf`, `mynx bt`. Nothing
built yet.

### 2026-10-26 (146): ESP32-C3's built-in JTAG (branch `c3-jtag`)

The maintainer picked the C3 recipes from the loose ends. Tried in
the release mynx (v0.3.0) with the C3 running MicroPython; nothing in
mynx needed changing:
- **openocd-esp32** v0.12.0-esp32-20260831, `linux-arm64` (sha256
  checked): `-f board/esp32c3-builtin.cfg` found the probe and the
  chip (rev v0.4), `reset halt`, flash auto-detect (4 MB), halt/`reg
  pc`/resume over telnet.
- **Serial beside JTAG:** with OpenOCD attached, the REPL on
  `/dev/serial/ttyACM0` answered (`print(6*7)`), then OpenOCD halted
  and resumed the core. Opening the port with DTR and RTS low still
  resets the C3 (the open raises them first, as on Linux); lowering
  DTR before RTS left it in download mode (known, see AGENTS.md).
- **GDB** (Debian's `gdb-multiarch` 16.3): `target extended-remote
  :3333`, registers, `x/4xw`, `stepi`, detach.
- **probe-rs** 0.32.0 aarch64 (sha256 checked): `list` shows `ESP
  JTAG`, `info` detects esp32c3, `reset`, `read` from ROM and flash;
  also with the serial port open. A second debugger while one runs
  gets "interface is busy (errno 16)" (usbfs claim), as on Linux.
- Docs: the guide's raw USB recipes (C3 JTAG: OpenOCD, GDB,
  probe-rs; the C6/S3/H2 and `probe-rs run` marked not tried), the
  roadmap.

### 2026-10-26 (145): v0.3.0 released

The maintainer tagged v0.3.0 (`mynx-0.3.0.apk`): everything since
v0.2.0, PR #33 to #49. The work log's status says so, and the
release's plan left "Next". On the release app, Android asks for each
board again (a different app from mynx dev).

### 2026-10-26 (144): UF2 flashing (branch `uf2`)

- **Spike, from Debian over raw USB** (scratchpad scripts, not kept):
  the RP2040 Zero put into BOOTSEL from MicroPython's REPL
  (`machine.bootloader()` over the URL). The drive's interface had
  the kernel's `usb-storage` bound (`USBDEVFS_GETDRIVER`); the claim
  gave EBUSY, `USBDEVFS_DISCONNECT` let it go, and INQUIRY (`RPI
  RP2`), READ CAPACITY (262144 sectors) and READ(10) answered.
  Sector 0 is an MBR: FAT16 from sector 1, data from 292,
  `INFO_UF2.TXT` in cluster 3. MicroPython's UF2 written from sector
  292, 32 sectors a WRITE(10): 1302 blocks in 3.7 s, every status
  came back, and the board restarted into MicroPython.
- **Decision (maintainer):** the writer lives in Debian (`mynx`, in
  Python), not in the app as entry 140 planned: no app change, tested
  natively, and the raw USB node already gives everything.
- **Code:** `tools/lib/mynx/uf2.py` (UF2 checks, bulk-only SCSI, the
  FAT with or without an MBR, finding drives in `/sys/bus/usb`, the
  usbfs pipe) and `mynx uf2` in `cli.py`; 20 tests against a fake
  drive (`tests/mynx/test_uf2.py`), 2 in `test_mynx.py`. Docs: the
  guide (commands, Raw USB's recipes), AGENTS.md, README, ROADMAP.
- **Phone:** see "Next", 0, step 3. The whole suite passes with
  `TZ=UTC`; `test_undo_list` fails under the tab's
  `Europe/Istanbul` (it expects UTC times), on `main` too.

### 2026-10-26 (143): PR #47 merged

The maintainer merged raw USB (PR #47); this docs-only PR marks it
merged in "Current status" and "Next" and adds it to the release's
list. Next: UF2 (see "Next", 0, step 3).

### 2026-10-26 (142): raw USB rebuilt test-first (PR #47)

The maintainer said "start the rebuild" after the spike's phone
results (entry 141, below). Branch `raw-usb` from `main`, draft PR
#47; CI builds it. Commits, each test-first:
- **proot's `mynx_usb`** (203f7fb), then **only nodes beside the
  app's socket** (7dacdf2): core's tests, run in mynx dev under the
  spike's proot, failed with ENODEV, since their temp
  `…/dev/bus/usb/001/004` ended like a device node. The test now
  uses `own-dev`, so it also passes under the spike's build.
- **core's stand-in** (67080a3, `writeUsbDevices`, as the spike's).
- **An own `/sys/bus` with the phone's buses** (2dfcc1c):
  `HOST_BUSES` the phone has, placeholders and binds as the own
  `/dev`'s; the probe now takes host paths for both.
- **The app** (68bebf3): `UsbFds` seeks the fd to 0 (entry 141's
  finding) and follows Android's attach, detach and permission
  broadcasts and the app's USB refresh, instead of the spike's
  polling every second. Device filter: Raspberry Pi (all of
  `2e8a`), STM32 DFU (`0483:df11`).
- **`mynx usb`** (f460984): every device; one with no serial driver
  has `driver: null` and shows its node ("raw USB"); `mynx usb
  allow` takes any; its answer waits for the stand-in.
- **Docs:** the guide's "Raw USB devices" (DFU, OpenOCD and sharing
  with a served serial port marked "not tried yet"), AGENTS.md
  (Raw USB, two lessons), ROADMAP, README.
- **Phone, first build (run 40292999091):** every open of a node
  missed the extension and opened the placeholder file: proot gives
  binds' host paths their real spelling (`/data/data/…`, as
  `/proc/PROOT/cwd` showed), `MYNX_USB_SOCKET` says `/data/user/0/…`.
  Fixed (d101439): the extension resolves its socket's folder at
  start. Also seen: Android lets Debian stat the phone's buses
  (`/sys/bus/platform`) but not list inside them; the binds keep them
  where they were before the own `/sys/bus`.
- **Phone, second build (run 40295886564), tests 1-4 and 6 of
  "Next", 0 passed:** the board put into BOOTSEL from MicroPython's
  REPL (`machine.bootloader()` over the URL); `mynx usb allow
  2e8a:0003` asked, answered `raw USB  /dev/bus/usb/001/004`, and
  `lsusb` listed it at once; the spike's `usb-check.py` all passed,
  now with the descriptors from byte 0 (the seek fix); `picotool
  load -x` flashed MicroPython back and it returned as `ttyACM0`,
  allowed without a dialog ("always"); a program's own
  `…/dev/bus/usb/001/005` file is left alone; core's and
  `test_proot_usb.py`'s tests pass in mynx dev. Test 5 too: a
  Kingston DataTraveler stick (`0951:1666`, outside the filter) was
  "not allowed", `mynx usb allow 0951:1666` asked, and `lsusb -v`
  then opened it and read its strings.
- Hotplug left for later (see "Next", 0).
- A timing test (`test_audio_record_until_ctrl_c_says_what_it_saved`)
  failed once in the full suite on the phone and passed alone.

### 2026-10-25 (141): raw USB spike (branch `spike-raw-usb`)

Overnight, the maintainer asleep. Spike 1(a) of "Next", 0, built;
CI's harness passes. **On the phone (2026-10-26, Pixel 10, run
40239371346): it works.** An RP2040 Zero in BOOTSEL: `picotool info
-a` reads it (`RP2040`, `B2`); libusb lists it; `lsusb -v` and pyusb
open it and read its strings (control transfers); the usbfs ioctl
(`CONNECTINFO`) works on the passed fd. The fd keeps `O_CLOEXEC`, a
missing device gives ENODEV (from the app) or ENOENT (no bus
folder: proot's own lookup fails first, the extension never sees it).
- **Phone finding:** the fd came at offset 73, the end:
  `openDevice()` reads the descriptors through it, and the copy sent
  shares the offset, so a program reading the node as on Linux got 0
  bytes. The app now seeks the copy back to 0 before sending (not yet
  on the phone; libusb, lsusb and picotool read sysfs and didn't
  mind).
- **`picotool load -x`** (MicroPython v1.24.1's `RPI_PICO` UF2,
  666 KB): flashed, verified and rebooted in 6 s. The board came back
  as a new device (`001/003`, `2e8a:0005`); Android's permission
  went from no to yes 3 s later (asked again), and the serial bridge
  gave it `ttyACM0`: the REPL over `rfc2217://` answered "Raspberry
  Pi Pico with RP2040", and `lsusb` lists it.
- **Spike 1(b) answer, in part:** with the board plugged in, Android
  says "damaged USB storage": it sees the BOOTSEL drive but won't
  mount it (the RP2040's FAT). So UF2 needs the app's own writer
  (`mynx uf2 flash`) or `picotool load`, not Android's mount.
- **proot's `mynx_usb`** (`proot/mynx_usb/`, `MYNX_USB_SOCKET`):
  an open of `…/dev/bus/usb/BBB/DDD` becomes chained `socket`,
  `connect`, `write`, `recvmsg`, `close` made by the program, so the
  app's fd lands in it; the program gets the fd, or the app's errno
  (EACCES not allowed, ENODEV gone), or EIO with no app. Memory below
  the stack pointer, as mynx_serial. Only a device's open asks for the
  exit stop (`sysexit_pending`, as link2symlink): with
  `FILTER_SYSEXIT` every open in Debian would stop twice. Decisions in
  `devices.c`, tested natively (`test_proot_usb.py`).
- **The app** (`UsbFds.kt`, spike): serves fds on
  `filesDir/dev/bus/usb/.ctl` (Debian's `/dev/bus/usb/.ctl`), peer uid
  checked, a fresh `openDevice()` per request, ours closed once sent;
  polls the devices once a second and writes the stand-in (core's
  `writeUsbDevices`, tested) for those it may use. Raspberry Pi's
  vendor ID added to the device filter.
- **Found while building it:**
  - Debian's libusb is built with udev: it lists devices through
    libudev, which reads `/sys/bus/usb/devices/*/uevent` (DEVTYPE,
    DEVNAME) and the `subsystem` link. No MAJOR/MINOR in `uevent`:
    libudev's enumerator skips a device with a device number and no
    udev database entry (`match_initialized`, compat mode).
  - libudev **refuses device folders not on a real sysfs**
    (`fd_is_fs_type(SYSFS_MAGIC)`) unless
    `SYSTEMD_DEVICE_VERIFY_SYSFS=0`: `prootLaunch` sets it with the
    stand-in. Later maybe: proot answering `fstatfs` there instead.
  - libudev finds `usb` by **listing `/sys/bus`**, which Android
    refuses (the folders can be stat'ed, not listed), so Debian gets
    a `/sys/bus` of its own (`filesDir/sys-bus`, with only `usb` in
    the spike). The real version should bind the phone's bus folders
    into it (as `HOST_DEVICES` in the own `/dev`): `platform`, `cpu`,
    `iio`, `event_source`, … exist here.
  - `libusb_init` already works under mynx (the uevent stand-in),
    and `lsusb` works without udev's hwdb ("unable to initialize usb
    spec" is only about names; strings come from sysfs).
  - probe-rs lists with Rust's nusb, which reads sysfs itself: the
    device's attributes and **a folder per interface** (`1-4:1.0`,
    `bInterfaceNumber`, class, and the `interface` string it finds
    CMSIS-DAP by), so the stand-in has them (first configuration,
    first settings; names from `UsbInterface.getName()`). nusb opens
    with raw system calls (rustix), which only proot can catch.
  - Not yet: hotplug. libusb with udev keeps its device list from
    udev monitor messages (libudev's format, sender uid 0, a netlink
    sender address), so a device that comes back in the same process
    isn't seen; a new process lists afresh. Fine for picotool,
    dfu-util and OpenOCD runs; later for `--watch` tools.
  - Debian's OpenOCD 0.12 has no `esp_usb_jtag` (the C3's built-in
    JTAG): that needs Espressif's `openocd-esp32` (arm64 builds on
    its GitHub releases).
- **CI harness** (`tests/spike/`, `.github/workflows/spike-usb.yml`,
  `ubuntu-24.04-arm`): builds proot natively (no 32-bit loader:
  `HAS_LOADER_32BIT=`), runs it as the app does with a stand-in app
  (`fake-app.py`) and checks from inside Debian (`usb-check.py`). The
  runner's kernel has no `dummy_hcd`, so a file with an RP2040's
  descriptors stands in for the device: all passes (open through the
  extension, descriptors, `O_CLOEXEC`, errors, libusb's list, `lsusb`,
  `lsusb -v` and pyusb opening it) but the usbfs ioctls, which only
  the phone can answer.
- **Phone test** (APK: `Download/mynx-dev-40239371346.apk`, run
  40239371346 of `gh workflow run build.yml --ref spike-raw-usb`;
  installs as mynx dev). In mynx dev's Debian: `apt
  install usbutils python3-usb picotool curl`, then `curl -fsSLO
  https://raw.githubusercontent.com/est4s/mynx/spike-raw-usb/tests/spike/usb-check.py`.
  Plug the RP2040 Zero in holding BOOT (OK on "open mynx dev"), open
  a new tab: `python3 usb-check.py 2e8a:0003` (also runs `picotool
  info -a` and shows the app's log, `/tmp/.mynx/serial/usb-fds.log`),
  then `lsusb -v -d 2e8a:0003`. Note too whether Android shows the
  drive (spike 1(b)).
- Commits on `spike-raw-usb` (66160cf … this one).

### 2026-10-25 (140): UF2 and raw USB planned

- Planned the development boards milestone with the maintainer;
  the plan and decisions are in "Next", 0. Nothing built yet.

### 2026-10-25 (139): services get `~/.local/bin` (branch `service-path`)

- Found in entry 138: a service runs `bash --login -c`, whose
  `~/.bashrc` returns before it adds `~/.local/bin`, so `pio`,
  `arduino-cli` and pipx tools weren't found.
- Fix: `/etc/profile.d/mynx.sh` (`writeToolsProfile()`, rewritten at
  every app start, so existing Debians get it too) puts
  `~/.local/bin` first when the folder exists, once. Root's default
  `.bashrc` no longer adds it a second time (old ones still do:
  harmless). Guide's services table says so. Tests:
  `ToolsInstallerTest`, `bashrc.bats`, `home-docs.bats`.
- Confirmed on the phone (mynx dev, PR #46's build): a service's
  `command -v pio` gives `/root/.local/bin/pio`, and its PATH has
  `~/.local/bin` once, first.

### 2026-10-25 (138): Android's process limit on the Nord 2 (branch `process-limit-guide`)

In mynx dev build 214, adb over Wireless debugging from the app
(`adb pair localhost:…`, run by the maintainer with the dialog open;
from a screenshot the code had already expired).
- Before: `settings_enable_monitor_phantom_procs` and
  `max_phantom_processes` both unset (Android's defaults).
- The guide's Android 12L/13 command, `settings put global
  settings_enable_monitor_phantom_procs false`, failed: "Permission
  denial: writing to settings requires
  android.permission.WRITE_SECURE_SETTINGS" (OnePlus wants Developer
  options → Disable permission monitoring for that). The Android 12
  pair worked: `device_config set_sync_disabled_for_tests
  persistent`, `device_config put activity_manager
  max_phantom_processes 2147483647`.
- Then a clean `pio run` of `~/sketches/pio-c3` as a service (118 s),
  and two at once, a service's and this tab's, where both proots were
  killed before (143 s and 113 s): all passed, nothing killed. Not
  checked: that the setting survives a reboot.
- Guide: Android 12, 12L and 13 use `device_config`; the `settings`
  way is mentioned with the OnePlus refusal. Test in
  `home-docs.bats`. AGENTS.md's lesson updated.
- Found: services don't get `~/.local/bin` (fixed in entry 139).

### 2026-10-25 (137): the release moves after the development boards

- At the maintainer's request, "Next" now has the development boards
  (the process limit, UF2 and raw USB) before releasing v0.3.0. Docs
  only.

### 2026-10-25 (136): Small serial fixes (branch `serial-small`)

On mynx dev build 209, a Nano, a C3 and an Arduino Micro on a hub.
- **A busy port's silence, fixed:** a program opening `/dev/serial/X`
  while a URL client had the port got nothing, and the helper logged
  "opened" / "the app closed the port" every second. The app's server
  greets a client it takes and closes on one it refuses, so
  `serial-port` tells them apart (`refused()`), logs "busy" once and
  leaves `.X.waiting` in the ports folder; `mynx usb` shows "waiting:
  a program has the URL" (`waiting` in `--json`, `SerialPortInfo`).
  Checked by hand on the Nano with the new helper.
- **Problem 6 ("no port yet"), likely cause fixed:** `usb-allow`
  answered at once for any allowed device (with no DEVICE it said
  nothing was waiting), so `mynx usb allow` never made the bridge look
  again; and the 40 minutes ended when the app came on screen (onStart
  refreshes), so Android probably allowed it with no broadcast reaching
  the bridge. Now an allowed device with no port counts as waiting and
  is opened again; the bridge looks again every second for 30 s after
  an attach while a device isn't allowed; a driver with no port is a
  shown error. Not reproduced, so not confirmed.
- **The 4 MB `read_flash` stalls (open):** on the C3 over the URL,
  with the phone idle, it ran to ~600 KB at ~8 KB/s, then nothing for
  6 min, no error (esptool waits). After killing it, the URL refused
  clients for some seconds (the old client still "busy"). A run under
  load hung too. The library's queued read re-queues each request
  before returning and turns an empty packet into a 200 ms GET_STATUS
  that throws when unanswered; nothing there explains a stall alone.
  So a temporary trace (`PortTrace`): each port's
  `/tmp/.mynx/serial/NAME.trace` gets a line a second while anything
  moves (bytes and reads in, empty reads, biggest read, bytes and
  writes out, read errors with their text, time since the last read,
  whether a client is on, a USB write stuck), plus configure and purge.
- **Build 212, the trace:** every packet from the C3 is 1 byte
  (7-11 K reads a second, "biggest 1 B"): esptool 4.7's stub flushes
  USB-Serial-JTAG after each character (`stub_tx_one_char()`). So 256
  queued reads held 256 bytes, ~30 ms. It stalled at 72% (3.04 MB)
  right after a second with 465 empty reads (each costs the library a
  GET_STATUS of up to 200 ms on the read thread) and a 219 ms gap;
  then no line at all: nothing in or out, no write stuck, no read
  error. Lost bytes, then esptool and the stub each wait for the
  other. Now 4096 reads of one packet each (~0.5 s of 1-byte packets,
  the same 256 KB). If that's not enough: skip the library's
  GET_STATUS on empty reads. Also confirmed on 212: `mynx usb allow`
  reopened the Nano after "couldn't open it … Failed send cmd [init
  #1]" (it used to answer at once).
- Boards dropped off the hub by themselves now and then (the Micro and
  Nano 4 min after the stall): the hub's power, as in entry 126.
- **Build 213 (4096 queued reads), worse:** `read_flash` never
  connected ("Wrong boot mode"), and `chip_id` failed right after the
  stub started ("Packet content transfer stopped (received 3
  bytes)"). The trace's errors, all `Queueing USB request failed`,
  with bursts of at most 255 reads: Android stops queueing at ~256,
  and the library's `read()` queues the completed read again *after*
  copying its packet and throws when that fails, so the packet is
  lost. Now `UsbReads` in `UsbBridge` instead of the library's read
  queue: up to 1024 reads, as many as Android takes; a refused one
  waits to be queued again (counted in the trace); empty packets
  pass with no GET_STATUS; FTDI's 2 status bytes stripped there.
- **Build 214 (`UsbReads`), passed:** Android took all 1024 reads
  at open but refused 17 re-queues 10 s later, all at once while
  idle (the other boards being plugged in); none during the test.
  The 4 MB `read_flash` on the C3 over the URL: 4,194,304 bytes in
  583 s (57.6 kbit/s, esptool's MD5 check passed), 0 read errors.
  The Nano (CH340) read its sketch's output cleanly at 9600. The busy
  hint: with the URL held, `mynx usb` showed `/dev/serial/ttyUSB0
  (waiting: a program has the URL)` and the helper logged "busy".
  "No port yet" didn't show again (not confirmed). Trace removed.
- **Pro Micro on build 214, after the merge:** `touch-flash.sh` (the
  1200 baud touch, then avrdude to the bootloader): the Caterina
  bootloader (2341:0036) had a port 1.5 s after the touch (still
  allowed from "always"), avrdude wrote and verified 4238 bytes, and
  the board came back running Tick (`tick 23`, `tick 24`, … at 9600).
  The bootloader's empty packets no longer cost a GET_STATUS. No FTDI
  board has been tried with `UsbReads` (its 2 status bytes are now
  stripped in `UsbReads`).
- Commits: busy hint, no-port fix, trace, 4096 reads, own reads,
  trace removed.

### 2026-10-21 (135): agent widget on the roadmap (branch `roadmap-agent-widget`)

- At the maintainer's request: "Agent widget" in `docs/ROADMAP.md`, a
  home screen widget like Claude's (shortcuts, not a text field) whose
  text box opens a tab with the default agent and whose mic also
  listens with Android's speech recognizer. Planned only; no code.

### 2026-10-21 (134): the guide on making apps (branch `guide-making-apps`)

- At the maintainer's request, after an agent built an Android widget
  in the app's Debian (Debian's `aapt`, `javac`, Google's `d8` and
  `android.jar`, `apksigner`) and found the guide didn't cover it.
  New section "Making an app": ask whether the user wants a terminal
  app (`~/apps`) or an Android app; the toolchain, with Google's
  download URLs (checked: the same files, by size and sha256, as the
  ones that built the widget); signing, installing through the
  browser, no logcat, permissions, plain `http`, widgets' limits; an
  Android app talking to a service in Debian. The Apps bullet points
  to it; a test in `home-docs.bats`.

### 2026-10-20 (133): PR #39 merged

- At the maintainer's request, with the adb check of entry 132 still
  open (now "Next" item 1). The docs went into the PR first.

### 2026-10-20 (132): PR #39 on the phone; Android kills big builds

- **Build 206 (6dffadb), in mynx dev on the maintainer's OnePlus Nord
  2 (Android 13):** `scandir` says `f`/`g` aren't symlinks and
  `copytree` works; `ls`, `find`, `git status` as before; `ls -R /usr`
  32,073 entries in 3.7 s. A service walking `~/.platformio` five
  times (16,688 files) survived; none of its 78 listed symlinks is a
  faked hard link. **`pio run` for `esp32-c3-devkitm-1`:** every
  toolchain installed and the build succeeded (61 files, 117 s,
  `firmware.bin`).
- **First fix (6dffadb):** CI's NDK build failed on `IFTODT` (glibc
  only); glibc's definition where libc has none, and a test building
  `links.c` without it.
- **Android kills big builds:** three `pio run`s ended with proot
  killed (tracees left running, ENOSYS: "Function not implemented"),
  once two proots at once (a tab's and a service's); the third time
  only the tab's (Claude Code's) and the build finished. The app
  stayed up. Taken as Android 12+'s phantom process killer (the
  maintainer saw it on another phone and fixed it with a setting);
  the Nord 2's Android 13 has no switch in Developer options, so the
  user guide ("How it runs") gives each version's way, adb included
  (wireless debugging from the app itself). **Not yet confirmed:**
  that those adb commands stop it on this phone.
- **Seen, not fixed (not from this PR, as far as the code says):**
  `cp -a` and `tar` fail on a `.l2s.*` intermediate link ("Too many
  levels of symbolic links": link2symlink's `lstat` says regular
  file); PlatformIO's cleanup of its temporary folders says
  "Directory not empty" (the guide calls it harmless: installs finish).
- Guide: PlatformIO's ESP32 recipe, Android's process limit; tests in
  `home-docs.bats`. Roadmap: the PlatformIO item.

### 2026-10-20 (131): faked hard links as regular files in listings

- **Problem:** link2symlink's faked hard link is a symlink on the host;
  every path call dereferences it (`lstat`: regular, `readlink`:
  EINVAL), but `getdents64` comes from the kernel: `DT_LNK`. So
  `os.scandir` says symlink, `shutil.copytree(symlinks=True)` calls
  `readlink` and gets EINVAL, and PlatformIO's ESP32 toolchains fail
  to install. Reproduced in this Debian with `ln a/f a/g` and
  `copytree`.
- **Fix:** a new proot extension, `proot/mynx_links/` (AGENTS.md
  "proot's mynx_links extension"): at `getdents64`'s exit, each
  `DT_LNK` record whose symlink leads to `.l2s.*` and on to a file
  gets that file's type. Same decision as link2symlink's
  `resolve_faked_hard_link()`, so broken ones and the `.l2s.*` links
  themselves stay `DT_LNK`. Turned on by `--link2symlink` (the patch
  adds it to `handle_option_link2symlink()`). The About credit says
  so.
- **Checked here:** `links.c` against real `getdents64` buffers
  (`tests/mynx/test_proot_links.py`, 7 tests, seen failing with the
  fix taken out); the patched proot builds natively with gcc, and
  both new files with `-Wall -Wextra -Werror`. Not run: a nested
  proot can't run here.
- **Phone tests (after CI builds it), in mynx dev:**
  1. `mkdir -p /tmp/t/a && cd /tmp/t && echo hi >a/f && ln a/f a/g`,
     then `python3 -c "import os; print([(e.name, e.is_symlink())
     for e in os.scandir('a')])"`: `f` and `g` False (were True).
  2. `python3 -c "import shutil; shutil.copytree('a', 'b',
     symlinks=True)"` works; `cat b/g` says hi.
  3. `ls -la a`, `find a -type l` (only `.l2s.f0001`), `git status`
     in a repo, `cp -a`, `tar`: as before.
  4. `pipx install platformio`, a project with `board = esp32-c3-devkitm-1`,
     `pio run`: the toolchains install and it builds (the real goal;
     another obstacle may show up after this one).
  5. Nothing feels slower (`ls -R /usr | wc -l`, a big `git status`):
     every `getdents64` now stops in proot.

### 2026-10-20 (130): PR #37 merged; docs caught up

- PR #37 merged at the maintainer's request (9eafb45), before the work
  log and roadmap were updated, so this PR does that. **Maintainer's
  rule:** never merge a branch without first updating the docs it
  makes stale (work log, roadmap, README, AGENTS.md), in that PR.

### 2026-10-20 (129): Go programs: every phone test passed (build 201)

- Build 200's log showed why replugs sent nothing: `LocalSocket`
  creates its socket in `connect()`, and `soTimeout` was set before
  it ("socket not created"). Fixed in 2eaa373 (AGENTS.md's lessons).
- Build 201: `board list --watch` printed `remove /dev/ttyUSB0` and
  then `add /dev/ttyUSB0` as the Nano was unplugged and plugged back;
  `uevents.log` says "sent" for both. The log stays, beside the
  helpers' logs.

### 2026-10-20 (128): Go programs on the phone (build 199)

- **Pass:** a uevent socket is made and bound; without the preload
  proot answers TIOCM*, TIOCEXCL and tcdrain with the preload's
  values and the bytes reach the port (a fake port in a service);
  with both, PROGRAM passes whole. With the Nano and the C3:
  `arduino-cli board list` lists both (VID:PID right), `arduino-cli
  monitor` shows Tick (opening resets the Nano: DTR through proot)
  and the C3's REPL (`print(6*7)` → 42); `esptool chip_id` with and
  without the preload; pyserial resets the Nano by DTR with no
  preload.
- **Fails:** `board list --watch` gets no event on a replug. A
  message sent by hand (`connect` + `send` from Debian; `sendto` with
  a path isn't translated by proot: ENOENT) reached it, and dead
  sockets weren't deleted, so the app's sender got nowhere and said
  nothing. It now logs to `/tmp/.mynx/serial/uevents.log`.
- `serial-names` was off in mynx dev's Debian (from before it became
  the default); turned on for these tests.

### 2026-10-20 (127): Go programs, proot's mynx_serial extension (branch `go-serial`)

- Why: Go programs are static, so the preload never loads; Android
  refuses arduino-cli's discovery `socket(AF_NETLINK, SOCK_RAW,
  NETLINK_KOBJECT_UEVENT)` itself (EACCES, checked here), and
  go.bug.st/serial's open fails on `TIOCEXCL`.
- Done, test-first where it can be: `proot/mynx_serial/ports.c`
  (refused requests, the port by its pty, asking the helper, the
  socket's path; `test_proot_serial.py`, against the real
  `serial-port`), `mynx_serial.c` (the proot glue, built here with
  gcc against Termux's proot: it compiles, but can't run), the patch
  and `build-proot.sh`, the cache key (`proot/**`), core's
  `SerialUevents` and `ueventSockets()`, `MYNX_SERIAL_DIR` in
  `prootLaunch`'s env, the app's `UeventSender`, the guides and `mynx
  about` (our extension is compiled into GPL proot: its source is
  linked there).
- Decisions: the uevent stand-in is a Unix datagram socket per
  program, sent to by the app (so `read()` gets one message at a
  time, as from the kernel); only tty `add`/`remove`, no USB device
  events. `tcdrain` doesn't wait under proot. The preload stays: it
  covers `flock`, `tcflow` and a waiting `tcdrain`.
- Tried and dropped: running a natively built proot inside the app's
  proot, to test the extension here: its loader crashes (signal 11)
  right after the first execve under the outer proot's ptrace.
- Local test runs: `test_undo_list` fails on `main` too (it compares
  clock times, off by the phone's time zone). `SerialServerTest`'s
  "a client leaving drops RTS and then DTR" failed under load: a real
  race (the client was assigned after its greeting went out), fixed
  in its own commit, bb9dfb4. A few `test_mynx` ones failed once each
  under load and pass when run again.
- Installed in the maintainer's Debian for this: `gcc`, `make`,
  `gawk`, `libtalloc-dev`.

### 2026-10-18 (126): USB serial part 3, an agent's phone tests

On mynx dev build 186, a C3 and a Nano plugged in. The agent drove
the monitor on a pty (a script sending keys) and ran part C as a
throwaway service (services get the own `/dev` as new tabs do, and
the agent's tab kept Android's).
- **Passed:** monitor tests 1-4, 7, 9 (the reset is refused with
  "can't reset: Permission denied (serial-preload off?)"), Ctrl+T `h`
  `e` `n` `D` `R`, an unknown key, Ctrl+]; arduino-cli (fix-links
  "fixed 24 links", Tick compiled and uploaded); mpremote by path and
  URL; in the own `/dev`: null, urandom, tty, ptys, `<( )`, apt, git,
  sound.
- **Fixed, test-first:** (1) the port picker crashed ("prompt string
  cannot contain null characters"): the C3's USB strings end in a NUL
  (`Espressif\0`), which Android passes on; `usbString()` drops them
  (also the double space in `mynx usb`, and sysfs's `product`). (2)
  No `/dev/ttyACM0` or `/sys/class/tty` entries: `writeSerialNames()`
  wrote into `filesDir/dev` before any tab had made it, failing
  quietly (`runCatching`), and nothing changed afterwards; it makes
  the folder now. (3) proot warned "can't sanitize binding /dev/full"
  (and `fuse`), leaving empty files: Android allows `access()` on them
  but not `stat()`; the probe uses `Os.stat`, and `prepareOwnDev()`
  removes placeholders for devices the probe no longer finds.
- Turning DTR off with RTS on (Ctrl+T `D`) puts the C3 into download
  mode: its USB-Serial-JTAG's reset logic, not a bug.
- **Later, on build 187 (run 39111972962):** 6 passes (a numbered
  list of all three); part C in a throwaway service: 13 (no proot
  warnings, no `full`/`fuse`), 14's links, 15 (`list_ports`: all three
  with VID:PID, serial, product), 16 (esptool tries the Nano first,
  ~10 s, then finds the C3) and 17 pass. **18 can't pass:** arduino-cli's
  serial-discovery (Go, static) needs a `NETLINK_KOBJECT_UEVENT`
  socket even for a plain START, and Android refuses apps one
  (checked: EACCES from `socket()`); the guide no longer promises
  `board list`. The stty touch now reaches the Pro Micro; its
  bootloader failed at "dropping DTR": Caterina refuses CDC's
  line-state request, which Linux's cdc-acm shrugs off. `UsbLine`
  now does too (e34c12f, not yet on the phone).
- **Build 189 (2026-10-19):** the bootloader now gets a port (same
  name, `ttyACM0`, as the sketch it replaces), and answers avr109 by
  hand (`S` → `CATERIN`, `V` → `10`), but avrdude got "programmer is
  not responding": after avrdude's first byte, ESC, which Caterina
  ignores, nothing came back while writes still arrived (`E` made it
  leave in 0.6 s). usb-serial-for-android checks the device with
  GET_STATUS (200 ms) after an empty packet and throws when it
  doesn't answer; the read loop took that as an unplug and stopped.
  It now keeps reading while the port is open (refresh() still
  closes an unplugged one). Not yet on the phone. `~/sketches`:
  `touch-flash.sh PORT` (touch, wait for 2341:0036, avrdude),
  `probe-boot.py`, `esc-exit.py`.
- **Build 191:** test 11 passes: the stty touch, the bootloader's
  port, avrdude flashed and verified Tick (4238 bytes), and it prints
  at 9600. But the sketch's port after the flash (the board restarting
  by itself) sat at "the app is opening it" again, `mynx usb allow`
  didn't help, a replug did. The code shows no way to be allowed, in
  `open` and portless without an error, so a temporary trace
  (`bridge.log` in `/tmp/.mynx/serial`, `UsbBridge.trace()`) logs each
  broadcast, refresh, open and close. Next: flash again
  (`~/sketches/touch-flash.sh`), read the trace, fix, remove it. The
  phone in these tests is a OnePlus Nord 2 (DN2103), Android 13.
- **The maintainer's tests, build 192 (2026-10-19), all pass:** the
  `serial` bar (1), its buttons (8), the C3 unplugged and back while
  monitoring (5), `serial-names on` in a new tab (13: no proot
  warnings, no `full`/`fuse`, htop, mc, claude), a name gone and back
  on unplug in an open tab (14), one board unplugged from the hub
  removes only its name (19), `serial-names off` as before (20).
  Aside: mc needs F10 to quit, which the phone's keyboard lacks: Esc 0
  works (no mc key bar yet).
- **Build 192 (with the trace):** five flashes in a row, the sketch's
  port came back each time (bootloader detached, sketch attached
  0.3 s later, served within 40 ms): problem 6 didn't show. The trace
  caught the Nano dropping off and coming back by itself 9 s after a
  flash (the hub's power, as the Pro Micro restarts?), handled fine;
  such a drop during an attach may be problem 6. The trace was removed
  (maintainer's decision); `git show a35033d` has it, to add again if
  the stuck port comes back.
- **Pro Micro (test 11), three problems.** (4) The 1200 baud touch
  never reached it: `stty -F … 1200` opens, sets and closes between
  two of `serial-port`'s looks (10 a second), so it saw nothing; and
  the monitor's Ctrl+T `b` (baud, then DTR off) reached the board as
  DTR off, then 1200 (lines go at once, settings at the next poll).
  Fixed in the helper, test-first: a line change sends changed
  settings first, and settings changed while nobody seemed to have it
  open are replayed as an open and a close (held `QUICK_OPEN`, 0.2 s:
  with none the board didn't react; 0.1 s worked, tried with the
  fixed helper run by hand). (5) Its bootloader (2341:0036
  "Arduino Leonardo", Caterina) is never served: "couldn't open it:
  controlTransfer failed", every time, so avrdude has no port. Not
  understood; the error now names the step (`openDevice()`), so the
  next build says which. (6) Once plugged in, it sat at "no port yet;
  the app is opening it" for about 40 minutes (also seen once at build
  175), `mynx usb allow` didn't help; then it had `ttyACM1`. Not
  understood either.

### 2026-10-18 (125): Serial monitor, board tools, one PR (branch `usb-serial-3`)

Overnight, at the maintainer's request (good night, test in the
morning); two subagents, merged by the main agent. Not on the phone.
- **Serial monitor:** `tools/apps/serial` (Apps → Serial monitor),
  `mynx usb monitor [PORT]`, the `serial` key bar. Ctrl+T commands as
  in miniterm; reset (RTS off, DTR off, an RTS pulse, a DTR pulse),
  bootloader (esptool's classic or USB-JTAG sequence by vendor, else
  the 1200-baud touch), reopens a port that comes back. No `--tab`:
  no request opens a tab with a command yet.
- **`mynx fix-links DIR…`:** arduino-cli unpacks into a temp folder
  and renames it, which breaks proot's fake hard links (AGENTS.md
  "proot notes"); replacing each with a copy works. arduino-cli 1.5.2
  then builds Blink for the Nano, Leonardo and C3 (esp32 core 1.5 GB,
  13 min first build). PlatformIO 6.2.0 builds AVR, but its ESP32
  toolchains fail to install (`copytree(symlinks=True)`: proot's
  getdents says DT_LNK for a fake hard link, `readlink` says EINVAL):
  the guide sends ESP32 users to arduino-cli.
- Recipes in the guide's USB section: esptool, mpremote, arduino-cli,
  PlatformIO, avrdude.
- Merged with entry 124 (port names) into one PR. **Main agent's
  change:** `serial-names` off by default for this round, since a
  broken own `/dev` would break every new tab with no shell left to
  turn it off; the plan turns it on from a running tab.
- **Found in CI, fixed:** the monitor's tests needed `keybar` on the
  PATH (the phone has `/opt/mynx/bin`, CI doesn't); and a race in
  `SerialServer`: it freed the port before dropping a leaving
  client's RTS and DTR, so a new client could have its lines dropped
  (the drop-order test flaked on it). Now freed after; test added.
- **Build:** `Download/mynx-dev-39082299921.apk` (PR #36's run
  39082299921), copied with `deliver.sh --no-open`: open it from the
  Files app to install mynx dev.
- Left out: UF2 and raw USB (Android-side work: mass storage, the
  USB fd into Debian), static Go programs (needs a proot patch).
- Known flaky under load (passed alone): `test_undo_list` (needs
  `TZ=`), `test_audio_play_stops_quietly_on_ctrl_c`, a
  `SerialServerTest` timing test.

### 2026-10-18 (124): Serial port names tools find (branch `serial-names`)

Overnight, a subagent, not on the phone yet. `/dev/ttyACM0` and
`/dev/ttyUSB0`, and a `/sys/class/tty` stand-in with each port's USB
details, so `list_ports`, esptool with no `--port`, PlatformIO and
`arduino-cli board list` find the ports (setting `serial-names`).
- **Why a `/dev` of Debian's own:** pyserial (`glob('/dev/ttyACM*')`)
  and go.bug.st/serial (`ReadDir("/dev")`) list `/dev`, and Android
  refuses that to apps (checked: EACCES, also `/dev/pts`). Binds don't
  show in listings (proot has no `getdents` emulation, `glue.c` says
  so), and a bind whose host path is missing at start is dropped
  (`new_binding()` realpaths it), so per-name binds over Android's
  `/dev` can't come and go. A folder of ours at `/dev`, with the
  phone's device files bound into it, is listable and its links change
  freely. Rejected: a proot patch (`getdents` for binds), wrapping
  `opendir` in the preload library (every program, and Go skips it).
- **Fallback:** `serial-names off` (new tabs) gives Android's `/dev`
  back, exactly as before; the helpers never get the own `/dev`.
- **Checked off the phone:** pyserial 3.5's `list_ports_linux` run
  against a written tree (paths swapped) gives `USB VID:PID=303A:1001
  SER=AA LOCATION=1-1:1.0` and `1A86:7523 LOCATION=1-2`, with
  products as descriptions. go.bug.st/serial's code read, not run.
- **Unsure until the phone:** whether anything in Debian needed a
  host `/dev` entry not in `HOST_DEVICES` (only those the phone has
  are bound; `ls /dev` shows what's there); proot following a link in
  the bound folder that's made after it started (it should: it's how
  `/dev/serial` links work). esptool still warns "Failed to get PID
  … /dev/pts/N" (it follows the link): harmless.

### 2026-10-18 (123): USB serial docs cleaned up

PR #34 is merged, so its plan (Next 0) and entries 115-122 left the
log (`git show 46bd16c:docs/WORKLOG.md`); their lessons are in
AGENTS.md. Two loose ends went to "Small open items". The roadmap's
development boards section says what's done and what's left.

### 2026-10-13 (114): Autostart on and off

The maintainer asked, before v0.2.0, for a way to switch a saved
service's autostart without editing the file (branch
`service-autostart`):
- core: `setServiceKey()` changes a block's `key = value` line, or adds
  one after its last setting; the `service-set NAME KEY=VALUE` request
  uses it (saved services only, an undo step, a running one keeps
  running).
- `mynx service autostart NAME on|off`; the menu's Services → NAME has
  "Autostart: on/off", which switches it (not for unsaved services).
- The guide says what starts with the app: autostart ones, plus those
  running when Android closed it; after Exit only autostart ones.
- AGENTS.md: GitHub deletes merged PR branches (setting on, 2026-10-13).
Phone test 21 (in "Next 0") is still to do. Then the maintainer
releases v0.2.0.

### 2026-10-13 (113): Services in the README and roadmap

PR #30 merged (eeb9b21). The README lists background services among
what ships; the roadmap moves them from Planned to Done. Next: the
maintainer plans v0.2.0 after a day or two of services in mynx dev.

### 2026-10-13 (112): The review's two leftovers, fixed

Maintainer's request, by two agents in parallel, merged on
`services`:
- A service whose block is deleted by hand while it runs is
  `orphaned`: unsaved until it ends, then gone from the list
  (`ServicesModel.kt`, e8999de). Adopted services stay listed after
  they end, as before.
- A reload of `services.conf` that changes anything calls
  `host.changed()` once (`ServiceManager.reload()`, 045381c), and the
  app rereads the file on `mynx check` and the other reloading
  requests, `undo` included (682a1d7).
Phone test 20 checks both: passed on the phone (run
38378514995). PR #30 marked ready and merged.

### 2026-10-13 (111): Background services confirmed on the phone

The maintainer ran all 19 phone tests in "Next 0" on mynx dev (run
38324395547's build): all pass. One bug found and fixed (6f47077,
run 38348748133 green): `mynx` crashed (FileNotFoundError on
`ID.wait`) when the app answered a [Later] at once, since it writes
`ID.wait`, then `ID.reply`, then deletes `ID.wait`. The race was
old, but every `service-*` request answers that way. Test 6's
expectation was wrong, not the code: a program that catches Ctrl+C
and exits 0 isn't restarted under `on-failure`; kept as it is (the
long-press Restart is for restarting). Then the two review items
were fixed (entry 112).

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
Left for later, then fixed in entry 112: a hand-deleted block's
service stayed listed after it ended, and `list`'s reload didn't
refresh the app.

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

