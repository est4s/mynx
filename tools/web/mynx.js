// mynx.js: the phone from an app's JavaScript (web apps, part 2).
//
// Load it in the page's head, before your own scripts:
//   <script src="https://mynx.invalid/mynx.js"></script>
// It defines one global, `mynx`. The script alone has no power: mynx
// hands the bridge's port only to a page on the app's own origin
// (https://NAME.mynx.invalid, or a served app's http://127.0.0.1:PORT
// while its own mynx service runs). Anywhere else every call rejects
// with code 'no-bridge'.
//
// This is the contract's skeleton (work log "Next", 0): every call,
// option, answer, stream and error is documented here; part C fills
// in the bodies and the guide (tools/AGENTS.md) follows this file. The
// wire side is core's Bridge.kt (MESSAGES, BRIDGE_CALLS).
//
// HOW IT TALKS TO MYNX
//
// The app posts a MessagePort to the window with the message data
// 'mynx-bridge' (window 'message' event, event.ports[0]); a new one may
// come for the same page (mynx.js keeps the latest; calls in flight on
// an older port reject 'closed'). On the port:
//   app → page {"hello":{"app","label","calls":[…]}} first,
//              {"id":N,"line":{…}} a stream's reading,
//              {"id":N,"reply":{"ok":true,…}} or
//              {"id":N,"reply":{"ok":false,"error","code"}} once.
//   page → app {"id":N,"call":CALL,"args":[LINE…]}, {"id":N,"cancel":true}
// LINEs are the argument lines `mynx` sends for the same command, so
// core sees what it sees from the terminal; each call below says how
// its options become lines. Text to the clipboard or to share is
// percent-encoded (encodeURIComponent, as `mynx` does with
// urllib.parse.quote). Bytes are base64 both ways.
//
// ANSWERS are the `mynx --json` objects without `ok`. Photos and
// recordings come as {type, bytes, data} (base64) from the app, and
// mynx.js gives {type, bytes, blob} (a Blob; plus `seconds` for a
// recording). At most 16 MB of data in either direction.
//
// ERRORS: every call's promise rejects with a MynxError, whose
// `message` is what the command would print, and `code`:
//   'denied'    the app isn't allowed this permission (`mynx app
//               permissions NAME PERMISSION allow` changes it), or the
//               user chose Don't allow
//   'refused'   anything else mynx refused: a setting off
//               (android-location), Android's permission refused, bad
//               options, no such sensor or device, a timeout, …
//   'closed'    the page's session ended under it (a new port, the
//               screen closing), or the permission dialog closed with
//               no answer
//   'no-bridge' not in mynx, not on the app's origin, a served app
//               whose service isn't running, or no port within 5 s
// Every refusal is in the app's log too (`mynx app log NAME`).
//
// PERMISSIONS: location, camera, microphone, clipboard (reading),
// notifications, bluetooth (every mynx.ble call) and serial (every
// mynx.serial call) are asked the first time (mynx's own dialog, then
// Android's if mynx hasn't got it yet); the answer is remembered in
// ~/.config/mynx/app-permissions.conf. Everything else needs none.
// Asking needs the app's screen in front.

