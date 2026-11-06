#!/usr/bin/env node
// Isolated executable fixtures for the actual vendored T3 viewer bundles (MIT).
// No network, real threads, emulators or host services are used.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
const assets = new URL('../android/app/src/main/assets/t3-streams/', import.meta.url);
const provenance = JSON.parse(await readFile(new URL('provenance.json', assets), 'utf8'));
const settle = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };

function fixture() {
  const nodes = [], sockets = [], messages = [], painted = [], configurations = [], decoded = [], timers = new Map();
  let nextTimer = 1;
  class Target {
    listeners = new Map();
    addEventListener(type, fn) { const list = this.listeners.get(type) ?? []; list.push(fn); this.listeners.set(type, list); }
    removeEventListener(type, fn) { this.listeners.set(type, (this.listeners.get(type) ?? []).filter(x => x !== fn)); }
    emit(type, values = {}) {
      const event = { type, preventDefault() {}, stopImmediatePropagation() {}, ...values };
      this[`on${type}`]?.(event);
      for (const fn of this.listeners.get(type) ?? []) fn(event);
    }
  }
  class Element extends Target {
    style = {}; children = []; attributes = {}; width = 0; height = 0; clientWidth = 400; clientHeight = 800;
    constructor(tag) { super(); this.tag = tag; nodes.push(this); }
    setAttribute(key, value) { this.attributes[key] = value; }
    append(...children) { this.children.push(...children); }
    prepend(...children) { this.children.unshift(...children); }
    replaceChildren(...children) { this.children = children; }
    getBoundingClientRect() { return { left: 0, top: 0, width: 400, height: 800 }; }
    getContext() { return { drawImage: (...args) => painted.push(args) }; }
    setPointerCapture() {}
    focus() {}
    blur() {}
    setSelectionRange() {}
    querySelector() { return null; }
  }
  class Socket extends Target {
    static OPEN = 1;
    readyState = 0; sent = [];
    constructor(url) { super(); this.url = url; sockets.push(this); }
    send(value) { assert.equal(this.readyState, 1, 'no sends to a disconnected socket'); this.sent.push(JSON.parse(value)); }
    open() { this.readyState = 1; this.emit('open'); }
    receive(data) { this.emit('message', { data }); }
    close() { this.readyState = 3; this.emit('close', { code: 1000, reason: '' }); }
  }
  class Decoder {
    static async isConfigSupported() { return { supported: true }; }
    state = 'unconfigured'; decodeQueueSize = 0;
    constructor(callbacks) { this.callbacks = callbacks; }
    configure(config) { configurations.push(config); this.state = 'configured'; }
    decode(chunk) { decoded.push(chunk); this.callbacks.output({ displayWidth: 400, displayHeight: 800, close() {} }); }
    close() { this.state = 'closed'; }
  }
  const setTimer = (callback, delay = 0) => { const id = nextTimer++; timers.set(id, { callback, delay }); return id; };
  const window = new Target();
  Object.assign(window, { ReactNativeWebView: { postMessage(value) { messages.push(JSON.parse(value)); } }, devicePixelRatio: 1 });
  const document = { documentElement: new Element('html'), body: new Element('body'), createElement: tag => new Element(tag), pictureInPictureEnabled: false };
  const context = vm.createContext({ window, document, WebSocket: Socket, VideoDecoder: Decoder,
    EncodedVideoChunk: class { constructor(value) { Object.assign(this, value); } },
    HTMLVideoElement: class {}, CanvasCaptureMediaStreamTrack: class {},
    ResizeObserver: class { constructor(callback) { this.callback = callback; } observe() { this.callback(); } disconnect() {} },
    fetch: async () => ({ ok: true, json: async () => ({}) }),
    createImageBitmap: async () => ({ width: 800, height: 600, close() {} }), Blob,
    setTimeout: setTimer, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: callback => setTimer(callback), cancelAnimationFrame: id => timers.delete(id),
    URLSearchParams, URL, TextEncoder, TextDecoder, ArrayBuffer, Uint8Array, DataView, AbortController,
    performance: { now: () => 0 }, crypto: { randomUUID: () => 'fixture' },
    WheelEvent: { DOM_DELTA_LINE: 1, DOM_DELTA_PAGE: 2 }, InputEvent: class {},
  });
  async function tick() {
    for (let round = 0; round < 5; round++) {
      for (const [id, timer] of [...timers]) if (timer.delay <= 150) { timers.delete(id); timer.callback(); }
      await settle();
    }
  }
  return { context, nodes, sockets, messages, painted, configurations, decoded, tick };
}
async function load(name, fixture) {
  const bytes = await readFile(new URL(`${name}-stream.js`, assets));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), provenance.bundles[`${name}-stream.js`].sha256);
  vm.runInContext(bytes.toString(), fixture.context, { filename: fileURLToPath(new URL(`${name}-stream.js`, assets)) });
  return fixture.context[name === 'device' ? 'T3DeviceStream' : 'T3PreviewStream'];
}
const access = { httpBase: 'https://fixture.example/api/device-hub', wsBase: 'wss://fixture.example/api/device-hub', query: { hostId: 'fixture-host', wsTicket: 'fixture-media' }, credentials: false };
{
  const f = fixture(), viewer = await load('device', f);
  viewer.text('drop before start');
  assert.equal(f.sockets.length, 0);
  viewer.start({ access, platform: 'android', deviceId: 'fixture device', colors: {} });
  await f.tick();
  assert.equal(f.sockets.length, 1);
  const socket = f.sockets[0];
  assert.match(socket.url, /^wss:\/\/fixture\.example\/api\/device-hub\/vendor\/serve-emu\/ws\?/);
  assert.equal(new URL(socket.url).searchParams.get('device'), 'fixture device');
  viewer.text('drop while not OPEN');
  assert.equal(socket.sent.length, 0);
  socket.open();
  socket.receive(Uint8Array.from([0, 0, 0, 1, 0x67, 0x64, 0, 0x1f, 0, 0, 1, 0x65]).buffer);
  await f.tick();
  assert.equal(f.configurations[0].codec, 'avc1.64001f');
  const semu = new ArrayBuffer(21), header = new DataView(semu);
  header.setUint32(0, 0x53454d55); header.setUint8(4, 1); header.setUint8(5, 1); header.setBigUint64(8, 123456n);
  new Uint8Array(semu).set([0, 0, 1, 0x65, 0xaa], 16);
  socket.receive(semu);
  assert.equal(f.decoded.at(-1).timestamp, 123456);
  assert.equal(f.painted.length, 1);
  assert(f.messages.some(m => m.type === 'status' && m.status === 'streaming'));
  const frame = f.nodes.find(node => node.attributes.role === 'application');
  frame.emit('pointerdown', { pointerId: 1, clientX: 200, clientY: 400 });
  frame.emit('pointerdown', { pointerId: 2, clientX: 20, clientY: 40 }); // Single touch.
  frame.emit('pointermove', { pointerId: 1, clientX: 300, clientY: 600 });
  frame.emit('pointerup', { pointerId: 1, clientX: 400, clientY: 800 });
  assert.deepEqual(socket.sent.filter(m => m.type === 'touch'), [
    { type: 'touch', action: 'down', x: .5, y: .5 },
    { type: 'touch', action: 'move', x: .75, y: .75 },
    { type: 'touch', action: 'up', x: 1, y: 1 },
  ]);
  const unicode = 'Café 👩‍💻 '.repeat(800);
  viewer.text(unicode);
  const text = socket.sent.filter(m => m.type === 'text');
  assert(text.length > 1, 'shared UTF-8 chunking must be used');
  assert.equal(text.map(m => m.text).join(''), unicode);
  socket.close();
  const count = socket.sent.length;
  viewer.text('drop after disconnect'); viewer.command('home'); viewer.stop(); viewer.text('drop after stop');
  await f.tick();
  assert.equal(socket.sent.length, count);
  assert.equal(f.sockets.length, 1, 'stopping clears the retry timer');
}
{
  const f = fixture(), viewer = await load('preview', f);
  viewer.start({ access: { ...access, httpBase: 'https://fixture.example/api/preview-stream', wsBase: 'wss://fixture.example/api/preview-stream' }, threadId: 'fixture', tabId: 'tab', interactive: true, background: '#000' });
  await f.tick();
  const socket = f.sockets[0]; socket.open();
  viewer.command({ type: 'text', text: 'drop before control' });
  assert.equal(socket.sent.length, 0);
  socket.receive(JSON.stringify({ type: 'control', canOperate: true, controller: 'you', generation: 1, dialog: null }));
  socket.receive(JSON.stringify({ type: 'viewport', width: 800, height: 600 }));
  socket.receive(Uint8Array.from([0xff, 0xd8, 0xff, 0xd9]).buffer);
  await f.tick();
  assert(socket.sent.some(m => m.type === 'ack'));
  assert.equal(f.painted.length, 1);
  assert(f.messages.some(m => m.type === 'status' && m.status === 'streaming'));
  const canvas = f.nodes.find(node => node.tag === 'canvas');
  canvas.emit('pointerdown', { isPrimary: true, pointerType: 'mouse', pointerId: 1, clientX: 200, clientY: 400, buttons: 1, button: 0, timeStamp: 1 });
  canvas.emit('pointerup', { isPrimary: true, pointerType: 'mouse', pointerId: 1, clientX: 200, clientY: 400, buttons: 0, button: 0, timeStamp: 2 });
  canvas.emit('wheel', { clientX: 200, clientY: 400, deltaX: 0, deltaY: 10, deltaMode: 0 });
  await f.tick();
  const mouse = socket.sent.filter(m => m.type === 'mouse');
  assert.deepEqual(mouse.map(m => [m.action, m.x, m.y]), [['down', 400, 300], ['up', 400, 300]]);
  assert(socket.sent.some(m => m.type === 'wheel' && m.deltaY === 10));
  viewer.command({ type: 'text', text: 'Café 👩‍💻' });
  assert(socket.sent.some(m => m.type === 'text' && m.text === 'Café 👩‍💻'));
  socket.receive(JSON.stringify({ type: 'control', canOperate: true, controller: 'agent', generation: 2, dialog: null }));
  const count = socket.sent.length;
  viewer.command({ type: 'text', text: 'drop without control' });
  viewer.stop(); viewer.command({ type: 'reload' });
  assert.equal(socket.sent.length, count);
}
console.log('Trusted stream bundle fixtures passed: H.264/SEMU, JPEG/ACK, single touch, browser input, Unicode text, stale/stopped input drops.');
