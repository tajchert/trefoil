/* Presentation patterns adapted from T3 Code (MIT). See licenses/T3-Code-MIT.txt. */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ToolActivity
import java.time.Instant

internal sealed interface ConversationEntry {
    val key: String
    data class Message(val message: ConversationMessage, val stacked: Boolean = false) : ConversationEntry {
        override val key = "message:${message.id}"
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
            val separate = activity.status in setOf("error", "failed") || activity.kind in setOf("compaction", "handoff", "notification")
            val previousActivity = previous?.activities?.lastOrNull()
            val previousSeparate = previousActivity?.status in setOf("error", "failed") || previousActivity?.kind in setOf("compaction", "handoff", "notification")
            if (previous != null && !separate && !previousSeparate && activity.runId != null && previousActivity?.runId == activity.runId) {
                result[result.lastIndex] = previous.copy(activities = previous.activities + item.activity)
            } else result += ConversationEntry.Activities(listOf(item.activity))
        }
    }
    return result
}

internal fun timelineInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
