package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraftKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The first message a thread started from an issue is prefilled with: key, title and URL, never the body (it can be large and is
 * untrusted). Ends with a blank line so the user continues below the link.
 */
internal fun issueStartTemplate(summary: IssueSummary): String = "${summary.displayKey}: ${summary.title}\n${summary.url}\n\n"

/**
 * Start thread is offered when the issue is of the current environment, the shell is connected and writable, the server offers issues,
 * the issue's project is one this environment lists (the sheet presets to it) and the link can be built.
 */
internal fun canStartThreadFromIssue(detailEnvironmentId: String?, summary: IssueSummary?, state: ClientState): Boolean =
    summary != null && detailEnvironmentId != null && state.environment?.id == detailEnvironmentId &&
        state.connection == ConnectionStatus.Connected && state.issuesSupported && state.canWriteThreads &&
        state.projects.any { it.id == summary.ref.projectId } && issueLinkFor(summary) != null

/**
 * A Start thread tap in progress: the New thread sheet opens on [projectId] carrying [link]. While [askReplace] the project's new-thread
 * draft holds text of its own and nothing opens until the user picks Replace or Keep current.
 */
data class IssueStartRequest(val environmentId: String, val projectId: String, val link: ThreadIssueLink, val template: String, val askReplace: Boolean)

/**
 * Start-from-issue state for the view model (memory only). [start] prefills the project's new-thread composer draft with the template
 * only when that draft is empty; a draft with text is never overwritten without [replace]. The request stays until the sheet is
 * cancelled ([cancel]) or the draft is started ([take]), which hands out the link for `ThreadDraft.issueLink`. Nothing here logs
 * titles or URLs.
 */
class IssueStartController(
    private val scope: CoroutineScope,
    private val state: () -> ClientState,
    private val draftText: (ComposerDraftKey) -> String?,
    private val writeText: (ComposerDraftKey, String) -> Unit,
    private val awaitLoaded: suspend () -> Unit = {},
) {
    private val mutable = MutableStateFlow<IssueStartRequest?>(null)
    val request: StateFlow<IssueStartRequest?> = mutable.asStateFlow()
    private var starting = false

    fun start(detailEnvironmentId: String?, summary: IssueSummary?) {
        if (starting || mutable.value != null || summary == null || !canStartThreadFromIssue(detailEnvironmentId, summary, state())) return
        starting = true
        scope.launch {
            try {
                // Stored drafts load asynchronously; judging "empty" before that could overwrite one.
                awaitLoaded()
                val now = state()
                if (!canStartThreadFromIssue(detailEnvironmentId, summary, now)) return@launch
                val link = issueLinkFor(summary) ?: return@launch
                val environmentId = now.environment?.id ?: return@launch
                val projectId = summary.ref.projectId
                val template = issueStartTemplate(summary)
                val key = ComposerDraftKey.NewThread(environmentId, projectId)
                val empty = draftText(key).isNullOrBlank()
                if (empty) writeText(key, template)
                mutable.value = IssueStartRequest(environmentId, projectId, link, template, askReplace = !empty)
            } finally { starting = false }
        }
    }

    /** Replace was chosen: the template takes the project's draft text (its settings stay) and the sheet may open. */
    fun replace() {
        val request = mutable.value?.takeIf { it.askReplace } ?: return
        writeText(ComposerDraftKey.NewThread(request.environmentId, request.projectId), request.template)
        mutable.value = request.copy(askReplace = false)
    }

    /** Keep current was chosen: the draft stays as it is, the sheet opens on it and the draft still carries the link. */
    fun keep() {
        val request = mutable.value?.takeIf { it.askReplace } ?: return
        mutable.value = request.copy(askReplace = false)
    }

    /** The sheet or the dialog was dismissed without starting. */
    fun cancel() { mutable.value = null }

    /** The link for the draft being started in [environmentId]; clears the request. Null when none is pending or it is another machine's. */
    fun take(environmentId: String?): ThreadIssueLink? {
        val request = mutable.value ?: return null
        mutable.value = null
        return request.link.takeIf { request.environmentId == environmentId && !request.askReplace }
    }
}
