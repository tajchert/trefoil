package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The cross-environment list of tracker issues. [entries] are the held rows of the first page and every page after it; the
 * held rows stay visible beside [failure] and [moreFailure]. [loading] means no rows are held yet, [refreshing] means rows
 * are held and a first page is being read again, [loadingMore] a later page. [capped] means [IssuesController.MAX_ROWS] rows
 * are held and "Refine filters to see more" replaces load-more. [live] is false while the client is not connected: the held
 * rows are then a "not live" copy and refresh and pagination are off.
 */
data class IssueListUiState(
    val environmentId: String? = null, val query: IssueQuery = IssueQuery(), val entries: List<IssueSummary> = emptyList(),
    val trackers: List<IssueTrackerStatus> = emptyList(), val errors: List<IssueProjectError> = emptyList(),
    val nextCursors: Map<String, String> = emptyMap(), val scopes: List<IssueScope> = emptyList(),
    val loading: Boolean = false, val loadingMore: Boolean = false, val refreshing: Boolean = false,
    val failure: IssueFailure? = null, val failureMessage: String? = null, val moreFailure: String? = null,
    val capped: Boolean = false, val live: Boolean = true, val loaded: Boolean = false,
) {
    /** Held rows shown without a connection. */
    val stale: Boolean get() = !live && entries.isNotEmpty()
    val canLoadMore: Boolean get() = live && loaded && !loading && !refreshing && !loadingMore && !capped && moreFailure == null && nextCursors.isNotEmpty()
}

/**
 * Loads `issues.list` for the current environment. Memory only: answers are reused per (environment, query) for [FRESH_MILLIS]
 * (at most [MAX_CACHED_QUERIES] of them), [reset] drops everything and a reply that arrives afterwards is ignored. Search text is
 * debounced [DEBOUNCE_MILLIS]; any other filter change cancels the load and starts from page one. A failed more-load stops
 * pagination until [retry]. Reads are never retried automatically, and nothing here logs titles, search text or `detail`.
 */
