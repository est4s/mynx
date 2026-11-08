// tools/web/mynx.js against a fake port. Run: node --test tests/js
// (tests/mynx/test_mynx_js.py runs it, skipped without node).
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { load, bridgeCalls } = require('./harness');

const b64 = (bytes) => Buffer.from(bytes).toString('base64');

test('ready resolves with the hello, and app and can follow it', async () => {
  const page = load();
  assert.equal(page.mynx.app, null);
  assert.equal(page.mynx.can.size, 0);
  page.port(['battery', 'torch']);
  const hello = await page.mynx.ready;
  assert.deepEqual(hello, { app: 'gadgets', label: 'Gadgets', calls: ['battery', 'torch'] });
  assert.equal(page.mynx.app, 'gadgets');
  assert.deepEqual([...page.mynx.can], ['battery', 'torch']);
});

test('messages that are not the bridge are ignored', async () => {
  const page = load();
  page.other('hello');
  page.other({ some: 'thing' });
  const port = page.port();
  await page.mynx.ready;
  assert.ok(port.started || port.onmessage);
});

test('ready rejects no-bridge after 5 s, and so does every call', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const page = load();
  const ready = page.mynx.ready.then(() => 'resolved', (e) => e);
  const call = page.mynx.battery().then(() => 'resolved', (e) => e);
  t.mock.timers.tick(4999);
  await Promise.resolve();
  t.mock.timers.tick(1);
  const error = await ready;
  assert.ok(error instanceof page.mynx.MynxError);
  assert.equal(error.code, 'no-bridge');
  assert.equal((await call).code, 'no-bridge');
  assert.equal((await page.mynx.torch.on().catch((e) => e)).code, 'no-bridge');
});

test('a call waits for the port, then answers without ok', async () => {
  const page = load();
  const answer = page.mynx.battery();
  const port = page.port();
  const sent = await port.call();
  assert.deepEqual(sent, { id: sent.id, call: 'battery', args: [] });
  assert.ok(Number.isInteger(sent.id) && sent.id >= 1);
  port.reply(sent.id, { ok: true, battery: { percent: 80 } });
  assert.deepEqual(await answer, { battery: { percent: 80 } });
});

test('ids are new for each call', async () => {
  const page = load();
  const port = page.port();
  page.mynx.battery();
  page.mynx.battery();
  const one = await port.call();
  const two = await port.call();
  assert.notEqual(one.id, two.id);
});

test('a refusal rejects with a MynxError: message, code and call', async () => {
  const page = load();
  const port = page.port();
  const answer = page.mynx.location();
  const sent = await port.call();
  port.reply(sent.id, { ok: false, error: "Gadgets isn't allowed to use your location", code: 'denied' });
  const error = await answer.catch((e) => e);
  assert.ok(error instanceof Error);
  assert.equal(error.name, 'MynxError');
  assert.equal(error.message, "Gadgets isn't allowed to use your location");
  assert.equal(error.code, 'denied');
  assert.equal(error.call, 'location');
});

test('a refusal with no code is refused', async () => {
  const page = load();
  const port = page.port();
  const answer = page.mynx.battery();
  port.reply((await port.call()).id, { ok: false, error: 'no' });
  assert.equal((await answer.catch((e) => e)).code, 'refused');
});

test('a call the app does not offer is refused without asking it', async () => {
  const page = load();
  const port = page.port(['battery']);
  const error = await page.mynx.camera().catch((e) => e);
  assert.equal(error.code, 'refused');
  assert.equal(error.message, "camera isn't offered to apps");
  assert.equal(port.sent.length, 0);
});

test('the first port is kept: a later one is closed and calls keep going on the first', async () => {
  const page = load();
  const first = page.port();
  const answer = page.mynx.battery();
  const sent = await first.call();
  const second = page.port(null);
  assert.equal(second.closed, true);
  assert.equal(second.onmessage, null);
  first.reply(sent.id, { ok: true, battery: { percent: 5 } });
  assert.deepEqual(await answer, { battery: { percent: 5 } });
  page.mynx.battery();
  await first.call();
  assert.equal(second.sent.length, 0);
});

