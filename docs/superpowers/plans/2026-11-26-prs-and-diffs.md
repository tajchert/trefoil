# PRs and diffs on Trefoil: research plan

Date: 2026-11-26. Status: plan only, nothing implemented. Upstream reference: T3 Code at `7deb51b550` (read-only checkout at `/Users/mtajchert/coding/priv/t3code`). Trefoil paths are repo-relative; upstream paths are written `t3code/<path>:line @ 7deb51b550`.

Legend: **[V]** Verified (read in the source or confirmed with `git show`), **[U]** Unverified (inferred, or depends on runtime behaviour not exercised).

## 1. Goal and scope

Goal: from a thread, a user can see what the agent changed (a native diff viewer per turn), and, when the thread has a linked pull request, see its status and checks, read its files, and run the common PR actions. None of these may break Trefoil's no-replay and fail-closed rules.

| In scope | Reason |
|---|---|
| Phase 1: checkpoint diffs per turn plus a native Compose diff viewer, with review ordering and a hide-test-files filter | The data is already in the thread projection (`checkpoints`) and comes from two read-only RPCs. This is the largest user-visible gap. |
| Phase 2: thread PR summary/detail/checks/files read path with a stale-while-revalidate (SWR) memory cache | Trefoil already holds `thread.pullRequests` links with snapshots, so the panel can paint at once. |
| Phase 3: PR actions (merge, update branch, ready/draft, comment, submit review) as single-send mutations with pending state and reconciliation | Upstream mobile uses them heavily. They need a written safety contract first. |
| Phase 4 (later): PR list across threads, filters, saved views | Saved views are client-only prefs (2d265dc410 changed no server files **[V]**). They only make sense once a list exists. |

| Out of scope | Reason |
|---|---|
| Changed-files card in the transcript | Upstream removed it in 1c6d41a9ba #41317 **[V]**. Do not build it. |
| Working-tree and branch-range diffs (`review.getDiffPreview`) in v1 | They need file-read access and a `cwd`. Kept as an optional follow-up task (T12). |
| Inline review comments on diff lines, AI review guide (`generateReviewGuide`), stack actions, labels and reviewers | Large surfaces, little use on a phone. PO may pull some in (Q5, Q6). |
| Optimistic PR state as in d5a50a1d0d #32774 | It conflicts with no-replay unless reduced to "pending plus reconcile" (see 6). |
| Disk persistence of PR/diff caches | Trefoil writes no transcript cache to disk (`docs/IMPLEMENTATION_STATUS.md` **[V]**). PO decision Q1. |

## 2. Upstream reference

Numbered findings:

