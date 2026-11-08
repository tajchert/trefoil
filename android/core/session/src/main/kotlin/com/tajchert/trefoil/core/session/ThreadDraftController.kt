package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Owns the local new-conversation draft and message sends for the view model. Confine calls to
 * one thread (the main dispatcher in the app): state is not synchronized.
 *
 * A draft keeps its `threadId`/`commandId` until the thread is known to exist. After an uncertain
 * launch it stays, and once the session is connected again and the shell lists its `threadId`, the
 * draft is cleared and that thread selected. An explicit resend while it is absent reuses the same
 * ids, which the server deduplicates; nothing is ever resent automatically.
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

    init { scope.launch { client.state.collect { reconcile(it) } } }

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
            if (client.state.value.environment?.id == launchEnvironment) open(id)
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
    /** Draft: updates locally, keeping a mode the model supports. Selected thread: dispatches `thread.model-selection.set`. */
    fun setModel(model: ModelOption) {
        val current = _draft.value
        if (current != null) _draft.value = current.copy(model = model, runtimeMode = compatibleRuntimeMode(current.runtimeMode, model))
        else mutate("Model change outcome is uncertain. Reconnect and check the thread.") { client.setModel(model) }
    }
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
        if (launching || state.connection != ConnectionStatus.Connected || state.environment?.id != environmentId) return
        if (state.threads.none { it.id == pending.threadId }) return
        clear(); open(pending.threadId)
    }
    private fun open(threadId: String) {
        scope.launch {
            try { client.selectThread(threadId) }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); notice(STARTED_MESSAGE) }
            catch (_: Exception) { notice(STARTED_MESSAGE) }
        }
    }

    private companion object {
        const val UNCERTAIN_MESSAGE = "Message outcome is uncertain. Check the conversation before sending again."
        const val LAUNCH_UNCERTAIN = "New conversation outcome is uncertain. Reconnect and check the thread list; sending again will not create a duplicate."
        const val STARTED_MESSAGE = "Conversation started. Reconnect to open it."
    }
}