test('a port that comes after a late load is the first one', async () => {
  const page = load();
  const answer = page.mynx.torch.off();
  const port = page.port();
  port.reply((await port.call()).id, { ok: true });
  assert.deepEqual(await answer, {});
});

test('a call the session closed under rejects closed', async () => {
  const page = load();
  const port = page.port();
  const answer = page.mynx.location();
  port.reply((await port.call()).id, { ok: false, error: 'the page closed', code: 'closed' });
  assert.equal((await answer.catch((e) => e)).code, 'closed');
});

test('clipboard.get gives the text; set and share percent-encode as mynx does', async () => {
  const page = load();
  const port = page.port();
  const got = page.mynx.clipboard.get();
  port.reply((await port.call()).id, { ok: true, text: 'two\nlines' });
  assert.equal(await got, 'two\nlines');
  page.mynx.clipboard.set("a b\n!'()*~-._%");
  assert.deepEqual((await port.call()).args, ['a%20b%0A%21%27%28%29%2A~-._%25']);
  page.mynx.share.text('ü');
  assert.deepEqual((await port.call()).args, ['%C3%BC']);
});

test('a stream: readings by for await, then done with the answer', async () => {
  const page = load();
  const port = page.port();
  const stream = page.mynx.location.stream({ every: 5 });
  assert.ok(stream instanceof page.mynx.MynxStream);
  const sent = await port.call();
  assert.deepEqual(sent.args, ['interval=5']);
  port.line(sent.id, { latitude: 1 });
  port.line(sent.id, { latitude: 2 });
  const seen = [];
  const reading = (async () => {
    for await (const fix of stream) seen.push(fix.latitude);
  })();
  await new Promise((resolve) => setImmediate(resolve));
  port.line(sent.id, { latitude: 3 });
  port.reply(sent.id, { ok: true });
  await reading;
  assert.deepEqual(seen, [1, 2, 3]);
  assert.deepEqual(await stream.done, {});
});

test('stop sends the cancel and done gets the final answer; twice is harmless', async () => {
  const page = load();
  const port = page.port();
  const stream = page.mynx.sensor.stream('compass', { rate: 5 });
  const sent = await port.call();
  const stopped = stream.stop();
  await port.cancelled(sent.id);
  stream.stop();
  port.reply(sent.id, { ok: true });
  await stopped;
  assert.deepEqual(await stream.done, {});
  assert.equal(port.sent.filter((m) => m.cancel).length, 1);
  assert.deepEqual(await stream.next(), { value: undefined, done: true });
});

test('stop before the port came sends nothing and ends it', async () => {
  const page = load();
  const stream = page.mynx.battery.stream();
  await stream.stop();
  assert.deepEqual(await stream.done, {});
  const port = page.port();
  await page.mynx.ready;
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(port.sent.length, 0);
});

test('breaking out of for await stops the stream', async () => {
  const page = load();
  const port = page.port();
  const stream = page.mynx.battery.stream();
  const sent = await port.call();
  port.line(sent.id, { percent: 50 });
  for await (const b of stream) {
    assert.equal(b.percent, 50);
    break;
  }
  await port.cancelled(sent.id);
});

test('a refused stream rejects done and throws from the iterator', async () => {
  const page = load();
  const port = page.port();
  const stream = page.mynx.location.stream();
  const sent = await port.call();
  port.reply(sent.id, { ok: false, error: 'android-location is off', code: 'refused' });
  await assert.rejects(stream.done, (e) => e.code === 'refused' && e.message === 'android-location is off');
  await assert.rejects(stream.next(), (e) => e.code === 'refused');
});

