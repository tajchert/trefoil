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
import com.tajchert.trefoil.core.model.isSubagentActiveStatus
import com.tajchert.trefoil.core.model.isSubagentTerminalStatus

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

private val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")
private val REASONING_EFFORT_OPTION_IDS = listOf("reasoningEffort", "effort", "reasoning", "variant", "reasoning_effort", "thinking_level")
private const val PROMPT_TITLE_LIMIT = 80
private const val DETAIL_LIMIT = 280

private fun subagentOrderKey(subagent: Subagent): Long = validThreadInstant(subagent.startedAt ?: subagent.updatedAt) ?: 0L

/** Port of `deriveThreadTurnSubagents`; null when the list is empty. */
internal fun subagentRoster(runs: List<ConversationRun>, subagents: List<Subagent>): SubagentRoster? {
    val rolledBack = runs.filter { it.status == "rolled_back" }.map { it.id }.toSet()
    val eligible = subagents.filter { it.runId == null || it.runId !in rolledBack }
    val first = eligible.firstOrNull() ?: return null
    val activeRun = runs.lastOrNull { it.status in ACTIVE_RUN_STATUSES }
    fun updated(s: Subagent) = validThreadInstant(s.updatedAt) ?: 0L
    val latestUpdated = eligible.fold(first) { latest, s -> if (updated(s) > updated(latest)) s else latest }
    // Ordinal, not list position: a reordered queue can leave runs out of order. Queued and never-started runs replace nothing.
    val latestStartedRun = runs.fold<ConversationRun, ConversationRun?>(null) { latest, run ->
        if (run.startedAt != null && run.id !in rolledBack && (latest == null || (run.ordinal ?: 0) > (latest.ordinal ?: 0))) run else latest
    }
    val latestStartedRunHasAgents = latestStartedRun != null && eligible.any { it.runId == latestStartedRun.id }
    val runId = activeRun?.id ?: if (latestStartedRunHasAgents) latestStartedRun?.id else latestUpdated.runId
    val roster = eligible.filter { it.runId == runId || it.isActive || it.workflow != null }
        .sortedWith(compareBy<Subagent> { subagentOrderKey(it) }.thenBy { it.id })
    if (roster.isEmpty()) return null
    val live = roster.count { it.isActive }
    val settled = roster.count { !it.isActive && it.isTerminal }
    return SubagentRoster(runId, activeRun != null, runId == null || latestStartedRun == null || runId == latestStartedRun.id, roster, live, settled)
}

/** Port of `resolveSubagentPillSegment`; null when the entry point should be hidden. */
internal fun subagentPill(roster: SubagentRoster?): SubagentPill? {
    if (roster == null) return null
    // Finished agents stay reachable until a later turn replaces them; workflows always stay reachable.
    if (!roster.turnActive && !roster.latestTurn && roster.liveCount == 0 && roster.subagents.none { it.workflow != null }) return null
    val total = roster.subagents.size
    if (roster.liveCount > 0) return SubagentPill("${roster.liveCount}/$total", "${roster.liveCount} of $total agents working")
    return SubagentPill("${roster.settledCount} done", "${roster.settledCount} ${if (roster.settledCount == 1) "agent" else "agents"} done")
}

private val SUBAGENT_PREFIX = Regex("^Subagent:\\s*", RegexOption.IGNORE_CASE)
private val ROOT_PATH = Regex("^/root/(?:[^/]+/)*([^/]+)/?$")

private fun formatSubagentDisplayTitle(title: String): String {
    val display = title.replace(SUBAGENT_PREFIX, "")
    val name = ROOT_PATH.find(display)?.groupValues?.get(1) ?: return display
    val words = name.replace(Regex("[_\\s]+"), " ").trim()
        .replace(Regex("(^|\\s)\\S")) { it.value.uppercase() }
    return words.ifEmpty { display }
}

/** `rowTitle` + `formatSubagentDisplayTitle`: title, else prompt (80 chars), else "Subagent". */
internal fun subagentRowTitle(subagent: Subagent): String {
    subagent.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return formatSubagentDisplayTitle(it) }
    val prompt = subagent.prompt.trim()
    if (prompt.isEmpty()) return "Subagent"
    return if (prompt.length > PROMPT_TITLE_LIMIT) prompt.take(PROMPT_TITLE_LIMIT - 3) + "..." else prompt
}

/** Working / Waiting / Idle / Completed / Failed / Cancelled / Interrupted; unknown -> "Stopped". */
internal fun subagentStatusLabel(status: String): String = when (status) {
    "pending", "running" -> "Working"; "waiting" -> "Waiting"; "idle" -> "Idle"; "completed" -> "Completed"
    "failed" -> "Failed"; "cancelled" -> "Cancelled"; "interrupted" -> "Interrupted"; else -> "Stopped"
}

internal fun subagentTone(status: String): SubagentTone = when {
    isSubagentActiveStatus(status) -> SubagentTone.Working
    status == "completed" -> SubagentTone.Completed
    status == "failed" -> SubagentTone.Failed
    else -> SubagentTone.Stopped
}

