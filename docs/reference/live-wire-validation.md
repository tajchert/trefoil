# Disposable T3 wire validation

Run from the Trefoil root with Node 24.10+ and an installed sibling T3 checkout:

```sh
node scripts/validate-t3-wire.mjs
# Or provide another source checkout with installed server dependencies:
node scripts/validate-t3-wire.mjs /absolute/path/to/t3code
```

The check launches the actual T3 source server, not a protocol mock. Every run
uses a fresh temporary working directory, HOME, application state directory,
random credential, and loopback port. It does not inherit provider or T3
environment settings, create projects or threads, send prompts, contact an
existing server, or display server logs or credentials. The server and temporary
directory are removed when the check finishes.

Verified against T3 commit `46449b5379a0089f4ef3ce85bce10a1d28beb4cb` on
2026-11-06 (server package 0.1.7, Effect 4.1.2):

- Missing orchestration version on `/ws` returns HTTP 426.
- The browser session endpoint accepts a fresh per-run development credential;
  its cookie authenticates `POST /api/auth/pairing-token` with `{ "label": ... }`.
- That disposable pairing credential exchanges through form-encoded
  `POST /oauth/token` for a native Bearer token. The form specifies
  `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`,
  `subject_token=<pairing credential>`,
  `subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap`, and
  `requested_token_type=urn:ietf:params:oauth:token-type:access_token`.
- The Bearer token verifies `authenticated: true` at `/api/auth/session` and
  authenticates `POST /api/auth/websocket-ticket/rpc` without browser cookies.
- The ticket authenticates `/ws?orchestrationProtocol=2&wsTicket=...`.
- JSON `{ "_tag": "Ping" }` receives `Pong`.
- A request with string ID, `headers: []`, `tag: "orchestration.subscribeShell"`,
  and payload `{ "requestCompletionMarker": true }` receives a `Chunk` with the
  same `requestId`.
- The initial chunk contains a `kind: "snapshot"` item with empty `projects`
  and `threads`, proving the instance is disposable and unpopulated.
- `Ack` and `Interrupt` use `requestId`; interrupt receives an `Exit` with a
  failure exit, terminating the subscription.
- Acknowledging every shell chunk permits the subsequent
  `{ "kind": "synchronized" }` item. This is the completion marker the native
  client can use before enabling writes.
- `server.getConfig` with `{}` returns a successful `Exit`, with the config in
  `exit.value`. `shellResumeCompletionMarker` is `true`. `providers` is an array;
  each provider's `models` is an array of objects with `slug` (identity), `name`
  (display label), `isCustom`, and nullable `capabilities`. The isolated instance
  returned 11 providers and 22 models; counts are informational and not pinned.

The request and signal envelopes match `core/protocol/EffectRpc.kt`. This harness
checks the wire format against a live server independently of Android. It does
not execute the Kotlin transport, prove token storage, validate reconnect
reconciliation, or test every RPC method. The development credential only
bootstraps the disposable browser administrator; the subsequent native path
uses an actual pairing grant and OAuth Bearer session.

Authoritative upstream sources are `apps/server/src/ws.ts` (route and version
gate), `packages/contracts/src/environment.ts` (protocol version),
`packages/contracts/src/auth.ts` and `environmentHttp.ts` (ticket exchange), and
Effect's `src/rpc/RpcMessage.ts` and `RpcSerialization.ts` (envelopes).