1. Checkpoint diff RPCs: `orchestration.getTurnDiff` and `orchestration.getFullThreadDiff`, at `t3code/packages/contracts/src/rpc.ts:2139-2150 @ 7deb51b550` **[V]**. Server handler at `t3code/apps/server/src/ws.ts:2131-2150 @ 7deb51b550` **[V]**. Authorized by `orchestration:read` (`t3code/apps/server/src/auth/RpcAuthorization.ts:52 @ 7deb51b550`) **[V]**.
2. The server caps diff output at `CHECKPOINT_DIFF_MAX_OUTPUT_BYTES = 10_000_000` and appends `"\n\n[truncated]"` (`t3code/apps/server/src/vcs/VcsDriver.ts:61 @ 7deb51b550`) **[V]**.
3. The projection carries `checkpointScopes` and `checkpoints` (`t3code/packages/contracts/src/orchestrationV2.ts:2364-2365 @ 7deb51b550`) **[V]**. `Checkpoint` (:1632-1646) has `{id, threadId, scopeId, runId|null, nodeId, parentCheckpointId|null, ordinalWithinScope, appRunOrdinal|null, ref, status: ready|missing|error|stale, files: CheckpointFileSummary[], filesTotal?, capturedAt}` **[V]**. `CheckpointFileSummary` (:1614) is `{path, kind, additions, deletions}` **[V]**. The wire sends at most `MAX_WIRE_CHECKPOINT_FILES = 200` files, and `filesTotal` when truncated (`t3code/apps/server/src/orchestration-v2/WireProjection.ts:27 @ 7deb51b550`) **[V]**.
4. Mobile derives turn sections with `deriveThreadCheckpointSummaries`, which skips checkpoints with a null `appRunOrdinal`/`runId`; `checkpointTurnCount = appRunOrdinal` (`t3code/packages/client-runtime/src/state/threadCheckpoints.ts @ 7deb51b550`) **[V]**. `getReadyReviewCheckpoints` (`t3code/apps/mobile/src/features/review/reviewModel.ts:550 @ 7deb51b550`) keeps checkpoints that are ready or have changes, sorted **[V]**. `buildCheckpointDiffTargets` (`t3code/apps/mobile/src/state/queryTargets.ts:15 @ 7deb51b550`) uses `getFullThreadDiff{toTurnCount}` when from==0, otherwise `getTurnDiff{from=n-1,to=n,ignoreWhitespace:false}` **[V]**.
5. `t3code/apps/mobile/src/state/reviewTurnDiffs.ts @ 7deb51b550` keeps the last answered diff per environment+thread, keyed by toTurnCount, only for the single-turn non-whitespace case **[V]**.
6. Large-diff thresholds: `LARGE_DIFF_LINE_THRESHOLD = 400` and `LARGE_DIFF_CHARACTER_THRESHOLD = 24_000` (`t3code/apps/mobile/src/features/review/reviewModel.ts:230-231 @ 7deb51b550`) **[V]**. Word diffs are skipped for lines over `REVIEW_MAX_WORD_ALT_LINE_LENGTH = 1_000` (`t3code/apps/mobile/src/features/review/reviewWordDiffs.ts:16 @ 7deb51b550`) **[V]**.
7. Native Android diff view: `t3code/apps/mobile/modules/t3-review-diff/android/src/main/java/expo/modules/t3reviewdiff/T3ReviewDiffView.kt @ 7deb51b550` **[V]**. It is a single canvas-drawn View over flattened `DiffRow`s (:524), with one shared horizontal offset (OverScroller), optional wrap (`ReviewDiffCodeLayout.kt`), sticky file headers and word-diff ranges. JS highlights only the visible window: overscan 160 rows, at most 360 rows (`t3code/apps/mobile/src/features/diffs/nativeReviewDiffHighlighter.ts @ 7deb51b550`) **[V]**.
8. Review order: `orderDiffFilesForReview` (`t3code/packages/client-runtime/src/diffFileOrder.ts:314 @ 7deb51b550`, 319 lines) **[V]**. It sorts by tier (source < test < generated), then an import-graph order, then regroups so each directory stays contiguous in tree order. Added in 210996207d #26760 and made faster in 6d4375aaf8 #42233 **[V]**.
9. Test filter: `isTestChangedFile` (`t3code/packages/shared/src/changedFileKind.ts @ 7deb51b550`) uses TEST_NAME/CLASS_TEST_NAME regexes, TEST_DIRS, and MOCK/GENERATED sets **[V]**. Mobile stores the toggle as the device-local preference `pullRequestHideTestFiles` (71ff113795 #26759) **[V]**. Its filter logic is in `t3code/apps/mobile/src/features/pull-requests/pullRequestFileFilters.ts @ 7deb51b550` **[V]**.
10. PR RPC family: `t3code/packages/contracts/src/rpc.ts:672-706` (method names) and `:1236-1452` (definitions), all with error `PullRequestRpcError` **[V]**. The PR file diff is HTTP only: `POST /api/pull-requests/diff` (`t3code/packages/contracts/src/environmentHttp.ts:834-847 @ 7deb51b550`), cursor-paged, with a 60 s client timeout (`t3code/packages/client-runtime/src/state/pullRequestDiffHttp.ts @ 7deb51b550`) **[V]**.
11. PR panel paints from saved data and reads ahead on intent (4b22bb47d4 #32911) **[V]**. Upstream persists the query cache to the mobile DB (`t3code/apps/mobile/src/connection/pull-request-cache-store.ts`, `v1:` keys) **[V]**.
12. Calls in mobile, by count: `detail` 20, `activity` 10, `runAction` 6, `list` 6, `update` 5, `diff` 4 **[V]** (grep count). These are the v1 priorities.

## 3. Wire contract

Reads need `orchestration:read`. Mutations (M) need `source-control:write` (`t3code/packages/contracts/src/clientRpcPermissions.ts:37-47 @ 7deb51b550`) **[V]**. Capability gates are optional Booleans under `environment.capabilities`: `pullRequests`, `pullRequestChecks`, `threadPullRequestLinking`, `threadPullRequests`, `threadPullRequestWatch`, `pullRequestStackActions` (`t3code/packages/contracts/src/environment.ts:100-155 @ 7deb51b550`) **[V]**. A missing key means "not supported".

| Method | Payload | Success | Kind | Gate | Phase |
|---|---|---|---|---|---|
| `orchestration.getFullThreadDiff` | `{threadId, toTurnCount, ignoreWhitespace?}` | `ThreadTurnDiff` | read | none (projection has checkpoints) | 1 |
| `orchestration.getTurnDiff` | `{threadId, fromTurnCount, toTurnCount, ignoreWhitespace?}`, from ≤ to | `ThreadTurnDiff` | read | none | 1 |
| `pullRequests.summary` | `PullRequestRef` | `PullRequestSummary` | read | `pullRequests` | 2 |
| `pullRequests.detail` | `PullRequestRef` | `PullRequestDetail` | read | `pullRequests` | 2 |
| `pullRequests.checks` | `PullRequestRef` | `NullOr(PullRequestChecks)` | read | `pullRequestChecks` | 2 |
| `pullRequests.activity` | `PullRequestRef` | `PullRequestActivity` | read | `pullRequests` | 2 (optional) |
| `pullRequests.subscribeRefreshes` | `{}` | stream of `NonNegativeInt` | stream | `pullRequests` | 2 |
| `POST /api/pull-requests/diff` | `{...Ref, cursor?, commit?, path?}` | `{patch, truncated, nextCursor, omittedFileStats?, withheldFilesReadable?}` | read (HTTP) | `pullRequests` | 2 |
| `pullRequests.runAction` | `{...Ref, action, mergeMethod?, updateMethod?, expectedHeadSha?, replyWithSummary?: true, stackNumber?, expectedStackHeads?}` | `UndefinedOr(PullRequestSummary)` | **M** | `pullRequests` + `detail.capabilities`/`viewerPermissions` | 3 |
| `pullRequests.comment` | `{...Ref, body 1..65536}` | Void | **M** | `pullRequests` | 3 |
| `pullRequests.update` | `{...Ref, title?, body?, maintainerCanModify?}` | Void | **M** | `pullRequests` | 3 |
| `pullRequests.submitReview` | `{...Ref, expectedHeadSha?, verdict comment|approve|request-changes, body, comments[]}` | Void | **M** | `pullRequests` | 3 |
| `pullRequests.list` / `listStats` / `filterOptions` | see `t3code/packages/contracts/src/rpc.ts:1236+` | | read | `pullRequests` | 4 |

All rows above **[V]** against `t3code/packages/contracts/src/rpc.ts`, `t3code/packages/contracts/src/checkpointDiff.ts`, `t3code/packages/contracts/src/pullRequest.ts` @ 7deb51b550.

Shapes **[V]**:
- `ThreadTurnDiff = {threadId, fromTurnCount, toTurnCount, diff, repositories?: [{name, repositoryPath, relativePath, diff}]}`.
- `PullRequestRef = {projectId, host?, expectedAccountId?, allowStale?, repository, number}`. The thread link (`t3code/packages/contracts/src/threadPullRequest.ts`) has `{host, repository, number, url, source, snapshot, watch?, ...}` but no `projectId`, so it is taken from the thread's `projectId`.
- `allowStale` defaults to serving the server's last-good copy. `allowStale:false` forces a fresh provider read (`t3code/apps/server/src/pullRequest/PullRequestService.ts:3240-3256, 3568-3582 @ 7deb51b550`) **[V]**. Use it only to reconcile after a mutation.
- `PullRequestAction` literals: merge, ready, draft, close, reopen, delete-branch, update-branch, enable-auto-merge, disable-auto-merge, revert, approve-workflows **[V]**.

Errors **[V]**:
- Diffs fail with `{message}` (`OrchestrationGetTurnDiffError` / `OrchestrationGetFullThreadDiffError`).
- PRs fail with `PullRequestUnavailableError {reason: cli-missing|cli-unauthenticated|provider-unsupported|repository-without-project, requirement?}` or `PullRequestOperationError {operation, detail, reason?: not-found}`. Both map to HTTP 503 on the diff endpoint.
- Auth errors are `EnvironmentAuthorizationError` (WS) and `EnvironmentAuthInvalidError` (HTTP).
- PR mutations carry no commandId and are not idempotent **[V]**. A typed Fail is a definite rejection. A timeout, disconnect or oversized frame is an unknown outcome.

## 4. Trefoil today

1. RPC plumbing: `rpc(tag, payload, epoch, guard, timeoutMillis)` at `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:845`, with at most 64 pending requests and an Interrupt on cancel **[V]**. Frames are capped at `EffectRpc.MAX_FRAME_CHARS = 16*1024*1024` (`android/core/protocol/src/main/kotlin/com/tajchert/trefoil/core/protocol/EffectRpc.kt:16`). An oversized reply fails only its own request (`T3Client.kt:901-909`) **[V]**. A 10 MB server diff fits in a frame, but its JSON-escaped form plus decoding is a memory risk **[U]**.
2. Read-RPC pattern with a captured generation/selection/revision guard: `loadActivity` at `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:1378-1405` (`orchestration.getTurnItem`) **[V]**. Diff loading copies this pattern.
3. Projection to state: `applyProjection`/`showProjection` at `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:1299-1340`, where each field uses `stableValue`. `threadPresentation(value)` at :1342-1370 is the pure derivation. `showEmptySelection` at :1096 clears state **[V]**. `checkpoints` and `pullRequests` are not yet derived into `ClientState` **[V]**.
4. Scopes are parsed at `T3Client.kt:523-526` (orchestration:operate, diagnostics:read, settings:write). Capabilities are read like `config.obj("environment").obj("capabilities")["threadStop"]` at :578-579 **[V]**. Neither `source-control:write` nor `pullRequests` is read yet **[V]**.
5. PR watches exist only as background work: `PULL_REQUEST_WATCH_TASK_ID = "pull-request-watch:"` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/BackgroundWorkProjection.kt:24`, :147-154) and `watchedPullRequests(thread)` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadQueueProjection.kt:143-144`). `thread.pull-request.watch` is sent at `T3Client.kt:2104, 2127` **[V]**. No `pullRequests.*` RPC is called **[V]**.
6. Diff UI today: `diffStats` (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationParts.kt:284-290`) counts +/- lines. The file_change row shows +/- in mono green/red (:219-230) and the raw `diffStr` in a 240dp `verticalScroll` (:253-267) **[V]**. There is no virtualization and no highlighting.
7. State and interface: `ClientState` at `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:214-311`. `UncertainOutcomeException` at :318. `interface TrefoilClient` at :321+, whose default methods call `error("... unavailable")` (e.g. :380) **[V]**.
8. Cache precedent: `ThreadProjectionCache(maxThreads=12, maxRows=6_000)`, per environment, with `forgetEnvironment` (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadProjectionCache.kt:44`) **[V]**.
9. UI shell: `enum class SidePanelTab { Preview, Screens, Agents }` at `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt:56`. The Agents flow chooses dock vs sheet at `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt:493`, tab content at :656-659, sheet at :693 **[V]**. Sheet pattern: `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadAgentsSheet.kt` (TfAdaptiveSheet with a separate content composable for screenshots) **[V]**.
10. Dependencies: `android/app/build.gradle.kts` has no syntax-highlighting library **[V]**.
11. Hotspots: `T3Client.kt` (2285 lines), `TrefoilApp.kt` (735), `ConversationUi.kt` (889), `Client.kt` (460) **[V]**. Tasks that touch them must run one after another.
12. Wire validator: `scripts/validate-t3-wire.mjs` spawns an isolated T3 server with no provider runs, so no checkpoints exist **[V]**. It can still check error shapes: `getFullThreadDiff` on a thread with no checkpoints, and `pullRequests.detail` on a non-GitHub project → `PullRequestUnavailableError` **[U]**.

## 5. Design

### Decisions

| # | Decision | Alternatives rejected | Why |
|---|---|---|---|
| D1 | Phase 1 source = projection `checkpoints` + `getFullThreadDiff`/`getTurnDiff`, exactly as upstream (finding 4) | `review.getDiffPreview` | Read-only, needs no file-read access, and the data is already in the projection |
| D2 | Parse unified diffs off the main thread (`Dispatchers.Default`) into immutable flattened `DiffRow`s | Parse in composition | A 10 MB diff can have 200k+ lines **[U]** |
| D3 | Render one `LazyColumn` over rows, keyed by row id, with `contentType` per row kind and sticky file headers (`stickyHeader`) | Canvas View as upstream; one `Text` per file | Lazy virtualization keeps composition proportional to the viewport. Compose `LazyColumn` with stable keys and fixed-height mono rows is enough, so a custom View is not justified **[U]**; T4 benchmarks it |
| D4 | No wrap by default: one shared horizontal `ScrollState` for all code rows, gutters (line numbers, +/-) pinned; a wrap toggle per viewer | Wrap only; per-row scroll | Matches upstream (finding 7). Code alignment matters, and fixed row heights keep scroll position stable. Wrap mode makes row heights variable but stays lazy |
| D5 | No syntax highlighting in v1; diff colouring (add/del background, word-diff spans) only. Later: highlight the visible window plus overscan (160 rows, cap 360, as upstream) with a small regex tokenizer, cached per row in an LRU | Add a full highlighter library now | No library in Trefoil, and JVM highlighters are heavy **[U]**. Word diffs give most of the review value. PO Q4 |
| D6 | Memory bounds: max 60_000 rows per loaded diff. Files over 400 lines or 24_000 chars (finding 6) and generated files start collapsed. Word diff only on paired lines ≤ 1_000 chars. Show a banner when `[truncated]` is present or when rows are cut | Unbounded | Keeps the heap bounded on a 10 MB reply |
| D7 | Review order + test filter ported to pure Kotlin in `core/model` with MIT attribution. Hide-tests is a device-local preference | Server-side ordering | Upstream is client-only (findings 8, 9) |
| D8 | PR caches in memory only, per environment, bounded LRU, cleared on `forgetEnvironment` | Disk persistence (upstream) | Trefoil writes no conversation-derived cache to disk. PO Q1 |
| D9 | PR panel paints from the thread link `snapshot` first, then `detail` (allowStale default), then revalidates on `subscribeRefreshes` ticks and on open | Blocking spinner | Mirrors 4b22bb47d4 within Trefoil's rules |
| D10 | PR mutations are sent once, never retried or replayed, and guarded with `expectedHeadSha`. An unknown outcome shows "Outcome unknown – checking…" and reconciles with `detail{allowStale:false}` | Optimistic state with rollback (d5a50a1d0d) | AGENTS.md: never replay mutations, reconcile unknown outcomes |

### Data model (`core/model`)

New file `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Diffs.kt`:
- `ThreadCheckpoint(id, turnCount: Int, status, files: List<CheckpointFile>, filesTotal: Int?, capturedAt)`.
- `CheckpointFile(path, kind, additions, deletions)`.
- `DiffDocument(files: List<DiffFile>, rows: List<DiffRow>, truncated: Boolean, rowCapHit: Boolean)`.
- `DiffFile(path, oldPath?, change: Added|Deleted|Modified|Renamed|Binary, additions, deletions, isTest, isGenerated, collapsedByDefault)`.
- `sealed DiffRow { FileHeader, Hunk(header), Line(kind Context|Add|Del, oldNo?, newNo?, text, wordRanges: IntArray?), Notice(text) }`, each with a stable `id: Long`.

New pure files:
- `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/UnifiedDiffParser.kt` (parser + row cap + word diffs).
- `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/DiffFileOrder.kt` (port of finding 8).
- `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/ChangedFileKind.kt` (port of finding 9).
Each port carries a header comment: `Adapted from T3 Code (MIT), <path> @ 7deb51b550`.

New file `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/PullRequests.kt`:
- `ThreadPullRequest(host, repository, number, url, source, snapshot: PrSnapshot?)`, taken from the projection.
- `PrDetail` with the fields used: state, title, isDraft, headSha, mergeability, behindBy, reviewDecision, checks, capabilities (allowed actions, merge methods), viewerPermissions.
- `PrCheck(name, status, url, required)`.
- `PrPanelState(snapshot, detail?, answered: Boolean, refreshing, error?, pendingAction: PendingPrAction?)`.
- `PendingPrAction(kind, startedAt, outcome: Sending|Unknown|Rejected(message))`.

`ClientState` (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:214-311`) gains: `checkpoints: List<ThreadCheckpoint>`, `threadPullRequests: List<ThreadPullRequest>`, `pullRequestsSupported`, `pullRequestChecksSupported`, `canWriteSourceControl`. `TrefoilClient` gains, each with an `error("... unavailable")` default:
- `suspend fun loadTurnDiff(turnCount: Int): DiffDocument`
- `fun prPanel(ref): StateFlow<PrPanelState>`
- `suspend fun refreshPullRequest(ref)`
- `suspend fun loadPullRequestDiff(ref, cursor): PrDiffPage`
- `suspend fun runPullRequestAction(ref, action, method?)`
- `suspend fun commentOnPullRequest(ref, body)`

### Session layer (`core/session`)

- Projection: `threadPresentation` (`T3Client.kt:1342-1370`) maps `checkpoints` (skipping a null `appRunOrdinal`, keeping ready-or-with-files, sorted by turn) and `pullRequests` (all links except `source == "stack-dismissed"`). Both use `stableValue` and are cleared in `showEmptySelection` (:1096).
- New `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/CheckpointDiffs.kt`:
  - It picks the RPC: turn 1 → `getFullThreadDiff{toTurnCount:1}`, turn n → `getTurnDiff{n-1, n, ignoreWhitespace:false}`.
  - Parsing runs on `Dispatchers.Default`.
  - It uses the `loadActivity` guard (generation + selection + environment), so a reply for a thread that is no longer selected is dropped.
  - An LRU keeps 6 parsed `DiffDocument`s per environment, bounded by total rows ≤ 120_000. Only completed turns are cached, because a completed turn's diff is immutable **[U]**.
  - An oversized frame shows "Diff too large for mobile" and is scoped to this request.
- New `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PullRequestReads.kt`:
  - It builds `PullRequestRef` from the link plus the thread's `projectId`.
  - The SWR cache is keyed by (environment, host, repo, number), holds up to 24 entries, and is cleared on `forgetEnvironment`.
  - One `subscribeRefreshes` stream per environment is opened only while a PR panel is visible. A counter tick triggers `detail` + `checks`.
  - Reads are prefetched on intent (when the PR chip is shown), at most 1 in flight per ref.
- PR diff HTTP: reuse the existing authorized HTTP client and shared URL policy (no new client). Cap each page at 4 MB, and page with `nextCursor` on demand.
- New `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PullRequestActions.kt`: see section 6.

### UI (`app`)

- Entry points:
  - A "Changes" action in the thread overflow (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadActionsUi.kt`), enabled when `checkpoints` is non-empty.
  - A PR chip in the conversation header when `threadPullRequests` is non-empty, showing state, checks dot and number.
- Phone: a full-screen route `ChangesScreen` with a turn picker (segmented list, newest first; each turn shows files and +/-), a file list (review order, hide-tests toggle) and the diff viewer. The PR panel is an adaptive sheet (ThreadAgentsSheet pattern) with Overview (status, checks, mergeability), Files and Actions.
- Wide/foldable: new `SidePanelTab.Changes` and `SidePanelTab.PullRequest` in `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt:56`, docked beside the conversation like Agents (`TrefoilApp.kt:493, 656-659`). In expanded width the Changes tab shows the file list and diff as a two-pane view (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/adaptive/TwoPane.kt`).
- New files:
  - `android/app/src/main/kotlin/com/tajchert/trefoil/ui/diff/DiffViewer.kt` (LazyColumn, shared h-scroll, wrap toggle, sticky headers, collapsed files with "Show N lines").
  - `android/app/src/main/kotlin/com/tajchert/trefoil/ui/diff/ChangesScreen.kt`.
  - `android/app/src/main/kotlin/com/tajchert/trefoil/ui/pr/PullRequestPanel.kt`.
  - `android/app/src/main/kotlin/com/tajchert/trefoil/ui/pr/PullRequestActions.kt`.
- Text uses `TrefoilType.mono` with fixed line height. Row heights are constant in no-wrap mode. The `AnnotatedString` for each line is built once in the row model (`remember(row.id)`), not per frame.
- The file_change row in `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationParts.kt:253-267` keeps its stats but replaces the raw 240dp dump with "Open in Changes" (it uses the same viewer when the turn is known). PO may keep the raw view (Q7).

### Offline and stale

- Disconnected or stale: show the cached diff and the cached PR detail with a "Last updated …" label. Disable all PR actions and the refresh button. Do not issue new reads until the session is live.
- Diffs of completed turns are immutable, so a cached copy is shown without a stale label **[U]**.

## 6. Safety (from AGENTS.md)

- **Never replay mutations; reconcile unknown outcomes.** `PullRequestActions` sends each mutation exactly once through `rpc` and never retries it. On a timeout, disconnect or oversized reply it sets `PendingPrAction.outcome = Unknown`, keeps the action disabled, and on the next live session calls `detail{allowStale:false}` and `checks`. It then shows "Merged" / "Not merged – try again" from the observed state. The user must tap again to resend. `expectedHeadSha` from the last detail is always sent for merge and approve, so a stale view fails with a typed error instead of merging unseen commits.
- **Session-fail vs scoped error.** SPEC.md line 14 says an uncertain mutation outcome fails the session (writes disabled) until reconciled **[V]**. PR mutations act on GitHub, not on T3 orchestration state. The plan proposes a scoped "outcome unknown" on the PR panel instead of failing the whole session. This needs a SPEC paragraph and PO approval (Q2). Until then, T10 follows SPEC strictly and uses `UncertainOutcomeException` (`Client.kt:318`).
- **Disable writes while disconnected or stale; isolate failures by environment.** Actions need `canWrite`, `canWriteSourceControl`, `pullRequestsSupported`, a live epoch, and the action listed in `detail.capabilities`/`viewerPermissions`. All caches and streams are per environment and cleared on `forgetEnvironment`.
- **Never log tokens, credentials, or conversation content.** Diff text, PR bodies, comments and file paths are conversation content: never log them. Error logs keep only the method tag, error reason enum and sizes.
- **Shared URL policy.** The PR diff HTTP call goes through the existing authorized client and URL policy. Private-LAN HTTP needs the saved consent. The PR `url` opens in the browser through the existing external-link path.
- **MIT attribution** on `DiffFileOrder.kt`, `ChangedFileKind.kt` and any parser logic adapted from upstream.
- **Fixtures only.** Tests and screenshots use synthetic diffs and PR JSON under test resources, never real user threads.
- No new disk persistence of diffs or PR data (D8).

## 7. Task breakdown

Waves keep shared hotspots serial: `Client.kt` (T1 only, later tasks only append), `T3Client.kt` (T3 → T6 → T10), `TrefoilApp.kt`/`SidePanel.kt` (T5 → T8).

| ID | Task | Owner files | Deps | Model | Effort | Wave |
|---|---|---|---|---|---|---|
| T1 | Contract foundation: model types, `ClientState` fields, `TrefoilClient` methods with defaults, fake client support | `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Diffs.kt`, `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/PullRequests.kt`, `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt` | – | Opus | S | 1 |
| T2 | Unified diff parser + word diffs + row cap; review order + test/generated detection ports (MIT) | `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/UnifiedDiffParser.kt`, `DiffFileOrder.kt`, `ChangedFileKind.kt` + tests in `android/core/model/src/test/kotlin/com/tajchert/trefoil/core/model/` | T1 types (can start against the brief's signatures) | Sonnet | M | 1 |
| T3 | Session: project `checkpoints`/`pullRequests` into state; read `pullRequests` capabilities and `source-control:write` scope; `CheckpointDiffs.kt` with guard + LRU | `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt`, `CheckpointDiffs.kt`, tests | T1 | Opus | M | 2 |
| T4 | Compose `DiffViewer` (lazy rows, shared h-scroll, wrap toggle, sticky headers, collapse, banners) + screenshots + a 50k-row scroll smoke test | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/diff/DiffViewer.kt`, `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/DiffScreenshots.kt` | T1, T2 | Sonnet | M | 2 |
| T5 | Changes screen + entry points: overflow action, phone route, `SidePanelTab.Changes`, two-pane, hide-tests pref, file_change "Open in Changes" | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/diff/ChangesScreen.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadActionsUi.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationParts.kt` | T3, T4 | Sonnet | M | 3 |
| T6 | PR read path: `PullRequestReads.kt` (ref, SWR cache, refresh stream, prefetch), HTTP diff paging with cap | `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PullRequestReads.kt`, `T3Client.kt` (wiring only) | T3 merged | Opus | M | 3 |
| T7 | Wire validator: diff RPC error shape on a no-checkpoint thread, `pullRequests.detail` on a non-GitHub project → `PullRequestUnavailableError`, `subscribeRefreshes` opens | `scripts/validate-t3-wire.mjs` | T1 (contract only) | Sonnet | S | 1 |
| T8 | PR panel UI: header chip, sheet (phone), `SidePanelTab.PullRequest` (wide), Overview/Checks/Files using `DiffViewer`, stale labels | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/pr/PullRequestPanel.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationUi.kt`, `SidePanel.kt`, `TrefoilApp.kt`, `PullRequestScreenshots.kt` | T5 merged, T6 | Sonnet | M | 4 |
| T9 | SPEC/safety paragraph for PR mutations (after PO Q2) | `SPEC.md` | Q2 | Haiku | S | 3 |
| T10 | PR actions: merge (methods from capabilities), update-branch, ready/draft, comment; `PendingPrAction`, single send, `expectedHeadSha`, reconcile with `allowStale:false` | `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/PullRequestActions.kt`, `T3Client.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/pr/PullRequestActions.kt` | T6, T8, T9 | Opus | L | 5 |
| T11 | Docs: `docs/IMPLEMENTATION_STATUS.md` rows, `AGENTS.md` one line on diff tests | docs | T5, T8, T10 | Haiku | S | 5 |
| T12 | Optional: working-tree / branch-range via `review.getDiffPreview`, gated on file-read access | `CheckpointDiffs.kt`, `ChangesScreen.kt` | T5, Q3 | Sonnet | M | later |
| T13 | Phase 4: PR list across threads, filters, saved views (client prefs) | new `android/app/src/main/kotlin/com/tajchert/trefoil/ui/pr/PullRequestList.kt`, session list read | T8 | Sonnet + Opus review | L | later |

Path key for bare names in the table: `SidePanel.kt` = `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt`; `TrefoilApp.kt` = `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`; `T3Client.kt`, `CheckpointDiffs.kt`, `PullRequestReads.kt` live in `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/`; `DiffFileOrder.kt`, `ChangedFileKind.kt` in `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/`; `ChangesScreen.kt` in `android/app/src/main/kotlin/com/tajchert/trefoil/ui/diff/`; `*Screenshots.kt` in `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/`; `SPEC.md` and `scripts/` at the repo root.

Acceptance criteria:
- **T1**: compiles, the fake client exposes the new fields, `:core:model:test` passes, no behaviour change.
- **T2**: parser tests cover add/del/rename/binary/no-newline/`[truncated]`/CRLF, the 60k row cap, and word ranges skipped over 1_000 chars. Order tests are ported from the upstream diffFileOrder test cases. The test-kind table matches `changedFileKind` examples. A 10 MB synthetic diff parses in under 1.5 s on JVM **[U]** budget.
- **T3**: projection tests (null `appRunOrdinal` skipped, ordering, stack-dismissed hidden). A guard test (selection changes mid-flight → reply dropped). RPC selection for turn 1 vs n. Oversized frame → scoped error. LRU eviction by rows.
- **T4**: screenshots for small, large-collapsed, rename/binary, truncated banner, wrap on/off, dark mode. A 50k-row fixture scrolls without OOM in a unit-level layout test.
- **T5**: phone and expanded screenshots. The overflow item is hidden without checkpoints. The hide-tests toggle persists locally.
- **T6**: SWR test (snapshot → cached detail → fresh detail). A refresh tick triggers one refetch. Cache cleared on forget. HTTP page cap enforced. The URL policy path is used (tested with a fake).
- **T7**: `node scripts/validate-t3-wire.mjs <t3code checkout>` passes against `7deb51b550`.
- **T8**: screenshots for open/draft/merged/closed, checks failing/pending, unavailable reason (cli-unauthenticated), stale offline.
- **T10**: tests show a timeout never resends, Unknown reconciles from detail, a typed Fail shows "T3 rejected …", actions are disabled while disconnected, and `expectedHeadSha` is always sent for merge.

Turn budget: each task is about 50–100 turns. T10 (L) may split into T10a (session + tests) and T10b (UI) if it runs long.

## 8. Test plan

Run from `android/`:

```sh
./gradlew :core:model:test :core:session:test :app:testDebugUnitTest :app:lintDebug
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*DiffScreenshotsKt'
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*ChangesScreenshotsKt'
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*PullRequestScreenshotsKt'
./gradlew :app:testDebugScreenshotTestDefaultTestSuite --tests '*ConversationScreenshotsKt'   # T5/T8 touch conversation surfaces
```

Wire: `node scripts/validate-t3-wire.mjs /Users/mtajchert/coding/priv/t3code` (T7, and again after T6/T10).

Notes:
- Some screenshot tests already fail on main because of relative-time labels. Re-record only the suites a task owns, and do not commit unrelated PNG churn. Fixtures pin timestamps.
- No device tests are needed. Manual check (optional): open a real T3 instance's own test thread, never a user thread, on one emulator, and scroll a large diff to judge feel.
- Run `git diff --check` before each commit (no formatter configured).

## 9. Risks, open questions, PO decisions

Risks:
- **R1 Memory on huge diffs.** A 10 MB string, its JSON-escaped frame and the parsed rows can all be on the heap at once **[U]**. Mitigations: parse and drop the raw string, cap rows, collapse large files, and let an oversized frame fail only its request. T4 measures this.
- **R2 Compose scroll performance with a shared horizontal offset.** Every visible row reads one `ScrollState` **[U]**. Fallback: apply the offset in a `graphicsLayer { translationX }` (layer-only, no recomposition). Last resort: a canvas-drawn row like upstream.
- **R3 Contract drift.** The PR schemas are large and change often. Decode leniently (tolerate additive fields), read only the fields we use, and pin with T7.
- **R4 Hotspot contention** in `T3Client.kt`/`TrefoilApp.kt`. Hence the serial chain in section 7, and new logic goes into new files.
- **R5 `checkpointTurnCount = appRunOrdinal` semantics** may differ for multi-scope (multi-repo) threads. Upstream takes `repositories[]` diffs **[U]**. v1 shows the combined diff with a repository header per section.

Open questions for the PO:
- **Q1** Persist the PR cache to disk (upstream does), or keep it memory-only (current Trefoil rule)? Recommendation: memory-only.
- **Q2** On an uncertain PR mutation outcome, fail the session (SPEC line 14) or show a scoped "outcome unknown" on the PR panel? Recommendation: scoped, with a SPEC paragraph.
- **Q3** Include working-tree / branch-range diffs (T12) in this feature?
- **Q4** Syntax highlighting: none in v1 (recommended), a small in-house tokenizer, or a library (APK size and cost)?
- **Q5** v1 PR actions: proposed merge, update-branch, ready/draft, comment. Also close/reopen, auto-merge, approve workflows?
- **Q6** Review composer (approve / request changes with a body) in v1, or later? Inline line comments are out.
- **Q7** Keep the raw diff dump in the file_change row, or replace it with "Open in Changes"?

## Review log

- 2026-11-26: initial plan from findings/pr-diffs-perf.md section E and upstream `7deb51b550`. Not yet reviewed.
