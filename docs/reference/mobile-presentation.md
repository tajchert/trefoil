# T3 mobile presentation reference

Studied the React Native/Expo Android app at `/Users/mtajchert/coding/priv/t3code`, baseline `46449b5379`, to guide native Compose behavior. This is a source comparison, not a claim of complete parity. Source paths below are relative to that repository.

## Identity and clocks

- `apps/mobile/src/components/ProviderIcon.tsx`, `features/threads/thread-provider-instance.ts`, `packages/client-runtime/src/state/models.ts`: resolve the current provider through its catalog instance ID, independently of the model name. Keep up to two previous provider identities behind the current icon. Account badges distinguish duplicate drivers/custom accents; catalog lookups stay environment-scoped. Ported Codex, Claude, Cursor, Grok and OpenCode vector marks; unknown drivers use a neutral fallback.
- `state/models.ts::resolveThreadWorkingStartedAt`: prefer the activity-owning run's `activityRunStartedAt`. Explicit null means no work clock; only an absent field permits fallback to the matching unfinished latest run's started/requested time. Never start over from last activity or a refresh timestamp.
- `formatWorkingDurationLabel`: seconds below 60, minutes below 60 minutes, then hours and minutes. Waiting uses completion time. Foreground/visible clocks tick once per second for elapsed labels. Snoozes wake at their actual deadline, not the next minute. Background views stop ticking.

## Conversation and tools

- `apps/mobile/src/lib/threadActivity.ts`: projected positions define visible order, including inherited history. Activity groups share run and attempt; messages, failure, compaction, handoff and notification rows break groups. A child/subagent activity remains distinct from its parent's ordinary tools.
- Successful completed turns fold intermediate commentary and ordinary tools beneath “Worked for …”; their last assistant answer stays visible. Active, streaming, failed/interrupted or unknown outcomes must not be folded into a false success. Requests and prominent rows remain visible. Unknown old metadata is handled conservatively.
- `features/threads/thread-work-log.tsx`: command rows use command input and exit state, file rows expose filename/diff, search rows expose query/results, reasoning uses formatted text. Output is selectable, scroll-bounded and copyable. Render structured fields once; legacy concatenated details are a fallback.
- `apps/server/src/orchestration-v2/WireProjection.ts::projectTurnItemForWire`: command and dynamic tool output may be removed from snapshots with `outputOmitted: true`. Expanding a row must offer an explicit read via `orchestration.getTurnItem`; empty wire output is not proof of an empty result. The response must be scoped to the original selection, source thread/item and projection revision so it cannot overwrite newer work. Loading output is a read, never a command dispatch. File-change bodies are also stripped, without an output flag: “Load details” uses the same read path. Hydrated output/details survive repeated snapshots only while source identity and lifecycle fields match.

## State and wire lessons

- A shell snapshot marked `resolvedRepositoryIdentityRoots` is repository enrichment, not an authoritative reset. Preserve threads and unrelated projects. An unmarked snapshot is authoritative, including an empty list.
- Retain list query/expansion/position and per-thread drafts/disclosures/position on navigation. Retain cached content during reconnect, with writes disabled until synchronization. Caches are bounded in memory and cleared on forgetting an environment; app-process termination still loses content caches.
- `apps/mobile/src/features/threads/threadListV2.ts` uses Pinned → Inbox/Threads → Working → Snoozed → Settled. Provider identity and run activity are distinct from task settlement. Trefoil deliberately makes collapse hide every row, as requested by its user.

## Remaining differences

Trefoil does not embed React Native and does not implement every T3 route. Full terminal-style output streaming, rich diff widgets, attachment/image rendering, interactive subagent navigation, goals, voice, remote provider icons and all provider-specific request forms remain separate work. A static subagent work row does not imply native child-thread navigation. Markdown remains a supported subset, not full CommonMark.

Keep fixture coverage for the actual wire behavior, not only hand-built UI models. The retained-state regression fixture uses 4 open, 1 working, 1 snoozed and 24 settled threads.
