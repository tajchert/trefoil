# Issue trackers: wire contract as Trefoil uses it

This file records what Trefoil sends and expects for T3 issue tracking. It was observed on an isolated T3 server
by `scripts/validate-t3-wire.mjs` (the "issue contract" block), and read from the T3 sources (MIT) where noted.
Everything below is **Observed** (the probe asserts it) or **Read** (from source only) unless it says otherwise.

Probe run: T3 server `0.1.11` from upstream revision `5ddb3b4b71` (the last revision before the Effect 4.1.5 upgrade,
which is what the stale dependency install in the reference checkout can run). Upstream HEAD `bc0f04da94` was
read for the contract; it differs only by additions listed in "Differences by revision". The server needs T3 ≥ v0.1.11.

All calls are Effect-RPC requests on the existing WebSocket (`{_tag:"Request", id, tag, payload, headers:[]}`).
Replies are `{_tag:"Exit", requestId, exit}`.

## Capabilities

`server.getConfig` and the `subscribeServerConfig` snapshot carry `environment.capabilities`, all optional booleans.

| Flag | Meaning | Trefoil |
|---|---|---|
| `issues` | `issues.*` RPCs exist | Required for any issue UI. Absent or false: hide everything. Observed `true`. |
| `issueLinkedThreads` | `issues.linkedThreads` is answered | Gate the "Linked threads" section. Observed `true`. |
| `issueTrackerAccounts` | tracker settings | Ignored. Not set at the probed revision. |

`thread.issue.link` / `unlink` have no flag of their own: they exist on every server that sets `issues`.

## Failure encoding (Observed, exact)

A failed RPC exit is `{"_tag":"Failure","cause":[<reason>, ...]}`. `cause` is a **JSON array of reasons**, not a nested
tree. Each reason is one of:

```json
{"_tag":"Fail","error":{"_tag":"IssueUnavailableError","reason":"tracker-unsupported"}}
{"_tag":"Fail","error":{"_tag":"OrchestrationV2DispatchCommandError","commandId":"…","commandType":"thread.issue.link","message":"No orchestration projection exists for thread no-such-thread.","detail":"…","cause":{"name":"OrchestratorProjectionError","message":"…","cause":{"name":"ProjectionStoreThreadNotFoundError","message":"…"}}}}
{"_tag":"Die","defect":"Missing key\n  at [\"state\"]"}
```

- A typed failure is `Fail` with `error` an object whose `_tag` names the error class. Other fields sit next to
  `_tag`. This matches what `orchestration.launchThread` already rejects with.
- A payload that does not decode (a missing required key, a blank string) is `Die` with `defect` a **string**. It is
  not typed: map it to a generic failure, never to `NotFound` or `NoPermission`.
- An interrupted request is also a `Failure` whose reasons are interrupts (existing handling: `exitInterruptedOnly`).
- Read the **first `Fail`** reason's `error._tag`, `error.reason` and `error.retryAt` only. `detail`, `message` and the
  nested `cause` can carry user content or paths: never log them.

Error classes for every `issues.*` RPC (shapes **Read** from `packages/contracts/src/issue.ts`):

| `error._tag` | Fields | Trefoil `IssueFailure` |
|---|---|---|
| `IssueUnavailableError` | `reason`: `tool-missing` \| `unauthenticated` \| `tracker-unsupported`; `tracker?` | `Unavailable` (keep `reason`) |
| `IssueOperationError` | `operation`, `detail`, `reason?`: `not-found` \| `read-only` \| `rate-limited`; `retryAt?` (epoch ms) | `reason` → `NotFound` / `ReadOnly` / `RateLimited` (with `retryAt`); no reason → `Failed` |
| `EnvironmentAuthorizationError` | | `NoPermission` |