class IssuesController(
    private val scope: CoroutineScope, private val client: () -> TrefoilClient, state: () -> ClientState = { ClientState() },
    private val now: () -> Long = System::currentTimeMillis, private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    private val clientState = state
    private val mutable = MutableStateFlow(IssueListUiState())
    val state: StateFlow<IssueListUiState> = mutable.asStateFlow()

    private data class CacheKey(val environmentId: String, val query: IssueQuery)
    private class Entry(var entries: List<IssueSummary>, var trackers: List<IssueTrackerStatus>, var errors: List<IssueProjectError>,
        var nextCursors: Map<String, String>, var scopes: List<IssueScope>, var capped: Boolean, var fetchedAt: Long)
    private val cache = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > MAX_CACHED_QUERIES
    }
    private class Remembered(val scopes: List<IssueScope>, val trackers: List<IssueTrackerStatus>)
    private val remembered = HashMap<String, Remembered>()
    private var generation = 0
    private var loadJob: Job? = null
    private var debounceJob: Job? = null

    /** The scopes the last first page of [environmentId] covered, for resolving issue links in chat. Empty before any list loaded. */
    fun scopesFor(environmentId: String): List<IssueScope> = remembered[environmentId]?.scopes.orEmpty()

    /** The trackers (with their hosts) the last first page of [environmentId] reported. */
    fun trackersFor(environmentId: String): List<IssueTrackerStatus> = remembered[environmentId]?.trackers.orEmpty()

    /** The Issues destination opened: shows the held list for this environment, reading it again unless it is fresh. */
    fun open() {
        val environmentId = environmentId()
        val held = mutable.value
        if (environmentId == null) { syncLive(); return }
        if (held.environmentId != null && held.environmentId != environmentId) reset()
        if (loadJob?.isActive == true && mutable.value.environmentId == environmentId) return
        startQuery(mutable.value.query, environmentId)
    }

    /** Applies [query]. Changed search text waits [debounceMillis] for more typing; every other change starts at once. */
    fun setQuery(query: IssueQuery) {
        val next = query.copy(text = query.text.trim().take(MAX_TEXT))
        val held = mutable.value
        if (next == held.query && held.environmentId != null) return
        val textChanged = next.text != held.query.text
        debounceJob?.cancel()
        cancelLoad()
        // Rows of the old query stay visible until the new answer; their cursors belong to the old query and must not be followed.
        mutable.update {
            it.copy(query = next, nextCursors = emptyMap(), loaded = false, loading = false, refreshing = false, loadingMore = false,
                failure = null, failureMessage = null, moreFailure = null)
        }
        val environmentId = environmentId() ?: return
        if (textChanged && debounceMillis > 0) debounceJob = scope.launch { delay(debounceMillis); startQuery(next, environmentId) }
        else startQuery(next, environmentId)
    }

    /** Reads the first page again, skipping the server's cache (`refresh = true`). Does nothing while not live. */
    fun refresh() {
        if (!live()) { syncLive(); return }
        val environmentId = environmentId() ?: return
        debounceJob?.cancel()
        loadFirst(mutable.value.query, environmentId, refresh = true)
    }

    /** Tries the failed step again: the failed more-load, or otherwise the first page. Never runs by itself. */
    fun retry() {
        if (!live()) { syncLive(); return }
        val environmentId = environmentId() ?: return
        val held = mutable.value
        if (held.moreFailure != null && held.entries.isNotEmpty()) { mutable.update { it.copy(moreFailure = null) }; loadMore() }
        else loadFirst(held.query, environmentId, refresh = false)
    }

    /** The next page. Does nothing without cursors, while one is loading, after a failed more-load (until [retry]) or at the row cap. */
    fun loadMore() {
        val held = mutable.value
        if (!held.canLoadMore) { if (!live()) syncLive(); return }
        val environmentId = environmentId() ?: return
        if (held.environmentId != environmentId) return
        val query = held.query
        val requested = held.nextCursors
        val gen = generation
        mutable.update { it.copy(loadingMore = true, moreFailure = null) }
        loadJob = scope.launch {
            try {
                val page = client().issuesList(query, requested, PAGE_SIZE, false)
                ensureActive()
                if (gen != generation) return@launch
                val current = mutable.value
                val (merged, capped) = mergePage(current.entries, page.entries, query.sort, page.nextCursors.isNotEmpty())
                val cursors = (current.nextCursors - requested.keys) + page.nextCursors
                mutable.update { it.copy(entries = merged, nextCursors = cursors, errors = mergeErrors(it.errors, page.errors), capped = capped, loadingMore = false) }
                store(environmentId, query)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen != generation) return@launch
                mutable.update { it.copy(loadingMore = false, moreFailure = message(e)) }
            }
        }
    }

    /** Drops everything: environment switch. A reply that arrives afterwards is ignored. */
    fun reset() {
        debounceJob?.cancel(); cancelLoad()
        cache.clear(); remembered.clear()
        mutable.value = IssueListUiState()
    }

    /** Re-reads the connection flag; call when the client state changes. */
    fun syncLive() {
        val live = live()
        mutable.update { if (it.live == live) it else it.copy(live = live) }
    }

    private fun environmentId() = clientState().environment?.id
    private fun live() = clientState().let { it.environment != null && it.connection == ConnectionStatus.Connected }
    private fun cancelLoad() { generation++; loadJob?.cancel(); loadJob = null }

    /** Shows a fresh held answer as is, an older one while a refresh runs; otherwise starts from nothing. */
    private fun startQuery(query: IssueQuery, environmentId: String) {
        cancelLoad()
        val live = live()
        val cached = cache[CacheKey(environmentId, query)]
        when {
            cached != null -> {
                val fresh = now() - cached.fetchedAt < FRESH_MILLIS
                mutable.value = held(environmentId, query, cached, live).copy(refreshing = !fresh && live)
                if (!fresh && live) loadFirst(query, environmentId, refresh = false)
            }
            !live -> mutable.value = IssueListUiState(environmentId, query, failure = IssueFailure.NotConnected, failureMessage = NOT_CONNECTED_MESSAGE, live = false)
            else -> {
                mutable.value = IssueListUiState(environmentId, query, loading = true)
                loadFirst(query, environmentId, refresh = false)
            }
        }
    }

    private fun held(environmentId: String, query: IssueQuery, entry: Entry, live: Boolean) = IssueListUiState(
        environmentId, query, entry.entries, entry.trackers, entry.errors, entry.nextCursors, entry.scopes, capped = entry.capped, live = live, loaded = true,
    )

    private fun loadFirst(query: IssueQuery, environmentId: String, refresh: Boolean) {
        cancelLoad()
        val gen = generation
        mutable.update {
            it.copy(environmentId = environmentId, query = query, loading = it.entries.isEmpty(), refreshing = it.entries.isNotEmpty(), loadingMore = false,
                failure = null, failureMessage = null, moreFailure = null, live = true)
        }
        loadJob = scope.launch {
            try {
                val page = client().issuesList(query, null, PAGE_SIZE, refresh)
                ensureActive()
                if (gen != generation) return@launch
                val (rows, capped) = mergePage(emptyList(), page.entries, query.sort, page.nextCursors.isNotEmpty())
                cache[CacheKey(environmentId, query)] = Entry(rows, page.trackers, page.errors, page.nextCursors, page.scopes, capped, now())
                remembered[environmentId] = Remembered(page.scopes, page.trackers)
                mutable.value = IssueListUiState(environmentId, query, rows, page.trackers, page.errors, page.nextCursors, page.scopes, capped = capped, loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen != generation) return@launch
                mutable.update {
                    it.copy(loading = false, refreshing = false, loaded = it.entries.isNotEmpty() || it.loaded,
                        failure = (e as? IssueException)?.failure ?: IssueFailure.Failed, failureMessage = message(e))
                }
            }
        }
    }

    private fun store(environmentId: String, query: IssueQuery) {
        val held = mutable.value
        val entry = cache[CacheKey(environmentId, query)]
        if (entry != null && held.environmentId == environmentId && held.query == query) {
            entry.entries = held.entries; entry.nextCursors = held.nextCursors; entry.errors = held.errors; entry.capped = held.capped
        }
    }

    private fun message(e: Exception) = issueListFailureMessage((e as? IssueException)?.failure ?: IssueFailure.Failed, (e as? IssueException)?.retryAtMillis, now())

    companion object {
        const val FRESH_MILLIS = 30_000L
        const val DEBOUNCE_MILLIS = 300L
        const val MAX_ROWS = 1_000
        const val MAX_CACHED_QUERIES = 8
        const val PAGE_SIZE = 50
        const val MAX_TEXT = 200
        const val NOT_CONNECTED_MESSAGE = "Connect to view issues"

        /** The identity of a row across pages: project, tracker, lowercase host and scope, key. */
        internal fun rowKey(ref: IssueRef) = listOf(ref.projectId, ref.tracker, ref.host.orEmpty().trim().lowercase(), ref.scope.trim().lowercase(), ref.key.trim()).joinToString("\u0000")

        /**
         * Adds [incoming] to [held]: a repeated row replaces the held one, rows are ordered by the requested date (newest first),
         * and at [MAX_ROWS] the rest is dropped. Capped when rows were dropped, or the cap is full and [more] pages remain.
         */
        internal fun mergePage(held: List<IssueSummary>, incoming: List<IssueSummary>, sort: IssueSort, more: Boolean): Pair<List<IssueSummary>, Boolean> {
            val byKey = LinkedHashMap<String, IssueSummary>()
            held.forEach { byKey[rowKey(it.ref)] = it }
            incoming.forEach { byKey[rowKey(it.ref)] = it }
            val sorted = byKey.values.sortedByDescending { millis(if (sort == IssueSort.Created) it.createdAt else it.updatedAt) }
            val trimmed = sorted.size > MAX_ROWS
            val kept = if (trimmed) sorted.take(MAX_ROWS) else sorted
            return kept to (trimmed || (kept.size >= MAX_ROWS && more))
        }

        private fun millis(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)

        internal fun mergeErrors(held: List<IssueProjectError>, incoming: List<IssueProjectError>): List<IssueProjectError> =
            (held + incoming).distinctBy { it.projectId + "\u0000" + it.message }

        /** User-facing text for a typed failure. Fixed wording: the server's message may carry tracker content. */
        internal fun issueListFailureMessage(failure: IssueFailure, retryAtMillis: Long?, now: Long): String = when (failure) {
            IssueFailure.NotSupported -> "This T3 server does not support issues."
            IssueFailure.NotConnected -> NOT_CONNECTED_MESSAGE
            IssueFailure.Unavailable -> "The issue trackers are unavailable. Check the trackers in T3 on your computer."
            IssueFailure.NotFound -> "These issues were not found."
            IssueFailure.RateLimited -> {
                val wait = retryAtMillis?.let { ceil((it - now) / 60_000.0).toLong() }
                when {
                    wait == null -> "The tracker is rate limiting requests. Try again later."
                    wait <= 0 -> "The tracker is rate limiting requests. You can try again now."
                    else -> "The tracker is rate limiting requests. Try again in $wait min."
                }
            }
            IssueFailure.ReadOnly -> "Issues are read-only."
            IssueFailure.NoPermission -> "You do not have access to issues on this server."
            IssueFailure.TimedOut -> "The request timed out. Try again."
            IssueFailure.Failed -> "Could not load issues."
        }
    }
}