(function () {
  'use strict';

  /** How long mynx.ready waits for the port before rejecting 'no-bridge'. */
  const READY_MS = 5000;

  /**
   * A refusal. `code` is 'denied', 'refused', 'closed' or 'no-bridge'
   * (see ERRORS above); `call` is the wire call's name, when there was one.
   */
  class MynxError extends Error {
    /**
     * @param {string} message what the command would print
     * @param {'denied'|'refused'|'closed'|'no-bridge'} code
     * @param {string} [call]
     */
    constructor(message, code, call) {
      super(message);
      this.name = 'MynxError';
      this.code = code;
      this.call = call;
    }
  }

  /**
   * A call that keeps going: an async iterator of its readings
   * (`for await (const r of stream)`), until it ends or is stopped.
   * - `stop()`: stops it (sends the cancel); `done` then resolves with
   *   the final answer (a recording's, or `{}`). Harmless twice.
   * - `done`: a promise of the final answer; rejects with a MynxError
   *   when it was refused (the iterator then throws it too).
   * - `next()`: the next reading, as an iterator's.
   * Readings that come while nobody iterates are queued.
   */
  class MynxStream {
    /** @returns {Promise<void>} */
    stop() { return notBuilt(); }
    /** @type {Promise<object>} */
    get done() { return notBuilt(); }
    /** @returns {Promise<IteratorResult<object>>} */
    next() { return notBuilt(); }
    [Symbol.asyncIterator]() { return this; }
  }

  /**
   * An open serial port (from mynx.serial.open): a MynxStream of
   * Uint8Array chunks (what the board sends), plus:
   * - `name`, `handle`, `baud` (from the first line, {"open":{…}});
   * - `write(data)`: a Uint8Array, or a string as UTF-8 →
   *   'serial-write' [handle, BASE64]; resolves {bytes};
   * - `set({baud, dtr, rts, dataBits, parity, stopBits})` →
   *   'serial-set' [handle, 'baud=N', 'dtr=0|1', 'rts=0|1', 'data=N',
   *   'parity=…', 'stop=…'] (only those given; booleans as 1/0);
   * - `close()`: stop(); the port is let go (RTS, then DTR down).
   * The stream rejects 'refused' when the port goes away (unplugged,
   * the Bluetooth link dropped), with why.
   */
  class SerialPort extends MynxStream {
    /** @param {Uint8Array|string} data @returns {Promise<{bytes:number}>} */
    write(data) { return notBuilt(); }
    /** @param {{baud?:number,dtr?:boolean,rts?:boolean,dataBits?:number,parity?:string,stopBits?:number}} options */
    set(options) { return notBuilt(); }
    /** @returns {Promise<void>} */
    close() { return notBuilt(); }
  }

  function notBuilt() {
    return Promise.reject(new MynxError('mynx.js is a skeleton: part C builds it', 'no-bridge'));
  }

  // Each function below documents: the wire call and its lines, the
  // answer, and the permission. Bodies are part C's.

  const mynx = {
    /**
     * Resolves {app, label, calls} once the bridge's port has come;
     * rejects 'no-bridge' after 5 s. Every call waits for it.
     * @type {Promise<{app:string,label:string,calls:string[]}>}
     */
    ready: notBuilt(),
    /** The app's name, once ready (null before). */
    app: null,
    /** The wire calls offered (hello's `calls`), a Set, once ready (empty before). */
    can: new Set(),
    MynxError,
    MynxStream,
    SerialPort,
  };

  /**
   * One fix. 'location' [`gps`, `timeout=N`] → {location: FIX}; FIX is
   * {latitude, longitude, accuracy, altitude, speed, bearing, provider,
   * time} (null for what's unknown). Permission: location.
   * @param {{gps?:boolean, timeout?:number}} [options] timeout in s, up to 300
   */
  mynx.location = function (options) { return notBuilt(); };
  /**
   * 'location-stream' [`gps`, `interval=N`]: a MynxStream of FIX.
   * Permission: location.
   * @param {{gps?:boolean, every?:number}} [options] every: seconds between fixes
   */
  mynx.location.stream = function (options) { return notBuilt(); };

  /**
   * One reading. 'sensor' [NAME, `timeout=N`] → {reading: {sensor,
   * values, unit, accuracy, time}}. No permission (the step sensors
   * need Android's, asked by mynx).
   * @param {string} name e.g. 'compass', 'accelerometer', 'light'
   * @param {{timeout?:number}} [options]
   */
  mynx.sensor = function (name, options) { return notBuilt(); };
  /** 'sensor-list' [] → {sensors: [{name, values, unit}]}. */
  mynx.sensor.list = function () { return notBuilt(); };
  /**
   * 'sensor-stream' [NAME, `rate=HZ`]: a MynxStream of readings.
   * @param {string} name
   * @param {{rate?:number}} [options] 1 to 200 a second (10 unless given)
   */
  mynx.sensor.stream = function (name, options) { return notBuilt(); };

  /**
   * 'vibrate' [MS, `strength=PCT`] → {}. Returns at once. No permission.
   * @param {number} [ms] 1 to 5000 (300 unless given)
   * @param {{strength?:number}} [options] 1 to 100
   */
  mynx.vibrate = function (ms, options) { return notBuilt(); };
  /** 'vibrate-effect' [NAME] → {ms} or {stopped: true}; waits until it ends. @param {'click'|'double-click'|'tick'|'heavy-click'} name */
  mynx.vibrate.effect = function (name) { return notBuilt(); };
  /**
   * 'vibrate-wave' [STEP…, `repeat`]: a MynxStream with no readings;
   * `done` gives {ms} or {stopped: true}. With repeat it runs until
   * stopped.
   * @param {Array<number|string>} steps MS or 'MS@PERCENT' ('100@0' a pause)
   * @param {{repeat?:boolean}} [options]
   */
  mynx.vibrate.wave = function (steps, options) { return notBuilt(); };
  /** 'vibrate-chain' [PRIM…] → {ms} or {stopped: true}. @param {string[]} primitives 'NAME[@PERCENT][+MS]' */
  mynx.vibrate.chain = function (primitives) { return notBuilt(); };
  /** 'vibrate-list' [] → {amplitude_control, effects, primitives}. */
  mynx.vibrate.list = function () { return notBuilt(); };
  /** 'vibrate-stop' [] → {}. */
  mynx.vibrate.stop = function () { return notBuilt(); };

  mynx.torch = {
    /** 'torch' [`on`, PERCENT] → {}. No permission. @param {number} [percent] 1 to 100 (Android 13+) */
    on(percent) { return notBuilt(); },
    /** 'torch' [`off`] → {}. */
    off() { return notBuilt(); },
  };

  /**
   * 'battery' [] → {battery: {percent, status, plugged, health,
   * temperature, voltage, current, current_average, charge,
   * time_to_full, cycles, technology, battery_saver, thermal, time}}.
   * No permission.
   */
  mynx.battery = function () { return notBuilt(); };
  /** 'battery-stream' [`interval=N`]: a MynxStream of battery objects. @param {{every?:number}} [options] */
  mynx.battery.stream = function (options) { return notBuilt(); };

  mynx.clipboard = {
    /** 'clipboard-get' [] → the text (a string; the answer's `text`). Permission: clipboard. */
    get() { return notBuilt(); },
    /** 'clipboard-set' [ENCODED] → {}. No permission. @param {string} text */
    set(text) { return notBuilt(); },
  };

  mynx.share = {
    /** 'share-text' [ENCODED] → {}. Opens Android's share sheet. No permission. @param {string} text */
    text(text) { return notBuilt(); },
    /**
     * 'share-files' [`name=NAME`, `type=MIME`, `data=BASE64`, … per
     * file] → {count}. No permission.
     * @param {Array<{name:string, type?:string, data:Uint8Array|Blob|string}>} files
     */
    files(files) { return notBuilt(); },
  };

  /**
   * 'notify' [TITLE, TEXT, `if-away`] → {shown, reason?}. Tapping it
   * opens the app's screen. Permission: notifications.
   * @param {string} title
   * @param {string} [text]
   * @param {{ifAway?:boolean}} [options] ifAway: not while the app's screen is in front
   */
  mynx.notify = function (title, text, options) { return notBuilt(); };

  /**
   * A photo: {quick: 'back'|'front'} → 'camera-quick' [FACING], taken
   * at once with no screen; without quick → 'camera' [] (the phone's
   * camera app; only when mynx.can has 'camera'). Resolves {type:
   * 'image/jpeg', bytes, blob}. Permission: camera.
   * @param {{quick?:'back'|'front'}} [options]
   */
  mynx.camera = function (options) { return notBuilt(); };

  mynx.audio = {
    /**
     * 'audio-record' [`seconds=N`, `rate=HZ`, `format=F`]: a MynxStream
     * (one reading {recording: true} once it records); stop() or N s
     * ends it, and `done` gives {type, bytes, seconds, blob}.
     * Permission: microphone.
     * @param {{seconds?:number, rate?:number, format?:'m4a'|'aac'|'ogg'|'opus'|'wav'}} [options] format m4a unless given
     */
    record(options) { return notBuilt(); },
    /**
     * Plays through the phone's speaker: 'audio-play' [`file=PATH`]
     * for a string naming a file of a static app's own (same origin),
     * else the bytes ('audio-play' [`data=BASE64`, `type=MIME`]; a URL
     * is fetched first). A MynxStream; `done` gives {seconds} when it
     * ends. No permission.
     * @param {string|Blob|Uint8Array} source
     */
    play(source) { return notBuilt(); },
  };

  mynx.rotation = {
    /** 'rotation-lock' [SIDE] → {locked}; holds the app's screen only, until unlock or the page goes. @param {'portrait'|'landscape'} [side] none: as it is */
    lock(side) { return notBuilt(); },
    /** 'rotation-unlock' [] → {locked: 'no'}. */
    unlock() { return notBuilt(); },
  };

  /**
   * Bluetooth LE (permission: bluetooth). DEVICE is an address or a
   * name; CHAR a UUID, a 16-bit one ('2a19'), a known name
   * ('battery-level') or 'SERVICE/CHAR'. `as`: 'hex', 'text', 'u8',
   * 'u16', 'u32', 'i8', 'i16', 'i32', 'float32'.
   */
  mynx.ble = {
    /**
     * 'ble-scan' [`seconds=N`, `name=TEXT`, `service=UUID`, `all`] →
     * {devices: [ADVERT]}; ADVERT {address, address_type, name, rssi,
     * services, manufacturer, connectable, time}.
     * @param {{seconds?:number, name?:string, service?:string, all?:boolean}} [options]
     */
    scan(options) { return notBuilt(); },
    /** 'ble-services' [DEVICE] → {device, mtu, services}. @param {string} device */
    services(device) { return notBuilt(); },
    /** 'ble-read' [DEVICE, CHAR, `as=F`] → {device, char, name, as, value, hex}. @param {string} device @param {string} char @param {{as?:string}} [options] */
    read(device, char, options) { return notBuilt(); },
    /**
     * 'ble-write' [DEVICE, CHAR, VALUE, `as=F`, `no-response`] →
     * {device, char, name, bytes}. A Uint8Array is sent as hex
     * (as=hex); a string with as 'text' unless given; a number needs as.
     * @param {string} device @param {string} char
     * @param {Uint8Array|string|number} value
     * @param {{as?:string, noResponse?:boolean}} [options]
     */
    write(device, char, value, options) { return notBuilt(); },
    /** 'ble-notify' [DEVICE, CHAR, `as=F`]: a MynxStream of {char, name, as, value, hex, time}. */
    notify(device, char, options) { return notBuilt(); },
    /** 'ble-connect' [DEVICE]: a MynxStream of {state, device, mtu}; holds the link (other calls on it are quick) until stopped. */
    connect(device) { return notBuilt(); },
  };
  /** 'ble-scan-stream' [`name=TEXT`, `service=UUID`, `all`]: a MynxStream of ADVERT. */
  mynx.ble.scan.stream = function (options) { return notBuilt(); };

  /** Serial ports, USB and Bluetooth (permission: serial). One user at a time. */
  mynx.serial = {
    /** 'serial-list' [] → {ports: [{name, kind, path, url, in_use, device}]}. */
    list() { return notBuilt(); },
    /**
     * 'serial-open' [PORT, `baud=N`, `data=N`, `parity=P`, `stop=S`,
     * `lower`] → a SerialPort once open (its first line). 'refused'
     * "PORT is busy" while a program or `mynx usb monitor` has it.
     * @param {string} port 'ttyACM0', 'ttyUSB0', 'rfcomm0', 'nus0'
     * @param {{baud?:number, dataBits?:5|6|7|8, parity?:'none'|'even'|'odd'|'mark'|'space', stopBits?:1|1.5|2, lower?:boolean}} [options]
     *   baud 115200, 8N1 unless given; lower: open with DTR and RTS down
     * @returns {Promise<SerialPort>}
     */
    open(port, options) { return notBuilt(); },
  };

  /**
   * The app's own background service (app.conf's `service`) only; no
   * permission. NAME may be left out (it's the app's own); any other
   * name, or an app with no service, is 'refused'.
   */
  mynx.service = {
    /** 'service-list' [] → {services: [SERVICE]} (only the app's own). */
    list() { return notBuilt(); },
    /** 'service-start' [NAME] → {service}. @param {string} [name] */
    start(name) { return notBuilt(); },
    /** 'service-stop' [NAME] → {service}. Stopping it ends the bridge at the next page load. */
    stop(name) { return notBuilt(); },
    /** 'service-restart' [NAME] → {service}. */
    restart(name) { return notBuilt(); },
    /** 'service-log' [NAME, LINES] → {log}. @param {string} [name] @param {{lines?:number}} [options] 100 unless given */
    log(name, options) { return notBuilt(); },
  };

  // A skeleton must not leave rejections unhandled.
  mynx.ready.catch(() => {});

  window.mynx = mynx;
})();
