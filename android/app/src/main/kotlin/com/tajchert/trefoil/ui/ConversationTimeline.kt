/* Presentation patterns adapted from T3 Code (MIT). See licenses/T3-Code-MIT.txt. */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.remoteFiles
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ToolActivity
import java.time.Instant
import java.time.Duration
import kotlin.math.roundToLong

internal sealed interface ConversationEntry {
    val key: String
    data class Message(val message: ConversationMessage, val stacked: Boolean = false) : ConversationEntry {
        override val key = "message:${message.id}"
    }
    /**
     * A folded run. The first fold of a run is keyed `run:<runId>`, the same key the live "Working for" header uses
     * ([workingHeaderKey]), so a settling turn swaps rows in place instead of jumping. Later parts of a run that a visible
     * entry split apart carry their first entry's key.
     */
    data class Work(val runId: String, val entries: List<ConversationEntry>, val label: String, val part: Int = 0) : ConversationEntry {
        override val key = if (part == 0) "run:$runId" else "run:$runId:${entries.first().key}"
    }
    data class Activities(val activities: List<ToolActivity>) : ConversationEntry {
        override val key = "activity:${activities.first().id}"
    }
    /** An agent HTML render: always visible at its ordinal position, never part of an activity stack or collapsed work. */
    data class HtmlRender(val activity: ToolActivity) : ConversationEntry {
        override val key = "html-render:${activity.id}"
    }
}

/** Authoritative turn-item ordinals win; timestamps are only used when that order is unavailable. */
internal fun conversationTimeline(messages: List<ConversationMessage>, activities: List<ToolActivity>): List<ConversationEntry> {
    data class Item(val message: ConversationMessage? = null, val activity: ToolActivity? = null) {
        val ordinal = message?.ordinal ?: activity?.ordinal
        // Parsed once: the sorts and the legacy insertion below compare it many times.
        val time = timelineInstant(message?.createdAt ?: activity?.startedAt)
    }
    // T3 `deriveTimelineEntriesFromVisibleTurnItems` never shows checkpoint items in the timeline.
    val all = messages.map { Item(message = it) } + activities.filter { it.kind != "checkpoint" }.map { Item(activity = it) }
    val ordered = if (all.any { it.ordinal != null }) {
        val known = all.filter { it.ordinal != null }.sortedBy { it.ordinal }.toMutableList()
        // A legacy/unmatched row must not discard the authoritative order of other rows.
        all.filter { it.ordinal == null }.forEach { item ->
            val nextByTime = item.time?.let { time -> known.indexOfFirst { it.time != null && it.time!! > time }.takeIf { it >= 0 } }
            val messageIndex = item.message?.let { messages.indexOf(it) }
            val nextMessage = messageIndex?.let { index -> messages.drop(index + 1).firstNotNullOfOrNull { message -> known.indexOfFirst { it.message?.id == message.id }.takeIf { it >= 0 } } }
            val previousMessage = messageIndex?.let { index -> messages.take(index).asReversed().firstNotNullOfOrNull { message -> known.indexOfFirst { it.message?.id == message.id }.takeIf { it >= 0 } } }
            val index = nextByTime ?: nextMessage ?: previousMessage?.plus(1) ?: known.size
            known.add(index, item)
        }
        known
    } else if (all.all { it.time != null }) all.sortedBy { it.time }
    else all // Legacy snapshots retain server message order; don't fabricate timestamps.
    val result = mutableListOf<ConversationEntry>()
    for (item in ordered) {
        if (item.message != null) {
            val previous = (result.lastOrNull() as? ConversationEntry.Message)?.message
            result += ConversationEntry.Message(item.message, previous?.role == "user" && item.message.role == "user")
        } else if (item.activity?.htmlRender != null) {
            result += ConversationEntry.HtmlRender(item.activity)
        } else if (item.activity != null) {
            // T3 groups consecutive work of one run attempt regardless of kind (reasoning, commands, edits, tools together).
            // Messages, HTML renders, standalone kinds (incl. `error`) and subagent turn boundaries break a group.
            val previous = result.lastOrNull() as? ConversationEntry.Activities
            val activity = item.activity
            val previousActivity = previous?.activities?.lastOrNull()
            if (previous != null && previousActivity != null && !activity.standalone && !previousActivity.standalone &&
                activity.runId != null && previousActivity.runId == activity.runId && previousActivity.runAttemptId == activity.runAttemptId &&
                (previousActivity.kind == "subagent") == (activity.kind == "subagent") &&
                (activity.kind != "subagent" || previousActivity.providerTurnId == activity.providerTurnId)) {
                result[result.lastIndex] = previous.copy(activities = previous.activities + activity)
            } else result += ConversationEntry.Activities(listOf(activity))
        }
    }
    return result
}

