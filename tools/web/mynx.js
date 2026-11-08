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
// Every call, option, answer, stream and error is documented here, and
// the guide (tools/AGENTS.md, "The phone from JavaScript") follows this
// file. The wire side is core's Bridge.kt (MESSAGES, BRIDGE_CALLS).
//
// HOW IT TALKS TO MYNX
//
// Once its 'message' listener is attached, mynx.js asks for the port:
// fetch('https://mynx.invalid/hello', {mode: 'no-cors', cache:
// 'no-store'}). The app intercepts that request, checks the page's
// origin and posts one MessagePort to the main frame with the message
// data 'mynx-bridge' (window 'message' event, event.ports[0]). A script
// loaded late works the same way: it asks when it loads. mynx.js keeps
// the FIRST port it gets and closes any later one; with none within
// 5 s, mynx.ready rejects 'no-bridge'. On the port:
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
//   'closed'    the page's session ended under it (a reload, the
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
  /** The most bytes a page sends in one call (decoded), as core's MAX_BRIDGE_DATA_BYTES. */
  const MAX_BYTES = 16 * 1024 * 1024;
  const NO_BRIDGE = "no bridge: this page isn't an app's own page in mynx";
  const page = window;
  const HELLO_URL = 'https://mynx.invalid/hello';

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

  // --- the channel ----------------------------------------------------

  let port = null; // the first port the page got: the one it keeps
  let hello = null;
  let nextId = 1;
  const open = new Map(); // id → {call, line(object), reply(object), fail(MynxError)}
  const waiters = []; // calls waiting for the first hello
  let gaveUp = false;
  let readyResolve;
  let readyReject;
  const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
  ready.catch(() => {});
  const readyTimer = setTimeout(() => {
    if (hello) return;
    gaveUp = true;
    const error = new MynxError(NO_BRIDGE, 'no-bridge');
    readyReject(error);
    waiters.splice(0).forEach((w) => w.reject(error));
  }, READY_MS);

  page.addEventListener('message', (event) => {
    if (event.data !== 'mynx-bridge' || !event.ports || !event.ports[0]) return;
    const next = event.ports[0];
    if (port) {
      // The page has its bridge: the app's later port isn't needed.
      if (typeof next.close === 'function') next.close();
      return;
    }
    port = next;
    next.onmessage = (e) => received(e.data);
    if (typeof next.start === 'function') next.start();
  });

  // Ask for the port, now that the listener hears it. The answer is
  // opaque (no-cors): the port is what counts, or no-bridge in 5 s.
  try {
    Promise.resolve(page.fetch(HELLO_URL, { mode: 'no-cors', cache: 'no-store' })).catch(() => {});
  } catch (e) {
    // No fetch (not a browser): no port, so no-bridge in 5 s.
  }

  function received(text) {
    let message;
    try {
      message = typeof text === 'string' ? JSON.parse(text) : text;
    } catch (e) {
      return;
    }
    if (!message || typeof message !== 'object') return;
    if (message.hello) {
      hello = message.hello;
      mynx.app = hello.app;
      mynx.label = hello.label;
      mynx.can = new Set(hello.calls || []);
      clearTimeout(readyTimer);
      readyResolve({ app: hello.app, label: hello.label, calls: hello.calls || [] });
      waiters.splice(0).forEach((w) => w.resolve());
      return;
    }
    const call = open.get(message.id);
    if (!call) return;
    if (message.line !== undefined) {
      call.line(message.line);
    } else if (message.reply !== undefined) {
      open.delete(message.id);
      const reply = message.reply || {};
      if (reply.ok) {
        const answer = Object.assign({}, reply);
        delete answer.ok;
        call.reply(answer);
      } else {
        call.fail(new MynxError(reply.error || 'mynx refused it', reply.code || 'refused', call.call));
      }
    }
  }

  /** Resolves once a port has come; rejects 'no-bridge' when none came in time. */
  function bridge() {
    if (hello) return Promise.resolve();
    if (gaveUp) return Promise.reject(new MynxError(NO_BRIDGE, 'no-bridge'));
    return new Promise((resolve, reject) => waiters.push({ resolve, reject }));
  }

  /**
   * Sends [call] with [lines]; [handlers] gets line(), reply() and
   * fail(). Returns the function that cancels it.
   */
  function send(call, lines, handlers) {
    if (!mynx.can.has(call)) {
      handlers.fail(new MynxError(`${call} isn't offered to apps`, 'refused', call));
      return () => {};
    }
    const id = nextId++;
    const entry = Object.assign({ call }, handlers);
    open.set(id, entry);
    port.postMessage(JSON.stringify({ id, call, args: lines }));
    return () => {
      if (open.get(id) === entry) port.postMessage(JSON.stringify({ id, cancel: true }));
    };
  }

  /** The lines, which may be a promise (bytes still being read), as strings. */
  function prepared(args) {
    return Promise.resolve(args).then((lines) => lines.map(String));
  }

  /** A plain call: a promise of its answer, made by [shape] from the reply. */
  function ask(call, args, shape) {
    return bridge().then(() => prepared(args)).then((lines) => new Promise((resolve, reject) => {
      send(call, lines, {
        line() {},
        reply: (answer) => resolve(shape ? shape(answer) : answer),
        fail: reject,
      });
    }));
  }

  // --- streams --------------------------------------------------------

  /**
   * A call that keeps going: an async iterator of its readings
   * (`for await (const r of stream)`), until it ends or is stopped.
   * - `stop()`: stops it (sends the cancel); `done` then resolves with
   *   the final answer (a recording's, or `{}`). Harmless twice.
   * - `done`: a promise of the final answer; rejects with a MynxError
   *   when it was refused (the iterator then throws it too).
   * - `next()`: the next reading, as an iterator's.
   * Readings that come while nobody iterates are queued. Leaving a
   * `for await` early (break, return) stops it.
   */
  class MynxStream {
    constructor(call, args, shapes) {
      const s = shapes || {};
      this._call = call;
      this._readings = [];
      this._takers = [];
      this._ended = false;
      this._error = null;
      this._stopped = false;
      this._cancel = null;
      this._shapeLine = s.line || ((line) => line);
      this._shapeDone = s.done || ((answer) => answer);
      this._done = new Promise((resolve, reject) => { this._resolve = resolve; this._reject = reject; });
      this._done.catch(() => {});
      if (args !== undefined) this._start(args);
    }

    _start(args) {
      bridge().then(() => prepared(args)).then((lines) => {
        if (this._ended) return;
        if (this._stopped) {
          this._finish({});
          return;
        }
        this._cancel = send(this._call, lines, {
          line: (line) => this._reading(this._shapeLine(line)),
          reply: (answer) => this._finish(this._shapeDone(answer)),
          fail: (error) => this._fail(error),
        });
      }).catch((error) => this._fail(error));
    }

    _reading(value) {
      if (value === undefined || this._ended) return;
      const taker = this._takers.shift();
      if (taker) taker.resolve({ value, done: false });
      else this._readings.push(value);
    }

    _finish(answer) {
      if (this._ended) return;
      this._ended = true;
      this._resolve(answer);
      this._takers.splice(0).forEach((t) => t.resolve({ value: undefined, done: true }));
    }

    _fail(error) {
      if (this._ended) return;
      this._ended = true;
      this._error = error;
      this._reject(error);
      this._takers.splice(0).forEach((t) => t.reject(error));
    }

    /** @returns {Promise<void>} */
    stop() {
      if (!this._stopped && !this._ended) {
        this._stopped = true;
        // Not sent yet: it never will be.
        if (this._cancel) this._cancel();
        else this._finish({});
      }
      return this._done.then(() => {}, () => {});
    }

    /** @type {Promise<object>} */
    get done() { return this._done; }

    /** @returns {Promise<IteratorResult<object>>} */
    next() {
      if (this._readings.length) return Promise.resolve({ value: this._readings.shift(), done: false });
      if (this._error) return Promise.reject(this._error);
      if (this._ended) return Promise.resolve({ value: undefined, done: true });
      return new Promise((resolve, reject) => this._takers.push({ resolve, reject }));
    }

    return() {
      this.stop();
      return Promise.resolve({ value: undefined, done: true });
    }

    [Symbol.asyncIterator]() { return this; }
  }

  function stream(call, args, shapes) {
    return new MynxStream(call, args, shapes);
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
    constructor(lines, opened) {
      super('serial-open');
      this.name = null;
      this.handle = null;
      this.baud = null;
      this._shapeLine = (line) => {
        if (line.open) {
          this.name = line.open.port;
          this.handle = line.open.handle;
          this.baud = line.open.baud;
          opened.resolve(this);
          return undefined;
        }
        return line.data !== undefined ? fromBase64(line.data) : undefined;
      };
      // Settles nothing once it opened: a promise settles once.
      this._done.then(() => opened.reject(new MynxError('the port closed before it opened', 'refused', 'serial-open')),
        (error) => opened.reject(error));
      this._start(lines);
    }

    /** @param {Uint8Array|string} data @returns {Promise<{bytes:number}>} */
    write(data) {
      return ask('serial-write', bytesOf(data).then((bytes) => [this.handle, toBase64(checked(bytes))]));
    }

    /** @param {{baud?:number,dtr?:boolean,rts?:boolean,dataBits?:number,parity?:string,stopBits?:number}} options */
    set(o) {
      const lines = [this.handle, ...options(o, [['baud', 'baud'], ['dtr', 'dtr', true], ['rts', 'rts', true],
        ['dataBits', 'data'], ['parity', 'parity'], ['stopBits', 'stop']])];
      return ask('serial-set', lines).then((answer) => {
        if (o && o.baud !== undefined) this.baud = o.baud;
        return answer;
      });
    }

    /** @returns {Promise<void>} */
    close() { return this.stop(); }
  }

  // --- data -----------------------------------------------------------

  function toBase64(bytes) {
    let text = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(text);
  }

  function fromBase64(data) {
    const text = atob(data);
    const bytes = new Uint8Array(text.length);
    for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
    return bytes;
  }

  /** A promise of the bytes of a Uint8Array (any view), an ArrayBuffer, a Blob or a string (UTF-8). */
  function bytesOf(data) {
    if (data instanceof Uint8Array) return Promise.resolve(data);
    if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data));
    if (ArrayBuffer.isView(data)) return Promise.resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      return data.arrayBuffer().then((buffer) => new Uint8Array(buffer));
    }
    if (typeof data === 'string') return Promise.resolve(new TextEncoder().encode(data));
    return Promise.reject(new MynxError('data is a Uint8Array, a Blob or a string', 'refused'));
  }

  /** [bytes] (anything with a length), or a refusal over 16 MB. */
  function checked(bytes) {
    if (bytes.length > MAX_BYTES) {
      const mb = (bytes.length / 1024 / 1024).toFixed(1);
      throw new MynxError(`that's too big for the bridge: ${mb} MB (at most 16 MB)`, 'refused');
    }
    return bytes;
  }

  /** A photo's or recording's answer: {type, bytes, …, blob} instead of data. */
  function withBlob(answer) {
    const out = Object.assign({}, answer);
    delete out.data;
    out.blob = new Blob([fromBase64(answer.data || '')], { type: answer.type });
    return out;
  }

  /** Percent-encoded as `mynx` does it (Python's quote(text, safe="")). */
  function encoded(text) {
    return encodeURIComponent(String(text))
      .replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
  }

  /** A sound file's type by its first bytes, for bytes given without one. */
  function soundType(bytes) {
    const at = (offset, text) => [...text].every((c, i) => bytes[offset + i] === c.charCodeAt(0));
    if (at(0, 'RIFF')) return 'audio/wav';
    if (at(0, 'OggS')) return 'audio/ogg';
    if (at(0, 'fLaC')) return 'audio/flac';
    if (at(4, 'ftyp')) return 'audio/mp4';
    if (at(0, 'ID3') || (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0)) return 'audio/mpeg';
    return 'application/octet-stream';
  }

  /**
   * The lines for the options given, in [keys] order: [key, word] gives
   * `word=value`, or `word` alone for true (false and undefined: none);
   * [key, word, true] gives `word=1|0` for a boolean.
   */
  function options(given, keys) {
    const o = given || {};
    const lines = [];
    for (const [key, word, flag] of keys) {
      const value = o[key];
      if (value === undefined || value === null) continue;
      if (flag) lines.push(`${word}=${value ? 1 : 0}`);
      else if (value === true) lines.push(word);
      else if (value !== false) lines.push(`${word}=${value}`);
    }
    return lines;
  }

  const one = (value) => (value === undefined || value === null ? [] : [value]);

  // --- the calls ------------------------------------------------------
  // Each documents: the wire call and its lines, the answer, and the
  // permission.

  const mynx = {
    /**
     * Resolves {app, label, calls} once the bridge's port has come;
     * rejects 'no-bridge' after 5 s. Every call waits for it.
     * @type {Promise<{app:string,label:string,calls:string[]}>}
     */
    ready,
    /** The app's name, once ready (null before). */
    app: null,
    /** The app's label, once ready (null before). */
    label: null,
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
  mynx.location = function (o) { return ask('location', options(o, [['gps', 'gps'], ['timeout', 'timeout']])); };
  /**
   * 'location-stream' [`gps`, `interval=N`]: a MynxStream of FIX.
   * Permission: location.
   * @param {{gps?:boolean, every?:number}} [options] every: seconds between fixes
   */
  mynx.location.stream = function (o) {
    return stream('location-stream', options(o, [['gps', 'gps'], ['every', 'interval']]));
  };

  /**
   * One reading. 'sensor' [NAME, `timeout=N`] → {reading: {sensor,
   * values, unit, accuracy, time}}. No permission (the step sensors
   * need Android's, asked by mynx).
   * @param {string} name e.g. 'compass', 'accelerometer', 'light'
   * @param {{timeout?:number}} [options]
   */
  mynx.sensor = function (name, o) { return ask('sensor', [name, ...options(o, [['timeout', 'timeout']])]); };
  /** 'sensor-list' [] → {sensors: [{name, values, unit}]}. */
  mynx.sensor.list = function () { return ask('sensor-list', []); };
  /**
   * 'sensor-stream' [NAME, `rate=HZ`]: a MynxStream of readings.
   * @param {string} name
   * @param {{rate?:number}} [options] 1 to 200 a second (10 unless given)
   */
  mynx.sensor.stream = function (name, o) {
    return stream('sensor-stream', [name, ...options(o, [['rate', 'rate']])]);
  };

  /**
   * 'vibrate' [MS, `strength=PCT`] → {}. Returns at once. No permission.
   * @param {number} [ms] 1 to 5000 (300 unless given)
   * @param {{strength?:number}} [options] 1 to 100
   */
  mynx.vibrate = function (ms, o) { return ask('vibrate', [...one(ms), ...options(o, [['strength', 'strength']])]); };
  /** 'vibrate-effect' [NAME] → {ms} or {stopped: true}; waits until it ends. @param {'click'|'double-click'|'tick'|'heavy-click'} name */
  mynx.vibrate.effect = function (name) { return ask('vibrate-effect', [name]); };
  /**
   * 'vibrate-wave' [STEP…, `repeat`]: a MynxStream with no readings;
   * `done` gives {ms} or {stopped: true}. With repeat it runs until
   * stopped.
   * @param {Array<number|string>} steps MS or 'MS@PERCENT' ('100@0' a pause)
   * @param {{repeat?:boolean}} [options]
   */
  mynx.vibrate.wave = function (steps, o) {
    return stream('vibrate-wave', [...(steps || []), ...options(o, [['repeat', 'repeat']])]);
  };
  /** 'vibrate-chain' [PRIM…] → {ms} or {stopped: true}. @param {string[]} primitives 'NAME[@PERCENT][+MS]' */
  mynx.vibrate.chain = function (primitives) { return ask('vibrate-chain', [...(primitives || [])]); };
  /** 'vibrate-list' [] → {amplitude_control, effects, primitives}. */
  mynx.vibrate.list = function () { return ask('vibrate-list', []); };
  /** 'vibrate-stop' [] → {}. */
  mynx.vibrate.stop = function () { return ask('vibrate-stop', []); };

  mynx.torch = {
    /** 'torch' [`on`, PERCENT] → {}. No permission. @param {number} [percent] 1 to 100 (Android 13+) */
    on(percent) { return ask('torch', ['on', ...one(percent)]); },
    /** 'torch' [`off`] → {}. */
    off() { return ask('torch', ['off']); },
  };

  /**
   * 'battery' [] → {battery: {percent, status, plugged, health,
   * temperature, voltage, current, current_average, charge,
   * time_to_full, cycles, technology, battery_saver, thermal, time}}.
   * No permission.
   */
  mynx.battery = function () { return ask('battery', []); };
  /** 'battery-stream' [`interval=N`]: a MynxStream of battery objects. @param {{every?:number}} [options] */
  mynx.battery.stream = function (o) { return stream('battery-stream', options(o, [['every', 'interval']])); };

  mynx.clipboard = {
    /** 'clipboard-get' [] → the text (a string; the answer's `text`). Permission: clipboard. */
    get() { return ask('clipboard-get', [], (answer) => answer.text); },
    /** 'clipboard-set' [ENCODED] → {}. No permission. @param {string} text */
    set(text) { return ask('clipboard-set', [encoded(text)]); },
  };

  mynx.share = {
    /** 'share-text' [ENCODED] → {}. Opens Android's share sheet. No permission. @param {string} text */
    text(text) { return ask('share-text', [encoded(text)]); },
    /**
     * 'share-files' [`name=NAME`, `type=MIME`, `data=BASE64`, … per
     * file] → {count}. No permission. A file's type is its own, else
     * a Blob's, else text/plain for a string (application/octet-stream
     * for bytes). 16 MB in all at most.
     * @param {Array<{name:string, type?:string, data:Uint8Array|Blob|string}>} files
     */
    files(files) {
      const list = files || [];
      const lines = Promise.all(list.map((file) => bytesOf(file.data))).then((all) => {
        checked({ length: all.reduce((sum, bytes) => sum + bytes.length, 0) });
        return list.flatMap((file, i) => {
          const type = file.type || (file.data && file.data.type) ||
            (typeof file.data === 'string' ? 'text/plain' : 'application/octet-stream');
          return [`name=${file.name}`, `type=${type}`, `data=${toBase64(all[i])}`];
        });
      });
      return ask('share-files', lines);
    },
  };

  /**
   * 'notify' [TITLE, TEXT, `if-away`] → {shown, reason?}. Tapping it
   * opens the app's screen. Permission: notifications. Line breaks
   * become spaces, as `mynx notify` makes them.
   * @param {string} title
   * @param {string} [text]
   * @param {{ifAway?:boolean}} [options] ifAway: not while the app's screen is in front
   */
  mynx.notify = function (title, text, o) {
    const flat = (value) => String(value === undefined || value === null ? '' : value).replace(/\n/g, ' ');
    return ask('notify', [flat(title), flat(text), ...options(o, [['ifAway', 'if-away']])]);
  };

  /**
   * A photo: {quick: 'back'|'front'} → 'camera-quick' [FACING], taken
   * at once with no screen; without quick → 'camera' [] (the phone's
   * camera app; only when mynx.can has 'camera'). Resolves {type:
   * 'image/jpeg', bytes, blob}. Permission: camera.
   * @param {{quick?:'back'|'front'}} [options]
   */
  mynx.camera = function (o) {
    const quick = o && o.quick;
    return quick ? ask('camera-quick', [quick], withBlob) : ask('camera', [], withBlob);
  };

  function soundLines(source, type) {
    return bytesOf(source).then((bytes) => {
      checked(bytes);
      return [`data=${toBase64(bytes)}`, `type=${type || (source && source.type) || soundType(bytes)}`];
    });
  }

  mynx.audio = {
    /**
     * 'audio-record' [`seconds=N`, `rate=HZ`, `format=F`]: a MynxStream
     * (one reading {recording: true} once it records); stop() or N s
     * ends it, and `done` gives {type, bytes, seconds, blob}.
     * Permission: microphone.
     * @param {{seconds?:number, rate?:number, format?:'m4a'|'aac'|'ogg'|'opus'|'wav'}} [options] format m4a unless given
     */
    record(o) {
      return stream('audio-record', options(o, [['seconds', 'seconds'], ['rate', 'rate'], ['format', 'format']]),
        { done: withBlob });
    },
    /**
     * Plays through the phone's speaker: 'audio-play' [`file=PATH`]
     * for a string naming a file of a static app's own (same origin),
     * else the bytes ('audio-play' [`data=BASE64`, `type=MIME`]; a URL
     * is fetched first). A MynxStream; `done` gives {seconds} when it
     * ends. No permission. Bytes with no type (a Uint8Array) are known
     * by their first bytes (wav, ogg, flac, mp4, mp3).
     * @param {string|Blob|Uint8Array} source
     */
    play(source) {
      let lines;
      if (typeof source === 'string') {
        const url = new URL(source, page.location.href);
        const own = url.origin === page.location.origin && /\.mynx\.invalid$/.test(url.hostname);
        lines = own
          ? [`file=${decodeURIComponent(url.pathname)}`]
          : Promise.resolve(page.fetch(url.href)).then((response) => {
            if (!response.ok) throw new MynxError(`couldn't fetch ${url.href} (${response.status})`, 'refused');
            return response.blob();
          }).then((blob) => soundLines(blob));
      } else {
        lines = soundLines(source);
      }
      return stream('audio-play', lines);
    },
  };

  mynx.rotation = {
    /** 'rotation-lock' [SIDE] → {locked}; holds the app's screen only, until unlock or the page goes. @param {'portrait'|'landscape'} [side] none: as it is */
    lock(side) { return ask('rotation-lock', one(side)); },
    /** 'rotation-unlock' [] → {locked: 'no'}. */
    unlock() { return ask('rotation-unlock', []); },
  };

  const SCAN = [['seconds', 'seconds'], ['name', 'name'], ['service', 'service'], ['all', 'all']];

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
    scan(o) { return ask('ble-scan', options(o, SCAN)); },
    /** 'ble-services' [DEVICE] → {device, mtu, services}. @param {string} device */
    services(device) { return ask('ble-services', [device]); },
    /** 'ble-read' [DEVICE, CHAR, `as=F`] → {device, char, name, as, value, hex}. @param {string} device @param {string} char @param {{as?:string}} [options] */
    read(device, char, o) { return ask('ble-read', [device, char, ...options(o, [['as', 'as']])]); },
    /**
     * 'ble-write' [DEVICE, CHAR, VALUE, `as=F`, `no-response`] →
     * {device, char, name, bytes}. A Uint8Array is sent as hex
     * (as=hex); a string with as 'text' unless given; a number needs as.
     * @param {string} device @param {string} char
     * @param {Uint8Array|string|number} value
     * @param {{as?:string, noResponse?:boolean}} [options]
     */
    write(device, char, value, o) {
      const given = o || {};
      let as = given.as;
      let text;
      if (value instanceof Uint8Array) {
        text = [...value].map((b) => b.toString(16).padStart(2, '0')).join('');
        as = as || 'hex';
      } else if (typeof value === 'string') {
        text = value;
        as = as || 'text';
      } else if (typeof value === 'number' && as) {
        text = String(value);
      } else {
        const why = typeof value === 'number' ? "a number needs its format: {as: 'u8'}, 'i16', …"
          : 'the value is a Uint8Array, a string or a number';
        return Promise.reject(new MynxError(why, 'refused', 'ble-write'));
      }
      return ask('ble-write', [device, char, text, `as=${as}`, ...options(given, [['noResponse', 'no-response']])]);
    },
    /** 'ble-notify' [DEVICE, CHAR, `as=F`]: a MynxStream of {char, name, as, value, hex, time}. */
    notify(device, char, o) { return stream('ble-notify', [device, char, ...options(o, [['as', 'as']])]); },
    /** 'ble-connect' [DEVICE]: a MynxStream of {state, device, mtu}; holds the link (other calls on it are quick) until stopped. */
    connect(device) { return stream('ble-connect', [device]); },
  };
  /** 'ble-scan-stream' [`name=TEXT`, `service=UUID`, `all`]: a MynxStream of ADVERT. */
  mynx.ble.scan.stream = function (o) { return stream('ble-scan-stream', options(o, SCAN.slice(1))); };

  /** Serial ports, USB and Bluetooth (permission: serial). One user at a time. */
  mynx.serial = {
    /** 'serial-list' [] → {ports: [{name, kind, path, url, in_use, device}]}. */
    list() { return ask('serial-list', []); },
    /**
     * 'serial-open' [PORT, `baud=N`, `data=N`, `parity=P`, `stop=S`,
     * `lower`] → a SerialPort once open (its first line). 'refused'
     * "PORT is busy" while a program or `mynx usb monitor` has it.
     * @param {string} port 'ttyACM0', 'ttyUSB0', 'rfcomm0', 'nus0'
     * @param {{baud?:number, dataBits?:5|6|7|8, parity?:'none'|'even'|'odd'|'mark'|'space', stopBits?:1|1.5|2, lower?:boolean}} [options]
     *   baud 115200, 8N1 unless given; lower: open with DTR and RTS down
     * @returns {Promise<SerialPort>}
     */
    open(name, o) {
      const lines = [name, ...options(o, [['baud', 'baud'], ['dataBits', 'data'], ['parity', 'parity'],
        ['stopBits', 'stop'], ['lower', 'lower']])];
      return new Promise((resolve, reject) => new SerialPort(lines, { resolve, reject }));
    },
  };

  /**
   * The app's own background service (app.conf's `service`) only; no
   * permission. NAME may be left out (it's the app's own); any other
   * name, or an app with no service, is 'refused'.
   */
  mynx.service = {
    /** 'service-list' [] → {services: [SERVICE]} (only the app's own). */
    list() { return ask('service-list', []); },
    /** 'service-start' [NAME] → {service}. @param {string} [name] */
    start(name) { return ask('service-start', one(name)); },
    /** 'service-stop' [NAME] → {service}. Stopping it ends the bridge at the next page load. */
    stop(name) { return ask('service-stop', one(name)); },
    /** 'service-restart' [NAME] → {service}. */
    restart(name) { return ask('service-restart', one(name)); },
    /** 'service-log' [NAME, LINES] → {log}. @param {string} [name] @param {{lines?:number}} [options] 100 unless given */
    log(name, o) {
      return ask('service-log', o && o.lines !== undefined ? [name || '', o.lines] : one(name));
    },
  };

  page.mynx = mynx;
})();
