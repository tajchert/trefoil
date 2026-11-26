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
// Node flags given to this script (e.g. an --import resolve hook) apply to the server too.
const child = spawn(process.execPath, [...process.execArgv, resolve(t3Root, 'apps/server/src/bin.ts'),
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
  // Trefoil's first config: the subscribeServerConfig snapshot with T3 mobile's opt-ins (ServerConfigStream.kt).
  // Both subscriptions run side by side for a few seconds; bytes count the config JSON (snapshot) and all stream values.
  const SLIM = { workspaceSnapshotsOnDemand: true, providerCatalogsOnDemand: true, providerCatalogDigests: true, providerStatusDeltas: true };
  const bytes = value => Buffer.byteLength(JSON.stringify(value));
  for (const [id, payload] of [['config-legacy', {}], ['config-slim', SLIM]]) socket.send(JSON.stringify({ _tag: 'Request', id, tag: 'subscribeServerConfig', payload, headers: [] }));
  const legacyFirst = await waitFor(event => event._tag === 'Chunk' && event.requestId === 'config-legacy');
  const slimFirst = await waitFor(event => event._tag === 'Chunk' && event.requestId === 'config-slim');
  await delay(3000);
  for (const id of ['config-legacy', 'config-slim']) socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: id }));
  const streamBytes = id => events.filter(event => event._tag === 'Chunk' && event.requestId === id).reduce((sum, event) => sum + bytes(event.values), 0);
  assert.equal(slimFirst.values[0]?.type, 'snapshot', 'the slim stream starts with a snapshot');
  const legacy = legacyFirst.values[0].config;
  const slim = slimFirst.values[0].config;
  const configBytes = { getConfig: bytes(config), legacySnapshot: bytes(legacy), slimSnapshot: bytes(slim),
    legacyStream3s: streamBytes('config-legacy'), slimStream3s: streamBytes('config-slim') };
  assert.equal(slim.environment?.environmentId, config.environment.environmentId, 'slim snapshot environment');
  assert.equal(slim.environment?.serverVersion, config.environment.serverVersion, 'slim snapshot serverVersion (shell resume origin)');
  assert.deepEqual(slim.environment?.capabilities, config.environment.capabilities, 'slim snapshot capabilities');
  assert.equal(slim.settings?.defaultRuntimeMode, config.settings.defaultRuntimeMode, 'slim snapshot defaultRuntimeMode');
  assert.ok(Array.isArray(slim.directEndpoints), 'slim snapshot directEndpoints');
  // Provider probes may finish between requests, so compare identity and shape, not live status.
  assert.deepEqual(slim.providers.map(p => p.instanceId), config.providers.map(p => p.instanceId), 'slim snapshot keeps every provider');
  for (const provider of slim.providers) {
    const full = config.providers.find(candidate => candidate.instanceId === provider.instanceId);
    assert.ok(Array.isArray(provider.models) && provider.models.every(m => typeof m.slug === 'string' && typeof m.name === 'string'), 'slim snapshot models');
    assert.equal(Array.isArray(provider.supportedRuntimeModes), Array.isArray(full.supportedRuntimeModes), 'slim snapshot runtime modes');
    assert.equal(Object.hasOwn(provider, 'usageLimits'), Object.hasOwn(full, 'usageLimits'), 'slim snapshot usage limits');
    assert.equal(provider.workspaceSnapshots, undefined, 'workspace snapshots stay on demand');
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
  // Thread inspector (InspectorStream.kt): the selected thread's subscribeThread and a second one for another thread run
  // concurrently on one socket; interrupting the inspector's leaves the selected stream live and refetchable.
  const subscribeThread = (id, threadId) => socket.send(JSON.stringify({ _tag: 'Request', id, tag: 'orchestration.subscribeThread', payload: { threadId, requestCompletionMarker: true }, headers: [] }));
  const threadChunks = id => events.filter(event => event._tag === 'Chunk' && event.requestId === id).flatMap(event => event.values);
  subscribeThread('thread-selected', 'wire-thread');
  subscribeThread('thread-inspector', 'wire-thread-2');
  for (const [id, threadId] of [['thread-selected', 'wire-thread'], ['thread-inspector', 'wire-thread-2']]) {
    await waitFor(event => event._tag === 'Chunk' && event.requestId === id && event.values.some(value => value.kind === 'synchronized'));
    const threadSnapshot = threadChunks(id).find(value => value.kind === 'snapshot');
    assert.equal(threadSnapshot?.projection?.thread?.id, threadId, `concurrent subscribeThread snapshot for ${id}`);
    assert.equal(events.some(event => event._tag === 'Exit' && event.requestId === id), false, `${id} stays open beside the other`);
  }
  socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: 'thread-inspector' }));
  const inspectorExit = await waitFor(event => event._tag === 'Exit' && event.requestId === 'thread-inspector');
  assert.equal(inspectorExit.exit._tag, 'Failure', 'interrupt ends the inspector stream');
  const refetched = await rpc('orchestration.getThreadProjection', { threadId: 'wire-thread' });
  assert.equal(refetched._tag, 'Success', 'getThreadProjection beside a live thread stream');
  assert.equal(events.some(event => event._tag === 'Exit' && event.requestId === 'thread-selected'), false, 'the selected stream survives the inspector interrupt');
  socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: 'thread-selected' }));
  await waitFor(event => event._tag === 'Exit' && event.requestId === 'thread-selected');
  const sideChatsCapability = config.environment.capabilities?.sideChats;
  assert.ok(sideChatsCapability === undefined || typeof sideChatsCapability === 'boolean', 'capabilities.sideChats is optional boolean');
  // Side chat start (T3Client.startSideChat): the exact thread.fork, the shell row, and the onlyIfUnused cleanup delete.
  let sideChatSummary = 'side chats not offered';
  if (sideChatsCapability === true) {
    socket.send(JSON.stringify({ _tag: 'Request', id: 'shell-side', tag: 'orchestration.subscribeShell', payload: { requestCompletionMarker: true }, headers: [] }));
    await waitFor(event => event._tag === 'Chunk' && event.requestId === 'shell-side' && event.values.some(value => value.kind === 'synchronized'));
    const shellValues = () => events.filter(event => event._tag === 'Chunk' && event.requestId === 'shell-side').flatMap(event => event.values);
    const fork = await rpc('orchestration.dispatchCommand', { type: 'thread.fork', commandId: 'wire-fork', createdBy: 'user', creationSource: 'mobile',
      sourceThreadId: 'wire-thread', targetThreadId: 'wire-side', sourcePoint: { type: 'latest_stable' }, title: 'Side chat', sideChat: true });
    assert.equal(fork._tag, 'Success', `thread.fork with sideChat: ${JSON.stringify(fork.cause ?? null)}`);
    assert.ok(Number.isInteger(fork.value?.sequence) && fork.value.sequence >= 0, 'thread.fork returns a dispatch receipt');
    await waitFor(() => shellValues().some(value => value.kind === 'thread.updated' && value.thread?.id === 'wire-side'));
    const row = shellValues().filter(value => value.kind === 'thread.updated' && value.thread?.id === 'wire-side').at(-1).thread;
    assert.equal(row.lineage?.sideChat, true, 'the fork is listed as a side chat');
    assert.equal(row.lineage?.parentThreadId, 'wire-thread', 'the side chat names its parent');
    const removed = await rpc('orchestration.dispatchCommand', { type: 'thread.delete', commandId: 'wire-side-delete', threadId: 'wire-side', onlyIfUnused: true });
    assert.equal(removed._tag, 'Success', `thread.delete onlyIfUnused: ${JSON.stringify(removed.cause ?? null)}`);
    await waitFor(() => shellValues().some(value => (value.kind === 'thread.removed' && value.threadId === 'wire-side') ||
      (value.kind === 'thread.updated' && value.thread?.id === 'wire-side' && value.thread.deletedAt)));
    // message.dispatch with a thread context record, sent to a thread that does not exist so no provider run can start:
    // a well-formed payload gets past decoding to a typed command rejection; a malformed context (control) fails decoding.
    const record = { version: 1, kind: 'thread', contextId: 'thread_wire-thread', label: 'Wire check', environmentId: config.environment.environmentId, threadId: 'wire-thread', title: 'Wire check' };
    const message = context => ({ type: 'message.dispatch', commandId: `wire-context-${rpcCount}`, threadId: 'wire-missing', createdBy: 'user', creationSource: 'mobile',
      messageId: `wire-context-message-${rpcCount}`, text: 'See [Wire check](t3-context://v1/thread/thread_wire-thread)', attachments: [], context, dispatchMode: { type: 'start_immediately' } });
    const describe = exit => JSON.stringify(exit.cause ?? exit);
    const withContext = await rpc('orchestration.dispatchCommand', message({ version: 1, records: [record] }));
    const malformed = await rpc('orchestration.dispatchCommand', message({ version: 99, records: 'nope' }));
    assert.equal(malformed._tag, 'Failure', 'malformed context is refused');
    assert.notEqual(withContext._tag, 'Success', 'a message to a missing thread is not accepted');
    assert.ok(Array.isArray(withContext.cause) && withContext.cause.every(reason => reason._tag === 'Fail'), `context dispatch is a typed rejection: ${describe(withContext)}`);
    assert.notEqual(describe(withContext), describe(malformed), 'the context payload decodes (its rejection differs from the malformed control)');
    // The dispatcher's own error (naming the decoded command type) is only reachable once the whole command decoded.
    const dispatchError = withContext.cause[0]?.error;
    assert.equal(dispatchError?._tag, 'OrchestrationV2DispatchCommandError', `context dispatch reached the dispatcher: ${describe(withContext)}`);
    assert.equal(dispatchError?.commandType, 'message.dispatch', 'the dispatcher decoded the message.dispatch with its context');
    assert.notEqual(malformed.cause?.[0]?.error?.commandType, 'message.dispatch', `the malformed control fails before dispatch: ${describe(malformed)}`);
    console.log(`side chat: context rejection ${describe(withContext).slice(0, 300)}; malformed control ${describe(malformed).slice(0, 300)}`);
    socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: 'shell-side' }));
    await waitFor(event => event._tag === 'Exit' && event.requestId === 'shell-side');
    sideChatSummary = 'thread.fork sideChat listed, onlyIfUnused delete removes it, message.dispatch context decodes';
  }
  // PRs and diffs. wire-thread has no provider run, so it has no checkpoints; wire-project is a plain directory, not a forge repository.
  const failures = exit => exit.cause.filter(reason => reason._tag === 'Fail').map(reason => reason.error);
  const typedFailure = (exit, label) => {
    assert.equal(exit._tag, 'Failure', `${label} fails`);
    assert.ok(Array.isArray(exit.cause) && exit.cause.length > 0 && exit.cause.every(reason => reason._tag === 'Fail'), `${label} is a typed Fail cause array`);
    return failures(exit)[0];
  };
  const diffErrors = {};
  for (const [tag, payload] of [
    ['orchestration.getFullThreadDiff', { threadId: 'wire-thread', toTurnCount: 1 }],
    ['orchestration.getTurnDiff', { threadId: 'wire-thread', fromTurnCount: 0, toTurnCount: 1 }],
  ]) {
    const error = typedFailure(await rpc(tag, payload), tag);
    assert.equal(typeof error.message, 'string', `${tag} error carries a message`);
    assert.ok(error.message.length > 0, `${tag} error message is not empty`);
    diffErrors[tag] = error;
  }
  const capabilities = config.environment.capabilities ?? {};
  const pullRequestCapabilities = Object.fromEntries(['pullRequests', 'pullRequestChecks', 'threadPullRequests']
    .map(key => [key, key in capabilities ? capabilities[key] : 'absent']));
  for (const value of Object.values(pullRequestCapabilities)) assert.ok(value === 'absent' || typeof value === 'boolean', 'PR capability keys are optional Booleans');
  const unavailable = typedFailure(await rpc('pullRequests.detail', { projectId: 'wire-project', repository: 'example/wire-check', number: 1 }), 'pullRequests.detail');
  assert.equal(unavailable._tag, 'PullRequestUnavailableError', 'pullRequests.detail on a non-forge project is unavailable');
  assert.equal(typeof unavailable.reason, 'string', 'PullRequestUnavailableError carries a reason');
  socket.send(JSON.stringify({ _tag: 'Request', id: 'pr-refreshes', tag: 'pullRequests.subscribeRefreshes', payload: {}, headers: [] }));
  // The stream only emits on a PR refresh, so "open" means no immediate Exit; a first value is accepted but not required.
  await delay(1000);
  const refreshEvents = events.filter(event => event.requestId === 'pr-refreshes');
  assert.ok(!refreshEvents.some(event => event._tag === 'Exit'), 'subscribeRefreshes stays open');
  const firstRefresh = refreshEvents.find(event => event._tag === 'Chunk')?.values?.[0];
  assert.ok(firstRefresh === undefined || Number.isInteger(firstRefresh) && firstRefresh >= 0, 'refresh ticks are non-negative integers');
  socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: 'pr-refreshes' }));
  const refreshesExit = await waitFor(event => event._tag === 'Exit' && event.requestId === 'pr-refreshes');
  assert.equal(refreshesExit.exit._tag, 'Failure', 'interrupt terminates subscribeRefreshes');
  const refreshesObserved = { firstValue: firstRefresh ?? 'none', interruptCause: refreshesExit.exit.cause.map(reason => reason._tag) };
  const summarize = error => ({ _tag: error._tag, message: error.message, reason: error.reason });
  console.log(`PRs and diffs (observed): ${JSON.stringify({ diffErrors: Object.fromEntries(Object.entries(diffErrors).map(([tag, error]) => [tag, summarize(error)])),
    pullRequestCapabilities, pullRequestDetail: summarize(unavailable), subscribeRefreshes: refreshesObserved })}`);
  // Issue contract (protocol/ISSUES.md). Synthetic key only; nothing here talks to a real tracker or a real thread.
  // TREFOIL_WIRE_RECORD=<t3 revision> also prints every recorded reply as JSON (RECORD ...), for refreshing ISSUES.md.
  const record = {};
  const caps = config.environment.capabilities ?? {};
  assert.equal(caps.issues, true, 'capabilities.issues');
  assert.equal(caps.issueLinkedThreads, true, 'capabilities.issueLinkedThreads');
  record.capabilities = { issues: caps.issues, issueLinkedThreads: caps.issueLinkedThreads, issueTrackerAccounts: caps.issueTrackerAccounts };
  record.serverVersion = config.environment.serverVersion;
  const failureOf = exit => {
    assert.equal(exit._tag, 'Failure', 'expected a typed failure');
    assert.ok(Array.isArray(exit.cause) && exit.cause.length > 0, 'failure cause is a non-empty array');
    return exit.cause;
  };
  const list = await rpc('issues.list', { state: 'open' });
  assert.equal(list._tag, 'Success', 'issues.list state=open');
  assert.ok(Array.isArray(list.value.entries) && list.value.entries.length === 0, 'no entries on a non-git workspace');
  for (const field of ['viewers', 'trackers', 'errors', 'nextCursors']) assert.ok(Object.hasOwn(list.value, field), `issues.list result has ${field}`);
  assert.ok(Array.isArray(list.value.trackers) && Array.isArray(list.value.errors), 'trackers and errors are arrays');
  record.issuesList = list.value;
  const listWithOptions = await rpc('issues.list', { state: 'open', involvement: 'all', limit: 50, sort: 'updated', query: 'x', labels: ['a'], assignee: 'someone', projectId: 'wire-project' });
  assert.equal(listWithOptions._tag, 'Success', 'issues.list accepts the full option set Trefoil may send');
  record.issuesListFilteredKeys = Object.keys(listWithOptions.value);
  const noState = await rpc('issues.list', {});
  record.issuesListMissingState = noState;
  assert.equal(noState._tag, 'Failure', 'issues.list requires state');
  const fakeRef = { projectId: 'wire-project', tracker: 'github', host: 'github.com', scope: 'wire/test', key: '999999' };
  const detail = await rpc('issues.detail', fakeRef);
  record.issuesDetailFake = detail;
  failureOf(detail);
  const detailMissingProject = await rpc('issues.detail', { ...fakeRef, projectId: 'no-such-project' });
  record.issuesDetailMissingProject = detailMissingProject;
  failureOf(detailMissingProject);
  const detailUnknownTracker = await rpc('issues.detail', { ...fakeRef, tracker: 'nope' });
  record.issuesDetailUnknownTracker = detailUnknownTracker;
  failureOf(detailUnknownTracker);
  const commentsFake = await rpc('issues.comments', { ...fakeRef, cursor: 'x' });
  record.issuesCommentsFake = commentsFake;
  failureOf(commentsFake);
  const linkedBefore = await rpc('issues.linkedThreads', { tracker: 'github', host: 'github.com', scope: 'wire/test', key: '1' });
  assert.equal(linkedBefore._tag, 'Success', 'issues.linkedThreads before link');
  assert.deepEqual(linkedBefore.value.threads, [], 'no linked threads before link');

  // Shell rows. A live subscription stays open across the link so a delta is observable; fresh ones read snapshots.
  const shellStreams = [];
  const subscribeShell = async id => {
    socket.send(JSON.stringify({ _tag: 'Request', id, tag: 'orchestration.subscribeShell', payload: { requestCompletionMarker: true }, headers: [] }));
    const first = await waitFor(event => event._tag === 'Chunk' && event.requestId === id && event.values.some(value => value.kind === 'snapshot'));
    shellStreams.push(id);
    return first.values.find(value => value.kind === 'snapshot').snapshot;
  };
  const freshShellThread = async id => {
    const snapshot = await subscribeShell(id);
    socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: id }));
    return [...snapshot.threads, ...snapshot.archivedThreads].find(thread => thread.id === 'wire-thread');
  };
  const liveSnapshot = await subscribeShell('shell-live');
  const liveThread = liveSnapshot.threads.find(thread => thread.id === 'wire-thread');
  assert.ok(liveThread, 'launched thread is in the shell snapshot');
  assert.ok(liveThread.issues === undefined || liveThread.issues.length === 0, 'no issues on the shell row before linking');
  record.shellRowKeys = Object.keys(liveThread);
  const liveItems = () => events.filter(event => event._tag === 'Chunk' && event.requestId === 'shell-live').flatMap(event => event.values);
  const liveCountBefore = liveItems().length;
  const commandIds = new Set();
  const dispatch = command => {
    assert.ok(!commandIds.has(command.commandId), 'every dispatch uses a fresh commandId');
    commandIds.add(command.commandId);
    return rpc('orchestration.dispatchCommand', command);
  };
  const link = { type: 'thread.issue.link', threadId: 'wire-thread', tracker: 'github', host: 'GitHub.com', scope: 'wire/test', key: '1',
    projectId: 'wire-project', url: 'https://github.com/wire/test/issues/1', displayKey: '#1', title: 'Wire probe issue', source: 'manual' };
  const linked = await dispatch({ ...link, commandId: 'wire-issue-link-1' });
  assert.equal(linked._tag, 'Success', 'thread.issue.link');
  assert.equal(typeof linked.value.sequence, 'number', 'link receipt carries a sequence');
  record.linkReceipt = linked.value;
  const expectLink = thread => {
    assert.ok(thread, 'thread present');
    assert.equal(thread.issues?.length, 1, 'exactly one issue link');
    const [row] = thread.issues;
    assert.equal(row.host, 'github.com', 'host is stored lowercased');
    assert.deepEqual([row.tracker, row.scope, row.key, row.projectId, row.url, row.displayKey, row.title, row.source],
      ['github', 'wire/test', '1', 'wire-project', link.url, '#1', link.title, 'manual']);
    return row;
  };
  const freshAfterLink = await freshShellThread('shell-after-link');
  record.shellIssueRow = expectLink(freshAfterLink);
  for (let attempt = 0; attempt < 100 && !liveItems().slice(liveCountBefore).some(item => item.thread?.issues?.length === 1); attempt++) await delay(50);
  const liveDelta = liveItems().slice(liveCountBefore).filter(item => item.thread?.id === 'wire-thread' || item.threadId === 'wire-thread');
  record.liveShellItemKinds = liveItems().slice(liveCountBefore).map(item => item.kind);
  assert.ok(liveDelta.some(item => item.thread?.issues?.length === 1), 'the live shell stream carries the link as a delta');
  record.liveShellDelta = liveDelta.find(item => item.thread?.issues?.length === 1);
  const projection = await rpc('orchestration.getThreadProjection', { threadId: 'wire-thread' });
  assert.equal(projection._tag, 'Success', 'getThreadProjection');
  const projectionIssues = projection.value.thread?.issues;
  assert.equal(projectionIssues?.length, 1, 'thread projection shows the issue link');
  assert.equal(projectionIssues[0].host, 'github.com', 'projection host lowercased');
  const linkedAfter = await rpc('issues.linkedThreads', { tracker: 'github', host: 'GitHub.com', scope: 'Wire/Test', key: '1' });
  assert.equal(linkedAfter._tag, 'Success', 'issues.linkedThreads after link');
  record.linkedThreads = linkedAfter.value;
  assert.deepEqual(linkedAfter.value.threads.map(thread => thread.id), ['wire-thread'], 'linkedThreads finds the thread (host and scope case-insensitive)');
  const linkedOther = await rpc('issues.linkedThreads', { tracker: 'github', host: 'github.com', scope: 'wire/test', key: '2' });
  assert.deepEqual(linkedOther.value.threads, [], 'another key has no threads');
  const noHost = await rpc('issues.linkedThreads', { tracker: 'github', scope: 'wire/test', key: '1' });
  record.linkedThreadsMissingHost = noHost._tag;
  assert.equal(noHost._tag, 'Failure', 'linkedThreads requires host');
  // A link with a new commandId for the same key is a receipt, not a second link.
  const relinked = await dispatch({ ...link, commandId: 'wire-issue-link-2' });
  assert.equal(relinked._tag, 'Success', 'second link of the same key');
  record.relinkReceipt = relinked.value;
  assert.equal(expectLink(await freshShellThread('shell-after-relink')).key, '1', 'no duplicate link after a repeat');
  // Same commandId replayed: the server must answer with a receipt too.
  const replayedLink = await rpc('orchestration.dispatchCommand', { ...link, commandId: 'wire-issue-link-1' });
  record.linkReplaySameCommandId = replayedLink;
  assert.equal(replayedLink._tag, 'Success', 'a replayed issue link commandId is answered with a receipt');
  const unlink = { type: 'thread.issue.unlink', threadId: 'wire-thread', tracker: 'github', host: 'GITHUB.com', scope: 'wire/test', key: '1' };
  const unlinked = await dispatch({ ...unlink, commandId: 'wire-issue-unlink-1' });
  assert.equal(unlinked._tag, 'Success', 'thread.issue.unlink');
  record.unlinkReceipt = unlinked.value;
  const afterUnlink = await freshShellThread('shell-after-unlink');
  assert.ok(afterUnlink.issues === undefined || afterUnlink.issues.length === 0, 'unlink removes the key from the shell row');
  record.shellRowAfterUnlinkIssues = afterUnlink.issues ?? null;
  const linkedGone = await rpc('issues.linkedThreads', { tracker: 'github', host: 'github.com', scope: 'wire/test', key: '1' });
  assert.deepEqual(linkedGone.value.threads, [], 'linkedThreads is empty after unlink');
  const unlinkedAgain = await dispatch({ ...unlink, commandId: 'wire-issue-unlink-2' });
  assert.equal(unlinkedAgain._tag, 'Success', 'unlinking a missing key is a receipt');
  record.unlinkMissingReceipt = unlinkedAgain.value;
  const linkMissingThread = await dispatch({ ...link, commandId: 'wire-issue-link-3', threadId: 'no-such-thread' });
  record.linkMissingThread = linkMissingThread;
  assert.equal(linkMissingThread._tag, 'Failure', 'link on a missing thread is rejected');
  const linkBlankTitle = await dispatch({ ...link, commandId: 'wire-issue-link-4', title: '  ' });
  record.linkBlankTitle = linkBlankTitle;
  assert.equal(linkBlankTitle._tag, 'Failure', 'link with a blank title is rejected');
  for (const id of shellStreams) socket.send(JSON.stringify({ _tag: 'Interrupt', requestId: id }));
  if (process.env.TREFOIL_WIRE_RECORD) console.log(`RECORD ${JSON.stringify({ t3Head: process.env.TREFOIL_WIRE_RECORD, ...record }, null, 2)}`);
  console.log(`PASS: isolated actual T3 server; native pairing OAuth Bearer/session/ticket; /ws protocol 2; Ping/Pong; config (${config.providers.length} providers, ${modelCount} models, defaultRuntimeMode); empty shell + synchronized marker; Ack/Interrupt/Exit; launch with client ids, commandId replay, typed rejection; two concurrent subscribeThread streams on one socket (inspector interrupt leaves the selected stream live); diff RPC typed failure on a no-checkpoint thread; pullRequests.detail PullRequestUnavailableError; PR capability keys; subscribeRefreshes open and interrupt; capabilities.sideChats=${sideChatsCapability} (${sideChatSummary}); issue capabilities, issues.list/detail/linkedThreads, thread.issue.link/unlink idempotency and shell propagation; config bytes ${JSON.stringify(configBytes)}.`);
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
