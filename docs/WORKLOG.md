# Work log

Where the project stands, what happened in each session, and what to do next.
Newest entries first. Rules for keeping it up to date: see
[`AGENTS.md`](../AGENTS.md#status-work-log-and-next-steps).

---

## Current status

- **Roadmap steps 1-7 are done** and confirmed on the owner's phone.
  Step 8 (*Profiles*) is planned but **parked** by the owner
  (2026-10-04): step 9 (*Android integration*) comes first and is
  planned; see "Next". **9.1 is confirmed** (vibration, clipboard,
  waiting/streaming requests); **9.2 (sharing) is confirmed**
  (2026-10-05); **9.3 (location) is confirmed** (2026-10-05). **9.4
  (sensors) is confirmed** (2026-10-05). **9.5 (camera and
  flashlight) is confirmed** (2026-10-05). **9.6 (sound)**: the files
  half (`pocket audio play`/`record`) is confirmed (2026-10-05,
  entry 42); the sound device's speaker half is confirmed (2026-10-05,
  entry 47); the microphone half is built (entry 49), waiting for
  the phone check.
- **Step 7 (*Agent support*), confirmed 2026-10-04:** phone
  notifications from agents' hooks (`pocket notify`, `pocket hook`),
  `pocket agent` installs Claude Code, Codex and Gemini CLI with their
  official installers and toggles their notifications, the AI agents
  submenu, the `agent` key bar, undo for config changes (`pocket undo`,
  `undo-keep`), and links that open in the phone's browser (`pocket
  open`, `xdg-open` as `BROWSER`, tappable links). Debug builds install
  new builds from the app's Debian (`pocket install-apk`).
- **Step 6 (*Customization*), confirmed 2026-10-04:** every setting is a
  plain-text file in `~/.config/pocket-terminal/`, changed with the
  `pocket` CLI (`check`, `settings`/`get`/`set`/`reset`, `theme`,
  `keybar`, `menu`, `--json`) and the curses editors `pocket edit`
  (menu → Settings): theme with live preview and a colour editor, font
  & cursor, key bars, launcher menu (`menu.conf`, `run COMMAND` items),
  `r` resets to default everywhere. Ten built-in themes; strip and key
  bar colours chosen by contrast. The app's commands and the agent
  guide live in the app's tools at `/opt/pocket-terminal` (replaced on
  each app update, first on the PATH); `~/AGENTS.md` points to the guide.
- **Step 5 (*Game and program key bars*):** game bars, `play`, `keybar
  NAME,FALLBACK`, bars any program can bring, hold-to-repeat. The owner
  dropped the in-app keyboard.
- **Step 4 (*File manager*):** nnn as `files`, and the always-visible
  two-row key bar that follows the running program.
- **Step 3 (*Default setup*):** Neon theme, JetBrains Mono Nerd Font,
  customized Debian image (starship, eza, mc, nano, less, htop,
  python3, three games), launcher menu on a fresh start.
- **Step 2 (*Tabs*):** tab strip, background service, activity/bell
  marks, rename, tabs restored after Android kills the app. Hardware
  keyboard shortcuts untested (see "Hardware keyboard checks").
- **Step 1 (*Core*):** a `root@localhost` bash in the built-in Debian 13
  (trixie) through proot; `apt` works; app updates keep the user's
  Debian.
- **App:** a tab strip above one Termux `TerminalView`, the key bar
  below it; sessions live in `TerminalService` (foreground service with
  an Exit action), saved to `filesDir/state/tabs`. First launch unpacks
  the rootfs into `filesDir/debian`; every start updates the tools in
  `filesDir/tools` and answers `pocket` requests from
  `/tmp/.pocket-terminal/requests`. Crashes are shown on the next
  launch.
- **Build:** GitHub Actions runs the TDD check and all tests, builds
  proot (cached), the rootfs and the tools archive, then a debug APK.
  `scripts/deliver.sh` installs it on the phone.
- **Code and tests:** `core/` (plain Kotlin: proot launch, rootfs and
  tools installers, tabs, key bars, colours/themes, settings, config
  check, `pocket` requests, undo, links, waiting and streaming
  requests, sharing, location; 312 tests), `app/` (thin Android layer),
  `tools/` (`pocket` and editors in Python, `menu` and other commands,
  the agent guide; 160 unittest tests incl. editors driven in a pty),
  `rootfs/` (Dockerfile, home dotfiles, games), `tests/shell/` (63 bats
  tests).


## Next

### Roadmap step 9: Android integration

README section "Android integration". Planned with the owner
2026-10-04, ahead of the parked step 8. Build test-first, with an
owner test after each part.

**9.1 is done** (confirmed on the phone 2026-10-04, log entry 28).
**9.2 is done** (confirmed on the phone 2026-10-05, entries 29-31).
**9.3 is done** (confirmed on the phone 2026-10-05, entries 32-33).
**9.4 is done** (confirmed on the phone 2026-10-05, entries 34-36).
**9.5 (camera and flashlight) is done** (confirmed on the phone
2026-10-05, entries 38-39).
**9.6 (sound), files half is done** (confirmed on the phone
2026-10-05, entries 40-42).
**9.6 sound device, speaker half is done** (confirmed on the phone
2026-10-05, entries 45-47).

**Next: check the sound device's microphone on the phone** (entry
49, built but not yet run on the phone; the plan is in entry 48).
With the new build installed and the app on screen, in a new tab:
1. Nothing records: no microphone indicator, the notification says
   "N terminals running".
2. `arecord -f S16_LE -r 48000 -c 1 -d 5 /tmp/m.wav` and speak:
   the first time Android asks to allow the microphone (if `pocket
   audio record` hasn't already). The indicator shows while it
   records, the notification says "Microphone: arecord", and both
   go away when it ends. `aplay /tmp/m.wav` plays the voice back.
3. `pocket set android-microphone off`, record again: the file is
   silent, the notification says blocked and why; `on` again.
4. `sleep 5; arecord -d 5 /tmp/b.wav` then leave the app at once:
   silence and the "open the app" notice; opening the app within the
   5 s starts the microphone (the rest of the file has sound).
5. Start `arecord -d 20 /tmp/c.wav` on screen, then leave the app:
   it keeps recording in the background (indicator stays).
If something fails: `cat /tmp/.pocket-terminal/sound/inputs` (who
Pulse says records) and `server.log`.

**Owner's decisions (2026-10-04):**
- **Camera: both ways.** `pocket camera FILE` opens the phone's camera
  app to frame the shot (no camera permission needed); `--quick
  front|back` snaps straight to the file with no screen, for scripts
  and agents (camera permission, app on screen).
- **Location: "while using the app" only**, never background location.
  A stream started with the app on screen keeps running in the
  background through the service.
- **Sharing both ways:** `pocket share` sends files or text to other
  apps, and the app is a target in Android's share sheet (files land
  in `~/Shared`).
- **Order:** 9.1 groundwork + vibration + clipboard, 9.2 sharing,
  9.3 location, 9.4 sensors, 9.5 camera + flashlight, 9.6 sound
  (added 2026-10-05).

**Owner's decisions (2026-10-05):**
- **Flashlight** joins the camera part (`pocket torch`, through the
  camera service; no permission).
- **Speaker: both, files first.** `pocket audio play FILE` (Android
  decodes it), then a real sound device: PulseAudio in Debian writes
  raw audio to a pipe the app plays, so mpv, sox, games make sound.
- **Microphone: both, files first.** `pocket audio record FILE` (until
  Ctrl+C or `--seconds`), then a PulseAudio source so arecord, sox,
  whisper.cpp hear the mic live.
- **Recording may keep going in the background**, like a location
  stream: it starts with the app on screen, the service adds the
  `microphone` foreground type, Android shows its mic indicator.

**Design (agent's proposal, change it if the owner objects):**
- **Waiting and streaming requests.** Today a request is answered at
  once and `pocket` gives up after 5 s. New: the app may answer later
  (a GPS fix, a permission dialog, the camera app), and the client
  waits as long as that request allows. A stream request gets lines
  appended to `ID.stream` until `pocket` writes `ID.cancel` (on Ctrl+C
  or exit) or its process is gone (pid sent in the request). Answering
  must never block the main thread.
- **Permissions** are asked through the activity the first time a
  command needs one. With the app off screen, the request fails saying
  "open the app" (later maybe a "tap to allow" notification).
- **On/off per feature:** settings `android-clipboard`,
  `android-share`, `android-location`, `android-sensors`,
  `android-camera` (`on` by default; the Android permission is still
  the real gate), shown in `pocket settings` and the Settings editor.
  Per profile once step 8 exists.
- **Output:** plain text by default, `--json` like every command;
  streams print one line per reading (one JSON object per line with
  `--json`).
- Logic (argument checks, output formats, stream bookkeeping) goes in
  `core`, tested; `app` only calls Android. Each part updates
  `tools/AGENTS.md` (`home-docs.bats` requires every command in it).

**Parts:**
1. **9.1 Groundwork, vibration, clipboard:** waiting/streaming
   requests in `core` and `client.py`; `pocket vibrate [MS]`; `pocket clipboard get` and `pocket clipboard
   set [TEXT]` (stdin when no TEXT). Android only lets the app on
   screen read the clipboard: `get` says so when it's not.
2. **9.2 Sharing:** `pocket share FILE…` and `pocket share --text
   TEXT` open Android's share sheet (through a provider like
   `ApkProvider`, serving just the shared files). Share-sheet target:
   `ACTION_SEND`/`SEND_MULTIPLE` for any type; files go to `~/Shared`
   (`share-folder` setting), text to a `.txt` file there; a
   notification says where.
3. **9.3 Location:** the permission flow (moved here from 9.1: it's
   the first part that needs a permission); `pocket location` (one fix, with a timeout) and
   `--stream [--interval S]`; `--coarse`. Fine/coarse permission; the
   service gains the `location` foreground-service type (and its
   Android 14 permission) for streams.
4. **9.4 Sensors:** `pocket sensor list`, `pocket sensor NAME` (one
   reading) and `--stream [--rate HZ]` (up to 200 Hz, so no special
   permission). Names like `accelerometer`, `gyroscope`, `light`.
5. **9.5 Camera:** `pocket camera FILE` (`ACTION_IMAGE_CAPTURE`, the
   photo copied into Debian) and `--quick front|back` (Camera2, camera
   permission). `pocket torch on|off` (`CameraManager.setTorchMode`,
   no permission; maybe a strength level on API 33+).
6. **9.6 Sound:** `pocket audio play FILE` (MediaPlayer; `play` is
   taken by the games) and `pocket audio record FILE` (RECORD_AUDIO,
   app on screen to start, `microphone` foreground type, Ctrl+C or
   `--seconds` ends it). Then the sound device: PulseAudio's
   `module-pipe-sink`/`module-pipe-source` on FIFOs in `/tmp`, the app
   moving raw PCM between them and AudioTrack/AudioRecord (no sockets,
   like the request files). Check first that pipe modules work under
   proot. Setting `android-audio`.

Vibration needs no permission. Notifications and opening links were
done in step 7; phone storage is already at `/storage/emulated/0`.

### Parked: roadmap step 8, Profiles

**Parked by the owner 2026-10-04** ("not such an important feature to
work on yet"): step 9 comes first. The plan below stands for when it's
picked up again.

README roadmap: "multiple profiles, switching, export/import"; README
section "Profiles: share your setup". Planned with the owner
2026-10-04. Build test-first, with an owner test after each part, as
in step 7.

**Owner's decisions (2026-10-04):**
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

**Design (agent's proposal, change it if the owner objects):**
- A profile is `~/.config/pocket-terminal/profiles/NAME/`, with the
  same layout as the config folder (`settings.conf`,
  `colors.properties`, `keybars/`, `menu.conf`) plus `profile.conf`
  (label, tab colour, icon), `packages` (apt names), `setup.sh` and
  `bashrc`. A profile only holds what it changes: its files lie over
  the top-level config (per file; `settings.conf` per key), so the
  existing config is the `default` profile and current installs keep
  working unchanged.
- `default-profile = NAME` in the top-level `settings.conf`.
- Each saved tab records its profile (`profile = NAME` in
  `filesDir/state/tabs`). The tab's shell gets `POCKET_PROFILE`;
  `pocket` commands act on that profile unless given `--profile NAME`
  (`--profile default` for the top level).
- Undo and `pocket check` cover the profile folders too.

**Parts:**
1. **8.1 Model and commands:** layering in `core` (tested);
   `pocket profile list/show/new/copy/rename/delete/default`, `--json`;
   `--profile` on the config commands; `pocket check` per profile.
2. **8.2 Profiles in the app:** tabs remember their profile and use
   its theme, font and key bars; the strip shows its colour; **⌄**
   opens a tab in a chosen profile; `POCKET_PROFILE` in the shell.
3. **8.3 Recipe:** `packages`, `setup.sh`, `bashrc` snippet (sourced
   via `/opt/pocket-terminal/shell.bash`); `pocket profile apply NAME`
   installs the packages and runs the script after showing them and
   asking.
4. **8.4 Export/import:** `pocket profile export NAME [FILE]` writes
   the text file; `pocket profile import FILE` shows everything it
   will write, install and run, asks, then adds the profile (applying
   it is a separate yes). Name clashes ask for a new name.
5. **8.5 Editors and docs:** a Profiles editor in `pocket edit`, a
   menu item, the agent guide (`tools/AGENTS.md`) and README.

### Regression checks (steps 4-6)
- **Step 6:** `pocket theme set` for a dark and a light theme (strip
  and key bar readable); the theme editor's live preview and `r` reset;
  font size from the editor and from pinch (kept after a restart);
  cursor style and blink; editing and resetting a key bar; editing and
  resetting the menu (`run` items, and a failing command explains
  itself); `pocket check` lists problems without dialogs.
- **Steps 4-5:** `files` (nnn bar, two rows, pages to swipe), game bars
  for `rogue`/`drive`/`flap` and the generic `game` bar, hold-to-repeat
  on arrows, each tab keeping its own bar.

### Hardware keyboard checks (later)
The owner has no hardware keyboard, so these are untested. Run them when
one is available (Bluetooth/USB keyboard), or with a soft keyboard that
sends real Ctrl/Alt key events (e.g. Hacker's Keyboard), or once the
in-app keyboard (step 5) can send these combos:
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
- No wakelock yet (README: "foreground service with an optional
  wakelock"). The shell survived a few minutes with the screen off without
  one; add a notification action for it (like Termux) if long jobs stall.
- Step 1.4's interrupted-install check (swipe the app away while unpacking,
  reopen) hasn't been tried on the phone.
- htop's CPU bars are static (fake `/proc/stat`, as in proot-distro).

---

## Log

### 2026-10-05 (49): sound device, microphone half

Built test-first from entry 48's plan; not yet run on the phone.
- `tools/lib/sound-watch` (bats, fake `pactl`): started by
  `sound-server` with Pulse's pid (exec keeps it), waits for the
  socket, lists on start and on every `on source…` event, removes
  `sound/inputs` and ends when Pulse stops. Bug found by running it
  here: it must set its own `PULSE_SERVER`, else `pactl` asks the
  tab's Pulse (the app's own one, here). `sound-server` adds
  `module-pipe-source` `mic` (default source, checked).
- core `Mic.kt`: `micUsers()` (only uncorked outputs of `mic`; names
  from `/proc/PID/comm`, since Pulse calls `arecord` `aplay`),
  `micState()` → `Mic.Off`/`On`/`Silent(why)`, `micNotice()`,
  `Silence` (20 ms zero chunks paced by the clock, no burst after a
  stall).
- app `MicFeeder`: FileObserver on the sound folder; asks for
  RECORD_AUDIO once while programs record and the app is on screen;
  a feed thread writes AudioRecord (48 kHz mono) or `Silence` to the
  pipe, opened O_NONBLOCK (a full pipe drops a chunk, Pulse gone →
  reopen). The service: started/stopped with the sound device,
  re-decided on settings changes and when the activity starts
  (`cameOnScreen()`); `microphone` FGS type while `Mic.On`; the
  notification shows `micNotice()` (tab count as subtext). Not
  compiled locally: CI is the first compile.
- A bats test runs the real Pulse where installed (skipped
  otherwise): `arecord` shows up in `inputs`, `mic` is default.
- Guide and architecture notes updated. Tests: core 344, bats 84.

### 2026-10-05 (48): microphone half planned

The owner worried about Android's microphone indicator; the plan
opens the microphone only while a program records from Pulse, names
the program in the notification and keeps `android-microphone off`
as the switch (see "Next"). Checked here with the real Pulse 17: a
pipe source with no writer gives `parecord` nothing (0 bytes in 2 s,
no silence), and `pactl list source-outputs` shows `Source: N`,
`Corked:` and `application.process.binary`.

### 2026-10-05 (47): sound device (speaker half) confirmed on the phone

Build 67 (1ea1e12). The owner heard all four tones, clean: `paplay`
and `aplay` (2.4 s and 2.8 s for a 2 s tone), after
`sound-device off`/`on` (no `pulseaudio` left while off: the pid
stop works) and after `pkill -9 pulseaudio` (back within 2 s).
Check 5 passed too: after Exit from the notification and reopening,
one `pulseaudio`, and it plays.

### 2026-10-05 (46): stop PulseAudio itself, by its pid

Picked up from a session that crashed before committing. Killing
the proot that runs the sound server leaves PulseAudio running, so a
stop (or an app killed by Android) would leave a stray Pulse, and the
next start would compete with it.
- `sound-server` writes its pid (`exec` keeps it) to
  `/tmp/.pocket-terminal/sound/pid`, and first stops an older Pulse
  named there (TERM, up to 2 s, then KILL), only if
  `/proc/PID/comm` is still `pulseaudio`.
- core: `SOUND_PID`, `soundServerPid()` (same check). `SoundDevice`
  sends SIGTERM to that pid before killing proot.
- Not yet compiled (CI) or run on the phone. Tests: core green,
  `sound-server.bats` 6/6.

### 2026-10-05 (45): sound device, speaker half

Built test-first from the spike's design (entries 43-44); not yet
run on the phone.
- **Owner's decisions:** the packages go in the image (plus `pocket
  sound install` for existing Debians); PulseAudio **starts with the
  app**. First chosen: on demand, but Pulse refuses to autospawn as
  root ("Not doing autospawn since we are root"), so on demand would
  need a listener holding the socket, ~9 MB of Python, more than an
  idle Pulse (5.5 MB RSS, 0 CPU ticks in 10 s). The owner picked
  starting with the app after hearing that.
- `tools/lib/sound-server`: PulseAudio with `-n`, pipe sink `phone`
  (48 kHz s16 stereo) at `/tmp/.pocket-terminal/sound/out`, native
  socket `…/native` (anonymous), suspend-on-idle; exit 3 when not
  installed. Checked here with the real Pulse: default sink `phone`,
  `paplay` into the pipe, a stale FIFO or socket left by `kill -9`
  doesn't stop the next start.
- core: `prootLaunch(command = …, soundSocket = …)` (`PULSE_SERVER`
  in every tab); `PipePlayer` (whole frames, play on sound, pause
  after 500 ms quiet, reopen after the pipe ends); `ServerRestarts`
  (1 s doubling to 60 s, reset after 30 s up, none for exit 3);
  `sound-device` setting; `sound-start` request (refused while the
  setting is off).
- ALSA needs no config: Debian's `99-pulse.conf` hook makes pulse the
  default whenever `PULSE_SERVER` answers (`aplay` checked here).
- app: `SoundDevice` runs the server in its own proot
  (`ProcessBuilder`, output appended to `sound/server.log`), plays
  the pipe on a 48 kHz AudioTrack (2× min buffer), idle check on the
  main handler; stop kills proot and opens the pipe to write once so
  a reader stuck opening it gets through. The service starts it when
  the setting is on, after each settings-changing request, and stops
  it on destroy. Not compiled locally (no Android SDK): CI is the
  first compile.
- `pocket sound [status] | start | install [--yes]`; guide and
  architecture notes updated. Tests: core 327, pocket 170, bats 66.

### 2026-10-05 (44): sound device spike confirmed on the phone

Spike build 65 (run 37330111521, `c401742`), `~/snd-spike.sh` run
twice with the app on screen:
- The owner heard the 2 s tone **both times, clean**.
- `paplay` took 2.35 s and 2.32 s (≈2.3 s expected): the AudioTrack
  paces the pipe sink. No underruns in the trace.
- Second run: the trace shows `eof` when Pulse stopped, then the
  FIFO reopened and played: the app recovers from a Pulse restart.
Thrown away: branch `spike/sound-device` (local and remote),
`~/snd-spike.sh`, `~/spike-tone.wav`, `~/.asoundrc`. The Pulse,
sox and ALSA packages from entry 43 stay installed in the dev
Debian. Next: plan the real sound device with the owner.

### 2026-10-05 (43): sound device spike, Debian side

Branch `spike/sound-device` (throwaway). In the app's Debian, with
`pulseaudio pulseaudio-utils sox alsa-utils libasound2-plugins`
installed (left installed):
- **PulseAudio 17 runs under proot** as fake root with `-n`,
  `module-pipe-sink`, `module-pipe-source` and
  `module-native-protocol-unix auth-anonymous=1` on `/tmp/snd/`.
  Harmless warnings: no RLIMIT_RTPRIO, no `/dev/shm`, no D-Bus.
- **Playback:** `paplay` and `aplay` (`~/.asoundrc` with `type
  pulse`) put the tone into the FIFO intact (2.0 s, 440 Hz).
- **The pipe sink has no clock:** it goes as fast as the reader
  (a 2 s tone in 186 ms). The app's blocking AudioTrack writes must
  pace it; read in small chunks (20 ms).
- **Nobody reading blocks players** (`paplay` hung). Idle sink writes
  nothing. A reader that leaves and comes back gets the rest;
  Pulse survives it.
- **Pulse deletes its FIFOs when it stops**, so the app must reopen
  the pipe each time Pulse starts.
- **Mic:** PCM written into the source FIFO comes out of `parecord`
  (660 Hz in, 659 out). But with no recording client the writer
  **blocks** (the source is suspended). `pactl subscribe` reports
  `new`/`remove` on source-output, so a helper in Debian can tell
  the app when to turn the mic on and off (also right for privacy).
- Spike app code: `SpikeSound` thread in the service reads
  `rootfs/tmp/snd/out` into a 48 kHz stereo AudioTrack, traces to
  `spike-sound.txt` in the external files dir. Untested on the phone.

### 2026-10-05 (42): 9.6 files half confirmed on the phone

The three checks from "Next" on the build with `a723342`, all pass:
1. `timeout -s INT 3 pocket audio play ~/long.wav`: the tone stops at
   3 s, nothing left in the request folder (the double-SIGINT fix
   and the sweep fix work).
2. Background playback keeps playing with the app left.
3. Microphone denied in Android's settings: record says it wasn't
   allowed.
The files half of 9.6 is done; the sound device is next.

### 2026-10-05 (41): 9.6 tested on the phone; Ctrl+C on a stream fixed

**Checks** (build 62, `c00a5a5`), steps from "Next", with the owner
listening:
1. pass: 7 s recorded (6.9 s saved), played back clearly. The very
   first take (owner away) saved 3.3 s of an 8 s run, likely the
   permission dialog. Playback reports ~0.3 s less than the recorder.
2. pass: plays to the end; `--json` gives file and seconds.
3. pass: `a.wav` 16-bit mono 16000 Hz, 3.0 s, plays.
4. pass: `.ogg` is Ogg Opus, `.aac` ADTS AAC, both play.
5. **fail**: `timeout -s INT 3 pocket audio play` of a 20 s tone:
   `pocket` returned at 3 s, the tone played all 20 s, and its reply
   was left in the request folder. A single `kill -INT` stopped it.
   Two causes: `timeout` sends SIGINT twice (to `pocket` and its
   group); the second broke `cancel()`'s wait and the cleanup deleted
   `ID.cancel` before the app looked. And the fallback (sweep every
   2 s for requests whose `pocket` has gone) never ran while the app
   was on screen: the activity's 250 ms poll calls
   `processRequests()`, whose `scheduleSweep()` pushed the sweep back
   each time. **Fixed:** `client.cancel()` ignores Ctrl+C while it
   waits (at most 5 s; test
   `test_a_second_ctrl_c_doesnt_take_the_cancel_back`), and
   `scheduleSweep()` never puts off a due sweep (app only, untested
   locally).
6. pass: 25 s recording with the owner in another app; mic indicator
   stayed on, the speech from while away is in it. Background
   playback not checked yet.
7. pass (by accident, twice): with the screen off, "the app must be
   on screen to start", exit 2.
8. pass: `android-microphone off` refuses recording, play works;
   `.mp3` gives the format error.
9. not done yet.
- Tests: pocket 160.

### 2026-10-05 (40): 9.6 started, `pocket audio play` and `record`

- **Files first**, per the owner's plan. `pocket audio play FILE`
  (MediaPlayer, the file opened by the app and handed over as an fd)
  waits until the sound ends; Ctrl+C stops it. `pocket audio record
  FILE [--seconds N] [--rate HZ]`: the ending picks the format, `.m4a`/
  `.aac` (AAC) and `.ogg`/`.opus` (Opus, API 29+) through
  MediaRecorder, `.wav` (16-bit mono PCM, for whisper.cpp and the
  like) through AudioRecord with core's `wavHeader()`. Recordings go
  to `.NAME.part` and core renames them when complete.
- **Ctrl+C ends a recording normally**, so `PendingReply.cancel` now
  lets the `onCancel` block answer (else `{"ok":true}` as before);
  `RecordReport.onStop` must stop and report before returning, and
  `pocket`'s `request(answer_on_interrupt=True)` returns that answer.
  The client's cancel wait went from 2 to 5 s for slow finishes.
  The app sends a `{"recording":true}` stream line once the mic is
  on, so "Recording to …" only shows when it really records.
- **Setting `android-microphone`** (not `android-audio` as planned):
  only recording is gated, like the other privacy settings; playing
  has no setting, like vibrate and torch.
- Service gains the `microphone` foreground type while anything
  records (`setRecording`, like `setLocating`); manifest adds
  RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE, microphone feature
  optional. A stop before the permission dialog answers means the
  recording never starts (`AudioRecorder.Request`).
- `targetFile()` (folder checks for a file to save to) is shared by
  camera and audio.
- **Test flake seen, not explained:** while iterating, the Ctrl+C
  tests (`test_camera_stops…`, `test_audio_play_stops…`) failed now
  and then with `pocket` exiting 130 in 0.1 s without writing
  `ID.cancel`, though `ID.wait` existed. Tracing never caught it,
  and it may have been leftover load from a hung test run (the
  first red run hung: the tests waited for a request that never
  came, so `wait_for_request()` now gives up after 5 s). The full
  suite then passed. Watch CI for it.
- App code can't build here: CI is its compile check.
- Tests: core 312, pocket 159.

### 2026-10-05 (39): 9.5 confirmed on the phone

Build 61 (`ad76f65` + docs) installed. `timeout -s INT 1 pocket
camera --quick back ~/d.jpg` and the same at 0.3 s printed nothing,
exit 124 from `timeout`; no file written, no request files left; the
next `--quick back` shot saved (2.0 MB). Step 9.5 is done.

### 2026-10-05 (38): 9.5 tested on the phone

**Checks** (build 60, commit f889717), steps from "Next", done with
the agent running each command and reading the photos:
1. pass: camera app, photos upright in portrait and landscape, back
   and front (2256x4000, 4000x2256, 3840x2160).
2. pass (after the owner first took photos by mistake): backing out
   gave "no photo was taken", exit 2, `a.jpg` unchanged.
3. pass: `--quick back` 1.9 s, `--quick front` 3.3 s, exposed and
   in focus, upright in portrait and landscape (keyboard lettering
   reads the right way, so not mirrored or turned).
4. pass, with a wart: SIGINT at 1 s (`timeout -s INT 1`) cancelled
   the shot, nothing written, no request files left, the next shot
   worked; but `pocket` printed a Python traceback (also when
   interrupted during startup, at 0.3 s). **Fixed:** `tools/bin/pocket`
   catches KeyboardInterrupt around the import and `main()`, exit 130,
   silent (the client had already cancelled the request). Test
   `test_camera_stops_quietly_on_ctrl_c`.
5. pass: on, 10 % dimmer, 100 % brighter, off; and `torch off` works
   with the app in the background, so the guide now says so.
6. pass: "the camera is off (pocket set android-camera on)", exit 2.
7. pass (owner, after the commit): with the camera denied in the
   app's Android settings, `pocket camera --quick back ~/x.jpg` gave
   "the camera wasn't allowed (allow it in the app's Android
   settings)", exit 2.
- Tests: pocket 153.

### 2026-10-05 (37): step 9.5, camera and flashlight

- `pocket camera FILE` opens the phone's camera app
  (`ACTION_IMAGE_CAPTURE`); the photo goes to a temporary file in the
  app's cache through `PhotoProvider` (writable, one random name at a
  time) and core moves it to FILE, so backing out or Ctrl+C never
  touches an existing FILE. The result comes back through
  `MainActivity.startForResult()`.
- `--quick front|back`: Camera2 in `CameraShooter`, no screen. A
  small YUV stream runs until `shotReady()` (AE converged and AF
  settled, at least 0.3 s, at most 2.5 s), then one full-size JPEG at
  quality 95, `JPEG_ORIENTATION` from `jpegOrientation()` (sensor
  mounting and the screen's rotation). 15 s app-side timeout.
- **Decision:** CAMERA is in the manifest for `--quick`, and Android
  then refuses `ACTION_IMAGE_CAPTURE` to an app that hasn't been
  granted it, so the camera-app way asks for the permission too (the
  owner's plan said it needed none: not possible with `--quick` in the
  same app). `uses-feature camera` is `required="false"`.
- `pocket torch on [PERCENT] | off`: `setTorchMode`, or
  `turnOnTorchWithStrengthLevel` with `torchLevel()` mapping percent
  onto the phone's levels (API 33+). No setting, no permission, like
  `vibrate`.
- Setting `android-camera` (blocks `camera`, not `torch`).
- Not done: the `camera` foreground-service type (a shot only runs
  with the app on screen, so not needed yet).
- Tests: core 298 (`CameraTest.kt`: requests, moving the photo,
  orientation, settling, torch levels), pocket 152, bats 63. The app
  code only compiles in CI.

### 2026-10-05 (36): 9.4 confirmed on the phone

With build 68a5c70 installed, the agent ran the two rechecks from
entry 35 in the app's Debian: `pocket sensor list` lines up in
columns (17 sensors, `compass` last), and `pocket sensor accelerometer
--stream --rate 1 | head -5` printed times about 1 s apart
(21.172, 21.977, 22.972, 23.979, 24.974). Step 9.4 is done.

### 2026-10-05 (35): 9.4 tested on the phone

**Checks** (build 57, commit 55c2b54), steps from "Next":
1. pass: 17 sensors, `compass` last. Cosmetic: names longer than
   20 characters (`magnetic-field-uncalibrated`, …) push their
   value columns out of line in `pocket sensor list`.
2. pass: flat z = 9.9; light 113 → 23 lx covered, sent only on change.
3. pass on the second try: 349° pointing north, about 75° after a
   quarter turn clockwise, wrapping through 0. (The first try read
   108° "north", probably the phone not really facing north or an
   uncalibrated magnetometer; maths checked against Android's.)
4. pass: 100 lines in 2.0 s, gaps 19-21 ms, exit 0, no request
   files left.
5. pass: 172 readings over 2 min 18 s with the app in the
   background, no gaps. SIGINT stopped it cleanly (exit 1, no
   request files left). Note: `--rate 1` gave readings about 0.8 s
   apart, not 1 s.
6. pass: allowed gave `108873 steps`; with physical activity denied
   in the app's settings: "step-counter wasn't allowed (allow
   physical activity in the app's Android settings)".
7. pass: refused with "sensors are off (pocket set android-sensors
   on)", also in `--json`; works again once back on.
8. pass: `pocket location` gave a network fix.

**Fixed after the checks** (not yet on the phone):
- Stream rate: the throttle kept 0.8 periods since the last reading
  sent, so with Android sending every ~200 ms `--rate 1` always gave
  0.8 s. Now readings are measured against a schedule (one period
  apart, a fifth of a period early allowed), so the average is the
  asked rate. Test in `SensorsTest`.
- `pocket sensor list` pads names to the longest one, not 20
  characters. (The `magnetic-field-uncalibrated` line is 57 columns,
  one more than the portrait screen; left as is.)

### 2026-10-05 (34): step 9.4, sensors

**Done**
- Owner added scope (commit 4786346): flashlight with the camera
  (9.5), sound as 9.6 (play/record files first, then a PulseAudio
  sound device through FIFOs), recording may go on in the background.
  README and "Next" updated.
- `pocket sensor list`, `pocket sensor NAME [--timeout S]` (10 s
  default, 1-60) and `--stream [--rate HZ]` (10 default, 1-200; lines
  start with the time). Core (`Sensors.kt`, `SensorRequestTest`,
  `CompassTest`): `SENSOR_KINDS` (Android type, value names, unit,
  extra permission), the `sensor-list`/`sensor`/`sensor-stream`
  requests, a stream throttle (drops readings closer than 0.8 of the
  period: Android sends more than asked), reading JSON (4 decimals,
  accuracy as a word), and `compass` = azimuth/pitch/roll from the
  rotation vector as Android's getOrientation does. New setting
  `android-sensors` (blocks the list too).
- App: `SensorReader` (SensorManager on a `HandlerThread`, event
  times converted from boot time to clock time); the activity's
  location dialog became `askPermissions(perms, answer)`, one dialog
  at a time; `ACTIVITY_RECOGNITION` for the step sensors (only asked
  on Android 10+).
- Guide: commands, setting, "The phone". Repo guide: Sensors.

**Decisions (agent's)**
- Sensors don't need the app on screen (no permission, and the
  service is in the foreground); only the step permission dialog
  does. Body sensors (heart rate) left out: they need BODY_SENSORS and
  Pixels have none. One-shot sensors (significant motion) left out.
- `compass` is a name of ours; Android's deprecated orientation
  sensor (type 3) isn't offered.

**Not checked:** the app module builds only in CI; on the phone see
"Next".

### 2026-10-05 (33): 9.3 confirmed on the phone

The owner went through all eight checks from entry 32 (one fix,
`--json`, `--gps`, a stream kept going in the background, `| head
-3`, the setting off, phone location off, starting off screen,
approximate and denied). After "Don't allow" in the app's Android
settings, `pocket location` showed Android's dialog again; denying
gave "location wasn't allowed (allow it in the app's Android
settings)", exit 2. Allowed again: `60.2070536, 24.7659244 ±100 m
network`. Step 9.3 is done.

### 2026-10-05 (32): step 9.3, location

**Done**
- `pocket location [--gps] [--timeout S]` and `--stream [--every S]`
  (plain line or `--json`; a stream prints one line per fix, Ctrl+C or
  a closed pipe stops it quietly). Core (`Location.kt`,
  `LocationRequestTest`, `LocationProvidersTest`): the `location`
  request (wait 300 s, the app gives up after the timeout: 60 s
  default, 1-300) and `location-stream` (interval 5 s default,
  1-3600); option checks; `locationProviders()` picks `gps`/`network`
  from the permissions, the location switch and the providers, or
  says why not; `fixJson()` (7 decimals for degrees, 1 for the rest,
  null for unknown). New setting `android-location` (on).
- App: `Locator` (LocationManager, main thread; one-off fixes stop at
  the first fix from any provider), the permission dialog through
  `MainActivity.askForLocation()` (queued answers; refused if the app
  is off screen), manifest permissions (coarse, fine,
  `FOREGROUND_SERVICE_LOCATION`) and the service's `location`
  foreground type, added while something is locating.
- Guide: command, setting, "The phone" text. Repo guide: Location.

**Decisions**
- Owner: accept whichever fix comes first; `--gps` insists on GPS.
- Agent's choices: no last-known location (always a fresh fix);
  locating must *start* with the app on screen (Android gives "while
  in use" location only then, or to a service already locating in the
  foreground), so a second request while a stream runs works in the
  background. `obj()` in PocketRequests.kt is now internal, for
  `fixJson`.

**Not checked:** the app module builds only in CI; on the phone see
"Next". One full run of the pocket and bats suites had a failure each
(bats in `shell-setup.bats` line 33) while both ran at once; neither
came back when re-run, so it looked like load, not a bug.

### 2026-10-05 (31): 9.2 confirmed on the phone

Build 55 (commit 9a1becd). The owner shared a web page from the
browser: `~/Shared` got `Polkukengät Nike ACG Zegama -
Top4Running.fi.txt` (named after the page title, non-ASCII kept)
holding only the link, with a trailing newline. Entry 30's fix works,
so 9.2 is fully confirmed. Older test files from entry 30 are still in
the owner's `~/Shared`, left alone.

### 2026-10-05 (30): 9.2 tested on the phone; browser share fixed

Tested build 54 (commit 197d67e) with the owner, the agent running
the commands. Checklist from entry 29:
- 1-2: `pocket share --text`, also from stdin with line breaks: the
  share sheet opens and the text arrives, lines kept.
- 3-4: one image (a generated PNG; the app can't read
  `/storage/emulated/0/DCIM` without a media permission) shows its
  preview and name; an image and a `.txt` together both arrive by mail.
- 5: with the owner still in the receiving app, `pocket share` refused
  ("the app must be on screen"), exit 2. 6: `android-share off`
  refuses, exit 2.
- 7: a Gallery photo shared to the app shows the box and notification
  and keeps its name; again gives `NAME (2).jpg`, identical bytes.
- 8: four photos at once all saved. **A web page from the browser
  saved the site's icon (a PNG) instead of the link:** the browser
  sends the link as EXTRA_TEXT with the icon in the ClipData as a
  share-sheet preview, and `ShareActivity` fell back to ClipData URIs
  whenever EXTRA_STREAM was empty. Fixed test-first: `filesToSave()` in
  `core/Sharing.kt` takes EXTRA_STREAM, else nothing when there's text
  (the clip is then a preview), else the clip's URIs.
- 9: `share-folder /root/inbox` saved there (folder created); reset.

### 2026-10-04 (29): step 9.2, sharing

Built test-first; committed as 7108d5c, with 197d67e fixing the share intent (`setClipData` returns nothing, so it can't be chained).

- **`pocket share FILE…`** and **`pocket share --text [TEXT]`** (stdin
  when no TEXT) open Android's share sheet. `core` checks the request
  (files exist and are files, at most 100; text non-empty, at most
  100 000 characters, percent-encoded like the clipboard) and the new
  `android-share` setting. The app needs to be on screen. `pocket`
  refuses file names with line breaks (one value per request line).
- **`ShareProvider`** (main manifest, so release builds too; not
  exported) serves the shared files read-only under a random token
  per share (`SharedFiles` in `core`, last 20 shares kept in memory,
  lost when the process dies). One type for several files comes from
  `shareType()` (`image/png`, else `image/*`, else `*/*`); single
  files use `ACTION_SEND`, several `ACTION_SEND_MULTIPLE`, with
  ClipData so the grant survives the chooser.
- **Share target:** `ShareActivity` (dialog theme, "Saving to
  ~/Shared…") takes `SEND`/`SEND_MULTIPLE` of any type and copies into
  the `share-folder` setting (default `~/Shared`) on a thread, staying
  open meanwhile because the read grant lasts as long as the activity.
  `Inbox` in `core` keeps the other app's file name made safe (last
  path part, no control characters, cut to 120 characters keeping the
  extension), names files without a name `shared-YYYY-MM-DD-HHMMSS`,
  saves text as `SUBJECT.txt` or `shared-….txt`, and never overwrites
  (` (2)`, ` (3)`…). A notification ("Saved photo.jpg in ~/Shared",
  "Saved 3 files in ~/Shared; 1 couldn't be read") opens the app; with
  notifications off it's a toast. Before Debian is set up it says to
  open the app first.
- Decided: `android-share` only covers `pocket share`. Sharing into
  the app is always the user's own action in another app, so it isn't
  gated.
- Docs: `tools/AGENTS.md` (commands, settings, "The phone"),
  `AGENTS.md` (architecture note).
- Tests: core 259 (was 239), pocket 132 (was 126), bats 63. The app
  module only builds in CI.

**Owner's checklist for 9.2** (after installing the build):
1. `pocket share --text "hello from Debian"`: the share sheet opens;
   send it to a chat or notes app and check the text arrives.
2. `echo -e "a\nb" | pocket share --text`: line breaks arrive.
3. `pocket share` on one photo (e.g. one in `/storage/emulated/0/DCIM`
   copied to `~`): the preview/receiving app shows the image and its
   name.
4. `pocket share` on two files of different types: both arrive
   (e.g. in mail as attachments, or Drive).
5. With the app in the background (`sleep 10; pocket share --text x`
   then switch away): it refuses and says the app must be on screen.
6. `pocket set android-share off`, then share: refused; set it back.
7. From the Gallery or Files app, share a photo **to the app**: a
   small "Saving to ~/Shared…" box, then a notification; `ls ~/Shared`
   shows it with its name. Share it again: `name (2).jpg`.
8. Share several files at once to the app, and a web page link from
   the browser (a `.txt` named after the page title).
9. `pocket set share-folder ~/inbox`, share once more: it lands in
   `~/inbox`. `pocket reset share-folder`.

### 2026-10-04 (28): 9.1 confirmed on the phone

Tested build 52 (commit b9bb696) with the owner, the agent running the
commands from the app's Debian:
- `pocket vibrate`, `1500` and `--json vibrate 200` all buzz; `0` and
  `6000` refuse with exit 2. A buzz started from a timer while the
  owner was in another app worked too.
- `clipboard set` (stdin with line breaks, and TEXT) works; pasting
  elsewhere gives the text; Android shows its own "copied" pop-up.
  `set` works from the background too.
- `clipboard get` prints what another app copied (`Kragg`), keeps line
  breaks, and from the background refuses with "the app must be on
  screen…". (The first try printed the old clip because the copy in
  the other app hadn't happened; pasting showed the same old text, so
  it's not a bug.)
- `android-clipboard off` blocks both, exit 2; `on` restores them.

No code changes.

### 2026-10-04 (27): step 9.1, waiting and streaming requests; vibrate; clipboard

**Done**
- **Waiting and streaming requests** (`core/.../LaterRequests.kt`,
  `LaterRequestTest`): `PocketRequests(later = mapOf(NAME to
  Later(seconds) { args, reply -> … }))`. The app writes `ID.wait` (the
  seconds, or `stream`) at once; the handler answers through
  `PendingReply` (`ok`, `refuse`, `line` for stream readings,
  `onCancel`), from any thread, first answer wins. `sweep()` cancels
  requests whose `pocket` wrote `ID.cancel` (it gets `{"ok":true}`) or
  whose process is gone (pid from the id; everything of it is
  deleted). The service sweeps on `.cancel` events and every 2 s while
  any are open. No request uses it yet: location (9.3) and sensors
  (9.4) will.
- `client.request(..., on_line=)` follows `ID.wait` (waits that long
  plus `POCKET_TIMEOUT`, or forever for a stream), hands each stream
  line to `on_line`, and on Ctrl+C or any error mid-stream writes
  `ID.cancel` and waits up to 2 s for the answer; it removes all of
  the request's files at the end (`tests/pocket/test_client.py`).
- `pocket vibrate [MS]` (300 ms default, 1-5000; `VIBRATE` permission,
  no runtime prompt).
- `pocket clipboard get` / `set [TEXT]` (stdin without TEXT). Text is
  sent percent-encoded so line breaks survive; at most 200,000
  characters (Binder limit). `get` refuses unless the app is on screen
  (Android hands background apps nothing); `get` adds a line break only
  for a terminal. New setting `android-clipboard` (on).
- Guide: "The phone" section, the commands and the setting.

**Decisions**
- The permission flow moved from 9.1 to 9.3: nothing in 9.1 needs a
  runtime permission, so it couldn't be tried on the phone yet.
- Agent's choice: clipboard text percent-encoded in one request line
  (URLDecoder in core), rather than a JSON parser or one line per text
  line, which lost trailing empty lines and `\r`.

**Not checked:** the app module can't build here; CI builds it. On the
phone: see "Next".

### 2026-10-04 (26): `pocket install-apk` and 7.3 confirmed; step 7 done

**Done**
- The owner installed build 51 (HEAD `1bec701`) through `pocket
  install-apk`: installing from the app's own Debian works. The tools
  in `/opt/pocket-terminal` match `tools/`.
- At the owner's request the agent ran the 7.3 checks itself in the
  app's Debian (config and menu state backed up first, restored after);
  all passed:
  - `pocket theme set nord` → `pocket undo` brings Neon back; a second
    undo says "Nothing to undo" (exit 1).
  - `undo-keep 3`, then font size, theme and cursor changes: `--list`
    names all three; three undos take them back newest first.
  - `font-size = huge` added by hand, no `pocket check`: `pocket undo`
    restores the file byte for byte ("edits by hand").
  - `pocket edit` driven in a pty: "Undo last change" asks "Undo
    'theme set nord'?", `y` undoes it, the status line says so.
  - `menu` with `MENU_DRYRUN=1` in a pty: AI agents lists Claude Code,
    Codex and "Gemini CLI (install)"; Enter on Claude Code runs `pocket
    agent start claude`; ↓ redraws in 0.1-0.2 s, no keys lost at 20 ms
    apart. (The menu opens on the last choice, from
    `~/.local/state/pocket-terminal/menu`.)
  - `pocket agent start gemini` shows the installer commands and asks;
    `n` → "Not installed.", exit 1, so the menu pauses on the message.
  - `pocket open` and `xdg-open` with https links succeed; `file://`
    and non-URLs are refused (exit 2).
