package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ThreadSummary
import org.junit.Assert.*
import org.junit.Test

class ThreadPresentationTest {
    @Test fun fullWireShapedRosterMatchesDesktopCountsWithoutListingChildrenOrSideChats() {
        val rows = (0 until 3).map { ThreadSummary("open-$it", "p", "Open $it", relationshipToParent = if (it == 2) "fork" else null, parentThreadId = if (it == 2) "open-0" else null) } +
            (0 until 2).map { ThreadSummary("working-$it", "p", "Work $it", "running", latestRunId = "run-$it") } +
            ThreadSummary("snoozed", "p", "Snoozed", snoozedUntil = "2099-01-01T00:00:00Z") +
            (0 until 25).map { ThreadSummary("settled-$it", "p", "Settled $it", "completed", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z") } +
            (0 until 56).map { ThreadSummary("child-$it", "p", "Child $it", relationshipToParent = "subagent", parentThreadId = "open-0") } +
            ThreadSummary("side-chat", "p", "Hidden chat", sideChat = true, parentThreadId = "open-0") +
            ThreadSummary("deleted", "p", "Deleted", deletedAt = "2026-11-06T09:00:00Z")
        assertEquals(mapOf("Threads" to 3, "Working" to 2, "Snoozed" to 1, "Settled" to 25),
            threadShelves(rows).associate { it.title to it.threads.size })
    }

    @Test fun freshRequestsAndFailuresWakeSnoozeButExistingQuestionsAndCompletionDoNot() {
        val now = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        val snoozed = ThreadSummary("s", "p", "Snoozed", "completed", snoozedAt = "2026-11-07T10:00:00Z", snoozedUntil = "2026-11-08T10:00:00Z")
        fun shelf(thread: ThreadSummary) = threadShelves(listOf(thread), now).single().title
        assertEquals("Snoozed", shelf(snoozed))
        assertEquals("Snoozed", shelf(snoozed.copy(pendingRequestKind = "user_input", pendingRequestCreatedAt = "2026-11-07T09:00:00Z")))
        assertEquals("Threads", shelf(snoozed.copy(pendingRequestKind = "user_input", pendingRequestCreatedAt = "2026-11-07T11:00:00Z")))
        assertEquals("Threads", shelf(snoozed.copy(pendingRequestKind = "approval")))
        assertEquals("Threads", shelf(snoozed.copy(pendingGuestMessageCount = 1)))
        assertEquals("Threads", shelf(snoozed.copy(status = "failed", latestRunId = "run", latestRunCompletedAt = "2026-11-07T11:00:00Z")))
        assertEquals("Snoozed", shelf(snoozed.copy(status = "failed", latestRunId = "run", latestRunCompletedAt = "2026-11-07T09:00:00Z")))
    }

    @Test fun workingMembershipIncludesIdleHeldWorkButLeavesActionablePlansInInbox() {
        val held = ThreadSummary("held", "p", "Held", "completed", completionHeldBackgroundWork = true)
        assertEquals("Working", threadShelves(listOf(held)).single().title)
        assertEquals("Threads", threadShelves(listOf(held.copy(status = "failed"))).single().title)
        assertEquals("Working", threadShelves(listOf(held.copy(status = "idle", completionHeldBackgroundWork = false, latestRunId = "r"))).single().title)
        val plan = held.copy(latestRunId = "r", completionHeldBackgroundWork = false, interactionMode = "plan", hasActionableProposedPlan = true)
        assertEquals("Threads", threadShelves(listOf(plan)).single().title)
    }

    @Test fun childApprovalsWakeParentAndLeaveWorkingWithoutListingChild() {
        val parent = ThreadSummary("parent", "p", "Parent", "running", latestRunId = "r", snoozedUntil = "2099-01-01T00:00:00Z")
        val child = ThreadSummary("child", "p", "Child", "running", relationshipToParent = "subagent", parentThreadId = "parent", pendingRequestKind = "approval")
        assertEquals("Threads", threadShelves(listOf(parent, child)).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(creationSource = "provider"))).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(activityRunStatusPresent = true))).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(pendingRequestKind = "user_input"))).single().title)
    }

    @Test fun metadataUpdatesDoNotReorderInboxAndExplicitNullAuthoredTimeDoesNotUseWakeMessageTime() {
        val rows = listOf(
            ThreadSummary("older", "p", "Older", createdAt = "2026-11-05T09:00:00Z", updatedAt = "2099-01-01T00:00:00Z"),
            ThreadSummary("newer", "p", "Newer", createdAt = "2026-11-06T09:00:00Z"))
        assertEquals(listOf("newer", "older"), threadShelves(rows).single().threads.map { it.id })
        val work = rows.map { it.copy(status = "running", latestRunRequestedAt = if (it.id == "older") "2099-01-01T00:00:00Z" else "2000-01-01T00:00:00Z", latestUserAuthoredMessageAtPresent = true) }
        assertEquals(listOf("newer", "older"), threadShelves(work).single().threads.map { it.id })
        assertEquals(listOf("older", "newer"), threadShelves(work.map { it.copy(latestUserAuthoredMessageAtPresent = false) }).single().threads.map { it.id })
    }
    @Test fun ownApprovalKeepsPriorityOverSideChatQuestion() {
        val parent = ThreadSummary("parent", "p", "Parent", pendingRequestKind = "approval")
        val sideChat = ThreadSummary("chat", "p", "Chat", sideChat = true, parentThreadId = "parent", pendingRequestKind = "user_input")
        val row = threadShelves(listOf(parent, sideChat)).single().threads.single()
        assertEquals("Needs approval", threadIndicator(row).label)
    }
    @Test fun t3SettlementPrecedesPinAndAttentionAndNeverInfersFromTimestamp() {
        val rows = listOf(
            ThreadSummary("settled-pin", "p", "Pinned settled", settledOverride = "settled", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("settled-request", "p", "Request settled", settledOverride = "settled", pendingRequestKind = "approval"),
            ThreadSummary("timestamp-only", "p", "Open", settledAt = "2026-11-06T09:00:00Z"))
        val shelves = threadShelves(rows).associate { it.title to it.threads.map { thread -> thread.id }.toSet() }
        assertEquals(setOf("settled-pin", "settled-request"), shelves["Settled"])
        assertEquals(setOf("timestamp-only"), shelves["Threads"])
        assertNull(shelves["Pinned"])
    }

    @Test fun blockedRunningThreadIsWorkingEvenWhenItsIndicatorSaysWaiting() {
        val thread = ThreadSummary("waiting", "p", "Wait for child", "running", blockedOnThreadWait = true)
        assertEquals("Waiting", threadIndicator(thread).label)
        assertEquals("Working", threadShelves(listOf(thread)).single().title)
    }
    @Test fun shelvesKeepPinnedWorkingSettledAndArchivedDistinct() {
        val rows = listOf(
            ThreadSummary("active", "p", "Active"),
            ThreadSummary("working", "p", "Working", "running"),
            ThreadSummary("settled", "p", "Settled", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("pinned", "p", "Pinned", "running", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("archive", "p", "Archive", archived = true),
        )
        val shelves = threadShelves(rows)
        assertEquals(listOf("Pinned", "Threads", "Working", "Settled", "Archived"), shelves.map { it.title })
        assertEquals(listOf("pinned", "active", "working", "settled", "archive"), shelves.flatMap { it.threads }.map { it.id })
    }
    @Test fun pinnedOrderAndInboxRecencyAreStable() {
        val rows = listOf(
            ThreadSummary("older", "p", "Older", updatedAt = "2026-11-05T09:00:00Z"),
            ThreadSummary("newer", "p", "Newer", updatedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("pinB", "p", "B", pinnedAt = "2026-11-06T09:00:00Z", pinOrderKey = "b"),
            ThreadSummary("pinA", "p", "A", pinnedAt = "2026-11-06T09:00:00Z", pinOrderKey = "a"),
        )
        assertEquals(listOf("pinA", "pinB", "newer", "older"), threadShelves(rows).flatMap { it.threads }.map { it.id })
        assertEquals(threadShelves(rows), threadShelves(rows.reversed()))
    }
    @Test fun attentionOverridesRunningAndSettled() {
        val thread = ThreadSummary("a", "p", "A", "running", settledAt = "2026-11-06T09:00:00Z", pendingRequestKind = "approval")
        assertEquals("Needs approval", threadIndicator(thread).label)
        assertEquals("Threads", threadShelves(listOf(thread)).single().title)
    }
    @Test fun snoozedThreadsReturnWhenTheirSavedTimeExpires() {
        val thread = ThreadSummary("s", "p", "Snoozed", snoozedUntil = "2026-11-06T10:00:00Z")
        val deadline = java.time.Instant.parse(thread.snoozedUntil).toEpochMilli()
        assertEquals("Snoozed", threadShelves(listOf(thread), deadline - 1).single().title)
        assertEquals("Threads", threadShelves(listOf(thread), deadline).single().title)
        assertEquals("Waiting", threadIndicator(thread.copy(status = "running", blockedOnThreadWait = true)).label)
    }
    @Test fun reportedThreadCountsAppearInSeparateShelves() {
        val threads = listOf(ThreadSummary("work", "p", "Working", "running"),
            ThreadSummary("snooze", "p", "Snoozed", snoozedUntil = "2099-01-01T00:00:00Z")) +
            (0 until 24).map { ThreadSummary("settled-$it", "p", "Settled $it", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z") } +
            (0 until 4).map { ThreadSummary("open-$it", "p", "Open $it") }
        assertEquals(mapOf("Threads" to 4, "Working" to 1, "Snoozed" to 1, "Settled" to 24),
            threadShelves(threads).associate { it.title to it.threads.size })
    }
    @Test fun invalidAndFutureTimesAreSafe() {
        assertEquals("", relativeThreadTime("invalid", 0))
        assertEquals("now", relativeThreadTime("2026-11-06T09:00:00Z", 0))
    }
}
