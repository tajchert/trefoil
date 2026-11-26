package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject

/**
 * The read-only thread inspector: at most one extra `orchestration.subscribeThread` stream, for a side thread (side chat
 * or subagent) of the selected thread, published as [ClientState.inspector]. It never touches the selected thread's
 * state (selection, readiness, Stop, queue, staleness) and never fails the session: an oversized frame sets
 * [InspectorError.TooLarge], a failed refresh or an ended stream [InspectorError.RefreshFailed], and a child the
 * synchronized shell no longer lists [InspectorError.Gone]; each stops only this stream. It sends no writes.
 *
 * Same snapshot/event handling as the selected thread: a snapshot applies at once, each event triggers a conflated
 * `orchestration.getThreadProjection` refetch. The projection goes into the thread cache on its first authoritative
 * snapshot and, latest, when the stream stops, so Maximize (close, then select the child) repaints from memory.
 *
 * Not thread-safe: every method runs under the client's lifecycle lock ([lock]), which the refresh coroutine takes too.
 */
internal class InspectorStream(private val scope: CoroutineScope, private val lock: Any, private val host: Host) {
    /** What the inspector needs from the client; every call is made under [lock]. */
    interface Host {
        /** The connection generation; a handler or refresh of an older one never publishes. */
        val epoch: Long
        /** Sends `orchestration.subscribeThread` for [threadId] and returns its request id (throws when it cannot be sent). */
        fun subscribe(threadId: String, handler: (JsonObject) -> Unit): String
        /** Forgets the stream [requestId] and sends its `Interrupt`, ignoring a dead socket. */
        fun interrupt(requestId: String)
        /** `orchestration.getThreadProjection` for [threadId] on connection [epoch]; called outside [lock]. */
        suspend fun projection(threadId: String, epoch: Long): JsonObject
        fun publish(transform: (InspectorThread?) -> InspectorThread?)
        fun cached(threadId: String): CachedThread?
        /** Stores the child's projection without evicting the selected thread's entry. */
        fun remember(threadId: String, entry: CachedThread)
    }

    private var generation = 0L
    /** Request id of the live stream; null when none is running. */
    var subscription: String? = null
        private set
    /** The child being shown (also after its stream stopped with an error); null once closed or suspended. */
    private var threadId: String? = null
    private var kind = SideThreadKind.SideChat
    private var error: InspectorError? = null
    private var projection = JsonObject(emptyMap())
    /** [projection] came from this stream, and a newer one than the cache holds. */
    private var unsaved = false
    private var saved = false
    private var signals: Channel<Unit>? = null
    private var refreshJob: Job? = null

    /** [threadId] is shown with a running stream. */
    fun isLive(threadId: String): Boolean = this.threadId == threadId && subscription != null

    /** Replaces any inspector with [threadId]: cached rows at once (not ready), then the stream's own. */
    fun open(threadId: String, kind: SideThreadKind) {
        stop()
        this.threadId = threadId; this.kind = kind; error = null; saved = false
        val stream = ++generation
        val epoch = host.epoch
        val cached = host.cached(threadId)
        projection = cached?.projection ?: JsonObject(emptyMap())
        host.publish { cached?.let { inspectorOf(threadId, kind, it.presentation, null) } ?: InspectorThread(threadId, kind) }
        val channel = Channel<Unit>(Channel.CONFLATED)
        signals = channel
        refreshJob = scope.launch {
            for (signal in channel) {
                try {
                    val value = host.projection(threadId, epoch)
                    synchronized(lock) { if (current(stream, epoch)) apply(value, snapshot = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: FrameTooLarge) { synchronized(lock) { if (current(stream, epoch)) failWith(InspectorError.TooLarge) }; break }
                catch (_: Exception) { synchronized(lock) { if (current(stream, epoch)) failWith(InspectorError.RefreshFailed) }; break }
            }
        }
        subscription = try {
            host.subscribe(threadId) { item ->
                if (current(stream, epoch)) when (item.string("kind")) {
                    "snapshot" -> apply(item.obj("projection"), snapshot = true)
                    "event" -> channel.trySend(Unit)
                }
            }
        } catch (failure: Exception) { close(); throw failure }
    }

    /** Stops the stream and clears [ClientState.inspector]. */
    fun close() {
        stop()
        threadId = null
        host.publish { null }
    }

    /** The connection ended: the stream stops and is not resubscribed; the last rows stay, marked not ready. */
    fun suspend() {
        if (threadId == null) return
        stop()
        threadId = null
        host.publish { it?.copy(ready = false) }
    }

    /** The server ended stream [requestId] (Exit). [threads] is the shell's current list. */
    fun ended(requestId: String, threads: List<ThreadSummary>) {
        if (requestId != subscription) return
        subscription = null
        failWith(if (threads.none { it.id == threadId }) InspectorError.Gone else InspectorError.RefreshFailed)
    }

    /** A frame of the stream was too large to parse. */
    fun tooLarge() = failWith(InspectorError.TooLarge)

    /** The synchronized shell changed: a child it no longer lists is gone. */
    fun shellChanged(threads: List<ThreadSummary>) {
        val id = threadId ?: return
        if (error != InspectorError.Gone && threads.none { it.id == id }) failWith(InspectorError.Gone)
    }

    private fun current(stream: Long, epoch: Long) = stream == generation && epoch == host.epoch

    private fun apply(incoming: JsonObject, snapshot: Boolean) {
        val id = threadId ?: return
        val value = retainHydratedActivities(incoming, projection)
        projection = value
        val derived = threadPresentation(value)
        host.publish { previous -> inspectorOf(id, kind, derived, previous?.takeIf { it.threadId == id }).copy(ready = true) }
        if (snapshot && !saved) { host.remember(id, CachedThread(value, derived)); saved = true; unsaved = false } else unsaved = true
    }

    private fun failWith(failure: InspectorError) {
        val id = threadId ?: return
        stop()
        error = failure
        host.publish { previous -> (previous?.takeIf { it.threadId == id } ?: InspectorThread(id, kind)).copy(ready = false, error = failure) }
    }

    /** Ends the running stream (with its `Interrupt`) and its refresh, saving a projection newer than the cache. */
    private fun stop() {
        generation++
        subscription?.let(host::interrupt)
        subscription = null
        signals?.close(); signals = null
        refreshJob?.cancel(); refreshJob = null
        val id = threadId
        if (id != null && unsaved) host.remember(id, CachedThread(projection, threadPresentation(projection)))
        unsaved = false
    }
}

/** The inspector slice of [derived]; rows equal to [previous]'s keep their instances, as the selected thread does. */
internal fun inspectorOf(threadId: String, kind: SideThreadKind, derived: ThreadPresentation, previous: InspectorThread?): InspectorThread =
    InspectorThread(
        threadId = threadId, kind = kind, ready = false, error = null,
        messages = previous?.let { stableRows(it.messages, derived.messages) { row -> row.id } } ?: derived.messages,
        activities = previous?.let { stableRows(it.activities, derived.activities) { row -> row.id } } ?: derived.activities,
        requests = previous?.let { stableValue(it.requests, derived.requests) } ?: derived.requests,
        runs = previous?.let { stableValue(it.runs, derived.runs) } ?: derived.runs,
        attempts = previous?.let { stableValue(it.attempts, derived.attempts) } ?: derived.attempts,
        subagents = previous?.let { stableValue(it.subagents, derived.subagents) } ?: derived.subagents,
        activeRunId = derived.activeRunId,
    )
