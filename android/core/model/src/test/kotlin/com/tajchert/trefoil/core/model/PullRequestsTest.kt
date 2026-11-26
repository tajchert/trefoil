package com.tajchert.trefoil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestsTest {
    private val detail = PrDetail(
        title = "Add a feature", url = "https://example.test/acme/widgets/pull/7", state = PrState.Open, isDraft = false,
        headRef = "feature", baseRef = "main", headSha = "abc123",
        actions = setOf(PrAction.Merge, PrAction.UpdateBranch, PrAction.Ready, PrAction.Draft),
        mergeMethods = listOf(PrMergeMethod.Squash), canComment = true,
    )
    private val live = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true, canWriteSourceControl = true, pullRequestsSupported = true,
    )

    @Test fun `open ready PR allows merge, update and draft but not ready`() {
        assertTrue(detail.canRun(PrAction.Merge))
        assertTrue(detail.canRun(PrAction.UpdateBranch))
        assertTrue(detail.canRun(PrAction.Draft))
        assertFalse(detail.canRun(PrAction.Ready))
        assertTrue(detail.copy(isDraft = true).canRun(PrAction.Ready))
        assertFalse(detail.copy(isDraft = true).canRun(PrAction.Draft))
    }

    @Test fun `actions need capability, open state and a merge method`() {
        assertFalse(detail.copy(actions = setOf(PrAction.UpdateBranch)).canRun(PrAction.Merge))
        assertFalse(detail.copy(mergeMethods = emptyList()).canRun(PrAction.Merge))
        PrAction.entries.forEach { assertFalse(detail.copy(state = PrState.Merged).canRun(it)) }
    }

    @Test fun `panel actions need a live writable session with source control write`() {
        val panel = PrPanelState(detail = detail)
        assertTrue(panel.canRun(PrAction.Merge, live))
        assertTrue(panel.canComment(live))
        assertFalse(panel.actionsEnabled(live.copy(connection = ConnectionStatus.Reconnecting)))
        assertFalse(panel.actionsEnabled(live.copy(canWrite = false)))
        assertFalse(panel.actionsEnabled(live.copy(canWriteSourceControl = false)))
        assertFalse(panel.actionsEnabled(live.copy(pullRequestsSupported = false)))
        assertFalse(PrPanelState().canRun(PrAction.Merge, live))
        assertFalse(PrPanelState(detail = detail.copy(canComment = false)).canComment(live))
    }

    @Test fun `sending or unknown outcome blocks actions, rejected and done do not`() {
        fun panel(outcome: PrActionOutcome) = PrPanelState(detail = detail, pendingAction = PendingPrAction(PrActionKind.Merge, 1L, outcome))
        assertFalse(panel(PrActionOutcome.Sending).actionsEnabled(live))
        assertFalse(panel(PrActionOutcome.Unknown).actionsEnabled(live))
        assertTrue(panel(PrActionOutcome.Rejected("Head changed")).actionsEnabled(live))
        assertTrue(panel(PrActionOutcome.Done).actionsEnabled(live))
    }

    @Test fun `visible checks prefer the checks reply`() {
        val embedded = PrCheck("build", PrCheckStatus.Pending)
        val fresh = PrCheck("build", PrCheckStatus.Success)
        assertEquals(listOf(embedded), PrPanelState(detail = detail.copy(checks = listOf(embedded))).visibleChecks)
        assertEquals(listOf(fresh), PrPanelState(detail = detail.copy(checks = listOf(embedded)), checks = listOf(fresh)).visibleChecks)
        assertEquals(emptyList<PrCheck>(), PrPanelState().visibleChecks)
    }

    @Test fun `wire values map both ways and tolerate unknown values`() {
        assertEquals("fast-forward", PrMergeMethod.FastForward.wire)
        assertEquals(PrMergeMethod.FastForward, PrMergeMethod.fromWire("fast-forward"))
        assertEquals(PrAction.UpdateBranch, PrAction.fromWire("update-branch"))
        assertNull(PrAction.fromWire("revert"))
        assertEquals(PrMergeability.Unknown, PrMergeability.fromWire("later"))
        assertEquals(PrCheckStatus.ActionRequired, PrCheckStatus.fromWire("action-required"))
        assertEquals(PrCheckStatus.Neutral, PrCheckStatus.fromWire("stale"))
        assertEquals(PrUnavailableReason.CliUnauthenticated, PrUnavailableReason.fromWire("cli-unauthenticated"))
        assertEquals(PrUnavailableReason.Other, PrUnavailableReason.fromWire(null))
        assertEquals(PrReviewDecision.ChangesRequested, PrReviewDecision.fromWire("changes-requested"))
        assertEquals(PrActionKind.UpdateBranch, PrActionKind.of(PrAction.UpdateBranch))
    }
}