- The owner confirmed on screen: `pocket open` opened the browser, and
  tapping links in the terminal works.
- **Step 7 is done.** README status updated to steps 1-7.

No code changes.

### 2026-10-04 (25): dev setup in the app's own Debian; `pocket install-apk`

**Done**
- The owner now runs Claude Code in the app's own Debian instead of
  Termux. Installed there: `git`, `gh` (logged in as `est4s`), JDK 21,
  `bats`. Baseline: 217 core, 113 `pocket` (after this work) and 63
  bats tests pass; real bats works here (its `/dev/fd` is fine).
- `scripts/deliver.sh` no longer uses Termux: it copies the APK to
  `/storage/emulated/0/Download` and runs `pocket install-apk FILE`,
  falling back to "open it from the Files app".
- `install-apk` request (`core/.../PocketRequests.kt`, tested in
  `InstallApkRequestTest`): maps the Debian path to the host
  (`hostPath`), refuses what isn't an `.apk` file, then calls the app's
  `installApk`. `pocket install-apk` sends the absolute path; it's left
  out of `pocket help` and the user guide (a development tool).
- App: `TerminalService.installApk` (debug builds only, app on screen)
  opens Android's "install unknown apps" setting if needed, else
  `ACTION_VIEW` on `content://io.github.est4s.terminal.apk/app.apk`,
  served by `ApkProvider` (just that file). The permission and the
  provider are declared only in `app/src/debug/AndroidManifest.xml`.