internal fun timelineInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** Legacy attention flag (includes interruptions); grouping and folding no longer use it. Prefer [displayFailure]. */
internal val ToolActivity.needsAttention: Boolean get() =
    status in setOf("error", "failed", "failure", "interrupted", "cancelled", "canceled", "stopped", "declined") ||
        (exitCode != null && exitCode != 0) || outputIndicatesFailure == true || !failure.isNullOrBlank()
internal val ToolActivity.isRunning: Boolean get() = status in setOf("running", "in_progress", "inProgress", "started", "pending", "waiting", "starting", "preparing")
private val successStatuses = setOf("completed", "succeeded", "success", "done")
internal val ToolActivity.standalone: Boolean get() = kind in setOf("compaction", "handoff", "notification", "user_input_request", "approval_request", "run_interrupt_result", "error", "proposed_plan", "system_notice", "fork", "thread_created", "secret_request", "link_request")
private val ConversationEntry.runId: String? get() = when (this) {
    is ConversationEntry.Message -> message.runId
    is ConversationEntry.Activities -> activities.first().runId
    is ConversationEntry.HtmlRender -> activity.runId
    is ConversationEntry.Work -> runId
}

private val interruptKinds = setOf("run_interrupt_request", "run_interrupt_result")
private val failedToolStatuses = setOf("error", "failed", "failure", "declined")

/**
 * Fold settled successful responses only (T3 `deriveTurnFolds`); unknown, failed or interrupted runs remain inspectable.
 * Individual tool failures (non-zero exit, failed preview) do not block folding.
 */
