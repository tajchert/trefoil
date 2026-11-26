package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.PendingPrAction
import com.tajchert.trefoil.core.model.PrActionKind
import com.tajchert.trefoil.core.model.PrActionOutcome
import com.tajchert.trefoil.core.model.PrActor
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrCheck
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrChecksState
import com.tajchert.trefoil.core.model.PrDetail
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrMergeability
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PrReviewDecision
import com.tajchert.trefoil.core.model.PrSnapshot
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PrUnavailableReason
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.core.model.ThreadPullRequest
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.pr.PrActionHandlers
import com.tajchert.trefoil.ui.pr.PrFilesState
import com.tajchert.trefoil.ui.pr.PrPanelEnv
import com.tajchert.trefoil.ui.pr.PrSection
import com.tajchert.trefoil.ui.pr.PullRequestChipContent
import com.tajchert.trefoil.ui.pr.PullRequestPanelContent
import com.tajchert.trefoil.ui.pr.prActionsModel
import com.tajchert.trefoil.ui.pr.prChipModel
import com.tajchert.trefoil.ui.shell.SidePanel
import com.tajchert.trefoil.ui.shell.SidePanelTab

/** Synthetic pull request for the PR panel screenshots (no real repository, people or content). */
private object PrFixtures {
    const val NOW = 1_800_000_000_000L
    val key = PullRequestKey("project-1", "github.com", "demo/greeter", 42)
    private const val URL = "https://github.com/demo/greeter/pull/42"
    private val author = PrActor("octo-demo")

    fun link(state: PrState = PrState.Open, draft: Boolean = false, checks: PrChecksState? = PrChecksState.Passing) = ThreadPullRequest(
        key, URL, "created",
        PrSnapshot(state, "Add a friendlier greeting", "feature/greeting", "main", isDraft = draft, author = author, additions = 38, deletions = 9, changedFiles = 4,
            checksState = checks, reviewDecision = PrReviewDecision.Approved),
    )

    val passing = listOf(
        PrCheck("build / compile", PrCheckStatus.Success, required = true),
        PrCheck("build / unit tests", PrCheckStatus.Success, required = true),
        PrCheck("lint", PrCheckStatus.Success),
        PrCheck("docs preview", PrCheckStatus.Skipped),
    )
    val failing = listOf(
        PrCheck("lint", PrCheckStatus.Success),
        PrCheck("build / unit tests", PrCheckStatus.Failure, description = "2 tests failed", url = "https://ci.example.test/run/1", required = true),
        PrCheck("build / compile", PrCheckStatus.Success, required = true),
        PrCheck("deploy preview", PrCheckStatus.ActionRequired, description = "Waiting for approval"),
        PrCheck("integration", PrCheckStatus.Pending),
        PrCheck("docs preview", PrCheckStatus.Skipped),
    )
    val pending = listOf(
        PrCheck("build / compile", PrCheckStatus.Success, required = true),
        PrCheck("build / unit tests", PrCheckStatus.Pending, required = true),
        PrCheck("integration", PrCheckStatus.Pending),
    )

    fun detail(
        state: PrState = PrState.Open, draft: Boolean = false, checks: List<PrCheck> = passing, merge: Boolean = true, behind: Int? = null,
        mergeability: PrMergeability = PrMergeability.Mergeable,
    ) = PrDetail(
        title = "Add a friendlier greeting", url = URL, state = state, isDraft = draft, headRef = "feature/greeting", baseRef = "main", headSha = "abc1234",
        mergeability = mergeability, behindBy = behind, reviewDecision = PrReviewDecision.Approved, author = author, updatedAt = "2026-11-25T10:00:00Z",
        additions = 38, deletions = 9, changedFiles = 4, checks = checks,
        actions = if (state != PrState.Open) emptySet() else buildSet { if (merge) add(PrAction.Merge); add(PrAction.UpdateBranch); add(if (draft) PrAction.Ready else PrAction.Draft) },
        mergeMethods = if (merge) listOf(PrMergeMethod.Squash, PrMergeMethod.Merge, PrMergeMethod.Rebase) else emptyList(),
        canComment = state == PrState.Open, canViewDiff = true,
    )

