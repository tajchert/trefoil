# Trefoil direct T3 wire contract

Pinned reference: T3 Code `46449b5379a0089f4ef3ce85bce10a1d28beb4cb`.
Sources: `packages/contracts/src/{environment,environmentHttp,auth,orchestrationV2,server,providerPolicy,modelSelection}.ts`, `packages/shared/src/remote.ts`, `apps/server/src/ws.ts`, installed Effect 4.1.2 `src/rpc/{RpcMessage,RpcSerialization}.ts`.

1. Parse a direct pairing URL's fragment/query `token` (or `t3code:` wrapper `pairingUrl`). Only HTTPS remote origins are accepted. Explicit loopback development URLs may use HTTP (`localhost`, `127.0.0.1`, `::1`, emulator `10.0.2.2`). No redirects are followed; bootstrap and bearer credentials cannot cross origins.
2. GET `/.well-known/t3/environment`, require `orchestrationProtocolVersion: 2`, retain `environmentId`.
3. POST form `/oauth/token`: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token=<bootstrap>`, `subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap`, `requested_token_type=urn:ietf:params:oauth:token-type:access_token`, mobile client metadata. Save only issued Bearer access token.
4. Each connect checks descriptor identity and authenticated `/api/auth/session` using bearer. Effective permissions (falling back to legacy scopes only when absent) control `orchestration:operate` writes.
5. POST `/api/auth/websocket-ticket/rpc` with bearer, falling back to `/api/auth/websocket-ticket` **only for HTTP 404**. Upgrade `/ws?wsTicket=<ticket>&orchestrationProtocol=2`. Do not send bearer in the WebSocket URL.
6. Effect JSON Request: `{ "_tag":"Request", "id":"1", "tag":"orchestration.subscribeShell", "payload":{"requestCompletionMarker":true}, "headers":[] }`. This is not JSON-RPC 2.0. JSON object or array frames are accepted. Chunk values receive Ack keyed by `requestId`; terminal Exit carries Success/value or Failure/cause. Cancel streams with Interrupt. Ping every 15 seconds, expire missing Pong after 45 seconds.
7. Request full shell and thread snapshots without optional compact/bounded/deduped encodings. Shell updates merge by ID and remove explicitly. Selected-thread snapshots project messages, runs, activity and pending runtime requests. Domain events invalidate writes and trigger a conflated authoritative `getThreadProjection` read (at most one in-flight and one queued refresh). Subscription completion markers gate writes. Reconnect discards old subscriptions and reads fresh state; old generations cannot apply callbacks.
8. Mutations allocate a UUID `commandId`. `message.dispatch` has `messageId`, text, empty attachments, and `dispatchMode.type` `start_immediately` or `queue_after_active`. Interrupt uses `run.interrupt` with active `runId`. Approval/user input uses `runtime-request.respond`; unknown/non-live request capabilities stay visible with response disabled. Thread creation uses `orchestration.launchThread`, server-provided model selection, `approval-required`, interaction `default`, workspace `root`.
9. Every request has a timeout; pending RPC count is bounded at 64. A lost/rejected/timeout mutation disables writes until reconnect. No mutation replay occurs. The next authoritative snapshot reconciles visible state; there is no automatic receipt retry.

## Current limits

The first client requests complete shell history (neither `omitIdleSubagents` nor `omitOlderSettled` is sent), so `getOlderSettledThreads` is not needed for this full-snapshot path. No bounded-history pagination UI exists yet. Domain event resnapshotting trades bandwidth for completeness; native incremental reducers can replace this later. Provider approval option restrictions are checked at dispatch; the shared UI currently exposes basic accept/decline. Unsupported requests explicitly direct users to another T3 client. Auto reconnect/backoff, access-token renewal, command receipt lookup, steer, thread.stop, attachments and terminal streams are follow-up work. Explicit reconnect is implemented.

Fixtures are synthetic examples shaped from the pinned contracts and contain no user data. The isolated server wire probe lives in `scripts/validate-t3-wire.mjs` and never touches a developer's T3 state.

## SSH transport (0.2.0 source)

SSH is a transport wrapper around the same V2 HTTP/Effect RPC protocol. Native
SSHJ verifies the host key before password/private-key authentication. First use
requires a user-confirmed SHA-256 pin; a changed pin blocks authentication.

Read the selected T3 home's `userdata/server-runtime.json`, verify live PID and
runtime metadata, and forward an ephemeral **127.0.0.1** listener only to the
remote **127.0.0.1** T3 port. Probe the descriptor and require V2, matching
server/CLI version and the expected saved environment identity. The running
server's matching CLI executes `auth pairing create --base-dir <home> --json`;
its `credential` is exchanged through the tunnel using the existing OAuth flow.
Never log raw command output or replay pairing creation after an unknown result.
No installation, update, service change or remote process stop occurs.

SSH profiles, host pins, access tokens and explicitly saved login secrets live
in Android Keystore-encrypted local files. The local forwarded URL is transient:
it is never persisted as an environment's durable endpoint. Reconnect opens a
new SSH tunnel before reusing the saved T3 token. Background/disconnect closes
the local tunnel; remote agents continue running. An unsaved login is requested
again when a new SSH connection is needed.
