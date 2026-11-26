# Side chats, thread inspector and thread references

Wire contract as Trefoil implements it: `T3Client` and `InspectorStream` in `android/core/session`, and `ThreadReferences.kt` in
`android/core/model`. Upstream reference: T3 Code `thread.fork` and side-chat start (`useStartSideChat`), and
`composerContextReferences` (MIT; see [NOTICE.md](NOTICE.md)). Subagent rows are described in [Subagents](SPEC.md#subagents).

## Capability

`environment.capabilities.sideChats` comes from the first config (`subscribeServerConfig` snapshot, or `server.getConfig`).
Only a JSON boolean `true` enables it. Absent, false or a string leaves it off, and the state is `sideChatsSupported`. It is
cleared on disconnect and on environment switch.

The flag gates creating a side chat only. Opening existing side chats and subagents needs no gate.

## Read-only inspector stream

The inspector shows one child thread (side chat, app subagent or provider subagent) beside the selected parent, without
replacing it.

- It uses a second `orchestration.subscribeThread` `{threadId, requestCompletionMarker: true}` on the same socket, beside the
  selected thread's own stream. The selected thread's stream, writes and queue are not touched.
- At most one inspector stream exists. Opening another child interrupts the previous stream (`Interrupt`). Opening the child that
  is already live does nothing.
- `openInspector` is refused locally unless the connection is up with a live shell, a thread is selected, the child is listed in
  the shell and is not the selected thread, the child has a side-thread kind, and either its `parentThreadId` is the selected
  thread or the selected thread's subagents reference it as a child.
- Each snapshot applies at once. Each event triggers a conflated `orchestration.getThreadProjection` refetch for the child only.
  The first authoritative snapshot and the latest projection are kept in `ThreadProjectionCache` when the stream stops.
- The inspector never sends writes and never changes `selectedThreadId`. It has no effect on the parent's writability,
  queue or stop state.
- It closes (`Interrupt`) when the selected thread changes or clears (including deletion or a too-large parent), when the
  environment switches, and when the child leaves the synchronized shell (`Gone`).
- On disconnect it is interrupted before the socket closes and is not resubscribed after reconnect. The UI calls
  `openInspector` again if it still holds the target.
- Errors stop only the inspector. A frame over `EffectRpc.MAX_FRAME_CHARS` gives `TooLarge`. A refetch failure or a stream
  `Exit` gives `RefreshFailed`. Neither fails the session.

## Starting a side chat (`startSideChat`)

Local refusals, before anything is sent: a blank question; no write access (`requireWrite`, fresh thread); `sideChatsSupported`
false; no selected thread listed in the shell; the parent is itself a side chat or subagent; the parent has no first message
(`latestUserMessageAt` is null and no loaded user message).

Each command is sent once, through the mutation path. Nothing is replayed.

1. `thread.fork`:

   ```json
   {"type":"thread.fork","commandId":"<uuid>","createdBy":"user","creationSource":"mobile",
    "sourceThreadId":"<parent>","targetThreadId":"<client uuid>","sourcePoint":{"type":"latest_stable"},
    "title":"Side chat","sideChat":true}
   ```

   The fork has no `createdAt`. A definite rejection ends the start with an error. An uncertain outcome (timeout, disconnect,
   invalid receipt, or an Exit whose cause is only `Interrupt`) fails the session. Nothing is deleted or retried.
2. Wait up to 10 s for the shell to list `targetThreadId`, or for the connection to drop. If the side chat never appears, no
   message is sent. The start fails with "created, but not here yet", followed by the cleanup in step 4.
3. `message.dispatch` to `targetThreadId`:
   - Same fields as a normal send: provenance (`createdBy`, `creationSource`), a UUID `messageId`, `text`, empty `attachments`,
     `dispatchMode: {"type":"start_immediately"}`. The `context` is added only when the question has resolvable references
     (see below).
   - No `modelSelection`, no `runtimeMode` and no `thread.runtime-mode.set`. The side chat runs with the settings it forked.
   - Guard: the write is still allowed (authorized, shell ready, connected, same epoch). The guard does not depend on the
     selection, so the user may switch threads meanwhile.
4. Cleanup: `thread.delete {"commandId":"<uuid>","threadId":"<side chat>","onlyIfUnused":true}`, sent once, and only after a
   definite failure: the shell never lists the fork, or the message is definitely rejected. It is never sent after an
   uncertain message, because that message may have landed. The cleanup is itself a mutation, so an uncertain cleanup fails the
   session. Its own error never replaces the original one.

The call returns the new thread id. The selected thread stays selected, and the new side chat is not opened automatically.

## Thread context records and references

### Marker in the composer text

A reference is a markdown link:

```
[<label>](t3-context://v1/thread/<contextId>)
```

- `label` is the sanitized title at insertion time.
- Only `thread/<valid context id>` hrefs count as thread references. Other `t3-context:` links are not thread references.
- Parsing follows upstream bounds: the label is at most 512 UTF-16 units with no `[`, `]` or newline. The href after the prefix
  is 1 to 200 characters with no whitespace and no `)`.

### contextId

`toComposerContextId("thread", threadId)`:

- `thread_<threadId>` when that matches `^[a-z0-9_-]{1,128}$` (case-insensitive).
- Otherwise the first 48 characters of the slug of `thread_<threadId>` (runs outside `[A-Za-z0-9_-]` become `-`, edge dashes
  trimmed, `ctx` when empty), then `-`, then the 16-hex-digit fnv1a64 digest of the whole `thread_<threadId>` (two 32-bit FNV-1a
  passes, the second over the code units in reverse).

### Label sanitising

`sanitizeContextLabel`: `[`, `]`, `\`, CR, LF and other JavaScript whitespace become spaces. Runs collapse to one space and the
ends are trimmed. The result is at most 200 UTF-16 units, without splitting a surrogate pair. An empty result becomes `thread`.

### Context record

Sent beside the message as `context: {"version":1,"records":[...]}`. One record per distinct `contextId`, in order of first
appearance, for each marker whose `contextId` matches a known, not deleted thread of the current environment:

```json
{"version":1,"kind":"thread","contextId":"thread_<threadId>","label":"<title>","environmentId":"<env>",
 "threadId":"<id>","title":"<title>"}
```

- `label` and `title` are the thread's current title, sanitized. The marker's own label is not used.
- At most 200 records (`THREAD_CONTEXT_MAX_RECORDS`).
- A marker with no matching record stays plain text, and the server gets no record for it.
- When no record remains, the `context` key is omitted entirely. This applies to `sendMessage` (both overloads) and to the
  side-chat message.

The server pairs text occurrences with records by `contextId`. The records are never logged, and records never cross
environments.

## Wire check

`scripts/validate-t3-wire.mjs <pinned T3 Code checkout>` runs against an isolated server. Always checked:

- two concurrent `orchestration.subscribeThread` streams on one socket, both delivering a snapshot and `synchronized`;
  interrupting the second leaves the first live; `getThreadProjection` still answers;
- `environment.capabilities.sideChats`, if present, is a boolean.

When it is `true`, the validator also checks, on a second shell stream:

- the exact `thread.fork` above returns `Success`, and the shell lists the fork with `lineage.sideChat` true and a
  `parentThreadId`;
- `thread.delete {onlyIfUnused:true}` returns `Success` and the thread is removed;
- `message.dispatch` with a thread context record, sent to a missing thread, is a typed `OrchestrationV2DispatchCommandError`
  (so the payload decoded), while a malformed `context` fails decoding.

The validator launches only ordinary threads with no provider run, so no side-chat run or subagent is exercised. It does not
cover the 10 s shell wait, the state machine in `startSideChat`, or the inspector UI. Those are covered by session and model
unit tests.