    fun panel(detail: PrPanelStateInput) = PrPanelState(
        snapshot = detail.link.snapshot, detail = detail.detail, checks = detail.detail?.checks, answered = true, pendingAction = detail.pending,
        unavailableReason = detail.unavailable, lastUpdatedMillis = NOW - 5 * 60_000L,
    )

    class PrPanelStateInput(val link: ThreadPullRequest, val detail: PrDetail?, val pending: PendingPrAction? = null, val unavailable: PrUnavailableReason? = null)

    val live = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true, canWriteSourceControl = true, pullRequestsSupported = true, pullRequestChecksSupported = true,
    )
    val offline = live.copy(connection = ConnectionStatus.Reconnecting)
}

@Composable
private fun PrContent(
    link: ThreadPullRequest, detail: PrDetail?, section: PrSection = PrSection.Overview, client: ClientState = PrFixtures.live, pending: PendingPrAction? = null,
    unavailable: PrUnavailableReason? = null, files: PrFilesState = PrFilesState(), others: List<ThreadPullRequest> = emptyList(), onClose: (() -> Unit)? = {},
    modifier: Modifier = Modifier,
) {
    val panel = PrFixtures.panel(PrFixtures.PrPanelStateInput(link, detail, pending, unavailable))
    val env = PrPanelEnv(
        live = client.connection == ConnectionStatus.Connected, nowMillis = PrFixtures.NOW, actions = prActionsModel(panel, client, link.key.number), files = files,
    )
    PullRequestPanelContent(
        link = link, panel = panel, env = env, section = section, onSection = {}, handlers = PrActionHandlers({ _, _ -> }, {}), onLoadMore = {}, onRetryFiles = {},
        modifier = modifier, others = others, onClose = onClose,
    )
}

@Composable
private fun PrShot(dark: Boolean = false, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) { Column(Modifier.fillMaxSize().background(Tf.colors.bg)) { content() } }
}

private val loadedFiles get() = PrFilesState(document = DiffFixtures.small, started = true)

