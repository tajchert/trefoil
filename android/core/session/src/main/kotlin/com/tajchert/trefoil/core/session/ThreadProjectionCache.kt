package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.JsonObject

/**
 * Everything the client derives from one thread projection for [ClientState]. Kept with the projection so a revisited
 * thread repaints from memory without re-parsing its JSON on the selecting (main) thread. Connection-dependent facts
 * (Steer, Stop, the catalog model) are applied when it is shown, never cached as live.
 */
internal data class ThreadPresentation(
    val messages: List<ConversationMessage>,
    val activities: List<ToolActivity>,
    val requests: List<RuntimeRequest>,
    val activeRunId: String?,
    val runs: List<ConversationRun>,
    val attempts: List<RunAttempt>,
    val subagents: List<Subagent>,
    val backgroundWork: List<PendingBackgroundTask>,
    val queuedMessages: List<QueuedMessage>,
    val queueHeld: Boolean,
    /** Steer/Stop as the projection allows them; shown only while connected. */
    val steerable: Boolean,
    val stoppable: Boolean,
    val runtimeMode: RuntimeMode?,
    val modelSelection: JsonObject?,
    val modelOptions: ModelOptionValues,
    val providerLocked: Boolean,
) {
    /** The rows this thread holds in memory: what the cache budget counts. */
    val rows: Int get() = messages.size + activities.size
}

internal class CachedThread(val projection: JsonObject, val presentation: ThreadPresentation)

/**
 * Recently viewed threads, keyed by (environment id, thread id). Memory only: nothing here is written to disk. Bounded by
 * [maxThreads] and by [maxRows] in total; the most recently stored thread is always kept (it is the one on screen, held in
 * memory anyway), older ones are evicted least recently used first. A projection is already capped by the oversized-frame
 * containment (`EffectRpc.MAX_FRAME_CHARS`), so the row budget bounds what the cache adds on top of the open thread.
 * Not thread-safe: the client guards it with its lifecycle lock.
 */
internal class ThreadProjectionCache(private val maxThreads: Int = 12, private val maxRows: Int = 6_000) {
    private val entries = linkedMapOf<Pair<String, String>, CachedThread>()

    operator fun get(key: Pair<String, String>): CachedThread? = entries[key]

    /** Stores [entry] as the most recently used and trims the rest to the budget. */
    fun put(key: Pair<String, String>, entry: CachedThread) {
        entries.remove(key)
        entries[key] = entry
        var rows = entries.values.sumOf { it.presentation.rows }
        while (entries.size > 1 && (entries.size > maxThreads || rows > maxRows)) {
            val eldest = entries.keys.first()
            rows -= entries.remove(eldest)!!.presentation.rows
        }
    }

    fun forgetEnvironment(environmentId: String?) { entries.keys.removeAll { it.first == environmentId } }

    fun clear() = entries.clear()

    val keys: Set<Pair<String, String>> get() = entries.keys
}

/**
 * [next] with every element that equals one in [previous] (matched by [id]) replaced by that previous instance, and
 * [previous] itself when the lists are equal. A refreshed projection rebuilds every row; keeping unchanged rows' identity
 * lets Compose skip them (strong skipping compares unstable parameters by instance).
 */
internal fun <T : Any> stableRows(previous: List<T>, next: List<T>, id: (T) -> Any?): List<T> {
    if (previous === next || previous.isEmpty() || next.isEmpty()) return next
    if (previous == next) return previous
    val byId = HashMap<Any?, T>(previous.size * 2)
    previous.forEach { byId[id(it)] = it }
    var reused = false
    val merged = next.map { row -> byId[id(row)]?.takeIf { it == row }?.also { reused = true } ?: row }
    return if (reused) merged else next
}

/** [next] when it differs from [previous], else [previous]'s instance. */
internal fun <T> stableValue(previous: T, next: T): T = if (previous == next) previous else next
