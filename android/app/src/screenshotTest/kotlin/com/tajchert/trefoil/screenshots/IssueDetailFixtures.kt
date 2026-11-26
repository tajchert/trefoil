package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.IssueDetailUiState
import com.tajchert.trefoil.core.model.*

/** Fake issue detail data for preview screenshots. Fixed instants, no clock. */
object IssueDetailFixtures {
    val ref = IssueRef("p1", "github", "github.com", "acme/widgets", "482")
    private val author = IssueActor("mira")
    private val summary = IssueSummary(
        ref, "github.com", "#482", "Settings screen crashes when the server list is empty",
        "https://github.com/acme/widgets/issues/482", author, IssueState(true, "Open"), "2026-11-20T09:15:00Z", "2026-11-24T16:40:00Z", null,
        listOf(IssueActor("jonas"), IssueActor("priya")), listOf(IssueLabel("bug", "d73a4a"), IssueLabel("android", "0e8a16"), IssueLabel("needs-triage", null)),
        4, "Widgets",
    )

    private fun comment(id: String, login: String, at: String, body: String) = IssueComment(id, IssueActor(login), body, at, null)

    val comments = listOf(
        comment("c1", "jonas", "2026-11-20T10:02:00Z", "I can reproduce this on a fresh install. The crash is in `ServerListAdapter`."),
        comment("c2", "priya", "2026-11-21T08:30:00Z", "Looks like the empty state is never inflated.\n\n- check `serverCount`\n- add a guard"),
        comment("c3", "mira", "2026-11-23T14:12:00Z", "Fix is up for review, see the linked pull request."),
    )
    val events = listOf(
        IssueEvent("e1", IssueEventKind.Labeled, IssueActor("jonas"), "2026-11-20T10:05:00Z", "bug", null),
        IssueEvent("e2", IssueEventKind.Assigned, IssueActor("mira"), "2026-11-21T09:00:00Z", "priya", null),
        IssueEvent("e3", IssueEventKind.Referenced, IssueActor("priya"), "2026-11-22T11:45:00Z", "acme/widgets#490", "https://github.com/acme/widgets/pull/490"),
    )
    val pullRequests = listOf(
        IssueLinkedPr("acme/widgets", 490, "Guard against an empty server list", "https://github.com/acme/widgets/pull/490", "open", false, true),
        IssueLinkedPr("acme/widgets", 471, "Refactor server list adapter", "https://github.com/acme/widgets/pull/471", "merged", true, false),
    )
    val linkedThreads = listOf(
        IssueLinkedThread("t1", "p1", "Fix empty server list crash", archived = false, settled = false),
        IssueLinkedThread("t2", "p1", "Investigate adapter inflation", archived = true, settled = false),
        IssueLinkedThread("t3", "p1", "Write regression test", archived = false, settled = true),
    )

    private const val body = "When the server list is empty the **Settings** screen crashes on open.\n\n" +
        "Steps:\n1. Remove every saved server\n2. Open Settings\n\nExpected: an empty state. Actual: `IllegalStateException`."

    fun detail(
        comments: List<IssueComment> = emptyList(), events: List<IssueEvent> = emptyList(), cursor: String? = null,
        pullRequests: List<IssueLinkedPr> = emptyList(), body: String = IssueDetailFixtures.body,
    ) = IssueDetail(
        summary, writable = false, capabilities = IssueCapabilities(events = true, linkedPullRequests = true), permissions = IssueViewerPermissions(),
        states = emptyList(), viewer = "mira", body = body, comments = comments, commentsCursor = cursor, events = events, linkedPullRequests = pullRequests,
    )

    private fun state(detail: IssueDetail) = IssueDetailUiState(environmentId = "env", ref = ref, detail = detail, linkedThreadsSupported = true)

    val header = state(detail())
    val activity = state(detail(comments, events))
    val loadOlder = state(detail(comments.drop(1), events.drop(1), cursor = "cursor-1"))
    val loadOlderFailed = loadOlder.copy(olderFailure = "The request timed out. Try again.")
    val pullRequestsState = state(detail(pullRequests = pullRequests, body = "Short description."))
    val threads = state(detail(body = "Short description.")).copy(linkedThreads = linkedThreads)
    val unavailable = IssueDetailUiState(
        environmentId = "env", ref = ref, failure = IssueFailure.NotFound, failureMessage = "This issue was not found. It may have been deleted or moved.",
    )
    val disconnected = state(detail(comments.take(1), events.take(1), cursor = "cursor-1")).copy(live = false, linkedThreads = linkedThreads.take(1))
    val notConnected = IssueDetailUiState(
        environmentId = "env", ref = ref, failure = IssueFailure.NotConnected, failureMessage = "Connect to view this issue", live = false,
    )
}
