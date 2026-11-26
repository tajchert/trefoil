package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.IssueListUiState
import com.tajchert.trefoil.core.model.*

/** Invented issue rows for the list screenshots. Fixed instants (the screenshots pin the clock at [NOW]); nothing comes from a real tracker. */
internal object IssueListFixtures {
    const val NOW = "2026-11-26T12:00:00Z"
    const val ENV = "env-workstation"

    private fun row(
        key: String, title: String, updated: String, labels: List<IssueLabel> = emptyList(), assignees: List<IssueActor> = emptyList(), comments: Int = 0,
        open: Boolean = true, project: String = "Widgets", scope: String = "acme/widgets",
    ) = IssueSummary(
        IssueRef("p1", "github", "github.com", scope, key), "github.com", "#$key", title, "https://github.com/$scope/issues/$key", IssueActor("mira"),
        IssueState(open, if (open) "Open" else "Closed"), "2026-11-01T09:00:00Z", updated, null, assignees, labels, comments, project,
    )

    private val bug = IssueLabel("bug", "d73a4a")
    private val android = IssueLabel("android", "0e8a16")
    private val triage = IssueLabel("needs-triage", null)

    val rows = listOf(
        row("482", "Settings screen crashes when the server list is empty", "2026-11-26T11:40:00Z", listOf(bug, android), listOf(IssueActor("jonas")), 4),
        row("479", "Retry sync after a dropped connection and keep the draft", "2026-11-26T08:10:00Z", listOf(android), listOf(IssueActor("priya", "Priya Rao")), 2),
        row("475", "Thread list flickers when a thread settles", "2026-11-25T14:00:00Z", listOf(bug, android, triage, IssueLabel("regression", "fbca04")), emptyList(), 0),
        row("TRF-42", "Show a link to the issue in the thread header and keep long titles readable on narrow screens", "2026-11-22T10:00:00Z", emptyList(), listOf(IssueActor("ada-lovelace")), 11,
            project = "Trefoil", scope = "TRF"),
        row("466", "Dark theme contrast on the usage meter", "2026-11-12T10:00:00Z", listOf(IssueLabel("design", "5319e7")), emptyList(), 1, open = false),
    )

    private val github = IssueTrackerStatus("github", "github.com", 2, true, null)

    val list = IssueListUiState(environmentId = ENV, entries = rows, trackers = listOf(github), loaded = true)
    val loadingMore = list.copy(loadingMore = true, nextCursors = mapOf("p1" to "c1"))
    val capped = list.copy(capped = true)
    val errors = list.copy(errors = listOf(IssueProjectError("p2", "Gadgets", "GitHub rate limit reached")), moreFailure = null)
    val unconfigured = IssueListUiState(
        environmentId = ENV, trackers = listOf(IssueTrackerStatus("github", "github.com", 2, false, "Sign in with gh auth login on the computer")), loaded = true,
    )
    val empty = IssueListUiState(environmentId = ENV, trackers = listOf(github), loaded = true)
    val unavailable = IssueListUiState(environmentId = ENV, failure = IssueFailure.Unavailable, failureMessage = "Issues are unavailable right now. Try again.")
    val offline = list.copy(live = false)

    /** Non-default filters on: closed state, assigned to me, one label, an assignee, newest first. */
    val filtered = list.copy(
        query = IssueQuery(state = IssueListState.Closed, involvement = IssueInvolvement.Assigned, projectId = "p-trefoil", labels = listOf("design"), assignee = "mira", sort = IssueSort.Created),
        entries = rows.takeLast(1),
    )
    val noMatch = empty.copy(query = IssueQuery(text = "zebra", labels = listOf("bug")))
}