**Decisions**
- Owner's choice (2026-10-04): a debug-only install request rather than
  copying the APK and tapping it by hand. Release builds must not get
  `REQUEST_INSTALL_PACKAGES` (Play restricts it).
- A tiny `ContentProvider` instead of androidx `FileProvider`: no new
  dependency for one file.

**Not checked:** the app module can't build here; CI checks it compiles.
The installer flow needs the owner's check (see "Next").

**Commits:** see git log (this entry's commit)

### 2026-10-04 (24): 7.3 undo; AI agents submenu; links open

**Done**
- Undo (`core/.../ConfigHistory.kt`): the app keeps the config folder as
  it last saw it in `~/.local/state/pocket-terminal/undo/last/`; each
  time it looks (before and after a changing request, at `check`, at
  app start, at `undo`) a difference becomes an undo step named after
  the change. So requests, editors (`check` now takes the change's
  name: "colours edited", "key bar X edited", "menu edited"), `pocket
  menu edit|reset` and hand edits are all undoable, in one mechanism.
  Setting `undo-keep` (default 1, 0-20). `pocket undo [--list]`, and
  "Undo last change" in `pocket edit`. Skips `fonts/`, files over 256
  KB and `*.tmp`.
- AI agents: the menu's `agents` action is a submenu from `pocket agent
  list --tsv` ("Codex (install)" when missing); `pocket agent start
  [NAME]` runs an installed agent (exec, so the menu comes back when it
  exits) or offers to install it, then to start it.
- Links: `pocket open URL` (`open-url` request; http/https only) and
  `tools/bin/xdg-open`; each shell gets `BROWSER=/opt/pocket-terminal/bin/xdg-open`.
  Tapping the terminal opens the link under the tap (`linkAt` in
  `core/.../Links.kt`, which follows links over rows the terminal
  wrapped or a program broke at the edge), else shows the keyboard as
  before. Links only open while the app is on screen (Android blocks
  starting activities from the background).

**Decisions**
- Undo has no redo; undo itself isn't recorded as a step.
- The owner's request (2026-10-04): picking an installed agent in the
  menu starts it, a missing one asks to install it.
- Python tests use a PATH of `/usr/bin:/bin` plus fakes when agents are
  involved: the dev phone has the real `claude` on its PATH.

**Commits:** see git log (this entry's commit)

### 2026-10-04 (23): 7.2, `pocket agent` installs AI agents

**Done**
- `pocket agent list | install [NAME] | notify NAME on|off`
  (`tools/lib/pocket_terminal/agents.py`, unit-tested in
  `tests/pocket/test_agents.py`). Install shows the official installer
  commands, asks, runs each with `bash -o pipefail` (a failed `curl`
  in `curl … | bash` must fail), then asks about notifications;
  `--yes`, `--notify`/`--no-notify` for agents. Without a name it's a
  numbered picker that pauses at the end (the menu's **AI agents**
  item, `run pocket agent install`).
- Installers (checked 2026-10-04): Claude Code
  `curl -fsSL https://claude.ai/install.sh | bash`; Codex
  `curl -fsSL https://chatgpt.com/codex/install.sh | sh` (a standalone
  binary now, no Node); Gemini CLI `npm install -g @google/gemini-cli`
  (engines `node >=20`; trixie has 20.19, so `apt-get install nodejs
  npm` is enough). `curl ca-certificates` come first when curl is
  missing, and are now in the image too.
- Hooks: Claude Code (`~/.claude/settings.json`: UserPromptSubmit,
  Stop, Notification), Codex (`~/.codex/hooks.json`, on by default
  now: UserPromptSubmit, Stop, PermissionRequest), Gemini CLI
  (`~/.gemini/settings.json`: BeforeAgent, AfterAgent, Notification).
  All call `/opt/pocket-terminal/bin/pocket hook NAME` (full path:
  agents may run hooks without the app's PATH).
- The Settings editor lists "<Agent> notifications" rows (←→ toggles,
  `r` turns off); "Reset all" leaves them alone.

**Decisions**
- Whether an agent notifies is stored only as the hook entries in the
  agent's own config (no setting in `settings.conf` that could
  disagree). Our entries are recognised by their command ending in
  `pocket hook NAME`; removing them keeps everything else.
- A config file that isn't valid JSON is refused, never rewritten.
- Codex treats any stderr from a hook as "block" and Gemini CLI wants
  JSON on stdout: `pocket hook` writes nothing to stderr, and `{}` for
  Gemini.

**Commits:** see git log (this entry's commit)

### 2026-10-04 (22): step 7 planned; 7.1, `pocket notify` and agent hooks

**Done**
- Planned step 7 with the owner (decisions under "Next").
- `pocket notify [--if-away] TITLE [TEXT]`: a `notify` request
  (`PocketRequests`, options `shell=N`, `if-away`, `agent`, `took=N`);
  the service posts it on a new high-importance channel, one
  notification per tab (id 1000 + shell number), so a tab's new
  notice replaces its last. Tapping it selects that tab
  (`EXTRA_SHELL`, `onNewIntent`); showing a tab clears its notice.
- Each shell gets `POCKET_SHELL=N` (`prootLaunch(shellId)`), so a
  notification knows its tab.
- Settings `agent-notify` (on) and `agent-notify-after` (30 s). Agent
  notices (`agent` option) follow them in core; the editor steps
  `agent-notify-after` by 5 with ←→. The editor hub's "Font & cursor"
  is now "Settings".
- `pocket hook claude`: Claude Code's `UserPromptSubmit`/`Stop`/
  `Notification` hooks. Turn start times live in
  `$TMPDIR/pocket-agent-turns/`. Always exits 0 and prints nothing:
  a `Stop` hook exiting 2 makes Claude Code keep working.
- Agent guide: the commands, settings and how to add the hooks by hand.

**Decisions**
- Turn-length and on/off rules are in core, not in the hook script, so
  every agent's hook just reports what happened.
- `--if-away` (used by the hooks) skips the notice only while that
  tab is on screen; plain `pocket notify` always shows.
- A notice from a tab that no longer exists opens the app without
  switching tabs.

**Commits:** see git log (this entry's commit)

### 2026-10-04 (21): step 6 done

**Owner confirmed** the round 2 fixes: readable light themes, htop and
failing menu commands, reset in every editor. **Step 6 done.** The
owner wants step 7 done by another agent: "Next" now describes it for a
fresh start. Docs updated: work log status, README status.

### 2026-10-04 (20): owner's first round on step 6

**Owner found:** (1) with solarized-light the key bar's labels were
white on a light background; (2) a menu item running `htop` said
nothing useful: htop isn't installed; (3) wanted "reset to default" in
every settings editor. Claude Code not installed yet, so the agent test
waits.

**Done**
- `stripColors()` picks colours by WCAG contrast: the theme's own picks
  when readable (Neon unchanged), else the foreground or another basic
  colour; a light theme's selected tab is a tint of the background. A
  test checks every built-in theme (text 4.5:1, accent and marks 3:1).
- `htop` is in the image (existing Debians: `apt install htop`); a
  `run` item that fails now says "NAME: command not found" or "failed
  (exit N)" and waits for a key instead of flashing back.
- Resets: requests `reset KEY|all` (`unsetSetting()` removes the line,
  so later default changes apply) and `theme-reset` (deletes the
  colours file); `pocket reset KEY|all`, `pocket theme reset`. Editors:
  `r` resets the selected setting, a "Reset all to defaults" row, `r`
  in the theme list (default theme), in the colour editor (that colour
  from the theme the file was set from), in a key bar (built-in back)
  and in the menu editor (as before).

### 2026-10-04 (19): step 6.2-6.6 built in one go

**Owner confirmed 6.1** on the phone (after the PATH fix): `pocket
version`, `pocket check`, live colours, problems listed with no dialog.
Owner asked for the rest of step 6 in one go, tested at the end; I
pushed in pieces and watched CI myself.

**Done**
- `core`, test-first: `Settings.kt` (parse, `setSetting()` keeping the
  file's other lines, `SETTINGS` descriptions); `Themes.kt` (ten themes
  as resources, `BUILT_IN_THEMES`, `# theme: NAME` marker);
  `BUILT_IN_KEY_BARS`; `PocketRequests` takes arguments (one per line)
  and answers `settings`, `set`, `themes`, `theme-show`, `theme-set`,
  `preview-colors`, `preview-end`, `keybars`, `keybar-show`,
  `keybar-edit`, `keybar-reset`; `checkConfig` covers settings (and a
  missing font file) and user themes. New built-in bar `pocket-edit`.
- App: applies settings (font size in dp, font file, cursor style via
  `getTerminalCursorStyle()`, blinking), pinch zoom saves `font-size`,
  `preview-colors` shows unsaved colours; the cursor blinker follows
  `onEmulatorSet` and programs hiding the cursor.
- Tools: the app's commands moved from `rootfs/bin` to `tools/bin`
  (first on the PATH now); `pocket` became `tools/lib/pocket_terminal`
  (client, cli, models, editors); `menu.conf` with `run COMMAND` items,
  `menu --check`, Settings item, menu colours from the theme; editors
  (curses) for theme/colours, font & cursor, key bars, menu.
- Tests: core (settings, themes, requests), `tests/pocket` (CLI 26,
  models 8, editors 10 driven through a pty), shell (menu.conf,
  `--check`, docs: guide covers every pocket command, bar and theme).
- Docs: agent guide moved to `tools/AGENTS.md`; home files point to it;
  repo AGENTS.md (architecture, Termux cursor notes, tests), README.

**Decisions**
- **`pocket theme set` writes the theme into `colors.properties`**
  (marked `# theme: NAME`) rather than a separate theme setting: one
  file to read and edit, no layering to explain. The image no longer
  ships a colours file (no file = Neon).
- **The menu's own themes are gone:** it uses the 16 basic colours, so
  every terminal theme recolours it (amber and phosphor became terminal
  themes).
- **The menu checks its own file** (`menu --check`); the app checks the
  files it reads; `pocket check` merges both. One parser per format.
- **Built-in bars and themes ship in the tools** (`/opt/pocket-terminal`)
  instead of the image's `/usr/share/pocket-terminal`, so they're
  current.
- **Editors work on a copy until Save** (key bars, menu, colours);
  settings and themes apply on each change, as people expect from a
  settings screen.

**Commits:** `dd64628`, `373544e`, `0d5719a`, and the cursor blinker and
work log commit after them.

### 2026-10-04 (18): step 6 planned; 6.1, the app's tools and `pocket`

**Decided with the owner:** start a small `pocket` now, in step 6;
editors are terminal programs; the aim is that AI coding CLIs can work
on every setting. Plan in "Next".

**Done**
- `core`, test-first: `TarUnpacker` (refactor: the rootfs installer's
  tar code, shared). `ToolsInstaller` (5 tests): unpacks the tools
  archive into `filesDir/tools` when the version changes, writes
  `.version`, keeps the old tools until the new ones are complete.
  `prootLaunch(toolsDir, requestDir)`: mounts the tools at
  `/opt/pocket-terminal`, adds its `bin` to the end of the PATH, sets
  `POCKET_REQUESTS`. `checkConfig()` (6 tests): problems in
  `colors.properties` and each key bar file (also bad bar file names),
  by Debian path. `PocketRequests` (7 tests): answers `ID.req` files
  with JSON `ID.reply` files.
- `tools/bin/pocket` (Python 3, 11 unittest tests against a fake app):
  `check`, `version`, `help`, `--json`, exit codes 0/1/2, times out
  with a clear message when the app doesn't answer, cleans up its
  files.
- App: the service updates the tools before any shell starts (version =
  version code + install time, so every build counts), watches the
  request folder with a `FileObserver` (the activity's 250 ms poll also
  processes requests, in case an event is missed), and a `check`
  reloads colours and key bars without dialogs. A failed tools update
  shows a dialog with the stack trace.
- CI: `pocket tests` step; `scripts/pack-tools.sh` packs `tools/` into
  the APK. `check-tdd.sh`: `tools/` needs `tests/pocket/`.
- Docs: home `AGENTS.md` "Changing settings: `pocket`" (a new
  home-docs test fails if a `pocket` command isn't documented there),
  repo `AGENTS.md` (architecture, tests), README.

**Bug found by the owner:** `pocket: command not found`. Debian's
`/etc/profile` sets root's PATH from scratch, dropping the app's
`/opt/pocket-terminal/bin` (the test only checked the env the app
passes). Fix: the app writes `/etc/profile.d/pocket-terminal.sh` at
start (tested by sourcing it with `sh`).

**Decisions**
- **App-owned tools live outside the rootfs** (mounted from the app's
  files), so they update with the app; the user's Debian is still never
  touched. The rootfs's own app parts (menu, `files`, `keybar`, `play`)
  can move there later (6.5 moves the menu).
- **Python for `pocket` and the editors:** already in the image, JSON
  and curses in the standard library; the games are Python curses too.
- **Request files instead of a socket:** proven by the key bar file,
  no Android socket APIs to spike; one request is a few ms.
- **Config checking stays in `core`** (the app answers `check`), so
  there's one parser per format, not a Python copy.

### 2026-10-04 (17): step 5 rethought: game and program key bars

**Done**
- `core`, test-first (10 more tests): `KeyButton.repeat`. Arrows,
  PgUp/PgDn, Backspace and Delete repeat on their own; any button with
  `Repeat` on its line opts in (`Repeat` alone is "no keys").
  `keyBarNames()` replaces `keyBarName()`: a report can list fallbacks
  (`my-game,game`), invalid names dropped. `loadKeyBar(names)` takes the
  first that exists (user file, then built-in), else the shell's.
- Built-in bars for `neon-rogue` (17 buttons, 2 pages), `neon-drive`,
  `neon-flap` and a generic `game` bar. Nitro (↑) repeats too, like a
  held ↑ key (first test expectation said otherwise; the test was wrong).
- Debian (bats, `tests/shell/play.bats`): `play GAME` runs a game under
  `keybar <file name>,game`; `rogue`, `drive`, `flap` are scripts calling
  `play` (were symlinks); the menu's Games run `play`.
- App: `KeyBarView` repeats held buttons (send on touch, then every
  50 ms after 400 ms); a rebuild of the bar or a page swipe (touch
  cancel) stops the repeat, so a button can't keep repeating with no
  release coming.
- Docs: README rewritten around key bars; home `AGENTS.md` explains
  `play`, fallbacks, `Repeat`, and how programs (or agents setting one
  up) bring their own bar.

**Owner's check found:** `~/games` didn't exist in a fresh install, so
the generic-bar check's `printf … > ~/games/test-game` failed. The image
now creates it (empty).

**Bug found by the owner:** the test game (`read -r x`) kept the menu's
bar until a tab switch. The bar was only rechecked on output, and a
program that starts quietly prints nothing after `keybar` writes the
file. Fix: `MainActivity` also polls the bar file every 250 ms while
visible (one stat; stopped in `onStop`). Not unit-testable (Android
glue); covered by the owner's generic-bar check.

**Owner confirmed on the phone:** game bars and pages, hold-to-repeat
in games/nnn/menu/shell, the generic bar (after the polling fix),
swipes. **Step 5 done.**

**Commits:** `414cc03`, `89ed8cd`, `496f5bb`

### 2026-10-04 (16): step 4, nnn and the key bar

**Done**
- Owner compared the Debian-packaged file managers (nnn, lf, ranger,
  vifm, broot; yazi/xplr not packaged) and tried nnn and lf in the app.
  Decision: nnn, plus an always-visible key bar for its actions.
- 4.1, rootfs (bats, `tests/shell/files.bats` + menu/bashrc/docs tests):
  `files [dir]` = `keybar nnn nnn -de`, with `NNN_BMS` bookmarks (h home,
  d Download, p Pictures, c DCIM, r /) unless the user set their own.
  `keybar NAME cmd…` writes NAME to `$POCKET_KEYBAR_FILE` while cmd runs
  and restores the previous content (EXIT trap; nests; passes the exit
  code; just runs cmd outside the app). The menu's Files item runs
  `files`; the `menu` function runs under `keybar menu`. `EDITOR` and
  `VISUAL` are nano (nnn opens text files with it). nnn installed.
- 4.2, `core` test-first (16 tests): `KeyBar.kt` parses bar files
  (`label = keys`; named keys, single characters, `Ctrl+`/`Alt+`,
  `"quoted text"`, `Ctrl` alone = sticky; bad lines reported).
  `keyBarName()` validates the reported name (else `shell`).
  `loadKeyBar()`: user `NAME.conf` beats the built-in, unknown → shell.
  Built-ins (`shell`, `nnn`, `menu`) are core resources, also shipped to
  `/usr/share/pocket-terminal/keybars/` to copy from.
  `prootLaunch(keyBarFile)` sets `POCKET_KEYBAR_FILE`.
- 4.2, app: `TerminalService` gives each shell
  `/tmp/.pocket-terminal/keybar-N`. `KeyBarView` (under the terminal,
  so above the keyboard) reloads when that file's timestamp changes,
  checked on the visible tab's output, tab switches and `onStart`
  (forced, to pick up edited bar files). Named keys go through
  `TerminalView.handleKeyCode()` (right sequences in cursor-app mode),
  characters through `inputCodePoint()`, text through
  `session.write()`. Sticky Ctrl is `ViewClient.readControlKey()`.
  Haptic tick on press. Problems in a bar file show in a dialog once.
- `applyColors()` now always repaints the views and only skips the
  library update when the scheme is unchanged: a new view (second
  `showTerminal`) was left unpainted before.
- Docs: README File manager + Key bar sections and roadmap item 4; home
  `AGENTS.md` documents `files`, bookmarks, key bars and their format.

**Decisions**
- Bar changes travel through a per-tab file, not an escape code: the
  library drops unknown OSC codes with no hook for the app.
- Built-in bars live in the app (core resources), so app updates can
  improve them without a rootfs migration; users override per bar.
- Not yet: key repeat on held buttons (arrows), opening non-text files
  in Android apps (needs a Debian → app channel).
- 4.3 (owner's feedback): `keyBarPages()` in core, test-first (4
  tests): always two rows (an empty second row keeps its height, so the
  terminal never resizes), first half on top, extra buttons on more
  pages. The app picks buttons per row from the widest label (min 44 dp)
  and the bar's width; buttons in a row share the width. `KeyBarView` is
  now a `FrameLayout` showing one page; a sideways swipe past the touch
  slop switches pages (taps still reach buttons), dots drawn on the
  bottom edge. nnn's bar gained ↑ ↓; built-in bars reordered so the
  halves make sense as rows.

**Owner confirmed on the phone:** all 4.2 and 4.3 checks (two rows,
↑ ↓ in nnn, no terminal resize on bar changes, pages with dots and
swipe, landscape). **Step 4 done.**

**Commits:** `4c11f36`, `796502a`, `d3ff5f1`

### 2026-10-03 (15): step 3.4, agent docs in the home folder

**Done**
- `rootfs/root/AGENTS.md` (+ `CLAUDE.md` importing it), shipped as
  `/root/AGENTS.md`: how this Debian runs (proot, fake root, no
  systemd/sudo, static `/proc` files, network, apt, storage), and a table
  of the setup's files (`.bashrc`, `starship.toml`, colours file, menu
  and its state, games) with how to change each: colours format and
  apply-on-return, tab titles via OSC 0, adding games in `~/games`,
  keeping the app's `PROMPT_COMMAND`, app-owned paths that updates may
  replace.
- Test-first (3 bats tests, `tests/shell/home-docs.bats`): `CLAUDE.md`
  imports `AGENTS.md`; every file in `rootfs/root/` and the other
  user-facing paths are mentioned in `AGENTS.md`, so the docs can't
  silently fall behind. Repo AGENTS.md's design rule points at them.

**Owner's first check:** `less` didn't exist (the base image has no pager
or editor), so `less` and `nano` are now installed. `ls
/storage/emulated/0` lists the phone's folders, so the docs' claim that
storage needs a permission was wrong; replaced with the path.

**Owner confirmed on the phone** (after clearing data), together with
step 3's final checks: `CLAUDE.md` imports `AGENTS.md`, `less` and `nano`
work, menu on the first tab only, starship/eza/folder titles, no debconf
warnings, tab folder restore after force-stop. **Step 3 done.**

**Commits:** `4e53912`, `51dacd8`

### 2026-10-03 (14): step 3.3, launcher menu

**Done**
- `rootfs/bin/menu` → `/usr/local/bin/menu`: the owner's Termux menu,
  ported to run inside Debian. Main: Terminal, Files (`mc ~`), Games,
  System, Exit. System: Update all (`apt update && apt upgrade -y`),
  System info, Theme (neon/amber/phosphor). State in
  `~/.local/state/pocket-terminal/menu`. Games: executables in
  `/opt/neon-games` and `~/games` (`MENU_GAME_DIRS` overrides), labelled
  from file names, so the games are now installed as `neon-rogue.py`,
  `neon-drive.py`, `neon-flap.py`. Sets the tab title to `Menu`.
  Sourcing it only defines functions (for tests).
- Dropped from the Termux version: the Termux/Debian split, Back up
  Debian (used proot-distro), Edit menu (the menu is now a system file a
  later migration may replace), uptime (the app's `/proc/uptime` is a
  static fake), Termux/Android lines in the splash and System info
  (`getprop` isn't reachable from Debian). Splash only with `--boot`.
- Root's `.bashrc`: `menu` function (exit code 10 = Exit = close the
  shell, so the tab); runs `menu --boot` once when `POCKET_MENU` is set,
  then unsets it.
- `core`, test-first: `prootLaunch(openMenu = true)` adds `POCKET_MENU=1`
  (2 tests). `TerminalService` passes it only for the fresh-start tab
  (no saved tabs), not for new, restored or restarted tabs.
- Tests: 13 bats tests for the menu (`tests/shell/menu.bats`: items,
  games, state, theme, dry-run key presses for q/Exit/Files/Games/
  Update, splash), 5 more for the `.bashrc` hook. `check-tdd.sh` pairs
  `rootfs/bin/` with `tests/shell/`.

**Gotcha: bats-lite gave false passes.** It ran each test inside
`if ( … )`, where bash ignores `set -e` even in the subshell, so only a
test's last command could fail it. CI's real bats caught a wrong
expectation (game order). Fixed; the 3.2 `.bashrc` tests were rerun and
pass for real. The menu now sets `LC_COLLATE=C`, so games are listed in
the same order whatever the locale.

**Not ported, maybe later:** a "Claude Code" item (README: offer the
official installer, don't bundle; belongs with AI agent support), a
backup that works without proot-distro.

**Owner confirmed on the phone:** all the 3.3 checks above pass.

**Commits:** `726ade9`, `9969039`

### 2026-10-03 (13): step 3.2, customized rootfs

**Done**
- `rootfs/Dockerfile`, built by `scripts/build-rootfs.sh` with buildx for
  arm64 (CI adds `setup-qemu-action` and `setup-buildx-action`), on the
  `debian:trixie` digest it pulled (recorded in `debian-rootfs.txt` as
  before). Installs `apt-utils dialog eza mc python3 starship`
  (no recommends; apt lists removed). Copies `rootfs/root/` (`.bashrc`,
  `.config/starship.toml`) into `/root`, the Neon colours file from
  `core`'s resources (named build context `neon`) to
  `/root/.config/pocket-terminal/colors.properties`, and the games to
  `/opt/neon-games/` with `rogue`, `drive`, `flap` in `/usr/local/bin`.
- Root's `.bashrc`, test-first with bats (7 tests in
  `tests/shell/bashrc.bats`): eza aliases only when eza exists; tab title
  = folder name (`~` at home) via `pocket_set_title`; starship init with
  `starship_precmd_user_func`. Tested: the app's `PROMPT_COMMAND` (tab
  folder reporting) still runs with and without starship (starship moves
  it into `STARSHIP_PROMPT_COMMAND` and evals it). `LANG`, and
  `~/.local/bin` first on `PATH`.
- CI runs `bats tests/shell` after the Kotlin tests; `check-tdd.sh` pairs
  `rootfs/root/` with `tests/shell/`.
- `scripts/bats-lite.sh`: tiny bats stand-in for the phone (see AGENTS.md).

**Gotcha:** real bats can't run on the phone. It uses process
substitution, and proot-distro's `/dev/fd` is a frozen copy of one
process's fd folder (`cat <(echo hi)` fails). Might affect the app's own
Debian too; on the check list.

**Decisions**
- Games live in `/opt/neon-games/` (system-wide, not in `/root`), so a
  later app update can replace them with a migration without touching
  user files; scores stay in `~`.
- Title is the folder name only: tabs are narrow on a phone.
- No QEMU-built image locally (no docker on the phone): the Dockerfile is
  verified by the CI build and the owner's checks.

**Owner confirmed on the phone** (after clearing data): starship prompt
with icons, folder-named tabs, eza aliases, all three games, no debconf
warnings on `apt install`, Neon colours file in place, `mc`, tab folder
restore after force-stop, and `cat <(echo ok)` works in the app's Debian
(so the `/dev/fd` problem is only in the dev Debian). APK 50 MB (rootfs
`.tar.xz` 45.6 MB, was 28.4).

**Commits:** `de39952`

### 2026-10-03 (12): step 3.1, neon colours and font

**Done**
- `core`, test-first (9 tests): `ColorScheme.kt`. `parseColorScheme()`
  reads Termux's `colors.properties` (`background`, `foreground`,
  `cursor`, `color0`–`color255`, `#rrggbb`, `#`/`!` comments) on top of a
  base scheme; bad lines are skipped and reported with line numbers.
  `NEON` comes from the resource `neon.colors.properties` (the owner's
  Termux file with a user-facing header). `loadColorScheme(file)` lays a
  file over neon (neon alone if missing). `stripColors()` derives the tab
  strip colours: background, `color0` selected, cursor accent, `color6`
  mark, `color7` text.
- App: `MainActivity.applyColors()` reads
  `~/.config/pocket-terminal/colors.properties` from the user's Debian on
  start and every time the app comes back to the front; only a changed
  scheme is applied (writes `TerminalColors.COLOR_SCHEME`, resets each
  tab's colours, recolours strip and background), so colours programs set
  by escape codes survive app switches. Problems show in a dialog once.
- Font: JetBrains Mono Nerd Font Mono for the terminal and the tab strip.
  `scripts/fetch-font.sh` downloads Nerd Fonts v3.5.1's
  `JetBrainsMono.tar.xz` (sha256-pinned) in CI and unpacks the TTF and
  `OFL.txt` into `app/src/main/assets/fonts/` (gitignored). The owner's
  Termux font is byte-identical to the release file (same sha256).
  Builds without the font fall back to monospace.

**Decisions**
- Font downloaded in CI, not committed: same pattern as proot and the
  rootfs; the hash pin keeps it reproducible. APK grows by ~1.2 MB
  (2.6 MB TTF, compressed).
- Reading the user's colours file now rather than waiting for 3.2: it
  was small, and lets the owner try theme edits right away.
- No new shell tests: `fetch-font.sh` is a build script verified by the
  CI build, like `build-proot.sh`.

**Not verified:** `app/` can't compile here; written against the
library's published sources (v0.118.3). Risk to watch: `NEON` loads a
Java resource from the `core` jar; if Android doesn't package it, the app
crashes on start and the crash dialog will say so.

**False alarm, white background:** the first palette check command in
the list left SGR background colour 15 (white) set, so typed text and a
`clear` came out white. Not an app bug; the command now ends with
`\e[0m`. On the way, the window behind the terminal is now painted in
the scheme background too, like Termux (the library never paints
default-background cells). `scripts/deliver.sh` now waits for the run
of HEAD: right after a push it had picked the previous run.

**Owner confirmed on the phone:** neon palette, Nerd Font icons, strip
colours, pinch zoom, a colours file in Debian recolours every tab on
return, a bad line shows the problems dialog, deleting the file brings
neon back.

**Commits:** `b39ecac`, `3339497`, `e4e7ab6`

### 2026-10-03 (11): rename tabs

Owner asked for renaming without a hardware keyboard before starting
step 3, so the README's "long-press a tab for rename" came forward (the
rest of that long-press menu, colour/duplicate/close others, is still
later).

**Done**
- `core`, test-first (5 tests): `Tabs.renameInput()` (the shown title)
  and `Tabs.applyRenameInput()` (trimmed; blank = automatic title; saving
  the shown title unchanged doesn't pin it), `TabAction.Rename` on
  Ctrl+Shift+R (Windows Terminal has no default; Ctrl+R still reaches
  bash).
- Long-press a tab → "Rename tab" dialog with the keyboard up; IME Done
  saves; **Automatic** button only on renamed tabs.

**Owner confirmed on the phone:** rename shows in the strip; clearing goes
back to "Tab N"; renamed tabs keep their names after a force-stop (the
step 2 restore check that couldn't be run before); a rename beats the
shell's OSC title and **Automatic** brings the shell title back; typing
works after cancelling.

**Commits:** `e37ac70`

### 2026-10-03 (10): step 2.5, restore tabs; step 2 done

**Done**
- `core`, test-first: `TabState.kt` (`SavedTabs`, plain-text
  `serialize()` / `parseSavedTabs()`, `Tabs.snapshot()` /
  `Tabs.restore()`), `HostPath.kt` (`hostPath()`), `prootLaunch(cwdFile)`.
  Broken or unknown-version files are ignored (fresh start).
- `TerminalService` saves `filesDir/state/tabs` on every tab change and
  when the activity stops; a cold start restores order, renames, selected
  tab and folders, with fresh shells. Restored background tabs start when
  first shown; their folder is kept in `knownCwd` until then. A missing
  folder falls back to `/root`. Exit and closing the last tab delete the
  file. Enter-restart of a failed shell reuses its folder.

**Gotcha: proot hides the shell's folder from the host.** The first
version read `/proc/<shell pid>/cwd`: on the phone it always showed the
app's files dir (the cwd proot itself was started in), whatever the
shell `cd`'d to, because proot tracks the guest cwd itself. (It also
spells app data `/data/data/…` where `filesDir` says `/data/user/0/…`.)
Found with a temporary on-device trace written to the app's external
files dir, readable from Debian under `/storage/emulated/0/Android/data/`
(a handy debugging channel, since there's no logcat). Now each shell
reports its folder: `PROMPT_COMMAND` writes `$PWD` to
`/tmp/.pocket-terminal/cwd-N`, which the service reads.

**Owner confirmed on the phone:** after force-stop, 3 tabs come back with
the same tab selected and `pwd` = `/etc` and `/tmp/x` in the right tabs;
a deleted folder opens in `/root` without errors; Exit then reopen gives
one fresh tab; `PROMPT_COMMAND` shows the printf line, prompt otherwise
unchanged.

**Not tested:** restoring renamed tabs (no rename UI; covered by core
tests). Hardware keyboard shortcuts (see "Hardware keyboard checks").

**Commits:** `d860de5`, `3f18912`, `eaed2ae`

### 2026-10-03 (9): steps 2.3 and 2.4, tab strip, shortcuts, marks

**Done**
- `core`: `TabShortcuts.kt`, test-first (7 tests): Windows Terminal's
  default tab shortcuts → `TabAction`, exact modifier match so other
  combos reach the shell.
- Tab strip above the terminal: title (max 160 dp, ellipsized) and × per
  tab, then +; selected tab highlighted (purple background, pink text);
  scrolls sideways and keeps the selected tab in view. Strip views aren't
  focusable, so tapping them leaves input on the terminal.
- Cyan ● (output) and 🔔 (bell) on background tabs, from the `Tabs` marks
  (2.4, folded in since the model already had the flags).
- `ViewClient.onKeyDown` runs tab shortcuts before the terminal sees the
  key; key repeats are consumed but ignored.

**Owner confirmed on the phone:** + opens and selects tabs; `top` keeps
running across switches; ● and 🔔 appear on background tabs; closing a
middle tab selects its right neighbour; closing the last tab closes the
app; "Tab N" titles; typing still reaches the terminal after tapping the
strip. Hardware keyboard shortcuts not tested (no keyboard), listed under
"Hardware keyboard checks".

**Commits:** `3560f8a`

### 2026-10-03 (8): step 2.2, tab model and exit rule

**Done**
- `core`: `Tabs<S>` and `closesOnExit()`, test-first (21 tests): open
  after current, close selects right else left, wrap-around next/previous,
  move left/right, shell title / rename / "Tab N" titles with stable
  numbers, activity and bell marks for background tabs, change callback
  (marks only report when they flip, since output arrives constantly).
- `TerminalService` holds its sessions in `Tabs`. Exit code 0 closes the
  tab; other codes (negative = signal) keep it open with the library's own
  `[Process completed (code N) - press Enter]` (the library already prints
  it, so the app adds nothing). Closing the last tab = `exit()`.
- Notification updates use `NotificationManager.notify()`; only
  `onStartCommand` calls `startForeground()`.

**Notes**
- First push failed to compile in CI: `var tabs = newTabs()` where the
  lambda in `newTabs()` reads `tabs` is a recursive type inference error.
  Fixed with explicit types (`01cb1ac`). `app/` can't compile on the
  phone, so read Kotlin carefully for inference cycles like this.

**Owner confirmed on the phone:** `exit` closes the app and the
notification; `exit 1` stays open with the message and Enter restarts;
killing a child shell changes nothing; notification Exit, Back and
rotation still work.

**Commits:** `ed95a18`, `01cb1ac`

### 2026-10-03 (7): step 2.1, background service

**Done**
- `core`: `runningTerminalsText(count)` for the notification, test-first
  (3 tests).
- `TerminalService`: foreground service (`specialUse`, with the subtype
  property) that owns the sessions. Notification "N terminal(s) running",
  low importance, opens the app; **Exit** kills all sessions, stops the
  service and calls `finishAndRemoveTask()` on the attached activity.
  Returns `START_NOT_STICKY`: a restarted service can't bring dead shells
  back (2.5 restores tabs instead).
- `MainActivity` starts the service with `startForegroundService()` while
  visible, binds, and shows the service's session. `onDestroy()` only
  unbinds. Back moves the task to the back (`OnBackInvokedDispatcher` on
  API 33+, `onBackPressed()` below). Asks for `POST_NOTIFICATIONS` on API
  33+; works without it.
- `SessionClient` belongs to the service and forwards to the attached
  activity, so sessions never hold a destroyed activity.
- Crash reporter moved to `TerminalApp` (`Application`) so it covers the
  service too. White `>_` notification icon (`ic_notification.xml`).

**Owner confirmed on the phone:** notification permission prompt and
"1 terminal running" with Exit; `htop` keeps running after Back and
reopening; rotation keeps the session; shell alive after a few minutes
with the screen off; Exit removes app and notification, reopening gives a
fresh shell.

**Notes**
- `scripts/deliver.sh` once failed with `unexpected EOF` from the GitHub
  API while polling (network hiccup, build was fine); rerunning it worked.

**Commits:** `8dbace9`

### 2026-10-03 (6): step 1.5, Debian starts; step 1 done

**Done**
- The terminal runs `prootLaunch()`'s command (login bash in Debian, fake
  root) instead of Android's `sh` (`0fdf4eb`).
- `apt update` failed to resolve hosts: the app lacked
  `android.permission.INTERNET`, so Android denied every socket (`52e497d`).
- `htop` failed with `Cannot open /proc/stat: Permission denied`.
  `writeFakeProc()` (core, test-first) writes static stand-ins for
  `/proc/{loadavg,stat,uptime,version,vmstat}` into `filesDir/fake-proc`,
  only for files the app can't read (probed at each start with a real
  read), and `prootLaunch(fakeProc = …)` binds them over the originals.
  On the owner's Android 16 all five are blocked (`9e5c713`).
- A `workflow_dispatch` rebuild installed as an update kept `htop`.

**Owner confirmed on the phone:** short opening screen, `apt install`
works, `htop` draws, updates keep installed packages. All of step 1's
"Done when" checks pass.

**Decisions**
- Fake `/proc` files live outside the user's Debian (app-managed, rewritten
  each start), so nothing is written into the user's rootfs.

