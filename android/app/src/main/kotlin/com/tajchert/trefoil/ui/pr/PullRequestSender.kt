package com.tajchert.trefoil.ui.pr

import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PullRequestKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sends pull request mutations on a scope that outlives the panel (the ViewModel's), so closing the sheet, switching threads or rotating
 * never cancels a request that is already on the wire (a cancelled send would be recorded as an unknown outcome). A synchronous per-key
 * guard drops a second tap while the first is still in flight: nothing is ever sent twice. A refusal before sending (not live, not
 * allowed) is kept per pull request in [errors] until the next send or [dismissError]. Never logs request content.
 */
class PullRequestSender(
    private val scope: CoroutineScope,
    private val sendAction: suspend (PullRequestKey, PrAction, PrMergeMethod?) -> Unit,
    private val sendComment: suspend (PullRequestKey, String) -> Unit,
) {
    private val lock = Any()
    private val _busy = MutableStateFlow<Set<PullRequestKey>>(emptySet())
    private val _errors = MutableStateFlow<Map<PullRequestKey, String>>(emptyMap())

    /** Pull requests with a send in flight. */
    val busy: StateFlow<Set<PullRequestKey>> = _busy.asStateFlow()

    /** The last refusal per pull request, cleared by the next send for it. */
    val errors: StateFlow<Map<PullRequestKey, String>> = _errors.asStateFlow()

    /** Returns false (and sends nothing) when a send for [key] is already in flight. */
    fun run(key: PullRequestKey, action: PrAction, method: PrMergeMethod?): Boolean =
        launchOnce(key, "The action could not be sent.") { sendAction(key, action, method) }

    fun comment(key: PullRequestKey, body: String): Boolean =
        launchOnce(key, "The comment could not be sent.") { sendComment(key, body) }

    fun dismissError(key: PullRequestKey) {
        _errors.update { it - key }
    }

    private fun launchOnce(key: PullRequestKey, fallback: String, send: suspend () -> Unit): Boolean {
        synchronized(lock) {
            if (key in _busy.value) return false
            _busy.update { it + key }
            _errors.update { it - key }
        }
        scope.launch {
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.update { it + (key to (e.message?.takeIf { m -> m.isNotBlank() } ?: fallback)) }
            } finally {
                synchronized(lock) { _busy.update { it - key } }
            }
        }
        return true
    }
}
