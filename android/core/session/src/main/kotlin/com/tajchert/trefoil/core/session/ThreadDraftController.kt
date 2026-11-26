package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Shown when a send's outcome is unknown; shared by the draft controller and the app's follow-up sends. */
const val UNCERTAIN_MESSAGE = "Message outcome is uncertain. Check the conversation before sending again."

/** Shown when a thread started from an issue exists but its issue link was not made; the UI offers "Link issue". */
const val ISSUE_NOT_LINKED_MESSAGE = "Thread started; the issue was not linked"

/**
 * A started thread that should link [link] but does not: the link was rejected, or its outcome was unknown and the
 * live shell row still lacks the key after reconnect. The UI shows [message] with a "Link issue" action
 * ([ThreadDraftController.retryIssueLink]). In memory only.
 */
data class IssueLinkNotice(val threadId: String, val environmentId: String, val link: ThreadIssueLink,
    val message: String = ISSUE_NOT_LINKED_MESSAGE) {
    val target: ThreadTarget get() = ThreadTarget(environmentId, threadId)
}

/**
 * Owns the local new-conversation draft and message sends for the view model. Confine calls to
 * one thread (the main dispatcher in the app): state is not synchronized.
 *
 * A draft keeps its `threadId`/`commandId` until the thread is known to exist. After an uncertain
 * launch it stays, and once the session is connected again and its live shell ([ClientState.shellLive]) lists its
 * `threadId`, the draft is cleared and that thread selected. An explicit resend while it is absent reuses the same
 * ids, which the server deduplicates; nothing is ever resent automatically.
 *
 * A draft with [ThreadDraft.issueLink] links that issue once the thread is known to exist (launch result or reconcile
 * hit), at most once per draft and only while still in the draft's environment. A rejected link exposes
 * [issueLinkNotice]; an uncertain one is never resent, and after reconnect the same notice appears if the live shell
 * row lacks the key. Only [retryIssueLink] sends another link, once per call.
 */