**Commits:** `0fdf4eb`, `52e497d`, `9e5c713`

### 2026-10-03 (5): step 1.4, first-run install

**Done**
- `core`: `RootfsInstaller(baseDir)`, test-first (13 tests). Unpacks the
  `.tar.xz` (commons-compress 1.28.0 + xz 1.12) into `debian.partial`,
  writes `etc/resolv.conf` (1.1.1.1, 8.8.8.8) and `etc/hosts`, then renames
  it to `debian`. Hard links become copies; symlinks are kept verbatim;
  device nodes/FIFOs are skipped; the owner always keeps rw (and x on
  dirs) so apt can replace files later. Refuses `..` paths and writing
  through symlinks; refuses to install over an existing `debian`; clearing a
  stale `debian.partial` doesn't follow symlinks. Progress in percent of
  compressed bytes read.
- Tried it on the real CI rootfs here (throwaway test, not committed):
  4414 files, 294 symlinks, ~3 min under proot on the phone; looked right.
- `app`: `MainActivity` shows a progress screen and unpacks on a background
  thread if `filesDir/debian` is missing, then shows the terminal. Errors
  show the stack trace on screen with a Retry button.

**Decisions**
- "Installed" = the final `debian` dir exists, made by an atomic rename at
  the end, instead of a separate marker file: same guarantee, one less
  thing to get out of sync.

