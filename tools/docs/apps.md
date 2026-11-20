# Apps

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx app [list]` | the apps: name, label, static or served, and their `app.conf` problems |
| `mynx app open NAME` | open an app on its own screen (the app must be on screen); a served one's service is started first |
| `mynx app reload NAME` | reload an app's open screen |
| `mynx app log NAME [-n N]` | the last N lines (100) of an app's log: its console, errors and failed loads |
| `mynx app shortcut NAME` | ask Android to put an app on the home screen (the app must be on screen) |
| `mynx app permissions [NAME]` | what each app may use from the phone: `allow`, `deny` or `ask` per permission |
| `mynx app permissions NAME PERMISSION allow\|deny\|ask` | change one app's answer; `ask` asks it again the next time |
| `mynx app permissions NAME reset` | forget all of an app's answers |

Apps are pages in HTML, CSS and JavaScript that open on a screen of
their own, not in a tab, with no build and no install. The menu's
**Apps** lists them; `mynx app open NAME` opens one. Each app is its
own entry in Android's recent apps, and opening it again brings that
one back.

- **An app is a folder:** `~/apps/NAME/` with an `index.html`, and
  whatever it loads (scripts, styles, images, sounds) beside it.
  NAME is `a-z`, `0-9` and `-`, up to 40 characters, not starting or
  ending with `-` (it's the host name in the app's address). A link
  to a folder elsewhere works too: `ln -s ~/pixel-hero/assets
  ~/apps/pixel-hero`. The built-in ones are in `/opt/mynx/apps`; one
  in `~/apps` with the same name wins. Executable files in `~/apps`
  aren't apps: they're terminal apps from before (see "Terminal
  apps" under "More of the setup" in `/opt/mynx/AGENTS.md`).
- **Its name in the menu:** `app.conf`'s `name`, else the `<title>`
  of `index.html`, else the folder's name (`pixel-hero` → "Pixel
  Hero"), up to 20 characters.
- **`app.conf`** (optional): one `key = value` per line, `#`
  comments. Bad lines are skipped; `mynx check` lists them.

  | Key | Means |
  |---|---|
  | `name` | its name in the menu and on its screen (up to 20 characters) |
  | `icon` | a PNG, JPEG or WebP file in the folder: its home screen shortcut's icon (below) |
  | `url` | `http://127.0.0.1:PORT/…` or `http://localhost:PORT/…`: a served app (below) |
  | `service` | the background service that serves `url` |
  | `fullscreen` | `no` (default), `yes` or `edge`: the status and navigation bars hidden, for games (below) |
  | `screen-awake` | `yes` or `no` (default): the screen stays on while the app is in front |

- **Where it runs:** at `https://NAME.mynx.invalid/`, an address of
  its own, so its `localStorage`, IndexedDB and cookies are its own
  and stay. It reads only the files in its folder: a link out of it,
  or `..`, is "not found". The internet works as in a browser
  (`fetch` to other sites, with their CORS rules). Sound starts after
  a tap, as in a browser.
- **The phone from JavaScript:** location, sensors, vibration, the
  flashlight, the battery, the clipboard, share, notifications,
  photos, the microphone, Bluetooth LE, serial ports and more, through
  `mynx.js` (see "The phone from JavaScript" below). `mynx` commands
  themselves aren't reachable from a page.
- **The screen:** the page fills it below the status bar, with no bar
  of its own, like an installed web app; recents shows the app's
  name. The back key goes back in the page's history; at its first
  page it closes the screen, as swiping it away in recents does. A
  page with screens of its own (a game on a canvas) can take it
  first: define `window.androidBack`, and return `true` when it
  handled the key (anything else goes on to the history).
  Reload it with `mynx app reload NAME`.
- **Full screen** (`fullscreen` in `app.conf`, for games): `yes` hides
  the status and navigation bars (a swipe from the edge shows them
  for a moment) and keeps the page clear of the camera cutout; `edge`
  hides them too and lets the page run under the cutout, so keep
  controls clear of the corners with CSS's `env(safe-area-inset-top)`
  (`-right`, `-bottom`, `-left`) and `<meta name="viewport"
  content="width=device-width, initial-scale=1, viewport-fit=cover">`.
  Both are padded for the keyboard, and hide the bars again after
  the keyboard or a rotation. `screen-awake = yes` keeps the screen
  on while the app is in front (not in the background).
