package com.tajchert.trefoil.downloads

import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.TrefoilClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One explicit attempt per tap; reconnecting never replays a failed or cancelled download. */
class FileDownloadCoordinator(private val scope: CoroutineScope, private val client: TrefoilClient, private val store: DownloadStore) {
    private val mutable = MutableStateFlow<Map<DownloadKey, FileDownloadState>>(emptyMap())
    val state = mutable.asStateFlow()
    /** In-memory inline image previews; separate from explicit saves to Downloads. */
    val images = InlineImageLoader(client)
    private val jobs = mutableMapOf<DownloadKey, Job>()
    private var nextAttempt = 0L

    private data class ContextKey(val environmentId: String?, val threadId: String?, val connected: Boolean)
    private fun context() = client.state.value.let {
        ContextKey(it.environment?.id, it.selectedThreadId, it.connection == ConnectionStatus.Connected)
    }

    init {
        scope.launch { runCatching { store.cleanupPendingDownloads() } }
        scope.launch {
            client.state.map { ContextKey(it.environment?.id, it.selectedThreadId,
                it.connection == ConnectionStatus.Connected) }.distinctUntilChanged().collect { current ->
                jobs.keys.toList().filter { !current.connected || it.environmentId != current.environmentId || it.threadId != current.threadId }
                    .forEach(::cancelKey)
            }
        }
    }

    fun key(reference: RemoteFileReference): DownloadKey? {
        val current = context()
        return DownloadKey(current.environmentId ?: return null, current.threadId ?: return null, DownloadKey.referenceId(reference))
    }

    fun download(reference: RemoteFileReference, expectedEnvironmentId: String? = null, expectedThreadId: String? = null) {
        val key = key(reference) ?: return
        val snapshot = client.state.value
        if (!context().connected || !(snapshot.canRead || snapshot.canWrite) || expectedEnvironmentId != null && key.environmentId != expectedEnvironmentId ||
            expectedThreadId != null && key.threadId != expectedThreadId) return
        if (mutable.value[key]?.status == DownloadStatus.Downloading) return
        val attempt = ++nextAttempt
        mutable.update { it + (key to FileDownloadState(reference, DownloadStatus.Downloading, totalBytes = reference.sizeBytes, attempt = attempt)) }
        val request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val completed = store.save(reference, { isCurrent(key) }) { output ->
                    currentCoroutineContext().ensureActive()
                    check(isCurrent(key)) { "Download context changed" }
                    client.downloadFile(reference, output, key.environmentId, key.threadId) { progress ->
                        updateAttempt(key, attempt) { it.copy(bytesDownloaded = progress.bytesDownloaded, totalBytes = progress.totalBytes) }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(isCurrent(key))
                updateAttempt(key, attempt) { it.copy(status = DownloadStatus.Complete, bytesDownloaded = completed.sizeBytes,
                    totalBytes = completed.sizeBytes, completed = completed) }
            } catch (cancelled: CancellationException) {
                updateAttempt(key, attempt) { it.copy(status = DownloadStatus.Cancelled, error = "Download cancelled. Tap Retry to download again.") }
                throw cancelled
            } catch (_: Exception) {
                updateAttempt(key, attempt) { it.copy(status = DownloadStatus.Failed, error = "Could not download this file. Reconnect if needed, then tap Retry.") }
            } finally {
                if (mutable.value[key]?.attempt == attempt) jobs.remove(key)
            }
        }
        jobs[key] = request
        request.start()
    }

    fun cancel(reference: RemoteFileReference, expectedEnvironmentId: String? = null, expectedThreadId: String? = null) {
        val key = key(reference) ?: return
        if (expectedEnvironmentId != null && key.environmentId != expectedEnvironmentId || expectedThreadId != null && key.threadId != expectedThreadId) return
        cancelKey(key)
    }

    private fun cancelKey(key: DownloadKey) {
        jobs[key]?.cancel()
        mutable.update { previous -> previous[key]?.takeIf { it.status == DownloadStatus.Downloading }?.let {
            previous + (key to it.copy(status = DownloadStatus.Cancelled, error = "Download cancelled. Tap Retry to download again."))
        } ?: previous }
    }

    private fun isCurrent(key: DownloadKey): Boolean = context().let { it.connected && it.environmentId == key.environmentId && it.threadId == key.threadId }

    private fun updateAttempt(key: DownloadKey, attempt: Long, transform: (FileDownloadState) -> FileDownloadState) {
        mutable.update { previous -> previous[key]?.takeIf { it.attempt == attempt }?.let { previous + (key to transform(it)) } ?: previous }
    }
}