**Owner confirmed on the phone:** progress screen then terminal, ~5 s;
`os-release` says trixie; `resolv.conf` written. Interrupt-and-reopen not
tried yet.

**Commits:** `3aede1b`

### 2026-10-03 (4): step 1.3, Debian rootfs in the APK

**Done**
- `scripts/build-rootfs.sh <out-dir>`: `docker pull --platform linux/arm64
  debian:trixie`, `docker create` + `docker export` (no container runs, so
  no QEMU), drops `.dockerenv`, `xz -T0 -9`. Also writes
  `debian-rootfs.txt` (`image=<repo digest>`, `built=<UTC time>`).
- CI runs it into `app/src/main/assets/` (gitignored), ~50 s, no cache.
- `androidResources.noCompress += "xz"`; the APK artifact uploads with
  `compression-level: 0`.
- Sizes: tar 146 MB, `.tar.xz` 28.4 MB (stored), APK 30 MB.

**Notes**
- The recorded digest is the multi-arch index digest of `debian:trixie`
  (`RepoDigests`), which identifies the release; the arm64 manifest digest
  differs.
- The tar has docker's empty `etc/hostname`, `etc/hosts`, `etc/resolv.conf`
  placeholders; 1.4 writes real `resolv.conf` and `hosts`.

**Commits:** `5acb33f`

