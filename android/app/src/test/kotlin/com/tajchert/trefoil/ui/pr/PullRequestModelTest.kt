package com.tajchert.trefoil.ui.pr

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.DiffChange
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.core.model.DiffFile
import com.tajchert.trefoil.core.model.DiffRow
import com.tajchert.trefoil.core.model.PendingPrAction
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrCheck
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrChecksState
import com.tajchert.trefoil.core.model.PrDetail
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PrSnapshot
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.core.model.ThreadPullRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestModelTest {
    private val key = PullRequestKey("p", "github.com", "demo/repo", 7)
    private val live = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true, canWriteSourceControl = true, pullRequestsSupported = true,
    )

    private fun detail(
        state: PrState = PrState.Open, draft: Boolean = false, actions: Set<PrAction> = setOf(PrAction.Merge, PrAction.UpdateBranch, PrAction.Draft),
        methods: List<PrMergeMethod> = listOf(PrMergeMethod.Squash), comment: Boolean = true, checks: List<PrCheck> = emptyList(),
    ) = PrDetail(
        title = "t", url = "https://example.test/pr/7", state = state, isDraft = draft, headRef = "h", baseRef = "main", actions = actions,
        mergeMethods = methods, canComment = comment, checks = checks,
    )

    private fun snapshot(state: PrState = PrState.Open, draft: Boolean = false, checks: PrChecksState? = null) =
        PrSnapshot(state, "title", "head", "main", isDraft = draft, checksState = checks)

    // --- check ordering and roll-up -----------------------------------------------------------------

    @Test fun checksAreOrderedFailingThenPendingThenPassing() {
        val checks = listOf(
            PrCheck("ok", PrCheckStatus.Success), PrCheck("run", PrCheckStatus.Pending), PrCheck("bad", PrCheckStatus.Failure),
            PrCheck("skip", PrCheckStatus.Skipped), PrCheck("need", PrCheckStatus.ActionRequired),
        )
        assertEquals(listOf("bad", "need", "run", "ok", "skip"), orderChecks(checks).map { it.name })
    }

    @Test fun requiredChecksLeadTheirGroupAndPlaceholdersAreDropped() {
        val checks = listOf(
            PrCheck("a", PrCheckStatus.Failure), PrCheck("b", PrCheckStatus.Failure, required = true), PrCheck("?", PrCheckStatus.Success, placeholder = true),
        )
        assertEquals(listOf("b", "a"), orderChecks(checks).map { it.name })
    }

    @Test fun toneComesFromChecksAndFallsBackToTheSnapshot() {
        assertEquals(PrChecksTone.Failing, checksTone(PrChecksState.Passing, listOf(PrCheck("x", PrCheckStatus.Failure))))
        assertEquals(PrChecksTone.Pending, checksTone(null, listOf(PrCheck("x", PrCheckStatus.Success), PrCheck("y", PrCheckStatus.Pending))))
        assertEquals(PrChecksTone.Passing, checksTone(null, listOf(PrCheck("x", PrCheckStatus.Success), PrCheck("y", PrCheckStatus.Skipped))))
        assertEquals(PrChecksTone.Failing, checksTone(PrChecksState.Failing, emptyList()))
        assertEquals(PrChecksTone.None, checksTone(null, null))
    }

    @Test fun aPlaceholderIsNeverCountedAsPassing() {
        assertEquals(PrChecksTone.None, checksTone(null, listOf(PrCheck("?", PrCheckStatus.Success, placeholder = true))))
        assertNull(checksSummary(listOf(PrCheck("?", PrCheckStatus.Success, placeholder = true))))
    }

    @Test fun summaryCountsEachGroup() {
        val checks = listOf(
            PrCheck("a", PrCheckStatus.Failure), PrCheck("b", PrCheckStatus.Cancelled), PrCheck("c", PrCheckStatus.Pending), PrCheck("d", PrCheckStatus.Success),
        )
        assertEquals("2 failing · 1 pending · 1 passing", checksSummary(checks))
        assertNull(checksSummary(emptyList()))
    }

    // --- chip -----------------------------------------------------------------------------------------

    @Test fun chipShowsStateChecksAndNumberFromTheSnapshot() {
        val link = ThreadPullRequest(key, "https://example.test/pr/7", "created", snapshot(draft = true, checks = PrChecksState.Failing))
        val chip = prChipModel(listOf(link))!!
        assertEquals(7, chip.number)
        assertEquals(PrDisplayState.Draft, chip.state)
        assertEquals(PrChecksTone.Failing, chip.checks)
        assertEquals(0, chip.extra)
        assertEquals("Pull request #7, draft, checks failing", chip.description)
    }

    @Test fun chipWithoutASnapshotStillShowsTheNumberAndCountsExtraLinks() {
        val first = ThreadPullRequest(key, "https://example.test/pr/7", "manual")
        val second = ThreadPullRequest(key.copy(number = 8), "https://example.test/pr/8", "manual")
        val chip = prChipModel(listOf(first, second))!!
        assertNull(chip.state)
        assertEquals(PrChecksTone.None, chip.checks)
        assertEquals(1, chip.extra)
        assertEquals("Pull request #7, and 1 more", chip.description)
        assertNull(prChipModel(emptyList()))
    }

    @Test fun displayStateFollowsStateAndDraft() {
        assertEquals(PrDisplayState.Merged, prDisplayState(PrState.Merged, true))
        assertEquals(PrDisplayState.Closed, prDisplayState(PrState.Closed, false))
        assertEquals(PrDisplayState.Open, prDisplayState(PrState.Open, false))
        assertEquals(PrDisplayState.Draft, prDisplayState(PrState.Open, true))
    }

    // --- overview -------------------------------------------------------------------------------------

    @Test fun overviewPrefersDetailAndFallsBackToTheSnapshot() {
        val link = ThreadPullRequest(key, "u", "created", snapshot())
        val snap = prOverview(link, PrPanelState())!!
        assertTrue(snap.fromSnapshot)
        assertEquals("title", snap.title)
        val full = prOverview(link, PrPanelState(detail = detail()))!!
        assertFalse(full.fromSnapshot)
        assertEquals("t", full.title)
        assertNull(prOverview(ThreadPullRequest(key, "u", "created"), PrPanelState()))
    }

    // --- actions --------------------------------------------------------------------------------------

    @Test fun buttonsFollowTheSessionRule() {
        val panel = PrPanelState(detail = detail())
        val model = prActionsModel(panel, live, 7)
        assertTrue(model.merge); assertTrue(model.updateBranch); assertTrue(model.draft); assertFalse(model.ready); assertTrue(model.comment)
        assertFalse(model.showReady)
        assertNull(model.disabledReason)
        assertEquals(listOf(PrMergeMethod.Squash), model.mergeMethods)
    }

    @Test fun aDraftOffersReadyNotConvertToDraft() {
        val panel = PrPanelState(detail = detail(draft = true, actions = setOf(PrAction.Ready, PrAction.UpdateBranch)))
        val model = prActionsModel(panel, live)
        assertTrue(model.showReady); assertTrue(model.ready); assertFalse(model.draft); assertFalse(model.merge)
    }

    @Test fun mergeNeedsAMergeMethod() {
        val panel = PrPanelState(detail = detail(methods = emptyList()))
        assertFalse(prActionsModel(panel, live).merge)
    }

    @Test fun everythingIsOffWhileDisconnectedWithTheReason() {
        val model = prActionsModel(PrPanelState(detail = detail()), live.copy(connection = ConnectionStatus.Reconnecting))
        assertFalse(model.anyEnabled)
        assertTrue(model.disabledReason!!.startsWith("You are offline"))
    }

    @Test fun missingSourceControlWriteDisablesEverythingAndSaysWhy() {
        val model = prActionsModel(PrPanelState(detail = detail()), live.copy(canWriteSourceControl = false))
        assertFalse(model.anyEnabled)
        assertTrue(model.disabledReason!!.contains("source control"))
        assertFalse(prActionsModel(PrPanelState(detail = detail()), live.copy(canWrite = false)).anyEnabled)
        assertFalse(prActionsModel(PrPanelState(detail = detail()), live.copy(pullRequestsSupported = false)).anyEnabled)
    }

    @Test fun aSendingOrUnknownActionBlocksEveryButton() {
        for (outcome in listOf(PrActionOutcome.Sending, PrActionOutcome.Unknown)) {
            val panel = PrPanelState(detail = detail(), pendingAction = PendingPrAction(PrActionKind.Merge, 0L, outcome))
            val model = prActionsModel(panel, live)
            assertFalse(model.anyEnabled)
            assertNotNull(model.disabledReason)
        }
    }

    @Test fun aRejectedActionLeavesTheButtonsAvailable() {
        val panel = PrPanelState(detail = detail(), pendingAction = PendingPrAction(PrActionKind.UpdateBranch, 0L, PrActionOutcome.Rejected("no")))
        assertTrue(prActionsModel(panel, live).merge)
    }

    @Test fun mergedAndClosedPullRequestsOfferNothing() {
        assertEquals("This pull request is already merged.", prActionsModel(PrPanelState(detail = detail(PrState.Merged, comment = false)), live).disabledReason)
        assertEquals("This pull request is closed.", prActionsModel(PrPanelState(detail = detail(PrState.Closed, comment = false)), live).disabledReason)
    }

    @Test fun commentsOnlyWhenTheDetailAllowsThem() {
        assertFalse(prActionsModel(PrPanelState(detail = detail(comment = false)), live).comment)
        assertFalse(prActionsModel(PrPanelState(), live).comment)
    }

    // --- pending text ---------------------------------------------------------------------------------

    @Test fun pendingTextCoversEveryOutcome() {
        assertNull(pendingText(null))
        assertNull(pendingText(PendingPrAction(PrActionKind.Merge, 0L, PrActionOutcome.Done)))
        val sending = pendingText(PendingPrAction(PrActionKind.Merge, 0L, PrActionOutcome.Sending))!!
        assertEquals("Merging…", sending.text); assertTrue(sending.progress); assertFalse(sending.error)
        val unknown = pendingText(PendingPrAction(PrActionKind.Merge, 0L, PrActionOutcome.Unknown))!!
        assertEquals("Outcome unknown – checking…", unknown.text); assertTrue(unknown.progress)
        val comment = pendingText(PendingPrAction(PrActionKind.Comment, 0L, PrActionOutcome.Unknown))!!
        assertEquals("Comment may have posted – check the pull request before sending again.", comment.text); assertFalse(comment.progress)
        val rejected = pendingText(PendingPrAction(PrActionKind.Draft, 0L, PrActionOutcome.Rejected("Branch protected")))!!
        assertEquals("Branch protected", rejected.text); assertTrue(rejected.error); assertFalse(rejected.progress)
        assertEquals("The server rejected this action.", pendingText(PendingPrAction(PrActionKind.Draft, 0L, PrActionOutcome.Rejected(" ")))!!.text)
    }

    // --- comment, merge method, urls, ages ---------------------------------------------------------------

    @Test fun commentLengthRules() {
        assertFalse(isValidComment("")); assertFalse(isValidComment("   "))
        assertTrue(isValidComment("a")); assertTrue(isValidComment("a".repeat(PR_COMMENT_MAX_CHARS)))
        assertFalse(isValidComment("a".repeat(PR_COMMENT_MAX_CHARS + 1)))
    }

    @Test fun mergeMethodDefaultsToSquashWhenOffered() {
        assertEquals(PrMergeMethod.Squash, defaultMergeMethod(listOf(PrMergeMethod.Merge, PrMergeMethod.Squash)))
        assertEquals(PrMergeMethod.Rebase, defaultMergeMethod(listOf(PrMergeMethod.Rebase, PrMergeMethod.Merge)))
        assertNull(defaultMergeMethod(emptyList()))
    }

    @Test fun onlyWebLinksOpen() {
        assertTrue(isOpenableUrl("https://example.test/x")); assertTrue(isOpenableUrl("http://example.test/x"))
        assertFalse(isOpenableUrl("javascript:alert(1)")); assertFalse(isOpenableUrl("intent://x")); assertFalse(isOpenableUrl(null))
    }

    @Test fun lastUpdatedShowsOnlyWhileNotLive() {
        val panel = PrPanelState(lastUpdatedMillis = 1_000_000L)
        assertNull(lastUpdatedLabel(panel, live = true, nowMillis = 1_300_000L))
        assertEquals("Last updated 5 min ago", lastUpdatedLabel(panel, live = false, nowMillis = 1_300_000L))
        assertNull(lastUpdatedLabel(PrPanelState(), live = false, nowMillis = 1_300_000L))
        assertEquals("just now", agoLabel(1_000L, 11_000L))
        assertEquals("2 hours ago", agoLabel(0L, 2 * 3600_000L))
        assertEquals("3 days ago", agoLabel(0L, 3 * 86_400_000L))
    }

    // --- diff pages -------------------------------------------------------------------------------------

    private fun page(path: String): DiffDocument = DiffDocument(
        files = listOf(DiffFile(0, path, change = DiffChange.Modified, additions = 1, deletions = 0, firstRowIndex = 0, endRowIndex = 2)),
        rows = listOf(DiffRow.FileHeader(0, 0), DiffRow.Hunk(1, 0, "@@ -1 +1 @@")),
    )

    @Test fun joiningPagesShiftsFileRowAndIdNumbering() {
        val joined = appendDiffPage(page("a"), page("b"))
        assertEquals(2, joined.files.size)
        val second = joined.files[1]
        assertEquals(1, second.index); assertEquals(2, second.firstRowIndex); assertEquals(4, second.endRowIndex)
        assertEquals(4, joined.rows.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), joined.rows.map { it.id })
        assertEquals(listOf(0, 0, 1, 1), joined.rows.map { it.fileIndex })
    }

    @Test fun joiningFlagsAreSticky() {
        val joined = appendDiffPage(page("a").copy(truncated = true), page("b"))
        assertTrue(joined.truncated)
        assertEquals(page("x"), appendDiffPage(null, page("x")))
        assertEquals(page("x"), appendDiffPage(DiffDocument.Empty, page("x")))
    }

    // --- unavailable ------------------------------------------------------------------------------------

    @Test fun everyUnavailableReasonHasFriendlyText() {
        for (reason in com.tajchert.trefoil.core.model.PrUnavailableReason.entries) {
            val (title, body) = unavailableMessage(reason)
            assertTrue(title.isNotBlank()); assertTrue(body.isNotBlank())
        }
    }
}
