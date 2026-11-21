# The phone

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx vibrate [MS]` | vibrate the phone, 300 ms unless given (1 to 5000); `--strength PCT` |
| `mynx vibrate effect NAME` | one of Android's effects: `click`, `double-click`, `tick`, `heavy-click` |
| `mynx vibrate wave STEP…` | a pattern of steps (`MS`, `MS@PERCENT`); `--repeat` until Ctrl+C |
| `mynx vibrate chain PRIM…` | Android's primitives one after another (`click`, `thud@50+100`, …) |
| `mynx vibrate list` | what the phone's vibrator can do: strength, effects, primitives |
| `mynx vibrate stop` | stop any vibration |
| `mynx clipboard get` | print the phone's clipboard (the app must be on screen) |
| `mynx clipboard set [TEXT]` | copy TEXT to the phone's clipboard, or stdin when there's no TEXT |
| `mynx share FILE…` | send files to another app through Android's share sheet (the app must be on screen) |
| `mynx share --text [TEXT]` | send TEXT, or stdin when there's no TEXT, to another app |
| `mynx location` | where the phone is: one fix; `--stream` keeps printing them |
| `mynx sensor list` | the phone's sensors, with their values and units |
| `mynx sensor NAME` | one reading of a sensor; `--stream` keeps printing them |
| `mynx battery` | the battery: charge, charging, temperature, health, battery saver; `--stream` prints changes |
| `mynx camera FILE` | take a photo with the phone's camera app, saved to FILE; `--quick front\|back` snaps one with no screen |
| `mynx torch on [PERCENT]\|off` | the phone's flashlight |
| `mynx rotation lock [portrait\|landscape]` | stop the screen turning while the program that ran it runs; `unlock`, `status` |
| `mynx audio play FILE` | play a sound file through the phone's speaker, until it ends |
| `mynx audio record FILE` | record from the microphone to FILE (`.m4a`, `.aac`, `.ogg`, `.opus`, `.wav`) until Ctrl+C or `--seconds N` |
| `mynx sound` | whether the sound device is on; `mynx sound start` starts it again, `mynx sound install` installs it |
| `mynx open FILE` | open a file in mynx's viewer or another app (see "Files and viewers") |

Commands for the phone itself, and the sound device:

- `mynx vibrate [MS]`: a buzz, e.g. `make && mynx vibrate`. It
  returns at once. `--strength PCT` (1 to 100) sets how strong, on
  phones whose vibrator has strength control (`mynx vibrate list`
  says); elsewhere it's refused, saying why.
- **Vibration patterns:** these wait until the vibration ends, and
  Ctrl+C stops it. Only one vibration plays at a time: a new one, or
  `mynx vibrate stop` (from any tab), ends the one before, whose
  `mynx` then returns. Nothing the phone can't do is skipped
  silently: it's refused with the reason (`--json`: `{"ok": false,
  "error": …}`).
  - `mynx vibrate effect NAME`: Android's predefined `click`,
    `double-click`, `tick` or `heavy-click` (Android 10+), as the
    phone tunes them.
  - `mynx vibrate wave STEP…`: steps one after another, each `MS`
    (full strength), `MS@PERCENT`, or `MS@0` for a pause:
    `mynx vibrate wave 200 100@0 200 100@0 600@40`. Up to 60 s;
    `--repeat` plays it again and again until Ctrl+C or `mynx vibrate
    stop`. Strengths other than 0 and 100 need strength control.
  - `mynx vibrate chain PRIM…`: Android's haptic primitives (Android
    11+), each `NAME[@PERCENT][+MS]`, `+MS` being a pause before it:
    `mynx vibrate chain slow-rise click+100 thud@60`. Names: `click`,
    `tick`, `quick-rise`, `slow-rise`, `quick-fall`, and from Android
    12 `low-tick`, `thud`, `spin`. Phones have only some of them.
  - `mynx vibrate list`: whether the vibrator has strength control,
    each effect (yes, no, or unknown before Android 11) and each
    primitive (with its length in ms from Android 12). `--json` gives
    `amplitude_control`, `effects` (name: true, false or null) and
    `primitives` (name: `supported`, `ms`).
  - A finished `--json` answer has `ms`, how long it played (an
    effect's is a close guess: Android doesn't say), or `"stopped":
    true` when another vibration or `stop` ended it.
  - Vibrations follow the phone's media vibration setting (Android
    13+), like other apps'.
- `mynx clipboard set [TEXT]` copies TEXT, or what's piped in, line
  breaks and all: `git log -1 | mynx clipboard set`.
  `mynx clipboard get` prints it as it is, with no line break added
  unless it goes to the terminal. Android only lets the app on screen
  read the clipboard, so `get` fails while the app is in the
  background; `set` works anyway. `android-clipboard off` blocks both.
- `mynx share FILE…` opens Android's share sheet to send files to
  another app (mail, chat, …): `mynx share report.pdf photo.jpg`.
  `mynx share --text TEXT` sends text, or what's piped in. The app
  must be on screen. `android-share off` blocks it.
- `mynx location` prints one fix: `60.1695213, 24.9354471 ±12 m
  network 08:41:02` (latitude, longitude, accuracy, where it came
  from, time). It takes the first fix from GPS or the network;
  `--gps` waits for GPS only. It gives up after 60 s (`--timeout
  SECONDS`, up to 300). `--stream` prints a fix about every 5 s
  (`--every SECONDS`) until Ctrl+C. `--json` gives `latitude`,
  `longitude`, `accuracy`, `altitude`, `speed`, `bearing` (null when
  unknown), `provider` and `time` (ms since 1970); with `--stream`,
  one object per line. The first time, Android asks to allow location
  (only while the app is in use), so the app must be on screen to
  start it; a stream keeps going in the background. `android-location
  off` blocks it.
- `mynx sensor list` names the phone's sensors: `accelerometer`,
  `gyroscope`, `magnetic-field`, `light`, `proximity`, `pressure`,
  `gravity`, `rotation-vector`, `step-counter`, … as the phone has
  them, and `compass` (azimuth clockwise from magnetic north, pitch
  and roll, in degrees, worked out from `rotation-vector`). `mynx
  sensor NAME` prints one reading: `x=0.1235 y=9.8067 z=-0.5 m/s²`,
  or `108 lx` for one value; it gives up after 10 s (`--timeout
  SECONDS`, up to 60). `--stream` prints up to 10 readings a second
  (`--rate HZ`, 1 to 200), each line starting with the time, until
  Ctrl+C; sensors like `light` and `proximity` only send when the
  value changes. `--json` gives `sensor`, `values` (by name), `unit`,
  `accuracy` (`high`, `medium`, `low`, `unreliable`, `no-contact`) and
  `time` (ms since 1970); with `--stream`, one object per line. The
  step sensors need Android's "physical activity" permission, asked
  the first time (app on screen). Readings keep coming in the
  background. `android-sensors off` blocks it.
- `mynx battery` shows the battery: level, status and what it's
  plugged into (with the time until full while charging),
  temperature, health, voltage, current (in or out, and on average),
  charge left in mAh, charge cycles, the technology, battery saver
  and the phone's thermal status (`none` to `shutdown`: how hard it
  is slowing down to cool off). Lines the phone doesn't fill are left
  out. `--stream` prints a line whenever the level, status, plug,
  health, temperature, battery saver or thermal status changes, until
  Ctrl+C; `--every SECONDS` (1 to 3600) adds one at least that often,
  for logging the current. `--json` gives `percent`, `status`
  (`charging`, `discharging`, `full`, `not-charging`, `unknown`),
  `plugged` (`ac`, `usb`, `wireless`, `dock`, null), `health`,
  `temperature` (°C), `voltage` (V), `current` and `current_average`
  (A, positive into the battery), `charge` (mAh), `time_to_full`
  (seconds), `cycles`, `technology`, `battery_saver`, `thermal` and
  `time` (ms since 1970), null for what the phone doesn't say; with
  `--stream`, one object per line. It needs no permission and works
  in the background. How phones report the current varies; it's
  shown as they say, except that while discharging it always flows
  out. Linux tools see the battery too: Android hides
  `/sys/class/power_supply`, so the app keeps a stand-in there
  (`battery`, `ac`, `usb`, `wireless`, in the kernel's names and
  units) for starship, `acpi`, htop or Python's psutil, updated on
  every change and every 30 s.
- `mynx camera FILE` opens the phone's camera app; the photo you
  take is saved to FILE as a JPEG (replacing it), and `mynx` prints
  `Saved /root/photo.jpg (2.3 MB)`. Backing out of the camera app
  leaves FILE alone ("no photo was taken"). `--quick back` (or
  `front`) takes the photo straight away with no screen, for scripts
  and agents: exposure and focus get a moment to settle, then it's
  saved, within a few seconds. `--json` gives `file` and `bytes`. Android asks
  to allow the camera the first time (for both ways), and only lets
  the app on screen use it, so the app must be on screen.
  `android-camera off` blocks it.
- `mynx torch on` and `mynx torch off` turn the flashlight on and
  off. `mynx torch on 30` sets a strength
  in percent, on phones whose flashlight has levels (Android 13+).
  Both work with the app in the background. Opening the camera turns
  it off.
- `mynx rotation lock` stops the screen turning with the phone,
  e.g. for a program that reads the tilt sensors: it stays as it is,
  or `mynx rotation lock portrait` (`landscape`) turns it to that
  side. The lock belongs to the program that ran `mynx` (the shell,
  when you type it) and ends when that program ends, so a crash
  can't leave the screen stuck; `--pid PID` gives it to another
  process. `mynx rotation unlock` ends every lock; `mynx rotation`
  says whether it's locked (`--json`: `locked` is `no`, `current`,
  `portrait` or `landscape`). From a program: run `mynx rotation
  lock` once at start.
- `mynx audio play FILE` plays a sound file (mp3, ogg, wav, m4a,
  flac, … whatever Android can play) and returns when it ends; Ctrl+C
  stops it. It works with the app in the background. `--json` gives
  `file` and `seconds`.
- `mynx audio record FILE` records from the microphone until Ctrl+C,
  or for `--seconds N`, then prints `Saved /root/memo.m4a (240 KB,
  15 s)`. The file's ending picks the format: `.m4a` or `.aac` (AAC),
  `.ogg` or `.opus` (Opus, Android 10+), `.wav` (16-bit PCM, for
  tools like whisper.cpp). Mono; `--rate HZ` (8000 to 48000) sets the
  sample rate, e.g. `mynx audio record --rate 16000 note.wav`. FILE
  only changes once the recording is complete. `--json` gives `file`,
  `bytes` and `seconds`. Android asks to allow the microphone the
  first time, and only lets the app on screen start recording; a
  recording keeps going in the background (Android shows its
  microphone indicator). `android-microphone off` blocks it.
- **The sound device:** programs here play sound themselves, through
  the phone's speaker: `mpv`, `aplay`, `paplay`, `sox`'s `play`,
  games, anything that uses PulseAudio or ALSA. The app runs
  PulseAudio in the background (outside the tabs) and plays what it
  sends; `PULSE_SERVER` in every tab points programs at it
  (`unix:/tmp/.mynx/sound/native`). Don't start your own
  `pulseaudio`. `mynx sound` says whether it's on; `mynx sound
  start` starts it again and returns once programs can connect;
  Debians set up before the sound device need `mynx sound
  install` once (it runs `apt-get install
  pulseaudio …`, asking first; `--yes` skips that). PulseAudio's
  output is in `/tmp/.mynx/sound/server.log`.
  `sound-device off` turns it off. Programs record through it too
  (`arecord`, `parecord`, `sox`'s `rec`, whisper.cpp, …): the app
  opens the phone's microphone only while a program records, and
  its notification names the programs ("Microphone: arecord").
  Programs get silence, and the notification says why, when
  `android-microphone` is off, when the microphone isn't allowed
  yet, or when recording starts while the app is in the background
  (Android only lets the app in use start the microphone): open the
  app and it starts. Recording is 48 kHz mono.
- **Sharing to Debian:** the app is in Android's share sheet too.
  Files shared to it from other apps are saved in `~/Shared` (the
  `share-folder` setting), keeping their names; shared text is saved
  as a `.txt` file there, named after its subject (a page's title) or
  the time. A name that's taken gets ` (2)`, so nothing is overwritten.
  A notification says what was saved where.
- `mynx notify` and `mynx open URL`: see "AI agents" in `/opt/mynx/AGENTS.md`.

## Files and viewers

mynx has its own viewers: text (a notepad), source code (highlighted,
with line numbers), Markdown (drawn as in chat), pictures, and a
player for audio and video (audio keeps playing with the screen off,
with controls in the notification), a PDF viewer (pages, pinch to
zoom, Go to page) and a page viewer for HTML files: a page is shown
rendered, with the files beside it (CSS, scripts, pictures) and the
web reachable, but no phone access (that's for web apps in
`~/apps`, which stay apps); its ⋮ has View source. Files open in them from touch
view's Files, from chat and from the terminal; text, code and
Markdown open to read, with an Edit button (Save, Undo, Find, Replace:
Ctrl+S, Ctrl+F, Ctrl+H). On Android 15 and later, PDFs' text can be
selected and searched (⋮ Find, Ctrl+F). Files over 1 MB open read-only. Any other file opens with
another Android app.

- `mynx open FILE`: opens FILE (a relative one is against the current
  folder). In a terminal it asks:
  ```
  Open notes.md with:
    1) mynx's Markdown viewer (default)
    2) another Android app
  Choose [1]:
  ```
  Enter takes the kind's setting. `--with mynx` or `--with android`
  doesn't ask; nor does a script or an agent (no terminal): the
  setting decides, so nothing waits. `--edit` opens text, code and
  Markdown ready to edit, and an HTML file's source. The app must be on screen. `--json` gives
  `{"ok": true, "with": "mynx", "kind": "code"}` (kinds: `picture`,
  `markdown`, `code`, `text`, `audio`, `video`, `pdf`, `html`, or
  `null`).
  `xdg-open FILE` does the same.
- Each kind's setting: `mynx` (default) or `android`, e.g. `mynx set
  open-video android`; touch view's Settings → Default apps too.
- `open-pictures`: `mynx` (default) or `android`: jpg, png, gif,
  webp, heic, bmp.
- `open-markdown`: `mynx` (default) or `android`: `.md`, `.markdown`.
- `open-code`: `mynx` (default) or `android`: source code (`.py`,
  `.kt`, `.sh`, `.c`, `.rs`, `.go`, `.js`, `.ts`, `.json`, `.yaml`,
  `.toml`, `.conf`, `.css`, `.sql`, `.diff`, …, and
  `Makefile`, `Dockerfile`, `.bashrc`).
- `open-text`: `mynx` (default) or `android`: `.txt`, `.log`, `.csv`,
  `.tsv`, `README`, and any other file that is UTF-8 text.
- `open-audio`: `mynx` (default) or `android`: mp3, m4a, aac, ogg,
  opus, flac, wav, mid.
- `open-video`: `mynx` (default) or `android`: mp4, webm, mkv, 3gp,
  mov.
- `open-pdf`: `mynx` (default) or `android`: `.pdf`. mynx's viewer
  shows the pages; on Android 15 and later a long press selects text
  (drag the handles, then Copy) and ⋮ Find (Ctrl+F) searches
  the pages. It can't open a PDF with a password (Open with another
  app can).
- `open-html`: `mynx` (default) or `android`: `.html`, `.htm`,
  shown rendered (View source shows the code).