**Important, Observed:** on a project with no tracker bound (a non-git workspace, the probe's case) **every**
`issues.detail` / `issues.comments` fails with `IssueUnavailableError` `{reason:"tracker-unsupported"}`. That holds for
an unknown project id and an unknown tracker too, so "not found" is **not** what a stale or foreign ref returns in that
case. `NotFound` only appears for a ref that reaches a real tracker (**Read**; not exercised, no credentials).
`tracker-unsupported` therefore means "this project or tracker cannot serve issues": show an unavailable state, not an
error toast.

Required-key rule: `issues.list` without `state` is `Die` (`Missing key at ["state"]`). Always send `state`.

## Read RPCs

| Tag | Payload | Success (Observed where stated) |
|---|---|---|
| `issues.list` | `{state:"all"\|"open"\|"closed"}` plus optional `involvement`, `projectId`, `excludeProjectIds`, `host`, `limit` (1-200 per scope), `cursors`, `refresh`, `query` (≤200), `labels` (≤20), `assignee`, `sort` | `{viewers:{}, trackers:[], entries:[], errors:[], nextCursors:{}, scopes:[]}` on a non-git workspace (all six keys present, Observed). Accepted with the full option set `{state, involvement, limit, sort, query, labels, assignee, projectId}` |
| `issues.detail` | `{projectId, tracker, host?, scope, key}` (**Do not send `refresh`**: it exists only on newer servers) | `IssueDetail` (**Read**) |
| `issues.comments` | the same ref plus `cursor` | `{comments[], nextCursor\|null}` (**Read**) |
| `issues.linkedThreads` | `{tracker, host, scope, key}`, **`host` required** (a missing host is a failure, Observed) | `{threads:[{id, projectId, title, archivedAt\|null, settled}]}` (Observed) |

`issues.linkedThreads` matches host and scope **case-insensitively**: the probe linked with host `GitHub.com`, then
queried with `GitHub.com` and `Wire/Test` and found the thread. Before the link and after the unlink the answer is
`{"threads":[]}`. An unknown key is also `{"threads":[]}`, not an error.

`IssueListResult` fields (`entries`, `trackers[]`, `errors[]`, `nextCursors`, `scopes[]`, `viewers`) and the summary,
detail, comment, event and PR shapes are as read from `packages/contracts/src/issue.ts`: parse them tolerantly,
required strings non-blank, ignore unknown keys, skip a malformed row. Scopes are `owner/repo` (GitHub, GitLab) or a
team key (Linear). `trackers` is empty and `scopes` empty here because the probe's project has no binding.

### GitHub `key` is the bare issue number (Read, confirmed)

`packages/source-control-github/src/server/gitHubIssueJson.ts` builds a summary with `key: String(raw.number)` and
`displayKey: "#" + raw.number`. Both at upstream HEAD `bc0f04da94` and at `5ddb3b4b71`. So a GitHub `IssueKey.key` is
`"123"`, `displayKey` is `#123`, `scope` is `owner/repo`. Issue templates use the file name as their `key`, but those
are a different object. A key for another tracker is that tracker's own id (Linear: `ENG-123`); treat keys as opaque.

## Thread links (mutations)

Both are commands sent with `orchestration.dispatchCommand`. The command JSON is the payload itself (no wrapper).

```json
{"type":"thread.issue.link","commandId":"<uuid>","threadId":"…","tracker":"github","host":"GitHub.com","scope":"wire/test","key":"1",
 "projectId":"…","url":"https://github.com/wire/test/issues/1","displayKey":"#1","title":"…","source":"manual"}
{"type":"thread.issue.unlink","commandId":"<uuid>","threadId":"…","tracker":"github","host":"github.com","scope":"wire/test","key":"1"}
```

- Receipt: `{"sequence": <int>}` (Observed for link, unlink, repeat link, unlink of a missing key).
- `title`, `displayKey` and `url` must be non-blank after trimming. A blank one is `Die`
  (`Expected a non-blank string at ["title"]`), so validate before sending.
- `source` is `"manual"` for Trefoil. The server also writes `"agent"` for links an agent made.
- A link on an unknown thread is `Fail` `OrchestrationV2DispatchCommandError`, `commandType` `thread.issue.link`.

### Identity and normalisation (Observed)

The stored link has the host **lowercased** (`GitHub.com` is stored as `github.com`); scope and key are trimmed.
Identity of a link is tracker + lowercased host + lowercased scope + trimmed key (`threadIssueKeyOf`; the probe
unlinks with host `GITHUB.com` and the link goes away). Trefoil's `IssueKey.identity` must match.

### Idempotency (Observed)

| Action | Result |
|---|---|
| Link a key the thread already has, **new** `commandId` | Receipt `{sequence}` (a fresh sequence), still exactly one link, no duplicate |
| Link again with the **same** `commandId` | Receipt with the **original** sequence |
| Unlink a key the thread does not have | Receipt, nothing changes |

This makes reconciling after an uncertain outcome safe, but it does **not** relax Trefoil's rule against replaying a
mutation: a retry is a new, explicit user action with a fresh `commandId`.

### Shell propagation (Observed; the plan's open question)

`thread.issue-synced` **does** reach the shell:

- A live `orchestration.subscribeShell` stream emits `{"kind":"thread.updated","sequence":N,"location":"active","thread":{…,"issues":[<link>]}}`
  for the link, where `sequence` equals the receipt's. No separate event kind. The same item arrives for unlink.
- A fresh `subscribeShell` snapshot lists `issues[0]` on the thread row; so does `orchestration.getThreadProjection`
  (`thread.issues`).
- The link object on the row: `{tracker, host (lowercased), scope, key, projectId, url, source, linkedAt (ISO), displayKey, title}`.
- Before any link the key may be **absent**; after an unlink it is the **empty array** `[]`. Treat absent and `[]` the
  same.
- `linkedThreads` is empty again after the unlink.

So chips can read `ThreadSummary.issues` from shell rows; no projection fallback is needed.

## What T2 (session parsing) must know

1. Parse `cause` as an **array**; the first `Fail` supplies `error._tag` / `reason` / `retryAt`. `Die` has a string
   `defect` and is a generic failure. Do not log `detail`, `message` or `defect`.
2. `tracker-unsupported` is the expected answer for any project without a tracker binding, including a bad ref.
3. `issues.list` result: tolerate all six keys; an empty result is `entries: []`, `trackers: []`.
4. Shell `issues` is optional and `[]` after an unlink; the link's `host` is already lowercase.
5. Link and unlink need non-blank `title`, `displayKey` and `url`.
6. `linkedThreads` needs `host`; it matches host and scope case-insensitively.
7. GitHub `key` is the bare number string.

## Differences by revision

The probe could not start upstream HEAD `bc0f04da94` (its install predates the Effect 4.1.5 upgrade; nothing in the
checkout was changed). Contract differences read at HEAD, all additive for Trefoil:

- `issues.detail` accepts `refresh?: boolean` (do not send).
- `IssueEvent.reference?{repository, number, kind, url}` (events that cross-reference an issue or PR).
- `IssueListResult.scopes` is already present in the probed reply (empty). Its doc comment now says rows are sorted by the requested `sort`.
- Tracker settings gained optional `accountId` fields. They are not part of `IssueRef`.
- `IssueOperationError` maps to HTTP 503 instead of 502 (HTTP only; the RPC encoding is unchanged).

## Refreshing this file

```sh
TREFOIL_WIRE_RECORD=<upstream revision> node scripts/validate-t3-wire.mjs <t3code checkout>
```

The probe starts an isolated server (temporary state, no credentials) and prints the recorded replies as `RECORD {…}`.
It needs a checkout whose installed dependencies match its revision.
