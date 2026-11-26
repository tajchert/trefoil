# Plan: issue trackers in Trefoil

Status: research plan, 2026-11-26. Trefoil base: `main` @ `d06242e3`. Upstream T3 Code reference: mobile 2.4.0 =
`7deb51b550` (read-only checkout `/Users/mtajchert/coding/priv/t3code`; HEAD `a77493caa5` only where noted).
Path forms: Trefoil paths are repo-relative; upstream paths are `t3code/<path>:line @ 7deb51b550`.
**Verified** means the fact was read in source for this plan. **Unverified** means it is inferred and needs a check
(most of those checks go into the wire probe, task T3).

## 1. Goal and scope

Goal: Trefoil users can see which issues a thread is linked to, browse a machine's issues (list, filters, detail,
comments), link or unlink the current thread, start a thread from an issue, and open issue links in chat as an
in-app issue screen. All of it hides itself on servers that do not advertise the feature.

| Phase | Scope | Verdict |
|---|---|---|
| 1 | Issue chips on a thread (from the shell row's `issues[]`), and "Linked threads" on an issue (`issues.linkedThreads`) | **In.** Cheapest piece, read-only, gated on capabilities |
| 2 | Read path: Issues destination with list, state/involvement/label/assignee/project filters, search, sort, pagination; detail with body, comments (older pages), events, linked PRs | **In** |
| 3 | Link / unlink the **current** thread from the issue detail; unlink from a chip | **In.** One `orchestration.dispatchCommand` each |
| 4 | "Start thread from this issue". Upstream does not have this; it is Trefoil's own: launch, then link | **In** |
| 5 | Issue links in chat markdown open the issue screen | **In**, with a Trefoil simplification (§5.6) |
| 6 | Write actions: comment, change state (later maybe edit, labels, assignees, reactions, create) | **Later / optional.** Needs `source-control:write` (§3.4) |
| — | Saved views (bb29ee3e71) | **Later.** Purely local preferences; add once the list ships |
| — | Tracker connect, token, account and project-binding settings (652e2f2ab5, 86863beede, 0591d32888) | **Out**, see below |

Why tracker settings stay out: `issueTrackers.connect` sends a Linear API token from the device to the server
(`t3code/packages/contracts/src/rpc.ts:1534-1541 @ 7deb51b550`, "A null token disconnects"), and both
`issueTrackers.connect` and `issueTrackers.bindProject` need `settings:write`
(`t3code/apps/server/src/auth/RpcAuthorization.ts:162-163 @ 7deb51b550`, Verified). That is server administration.
It would make Trefoil handle third-party tracker credentials, which AGENTS.md avoids. It is a one-time job best
done in T3 web or desktop. Trefoil shows *why* a list is empty instead: `IssueTrackerSummary.configured=false` plus
`detail`, and per-project `errors[]` (§3.2). Server-side features need no client work beyond tolerant parsing: forks
listing upstream issues (c79f618bf8) and several Linear accounts (0591d32888). Extra fields such as `accounts`,
`accountId` and `scopes` are ignored or optional.

## 2. Upstream reference

| Commit | What | Where |
|---|---|---|
| 20501a0221 (#19259) | Issue core + GitHub tracker, contracts, RPCs | `t3code/packages/contracts/src/issue.ts`, `t3code/packages/contracts/src/rpc.ts:708-722` |
| 86863beede (#19274) | Linear standalone tracker, `issueTrackers.*` | `t3code/packages/issue-tracker-linear/` |
| 0597b9edcd (#19270) | Thread to issue links (`thread.issue.link/unlink`), MCP tools let agents link (`source: "agent"`) | `t3code/packages/contracts/src/orchestrationV2.ts:3526-3542` |
| 6ad58a8485 (#37040) | Mobile Issues tab: list, detail, comment composer, edit, labels, assignees | `t3code/apps/mobile/src/features/issues/IssuesScreen.tsx`, `t3code/apps/mobile/src/features/issues/IssueRouteScreen.tsx` |
| 60f7e5d9b1 (#37760) | Label and assignee filters, sort | `t3code/apps/mobile/src/features/issues/IssuesScreen.tsx:136-148` |
| bb29ee3e71 (#37963) | Saved views, local preferences | `t3code/apps/mobile/src/persistence/mobile-preferences.ts:191,460` |
| b6a0fd6d62 (#38771) | Issue detail lists linked threads | `t3code/apps/mobile/src/features/issues/IssueRouteScreen.tsx:308-360` |
| 652e2f2ab5 (#37814) | Mobile settings: providers, tracker connect and bind | `t3code/apps/mobile/src/features/settings/components/IssueTrackerSection.tsx` |
| c350ee7e15, 9fde959ac0 | Web project filter (`projectId`, `excludeProjectIds`) | `t3code/apps/web/src/components/issue/` |
| 82e6b02980, 671e05bc8c | Fixes: end-of-list spinner; Activity cross-reference opens the issue | in 2.4.0 (Verified with `merge-base --is-ancestor`) |

Server version: all of the above, `issueLinkedThreads` included, first ship in tag `v0.1.11` (Verified with
`git tag --contains`). Older servers lack `capabilities.issues`.

UI behaviour in upstream mobile 2.4.0 (Verified unless marked):
- The list asks for `{state:"open", involvement:"all", limit:50}` by default
  (`t3code/apps/mobile/src/features/issues/IssuesScreen.tsx:58`). Search is debounced 300 ms (`:133`). Filters:
  involvement, labels, assignee, sort (`:238-293`). Infinite scroll uses `nextCursors` and stops when no scope has a
  cursor, which fixes an endless spinner (`:165-195`). Pull to refresh. It covers every connected environment whose
  config has `capabilities.issues === true` (`:150-157`).
- The detail shows title, state, labels and assignees, the markdown body, an Activity list (comments and events
  merged by time, `:553-566`) with "Load older comments" (`:582-588`, `:771`), linked PRs, and linked threads, which
  are hidden when there are none or the capability is absent (`:308-334`). The composer appears only when
  `capabilities.comment` is true (`:597`).
- Mobile has **no** issue chip on a thread and **no** link or unlink UI. Web has both: chips in sidebar rows
  (`t3code/apps/web/src/components/sidebar/SidebarThreadRow.tsx:1172-1209`), and link or unlink from the issue
  (`t3code/apps/web/src/components/issue/IssueThreadLinks.tsx:79-112`, which shows a toast on failure and refreshes
  linked threads after success).
- Chat links: `t3code/apps/mobile/src/native/useOpenPullRequest.ts:43-89` opens an issue screen when
  `capabilities.issues` is set and `resolveIssueLinkTarget` (`t3code/packages/client-runtime/src/issueLinks.ts:146-194`)
  maps the URL onto a project's tracker binding. Otherwise the URL goes to the browser.
- Nothing upstream starts a thread from an issue (grep of mobile, web and client-runtime; Verified in the prior
  findings, rechecked: no caller of `linkThreadIssue` outside `IssueThreadLinks`).

## 3. Wire contract (all Effect-RPC over the existing WebSocket)

### 3.1 Feature gates (server config `environment.capabilities`)
From `t3code/packages/contracts/src/environment.ts:88-231 @ 7deb51b550` (Verified). All are optional booleans.
`t3code/apps/server/src/environment/ServerEnvironment.ts:231-234` sets them to `true`.
- `issues` (`:102`): the `issues.*` RPCs exist. Without it, the whole feature is hidden.
- `issueLinkedThreads` (`:228`): `issues.linkedThreads` is answered.
- `issueTrackerAccounts` (`:230`): only matters for settings. Trefoil ignores it.

### 3.2 Read RPCs (scope `orchestration:read`, `t3code/apps/server/src/auth/RpcAuthorization.ts:155-161`, Verified)
Errors for every `issues.*` RPC: `IssueRpcError` = `IssueUnavailableError | IssueOperationError |
EnvironmentAuthorizationError` (`t3code/packages/contracts/src/rpc.ts:1455-1459`).
- `IssueUnavailableError` `{reason: "tool-missing"|"unauthenticated"|"tracker-unsupported", tracker?}`
  (`t3code/packages/contracts/src/issue.ts:464-495`).
- `IssueOperationError` `{operation, detail, reason?: "not-found"|"read-only"|"rate-limited", retryAt?: number}` (`:497-518`).

| Method | Payload | Success |
|---|---|---|
| `issues.list` (`t3code/packages/contracts/src/rpc.ts:1461`) | `IssueListInput` (`t3code/packages/contracts/src/issue.ts:281-310`): `state` "all"/"open"/"closed" (**required**); `involvement?` "all"/"assigned"/"authored"/"mentioned"; `projectId?`; `excludeProjectIds?` (≤1000); `host?`; `limit?` 1-200 (rows per scope); `cursors?` Record<"`<host> <scope>`", string ≤4096>; `refresh?`; `query?` (≤200); `labels?` (≤20, all must match); `assignee?` (login ≤320); `sort?` "updated"/"created" (absent = updated) | `IssueListResult` (`:331-351`): `viewers` Record<host, login>; `trackers[]` `IssueTrackerSummary{tracker, host, projectCount, capabilities: IssueCapabilities\|null, configured, detail\|null}`; `entries[]` `IssueListEntry` = `IssueSummary` + `projectId`, `projectTitle`; `errors[]` `{projectId, projectTitle, message}`; `nextCursors` (a scope is absent once done); `scopes?[]` `IssueScopeRef{projectId, tracker, host?, scope}` (newer, optional) |
| `issues.detail` (`t3code/packages/contracts/src/rpc.ts:1467`) | `IssueRef` `{projectId, tracker, host?, scope, key}` (`t3code/packages/contracts/src/issue.ts:88-94`). `refresh?` exists only at HEAD `a77493caa5` (`IssueDetailInput`); **do not send it** | `IssueDetail` (`:234-256`): summary fields + `projectId`, `projectTitle`, `writable`, `capabilities`, `viewerPermissions`, `states[]`, `viewer\|null`, `body` (string), `reactions?`, `comments[]` (newest page), `commentsCursor\|null`, `events[]`, `linkedPullRequests[]` |
| `issues.comments` (`t3code/packages/contracts/src/rpc.ts:1480`) | `IssueRef` + `cursor` (≤4096) | `{comments[], nextCursor\|null}`: older comments, pages walk backwards (`t3code/packages/contracts/src/issue.ts:260-270`) |
| `issues.linkedThreads` (`t3code/packages/contracts/src/rpc.ts:1474`) | `ThreadIssueKey` `{tracker, host, scope, key}` (host **required**) | `{threads[{id, projectId, title, archivedAt\|null, settled}]}`, most recently active first (`t3code/packages/contracts/src/issue.ts:562-574`) |
| `issues.candidates`, `issues.templates` | `IssueScopeRef` | Not used before phase 6 (pickers come from loaded rows) |
| `issueTrackers.list` | `{}` | Not used (settings; Linear link resolution uses `scopes`, see §5.6) |

Shared shapes (`t3code/packages/contracts/src/issue.ts`, Verified):
- `IssueSummary` (`:107-124`): `tracker` (kebab id string, e.g. `github`, `gitlab`, `linear`), `host`, `scope`
  (`owner/repo` or a team key), `key` (native id), `displayKey` (`#123`, `ENG-123`), `title`, `url`,
  `author: Actor|null`, `state {category: open|closed, name, closeReason?}`, `createdAt`, `updatedAt`,
  `closedAt|null`, `assignees[]`, `labels[]`, `commentCount`.
- `IssueActor` = `PullRequestActor`, `IssueLabel` = `PullRequestLabel`. Unverified field list: read
  `t3code/packages/contracts/src/sourceControl.ts` for `login`, `name?`, `avatarUrl?` and for label `name`, `color?`.
- `IssueComment` `{id, author|null, body, createdAt, url|null, reactions?}`.
- `IssueEvent` `{id, kind ∈ closed/reopened/labeled/unlabeled/assigned/unassigned/renamed/referenced/state-changed,
  actor|null, createdAt, detail|null, reference?{repository, number, kind: issue|pull-request, url}}`.
- `IssueLinkedPullRequest` `{repository, number, title, url, state, isDraft, closesIssue}`.
- `IssueCapabilities` (10 booleans: create, edit, comment, labels, assignees, reactions, templates,
  linkedPullRequests, events, search).
- `IssueViewerPermissions` (setState, comment, edit, labels, assignees).

### 3.3 Thread links: projection fields and commands (**mutations**)
- Shell row and thread projection: `issues?: ThreadIssueLink[]`, optional
  (`t3code/packages/contracts/src/orchestrationV2.ts:516` app thread, `:2429` thread shell, Verified).
  `ThreadIssueLink` = `ThreadIssueKey` + `projectId`, `url`, `source: "manual"|"agent"`, `linkedAt`, `displayKey`,
  `title` (`t3code/packages/contracts/src/issue.ts:541-551`). Identity: `threadIssueKeyOf` = tracker, lowercased trimmed host, lowercased trimmed
  scope, trimmed key (`t3code/packages/contracts/src/issue.ts:553-555`).
- Domain event `thread.issue-synced` (`t3code/packages/contracts/src/orchestrationV2.ts:2219`). The projector replaces the thread
  (`t3code/apps/server/src/orchestration-v2/ProjectionStore.ts:1085-1090`) and also handles it in the shell branch
  (`:2555`), so shell rows update. Verified by reading; the end-to-end shell update is still Unverified (wire probe, T3).
- `thread.issue.link` `{type, commandId, threadId, tracker, host, scope, key, projectId, url, displayKey, title,
  source}` and `thread.issue.unlink` `{type, commandId, threadId, tracker, host, scope, key}`
  (`t3code/packages/contracts/src/orchestrationV2.ts:3526-3542`). Both are sent through `orchestration.dispatchCommand`
  (scope `orchestration:operate`, `t3code/apps/server/src/auth/RpcAuthorization.ts:47`). The receipt is `{sequence}`.
- **Idempotent on the server** (`t3code/apps/server/src/orchestration-v2/Orchestrator.ts:5386-5414`, Verified):
  linking an existing key returns the thread unchanged, and so does unlinking a missing key. The server stores the
  host lowercased and the scope and key trimmed. This makes reconciliation simple. It does **not** relax the rule
  against replaying commands (§6).

### 3.4 Write RPCs (phase 6, **mutations**, scope `source-control:write`)
`issues.comment` `IssueRef + body` (1..65536 chars) → Void; `issues.setState` `IssueRef + state` (a name from
`detail.states`) → Void. `issues.update`, `issues.react` and `issues.create` are also listed
(`t3code/packages/contracts/src/clientRpcPermissions.ts:48-52`, `t3code/packages/contracts/src/issue.ts:354-405`). Trefoil has to check that the
session permissions include `"source-control:write"` (`t3code/packages/contracts/src/auth.ts:41`) next to the
existing permission parsing (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:523-526`).

## 4. Trefoil today

Nothing issue-related exists (grep of `android/`, `protocol/SPEC.md`, `docs/IMPLEMENTATION_STATUS.md`; Verified).
Pieces to reuse (all Verified):
- RPC primitive: `rpc(tag, payload, epoch, guard, timeoutMillis)` with a cap of 64 pending requests and a 30 s
  default timeout. See `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:845-857`
  and `:59`. A failed Exit becomes `ServerRejected` (`:338`), which **drops the typed cause**. Phase 2 needs the
  `_tag`/`reason` of `Fail`. Cause parsing pattern: `exitInterruptedOnly`
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadSettings.kt:85-93`).
- An oversized frame on a pending plain RPC fails only that request
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:906`). The cap is 16 MiB
  (`android/core/protocol/src/main/kotlin/com/tajchert/trefoil/core/protocol/EffectRpc.kt:16`).
- Mutation rules: `mutation(epoch, subject)`, where a definite rejection keeps the session and an uncertain outcome
  fails it (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:1722-1732`), plus `requireReceipt` (`:1703-1706`). Thread-level writes that do not need the
  thread selected follow `threadAction` → `performThreadAction`: `requireThreadWrite`, target environment check, row
  must exist and not be archived or deleted, UUID `commandId` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:2000-2033`). Link and unlink copy this pattern.
- Capability parsing from the first config: `config.obj("environment").obj("capabilities")["threadStop"]`
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:578-579`), published into `ClientState` (`:597`).
- Shell row parsing: `threadSummary(value)`
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PresentationProjection.kt:105`).
  `ThreadSummary` is `@Serializable` for the encrypted shell cache, so new fields need defaults
  (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:27-67`).
- Client interface with default `error(...)` bodies (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:321-445`, e.g. `launchThread` at `:413`).
  `ClientState` is at `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:214-310`.
- Separate read feature pattern: `android/app/src/main/kotlin/com/tajchert/trefoil/UsageController.kt:8-45` (state
  flow, cancellable load jobs, typed failures) with `UsageException`/`UsageFailure` and a 60 s cache in `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:952-990`.
- Draft launch: `ThreadDraft` (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:206-212`) and `ThreadDraftController.send` / `reconcile` / `open`
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadDraftController.kt:44-133`). The
  ViewModel's `startDraft` restores the ids of a pending launch
  (`android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt:875-880`). Composer prefill:
  `ComposerDraftStore.update/get` with `ComposerDraftKey.NewThread(environmentId, projectId)`
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ComposerDraftStore.kt:14-19,113,122`).
- Navigation: `ShellDestination { Threads, Screens, Servers, Usage, Machines }`
  (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellDestination.kt:14-17`), rail and drawer entries
  (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellNavigation.kt:96-130,304`), side panel tabs
  `Preview, Screens, Agents` (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt:56-62`), and
  thread header chips with overflow demotion (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt:586-600`).
- Markdown links: all go to `LocalUriHandler.current.openUri` (http(s) only via `isSafeLink`)
  (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/MarkdownContent.kt:224-245,287-288`). That makes it the
  single interception point for phase 5.
- Wire probe: `scripts/validate-t3-wire.mjs:188-205` creates a project and launches threads on an isolated T3.

Missing: issue models, parsers, RPC calls, capability flags, typed RPC failure reasons, `ThreadSummary.issues`, every screen.

## 5. Design

### 5.1 Data model: new `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/IssueModels.kt`
Plain data classes. Unknown tracker kinds stay strings, unknown event kinds are dropped, and unknown enum literals
decode to a fallback. `ThreadIssueLink` and `IssueKey` are `@Serializable` because they ride in the shell cache.
```kotlin
@Serializable data class IssueKey(val tracker: String, val host: String, val scope: String, val key: String) {
    val identity: String get() = "$tracker\u0000${host.trim().lowercase()}\u0000${scope.trim().lowercase()}\u0000${key.trim()}"
}
@Serializable data class ThreadIssueLink(val key: IssueKey, val projectId: String, val url: String, val source: String,
    val linkedAt: String?, val displayKey: String, val title: String)
data class IssueRef(val projectId: String, val tracker: String, val host: String?, val scope: String, val key: String)
data class IssueActor(val login: String, val name: String? = null)          // avatar not loaded in v1
data class IssueLabel(val name: String, val color: String? = null)
data class IssueState(val open: Boolean, val name: String, val closeReason: String? = null)
data class IssueSummary(val ref: IssueRef, val hostName: String, val displayKey: String, val title: String, val url: String,
    val author: IssueActor?, val state: IssueState, val createdAt: String, val updatedAt: String, val closedAt: String?,
    val assignees: List<IssueActor>, val labels: List<IssueLabel>, val commentCount: Int, val projectTitle: String)
data class IssueCapabilities(/* 10 booleans, default false */) ; data class IssueViewerPermissions(/* 5 booleans */)
data class IssueComment(val id: String, val author: IssueActor?, val body: String, val createdAt: String, val url: String?)
data class IssueEvent(val id: String, val kind: IssueEventKind, val actor: IssueActor?, val createdAt: String, val detail: String?, val referenceUrl: String?)
data class IssueLinkedPr(val repository: String, val number: Int, val title: String, val url: String, val state: String, val draft: Boolean, val closesIssue: Boolean)
data class IssueDetail(val summary: IssueSummary, val writable: Boolean, val capabilities: IssueCapabilities,
    val permissions: IssueViewerPermissions, val states: List<IssueState>, val viewer: String?, val body: String,
    val comments: List<IssueComment>, val commentsCursor: String?, val events: List<IssueEvent>, val linkedPullRequests: List<IssueLinkedPr>)
enum class IssueListState { All, Open, Closed }; enum class IssueInvolvement { All, Assigned, Authored, Mentioned }; enum class IssueSort { Updated, Created }
data class IssueQuery(val state: IssueListState = IssueListState.Open, val involvement: IssueInvolvement = IssueInvolvement.All,
    val projectId: String? = null, val text: String = "", val labels: List<String> = emptyList(), val assignee: String? = null, val sort: IssueSort = IssueSort.Updated)
data class IssueTrackerStatus(val tracker: String, val host: String, val projectCount: Int, val configured: Boolean, val detail: String?)
data class IssueListPage(val entries: List<IssueSummary>, val trackers: List<IssueTrackerStatus>, val errors: List<IssueProjectError>,
    val nextCursors: Map<String, String>, val scopes: List<IssueScope>)
data class IssueLinkedThread(val id: String, val projectId: String, val title: String, val archived: Boolean, val settled: Boolean)
enum class IssueFailure { NotSupported, NotConnected, Unavailable, NotFound, RateLimited, ReadOnly, NoPermission, TimedOut, Failed }
class IssueException(val failure: IssueFailure, message: String, val retryAtMillis: Long? = null) : Exception(message)
```
- `ThreadSummary` gains `val issues: List<ThreadIssueLink> = emptyList()` (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:29`).
- `ClientState` gains `issuesSupported`, `issueLinkedThreadsSupported` (default false; cleared on disconnect next
  to `threadStopSupported`) and, in phase 6, `canWriteIssues`.
- `TrefoilClient` gains defaults that throw `IssueException(NotSupported)`:
  `issuesList(query, cursors: Map<String,String>?, limit: Int = 50, refresh: Boolean = false): IssueListPage`,
  `issueDetail(ref): IssueDetail`, `issueComments(ref, cursor): Pair<List<IssueComment>, String?>`,
  `issueLinkedThreads(key): List<IssueLinkedThread>`, `linkIssue(target: ThreadTarget, link: ThreadIssueLink)`,
  `unlinkIssue(target: ThreadTarget, key: IssueKey)`.

### 5.2 Session layer
- New pure file `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/IssueWire.kt`. It holds
  tolerant parsers (a malformed row is skipped, not fatal; required strings must be non-blank), the `IssueQuery` →
  `IssueListInput` encoder (sends only the non-default fields, as upstream `t3code/apps/mobile/src/features/issues/IssuesScreen.tsx:142-149` does; trims
  `query` and caps it at 200 chars; caps labels at 20), the link and unlink command builders, and two ports:
  - `mergeIssuePage(held, page, sort)` from `mergeIssueListPage` / `continueIssuePage`
    (`t3code/packages/client-runtime/src/state/issues.ts:35-58`, `t3code/packages/client-runtime/src/issueList.ts:75-94`).
    It keys rows by `projectId, lowercase host, lowercase scope, key`. A repeated row replaces the held one. It sorts
    by the requested date. Cursors named in the request are replaced by the reply's.
  - `prependOlderComments(current, older)` (dedupe by id).
- `T3Client` (one writer): parse `issues` / `issueLinkedThreads` next to `threadStop` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:578-579`). Parse
  shell `issues[]` in `threadSummary` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PresentationProjection.kt:105`).
  - Read RPCs go through `rpc()` with no `mutating` wrapper. They check `issuesSupported`, being connected and the
    epoch. A failure maps to `IssueException`: `IssueUnavailableError` → Unavailable (keep `reason`);
    `IssueOperationError.reason` `not-found` → NotFound, `rate-limited` → RateLimited with `retryAt`, `read-only` →
    ReadOnly; `EnvironmentAuthorizationError` → NoPermission; timeout → TimedOut. To get there, `ServerRejected`
    carries the first `Fail` reason's error object, reduced to `_tag` + `reason` + `retryAt` (no free text is logged).
    The `Fail` encoding (`{_tag:"Fail", error:{...}}`) is **Unverified**; the T3 wire probe confirms it.
  - Use a 45 s timeout for `issues.list` and `issues.detail`, because the server calls tracker APIs (Unverified
    latency; adjust after T3).
  - `linkIssue` / `unlinkIssue` copy `performThreadAction`: `mutating`, `requireThreadWrite`, environment match, the
    row exists and is not archived or deleted, `threadCommand("thread.issue.link", threadId, …)` with
    `source:"manual"`, host lowercased (the server does it too), `mutation(epoch, "issue link")` + `requireReceipt`.
    A link whose key the row already shows is refused locally ("Already linked"), and so is an unlink of a key the
    row does not show.
- Cache: `T3Client` caches nothing for issues. The app controller holds results (§5.4).

### 5.3 State ownership
| State | Owner | Lifetime |
|---|---|---|
| Thread to issue links | `ThreadSummary.issues` (shell, server-authoritative) | Shell cache like other rows |
| Capability flags | `ClientState` | Per connection |
| List pages, filters, detail, comment pages, linked threads | new `android/app/src/main/kotlin/com/tajchert/trefoil/IssuesController.kt` | Memory only, keyed by environment id; dropped on environment switch; never written to disk |
| Pending issue link of a new-thread draft | `ThreadDraft.issueLink` (in memory) | Until linked, failed, or the draft is cleared |
| Saved views (later) | Local preferences | Device |

### 5.4 App: controller and caching
`IssuesController(scope, client: () -> TrefoilClient)` mirrors `UsageController`:
- `IssueListUiState(query, entries, trackers, errors, nextCursors, loading, loadingMore, refreshing, failure,
  moreFailure, stale)`. Search is debounced 300 ms. Changing a filter cancels the load job and starts from page one.
  `loadMore()` does nothing when `nextCursors` is empty or the previous more-load failed without a retry (the
  82e6b02980 lesson). Held rows are capped at 1,000. Past the cap, "Refine filters to see more" replaces load-more.
- Reuse: list answers are reused for 30 s per `(environmentId, query)` and the detail for 30 s per ref (matching
  upstream `staleTimeMs: 30_000`, `t3code/packages/client-runtime/src/state/issues.ts:176,200`). Pull to refresh
  sends `refresh: true` on the first page only. At most 8 cached details (LRU). Comment pages hold at most 500
  comments, then "Open on <host>" takes over.
- Linked threads: loaded with the detail when `issueLinkedThreadsSupported`, and re-read after a link or unlink succeeds.

### 5.5 UI
- **Phase 1 chips.** `ThreadIssueChip` (new file
  `android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/ThreadIssueChips.kt`) shows the first link's
  `displayKey` plus "+N" in the thread header `actions` slot, beside Agents and Servers (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt:586-600`). It
  is demoted into the thread menu as "Issues (N)" when the header budget is short (extend
  `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/HeaderBudget.kt`). Tapping opens a small sheet with
  every link (`displayKey`, title, `source` badge "agent"/"manual"). Each row opens the issue detail when
  `issuesSupported`, otherwise the URL in the browser (https only). Thread list rows stay unchanged in v1 (§9 Q5).
- **Phase 2 Issues destination.** Add `ShellDestination.Issues` (environment-scoped, like Screens). Its rail and
  drawer entry is shown only while connected to a server with `issuesSupported`. Without a connection, it stays
  visible but disabled with cached data, like Screens.
  - Phone: list screen → detail screen (push). Back returns to the list, then to Threads.
  - Wide or foldable (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/adaptive/TwoPane.kt`): list pane plus
    detail pane, with the same pane focus and keyboard rules as Threads.
  - List row: state dot (open or closed category), `displayKey` · project title, title, labels (at most 3 + "+N"),
    assignee initials, comment count, relative updated time.
  - Filter bar: State (Open/Closed/All), Involvement, Project (from shell projects), Labels and Assignee (chosen from
    values in loaded rows; `issues.candidates` comes later), Sort (Recently updated / Newest).
  - Empty states: no tracker configured, shown from `trackers[].configured == false` with `detail` and the message
    "Connect trackers in T3 on your computer"; per-project errors in a collapsible banner; and "No issues match".
  - Detail: header (state, `displayKey`, title, project, author, dates), labels, assignees, the markdown body via
    `MarkdownContent`, then an Activity list that merges comments and events by time, oldest first, with "Load older
    comments" on top. Then linked PRs, which open in the browser; Trefoil has no PR screen. Then "Linked threads"
    (hidden when empty or unsupported): each row opens the thread with `selectThreadIn(environmentId, id)`, and
    archived ones carry a label. The toolbar has Open in browser, Refresh and (phase 3) Link.
  - From a thread chip on wide screens, the detail opens in a new docked side panel tab `Issue` (extend
    `SidePanelTab`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt:56`), so the conversation stays visible. On phones it pushes the detail screen.
- **Phase 3.** The detail toolbar gets "Link to current thread" when a thread is selected in the same environment,
  it is not already linked (key identity), and `canWriteThreads`. "Unlink from current thread" appears when it is
  linked. Chip sheet rows get "Unlink" through a confirmation dialog. While a command is in flight, the button shows
  progress and repeat taps do nothing. The UI then waits for the shell row to show or drop the key.
- **Phase 4.** The detail gets a "Start thread" action. It opens the existing New thread sheet preset to
  `issue.projectId` (the user may change the project) and prefills the composer with
  `"<displayKey>: <title>\n<url>\n\n"`. It never includes the body, which can be large and is untrusted. The draft
  carries `issueLink`. See §6 for the launch-then-link sequence and failure handling.
- **Phase 5 chat links.** See §5.6.

### 5.6 Issue links in chat (Trefoil simplification of `resolveIssueLinkTarget`)
Upstream resolves through each project's `repositoryIdentity` and per-host URL definitions
(`t3code/packages/client-runtime/src/issueLinks.ts:24-61,146-194`, `t3code/packages/source-control-core/src/shared/definition.ts:689-692,862-871`).
Trefoil does not parse `repositoryIdentity`. Instead, new pure `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/IssueLinkResolver.kt`
resolves a tapped URL **only** to targets the server has already named:
1. A selected-thread `ThreadIssueLink.url` with the same normalized URL (lowercase scheme and host, no
   fragment, no trailing slash).
2. An entry in the in-memory issue list with the same normalized URL.
3. A URL shaped like an issue whose host and scope exactly match a cached `IssueListResult.scopes` entry, or a
   `trackers` host plus an entry's scope. GitHub-style is `https://<host>/<owner>/<repo>/issues/<n>` (scope
   `owner/repo`, key `n`). Linear is `https://linear.app/<workspace>/issue/<TEAM-n>` (scope `TEAM`, key `TEAM-n`).
   Matching is case-insensitive on host and scope. A lookalike host matches nothing.

`ConversationUi` provides a wrapping `UriHandler` through `CompositionLocalProvider(LocalUriHandler provides …)`
around the timeline. When `issuesSupported` and the resolver returns a ref, it opens the issue detail; otherwise it
falls back to the platform handler. `MarkdownContent.kt` needs no change. Rule 3 needs scopes, so the controller
remembers the last list's `scopes`/`trackers` per environment, and the Issues destination fills it. Before the list
has ever loaded, rules 1 and 2 still cover agent-linked issues (Unverified that the GitHub tracker's `key` is the
bare number; check in T3 with a fixture-free reading of `t3code/packages/source-control-github/`).

### 5.7 Offline, disconnected, stale
- Reads need `connection == Connected`. While disconnected, a held list or detail stays visible with a "Not live"
  note, and refresh and pagination are disabled. Opening a new detail while disconnected shows "Connect to view this issue".
- Link, unlink and start-from-issue are disabled unless `canWriteThreads`. `requireThreadWrite` also checks the
  shell is ready. Phase 6 writes also need `canWriteIssues`.
- An environment switch drops all controller state (environment isolation). A capability that turns off on
  reconnect hides the destination and returns the user to Threads.

## 6. Safety and constraints (AGENTS.md applied)
- **No mutation replay.** `thread.issue.link`/`unlink` go through `mutation()`: sent once, with a fresh UUID
  `commandId`. A definite rejection shows "T3 rejected the issue link" and keeps the session. Timeout, disconnect or
  an interrupted Exit fails the session (writes disabled). After reconnect, the shell row's `issues[]` is the truth:
  the UI shows the linked or unlinked state from it and never resends on its own. A user may tap Link again. The
  server's idempotency (§3.3) makes that safe, but it is still a fresh, explicit command.
- **Launch then link (phase 4).** The link needs an existing thread, so it is never folded into `launchThread`.
  Sequence in `ThreadDraftController`:
  1. `launchThread` as today: same draft ids, same uncertainty rules.
  2. When the thread is known to exist (the launch result, or `reconcile` finding the draft `threadId` in a live
     shell), send `linkIssue` **at most once per draft**: set a `linkAttempted` flag before sending.
  3. On link success, nothing to do; the shell shows the chip.
  4. On a definite rejection, show "Thread started; the issue was not linked" with a **Link issue** action, which
     sends a new explicit `linkIssue`.
  5. On an uncertain link, the session fails as usual. After reconnect, if the row lacks the key, the same "Link
     issue" action shows on the thread (no auto-send).

  An agent may also link the same issue itself (`source: "agent"`). A later manual link of that key is then a no-op
  on the server; the local check refuses it first when the row already shows it. The pending link lives in memory;
  if the process dies in between, the URL is still in the first message, so nothing is lost silently (§9 Q4).
- **Disable writes when stale.** See §5.7. Commands capture the target `ThreadTarget` when built and check
  environment and generation under `lifecycleLock`, as `performThreadAction` does.
- **URL policy.** Issue, PR, comment and event URLs open only as `https://` (also `http://` to match
  `isSafeLink`) through the system browser. They are never loaded in an in-app WebView and never used as a
  connection endpoint. The shared connection URL policy (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/PairingLinks.kt`) is not involved and must not be bypassed
  or reused for this. Avatars are not loaded in v1, so there are no third-party image fetches.
- **No logging of content or tokens.** Never log issue titles, bodies, comments, `IssueOperationError.detail`,
  search text or URLs. Log tags and reasons only. Trefoil never handles tracker credentials (§1).
- **Environment isolation.** All issue state is keyed by environment id. Each RPC is checked against the epoch, so
  a reply from an old connection is dropped. Per-project `errors[]` show as a partial result, not a failure.
- **Bounded memory.** Limit 50 per scope per page, at most 1,000 held rows, at most 500 comments per detail, at most
  8 cached details, with bodies rendered as-is (the server caps them at 64 KiB, `t3code/packages/contracts/src/issue.ts:351-353`). The 16 MiB frame
  cap already fails only the offending request (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:906`). Nothing is persisted.
- **Additive fields.** All parsers ignore unknown keys. Unknown `tracker` values display as-is, unknown event kinds
  are dropped, and a missing `issues` on a shell row means no links.
- **Attribution.** Merge and parse helpers ported from T3 (`mergeIssueListPage`, `continueIssuePage`,
  `threadIssueKeyOf`) carry the T3 MIT attribution comment used elsewhere in `core/session`.

## 7. Task breakdown

Rules: `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt` has one writer at a time, and so does `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`. Each task ends with a commit on its
branch and a report: branch, SHA, checks run, checks not run. Run Gradle from `android/`.

**Wave 1 (foundation, sequential first)**

**T1: Contract types and interface. Opus, S.** Owns
`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/IssueModels.kt` (new),
`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt` (ThreadSummary.issues, ClientState
flags, TrefoilClient defaults) and
`android/core/model/src/test/kotlin/com/tajchert/trefoil/core/model/IssueModelsTest.kt` (key identity, shell-cache
round trip of `ThreadSummary` with issues, old cache without the field). Acceptance: `:core:model:test` passes; no
behaviour change. Everything below builds against this.

**Wave 2 (parallel, three worktrees)**

**T2: Session wiring. Opus, M-L.** Depends on T1. Owns
`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/IssueWire.kt` (new),
`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt`,
`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PresentationProjection.kt` (`threadSummary`),
`android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/IssueWireTest.kt` (new) and
`android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/IssueSessionTest.kt` (new; fake socket like
`android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/UsageClientTest.kt`), plus synthetic fixtures `protocol/fixtures/issue-list.json`, `protocol/fixtures/issue-detail.json`.
Tests:
- Parsers tolerate missing optionals, unknown event kinds, unknown trackers and malformed rows; capability gating
  refuses reads.
- Typed failure mapping covers each error tag. Merge covers repeats, other scopes and sort.
- Link and unlink send one command with the right fields. Rejection keeps the session; a timeout fails it with no
  resend. Already-linked is refused locally. A shell row with `issues` parses.

Acceptance: `:core:model:test :core:session:test` pass.

**T3: Wire probe and protocol doc. Sonnet, M.** No dependencies. Owns `scripts/validate-t3-wire.mjs` and
`protocol/ISSUES.md` (new). On the isolated T3, after the existing launch:
- Assert `config.environment.capabilities.issues`.
- Call `issues.list {state:"open"}` and record the shape: expect `entries: []`, plus `trackers`/`errors` for a
  non-git workspace.
- Call `issues.detail` on a fake ref and record the **exact Exit cause JSON** of the typed failure.
- Dispatch `thread.issue.link` with a synthetic key (`github`, `github.com`, `wire/test`, `1`) and assert that the
  shell row and projection show `issues[0]` with the host lowercased.
- Call `issues.linkedThreads` on that key: expect the thread, if the capability is set.
- Send the link again: expect a receipt and no duplicate. Unlink: expect the key gone.

Run with `node scripts/validate-t3-wire.mjs /Users/mtajchert/coding/priv/t3code` (it never touches the developer's
T3 state). Report the Unverified items of §3, §5.2 and §5.6 as confirmed or changed. Acceptance: the script passes,
and ISSUES.md documents the contract as Trefoil uses it (no links to gitignored plans).

**T4: Phase 1 UI (chips and the links sheet). Sonnet, M.** Depends on T1 only (uses a fake client and state). Owns
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/ThreadIssueChips.kt` (new),
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/LinkedThreadsSection.kt` (new, a stateless list that
T6 reuses), `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/HeaderBudget.kt`, the chip slot in
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt` (an `onOpenIssue: (ThreadIssueLink) -> Unit`
callback that opens the URL for now) and
`android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/IssueScreenshots.kt` (new, plus
`android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/IssueFixtures.kt`). Tests: unit tests for the header budget demotion; screenshots of one chip, of "+2", of the
sheet in light and dark, and of the narrow-width demotion. Acceptance:
`:app:testDebugUnitTest :app:lintDebug`, then `:app:updateDebugScreenshotTestDefaultTestSuite --tests '*IssueScreenshotsKt'`
and commit only those PNGs.

**Wave 3 (after T2 and T4 merge; parallel, two worktrees)**

**T5: Phase 2 list and navigation. Sonnet, L.** Owns
`android/app/src/main/kotlin/com/tajchert/trefoil/IssuesController.kt` (new),
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/IssuesScreen.kt` (new; list, filter bar, empty and
error states), `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellDestination.kt`,
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellNavigation.kt`,
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt` (the `Issue` tab),
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`,
`android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt` (controller wiring; reset on environment
switch) and `android/app/src/main/kotlin/com/tajchert/trefoil/MainActivity.kt`. It wires T4's `onOpenIssue` to
open the detail by navigation; the detail content comes from T6 as a composable slot.
`android/app/src/test/kotlin/com/tajchert/trefoil/IssuesControllerTest.kt` covers debounce, filter reset, the
load-more stop with no cursors, the stop after a failure without retry, the row cap, staleness reuse and the
environment-switch reset. Add `shellBack`/`NavEntries` tests for `Issues`. Add screenshots for list, empty
unconfigured, errors banner, filters and two-pane. Acceptance: same gates as T4, plus `--tests '*ShellScreenshotsKt'`
only if the rail changed (re-record only rail PNGs that changed on purpose).

**T6: Phase 2 detail. Sonnet, M-L.** Owns
`android/app/src/main/kotlin/com/tajchert/trefoil/IssueDetailController.kt` (new: detail, comment paging, linked
threads, LRU) and `android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/IssueDetailScreen.kt` (new;
stateless `IssueDetailContent(state, actions)`), plus their test
`android/app/src/test/kotlin/com/tajchert/trefoil/IssueDetailControllerTest.kt` and detail screenshots (header,
activity merge, load older, linked PRs, linked threads with an archived row, unavailable error, disconnected).
It must not edit `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`; T5 mounts the composable. Acceptance: same gates.

**Wave 4 (after wave 3 merges)**

**T7: Phase 3 link and unlink. Sonnet, S-M.** Owns link and unlink in
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/IssueDetailScreen.kt` and
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/ThreadIssueChips.kt`, the actions in
`android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt` (via the existing `mutation(...)` helper,
`android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt:871` style) and `android/app/src/main/kotlin/com/tajchert/trefoil/IssueDetailController.kt` (refresh linked threads after success). Tests:
enabled rules (selected, same environment, not linked, `canWriteThreads`), in-flight guard, state after
rejection, and no resend after an uncertain outcome. Acceptance: `:app:testDebugUnitTest :app:lintDebug` plus
detail screenshots with the Link and Unlink buttons.

**T8: Phase 5 chat issue links. Sonnet, M.** Can run in parallel with T7; rebase before merge, because both touch
`android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt` lightly. Owns
`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/IssueLinkResolver.kt` (new) and its test
`android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/IssueLinkResolverTest.kt` (thread link
match, list match, GitHub and Linear patterns, lookalike host `github.com.evil.io`, a port in the host, query and
fragment stripping, Enterprise host only via scopes), the `UriHandler` wrapper in
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationUi.kt`, and the scopes memory in
`android/app/src/main/kotlin/com/tajchert/trefoil/IssuesController.kt`. Acceptance: `:core:session:test :app:testDebugUnitTest :app:lintDebug`.

**Wave 5**

**T9: Phase 4 start thread from issue. Opus, M.** Owns `ThreadDraft.issueLink` in
`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt`,
`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadDraftController.kt`, the prefill and
"Link issue" retry action in `android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt`, the Start
thread button in `android/app/src/main/kotlin/com/tajchert/trefoil/ui/issues/IssueDetailScreen.kt`, and the preset in
`android/app/src/main/kotlin/com/tajchert/trefoil/ui/NewThreadSheet.kt`. Tests in
`android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/ThreadDraftControllerTest.kt`:
- The link is sent once after a launch succeeds.
- An uncertain launch followed by a `reconcile` hit sends one link.
- A definite link rejection shows the notice and no retry.
- An uncertain link means no resend after reconnect.
- An environment switch between launch and link sends no link.
- A prefill never overwrites a non-empty composer draft for that project (ask instead).

Acceptance: `:core:model:test :core:session:test :app:testDebugUnitTest :app:lintDebug`.

**T10: Docs. Haiku, S.** After T9. Owns `docs/IMPLEMENTATION_STATUS.md` and a `protocol/SPEC.md` pointer to
`protocol/ISSUES.md`. Acceptance: `git diff --check`.

**Later (separate goal): T11: Phase 6 comment and change state. Opus, M.** Covers `canWriteIssues` from
`source-control:write`, `issues.comment` and `issues.setState` as mutations (sent once; on an uncertain outcome
re-read the detail and never resend; a duplicate comment is the risk), gating on `capabilities.comment` /
`permissions.comment` / `writable`, and states from `detail.states`. Saved views (local preferences) also come later
(Sonnet, S).

Parallelism summary: T1 → {T2, T3, T4} → {T5, T6} → {T7, T8} → T9 → T10. T2 and T9 are the Opus reviews before merge.

## 8. Test plan and verification
- Fast tier per task, from `android/`: `./gradlew :core:model:test :core:session:test :app:testDebugUnitTest :app:lintDebug`
  (narrow to the touched modules).
- Screenshots: `./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*IssueScreenshotsKt'` to record, and
  `:app:testDebugScreenshotTestDefaultTestSuite --tests '*IssueScreenshotsKt'` to validate. Some existing screenshot
  tests already fail on main because of relative-time labels. Do not re-record unrelated suites. Issue fixtures use
  fixed instants and a fixed clock.
- Wire: `node scripts/validate-t3-wire.mjs /Users/mtajchert/coding/priv/t3code` (isolated T3; never real threads).
- Final gate once, on the merged commit: the AGENTS.md check list
  (`./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`)
  plus `git diff --check`.
- Manual (optional, emulator): connect to a T3 ≥ v0.1.11 with a GitHub-backed project. Open Issues, filter, open a
  detail, load older comments, link the current thread, see the chip, unlink it, start a thread from an issue, and
  tap an issue URL in chat. Use a throwaway repository; never real user threads.

## 9. Risks, open questions, decisions

Risks
1. **Typed failure encoding** (`Fail.error._tag`) is Unverified. If it differs, the mapping in T2 changes. T3 records it first.
2. **Shell propagation of `thread.issue-synced`** was read but not observed. If shell rows do not update, chips need
   the selected-thread projection's `issues` as a fallback (the same field is in `OrchestrationV2AppThread`).
3. **Tracker latency and rate limits.** The server calls GitHub or Linear, so a list can take seconds, and
   `rate-limited` comes with `retryAt`. Show the time and do not retry automatically.
4. **Linked threads outside the shell.** These are archived, deleted or hidden. `selectThreadIn` may refuse them;
   show "This thread is archived" instead of failing.
5. **Chat link resolution is narrower than upstream.** GitHub Enterprise and fork scopes resolve only after a list
   load gave `scopes`. Before that, those links open in the browser. This is acceptable and documented.
6. **Scope of T5.** It is the largest task. If it runs past about 100 turns, split filters into a follow-up (Sonnet, S).

Open questions (defaults in bold)
1. Start from issue: **prefill the composer, user sends** vs auto-send. Prompt template **`<displayKey>: <title>\n<url>`**; never the body?
2. Wide screens: should an issue opened from a thread chip use the **side panel `Issue` tab**, or switch to the Issues destination?
3. Navigation: Issues as **a 5th rail and drawer entry, shown only with `capabilities.issues`**, or nested under Threads?
4. Persist a pending start-from-issue link across process death? **No** (the URL is in the first message), or store it with `pendingLaunch` in `ComposerDraftStore`.
5. Show issue chips on thread list rows too (web does), or **thread header only** in v1?
6. Phase 6 (comment, state) in this goal, or **a later goal**? Tokens from pairing may lack `source-control:write`.
7. Should Trefoil show "Connect trackers in T3 on your computer" with a deep link to the T3 web settings, or **text only**?
