package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    @Test fun toolWorkStaysBetweenUserPromptAndAssistantAnswer() {
        val entries = conversationTimeline(listOf(ConversationMessage("u", "user", "Help", ordinal = 0), ConversationMessage("a", "assistant", "Done", ordinal = 3)), listOf(ToolActivity("t1", "Read", runId = "r", ordinal = 1), ToolActivity("t2", "Edit", runId = "r", ordinal = 2)))
        assertEquals(listOf("message:u", "activity:t1", "message:a"), entries.map { it.key })
        assertEquals(2, (entries[1] as ConversationEntry.Activities).activities.size)
    }
    @Test fun unmatchedMessageDoesNotInvalidateKnownTimelineOrder() {
        val entries = conversationTimeline(listOf(ConversationMessage("u", "user", "Help", ordinal = 0), ConversationMessage("extra", "assistant", "Extra", createdAt = "2026-01-01T12:00:02Z"), ConversationMessage("a", "assistant", "Done", createdAt = "2026-01-01T12:00:03Z", ordinal = 3)), listOf(ToolActivity("t", "Read", startedAt = "2026-01-01T12:00:01Z", ordinal = 1)))
        assertEquals(listOf("message:u", "activity:t", "message:extra", "message:a"), entries.map { it.key })
    }
    @Test fun failuresAndUnknownRunsStaySeparate() {
        val entries = conversationTimeline(emptyList(), listOf(ToolActivity("t1", "Read", ordinal = 0), ToolActivity("t2", "Edit", ordinal = 1), ToolActivity("t3", "Fail", status = "failed", runId = "r", ordinal = 2), ToolActivity("t4", "Edit", runId = "r", ordinal = 3)))
        assertEquals(4, entries.size)
    }
    @Test fun timestampFallbackInterleavesActivitiesWithoutInventingOrder() {
        val entries = conversationTimeline(listOf(ConversationMessage("u", "user", "Help", createdAt = "2026-01-01T12:00:00Z"), ConversationMessage("a", "assistant", "Done", createdAt = "2026-01-01T12:00:03Z")), listOf(ToolActivity("t", "Read", startedAt = "2026-01-01T12:00:01Z")))
        assertEquals(listOf("message:u", "activity:t", "message:a"), entries.map { it.key })
    }
    @Test fun differentRunsDoNotCollapseTogether() {
        val entries = conversationTimeline(emptyList(), listOf(ToolActivity("t1", "Read", runId = "r1", ordinal = 0), ToolActivity("t2", "Edit", runId = "r2", ordinal = 1)))
        assertEquals(2, entries.size)
    }
    @Test fun legacyMessagesKeepServerOrderAndOnlyConsecutiveUsersStack() {
        val entries = conversationTimeline(listOf(ConversationMessage("u1", "user", "One"), ConversationMessage("u2", "user", "Two"), ConversationMessage("a", "assistant", "Three"), ConversationMessage("u3", "user", "Four")), emptyList()).filterIsInstance<ConversationEntry.Message>()
        assertEquals(listOf(false, true, false, false), entries.map { it.stacked })
    }
    @Test fun unfinishedCodeFenceRemainsCodeDuringStreaming() {
        val blocks = markdownBlocks("Hello\n\n```kotlin\nval answer = 42")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock("val answer = 42", true, "kotlin"), blocks.last())
    }
}
