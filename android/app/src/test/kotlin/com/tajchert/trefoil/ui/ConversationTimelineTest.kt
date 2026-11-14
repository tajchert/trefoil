package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    @Test fun commandsGroupOnlyAcrossConsecutiveCommandsInSameRun() {
        val entries = conversationTimeline(listOf(ConversationMessage("m", "assistant", "Hi", ordinal = 3)), listOf(
            ToolActivity("a", "Command", kind = "command_execution", status = "failed", runId = "r", ordinal = 0),
            ToolActivity("b", "Command", kind = "command_execution", runId = "r", ordinal = 1),
            ToolActivity("c", "Read", kind = "file_read", runId = "r", ordinal = 2),
            ToolActivity("d", "Command", kind = "command_execution", runId = "r", ordinal = 4)))
        assertEquals(listOf("activity:a", "activity:c", "message:m", "activity:d"), entries.map { it.key })
        assertEquals(2, (entries.first() as ConversationEntry.Activities).activities.size)
    }
    @Test fun emptyAndCompletedCheckpointHaveNoExpandableDetail() {
        assertFalse(ToolActivity("a", "Run command").hasExpandableDetail())
        assertFalse(ToolActivity("b", "Checkpoint", detail = "completed").hasExpandableDetail())
        assertTrue(ToolActivity("c", "Checkpoint", diffStr = "+change").hasExpandableDetail())
    }
    @Test fun filesInCommentaryStayVisibleAfterSuccessfulRunFolds() {
        val entries = conversationTimeline(listOf(
            ConversationMessage("artifact", "assistant", "Path: `/fixture/output.apk`", runId = "r", ordinal = 1),
            ConversationMessage("answer", "assistant", "Ready to test", runId = "r", ordinal = 3)),
            listOf(ToolActivity("test", "Test", status = "completed", runId = "r", ordinal = 2)))
        val folded = foldConversationTimeline(entries, runs = listOf(ConversationRun("r", "completed")))
        assertTrue(folded.any { it is ConversationEntry.Message && it.message.id == "artifact" })
    }
    @Test fun prominentPlansAndNoticesNeverJoinOrdinaryWorkOrDisappearIntoSuccessfulFold() {
        val activities = listOf(
            ToolActivity("command", "Run command", kind = "command_execution", status = "completed", runId = "r", ordinal = 0),
            ToolActivity("plan", "Proposed plan", kind = "proposed_plan", status = "completed", runId = "r", ordinal = 1),
            ToolActivity("notice", "System notice", kind = "system_notice", status = "completed", runId = "r", ordinal = 2))
        val ordered = conversationTimeline(listOf(ConversationMessage("answer", "assistant", "Done", runId = "r", ordinal = 3)), activities)
        val folded = foldConversationTimeline(ordered, runs = listOf(ConversationRun("r", "completed", completedAt = "2026-11-06T10:00:00Z")))
        assertEquals(listOf("run:r:activity:command", "activity:plan", "activity:notice", "message:answer"), folded.map { it.key })
    }
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
    @Test fun successfulWorkFoldsCommentaryAndToolsButLeavesFinalAnswer() {
        val entries = conversationTimeline(listOf(
            ConversationMessage("u", "user", "Help", runId = "r", ordinal = 0),
            ConversationMessage("comment", "assistant", "Checking files", runId = "r", ordinal = 1),
            ConversationMessage("answer", "assistant", "Finished", runId = "r", ordinal = 3),
        ), listOf(ToolActivity("tool", "Read", status = "completed", runId = "r", ordinal = 2)))
        val folded = foldConversationTimeline(entries)
        assertEquals(listOf("message:u", "run:r:message:comment", "message:answer"), folded.map { it.key })
        assertEquals(listOf("message:comment", "activity:tool"), (folded[1] as ConversationEntry.Work).entries.map { it.key })
    }
    @Test fun activeFailedInterruptedAndUnknownWorkNeverFolds() {
        for (status in listOf("running", "failed", "interrupted", "cancelled", "declined", "")) {
            val entries = conversationTimeline(listOf(ConversationMessage("a", "assistant", "Commentary", runId = "r", ordinal = 0), ConversationMessage("b", "assistant", "Answer", runId = "r", ordinal = 2)), listOf(ToolActivity("t", "Tool", status = status, runId = "r", ordinal = 1)))
            assertEquals("status=$status", entries, foldConversationTimeline(entries))
        }
        val completed = conversationTimeline(listOf(ConversationMessage("a", "assistant", "Answer", runId = "r", ordinal = 1)), listOf(ToolActivity("t", "Tool", status = "completed", runId = "r", ordinal = 0)))
        assertEquals(completed, foldConversationTimeline(completed, activeRunId = "r"))
    }
    @Test fun standaloneNoticesKeepTheirPositionOutsideFold() {
        val entries = conversationTimeline(listOf(ConversationMessage("a", "assistant", "Answer", runId = "r", ordinal = 3)), listOf(ToolActivity("t", "Read", status = "completed", runId = "r", ordinal = 0), ToolActivity("n", "Notice", kind = "notification", status = "completed", runId = "r", ordinal = 1), ToolActivity("t2", "Write", status = "completed", runId = "r", ordinal = 2)))
        val folded = foldConversationTimeline(entries)
        assertTrue(folded[0] is ConversationEntry.Work)
        assertEquals("activity:n", folded[1].key)
        assertTrue(folded[2] is ConversationEntry.Work)
        assertEquals("message:a", folded[3].key)
    }
    @Test fun durationMatchesMobileRoundingAndDoesNotInventMissingTimes() {
        assertEquals("1ms", workDuration(0))
        assertEquals("1.5s", workDuration(1500))
        assertEquals("1m", workDuration(59500))
        assertEquals("1h 1m 1s", workDuration(3661000))
        val entries = conversationTimeline(listOf(ConversationMessage("a", "assistant", "Answer", runId = "r", ordinal = 1)), listOf(ToolActivity("t", "Tool", status = "completed", runId = "r", ordinal = 0)))
        assertEquals("Worked", (foldConversationTimeline(entries).first() as ConversationEntry.Work).label)
    }
    @Test fun attemptAndSubagentBoundariesPreventUnrelatedGroups() {
        val entries = conversationTimeline(emptyList(), listOf(
            ToolActivity("a", "Read", runId = "r", runAttemptId = "one", ordinal = 0),
            ToolActivity("b", "Read", runId = "r", runAttemptId = "two", ordinal = 1),
            ToolActivity("c", "Agent", kind = "subagent", runId = "r", runAttemptId = "two", providerTurnId = "turn-1", ordinal = 2),
            ToolActivity("d", "Agent", kind = "subagent", runId = "r", runAttemptId = "two", providerTurnId = "turn-2", ordinal = 3)))
        assertEquals(4, entries.size)
    }
    @Test fun explicitRunOutcomeControlsFoldingAndAuthoritativeDuration() {
        val entries = conversationTimeline(listOf(ConversationMessage("c", "assistant", "Checking", runId = "r", ordinal = 0), ConversationMessage("a", "assistant", "Answer", runId = "r", ordinal = 1)), emptyList())
        for (status in listOf("failed", "interrupted", "cancelled", "waiting", "running"))
            assertEquals(entries, foldConversationTimeline(entries, runs = listOf(ConversationRun("r", status, startedAt = "2026-01-01T12:00:00Z", completedAt = "2026-01-01T12:01:00Z"))))
        val work = foldConversationTimeline(entries, runs = listOf(ConversationRun("r", "completed", startedAt = "2026-01-01T12:00:00Z", completedAt = "2026-01-01T12:01:00Z"))).first() as ConversationEntry.Work
        assertEquals("Worked for 1m", work.label)
    }
    @Test fun successfulExitStatusCannotHideToolFailureOrOngoingChild() {
        val messages = listOf(ConversationMessage("a", "assistant", "Answer", runId = "r", ordinal = 2))
        val run = listOf(ConversationRun("r", "completed", completedAt = "2026-01-01T12:01:00Z"))
        for (tool in listOf(
            ToolActivity("t", "Command", status = "completed", exitCode = 1, runId = "r", ordinal = 0),
            ToolActivity("t", "Command", status = "completed", outputIndicatesFailure = true, runId = "r", ordinal = 0),
            ToolActivity("t", "Child", status = "running", kind = "subagent", runId = "r", ordinal = 0))) {
            val entries = conversationTimeline(messages, listOf(tool))
            assertEquals(entries, foldConversationTimeline(entries, runs = run))
        }
    }
    @Test fun fencedMarkdownKeepsShorterFenceAsCodeAndSupportsTildes() {
        assertEquals(listOf(MarkdownBlock("```\ninside", true, "markdown")), markdownBlocks("````markdown\n```\ninside\n````"))
        assertEquals(listOf(MarkdownBlock("echo test", true, "sh")), markdownBlocks("~~~sh\necho test\n~~~"))
    }
    @Test fun unfinishedCodeFenceRemainsCodeDuringStreaming() {
        val blocks = markdownBlocks("Hello\n\n```kotlin\nval answer = 42")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock("val answer = 42", true, "kotlin"), blocks.last())
    }
}