test('a stream whose session closed rejects closed', async () => {
  const page = load();
  const port = page.port();
  const stream = page.mynx.battery.stream();
  const sent = await port.call();
  page.port(null); // closed, and the stream goes on
  port.line(sent.id, { percent: 9 });
  assert.deepEqual((await stream.next()).value, { percent: 9 });
  port.reply(sent.id, { ok: false, error: 'the page closed', code: 'closed' });
  await assert.rejects(stream.done, (e) => e.code === 'closed');
});

test('camera: a quick photo comes back as a Blob', async () => {
  const page = load();
  const port = page.port();
  const photo = page.mynx.camera({ quick: 'front' });
  const sent = await port.call();
  assert.deepEqual([sent.call, ...sent.args], ['camera-quick', 'front']);
  port.reply(sent.id, { ok: true, type: 'image/jpeg', bytes: 3, data: b64([0xff, 0xd8, 0xff]) });
  const answer = await photo;
  assert.equal(answer.type, 'image/jpeg');
  assert.equal(answer.bytes, 3);
  assert.ok(answer.blob instanceof Blob);
  assert.equal(answer.blob.type, 'image/jpeg');
  assert.deepEqual([...new Uint8Array(await answer.blob.arrayBuffer())], [0xff, 0xd8, 0xff]);
  assert.equal(answer.data, undefined);
});

test('audio.record: a stream whose done has the recording as a Blob', async () => {
  const page = load();
  const port = page.port();
  const recording = page.mynx.audio.record({ seconds: 3, format: 'wav' });
  const sent = await port.call();
  assert.deepEqual(sent.args, ['seconds=3', 'format=wav']);
  port.line(sent.id, { recording: true });
  assert.deepEqual((await recording.next()).value, { recording: true });
  port.reply(sent.id, { ok: true, type: 'audio/wav', bytes: 2, seconds: 3, data: b64([1, 2]) });
  const done = await recording.done;
  assert.equal(done.seconds, 3);
  assert.equal(done.type, 'audio/wav');
  assert.deepEqual([...new Uint8Array(await done.blob.arrayBuffer())], [1, 2]);
});

test('audio.play: an own file by its path, bytes as base64 with their type', async () => {
  const page = load();
  const port = page.port();
  page.mynx.audio.play('sounds/ding.mp3').done.catch(() => {});
  assert.deepEqual((await port.call()).args, ['file=/sounds/ding.mp3']);
  page.mynx.audio.play(new Blob([new Uint8Array([7])], { type: 'audio/ogg' })).done.catch(() => {});
  assert.deepEqual((await port.call()).args, ['data=' + b64([7]), 'type=audio/ogg']);
  page.mynx.audio.play(new Uint8Array([0x52, 0x49, 0x46, 0x46])).done.catch(() => {});
  assert.deepEqual((await port.call()).args, ['data=' + b64([0x52, 0x49, 0x46, 0x46]), 'type=audio/wav']);
});

test('audio.play: a URL elsewhere is fetched first', async () => {
  const fetched = [];
  const fetch = async (url) => {
    fetched.push(String(url));
    return { ok: true, blob: async () => new Blob([new Uint8Array([9])], { type: 'audio/mpeg' }) };
  };
  const page = load({ origin: 'http://127.0.0.1:8080', fetch });
  const port = page.port();
  page.mynx.audio.play('/ding.mp3').done.catch(() => {});
  assert.deepEqual((await port.call()).args, ['data=' + b64([9]), 'type=audio/mpeg']);
  assert.deepEqual(fetched, ['http://127.0.0.1:8080/ding.mp3']);
});