### 2026-10-03 (3): step 1.2, proot in the APK

**Done**
- `scripts/build-proot.sh <out-dir>` builds Termux's proot `v5.1.107.96`
  (the release Termux ships) for arm64, API 26, with talloc 2.5.0 linked
  statically. CI runs it with the runner's NDK (29.0.14206865), caches
  `build/proot` keyed on the script hash + NDK version, and copies the two
  `.so` files into `app/src/main/jniLibs/arm64-v8a/` (gitignored).
- `packaging.jniLibs.useLegacyPackaging = true`, so they're extracted to
  `nativeLibraryDir` and can be executed.
- The test shell has `nativeLibraryDir` on `PATH` plus `PROOT_LOADER` and
  `PROOT_TMP_DIR` (`cacheDir/proot`).
- Confirmed on the phone: `libproot.so --version` prints the banner
  (`process_vm = yes, seccomp_filter = yes`); `libproot.so -0 /system/bin/id`
  prints `uid=0(root)`, so tracing through the unbundled loader works.

**Decisions / gotchas**
- Loader unbundled (`PROOT_UNBUNDLE_LOADER`, path given via `PROOT_LOADER`):
  the bundled loader is extracted to app storage, where exec is forbidden.
- The 32-bit loader gets built (the makefile requires it on arm64) but isn't
  shipped: the Pixel 10 has no 32-bit support, and the rootfs is arm64-only.