class ThreadDraftController(
    private val scope: CoroutineScope,
    private val client: TrefoilClient,
    private val notice: (String?) -> Unit,
) {
    private val _draft = MutableStateFlow<ThreadDraft?>(null)
    val draft: StateFlow<ThreadDraft?> = _draft.asStateFlow()
    private val _sendFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val sendFailed: SharedFlow<Unit> = _sendFailed.asSharedFlow()
    /** Environment the draft belongs to; a draft never follows the user to another environment. */
    var environmentId: String? = null
        private set
    /** One launch at a time: a second tap must not race the first. */
    private var launching = false
    private val _issueLinkNotice = MutableStateFlow<IssueLinkNotice?>(null)
    val issueLinkNotice: StateFlow<IssueLinkNotice?> = _issueLinkNotice.asStateFlow()
    /** Draft thread ids whose automatic link was already attempted; set before sending. */
    private val linkAttempted = mutableSetOf<String>()
    /** The draft's link, waiting for the live shell to list the started thread ([TrefoilClient.linkIssue] needs its row). */
    private var awaitingLink: IssueLinkNotice? = null
    /** A link whose outcome is unknown; checked against the live shell after reconnect, never resent. */
    private var uncertainLink: IssueLinkNotice? = null
    /** One explicit link command at a time. */
    private var linking = false

    init { scope.launch { client.state.collect { reconcile(it); sendAwaitingLink(it); reconcileIssueLink(it) } } }

    /** Opens a local draft; no server mutation until the first send. */
    fun start(draft: ThreadDraft) {
        notice(null); environmentId = client.state.value.environment?.id; _draft.value = draft
    }
    fun clear() { _draft.value = null; environmentId = null }
    private fun clearIf(expected: ThreadDraft) { if (_draft.value?.threadId == expected.threadId) clear() }

    /** With a draft, the first message creates the thread; otherwise it goes to the selected thread. */
    fun send(text: String) {
        val pending = _draft.value ?: return sendToThread(text)
        // The first tap's launch is still in flight and settles the composer itself.
        if (launching) return
        launching = true
        val launchEnvironment = environmentId
        scope.launch {
            notice(null)
            var failure: String? = null
            val id = try { client.launchThread(pending, text) }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); failure = LAUNCH_UNCERTAIN; null }
            catch (e: UncertainOutcomeException) { failure = LAUNCH_UNCERTAIN; null }
            catch (e: Exception) { failure = e.message ?: "Could not start the conversation. Reconnect and try again."; null }
            finally { launching = false }
            if (id == null) { launchFailed(failure ?: LAUNCH_UNCERTAIN); return@launch }
            clearIf(pending)
            // The user may have moved to another environment meanwhile; the thread is not there.
            if (client.state.value.environment?.id == launchEnvironment) { open(id); linkAfterLaunch(pending, id, launchEnvironment) }
        }
    }
    /** The draft and its ids stay; the thread may already exist, so check right away as well. */
    private fun launchFailed(message: String) {
        notice(message); _sendFailed.tryEmit(Unit); reconcile(client.state.value)
    }

    private fun sendToThread(text: String) {
        scope.launch {
            notice(null)
            try { client.sendMessage(text) }
            catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                notice(UNCERTAIN_MESSAGE); _sendFailed.tryEmit(Unit)
            } catch (e: UncertainOutcomeException) { notice(UNCERTAIN_MESSAGE); _sendFailed.tryEmit(Unit) }
            catch (e: Exception) { notice(e.message ?: "Could not send the message."); _sendFailed.tryEmit(Unit) }
        }
    }

    /** Draft: updates locally. Selected thread: dispatches `thread.runtime-mode.set`. */
    fun setRuntimeMode(mode: RuntimeMode) {
        val current = _draft.value
        if (current != null) _draft.value = current.copy(runtimeMode = mode)
        else mutate("Mode change outcome is uncertain. Reconnect and check the thread.") { client.setRuntimeMode(mode) }
    }
    /**
     * Draft: updates locally, keeping a mode the model supports and only the options it offers.
     * Selected thread: dispatches `thread.model-selection.set`.
     */
    fun setModel(model: ModelOption) {
        val current = _draft.value
        if (current != null) _draft.value = current.copy(model = model, runtimeMode = compatibleRuntimeMode(current.runtimeMode, model), modelOptions = catalogEntry(model).validOptions(current.modelOptions))
        else mutate("Model change outcome is uncertain. Reconnect and check the thread.") { client.setModel(model) }
    }
    /**
     * Sets provider option [id] ([value] String or Boolean). Draft: updates locally when the model offers
     * the value, otherwise shows why. Selected thread: dispatches `thread.model-selection.set` with merged options.
     */
    fun setModelOption(id: String, value: Any) {
        val current = _draft.value
        if (current != null) {
            val refusal = runCatching { catalogEntry(current.model).requireOptionValue(id, value) }.exceptionOrNull()
            if (refusal != null) notice(refusal.message) else _draft.value = current.copy(modelOptions = current.modelOptions + (id to value))
        } else mutate("Model option change outcome is uncertain. Reconnect and check the thread.") { client.setModelOption(id, value) }
    }
    /** The server catalog entry for [model] (current descriptors), or [model] itself when the catalog lacks it. */
    private fun catalogEntry(model: ModelOption): ModelOption = client.state.value.models.firstOrNull { it.sameModelAs(model) } ?: model
    private fun mutate(uncertain: String, block: suspend () -> Unit) {
        scope.launch {
            notice(null)
            try { block() }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); notice(uncertain) }
            catch (_: UncertainOutcomeException) { notice(uncertain) }
            catch (e: Exception) { notice(e.message ?: uncertain) }
        }
    }

    private fun reconcile(state: ClientState) {
        val pending = _draft.value ?: return
        // Only rows the current connection's shell confirmed (shellLive): a cached or retained row may list a thread the
        // server has since deleted, and clearing the draft on it would lose the user's text.
        if (launching || state.connection != ConnectionStatus.Connected || !state.shellLive || state.environment?.id != environmentId) return
        val row = state.threads.firstOrNull { it.id == pending.threadId } ?: return
        val launchEnvironment = environmentId
        clear(); open(pending.threadId)
        // The shell may already show the link (an agent linked it): nothing to send.
        if (pending.issueLink?.let { !row.showsLink(it) } == true) linkAfterLaunch(pending, pending.threadId, launchEnvironment)
    }

    /**
     * Queues the draft's issue link, at most once per draft and only when still in [launchEnvironment]. It is sent as
     * soon as the live shell lists the thread; a row already showing the key needs nothing.
     */
    private fun linkAfterLaunch(draft: ThreadDraft, threadId: String, launchEnvironment: String?) {
        val link = draft.issueLink ?: return
        if (launchEnvironment == null || client.state.value.environment?.id != launchEnvironment) return
        if (!linkAttempted.add(draft.threadId)) return
        awaitingLink = IssueLinkNotice(threadId, launchEnvironment, link)
        sendAwaitingLink(client.state.value)
    }
    private fun sendAwaitingLink(state: ClientState) {
        val pending = awaitingLink ?: return
        val environment = state.environment?.id ?: return
        if (pending.environmentId != environment) { awaitingLink = null; return }
        if (linking || state.connection != ConnectionStatus.Connected || !state.shellLive) return
        val row = state.threads.firstOrNull { it.id == pending.threadId } ?: return
        awaitingLink = null
        if (!row.showsLink(pending.link)) sendLink(pending)
    }
    private fun ThreadSummary.showsLink(link: ThreadIssueLink) = issues.any { it.key.identity == link.key.identity }
    private fun shownInShell(pending: IssueLinkNotice): Boolean = shownIn(client.state.value, pending)
    private fun shownIn(state: ClientState, pending: IssueLinkNotice): Boolean =
        state.environment?.id == pending.environmentId && state.threads.any { it.id == pending.threadId && it.showsLink(pending.link) }

    /** "Link issue": a new explicit `linkIssue` for the shown notice, once per call; ignored while one is in flight. */
    fun retryIssueLink() {
        val pending = _issueLinkNotice.value ?: return
        if (linking) return
        if (client.state.value.environment?.id != pending.environmentId) { _issueLinkNotice.value = null; return }
        sendLink(pending)
    }
    fun dismissIssueLinkNotice() { _issueLinkNotice.value = null; uncertainLink = null; awaitingLink = null }

    private fun sendLink(pending: IssueLinkNotice) {
        linking = true
        scope.launch {
            var uncertain = false
            var rejected = false
            try { client.linkIssue(pending.target, pending.link) }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); uncertain = true }
            catch (_: UncertainOutcomeException) { uncertain = true }
            catch (_: Exception) { rejected = true }
            finally { linking = false }
            // The user moved to another environment meanwhile: the outcome belongs to a thread not shown there.
            if (client.state.value.environment?.id != pending.environmentId) { sendAwaitingLink(client.state.value); return@launch }
            when {
                // Never resent: the live shell row decides after reconnect.
                uncertain -> { _issueLinkNotice.value = null; uncertainLink = pending }
                // A refusal because the row already shows the key (an agent linked it) is not a failure.
                rejected -> { uncertainLink = null; _issueLinkNotice.value = if (shownInShell(pending)) null else pending }
                else -> { uncertainLink = null; _issueLinkNotice.value = null }
            }
            // A draft's row may have arrived while this command was in flight.
            sendAwaitingLink(client.state.value)
        }
    }

    /** Settles an uncertain link from the live shell (no resend); drops link state that belongs to another environment. */
    private fun reconcileIssueLink(state: ClientState) {
        val environment = state.environment?.id ?: return
        // Another environment, or the shell now shows the key (an agent linked it): the notice no longer applies.
        if (_issueLinkNotice.value?.let { it.environmentId != environment || (state.shellLive && shownIn(state, it)) } == true) _issueLinkNotice.value = null
        val pending = uncertainLink ?: return
        if (pending.environmentId != environment) { uncertainLink = null; return }
        if (linking || state.connection != ConnectionStatus.Connected || !state.shellLive) return
        val row = state.threads.firstOrNull { it.id == pending.threadId } ?: return
        uncertainLink = null
        if (!row.showsLink(pending.link)) _issueLinkNotice.value = pending
    }
    private fun open(threadId: String) {
        scope.launch {
            try { client.selectThread(threadId) }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); notice(STARTED_MESSAGE) }
            catch (_: Exception) { notice(STARTED_MESSAGE) }
        }
    }

    private companion object {
        const val LAUNCH_UNCERTAIN = "New conversation outcome is uncertain. Reconnect and check the thread list; sending again will not create a duplicate."
        const val STARTED_MESSAGE = "Conversation started. Reconnect to open it."
    }
}