- **Home screen shortcuts:** `mynx app shortcut NAME`, or the menu's
  Apps → **Add to home screen…**, asks Android to pin one (it shows
  its own dialog; mynx must be on screen). Its icon is `app.conf`'s
  `icon`, else a letter on a colour from the theme. Tapping it opens
  the app straight away, starting mynx and the app's service if they
  aren't running; a shortcut whose app is gone says so on its screen.
  A changed name or icon shows at the next open.
- **Auto-reload:** while its screen shows, an app reloads by itself
  when a file in its folder changes: edit `index.html` and the phone
  shows the change within a second or two. `mynx app reload NAME`
  reloads it by hand.
- **Errors, for agents** (there's no browser console here): the
  page's `console.log`, `console.warn` and `console.error`, uncaught
  errors, failed loads and HTTP errors go to
  `/tmp/.mynx/apps/NAME.log`; `mynx app log NAME` prints the last 100
  lines (`-n N` for more). Each opening adds a `--- opened` line, then
  one line per message: `[HH:MM:SS.mmm] LEVEL SOURCE:LINE MESSAGE`. It
  keeps the last 1000 lines.
- **Served apps:** an app can come from a server running here instead,
  a Python backend say, run as a background service (see "Background
  services"). Its `app.conf`:

  ```
  name = Notes
  url = http://127.0.0.1:8090/
  service = notes
  ```

  with `mynx service add notes --cwd ~/apps/notes -- python3
  server.py 8090` (listening on 127.0.0.1). Opening the app starts the
  service if it isn't running and waits up to 15 s for the port;
  closing the app's screen (closing it, not just leaving it) stops the
  service again, **only if opening it started it**: one that was
  already running, or has `autostart = yes`, is left alone. Plain
  `http` works for 127.0.0.1 and localhost only. A served app's
  `index.html`, if any, isn't used; it isn't kept to its folder (its
  server decides) and doesn't reload by itself (`mynx app reload
  NAME`). Without `service`, something must already answer on the
  port.
- **The example:** `/opt/mynx/apps/sketch`, a finger-drawing pad that
  keeps the drawing in `localStorage` (`index.html`, `sketch.js`,
  `sketch.css`). To start one of your own from it: `cp -r
  /opt/mynx/apps/sketch ~/apps/my-pad`. `/opt/mynx/apps/gadgets`
  uses the phone from JavaScript: a compass, the battery, the
  flashlight and vibration, a location fix, a Bluetooth LE scan and a
  serial console, each card showing why when it's refused.
- `mynx app` lists them; `--json` gives `apps`: `name`, `label`,
  `path`, `kind` (`static` or `served`), `origin`, `url`, `service`,
  `icon`, `fullscreen`, `screen_awake` and `problems`. `mynx app open
  NAME` needs the app on screen: Android doesn't let it open a screen
  from the background.

## The phone from JavaScript

An app's page reaches the phone through `mynx.js`, which mynx serves
to every page (it asks mynx for its connection when it loads, so one
added later works too). Load it in the head, before your own scripts:

```html
<script src="https://mynx.invalid/mynx.js"></script>
```

It defines one global, `mynx`. Every call returns a promise of the
answer `mynx --json` gives for the same command, without `ok`, and
takes the same options (`mynx.location({gps: true})` is `mynx
location --gps`). `mynx.ready` resolves with `{app, label, calls}`
once mynx has connected the page (every call waits for it);
`mynx.app` is the app's name and `mynx.can` a Set of the calls
offered.

```js
await mynx.ready;
const { battery } = await mynx.battery();
mynx.vibrate(200);
for await (const r of mynx.sensor.stream('compass', { rate: 10 })) {
  needle.style.transform = `rotate(${-r.values.azimuth}deg)`;
}
```

| Call | Answer | Permission |
|---|---|---|
| `mynx.location({gps, timeout})` | `{location: {latitude, longitude, accuracy, altitude, speed, bearing, provider, time}}` | location |
| `mynx.location.stream({gps, every})` | a stream of fixes | location |
| `mynx.sensor(name, {timeout})`, `mynx.sensor.list()` | `{reading: {sensor, values, unit, accuracy, time}}`, `{sensors}` | |
| `mynx.sensor.stream(name, {rate})` | a stream of readings | |
| `mynx.vibrate(ms, {strength})` | `{}` at once | |
| `mynx.vibrate.effect(name)`, `.chain(prims)` | `{ms}` or `{stopped: true}` when it ends | |
| `mynx.vibrate.wave(steps, {repeat})` | a stream; `done` gives `{ms}` or `{stopped: true}` | |
| `mynx.vibrate.list()`, `.stop()` | as `mynx vibrate list`, `{}` | |
| `mynx.torch.on(percent)`, `.off()` | `{}` | |
| `mynx.battery()`, `mynx.battery.stream({every})` | `{battery: {percent, status, …}}`, a stream | |
| `mynx.clipboard.get()` | the text | clipboard |
| `mynx.clipboard.set(text)` | `{}` | |
| `mynx.share.text(text)`, `mynx.share.files([{name, type, data}])` | `{}`, `{count}` (Android's share sheet) | |
| `mynx.notify(title, text, {ifAway})` | `{shown, reason}`; tapping it opens the app | notifications |
| `mynx.camera({quick: 'back'\|'front'})` | `{type, bytes, blob}`: a photo taken at once with no screen | camera |
| `mynx.camera()` | the same, from the phone's camera app (only when `mynx.can.has('camera')`) | camera |
| `mynx.audio.record({seconds, rate, format})` | a stream; `done` gives `{type, bytes, seconds, blob}` | microphone |
| `mynx.audio.play(source)` | a stream; `done` gives `{seconds}`: a file of the app's own, a URL, a Blob or bytes | |
| `mynx.rotation.lock(side)`, `.unlock()` | `{locked}`: the app's screen only, until the page goes | |
| `mynx.ble.scan({seconds, name, service, all})`, `mynx.ble.scan.stream(…)` | `{devices}`, a stream of devices | bluetooth |
| `mynx.ble.services(dev)`, `.read(dev, char, {as})`, `.write(dev, char, value, {as, noResponse})` | as `mynx ble` | bluetooth |
| `mynx.ble.notify(dev, char, {as})`, `.connect(dev)` | streams; `connect` holds the link until stopped | bluetooth |
| `mynx.serial.list()` | `{ports: [{name, kind, path, url, in_use, device}]}` | serial |
| `mynx.serial.open(port, {baud, dataBits, parity, stopBits, lower})` | an open port (below) | serial |
| `mynx.service.list()`, `.start()`, `.stop()`, `.restart()`, `.log(name, {lines})` | the app's own service only (`app.conf`'s `service`) | |

- **Streams** are async iterators: `for await (const fix of s)` gets
  each reading, `s.stop()` stops it (as Ctrl+C does), and `s.done` is
  a promise of the final answer. Leaving the loop early stops it. A
  page's streams stop when it's reloaded or its screen closes, not
  when it goes to the background.
- **Errors:** a refused call rejects with a `mynx.MynxError`: its
  `message` is what the command would print, its `code` one of
  `denied` (the app isn't allowed that permission), `refused`
  (anything else: a setting off, Android's permission refused, bad
  options, a timeout), `closed` (the page's screen closed or reloaded
  under it, or the question was closed unanswered) or `no-bridge`
  (not in mynx, or not on the app's own address: `mynx.ready`
  rejects after 5 s). Every refusal is in the app's log too (`mynx app
  log NAME`, `warn bridge …`), so an agent sees why a page got
  nothing.
- **Where it works:** only on the app's own address,
  `https://NAME.mynx.invalid` for a static app, a served app's
  `http://127.0.0.1:PORT` **while its own `service` runs** (a served
  app with no `service` gets no bridge: with nothing of ours on the
  port, another Android app could listen there). A page the app
  navigates to elsewhere, or an iframe of another site, gets nothing.
  That's trust, not proof: every script the page loads (a library
  from a CDN too) shares its address and so its bridge.
- **Permissions:** location, camera, microphone, reading the
  clipboard, notifications, Bluetooth (every `mynx.ble` call) and
  serial ports (every `mynx.serial` call) are asked the first time,
  on the app's screen: "Gadgets wants to use your location", **Allow**
  or **Don't allow**, then Android's own dialog if mynx hasn't got
  that permission yet. The rest just work. The answers are kept in
  `~/.config/mynx/app-permissions.conf`, one `[NAME]` block per app
  with `PERMISSION = allow|deny` lines (not listed: asked); `mynx
  check` checks it and `mynx undo` takes a change back. `mynx app
  permissions [NAME]` lists them, `mynx app permissions NAME
  location deny` changes one (`ask` asks again), `mynx app
  permissions NAME reset` forgets them all. Asking needs the app on
  screen, else the call is refused. The `android-*` settings still
  apply on top (`android-location off` refuses every app).
- **Binary data:** photos and recordings come as a `Blob` (`URL.
  createObjectURL(answer.blob)` shows or plays one); files to share
  or play and bytes to a serial port or a BLE characteristic go as a
  `Uint8Array`, a `Blob` or a string (as UTF-8). At most 16 MB a
  call.
- **Serial ports:** `const p = await mynx.serial.open('ttyACM0',
  {baud: 115200})` gives a stream of `Uint8Array` chunks (`for await
  (const bytes of p)`), with `p.write('hi\r')`, `p.set({dtr: false,
  rts: true, baud: 9600})` and `p.close()`. One user at a time, as
  for programs here: "ttyACM0 is busy" while `mynx usb monitor` or
  another program has it.


## Building an Android app

The tools, once (about 110 MB; `r8.jar` gives `d8`, `android.jar` is
Android 14's API):

```
apt install aapt zipalign apksigner openjdk-21-jdk-headless unzip
mkdir -p ~/androidsdk && cd ~/androidsdk
curl -fLo r8.jar https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar
curl -fLo platform.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -j platform.zip android-34/android.jar -d android-34 && rm platform.zip
```

A project is `AndroidManifest.xml`, `res/` and `src/`, with a
`build.sh` that runs, with `JAR=~/androidsdk/android-34/android.jar`:

```
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$JAR"
javac --release 11 -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
java -cp ~/androidsdk/r8.jar com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
aapt package -f -M AndroidManifest.xml -S res -I "$JAR" -F build/unsigned.apk
(cd build/dex && aapt add ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks release.keystore --out NAME.apk build/aligned.apk
```

(`-A assets` on the second `aapt package` adds an `assets/` folder.)
A build takes about a minute.

- **Signing:** make the keystore once per app (`keytool -genkeypair
  -keystore release.keystore …`) and keep it: Android installs an
  update only when it's signed with the same key. Raise
  `versionCode` in the manifest for each update.
- **The package name** (`package=` in the manifest) must be unique on
  the phone, e.g. `com.NAME.app`.
- **Installing:** serve the folder with `python3 -m http.server 8765
  --bind 127.0.0.1` (in the background) and `mynx open
  http://127.0.0.1:8765/NAME.apk`; the browser downloads it, and
  **Open** starts Android's installer. mynx must be on screen: when
  `mynx open` says it isn't, ask the user to come back to it, then
  run it again. The first time, Android asks to allow the browser to
  install apps.
- **No adb or logcat here:** you can't read the app's log or see why
  it crashed. Have it write errors where Debian can read them, e.g. a
  `Thread.setDefaultUncaughtExceptionHandler` that writes to
  `/storage/emulated/0/Download/NAME-crash.txt`.
- **Hardware:** each permission goes in the manifest
  (`<uses-permission>`); camera, location, microphone, contacts and
  the like are also asked at run time (`requestPermissions`).
- **Internet:** the `INTERNET` permission, then `HttpURLConnection`.
  Android blocks plain `http://` unless the manifest's
  `android:networkSecurityConfig` allows it, e.g. for one host in
  `res/xml/network_security_config.xml`:
  `<network-security-config><domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain></domain-config></network-security-config>`
- **Widgets** (an `AppWidgetProvider`) show only RemoteViews: text,
  images, buttons, lists, `TextClock`; no custom views or WebView
  (draw a `Bitmap` for a chart). They update on their own at most
  every 30 minutes (`updatePeriodMillis`); more often needs
  `AlarmManager` or `JobScheduler`, or a tap
  (`setOnClickPendingIntent`).
- **Running in the background:** short jobs through `JobScheduler`;
  anything that keeps running needs a foreground service with a
  notification.

**An Android app and Debian:** an app or widget can talk to a server
running here as a background service (`mynx service add`, see
"Background services" in `/opt/mynx/docs/services.md`), at `http://127.0.0.1:PORT` with plain `http`
allowed for `127.0.0.1` as above. So a widget can show what a script
works out, or its buttons can run commands. It only works while mynx
runs; tell the user so.
