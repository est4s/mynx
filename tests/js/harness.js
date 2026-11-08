// Loads tools/web/mynx.js under node with a fake window and a fake
// bridge port, as the app's WebView would give them (core's Bridge.kt).
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SCRIPT = path.join(__dirname, '..', '..', 'tools', 'web', 'mynx.js');

/** The app's end of the channel: what the page posted, and ways to answer. */
class FakePort {
  constructor() {
    this.sent = [];
    this.onmessage = null;
    this.started = false;
    this.waiting = [];
  }

  // The page's side (MessagePort).
  postMessage(text) {
    if (typeof text !== 'string') throw new Error('the bridge carries strings only');
    const message = JSON.parse(text);
    this.sent.push(message);
    const waiters = this.waiting;
    this.waiting = [];
    waiters.forEach((w) => w());
  }

  start() { this.started = true; }

  close() { this.closed = true; }

  // The app's side.
  send(object) { this.onmessage({ data: JSON.stringify(object) }); }

  hello(calls, app = 'gadgets', label = 'Gadgets') { this.send({ hello: { app, label, calls } }); }

  reply(id, reply) { this.send({ id, reply }); }

  line(id, line) { this.send({ id, line }); }

  /** The page's next message whose `id` is new (a call), waiting for it. */
  async call() {
    const seen = this.calls || 0;
    for (;;) {
      const calls = this.sent.filter((m) => m.call !== undefined);
      if (calls.length > seen) {
        this.calls = seen + 1;
        return calls[seen];
      }
      await new Promise((resolve) => this.waiting.push(resolve));
    }
  }

  /** Waits for a cancel of [id]. */
  async cancelled(id) {
    for (;;) {
      if (this.sent.some((m) => m.id === id && m.cancel === true)) return;
      await new Promise((resolve) => this.waiting.push(resolve));
    }
  }
}

/** Every wire call Bridge.kt offers, as the hello sends them. */
function bridgeCalls() {
  const kotlin = fs.readFileSync(path.join(__dirname, '..', '..', 'core', 'src', 'main', 'kotlin', 'io', 'github',
    'est4s', 'terminal', 'core', 'Bridge.kt'), 'utf8');
  const table = kotlin.slice(kotlin.indexOf('val BRIDGE_CALLS'));
  return [...table.slice(0, table.indexOf('\n)\n')).matchAll(/(?:req|own)\("([a-z-]+)"/g)].map((m) => m[1]);
}

/**
 * A fresh mynx.js in this realm (so Uint8Array and Blob are the test's
 * own). [origin] is the page's; [fetch] the window's.
 */
function load({ origin = 'https://gadgets.mynx.invalid', fetch } = {}) {
  const listeners = [];
  const hellos = []; // [url, options] of each request for the port
  const window = {
    location: new URL(origin + '/index.html'),
    addEventListener(type, listener) { if (type === 'message') listeners.push(listener); },
    fetch(url, options) {
      if (String(url) === 'https://mynx.invalid/hello') {
        // The listener must already hear the port the app posts now.
        hellos.push([String(url), options, listeners.length]);
        return Promise.resolve({ ok: false, status: 0, type: 'opaque' });
      }
      return fetch ? fetch(url, options) : Promise.reject(new Error('no network here'));
    },
  };
  globalThis.window = window;
  vm.runInThisContext(fs.readFileSync(SCRIPT, 'utf8'), { filename: SCRIPT });
  const mynx = window.mynx;
  delete globalThis.window;
  return {
    mynx,
    window,
    hellos,
    /** Posts a port to the window, as the app does when the page asks (only then). */
    port(calls = bridgeCalls()) {
      if (!hellos.length) throw new Error('a port before mynx.js asked for one');
      const port = new FakePort();
      listeners.forEach((l) => l({ data: 'mynx-bridge', ports: [port] }));
      if (calls) port.hello(calls);
      return port;
    },
    /** A message that isn't the bridge's. */
    other(data) { listeners.forEach((l) => l({ data, ports: [] })); },
  };
}

module.exports = { load, FakePort, bridgeCalls };
