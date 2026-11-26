package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.IssueKey
import com.tajchert.trefoil.core.model.ThreadIssueLink

/** Fixed, invented issue links for the chip and sheet screenshots; nothing here comes from a real tracker. */
internal object IssueFixtures {
    private fun link(scope: String, key: String, title: String, source: String) = ThreadIssueLink(
        key = IssueKey("github", "github.com", scope, key), projectId = "project-1", url = "https://github.com/$scope/issues/$key",
        source = source, linkedAt = "2026-11-20T09:00:00Z", displayKey = "#$key", title = title,
    )

    val agentLinked = link("acme/widgets", "128", "Retry sync after a dropped connection", "agent")
    val manual = link("acme/widgets", "131", "Thread list flickers when a thread settles", "manual")
    val third = ThreadIssueLink(
        key = IssueKey("linear", "linear.app", "TRF", "TRF-42"), projectId = "project-1", url = "https://linear.app/acme/issue/TRF-42",
        source = "manual", linkedAt = null, displayKey = "TRF-42", title = "Show a link to the issue in the thread header and keep long titles readable on narrow screens",
    )

    val one = listOf(agentLinked)
    val three = listOf(agentLinked, manual, third)

    /** The selected thread of [state] carries [links], as the shell row does once T3 reports them. */
    fun withIssues(state: ClientState, links: List<ThreadIssueLink>): ClientState =
        state.copy(threads = state.threads.map { if (it.id == state.selectedThreadId) it.copy(issues = links) else it })
}
