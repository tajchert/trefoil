package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlin.math.ceil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Detail view of one tracker issue. [failure] and [failureMessage] describe the last failed load or refresh; a held [detail]
 * stays visible beside them. [live] is false while the client is not connected: reads are then disabled and a held detail
 * is a "not live" copy.
 */
data class IssueDetailUiState(
    val environmentId: String? = null, val ref: IssueRef? = null, val detail: IssueDetail? = null,
    val loading: Boolean = false, val refreshing: Boolean = false, val loadingOlder: Boolean = false,
    val failure: IssueFailure? = null, val failureMessage: String? = null, val olderFailure: String? = null,
    val linkedThreads: List<IssueLinkedThread> = emptyList(), val linkedThreadsSupported: Boolean = false,
    val live: Boolean = true, val commentsCapped: Boolean = false,
)

/**
 * Loads one issue detail at a time, with its comment pages and the threads that link it. Memory only: details are cached
 * per environment and ref for [FRESH_MILLIS], at most [MAX_CACHED_DETAILS] of them (LRU), and [reset] drops everything.
 * Reads are never retried automatically, and nothing here logs titles, bodies, comments or URLs.
 */
class IssueDetailController(
    private val scope: CoroutineScope, private val client: () -> TrefoilClient, state: () -> ClientState,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val clientState = state
    private val mutable = MutableStateFlow(IssueDetailUiState())
    val state: StateFlow<IssueDetailUiState> = mutable.asStateFlow()
    private data class CacheKey(val environmentId: String, val ref: IssueRef)
    private class Entry(var detail: IssueDetail, var capped: Boolean, var linkedThreads: List<IssueLinkedThread>, var fetchedAt: Long)
    private val cache = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > MAX_CACHED_DETAILS
    }
    private var target: Triple<String, IssueRef, IssueKey>? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var olderJob: Job? = null
    private var linkedJob: Job? = null

    /** Opens [ref]. A cached detail younger than 30 s is shown as is; an older one is shown while a refresh runs. */
    fun open(environmentId: String, ref: IssueRef, key: IssueKey) {
        val same = target?.let { it.first == environmentId && it.second == ref } == true
        if (same && loadJob?.isActive == true) return
        cancelJobs()
        target = Triple(environmentId, ref, key)
        val supported = linkedSupported()
        val entry = cache[CacheKey(environmentId, ref)]
        val live = live()
        when {
            entry != null -> {
                val fresh = now() - entry.fetchedAt < FRESH_MILLIS
                mutable.value = IssueDetailUiState(
                    environmentId, ref, entry.detail, refreshing = !fresh && live, linkedThreads = entry.linkedThreads,
                    linkedThreadsSupported = supported, live = live, commentsCapped = entry.capped,
                )
                if (!fresh && live) load()
            }
            !live -> mutable.value = IssueDetailUiState(
                environmentId, ref, failure = IssueFailure.NotConnected, failureMessage = NOT_CONNECTED_MESSAGE,
                linkedThreadsSupported = supported, live = false,
            )
            else -> {
                mutable.value = IssueDetailUiState(environmentId, ref, loading = true, linkedThreadsSupported = supported, live = true)
                load()
            }
        }
    }

    /** Reads the detail again, skipping the cache. Does nothing while not live. */
    fun refresh() { if (target != null && live()) load() else syncLive() }

    /** Tries a failed load again. Never runs by itself. */
    fun retry() { if (target != null && live()) load() else syncLive() }

    /** Prepends the next older comment page, deduped by id; at [MAX_COMMENTS] held comments the list is capped. */
    fun loadOlderComments() {
        val (environmentId, ref, _) = target ?: return
        val current = mutable.value
        val detail = current.detail ?: return
        val cursor = detail.commentsCursor ?: return
        if (!live() || current.loadingOlder || current.commentsCapped) { syncLive(); return }
        val gen = generation
        olderJob?.cancel()
        mutable.update { it.copy(loadingOlder = true, olderFailure = null, live = true) }
        olderJob = scope.launch {
            try {
                val page = client().issueComments(ref, cursor)
                ensureActive()
                if (gen != generation) return@launch
                val held = mutable.value.detail ?: return@launch
                val (merged, capped) = mergeOlderComments(held.comments, page.comments, page.nextCursor)
                val next = held.copy(comments = merged, commentsCursor = page.nextCursor)
                cache[CacheKey(environmentId, ref)]?.let { it.detail = next; it.capped = capped }
                mutable.update { it.copy(detail = next, loadingOlder = false, olderFailure = null, commentsCapped = capped) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen != generation) return@launch
                mutable.update { it.copy(loadingOlder = false, olderFailure = failureMessage(e)) }
            }
        }
    }

    /** Drops everything: environment switch. A reply that arrives afterwards is ignored. */
    fun reset() {
        cancelJobs()
        target = null
        cache.clear()
        mutable.value = IssueDetailUiState()
    }

    /** Reads who links this issue again, for example after a link or unlink succeeded. */
    fun refreshLinkedThreads() {
        val (environmentId, ref, key) = target ?: return
        syncLive()
        if (!linkedSupported() || !live()) return
        val gen = generation
        linkedJob?.cancel()
        linkedJob = scope.launch { fetchLinked(gen, environmentId, ref, key) }
    }

    /** Re-reads the connection flag and the linked-threads capability; call when the client state changes. */
    fun syncLive() {
        if (target == null) return
        val live = live()
        val supported = linkedSupported()
        mutable.update { if (it.live == live && it.linkedThreadsSupported == supported) it else it.copy(live = live, linkedThreadsSupported = supported) }
    }

    private fun live() = clientState().connection == ConnectionStatus.Connected
    private fun linkedSupported() = clientState().issueLinkedThreadsSupported
    private fun cancelJobs() {
        generation++
        loadJob?.cancel(); olderJob?.cancel(); linkedJob?.cancel()
        loadJob = null; olderJob = null; linkedJob = null
    }

    private fun load() {
        val (environmentId, ref, key) = target ?: return
        loadJob?.cancel(); olderJob?.cancel(); linkedJob?.cancel()
        generation++
        val gen = generation
        val supported = linkedSupported()
        mutable.update {
            it.copy(loading = it.detail == null, refreshing = it.detail != null, loadingOlder = false, failure = null, failureMessage = null,
                olderFailure = null, linkedThreadsSupported = supported, live = true)
        }
        loadJob = scope.launch {
            try {
                val fetched = client().issueDetail(ref)
                ensureActive()
                if (gen != generation) return@launch
                val (comments, capped) = capComments(fetched.comments, fetched.commentsCursor)
                val detail = fetched.copy(comments = comments)
                val entry = cache[CacheKey(environmentId, ref)]
                val linked = entry?.linkedThreads ?: emptyList()
                cache[CacheKey(environmentId, ref)] = Entry(detail, capped, linked, now())
                mutable.update {
                    it.copy(detail = detail, loading = false, refreshing = false, failure = null, failureMessage = null, commentsCapped = capped)
                }
                if (supported) fetchLinked(gen, environmentId, ref, key)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen != generation) return@launch
                mutable.update {
                    it.copy(loading = false, refreshing = false, failure = (e as? IssueException)?.failure ?: IssueFailure.Failed, failureMessage = failureMessage(e))
                }
            }
        }
    }

    private suspend fun fetchLinked(gen: Int, environmentId: String, ref: IssueRef, key: IssueKey) {
        try {
            val threads = client().issueLinkedThreads(key)
            currentCoroutineContext().ensureActive()
            if (gen != generation) return
            cache[CacheKey(environmentId, ref)]?.linkedThreads = threads
            mutable.update { it.copy(linkedThreads = threads) }
        } catch (e: CancellationException) { throw e }
        // The detail stays usable without the linked threads; keep what was held.
        catch (e: Exception) { }
    }

    private fun failureMessage(e: Exception): String = issueFailureMessage((e as? IssueException)?.failure ?: IssueFailure.Failed, (e as? IssueException)?.retryAtMillis, now())

    companion object {
        const val FRESH_MILLIS = 30_000L
        const val MAX_CACHED_DETAILS = 8
        const val MAX_COMMENTS = 500
        const val NOT_CONNECTED_MESSAGE = "Connect to view this issue"

        /** Keeps the newest [MAX_COMMENTS]; capped when comments were dropped, or older ones remain and the cap is full. */
        internal fun capComments(comments: List<IssueComment>, cursor: String?): Pair<List<IssueComment>, Boolean> {
            val trimmed = comments.size > MAX_COMMENTS
            val kept = if (trimmed) comments.takeLast(MAX_COMMENTS) else comments
            return kept to (trimmed || (kept.size >= MAX_COMMENTS && cursor != null))
        }

        /** Prepends [older] to [held], dropping comments already held (by id), then applies the cap. */
        internal fun mergeOlderComments(held: List<IssueComment>, older: List<IssueComment>, cursor: String?): Pair<List<IssueComment>, Boolean> {
            val seen = held.mapTo(HashSet()) { it.id }
            val fresh = older.filter { seen.add(it.id) }
            return capComments(fresh + held, cursor)
        }

        /** User-facing text for a typed failure. Fixed wording: the server's message may carry issue content. */
        internal fun issueFailureMessage(failure: IssueFailure, retryAtMillis: Long?, now: Long): String = when (failure) {
            IssueFailure.NotSupported -> "This T3 server does not support issues."
            IssueFailure.NotConnected -> NOT_CONNECTED_MESSAGE
            IssueFailure.Unavailable -> "The issue tracker is unavailable. Check the tracker in T3 on your computer."
            IssueFailure.NotFound -> "This issue was not found. It may have been deleted or moved."
            IssueFailure.RateLimited -> {
                val wait = retryAtMillis?.let { ceil((it - now) / 60_000.0).toLong() }
                when {
                    wait == null -> "The tracker is rate limiting requests. Try again later."
                    wait <= 0 -> "The tracker is rate limiting requests. You can try again now."
                    else -> "The tracker is rate limiting requests. Try again in $wait min."
                }
            }
            IssueFailure.ReadOnly -> "This issue is read-only."
            IssueFailure.NoPermission -> "You do not have access to this issue."
            IssueFailure.TimedOut -> "The request timed out. Try again."
            IssueFailure.Failed -> "Could not load this issue."
        }
    }
}
