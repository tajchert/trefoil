package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Which toolbar button the issue detail offers for the thread that is open. */
enum class IssueLinkAction { None, Link, Unlink }

/**
 * The link or unlink command the user started and the shell row has not yet confirmed. The UI shows progress and ignores repeat
 * taps while one is held; it is cleared when the row shows (or drops) the key, when the command fails, or after a timeout.
 */
data class IssueLinkPending(val environmentId: String, val threadId: String, val identity: String, val linking: Boolean)

/** The link a manual Link tap sends, built from the loaded detail; null when the server left a field a link needs blank. */
internal fun issueLinkFor(summary: IssueSummary): ThreadIssueLink? =
    if (summary.url.isBlank() || summary.displayKey.isBlank() || summary.title.isBlank() || summary.ref.projectId.isBlank()) null
    else ThreadIssueLink(summary.issueKey, summary.ref.projectId, summary.url, source = "manual", displayKey = summary.displayKey, title = summary.title)

/** The open, non-archived row of the selected thread, or null. */
private fun selectedRow(state: ClientState): ThreadSummary? = state.threads.firstOrNull {
    it.id == state.selectedThreadId && it.deletedAt == null && !it.archived && it.archivedAt == null
}

/**
 * Link when a thread of the same environment is selected and does not show the issue yet, Unlink when it does. Nothing unless the
 * connection is live, the server offers issues and threads can be written. The row is the truth: a held pending command does not
 * change the answer.
 */
internal fun issueLinkAction(detailEnvironmentId: String?, summary: IssueSummary?, state: ClientState, hasDraft: Boolean): IssueLinkAction {
    if (summary == null || hasDraft || detailEnvironmentId == null || state.environment?.id != detailEnvironmentId) return IssueLinkAction.None
    if (state.connection != ConnectionStatus.Connected || !state.issuesSupported || !state.canWriteThreads) return IssueLinkAction.None
    val row = selectedRow(state) ?: return IssueLinkAction.None
    return when {
        row.issues.any { it.key.identity == summary.issueKey.identity } -> IssueLinkAction.Unlink
        issueLinkFor(summary) != null -> IssueLinkAction.Link
        else -> IssueLinkAction.None
    }
}

/**
 * Link and unlink for the selected thread. One command is in flight at a time, checked synchronously at the tap. Each command is sent
 * once through the client and never replayed: a definite rejection keeps the session and shows a notice, an uncertain outcome shows
 * a notice and leaves the shell row, after the reconnect, as the truth. After a success it waits (up to [settleMillis]) for the
 * row to show or drop the key, then calls [onSettled] so the issue's linked threads are read again. Nothing here logs titles or URLs.
 */
class IssueLinkController(
    private val scope: CoroutineScope, private val client: () -> TrefoilClient, private val notice: (String?) -> Unit,
    private val onSettled: () -> Unit, private val track: suspend (suspend () -> Unit) -> Unit = { it() },
    private val settleMillis: Long = SETTLE_MILLIS,
) {
    private val mutable = MutableStateFlow<IssueLinkPending?>(null)
    val pending: StateFlow<IssueLinkPending?> = mutable.asStateFlow()

    fun link(link: ThreadIssueLink) = run(link.key, linking = true) { target -> client().linkIssue(target, link) }

    fun unlink(key: IssueKey) = run(key, linking = false) { target -> client().unlinkIssue(target, key) }

    private fun run(key: IssueKey, linking: Boolean, send: suspend (ThreadTarget) -> Unit) {
        val current = client().state.value
        val environmentId = current.environment?.id ?: return
        val row = selectedRow(current) ?: return
        val shown = row.issues.any { it.key.identity == key.identity }
        if (shown == linking) return
        val held = IssueLinkPending(environmentId, row.id, key.identity, linking)
        if (!mutable.compareAndSet(null, held)) return
        notice(null)
        scope.launch {
            try { track { send(ThreadTarget(environmentId, row.id)) } }
            catch (e: CancellationException) { release(held); throw e }
            catch (_: UncertainOutcomeException) { release(held); notice(uncertainMessage(linking)); return@launch }
            catch (e: Exception) { release(held); notice(failureMessage(e, linking)); return@launch }
            withTimeoutOrNull(settleMillis) { client().state.first { reflects(it, held) } }
            release(held)
            onSettled()
        }
    }

    private fun release(held: IssueLinkPending) { mutable.compareAndSet(held, null) }

    private fun reflects(state: ClientState, held: IssueLinkPending): Boolean {
        if (state.environment?.id != held.environmentId) return true
        val row = state.threads.firstOrNull { it.id == held.threadId } ?: return true
        return row.issues.any { it.key.identity == held.identity } == held.linking
    }

    companion object {
        const val SETTLE_MILLIS = 10_000L
        internal fun uncertainMessage(linking: Boolean) =
            "Issue ${if (linking) "link" else "unlink"} outcome is uncertain. Reconnect, then check the thread's issues before trying again."
        /** Fixed wording: the server's message and the issue's text never reach the notice. */
        internal fun failureMessage(e: Exception, linking: Boolean): String = when {
            (e as? IssueException)?.failure == IssueFailure.NotSupported -> "This T3 server does not support issues."
            e is IllegalStateException && e.message?.startsWith("T3 rejected") == true -> "T3 rejected the issue ${if (linking) "link" else "unlink"}."
            else -> "Could not ${if (linking) "link" else "unlink"} the issue. Check the thread and try again."
        }
    }
}
