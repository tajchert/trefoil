package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadActionsTest {
    @Test fun commandsUseCapturedTargetAndExactFields() {
        val target = ThreadTarget("env", "row")
        assertEquals("row", threadActionCommand(target, ThreadAction.Unsettle, null, "c").string("threadId"))
        assertEquals("user", threadActionCommand(target, ThreadAction.Wake, null, "c").string("reason"))
        assertEquals("thread.metadata.update", threadActionCommand(target, ThreadAction.Rename, "  New  ", "c").string("type"))
        assertEquals("New", threadActionCommand(target, ThreadAction.Rename, "  New  ", "c").string("title"))
        assertFalse(threadActionCommand(target, ThreadAction.Pin, null, "c").containsKey("orderKey"))
        assertEquals("thread.unpin", threadActionCommand(target, ThreadAction.Unpin, null, "c").string("type"))
        assertEquals("thread.settle", threadActionCommand(target, ThreadAction.Settle, null, "c").string("type"))
        assertEquals("a0", threadActionCommand(target, ThreadAction.Pin, "a0", "c").string("orderKey"))
        assertEquals("2099-01-01T00:00:00Z", threadActionCommand(target, ThreadAction.Snooze, "2099-01-01T00:00:00Z", "c").string("snoozedUntil"))
    }
    @Test fun settlementUndoRestoresPinBeforeSnooze() {
        val row = ThreadSummary("row", "p", "T", pinnedAt = "now", pinOrderKey = "a0", snoozedUntil = "2099-01-01T00:00:00Z")
        assertEquals(listOf(ThreadAction.Unsettle, ThreadAction.Pin, ThreadAction.Snooze), settlementUndoSteps(row).map { it.first })
        assertEquals("a0", settlementUndoSteps(row)[1].second)
    }
    @Test fun snoozeRejectsApprovalsChildrenAndStartingTurns() {
        val row = ThreadSummary("row", "p", "T")
        assertTrue(canSnoozeThread(row))
        assertTrue(canSnoozeThread(row.copy(pendingRequestKind = "user_input")))
        assertFalse(canSnoozeThread(row.copy(pendingRequestKind = "approval")))
        assertFalse(canSnoozeThread(row.copy(pendingChildRequestKind = "user_input")))
        assertFalse(canSnoozeThread(row.copy(status = "starting")))
    }
}