test('share.files: name, type and data per file, strings as UTF-8', async () => {
  const page = load();
  const port = page.port();
  const shared = page.mynx.share.files([
    { name: 'a.txt', data: 'ü' },
    { name: 'b.bin', type: 'application/x-b', data: new Uint8Array([1, 2]) },
    { name: 'c.png', data: new Blob([new Uint8Array([3])], { type: 'image/png' }) },
  ]);
  const sent = await port.call();
  assert.deepEqual(sent.args, [
    'name=a.txt', 'type=text/plain', 'data=' + b64(Buffer.from('ü')),
    'name=b.bin', 'type=application/x-b', 'data=' + b64([1, 2]),
    'name=c.png', 'type=image/png', 'data=' + b64([3]),
  ]);
  port.reply(sent.id, { ok: true, count: 3 });
  assert.deepEqual(await shared, { count: 3 });
});

test('more than 16 MB is refused before it is sent', async () => {
  const page = load();
  const port = page.port();
  const big = new Uint8Array(16 * 1024 * 1024 + 1);
  const error = await page.mynx.share.files([{ name: 'big', data: big }]).catch((e) => e);
  assert.equal(error.code, 'refused');
  assert.match(error.message, /16 MB/);
  assert.equal(port.sent.length, 0);
});

test('ble.write: bytes as hex, strings as text unless given, a number needs as', async () => {
  const page = load();
  const port = page.port();
  page.mynx.ble.write('mynx-ble', 'nus-rx', new Uint8Array([0x0a, 0x1b]));
  assert.deepEqual((await port.call()).args, ['mynx-ble', 'nus-rx', '0a1b', 'as=hex']);
  page.mynx.ble.write('mynx-ble', 'nus-rx', 'hi', { noResponse: true });
  assert.deepEqual((await port.call()).args, ['mynx-ble', 'nus-rx', 'hi', 'as=text', 'no-response']);
  page.mynx.ble.write('mynx-ble', 'x', 5, { as: 'u8' });
  assert.deepEqual((await port.call()).args, ['mynx-ble', 'x', '5', 'as=u8']);
  const error = await page.mynx.ble.write('mynx-ble', 'x', 5).catch((e) => e);
  assert.equal(error.code, 'refused');
  assert.match(error.message, /as/);
});

test('serial.open: a SerialPort once open, Uint8Array chunks, write, set, close', async () => {
  const page = load();
  const port = page.port();
  const opening = page.mynx.serial.open('ttyACM0', { baud: 9600, lower: true });
  const sent = await port.call();
  assert.deepEqual([sent.call, ...sent.args], ['serial-open', 'ttyACM0', 'baud=9600', 'lower']);
  port.line(sent.id, { open: { port: 'ttyACM0', handle: 4, baud: 9600 } });
  const serial = await opening;
  assert.ok(serial instanceof page.mynx.SerialPort);
  assert.equal(serial.name, 'ttyACM0');
  assert.equal(serial.handle, 4);
  assert.equal(serial.baud, 9600);
  port.line(sent.id, { data: b64([104, 105]) });
  const chunk = (await serial.next()).value;
  assert.ok(chunk instanceof Uint8Array);
  assert.deepEqual([...chunk], [104, 105]);

  const wrote = serial.write('hi\r');
  const write = await port.call();
  assert.deepEqual([write.call, ...write.args], ['serial-write', '4', b64(Buffer.from('hi\r'))]);
  port.reply(write.id, { ok: true, bytes: 3 });
  assert.deepEqual(await wrote, { bytes: 3 });

  serial.set({ baud: 115200, dtr: false, rts: true });
  const set = await port.call();
  assert.deepEqual([set.call, ...set.args], ['serial-set', '4', 'baud=115200', 'dtr=0', 'rts=1']);

  const closed = serial.close();
  await port.cancelled(sent.id);
  port.reply(sent.id, { ok: true });
  await closed;
});

test('serial.open refused before it opens rejects', async () => {
  const page = load();
  const port = page.port();
  const opening = page.mynx.serial.open('ttyACM0');
  const sent = await port.call();
  port.reply(sent.id, { ok: false, error: 'ttyACM0 is busy', code: 'refused' });
  await assert.rejects(opening, (e) => e.message === 'ttyACM0 is busy' && e.code === 'refused');
});

