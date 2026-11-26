# Plan: thread inspector for subagents, side chats and previews (Android)

Status: research plan, 2026-11-26. Trefoil main `d06242e3`. Upstream T3 Code read-only checkout `/Users/mtajchert/coding/priv/t3code`,
pinned reference Mobile 2.4.0 = `7deb51b550` (all three upstream commits below are ancestors of it, verified with `git merge-base`).
"Verified" = read in source for this plan. "Unverified" = inferred; the owning task must check it first.

## 1. Goal and scope

Goal: on wide and foldable windows a subagent or side chat opens **beside** its parent instead of replacing it; side chats get an
opener on every form factor; the user can start a side chat (`/side`) and reference one from the parent's composer ("Add to chat").

In scope:
- A **Thread** tab in the existing docked side panel (the "inspector") that shows a child thread (app subagent, provider subagent,
  side chat) of the selected thread, read-only, with **Maximize** (today's full-screen open) and **Close**.
- Phone and windows that cannot dock: keep today's behaviour (child replaces the selected thread, Back returns to the parent), and
  extend it to side chats.
- A side-chat chip row on the parent conversation; side chats folded into the Agents tab is not required.
- Starting a side chat with `/side <question>` from the parent composer (`thread.fork` + `sideChat: true`, then `message.dispatch`).
- "Add to chat": insert a thread reference into the parent's composer and send it as a `thread` context record.
- Rendering received `t3-context://v1/thread/...` references in user messages as chips instead of raw markdown.

Out of scope (with reasons):
- **Writable inspector** (composer and approval buttons inside the pane). Upstream renders a full thread route there
  (`sideThreadComposer` gives side chats and app subagents a composer). Trefoil's `T3Client` gates every write on one selected thread
  (`threadReady`, `ThreadSession`); a second write slot is a cross-cutting session change. Maximize gives full writes today. See §9 Q1.
- **Linked-environment threads** (`linkedThread` target): needs a multi-environment model Trefoil lacks; `IMPLEMENTATION_STATUS.md:75`
  already defers remote-environment child opening. Rows with `remoteChild` stay non-openable.
- **Device previews** (`device` target): Trefoil has no device-mirroring feature.
- **Local previews**: already docked. Trefoil's Preview tab hosts `TunnelBrowserScreen` beside the conversation, which is the
  `localPreview` inspector target. Opening localhost links from the timeline into the Preview tab, and an editable address bar, are
  separate "adopt later" items (findings/previews-inspector.md verdict 2). Nothing changes here.
- Older-history paging inside the inspector. Maximize opens the full thread; see the bounded-snapshots plan.
- The `@` thread picker in the composer (matching thread titles). Optional task T8; see §9 Q4.

## 2. Upstream reference

| Commit | PR | What | Mobile files |
|---|---|---|---|
| `7c6e889ddb` | #39515 | Subagents, side chats and linked threads open the same way: in the inspector where there is one, else full screen | `t3code/apps/mobile/src/features/threads/useOpenLinkedThread.ts`, `thread-inspector-navigation.ts`, `thread-inspector-preview.tsx`, `thread-inspector-header.tsx`, `ThreadAgentsSheet.tsx`, `apps/mobile/src/Stack.tsx` |
| `b972842270` | #43210 | Android uses the same right-hand inspector pane as iOS | `t3code/apps/mobile/src/lib/layout.ts`, `apps/mobile/src/features/layout/workspace-inspector-pane.tsx`, `AdaptiveWorkspaceLayout.tsx`, `createWorkspaceStackNavigator.android.tsx` |
| `bb1a66a3a4` | #38323 | Side chat "Bring back" (merge-back) replaced by "Add to chat" and `@` reference. **Web UI only**; client-runtime filter shared with mobile | `t3code/packages/client-runtime/src/composerThreadItems.ts`, `apps/web/src/components/ThreadContextChip.tsx`, `docs/user/thread-sidebar.md` |

Behaviour at `7deb51b550` (all Verified):
- Inspector availability: `deriveFileInspectorPaneLayout` needs split view and viewport >= 820 dp
  (`t3code/apps/mobile/src/lib/layout.ts:57`, `:289-307`); pane width defaults to 40 % of the viewport, min 260, keeps the main column
  >= 320 (`INSPECTOR_MIN_MAIN_WIDTH`, `:60`, `:313-326`). The sidebar steps aside when the main column would drop below 320 (`:245-256`).
- Presentation: `resolveMobileSideThreadPresentation` returns `"inspector"` when available, else `"full-screen"`
  (`t3code/packages/client-runtime/src/sideThread.ts:90`). Targets: `thread | linkedThread | device | localPreview`
  (`t3code/apps/mobile/src/features/threads/thread-inspector-navigation.ts:10-23`). The target is a param of the parent's `Thread`
  route (`:44-46`), so it belongs to the parent and goes when the parent changes.
- Kinds: `sideThreadKindOf` = `side-chat` (lineage `sideChat` with a parent) / `subagent` / `provider-subagent`
  (`creationSource == "provider"`) (`sideThread.ts:13-26`). Composer: side chat and app subagent get the normal composer,
  provider subagent read-only, linked read-only-connect (`sideThread.ts:37-50`).
- Inspector content for `thread`: the full `ThreadRouteScreen` with an inspector header carrying **Maximize**
  (`thread-inspector-preview.tsx:86-101`, `thread-inspector-header.tsx:11-28`); maximized it becomes `ThreadInspectorFullscreen` with
  Close (`ThreadRouteScreen.tsx:1158-1170`).
- Opening from the Agents sheet: the sheet gives way and the child opens beside the parent
  (`thread-inspector-navigation.test.tsx:179`); without an inspector the child pushes as its own screen (`useOpenLinkedThread.ts:24-37`).
- Side chat opener: `ThreadSideChatsRow` above the composer, a horizontal row of chips with approval/input dots, each opening the
  side chat like a linked thread (`t3code/apps/mobile/src/features/threads/ThreadSideChatsRow.tsx:15-80`;
  placed in `ThreadDetailScreen.tsx:1749-1754`). Source: `sideChatsAtom` of the parent.
- Side chat creation on mobile: `/side <question>` in the composer (`use-composer-command-menu.ts:138-141`,
  `use-thread-composer-state.ts:703-730`). `useStartSideChat` (`t3code/apps/mobile/src/features/threads/useStartSideChat.ts:37-129`):
  `thread.fork` with a client UUID target, waits for the shell row, aligns runtime mode if it differs, `message.dispatch` the question,
  opens it; on any failure after the fork sends `thread.delete {onlyIfUnused:true}`. Unavailable when: the thread is itself a side chat
  or a subagent, the server lacks `capabilities.sideChats`, the thread has no first message yet, or its worktree is pending
  (`use-thread-composer-state.ts:950-978`).
- Add to chat (web): a button at the top of the side chat; the `@` picker offers the thread's **own** side chats only once they have a
  user message (`latestUserMessageAt != null`) (`composerThreadItems.ts:49-61`). Works while the parent is busy. External OpenCode
  agents cannot read the link (`docs/user/thread-sidebar.md` diff in `bb1a66a3a4`). Mobile 2.4.0 has no Add-to-chat button for side
  chats; it has the generic `@` thread picker (`use-composer-command-menu.ts:600-632`).

## 3. Wire contract

All calls go over the existing Effect RPC socket; `orchestration.dispatchCommand` takes the command object and returns a receipt
`{sequence}`. Paths are `t3code/packages/contracts/src/...@7deb51b550`.

| Call | Mutation | Fields (Verified) | Notes |
|---|---|---|---|
| `orchestration.subscribeThread` (stream) | no | in `{threadId, requestCompletionMarker:true}`; items `snapshot` / `synchronized` / `event` | Second concurrent subscription for the inspector child. That a server accepts two thread subscriptions on one socket is **Unverified** (web's `SideThreadPane` suggests yes); T2 checks it with the wire validator. |
| `orchestration.getThreadProjection` | no | in `{threadId}` → projection (server-bounded, recent window) | Same conflated per-event refetch as the main thread (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt:1066-1083`). |
| `thread.fork` command | **yes** | `type`, `commandId`, `createdBy:"user"`, `creationSource:"mobile"`, `sourceThreadId`, `targetThreadId` (client UUID), `sourcePoint:{type:"latest_stable"}`, `title?` (trimmed non-empty), `sideChat?: true`, `createdAt?` (`orchestrationV2.ts:3771-3781`, source point `:226-230`, creation fields `:103-118`) | Older servers ignore `sideChat` (`environment.ts:103`), so gate on the capability. |
| `message.dispatch` command | **yes** | as today plus optional `context: {version:1, guest?, records: unknown[]}` (`orchestrationV2.ts:3611-3630`; `composerContext.ts:314-335`, max 200 records, ids unique) | Trefoil today sends no `context` (Verified: no `context` writer under `android/core/session`). |
| `thread.delete` command | **yes** | `type`, `commandId`, `threadId`, `onlyIfUnused?: true` (`orchestrationV2.ts:3393-3398`) | Cleanup of an empty side chat after a failed start; a no-op when the side chat has any run or message. |
| `thread.runtime-mode.set` | **yes** | upstream sends it when the composer mode differs from the fork's (`useStartSideChat.ts:94-108`) | Trefoil T5 reuses its existing runtime-mode command; exact name is in `T3Client.kt` (Verified exists as `onSetRuntimeMode`). |
| `server.getConfig` / `subscribeServerConfig` | no | `environment.capabilities.sideChats?: boolean` (`environment.ts:104`) | Absent or false: no `/side`, no create. Opening existing side chats needs no gate. |

Thread context record (`composerContext.ts:87-91`, `:212-219`), Verified:
```json
{"version":1,"kind":"thread","contextId":"thread_<threadId>","label":"<title>","environmentId":"<env>","threadId":"<id>","title":"<title>"}
```
- `contextId` = `toKindScopedComposerContextId("thread", threadId)` (`t3code/packages/shared/src/composerContextReferences.ts:39-54`):
  `"thread_" + threadId` when that matches `^[a-z0-9_-]{1,128}$`i, otherwise slug(48) + `-` + fnv1a64 digest. Port both branches.
- `label` = `title` = sanitized title: `[`, `]`, `\`, CR, LF → space, collapse whitespace, trim, max 200 UTF-16 units without splitting a
  surrogate pair, empty → `"thread"` (`:72-83`, `:86-97`).
- In `text`, the reference is `[<label>](t3-context://v1/thread/<contextId>)` (`:56-58`, `:138-146`). The server pairs text
  occurrences with records by `contextId` and appends an envelope telling the agent to read it with `t3_thread_read`
  (`composerContextReferences.ts:346-352`, `:394-428`, used by `apps/server/src/orchestration-v2/ProviderTurnStartService.ts`).
- Undecodable records are dropped, not fatal (`composerContext.ts:314`). An occurrence without a record still becomes a marker.

## 4. Trefoil today

Reuse (Verified):
- Docked side panel with tabs `Preview | Screens | Agents`: `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt:56-104`
  (`sidePanelTabs`, `resolveSidePanelTab`, `sidePanelPresentation`), panel chrome with one header action slot `:110-130`, Agents tab
  `:163-178`. Header action plumbing: `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanelAction.kt:12-37`.
- Dock rules: `SidePanelMode` FullScreen / ReplacesList / ThirdPane (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/adaptive/WindowLayout.kt:30`,
  `:104-108`): docks at >= 840 dp (Expanded, list replaced) and third pane at >= 1600 dp; book posture always replaces the list (`:84-90`).
  Widths 360-640, default 520 (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/adaptive/AdaptiveSpec.kt:57-62`).
- Shell wiring: panel tabs `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt:437-470`, panel content `:653-660`
  (Agents rows call `select`, so a child **replaces** the parent and the roster follows the child), agents sheet `:693-694`.
- Back: `ShellBack.ToParent` only for `relationshipToParent == "subagent"` (`TrefoilApp.kt:298-302`, `:463-469`;
  `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellDestination.kt:29-52`).
- Model: `ThreadSummary.parentThreadId/relationshipToParent/sideChat/creationSource/deletedAt/latestUserMessageAt`
  (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt:32`, `:46-47`); side chats hidden from the list
  (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadListUi.kt:81-84`), attention folds into the top-level ancestor
  (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/AgentAwareness.kt:314`, `:365-367`).
  `isProviderNativeSubagentThread` (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Subagents.kt:86`).
- Thread pipeline: `selectThreadNow` (`T3Client.kt:1037-1095`), cache repaint `applyCachedThread` (`:1309-1312`), `showProjection`
  (`:1314-1341`), pure-ish `threadPresentation` (`:1343+`), `ThreadProjectionCache` per environment 12 threads / 6 000 rows, memory only
  (`android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadProjectionCache.kt:44-65`). Write gates: `threadReady`
  (`T3Client.kt:121`), `projectionLive` (`:220`), `dispatchCommand`/`mutation` with uncertain-outcome failure (`:1697-1731`),
  `threadCommand(type, threadId, extra)` for explicit targets (`:1688-1690`), `CREATION_SOURCE = "mobile"` (`:2243`).
- Conversation UI takes a whole `ClientState` (`android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationUi.kt:98-140`), so a
  read-only child can be rendered by passing a derived state with `canWrite = false`.
- Citation pattern to copy for references: markers live in composer text, chips above the composer, parse on render
  (`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/AssistantCitationDraft.kt:1-60`,
  `android/app/src/main/kotlin/com/tajchert/trefoil/ui/AssistantCitationUi.kt:96-176`, `ConversationUi.kt:546`, `ConversationParts.kt:61`).
- Screenshot baselines: `Tablet_App_SidePanel_{Preview,Agents,Screens_FoldLandscape,BookPosture,ThreePane_Desktop}`
  (`android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/ShellScreenshots.kt:245-260`).

Missing: second thread stream; inspector UI state; side-chat opener; `/side`; `thread.fork`/`thread.delete` writers; `sideChats`
capability parse; context records on `message.dispatch`; thread-reference parse/render. No "Bring back" exists, so nothing to remove.

## 5. Design

### 5.1 Data model (`core/model`)
New file `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/SideThreads.kt`:
```kotlin
enum class SideThreadKind { SideChat, Subagent, ProviderSubagent }
fun ThreadSummary.sideThreadKind(): SideThreadKind?          // port of sideThreadKindOf
fun sideChatsOf(threads: List<ThreadSummary>, parentId: String): List<ThreadSummary> // sideChat && parent == id && deletedAt == null, newest first
data class InspectorThread(                                    // published in ClientState.inspector
    val threadId: String, val kind: SideThreadKind, val ready: Boolean, val error: InspectorError?,
    val messages: List<ConversationMessage>, val activities: List<ToolActivity>, val requests: List<RuntimeRequest>,
    val runs: List<ConversationRun>, val attempts: List<RunAttempt>, val subagents: List<Subagent>, val activeRunId: String?,
)
enum class InspectorError { TooLarge, RefreshFailed, Gone }
```
`ClientState.inspector: InspectorThread? = null` and `ClientState.sideChatsSupported: Boolean = false` in
`android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt`; `TrefoilClient.openInspector(threadId)`,
`closeInspector()`, `startSideChat(question)` with throwing defaults.

New file `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/ThreadReferences.kt` (pure, ported, MIT header):
`toComposerContextId`, `sanitizeContextLabel`, `threadReferenceMarker(threadId, title)`, `collectThreadReferences(text)`,
`threadContextRecords(text, threads, environmentId): JsonArray?` (only occurrences whose `contextId` maps to a known thread of the
current environment; dedupe; cap 200), `splitThreadReferenceDraft` (like `splitCitationDraft`).

### 5.2 Session layer (`core/session`)
- **Separate subscription, never the selected-thread slot.** Reusing the slot would drop the parent's `threadReady`, Stop, queue and
  composer state, which is the behaviour we are removing. New `internal class InspectorStream` in
  `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/InspectorStream.kt`, owned by `T3Client`, guarded by
  `lifecycleLock`, with its own `inspectorGeneration`, subscription id, conflated refresh channel and `projection: JsonObject`.
  Same snapshot/synchronized/event handling as `selectThreadNow`, minus writes.
- Extract `threadPresentation(value)` to a top-level `internal fun` (it reads no client state other than its input; verify) so both
  streams share it. The inspector publishes `InspectorThread` via `update { it.copy(inspector = ...) }`.
- Rules: at most **one** inspector stream. `openInspector(id)` requires connected + `shellLive` + the thread listed with
  `parentThreadId == selectedThreadId` (or a subagent row's `subagentOpenThreadId`) and a non-null `sideThreadKind()`. It closes on:
  parent selection change, environment switch, disconnect (generation bump; it is not resubscribed after reconnect unless the UI still
  holds the target and calls `openInspector` again), the child disappearing from the synchronized shell (`Gone`).
- Cache: a cached child repaints the pane at once (`ready = false`); every authoritative snapshot is `put` into `ThreadProjectionCache`,
  so Maximize (`selectThread(child)`) repaints from memory. The cache budget already bounds the total; the inspector adds one live
  projection on top of the selected one. Frames are capped by `EffectRpc.MAX_FRAME_CHARS`; `FrameTooLarge` sets `TooLarge` on the
  inspector only. A refresh failure sets `RefreshFailed` and stops the inspector stream; it never fails the session (it sends nothing).
- Bounded snapshots (`docs/superpowers/plans/2026-11-25-bounded-thread-snapshots.md`): when that lands, the inspector sends the same
  subscribe flags but never pages history; "Maximize for older messages".
- Side chat start (`startSideChat`): one coroutine under `mutating`, preconditions under the lock (`requireWrite`, capability, parent is
  not a side chat or subagent, parent has a first message). Steps: (1) `thread.fork` via `mutation` (uncertain → session fails, as
  today); (2) wait up to 10 s for the shell to list `targetThreadId` (no send if it never appears: report "created but not here yet",
  then best-effort `thread.delete onlyIfUnused`); (3) `message.dispatch` to `targetThreadId` with `threadCommand` (provenance, text,
  empty attachments, `dispatchMode start_immediately`, runtime mode/model as the parent composer would send if those fields already
  exist on Trefoil's first-message path; otherwise inherit the fork's); (4) on a definite rejection in (3) send `onlyIfUnused` delete
  once; on an uncertain (3) do **not** delete (the message may have landed) and fail the session as `mutation` does.
- References on send: in `sendMessage(text[, mode])`, under the lock, `threadContextRecords(text, state.threads, environment.id)`;
  when non-null put `context: {version:1, records:[...]}` into `message.dispatch`. No API change for callers.

### 5.3 State ownership
| State | Owner | Persistence |
|---|---|---|
| Child projection, presentation, ready/error | `T3Client` (`InspectorStream`) | memory; projection also into `ThreadProjectionCache` |
| Inspector target (thread id) | `TrefoilApp` `rememberSaveable` (`inspectorThreadId`, `inspectorFrom: SidePanelTab?`) | saved state; cleared on environment or parent change |
| Which tab is shown | existing `sidePanelTabName` plus new `SidePanelTab.Thread` | saved state |
| Composer draft incl. reference markers | `ComposerDraftStore` (text only, as citations) | unchanged schema |
| Side-chat start in flight | `TrefoilViewModel` (single flag, like send outcomes) | memory |

### 5.4 UI and navigation
- `SidePanelTab.Thread("Chat")` exists only while a target is set; label by kind: "Subagent" / "Side chat". Tab content =
  `InspectorThreadPane` (new `android/app/src/main/kotlin/com/tajchert/trefoil/ui/InspectorThreadPane.kt`): title + kind subtitle,
  `Conversation(...)` fed a `ClientState` copy built from `state.inspector` with `canWrite = false`, `canSteer/canStop = false`, no
  composer (a read-only bar: "Read-only here. Maximize to reply." / provider subagent: "This agent takes no messages."), pending
  requests shown as a non-interactive notice "Needs approval, Maximize to answer". Panel action (`PanelActionEffect`) = **Maximize**;
  side chats with `latestUserMessageAt != null` also offer **Add to chat** (second action needs `SidePanel` to accept a list, max 2).
  States: loading (cached rows dimmed), `TooLarge`/`RefreshFailed` (message + Retry calling `openInspector`), `Gone` (closes the tab).
- Opening: Agents tab row, timeline subagent rows (`onOpenThread`), side-chat chips. When `canDock` (`TrefoilApp.kt:444`) and a thread is
  selected: set target, `openInspector`, open the Thread tab (remember the tab it came from). Otherwise: `select(child)` as today.
- Maximize: `closeInspector()`, close the Thread tab, `select(child)`; Back from the maximized child returns to the parent
  (`backParent` extended to `sideChat == true`). Upstream's maximized view has Close instead; Trefoil keeps its parent-back pattern.
- Back (extend `shellBack`): Thread tab open → back to `inspectorFrom` tab if set (e.g. Agents), else close the panel; existing
  ClosePanel/ToParent/ToList order otherwise. Add a `ShellBack.CloseInspector` case and tests in
  `android/app/src/test/kotlin/com/tajchert/trefoil/ui/shell/ShellDestinationTest.kt` and `SidePanelTest.kt` in the same directory.
- Side-chat chips: `SideChatsRow` above the composer in `ConversationUi.kt` (from `sideChatsOf(state.threads, selectedThreadId)`), dots
  for approval / input via the existing attention helpers, horizontally scrolling, hidden when empty or for a draft.
- `/side`: composer recognises `/side ` prefix when `state.sideChatsSupported` and the thread is eligible; Send runs `startSideChat`;
  on success opens the new side chat like a chip tap. Unavailable reasons are shown as the composer's existing inline notice.
- Composer reference chips: a row like `CitationChips` listing thread markers in the draft (tap = open inspector, x = remove marker).
  User-message bubbles render markers as a chip with the label (tap opens the thread when it is a known side chat of this parent).
- Phone/Medium/compact height: no Thread tab (`effectiveSidePanelMode` = FullScreen). Book posture: the tab lives in the right region
  like other tabs. Folding to FullScreen while the Thread tab is open **closes** the inspector stream; the target stays in
  `inspectorThreadId` and reopens when the window docks again, as other tabs do today. No automatic `select(child)` on fold.

### 5.5 Offline / stale
- Disconnected or reconnecting: the inspector shows its last rows (from the cache) marked stale, with no actions except Close and
  Maximize (Maximize itself follows `selectThread` rules: cached repaint offline). `/side`, Add to chat insertion is local text (allowed),
  sending follows the parent composer's gates.

## 6. Safety (AGENTS.md applied)
- No replay: `thread.fork`, `message.dispatch`, `thread.delete` are sent once each through `mutation`; an uncertain fork or dispatch fails
  the session ("reconnect and check"), reconciliation is the reconnect + shell snapshot (the new side chat either appears or not).
  The cleanup delete is sent only after a **definite** failure and only with `onlyIfUnused:true`, so it can never remove a used chat.
- Writes disabled when stale: `startSideChat` uses `requireWrite()` + `awaitFreshThread()` on the parent; the inspector offers no writes.
- Environment isolation: inspector target and stream are keyed by environment id; closed on switch; cache keys `(env, thread)`.
  `threadContextRecords` only emits records for threads of the current environment.
- No content logging: inspector, references and side-chat code log no titles, text or ids; reuse existing error strings.
- Bounded memory: one inspector stream; projection capped by frame containment; cache budget unchanged; reference records capped at 200.
- URL policy: unchanged; previews still go through `PortForwardCoordinator`. `t3-context:` hrefs are never handed to an Intent/WebView.
- Attribution: ported functions (`toComposerContextId`, fnv1a64, sanitize, `sideThreadKindOf`, side-chat start flow) get the T3 MIT header
  like `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadAgentsSheet.kt:1-3`.

## 7. Tasks

| # | Task | Owner files (full paths) | Deps | Model | Effort |
|---|---|---|---|---|---|
| T0 | Contract: add `SideThreadKind`, `InspectorThread`, `ClientState.inspector/sideChatsSupported`, `TrefoilClient` defaults; fill BRIEF facts | `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/SideThreads.kt` (new), `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/Client.kt` | — | Opus (orchestrator, ≤ 40 lines) | S |
| T1 | Thread references (pure) + tests with vectors generated from upstream `toComposerContextId`/`sanitizeComposerContextLabel` (`node -e` against the checkout) | `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/ThreadReferences.kt` (new), `android/core/model/src/test/kotlin/com/tajchert/trefoil/core/model/ThreadReferencesTest.kt` (new) | T0 | Sonnet | S |
| T2 | Inspector stream in session: extract `threadPresentation`, `InspectorStream`, open/close rules, cache hand-off, errors; parse `capabilities.sideChats`; wire check of two concurrent `subscribeThread` | `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/InspectorStream.kt` (new), `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt`, `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/ThreadProjectionCache.kt`, `android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/InspectorStreamTest.kt` (new), `scripts/validate-t3-wire.mjs` | T0 | Opus | L |
| T3 | Side chat start + references on send in session (`startSideChat`, `context` on `message.dispatch`) | `android/core/session/src/main/kotlin/com/tajchert/trefoil/core/session/T3Client.kt`, `android/core/session/src/test/kotlin/com/tajchert/trefoil/core/session/T3ClientTest.kt` (or a new `SideChatStartTest.kt`) | T1, **after T2 merges** (same file) | Opus | M |
| T4 | Shell: `SidePanelTab.Thread`, `InspectorThreadPane`, open/maximize/close, Back, fold behaviour, `backParent` for side chats, Agents/timeline openers; screenshots | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanel.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/SidePanelAction.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/shell/ShellDestination.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/TrefoilApp.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/InspectorThreadPane.kt` (new), `android/app/src/main/kotlin/com/tajchert/trefoil/TrefoilViewModel.kt`, `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/ShellScreenshots.kt`, `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/ShellFixtures.kt` | T0 (fake `ClientState.inspector` in fixtures); integrate with T2 before merge | Sonnet | L |
| T5 | Conversation: `SideChatsRow`, `/side` composer path, reference chips in composer and user bubbles, Add to chat action | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationUi.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationParts.kt`, `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ThreadReferenceUi.kt` (new), `android/app/src/test/kotlin/com/tajchert/trefoil/ui/ThreadReferenceUiTest.kt` (new), `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/ConversationScreenshots.kt` | T1; **after T4 merges** if it needs `TrefoilApp.kt` hooks | Sonnet | M |
| T6 | Docs: `docs/IMPLEMENTATION_STATUS.md` paragraph, `protocol/SPEC.md` or new `protocol/SIDE_CHATS.md` (fork, delete, context record) | those files | T2–T5 | Haiku | S |
| T7 | Integration: wire validator run, full gate, review fixes | — | all | Opus | M |
| T8 (optional) | `@` thread picker (own side chats with a user message + listed threads, port of `matchComposerThreadItems`) | `android/app/src/main/kotlin/com/tajchert/trefoil/ui/ConversationUi.kt`, `android/core/model/src/main/kotlin/com/tajchert/trefoil/core/model/ThreadReferences.kt` | T5 | Sonnet | M |

Waves: **W0** T0 (orchestrator commit). **W1** in parallel worktrees: T1, T2, T4 (T4 against fake state). **W2**: T3 (after T2, same
`T3Client.kt`), T5 (after T1; after T4 if touching `TrefoilApp.kt`). **W3**: T6 then T7. T2 and T3 must be sequential; T4 and T5 share
`ConversationUi.kt` only if T4 adds a read-only flag there; assign that flag to T4 and let T5 rebase.

Acceptance criteria:
- T1: round-trip of marker ↔ record; ids equal upstream vectors for a UUID, a non-matching id, a 300-char title, a title with `[]\n`
  and a surrogate pair at 200; unknown thread → no record.
- T2: opening keeps the parent's `selectedThreadId`, `canWrite`, `canStop`, queue untouched (assert state before/after); events on the
  child never mark the parent stale; parent switch / env switch / disconnect close the stream and send `Interrupt`; `FrameTooLarge` sets
  `TooLarge` without `fail`; only one stream after two opens; snapshot lands in cache and Maximize repaints from it; validator proves
  two concurrent subscriptions.
- T3: fork payload exactly as §3; no dispatch when the shell never lists the target; definite dispatch rejection → one `onlyIfUnused`
  delete; uncertain dispatch → no delete, session failed; capability false → refused locally, nothing sent; `context` present only when
  the text has a resolvable marker.
- T4: tablet 1280 and 1600 dp and book posture show the Thread tab beside the still-selected parent; phone/Medium open full screen as
  today; Back order tests; Maximize then Back returns to parent; side chat child has Back-to-parent.
- T5: chips row visible only with side chats; `/side` hidden without capability; Add to chat inserts one marker (no duplicate) and the
  chip appears; user bubble renders the label, not the URL.

## 8. Test plan and verification

From `android/` (Gradle 9.8, Java 21; `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` if `local.properties` is absent):
```sh
./gradlew :core:model:test                       # T0, T1
./gradlew :core:session:test                     # T2, T3
./gradlew :app:testDebugUnitTest :app:lintDebug  # T4, T5
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*ShellScreenshotsKt'         # T4: new Tablet_App_SidePanel_Thread_* (Light/Dark, ThreePane, BookPosture)
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*ConversationScreenshotsKt'  # T5: side-chat row, reference chips
./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug  # T7
node scripts/validate-t3-wire.mjs /Users/mtajchert/coding/priv/t3code   # T2/T7: two subscribeThread streams, thread.fork sideChat, delete onlyIfUnused, message.dispatch context
git diff --check
```
Some existing screenshot tests fail on main because of relative-time labels; re-record only the files a task owns and review PNG diffs.
Fixtures only (no real threads). Instrumented tests are not needed (no WebView change).

## 9. Risks, open questions, decisions

Decisions needed from the product owner:
1. **Writable inspector?** Upstream lets you reply in a side chat or app subagent inside the pane. This plan ships read-only + Maximize.
   Making it writable means a second write session in `T3Client` (send, approvals, stop for the child) with its own readiness and
   uncertain-outcome handling: estimate an extra L task (Opus) after T2. Default: read-only now.
2. **Phone side chats**: upstream opens them full screen with Close; Trefoil keeps "replace + Back to parent". OK?
3. **Add to chat placement**: upstream mobile has no button (web only). Offer it as the inspector header action and in the side-chat chip
   long-press? Default: inspector action + Maximized side chat overflow item "Add to parent chat" (which selects the parent and inserts).
4. **`@` picker (T8)**: needed now, or later?
5. Threshold: Trefoil docks at 840 dp (existing rule), upstream at 820 dp. Keep 840 (no new breakpoint).

Risks:
- Server concurrency of two thread subscriptions per socket is Unverified; if refused, fall back to a one-shot
  `getThreadProjection` + refresh on shell row changes (no live stream) for the inspector.
- Doubled refresh traffic while a busy child and parent both stream (each event triggers a bounded refetch, ≈1 MiB worst case). Mitigated
  by conflation; revisit with the bounded-snapshots incremental reducer.
- `threadPresentation` may read client state (models, pending selections); extraction must keep the parent path byte-identical
  (T2 asserts with existing session tests).
- Side chat runtime mode / model: Trefoil's first-message path may not send `modelSelection`; if not, side chats inherit the fork's
  settings (differs from upstream which applies the parent composer's pending choice). Note in release docs.
- Agents that cannot read `t3_thread_read` (external OpenCode) get a reference they cannot follow; upstream accepts this. Consider a
  one-line hint in the Add to chat confirmation.
- `docs/IMPLEMENTATION_STATUS.md` says side chats are "folded into parent attention"; a side chat waiting for approval is now reachable
  via the chip row, but answering still needs Maximize (until Q1).
