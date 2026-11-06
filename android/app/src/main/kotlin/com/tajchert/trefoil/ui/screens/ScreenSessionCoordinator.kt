package com.tajchert.trefoil.ui.screens

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Ephemeral viewer credentials stay in memory and never enter SavedState or the environment vault. */
data class ScreenViewerState(
    val environmentId: String? = null,
    val target: ScreenTarget? = null,
    val access: ScreenAccess? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    override fun toString() = "ScreenViewerState(loading=$loading, credentials=***)"
}

class ScreenSessionCoordinator(private val scope: CoroutineScope, private val client: TrefoilClient) {
    private val mutable = MutableStateFlow(ScreenViewerState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var catalogRequest: Job? = null
    private var revision = 0L
    private var paused = false

    private data class LifecycleKey(val environmentId: String?, val connection: ConnectionStatus, val threadId: String?, val fresh: Boolean)
    init {
        scope.launch {
            client.state.map { LifecycleKey(it.environment?.id, it.connection, it.selectedThreadId, it.canRead || it.canWrite) }.distinctUntilChanged().collect { (environmentId, connection, threadId, fresh) ->
                val viewer = mutable.value
                if (viewer.target != null) {
                    if (viewer.environmentId != environmentId || viewer.target.threadId != threadId) close()
                    else if (connection != ConnectionStatus.Connected) suspendViewer()
                    else if (!paused && fresh && viewer.access == null && !viewer.loading && viewer.error == null) open(viewer.target)
                }
            }
        }
    }

    fun isCurrent(access: ScreenAccess): Boolean = !paused && client.isScreenAccessCurrent(access)

    fun refresh() {
        if (paused) return
        catalogRequest?.cancel()
        catalogRequest = scope.launch {
            val environmentId = client.state.value.environment?.id
            try { client.refreshScreens() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (client.state.value.environment?.id == environmentId) mutable.update { it.copy(error = "Could not refresh screen sessions. Reconnect and try again.") }
            }
        }
    }

    fun open(target: ScreenTarget) {
        if (paused) return
        val environmentId = client.state.value.environment?.id ?: return
        val epoch = ++revision
        request?.cancel()
        mutable.value = ScreenViewerState(environmentId, target, loading = true)
        request = scope.launch {
            try {
                check(!paused && client.state.value.connection == ConnectionStatus.Connected)
                // A reconnect resets catalog freshness even when environment/thread IDs stay the same.
                client.refreshScreens()
                val access = client.screenAccess(target)
                if (!paused && epoch == revision && client.state.value.environment?.id == environmentId) {
                    mutable.value = if (client.isScreenAccessCurrent(access)) ScreenViewerState(environmentId, target, access)
                    else ScreenViewerState(environmentId, target, error = "Screen session changed. Refresh sessions and try again.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == revision) mutable.value = ScreenViewerState(environmentId, target, error = "Could not open this screen. Refresh sessions or reconnect, then try again.")
            }
        }
    }

    private fun suspendViewer() {
        revision++
        request?.cancel()
        mutable.update { it.copy(access = null, loading = false) }
    }
    fun pause() { paused = true; suspendViewer(); catalogRequest?.cancel() }
    fun resume() {
        paused = false
        val viewer = mutable.value
        if (viewer.target != null && viewer.access == null && !viewer.loading && viewer.error == null && client.state.value.connection == ConnectionStatus.Connected && (client.state.value.canRead || client.state.value.canWrite)) open(viewer.target)
    }
    fun close() {
        revision++
        request?.cancel()
        mutable.value = ScreenViewerState()
    }
}
