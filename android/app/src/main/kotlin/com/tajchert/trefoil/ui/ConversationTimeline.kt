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
    data class Work(val runId: String, val entries: List<ConversationEntry>, val label: String) : ConversationEntry {
        override val key = "run:$runId:${entries.first().key}"
    }
    data class Activities(val activities: List<ToolActivity>) : ConversationEntry {
        override val key = "activity:${activities.first().id}"
    }
}

/** Authoritative turn-item ordinals win; timestamps are only used when that order is unavailable. */
internal fun conversationTimeline(messages: List<ConversationMessage>, activities: List<ToolActivity>): List<ConversationEntry> {
    data class Item(val message: ConversationMessage? = null, val activity: ToolActivity? = null) {
        val ordinal get() = message?.ordinal ?: activity?.ordinal
        val time get() = timelineInstant(message?.createdAt ?: activity?.startedAt)
    }
    val all = messages.map { Item(message = it) } + activities.map { Item(activity = it) }
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
        } else if (item.activity != null) {
            val previous = result.lastOrNull() as? ConversationEntry.Activities
            val activity = item.activity
            val separate = (activity.needsAttention && activity.kind != "command_execution") || activity.standalone
            val previousActivity = previous?.activities?.lastOrNull()
            val previousSeparate = previousActivity?.let { (it.needsAttention && it.kind != "command_execution") || it.standalone } == true
            if (previous != null && !separate && !previousSeparate && activity.runId != null && previousActivity?.runId == activity.runId && previousActivity?.runAttemptId == activity.runAttemptId && (previousActivity?.kind == "command_execution") == (activity.kind == "command_execution") && (previousActivity?.kind == "subagent") == (activity.kind == "subagent") && (activity.kind != "subagent" || previousActivity?.providerTurnId == activity.providerTurnId)) {
                result[result.lastIndex] = previous.copy(activities = previous.activities + item.activity)
            } else result += ConversationEntry.Activities(listOf(item.activity))
        }
    }
    return result
}

internal fun timelineInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal val ToolActivity.needsAttention: Boolean get() =
    status in setOf("error", "failed", "failure", "interrupted", "cancelled", "canceled", "stopped", "declined") ||
        (exitCode != null && exitCode != 0) || outputIndicatesFailure == true || !failure.isNullOrBlank()
internal val ToolActivity.isRunning: Boolean get() = status in setOf("running", "in_progress", "inProgress", "started", "pending", "waiting", "starting", "preparing")
private val successStatuses = setOf("completed", "succeeded", "success", "done")
private val ToolActivity.standalone: Boolean get() = kind in setOf("compaction", "handoff", "notification", "user_input_request", "approval_request", "run_interrupt_result", "error", "proposed_plan", "system_notice", "fork", "thread_created", "secret_request", "link_request")
private val ConversationEntry.runId: String? get() = when (this) {
    is ConversationEntry.Message -> message.runId
    is ConversationEntry.Activities -> activities.first().runId
    is ConversationEntry.Work -> runId
}

/** Fold settled successful responses only; unknown or interrupted outcomes remain inspectable. */
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
            work.none { it.needsAttention || it.isRunning } &&
            // Legacy snapshots lack runs. Completed tool work plus a terminal reply is evidence of success.
            (if (run != null) run.status in successStatuses && run.completedAt != null
             else work.isNotEmpty() && work.all { it.status in successStatuses })
    }.keys
    fun hidden(entry: ConversationEntry): Boolean = entry.runId in foldable && when (entry) {
        is ConversationEntry.Message -> entry.message.role == "assistant" && entry.message.remoteFiles().isEmpty() && entry.key != finalByRun[entry.runId]?.key
        is ConversationEntry.Activities -> entry.activities.none { it.standalone }
        is ConversationEntry.Work -> false
    }
    fun label(runId: String): String {
        val run = runById[runId]
        val runEntries = entries.filter { it.runId == runId }
        val final = finalByRun[runId]?.message
        val prompt = entries.filterIsInstance<ConversationEntry.Message>().lastOrNull { it.message.role == "user" && it.message.runId == runId }?.message
        val start = timelineInstant(run?.startedAt) ?: timelineInstant(prompt?.createdAt) ?: runEntries.firstNotNullOfOrNull {
            when (it) {
                is ConversationEntry.Message -> timelineInstant(it.message.createdAt)
                is ConversationEntry.Activities -> it.activities.firstNotNullOfOrNull { activity -> timelineInstant(activity.startedAt) }
                is ConversationEntry.Work -> null
            }
        }
        val ends = runEntries.flatMap {
            when (it) {
                is ConversationEntry.Message -> listOfNotNull(timelineInstant(it.message.updatedAt ?: it.message.createdAt))
                is ConversationEntry.Activities -> it.activities.mapNotNull { activity -> timelineInstant(activity.completedAt ?: activity.startedAt) }
                is ConversationEntry.Work -> emptyList()
            }
        }
        val end = timelineInstant(run?.completedAt) ?: ends.maxOrNull() ?: timelineInstant(final?.updatedAt)
        return if (start != null && end != null) "Worked for ${workDuration(Duration.between(start, end).toMillis().coerceAtLeast(0))}" else "Worked"
    }
    val result = mutableListOf<ConversationEntry>()
    var pending = mutableListOf<ConversationEntry>()
    fun flush() {
        if (pending.isEmpty()) return
        val runId = pending.first().runId!!
        result += ConversationEntry.Work(runId, pending.toList(), label(runId))
        pending = mutableListOf()
    }
    for (entry in entries) {
        if (hidden(entry)) {
            if (pending.isNotEmpty() && pending.first().runId != entry.runId) flush()
            pending += entry
        } else {
            flush()
            result += entry
        }
    }
    flush()
    return result
}

/** Mirrors T3 shared/orchestrationTiming's rounding at unit boundaries. */
internal fun workDuration(milliseconds: Long): String {
    val ms = milliseconds.coerceAtLeast(0)
    if (ms < 1000) return "${ms.coerceAtLeast(1)}ms"
    if (ms < 10000) return "${(ms / 100.0).roundToLong() / 10.0}".removeSuffix(".0") + "s"
    val total = (ms / 1000.0).roundToLong()
    if (total < 60) return "${total}s"
    return listOf(total / 3600 to "h", (total % 3600) / 60 to "m", total % 60 to "s")
        .filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
}