- No `libandroid-shmem` (Termux links it for SysV shm); proot's own sysvipc
  extension is enough for now.
- Build fixes: proot's `ashmem_memfd.c` lacks `#include <string.h>` (patched
  in by `sed`; hence the `-dirty` version suffix; force-including it
  globally breaks the freestanding loader). talloc needs libreplace's objects
  in the static archive (`rep_memset_explicit`; no `memset_explicit` before
  API 34).
- Building locally with Termux's clang fails differently (its headers hide
  `memset_explicit` but waf's link check finds it) and takes ~20 min under
  proot; iterate in CI instead.
- No bats tests for the build script: it's verified by the CI build and the
  on-device check above.

**Commits:** `eef890e`, `2263916`, `515cfc9`, `0bda401`

### 2026-10-03 (2): step 1.1, terminal view with a local shell

**Done**
- Added Termux `terminal-view:v0.118.3` from JitPack (repo limited to the
  `com.github.termux.termux-app` group). API checked against the published
  sources jar, since `app/` can't compile on the phone.
- `MainActivity` is now a `TerminalView` + `TerminalSession` running
  `/system/bin/sh` (cwd and `HOME` = `filesDir`). `TerminalClients.kt` holds
  the session/view client callbacks: tap shows the soft keyboard, pinch
  changes the font size, clipboard copy/paste, Enter restarts a finished shell.
