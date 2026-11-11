/* Subagent roster and row presentation adapted from T3 Code client-runtime
 * (state/threadSubagents.ts, state/subagentDisplay.ts), shared/orchestrationTiming.ts and
 * mobile features/threads (threadAgentsPresentation.ts, subagent-card-presentation.ts) (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.Subagent

/** T3 `deriveThreadTurnSubagents` result: "this turn's agents". */
internal data class SubagentRoster(
    val runId: String?,
    val turnActive: Boolean,
    val latestTurn: Boolean,
    val subagents: List<Subagent>,
    val liveCount: Int,
    val settledCount: Int,
)

/** T3 `resolveSubagentPillSegment`: `"2/5"` while live, else `"5 done"`. */
internal data class SubagentPill(val label: String, val accessibilityLabel: String)

internal enum class SubagentTone { Working, Completed, Failed, Stopped }

/** Port of `deriveThreadTurnSubagents`; null when the list is empty. */
internal fun subagentRoster(runs: List<ConversationRun>, subagents: List<Subagent>): SubagentRoster? = TODO("stream B")

/** Port of `resolveSubagentPillSegment`; null when the entry point should be hidden. */
internal fun subagentPill(roster: SubagentRoster?): SubagentPill? = TODO("stream B")

/** `rowTitle` + `formatSubagentDisplayTitle`: title, else prompt (80 chars), else "Subagent". */
internal fun subagentRowTitle(subagent: Subagent): String = TODO("stream B")

/** Working / Waiting / Idle / Completed / Failed / Cancelled / Interrupted; unknown → "Stopped". */
internal fun subagentStatusLabel(status: String): String = TODO("stream B")

internal fun subagentTone(status: String): SubagentTone = TODO("stream B")

/** `subagentDetailPreview` then `subagentCardDetail`; null when nothing to show. */
internal fun subagentDetail(subagent: Subagent): String? = TODO("stream B")

/** `deriveSubagentElapsedMs`: null without `startedAt`; live agents run to [now]; 0 → null. */
internal fun subagentElapsedMs(subagent: Subagent, now: Long): Long? = TODO("stream B")

/** `formatDuration`: `"350ms"`, `"1.2s"`, `"42s"`, `"1h 2m 3s"` style. */
internal fun formatSubagentDuration(elapsedMillis: Long): String = TODO("stream B")

/** "Provider · Model · Effort" with catalog labels; null when nothing is known. */
internal fun subagentModelLine(subagent: Subagent, providers: List<ProviderInstance>, models: List<ModelOption>): String? = TODO("stream B")

/** "k/n agents done" for a workflow coordinator; null for plain subagents. */
internal fun subagentWorkflowSummary(subagent: Subagent): String? = TODO("stream B")

/**
 * The local child thread a row opens, or null when the row is not tappable. Remote tasks
 * (`remoteChild` without `childThreadId`) are never openable in v1.
 */
internal fun subagentOpenThreadId(subagent: Subagent): String? = TODO("stream B")
