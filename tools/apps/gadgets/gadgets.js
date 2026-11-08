// Gadgets: one card per thing the phone does, through mynx.js (loaded
// in the page's head from https://mynx.invalid/mynx.js). Every call
// returns a promise or a stream; a refusal is a MynxError with a
// message for people and a code: denied (this app isn't allowed),
// refused, closed or no-bridge.
'use strict';

const $ = (selector, root = document) => root.querySelector(selector);

/** Shows a refusal on its card (or clears it). */
function showError(card, error) {
  const why = error ? `${error.message}${error.code ? ` (${error.code})` : ''}` : '';
  $('.error', card).textContent = why;
}

/** Runs [work] for [card], showing its error there. */
async function guarded(card, work) {
  showError(card, null);
  try {
    await work();
  } catch (error) {
    showError(card, error);
  }
}

// --- Compass: a sensor stream ------------------------------------------

async function compass() {
  const card = $('#compass');
  await guarded(card, async () => {
    for await (const reading of mynx.sensor.stream('compass', { rate: 10 })) {
      const azimuth = reading.values.azimuth;
      $('.needle', card).style.transform = `rotate(${-azimuth}deg)`;
      $('.value', card).textContent = `${Math.round(azimuth)}° from north`;
    }
  });
}

// --- Battery: one reading, then each change ------------------------------

function batteryText(b) {
  const parts = [`${b.percent} %`, b.status];
  if (b.temperature !== null) parts.push(`${b.temperature.toFixed(1)} °C`);
  return parts.join(' · ');
}

async function battery() {
  const card = $('#battery');
  await guarded(card, async () => {
    $('.value', card).textContent = batteryText((await mynx.battery()).battery);
    for await (const b of mynx.battery.stream()) $('.value', card).textContent = batteryText(b);
  });
}

// --- Flashlight and vibration: no permission needed ----------------------

function buttons() {
  const card = $('#buttons');
  let on = false;
  $('#torch').onclick = () => guarded(card, async () => {
    await (on ? mynx.torch.off() : mynx.torch.on());
    on = !on;
    $('#torch').textContent = on ? 'Flashlight off' : 'Flashlight on';
  });
  $('#buzz').onclick = () => guarded(card, () => mynx.vibrate(200));
}

// --- Location: asked the first time ---------------------------------------

function locationCard() {
  const card = $('#location');
  $('#locate').onclick = () => guarded(card, async () => {
    $('.value', card).textContent = 'Looking…';
    try {
      const fix = (await mynx.location({ timeout: 60 })).location;
      const accuracy = fix.accuracy === null ? '' : ` ±${Math.round(fix.accuracy)} m`;
      $('.value', card).textContent = `${fix.latitude.toFixed(5)}, ${fix.longitude.toFixed(5)}${accuracy}\n` +
        `from ${fix.provider}`;
    } catch (error) {
      $('.value', card).textContent = '';
      throw error;
    }
  });
}

// --- Bluetooth LE: a scan's list ------------------------------------------

function bleCard() {
  const card = $('#ble');
  const list = $('.list', card);
  $('#scan').onclick = () => guarded(card, async () => {
    list.textContent = '';
    $('#scan').disabled = true;
    try {
      const { devices } = await mynx.ble.scan({ seconds: 5 });
      for (const device of devices) {
        const item = document.createElement('li');
        item.textContent = `${device.name || '(no name)'}  ${device.address}  ${device.rssi} dBm`;
        list.append(item);
      }
      if (!devices.length) list.textContent = 'Nothing heard.';
    } finally {
      $('#scan').disabled = false;
    }
  });
}

// --- Serial console: one port at a time -----------------------------------

function serialCard() {
  const card = $('#serial');
  const output = $('#output');
  const input = $('#line');
  const decoder = new TextDecoder();
  let port = null;

  const print = (text) => {
    output.textContent += text;
    output.scrollTop = output.scrollHeight;
  };

  const refresh = () => guarded(card, async () => {
    const { ports } = await mynx.serial.list();
    const select = $('#ports');
    select.textContent = '';
    for (const p of ports.length ? ports : [{ name: '', device: '(no ports)' }]) {
      const option = document.createElement('option');
      option.value = p.name;
      option.textContent = p.name ? `${p.name}  ${p.device || p.kind}` : p.device;
      select.append(option);
    }
  });

  const closed = () => {
    port = null;
    input.disabled = true;
    $('#connect').textContent = 'Open';
  };

  $('#refresh').onclick = refresh;
  $('#connect').onclick = () => guarded(card, async () => {
    if (port) {
      await port.close();
      closed();
      return;
    }
    const name = $('#ports').value;
    if (!name) throw new Error('Plug in a board, then ↻');
    port = await mynx.serial.open(name, { baud: 115200 });
    print(`--- ${port.name} at ${port.baud} baud\n`);
    input.disabled = false;
    $('#connect').textContent = 'Close';
    const open = port;
    try {
      for await (const bytes of open) print(decoder.decode(bytes, { stream: true }));
    } finally {
      if (port === open) closed();
    }
  });
  $('#send').onsubmit = (event) => {
    event.preventDefault();
    if (!port) return;
    const text = input.value;
    input.value = '';
    guarded(card, () => port.write(text + '\r\n'));
  };
  return refresh;
}

// --- start ----------------------------------------------------------------

buttons();
locationCard();
bleCard();
const refreshPorts = serialCard();

mynx.ready.then((hello) => {
  $('#status').textContent = `Connected to mynx as ${hello.app}.`;
  compass();
  battery();
  refreshPorts();
}, (error) => {
  $('#status').textContent = error.message;
  document.querySelectorAll('.card').forEach((card) => showError(card, error));
});
