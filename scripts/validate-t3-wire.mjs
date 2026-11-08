#!/usr/bin/env node
// Live interoperability check against a disposable T3 server (MIT).
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const t3Root = resolve(process.argv[2] ?? fileURLToPath(new URL('../../t3code', import.meta.url)));
const root = await mkdtemp(resolve(tmpdir(), 'trefoil-wire-'));
const home = resolve(root, 'home');
await mkdir(home);
const port = await new Promise((res, rej) => {
  const server = createServer();
  server.on('error', rej);
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    server.close(() => res(port));
  });
});
const token = randomBytes(32).toString('hex');
const origin = `http://127.0.0.1:${port}`;
// Deliberately do not inherit T3, provider, telemetry, or cloud credentials.
const child = spawn(process.execPath, [resolve(t3Root, 'apps/server/src/bin.ts'),
  '--mode', 'web', '--host', '127.0.0.1', '--port', String(port),
  '--base-dir', resolve(root, 'state'), '--no-browser', '--dev-url', 'http://127.0.0.1:9'], {
  cwd: root,
  env: { PATH: process.env.PATH, HOME: home, TMPDIR: root, LANG: 'en_US.UTF-8',
    T3CODE_AUTO_BOOTSTRAP_PROJECT_FROM_CWD: 'false', T3CODE_DEV_AUTH_TOKEN: token,
    T3CODE_LOG_LEVEL: 'None', T3CODE_TRACE_TIMING_ENABLED: 'false' },
  stdio: ['ignore', 'pipe', 'pipe'],
});
const childExit = new Promise(res => child.once('exit', res));
// Drain output, but never display server logs or authentication material.
child.stdout.resume();
child.stderr.resume();
let socket;
try {
  let ready = false;
  for (let attempt = 0; attempt < 240; attempt++) {
    if (child.exitCode !== null) throw new Error(`Isolated T3 exited (${child.exitCode}) before readiness`);
    try {
      const response = await fetch(`${origin}/.well-known/t3/environment`, { signal: AbortSignal.timeout(500) });
      if (response.ok) { ready = true; break; }
    } catch {}
    await delay(250);
  }
  assert.ok(ready, 'isolated server readiness timed out');
  const incompatible = await fetch(`${origin}/ws`);
  assert.equal(incompatible.status, 426, 'missing protocol version is rejected');
  const sessionResponse = await fetch(`${origin}/api/auth/browser-session`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Origin: origin },
    body: JSON.stringify({ credential: token }),
  });
  assert.equal(sessionResponse.status, 200, 'browser-session exchange');
  const cookie = sessionResponse.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
  const pairingResponse = await fetch(`${origin}/api/auth/pairing-token`, {
    method: 'POST', headers: { Cookie: cookie, Origin: origin, 'Content-Type': 'application/json' },
    body: JSON.stringify({ label: 'Disposable Trefoil wire check' }),
  });
  assert.equal(pairingResponse.status, 200, 'isolated pairing credential creation');
  const pairing = await pairingResponse.json();
  assert.equal(typeof pairing.credential, 'string');
  const accessResponse = await fetch(`${origin}/oauth/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      subject_token: pairing.credential,
      subject_token_type: 'urn:t3:params:oauth:token-type:environment-bootstrap',
      requested_token_type: 'urn:ietf:params:oauth:token-type:access_token',
      client_label: 'Disposable Trefoil wire check', client_device_type: 'mobile', client_os: 'Android',
    }),
  });
  assert.equal(accessResponse.status, 200, 'native OAuth pairing exchange');
  const access = await accessResponse.json();
  assert.equal(access.token_type, 'Bearer');
  assert.equal(typeof access.access_token, 'string');
  const bearerHeaders = { Authorization: `Bearer ${access.access_token}` };
  const nativeSessionResponse = await fetch(`${origin}/api/auth/session`, { headers: bearerHeaders });
  assert.equal(nativeSessionResponse.status, 200, 'native bearer session verification');
  const nativeSession = await nativeSessionResponse.json();
  assert.equal(nativeSession.authenticated, true);
  const ticketResponse = await fetch(`${origin}/api/auth/websocket-ticket/rpc`, {
    method: 'POST', headers: bearerHeaders,
  });
  assert.equal(ticketResponse.status, 200, 'RPC ticket exchange');
  const { ticket } = await ticketResponse.json();
  assert.equal(typeof ticket, 'string');
  socket = new WebSocket(`${origin.replace('http:', 'ws:')}/ws?orchestrationProtocol=2&wsTicket=${encodeURIComponent(ticket)}`);
  const events = [];
  socket.addEventListener('message', event => {
    const parsed = JSON.parse(String(event.data));
    for (const message of Array.isArray(parsed) ? parsed : [parsed]) {
      events.push(message);
      if (message._tag === 'Chunk') socket.send(JSON.stringify({ _tag: 'Ack', requestId: message.requestId }));
    }
  });
  await new Promise((res, rej) => {
    socket.addEventListener('open', res, { once: true });
    socket.addEventListener('error', () => rej(new Error('isolated WebSocket handshake failed')), { once: true });
  });
  const waitFor = async predicate => {
    for (let attempt = 0; attempt < 100; attempt++) {
      const event = events.find(predicate);
      if (event) return event;
      await delay(50);
    }
    throw new Error('Expected RPC envelope did not arrive');
  };
  socket.send(JSON.stringify({ _tag: 'Ping' }));
  await waitFor(event => event._tag === 'Pong');
  socket.send(JSON.stringify({ _tag: 'Request', id: 'config', tag: 'server.getConfig', payload: {}, headers: [] }));
  const configResponse = await waitFor(event => event._tag === 'Exit' && event.requestId === 'config');
  assert.equal(configResponse.exit._tag, 'Success', 'server config request');
  const config = configResponse.exit.value;
  assert.equal(config.shellResumeCompletionMarker, true);
  assert.ok(Array.isArray(config.providers), 'config providers are an array');
  let modelCount = 0;
  for (const provider of config.providers) {
    assert.ok(Array.isArray(provider.models), 'provider models are an array');
    for (const model of provider.models) {
      assert.equal(typeof model.slug, 'string', 'model identity uses slug');
      assert.equal(typeof model.name, 'string', 'model display name uses name');
      assert.equal(typeof model.isCustom, 'boolean');
      assert.ok(Object.hasOwn(model, 'capabilities'));
      modelCount++;
    }
  }
  // Exact envelopes emitted by core/protocol/EffectRpc.kt.
  socket.send(JSON.stringify({ _tag: 'Request', id: 'shell', tag: 'orchestration.subscribeShell', payload: { requestCompletionMarker: true }, headers: [] }));
  const chunk = await waitFor(event => event._tag === 'Chunk' && event.requestId === 'shell');
  assert.ok(Array.isArray(chunk.values));
  const snapshot = chunk.values.find(value => value.kind === 'snapshot');
  assert.ok(snapshot, 'initial shell snapshot');
  assert.deepEqual(snapshot.snapshot.projects, [], 'disposable environment contains no projects');
  assert.deepEqual(snapshot.snapshot.threads, [], 'disposable environment contains no threads');
  await waitFor(event => event._tag === 'Chunk' && event.requestId === 'shell' && event.values.some(value => value.kind === 'synchronized'));
  socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: 'shell' }));
  const exit = await waitFor(event => event._tag === 'Exit' && event.requestId === 'shell');
  assert.equal(exit.exit._tag, 'Failure', 'interrupt terminates stream');
  assert.equal(typeof config.settings?.defaultRuntimeMode, 'string', 'settings.defaultRuntimeMode');
  for (const provider of config.providers) {
    if (provider.supportedRuntimeModes !== undefined) assert.ok(Array.isArray(provider.supportedRuntimeModes), 'provider-level supportedRuntimeModes');
  }
  // Launch identity, mutating only this disposable environment. No initialMessage, so no provider run starts.
  let rpcCount = 0;
  const rpc = async (tag, payload) => {
    const id = `launch-check-${rpcCount++}`;
    socket.send(JSON.stringify({ _tag: 'Request', id, tag, payload, headers: [] }));
    return (await waitFor(event => event._tag === 'Exit' && event.requestId === id)).exit;
  };
  const workspaceRoot = resolve(root, 'workspace');
  await mkdir(workspaceRoot);
  const project = await rpc('projects.mutate', { type: 'project.create', commandId: 'wire-project', projectId: 'wire-project', title: 'Wire check', workspaceRoot });
  assert.equal(project._tag, 'Success', 'disposable project creation');
  const provider = config.providers.find(candidate => candidate.models.length > 0);
  assert.ok(provider, 'a provider lists a model');
  const launch = { commandId: 'wire-launch', threadId: 'wire-thread', projectId: 'wire-project', title: 'Wire check',
    modelSelection: { instanceId: provider.instanceId, model: provider.models[0].slug },
    runtimeMode: 'full-access', interactionMode: 'default', workspaceStrategy: { type: 'root' } };
  const launched = await rpc('orchestration.launchThread', launch);
  assert.equal(launched._tag, 'Success', 'launchThread');
  assert.equal(launched.value.threadId, 'wire-thread', 'client threadId is honoured');
  assert.equal(launched.value.resumed, false);
  const replayed = await rpc('orchestration.launchThread', launch);
  assert.equal(replayed._tag, 'Success', 'replayed launchThread');
  assert.deepEqual([replayed.value.threadId, replayed.value.resumed], ['wire-thread', true], 'same commandId resumes, no duplicate');
  const rejected = await rpc('orchestration.launchThread', { ...launch, commandId: 'wire-launch-2', threadId: 'wire-thread-2', projectId: 'missing' });
  assert.equal(rejected._tag, 'Failure', 'launch into a missing project is rejected');
  assert.ok(Array.isArray(rejected.cause) && rejected.cause.every(reason => reason._tag === 'Fail'), 'typed rejection is a Fail cause array');
  const resent = await rpc('orchestration.launchThread', { ...launch, commandId: 'wire-launch-2', threadId: 'wire-thread-2' });
  assert.equal(resent._tag, 'Success', 'a corrected resend may reuse the rejected ids');
  console.log(`PASS: isolated actual T3 server; native pairing OAuth Bearer/session/ticket; /ws protocol 2; Ping/Pong; config (${config.providers.length} providers, ${modelCount} models, defaultRuntimeMode); empty shell + synchronized marker; Ack/Interrupt/Exit; launch with client ids, commandId replay, typed rejection.`);
} finally {
  socket?.close();
  child.kill('SIGTERM');
  await Promise.race([childExit, delay(5000, undefined, { ref: false })]);
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGKILL');
    await childExit;
  }
  await rm(root, { recursive: true, force: true });
}