internal fun foldConversationTimeline(
    entries: List<ConversationEntry>,
    activeRunId: String? = null,
    runs: List<ConversationRun> = emptyList(),
): List<ConversationEntry> {
    val runById = runs.associateBy { it.id }
    val finalByRun = entries.filterIsInstance<ConversationEntry.Message>()
        .filter { it.message.role == "assistant" && it.message.runId != null }.associateBy { it.message.runId }
    val foldable = entries.filter { it.runId != null }.groupBy { it.runId!! }.filter { (runId, runEntries) ->
        val run = runById[runId]
        val work = runEntries.filterIsInstance<ConversationEntry.Activities>().flatMap { it.activities }
        val final = finalByRun[runId]
        runId != activeRunId && final != null &&
            runEntries.filterIsInstance<ConversationEntry.Message>().none { it.message.streaming } &&
            work.none { it.isRunning || it.kind in interruptKinds } &&
            // Legacy snapshots lack runs. Settled tool work plus a terminal reply is evidence of success, unless a top-level
            // provider error failed the response.
            (if (run != null) run.status in successStatuses && run.completedAt != null
             else work.isNotEmpty() && work.all { it.status in successStatuses || it.status in failedToolStatuses } &&
                work.none { it.kind == "error" && it.parentItemId == null })
    }.keys
    fun hidden(entry: ConversationEntry): Boolean = entry.runId in foldable && when (entry) {
        is ConversationEntry.Message -> entry.message.role == "assistant" && entry.message.remoteFiles().isEmpty() && entry.key != finalByRun[entry.runId]?.key
        is ConversationEntry.Activities -> entry.activities.none { it.standalone }
        is ConversationEntry.HtmlRender, is ConversationEntry.Work -> false
    }
    fun firstStart(items: List<ConversationEntry>): Instant? = items.firstNotNullOfOrNull {
        when (it) {
            is ConversationEntry.Message -> timelineInstant(it.message.createdAt)
            is ConversationEntry.Activities -> it.activities.firstNotNullOfOrNull { activity -> timelineInstant(activity.startedAt) }
            is ConversationEntry.HtmlRender -> timelineInstant(it.activity.startedAt)
            is ConversationEntry.Work -> null
        }
    }
    fun lastEnd(items: List<ConversationEntry>): Instant? = items.flatMap {
        when (it) {
            is ConversationEntry.Message -> listOfNotNull(timelineInstant(it.message.updatedAt ?: it.message.createdAt))
            is ConversationEntry.Activities -> it.activities.mapNotNull { activity -> timelineInstant(activity.completedAt ?: activity.startedAt) }
            is ConversationEntry.HtmlRender -> listOfNotNull(timelineInstant(it.activity.completedAt ?: it.activity.startedAt))
            is ConversationEntry.Work -> emptyList()
        }
    }.maxOrNull()
    fun workLabel(start: Instant?, end: Instant?): String =
        if (start != null && end != null) "Worked for ${workDuration(Duration.between(start, end).toMillis().coerceAtLeast(0))}" else "Worked"
    // T3 `deriveTurnFolds` timing. A response starts at a non-steer user message or a notification; the run group created
    // next takes that prompt's time as its start boundary. Group entries are assistant messages and work, never renders.
    fun startsResponse(entry: ConversationEntry) =
        (entry is ConversationEntry.Message && entry.message.role == "user" && entry.message.inputIntent !in steerIntents) ||
            (entry is ConversationEntry.Activities && entry.activities.first().kind == "notification")
    fun isSteer(entry: ConversationEntry) =
        entry is ConversationEntry.Message && entry.message.role == "user" && entry.message.inputIntent in steerIntents
    fun groupRunId(entry: ConversationEntry): String? = when (entry) {
        is ConversationEntry.Message -> entry.message.runId.takeIf { entry.message.role == "assistant" }
        is ConversationEntry.Activities -> entry.activities.first().runId.takeIf { entry.activities.first().kind != "system_notice" }
        is ConversationEntry.HtmlRender, is ConversationEntry.Work -> null
    }
    // T3 `timelineEntryEnd`: a message ends at its last update, a work entry at its creation time (`startedAt ?? updatedAt`;
    // completedAt only for snapshots without either).
    fun ToolActivity.createdAt(): Instant? = timelineInstant(startedAt ?: updatedAt ?: completedAt)
    fun entryStart(entry: ConversationEntry): Instant? = when (entry) {
        is ConversationEntry.Message -> timelineInstant(entry.message.createdAt)
        is ConversationEntry.Activities -> entry.activities.first().createdAt()
        else -> null
    }
    fun entryEnd(entry: ConversationEntry): Instant? = when (entry) {
        is ConversationEntry.Message -> timelineInstant(entry.message.updatedAt ?: entry.message.createdAt)
        is ConversationEntry.Activities -> entry.activities.last().createdAt()
        else -> null
    }
    val startBoundary = mutableMapOf<String, Instant?>()
    val groupEntries = mutableMapOf<String, MutableList<ConversationEntry>>()
    var pendingBoundary: Instant? = null
    for (entry in entries) {
        if (startsResponse(entry)) {
            pendingBoundary = entryStart(entry)
            continue
        }
        if (isSteer(entry)) continue
        val runId = groupRunId(entry) ?: continue
        if (runId !in groupEntries) {
            startBoundary[runId] = pendingBoundary
            pendingBoundary = null
        }
        groupEntries.getOrPut(runId) { mutableListOf() } += entry
    }
    // T3 passes the thread's activity run (`deriveThreadActivityRun`) as `latestRun`: only that run is timed by its own start
    // and completion, every other turn from its prompt to the later of its final reply and its last entry.
    // Live run first, then T3 `presentedLatestRun`: a usage-limited run presented as latest, else `latestUnheldRun`.
    fun newest(candidates: List<ConversationRun>) =
        candidates.withIndex().maxWithOrNull(compareBy({ it.value.ordinal ?: Int.MIN_VALUE }, { it.index }))?.value
    val latestRun = newest(runs.filter { it.status in liveRunStatuses })
        ?: runs.firstOrNull { it.presentedAsLatest }
        ?: newest(runs.filterNot { (it.status == "queued" && it.queueHeld) || outlivedBeforeStart(it, runs) })
    fun label(runId: String): String {
        val run = runById[runId]
        if (run != null && run.id == latestRun?.id) {
            val start = timelineInstant(run.startedAt)
            val end = timelineInstant(run.completedAt)
            if (start != null && end != null) return workLabel(start, end)
        }
        val group = groupEntries[runId].orEmpty()
        val start = startBoundary[runId] ?: group.firstOrNull()?.let(::entryStart)
        val end = listOfNotNull(finalByRun[runId]?.let(::entryEnd), group.lastOrNull()?.let(::entryEnd)).maxOrNull()
        return workLabel(start, end)
    }

    // T3 folds a turn into one row that also hides settled work after the final reply, per entry: failed trailing items stay
    // visible. Standalone kinds keep their own rows, as in T3, where they are not fold-group work (plans, handoffs, resource
    // cards, notices, notifications) or always failures (errors); approval and input requests never become activities here.
    val terminalIndex = finalByRun.mapValues { (_, final) -> entries.indexOf(final) }
    fun trailing(index: Int, entry: ConversationEntry) = index > (terminalIndex[entry.runId] ?: Int.MAX_VALUE)
    val result = mutableListOf<ConversationEntry>()
    var pending = mutableListOf<ConversationEntry>()
    val parts = mutableMapOf<String, Int>()
    val trailingByRun = linkedMapOf<String, MutableList<ConversationEntry>>()
    fun flush() {
        if (pending.isEmpty()) return
        val runId = pending.first().runId!!
        val part = parts[runId] ?: 0
        parts[runId] = part + 1
        result += ConversationEntry.Work(runId, pending.toList(), label(runId), part)
        pending = mutableListOf()
    }
    for ((index, entry) in entries.withIndex()) {
        if (hidden(entry)) {
            if (trailing(index, entry)) {
                flush()
                val (failed, settled) = (entry as? ConversationEntry.Activities)?.activities.orEmpty().partition { it.displayFailure }
                if (entry !is ConversationEntry.Activities) trailingByRun.getOrPut(entry.runId!!) { mutableListOf() } += entry
                if (settled.isNotEmpty()) trailingByRun.getOrPut(entry.runId!!) { mutableListOf() } += ConversationEntry.Activities(settled)
                if (failed.isNotEmpty()) result += ConversationEntry.Activities(failed)
                continue
            }
            if (pending.isNotEmpty() && pending.first().runId != entry.runId) flush()
            pending += entry
        } else {
            flush()
            result += entry
        }
    }
    flush()
    for ((runId, trailingEntries) in trailingByRun) {
        // The last fold of the turn sits right before its reply, so trailing work extends it and split rows stay apart.
        val last = result.indexOfLast { it is ConversationEntry.Work && it.runId == runId }
        if (last >= 0) {
            val work = result[last] as ConversationEntry.Work
            result[last] = work.copy(entries = work.entries + trailingEntries)
        } else {
            // No earlier fold: T3 anchors the row at the turn's first entry after its prompt.
            val anchor = result.indexOfFirst { it.runId == runId && !(it is ConversationEntry.Message && it.message.role == "user") }
            val work = ConversationEntry.Work(runId, trailingEntries, label(runId))
            if (anchor >= 0) result.add(anchor, work) else result += work
        }
    }
    // A run split by a render or standalone entry labels each row from its own entries.
    val splitRuns = result.filterIsInstance<ConversationEntry.Work>().groupingBy { it.runId }.eachCount().filterValues { it > 1 }.keys
    if (splitRuns.isEmpty()) return result
    return result.map { entry ->
        if (entry is ConversationEntry.Work && entry.runId in splitRuns) {
            // lastEnd falls back to a start time, so a row with no real end would read "Worked for 1ms": show no duration instead.
            val start = firstStart(entry.entries)
            val end = lastEnd(entry.entries)
            entry.copy(label = if (start != null && end != null && end > start) workLabel(start, end) else "Worked")
        } else entry
    }
}