/* ---------- Overview by state ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 720)
@Composable
fun PullRequest_Open_Light() { PrShot { PrContent(PrFixtures.link(), PrFixtures.detail()) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PullRequest_Open_Dark() { PrShot(dark = true) { PrContent(PrFixtures.link(), PrFixtures.detail()) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun PullRequest_Draft_Light() {
    PrShot { PrContent(PrFixtures.link(draft = true), PrFixtures.detail(draft = true, behind = 3, mergeability = PrMergeability.Conflicting)) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun PullRequest_Merged_Light() { PrShot { PrContent(PrFixtures.link(PrState.Merged), PrFixtures.detail(PrState.Merged)) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun PullRequest_Closed_Light() { PrShot { PrContent(PrFixtures.link(PrState.Closed), PrFixtures.detail(PrState.Closed)) } }

/* ---------- Checks ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun PullRequest_ChecksFailing_Light() {
    PrShot { PrContent(PrFixtures.link(checks = PrChecksState.Failing), PrFixtures.detail(checks = PrFixtures.failing), section = PrSection.Checks) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PullRequest_ChecksFailing_Dark() {
    PrShot(dark = true) { PrContent(PrFixtures.link(checks = PrChecksState.Failing), PrFixtures.detail(checks = PrFixtures.failing), section = PrSection.Checks) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun PullRequest_ChecksPending_Light() {
    PrShot { PrContent(PrFixtures.link(checks = PrChecksState.Pending), PrFixtures.detail(checks = PrFixtures.pending), section = PrSection.Checks) }
}

/* ---------- Files ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_Files_Light() {
    PrShot { PrContent(PrFixtures.link(), PrFixtures.detail(), section = PrSection.Files, files = loadedFiles) }
}

/* ---------- Actions ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_Actions_Light() { PrShot { PrContent(PrFixtures.link(), PrFixtures.detail(), section = PrSection.Actions) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_ActionPending_Light() {
    PrShot {
        PrContent(
            PrFixtures.link(), PrFixtures.detail(), section = PrSection.Actions,
            pending = PendingPrAction(PrActionKind.Merge, PrFixtures.NOW, PrActionOutcome.Sending),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_ActionUnknown_Light() {
    PrShot {
        PrContent(
            PrFixtures.link(), PrFixtures.detail(), section = PrSection.Actions,
            pending = PendingPrAction(PrActionKind.Merge, PrFixtures.NOW, PrActionOutcome.Unknown),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_ActionRejected_Light() {
    PrShot {
        PrContent(
            PrFixtures.link(), PrFixtures.detail(), section = PrSection.Actions,
            pending = PendingPrAction(PrActionKind.UpdateBranch, PrFixtures.NOW, PrActionOutcome.Rejected("The branch has conflicts with main.")),
        )
    }
}

/* ---------- Unavailable, stale and offline ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun PullRequest_Unavailable_CliUnauthenticated_Light() {
    PrShot { PrContent(PrFixtures.link(), null, unavailable = PrUnavailableReason.CliUnauthenticated) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_Offline_Overview_Light() { PrShot { PrContent(PrFixtures.link(), PrFixtures.detail(), client = PrFixtures.offline) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun PullRequest_Offline_Actions_Light() {
    PrShot { PrContent(PrFixtures.link(), PrFixtures.detail(), section = PrSection.Actions, client = PrFixtures.offline) }
}

/* ---------- Phone sheet and wide docked panel ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760)
@Composable
fun PullRequest_Sheet_Phone_Light() {
    TrefoilTheme {
        Column(Modifier.fillMaxSize().background(Tf.colors.scrim).padding(top = 48.dp)) {
            Column(Modifier.fillMaxWidth().background(Tf.colors.sheet, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                PrContent(PrFixtures.link(), PrFixtures.detail(checks = PrFixtures.failing), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DockedShot(dark: Boolean, section: PrSection) {
    TrefoilTheme(darkTheme = dark) {
        Row(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            Column(Modifier.weight(1f).fillMaxSize().background(Tf.colors.s1)) {}
            Column(Modifier.padding(0.dp).fillMaxSize().weight(1f)) {
                SidePanel(
                    tabs = listOf(SidePanelTab.Changes, SidePanelTab.PullRequest), selected = SidePanelTab.PullRequest, disabled = emptySet(), onSelect = {}, onClose = {},
                    tabsFocus = FocusRequester(), modifier = Modifier.fillMaxSize(),
                ) {
                    PrContent(PrFixtures.link(), PrFixtures.detail(checks = PrFixtures.failing), section = section, files = loadedFiles, onClose = null)
                }
            }
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 960, heightDp = 640)
@Composable
fun PullRequest_Docked_Light() { DockedShot(dark = false, section = PrSection.Overview) }

@PreviewTest
@Preview(showBackground = true, widthDp = 960, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PullRequest_Docked_Files_Dark() { DockedShot(dark = true, section = PrSection.Files) }

/* ---------- Header chip ---------- */

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 120)
@Composable
fun PullRequest_Chips_Light() {
    TrefoilTheme {
        Row(Modifier.fillMaxWidth().background(Tf.colors.bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (link in listOf(
                PrFixtures.link(), PrFixtures.link(draft = true, checks = PrChecksState.Pending), PrFixtures.link(PrState.Merged, checks = null),
                PrFixtures.link(PrState.Closed, checks = PrChecksState.Failing),
            )) prChipModel(listOf(link))?.let { PullRequestChipContent(it, onClick = {}) }
        }
    }
}