test('a serial port that goes away rejects its stream with why', async () => {
  const page = load();
  const port = page.port();
  const opening = page.mynx.serial.open('rfcomm0');
  const sent = await port.call();
  port.line(sent.id, { open: { port: 'rfcomm0', handle: 1, baud: 115200 } });
  const serial = await opening;
  port.reply(sent.id, { ok: false, error: 'the link dropped', code: 'refused' });
  await assert.rejects(serial.done, (e) => e.message === 'the link dropped');
});

test('vibrate.wave is a stream with no readings whose done has the answer', async () => {
  const page = load();
  const port = page.port();
  const wave = page.mynx.vibrate.wave([200, '100@0', 600], { repeat: true });
  const sent = await port.call();
  assert.deepEqual(sent.args, ['200', '100@0', '600', 'repeat']);
  port.reply(sent.id, { ok: true, stopped: true });
  assert.deepEqual(await wave.done, { stopped: true });
});

test('every call Bridge.kt offers has a function that sends it', async () => {
  const calls = {
    'location': (m) => m.location(), 'location-stream': (m) => m.location.stream(),
    'sensor-list': (m) => m.sensor.list(), 'sensor': (m) => m.sensor('light'),
    'sensor-stream': (m) => m.sensor.stream('light'), 'vibrate': (m) => m.vibrate(),
    'vibrate-effect': (m) => m.vibrate.effect('click'), 'vibrate-wave': (m) => m.vibrate.wave([1]),
    'vibrate-chain': (m) => m.vibrate.chain(['click']), 'vibrate-list': (m) => m.vibrate.list(),
    'vibrate-stop': (m) => m.vibrate.stop(), 'torch': (m) => m.torch.off(), 'battery': (m) => m.battery(),
    'battery-stream': (m) => m.battery.stream(), 'clipboard-get': (m) => m.clipboard.get(),
    'clipboard-set': (m) => m.clipboard.set('x'), 'share-text': (m) => m.share.text('x'),
    'share-files': (m) => m.share.files([{ name: 'a', data: 'x' }]), 'notify': (m) => m.notify('t'),
    'camera-quick': (m) => m.camera({ quick: 'back' }), 'camera': (m) => m.camera(),
    'audio-record': (m) => m.audio.record(), 'audio-play': (m) => m.audio.play(new Uint8Array([1])),
    'rotation-lock': (m) => m.rotation.lock(), 'rotation-unlock': (m) => m.rotation.unlock(),
    'ble-scan': (m) => m.ble.scan(), 'ble-scan-stream': (m) => m.ble.scan.stream(),
    'ble-services': (m) => m.ble.services('d'), 'ble-read': (m) => m.ble.read('d', 'c'),
    'ble-write': (m) => m.ble.write('d', 'c', 'v'), 'ble-notify': (m) => m.ble.notify('d', 'c'),
    'ble-connect': (m) => m.ble.connect('d'), 'serial-list': (m) => m.serial.list(),
    'serial-open': (m) => m.serial.open('ttyACM0'), 'service-list': (m) => m.service.list(),
    'service-start': (m) => m.service.start(), 'service-stop': (m) => m.service.stop(),
    'service-restart': (m) => m.service.restart(), 'service-log': (m) => m.service.log(),
  };
  // serial-write and serial-set go through an open SerialPort (tested above).
  const offered = bridgeCalls().filter((c) => c !== 'serial-write' && c !== 'serial-set');
  assert.deepEqual(Object.keys(calls).sort(), offered.sort());
  for (const [call, send] of Object.entries(calls)) {
    const page = load();
    const port = page.port();
    const result = send(page.mynx);
    (result.done || result).catch(() => {});
    const sent = await port.call();
    assert.equal(sent.call, call);
    assert.ok(sent.args.every((a) => typeof a === 'string'), call);
  }
});
