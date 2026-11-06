package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ThreadSummary
import org.junit.Assert.*
import org.junit.Test

class ThreadPresentationTest {
    @Test fun shelvesKeepPinnedWorkingSettledAndArchivedDistinct() {
        val rows = listOf(
            ThreadSummary("active", "p", "Active"),
            ThreadSummary("working", "p", "Working", "running"),
            ThreadSummary("settled", "p", "Settled", settledAt = "2026-11-06T09:00:00Z"),
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
    @Test fun invalidAndFutureTimesAreSafe() {
        assertEquals("", relativeThreadTime("invalid", 0))
        assertEquals("now", relativeThreadTime("2026-11-06T09:00:00Z", 0))
    }
}
