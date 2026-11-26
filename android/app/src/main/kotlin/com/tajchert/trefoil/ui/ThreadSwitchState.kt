package com.tajchert.trefoil.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.ToolActivity

/**
 * The folded timeline of each recently shown thread, one entry per thread, reused while its inputs are the same instances.
 * The session keeps a revisited thread's rows as the same instances, so switching back reuses its fold instead of
 * rebuilding it on the main thread; a streaming delta only replaces its own thread's entry. The budget mirrors the
 * session's thread cache (`ThreadProjectionCache`: [maxThreads] threads and [maxRows] rows, least recently shown evicted
 * first), so a thread the session no longer keeps is not kept here either. Memory only; the shell provides one per
 * environment ([LocalTimelineFoldMemo]), so nothing outlives an environment switch. Main thread only.
 */
internal class TimelineFoldMemo(private val maxThreads: Int = 12, private val maxRows: Int = 6_000) {
    private class Entry(val messages: List<ConversationMessage>, val activities: List<ToolActivity>, val activeRunId: String?,
                        val runs: List<ConversationRun>, val folded: List<ConversationEntry>) {
        val rows get() = messages.size + activities.size
    }
    private val threads = linkedMapOf<String, Entry>() // least recently shown first

    val threadIds: Set<String> get() = threads.keys

    fun fold(threadId: String?, messages: List<ConversationMessage>, activities: List<ToolActivity>, activeRunId: String?, runs: List<ConversationRun>): List<ConversationEntry> {
        if (threadId == null) return foldConversationTimeline(conversationTimeline(messages, activities), activeRunId, runs)
        val hit = threads.remove(threadId)?.takeIf { it.messages === messages && it.activities === activities && it.runs === runs && it.activeRunId == activeRunId }
        val entry = hit ?: Entry(messages, activities, activeRunId, runs, foldConversationTimeline(conversationTimeline(messages, activities), activeRunId, runs))
        threads[threadId] = entry
        var rows = threads.values.sumOf { it.rows }
        while (threads.size > 1 && (threads.size > maxThreads || rows > maxRows)) {
            val eldest = threads.keys.first()
            rows -= threads.remove(eldest)!!.rows
        }
        return entry.folded
    }
}

internal val LocalTimelineFoldMemo = staticCompositionLocalOf<TimelineFoldMemo?> { null }

/**
 * Saves a conversation's scroll position with its thread. Restored while the conversation follows its end ([followEnd]
 * returns the end index), it starts at the end, since the turn window restarts at the latest turns and the saved index
 * may no longer point there; otherwise the reader's position comes back as it was.
 */
internal fun followingListSaver(followEnd: () -> Int?): Saver<LazyListState, *> = listSaver(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { saved -> followEnd()?.let { LazyListState(it) } ?: LazyListState(saved[0], saved[1]) },
)

/** Lets LazyColumn reuse a row's layout only for a row of the same kind. */
internal fun timelineContentType(entry: ConversationEntry): Int = when (entry) {
    is ConversationEntry.Message -> if (entry.message.role == "user") 0 else 1
    is ConversationEntry.Work -> 2
    is ConversationEntry.Activities -> if (entry.isSubagentGroup) 4 else 3
    is ConversationEntry.HtmlRender -> 5
}

/**
 * Ids of the top-level message rows that show their metadata: a user message ending a run of user messages, and the last
 * settled assistant message of a run that is not the active one.
 */
internal fun messageMetadataIds(entries: List<ConversationEntry>, messages: List<ConversationMessage>, activeRunId: String?): Set<String> {
    val lastAssistantByRun = HashMap<String?, String>()
    messages.forEach { if (it.role == "assistant") lastAssistantByRun[it.runId] = it.id }
    return buildSet {
        entries.forEachIndexed { index, entry ->
            val message = (entry as? ConversationEntry.Message)?.message ?: return@forEachIndexed
            val show = if (message.role == "user") (entries.getOrNull(index + 1) as? ConversationEntry.Message)?.message?.role != "user"
            else !message.streaming && (activeRunId == null || (message.runId != null && message.runId != activeRunId)) &&
                message.id == lastAssistantByRun[message.runId]
            if (show) add(message.id)
        }
    }
}