private val liveRunStatuses = setOf("preparing", "starting", "running", "waiting")
private val steerIntents = setOf("steer", "promoted_queued_to_steer")

/** T3 `latestUnheldRun` skips a run that a steer cancelled before it started. */
private fun outlivedBeforeStart(run: ConversationRun, runs: List<ConversationRun>): Boolean {
    if (run.status != "cancelled" || !run.startedAt.isNullOrBlank()) return false
    val cancelledAt = timelineInstant(run.completedAt) ?: return false
    return runs.any { earlier ->
        (earlier.ordinal ?: 0) < (run.ordinal ?: 0) && earlier.status != "queued" &&
            (timelineInstant(earlier.completedAt)?.let { it >= cancelledAt } ?: true)
    }
}

/** T3 shared/orchestrationTiming `formatDuration`, including its rounding at unit boundaries ("1m 0s"). */
internal fun workDuration(milliseconds: Long): String {
    if (milliseconds < 0) return "0ms"
    val ms = milliseconds
    if (ms < 1000) return "${ms.coerceAtLeast(1)}ms"
    if (ms < 10000) return "${(ms / 100.0).roundToLong() / 10.0}".removeSuffix(".0") + "s"
    val total = (ms / 1000.0).roundToLong()
    if (total < 60) return "${total}s"
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0 || minutes > 0) add("${seconds}s")
    }.joinToString(" ")
}