private val WHITESPACE = Regex("[\\s\\u00A0]+")
private val MD_LINK = Regex("!?\\[([^\\]]+)\\]\\((?:\\\\.|[^\\\\()]|\\((?:\\\\.|[^\\\\()])*\\))*\\)")
private val LIST_BULLET = Regex("^[ \\t]*[-*][ \\t]+", RegexOption.MULTILINE)

/** T3 `subagentDetailPreview`: result for terminal agents, progress otherwise; whitespace-collapsed, capped at 280 chars. */
private fun subagentDetailPreview(subagent: Subagent): String? {
    val terminal = isSubagentTerminalStatus(subagent.status)
    val preferred = if (terminal) subagent.result else subagent.progress
    val fallback = if (terminal) subagent.progress else subagent.result
    val detail = (preferred?.trimStart()?.takeIf { it.isNotEmpty() } ?: fallback?.trimStart() ?: "").trimEnd().replace(WHITESPACE, " ")
    if (detail.length <= DETAIL_LIMIT) return detail.ifEmpty { null }
    var end = DETAIL_LIMIT
    if (detail[end - 1].isHighSurrogate() && detail[end].isLowSurrogate()) end -= 1
    return detail.substring(0, end).trimEnd() + "\u2026"
}

/** `subagentDetailPreview` then `subagentCardDetail`; null when nothing to show. */
internal fun subagentDetail(subagent: Subagent): String? {
    val detail = subagentDetailPreview(subagent)
    if (detail.isNullOrEmpty() || Regex("^Child task ended with status\\b", RegexOption.IGNORE_CASE).containsMatchIn(detail)) return null
    return detail.replace(MD_LINK, "\$1").replace("`", "").replace(LIST_BULLET, "").replace(WHITESPACE, " ").trim().ifEmpty { null }
}

/** `deriveSubagentElapsedMs`: null without `startedAt`; live agents run to [now]; 0 -> null. */
internal fun subagentElapsedMs(subagent: Subagent, now: Long): Long? {
    val start = validThreadInstant(subagent.startedAt) ?: return null
    val end = if (subagent.isActive) now else validThreadInstant(subagent.completedAt) ?: return null
    return (end - start).coerceAtLeast(0).takeIf { it > 0 }
}

/** `formatDuration`: `"350ms"`, `"1.2s"`, `"42s"`, `"1h 2m 3s"` style. */
internal fun formatSubagentDuration(elapsedMillis: Long): String {
    if (elapsedMillis < 0) return "0ms"
    if (elapsedMillis < 1_000) return "${maxOf(1L, elapsedMillis)}ms"
    if (elapsedMillis < 10_000) {
        val tenths = Math.round(elapsedMillis / 100.0)
        return if (tenths % 10 == 0L) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
    }
    // Choose the unit after rounding so 59.5 seconds never renders as "60s".
    val total = Math.round(elapsedMillis / 1_000.0)
    if (total < 60) return "${total}s"
    return listOf(total / 3_600 to "h", total % 3_600 / 60 to "m", total % 60 to "s").filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
}

/** "Provider · Model · Effort" with catalog labels; null when nothing is known. */
internal fun subagentModelLine(subagent: Subagent, providers: List<ProviderInstance>, models: List<ModelOption>): String? {
    val provider = listOfNotNull(subagent.providerInstanceId, subagent.selectionInstanceId).firstNotNullOfOrNull { id -> providers.firstOrNull { it.instanceId == id } }
    val selectionModel = subagent.selectionModel?.trim()?.takeIf { it.isNotEmpty() }
    val slug = subagent.model?.trim()?.takeIf { it.isNotEmpty() } ?: selectionModel
    val instanceIds = listOfNotNull(subagent.selectionInstanceId, subagent.providerInstanceId)
    val modelLabel = slug?.let { s -> models.firstOrNull { it.instanceId in instanceIds && it.model == s }?.label?.takeIf { it.isNotBlank() } ?: s }
    // The selection's effort only describes the reported model when both name the same one.
    val effort = if (slug != null && selectionModel == slug) {
        REASONING_EFFORT_OPTION_IDS.firstNotNullOfOrNull { subagent.selectionOptions[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }
            ?.replaceFirstChar { it.uppercase() }
    } else null
    return listOfNotNull(provider?.let(::providerDisplayName), modelLabel, effort).filter { it.isNotEmpty() }.joinToString(" \u00B7 ").ifEmpty { null }
}

/** "k/n agents done" for a workflow coordinator; null for plain subagents. */
internal fun subagentWorkflowSummary(subagent: Subagent): String? {
    val agents = subagent.workflow?.agents ?: return null
    return "${agents.count { it.state == "completed" }}/${agents.size} agents done"
}

/**
 * The local child thread a row opens, or null when the row is not tappable. Remote tasks
 * (`remoteChild` without `childThreadId`) are never openable in v1.
 */
internal fun subagentOpenThreadId(subagent: Subagent): String? = subagent.childThreadId?.takeIf { it.isNotBlank() }