- Edge-to-edge (targetSdk 35+ ignores `adjustResize`): a `FrameLayout`
  around the terminal pads itself by the system bar and IME insets.
  `TerminalView` ignores its own padding (it draws from 0,0), so padding the
  view directly put the first row under the status bar.
- `configChanges` on the activity so rotation/keyboard changes don't recreate
  it and kill the shell (the background service in step 2 replaces this).
- Uncaught exceptions are written to `filesDir/last-crash.txt` and shown in
  a dialog on next launch (no logcat on the dev phone).
- `abiFilters = arm64-v8a`: proot and the rootfs will be arm64 only.

**Decisions**
- No spike branch: this step is pure Android glue with no logic for `core`,
  so there was nothing to rebuild test-first. No tests added for it.

**Owner confirmed on the phone:** keyboard, rendering, colours, pinch zoom,
rotation and shell restart work. The first build drew under the status bar;
fixed in `d7952ea` and confirmed (status bar and keyboard both clear).

**Commits:** `40dc32a`, `d7952ea`

### 2026-10-03: scope, build pipeline, TDD

**Done**
- Wrote the product scope in `README.md` with the owner: built-in Debian,
  Windows Terminal-style tabs, in-app keyboard, phone-sized keyboard-driven
  terminal file manager (`pocket files`), customization, shareable profiles,
  Android integration (incl. location, camera, sensors), AI agent support,
  development boards (later). Roadmap of 11 steps.
- Android app skeleton, GitHub Actions build, shared debug signing key.
  First APK installed and confirmed working on the phone.
- `AGENTS.md` (architecture, environment, design rules), `CLAUDE.md`
  importing it, `scripts/deliver.sh` for the build → install loop.
- TDD setup: plain-Kotlin `core` module, Gradle wrapper, pre-commit hook,
  CI TDD check, `scripts/setup.sh`, Claude Code `SessionStart` hook.
  `prootLaunch()` built test-first as the first example.

**Decisions**
- Build in GitHub Actions, not on the phone (no arm64 `aapt2`). `core`
  tests run on the phone.
- Reuse Termux's terminal libraries and its proot fork; don't fork the
  Termux app.
- Debian rootfs from the official `debian:trixie` arm64 Docker image.
- App ID `io.github.est4s.terminal` is name-neutral; "Pocket Terminal" is a
  working name (taken on the Play Store), to be renamed before release.
- Keyboard-first everywhere; tap support later, behind a settings toggle.
- Owner tried Android's built-in Linux Terminal (VM): too slow. The app
  stays proot-based.
- Repo is private for now; docs-only pushes skip CI to save build minutes.

**Commits:** `a658457` … `6cb11fd`

**Open issues:** none.
