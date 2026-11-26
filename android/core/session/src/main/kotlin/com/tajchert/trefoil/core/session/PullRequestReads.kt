// Adapted from T3 Code (MIT), packages/contracts/src/pullRequest.ts and apps/web/src/components/pullRequest/pullRequestDetail.logic.ts @ 7deb51b550
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PrActor
import com.tajchert.trefoil.core.model.PrAction
import com.tajchert.trefoil.core.model.PrCheck
import com.tajchert.trefoil.core.model.PrCheckStatus
import com.tajchert.trefoil.core.model.PrDetail
import com.tajchert.trefoil.core.model.PrDiffPage
import com.tajchert.trefoil.core.model.PrMergeMethod
import com.tajchert.trefoil.core.model.PrMergeability
import com.tajchert.trefoil.core.model.PrPanelState
import com.tajchert.trefoil.core.model.PrSnapshot
import com.tajchert.trefoil.core.model.PrState
import com.tajchert.trefoil.core.model.PrUnavailableReason
import com.tajchert.trefoil.core.model.PullRequestKey
import com.tajchert.trefoil.core.model.UnifiedDiffParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What [T3Client] gives the pull request layer for one environment. Implementations never retry a request.
 *
 * Failures:
 * - a typed RPC `Fail` (or an HTTP 503 error body on the diff endpoint) throws [PrRpcFailure] with the error object
 *   (`{_tag: PullRequestUnavailableError|PullRequestOperationError, ...}`), a definite rejection;
 * - a request that was provably never sent (not live, stale epoch) throws [PrRequestNotSent];
 * - a timeout, disconnect, oversized reply or Interrupt-only Exit throws
 *   [com.tajchert.trefoil.core.model.UncertainOutcomeException]: the outcome is unknown.
 */
interface PullRequestTransport {
    /** A live session on this environment: connected, synchronized and not stale. */
    fun isLive(): Boolean

    /** One `pullRequests.*` RPC; returns the Exit's success value. */
    suspend fun request(tag: String, payload: JsonObject): JsonElement

    /**
     * `pullRequests.subscribeRefreshes {}`: emits the server's refresh counter. Collecting opens the stream and
     * cancelling the collector interrupts it; an Interrupt-only Exit completes the flow normally.
     */
    fun refreshes(): Flow<Long>

    /** `POST /api/pull-requests/diff` through the authorized client and shared URL policy; returns the JSON body. */
    suspend fun postDiff(body: JsonObject): JsonObject
}

/** A typed `Fail` for [tag]. [body] is the error object; it may hold host text, so never log it. */
class PrRpcFailure(val tag: String, val body: JsonObject) : IllegalStateException("$tag failed") {
    val errorTag: String get() = body.str("_tag").orEmpty()
}

/** The request was never sent, so nothing happened on the server. */
class PrRequestNotSent(message: String = "Reconnect before using this pull request") : IllegalStateException(message)

/** A pull request read failed in a way scoped to that read (oversized page, unreadable reply, unavailable host). */
class PullRequestReadException(message: String) : IllegalStateException(message)

/** Most characters of one PR diff page Trefoil parses. */
const val PR_DIFF_MAX_PAGE_CHARS = 4_000_000

/**
 * Per-environment pull request reads: an in-memory stale-while-revalidate cache of [PrPanelState] (at most
 * [maxEntries], least recently used evicted first), at most one `detail` (+ `checks`) read in flight per PR, and the
 * `pullRequests.subscribeRefreshes` stream, open only while some [panel] flow is collected and the session is live.
 *
 * [T3Client] calls [sessionLive] when the session becomes live, [sessionLost] on disconnect, and [forget] when the
 * environment is forgotten or switched. Nothing is persisted or logged.
 */
class PullRequestReads(
    private val transport: PullRequestTransport,
    private val scope: CoroutineScope,
    private val checksSupported: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxEntries: Int = 24,
) {
    private class Entry(val flow: MutableStateFlow<PrPanelState>) {
        /** One read-only view per entry, so repeated [panel] calls hand back the same instance. */
        val view: StateFlow<PrPanelState> = flow.asStateFlow()
        var read: Job? = null
        var token = 0L
        var again = false
        var againAllowStale = true
        var watcher: Job? = null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<PullRequestKey, Entry>(16, 0.75f, true)
    private var stream: Job? = null
    private var generation = 0L

    /**
     * The panel for [key]: [snapshot] (when given) over any cached detail, then a fresh `detail` read when live.
     * The returned flow keeps updating on refresh ticks while collected. Asked again while it is collected (a
     * recomposition), it returns the same flow without another read.
     */
    fun panel(key: PullRequestKey, snapshot: PrSnapshot? = null): StateFlow<PrPanelState> {
        val (entry, watched) = synchronized(lock) {
            val entry = entryLocked(key)
            if (snapshot != null && entry.flow.value.snapshot != snapshot) entry.flow.update { it.copy(snapshot = snapshot) }
            entry to (entry.flow.subscriptionCount.value > 0)
        }
        if (!watched) refresh(key)
        return entry.view
    }

    /** Current panel of [key], if cached. */
    fun current(key: PullRequestKey): PrPanelState? = synchronized(lock) { entries[key]?.flow?.value }

    /**
     * Revalidates [key] when live (no-op otherwise). [allowStale] false forces a fresh host read, used only after a
     * mutation. A read already in flight is joined by one follow-up read instead of a parallel one.
     */
    fun refresh(key: PullRequestKey, allowStale: Boolean = true) {
        if (!transport.isLive()) return
        synchronized(lock) {
            val entry = entryLocked(key)
            if (entry.read?.isActive == true) {
                entry.again = true
                entry.againAllowStale = entry.againAllowStale && allowStale
                return
            }
            startReadLocked(key, entry, allowStale)
        }
    }

    /**
     * Reads [key] now with `allowStale:false` and returns the detail that read itself produced, or null when it failed,
     * was cancelled (session lost, forgotten) or superseded: a cached pre-mutation detail is never mistaken for it.
     */
    internal suspend fun reconcileRead(key: PullRequestKey): PrDetail? {
        if (!transport.isLive()) return null
        val result = CompletableDeferred<PrDetail?>()
        val job = synchronized(lock) {
            val entry = entryLocked(key)
            entry.read?.cancel()
            entry.again = false
            startReadLocked(key, entry, allowStale = false, result)
        }
        job.invokeOnCompletion { result.complete(null) }
        return result.await()
    }

    /** Applies [transform] to [key]'s panel, creating it if needed (pending actions). */
    internal fun updatePanel(key: PullRequestKey, transform: (PrPanelState) -> PrPanelState) {
        synchronized(lock) { entryLocked(key).flow.update(transform) }
    }

    /** The session became live: reopen the refresh stream if panels are watched and revalidate them. */
    fun sessionLive() {
        val watched = synchronized(lock) { entries.filterValues { it.flow.subscriptionCount.value > 0 }.keys.toList() }
        updateStream()
        watched.forEach { refresh(it) }
    }

    /** The session ended: close the stream and drop in-flight reads; cached panels stay for the stale view. */
    fun sessionLost() {
        synchronized(lock) {
            generation++
            stream?.cancel(); stream = null
            entries.values.forEach { entry ->
                entry.read?.cancel(); entry.read = null; entry.again = false
                entry.flow.update { it.copy(refreshing = false) }
            }
        }
    }

    /** The environment was forgotten: drop every panel, read and the stream. */
    fun forget() {
        synchronized(lock) {
            generation++
            stream?.cancel(); stream = null
            entries.values.forEach { entry ->
                entry.read?.cancel(); entry.watcher?.cancel()
                entry.flow.value = PrPanelState()
            }
            entries.clear()
        }
    }

    /** True while the refresh stream is open (tests and idle checks). */
    val streamOpen: Boolean get() = synchronized(lock) { stream?.isActive == true }

    /** One page of the PR diff, parsed off the main thread. Pages over [PR_DIFF_MAX_PAGE_CHARS] fail only this read. */
    suspend fun loadDiff(key: PullRequestKey, cursor: String?): PrDiffPage {
        if (!transport.isLive()) throw PrRequestNotSent("Reconnect to load the pull request diff")
        val body = buildJsonObject {
            putRef(key)
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        }
        val reply = try { transport.postDiff(body) } catch (failure: PrRpcFailure) { throw PullRequestReadException(readFailureMessage(failure)) }
        val patch = (reply["patch"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw PullRequestReadException("The server sent an unreadable pull request diff")
        if (patch.length > PR_DIFF_MAX_PAGE_CHARS) throw PullRequestReadException("This part of the pull request diff is too large for mobile")
        val truncated = reply.bool("truncated") == true
        val next = reply.str("nextCursor")?.takeIf { it.isNotBlank() }
        val document = withContext(parseDispatcher) { UnifiedDiffParser.parse(patch) }
        return PrDiffPage(document, next, truncated)
    }

    private fun entryLocked(key: PullRequestKey): Entry {
        entries[key]?.let { return it }
        val entry = Entry(MutableStateFlow(PrPanelState()))
        entries[key] = entry
        entry.watcher = scope.launch { entry.flow.subscriptionCount.collect { updateStream() } }
        evictLocked()
        return entry
    }

    private fun evictLocked() {
        if (entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            val (_, entry) = iterator.next()
            val value = entry.flow.value
            if (entry.flow.subscriptionCount.value > 0 || value.pendingAction?.blocksActions == true) continue
            entry.read?.cancel(); entry.watcher?.cancel()
            iterator.remove()
        }
    }

    private fun startReadLocked(key: PullRequestKey, entry: Entry, allowStale: Boolean, result: CompletableDeferred<PrDetail?>? = null): Job {
        val epoch = generation
        val token = ++entry.token
        entry.flow.update { it.copy(refreshing = true) }
        val job = scope.launch { read(key, entry, allowStale, epoch, token, result) }
        if (entry.token == token && job.isActive) entry.read = job
        return job
    }

    private suspend fun read(key: PullRequestKey, entry: Entry, allowStale: Boolean, epoch: Long, token: Long, result: CompletableDeferred<PrDetail?>?) {
        val ref = buildJsonObject { putRef(key); if (!allowStale) put("allowStale", false) }
        val outcome = try {
            val detail = decodePullRequestDetail(transport.request("pullRequests.detail", ref), entry.flow.value.snapshot)
                ?: throw PullRequestReadException("The server sent an unreadable pull request")
            ReadOutcome.Fresh(detail, if (checksSupported()) readChecks(ref) else ChecksReply.Unchanged, now())
        } catch (error: CancellationException) {
            throw error
        } catch (failure: PrRpcFailure) {
            if (failure.errorTag == UNAVAILABLE_TAG) ReadOutcome.Unavailable(PrUnavailableReason.fromWire(failure.body.str("reason")))
            else ReadOutcome.Failed(readFailureMessage(failure), answered = true)
        } catch (error: PullRequestReadException) {
            ReadOutcome.Failed(error.message ?: GENERIC_READ_ERROR, answered = true)
        } catch (_: Exception) {
            ReadOutcome.Failed("Couldn't refresh this pull request", answered = false)
        }
        synchronized(lock) {
            if (epoch != generation || entries[key] !== entry || entry.token != token) return
            entry.flow.update { state -> outcome.applyTo(state).copy(refreshing = false) }
            result?.complete((outcome as? ReadOutcome.Fresh)?.detail)
            entry.read = null
            if (entry.again && transport.isLive()) {
                val stale = entry.againAllowStale
                entry.again = false; entry.againAllowStale = true
                startReadLocked(key, entry, stale)
            }
        }
    }

    private sealed interface ChecksReply {
        data object Unchanged : ChecksReply
        data class Value(val checks: List<PrCheck>?) : ChecksReply
    }

    private sealed interface ReadOutcome {
        fun applyTo(state: PrPanelState): PrPanelState

        data class Fresh(val detail: PrDetail, val checks: ChecksReply, val stamp: Long) : ReadOutcome {
            override fun applyTo(state: PrPanelState) = state.copy(
                detail = detail, answered = true, error = null, unavailableReason = null, lastUpdatedMillis = stamp,
                checks = if (checks is ChecksReply.Value) checks.checks else state.checks,
            )
        }

        data class Unavailable(val reason: PrUnavailableReason) : ReadOutcome {
            override fun applyTo(state: PrPanelState) = state.copy(answered = true, error = null, unavailableReason = reason)
        }

        data class Failed(val message: String, val answered: Boolean) : ReadOutcome {
            override fun applyTo(state: PrPanelState) = state.copy(answered = state.answered || answered, error = message)
        }
    }

    /** A failed checks read keeps the previous checks; the detail read decides the panel's error. */
    private suspend fun readChecks(ref: JsonObject): ChecksReply = try {
        ChecksReply.Value(decodePullRequestChecks(transport.request("pullRequests.checks", ref)))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ChecksReply.Unchanged
    }

    private fun updateStream() {
        synchronized(lock) {
            val watched = entries.values.any { it.flow.subscriptionCount.value > 0 }
            if (watched && transport.isLive()) {
                if (stream?.isActive == true) return
                val epoch = generation
                stream = scope.launch {
                    try {
                        transport.refreshes().collect { if (epoch == generation) tick() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The stream is optional; panels still revalidate on open and on refresh.
                    }
                }
            } else if (!watched) {
                stream?.cancel(); stream = null
            }
        }
    }

    private fun tick() {
        val watched = synchronized(lock) { entries.filterValues { it.flow.subscriptionCount.value > 0 }.keys.toList() }
        watched.forEach { refresh(it) }
    }

    internal companion object {
        const val UNAVAILABLE_TAG = "PullRequestUnavailableError"
        const val OPERATION_TAG = "PullRequestOperationError"
        const val GENERIC_READ_ERROR = "Couldn't load this pull request"

        fun readFailureMessage(failure: PrRpcFailure): String = when {
            failure.errorTag == UNAVAILABLE_TAG -> unavailableMessage(PrUnavailableReason.fromWire(failure.body.str("reason")))
            failure.errorTag == OPERATION_TAG && failure.body.str("reason") == "not-found" -> "Pull request not found"
            else -> GENERIC_READ_ERROR
        }

        fun unavailableMessage(reason: PrUnavailableReason): String = when (reason) {
            PrUnavailableReason.CliMissing -> "The server has no code host credential for pull requests"
            PrUnavailableReason.CliUnauthenticated -> "The server's code host sign-in was refused"
            PrUnavailableReason.ProviderUnsupported -> "This project's code host does not support pull requests"
            PrUnavailableReason.RepositoryWithoutProject -> "No project on the server has this repository"
            PrUnavailableReason.Other -> "Pull requests are unavailable on this server"
        }
    }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putRef(key: PullRequestKey) {
    put("projectId", key.projectId)
    key.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
    put("repository", key.repository)
    put("number", key.number)
}

/**
 * Maps upstream `PullRequestDetail` leniently. Actions are `capabilities.actions ∧ viewerPermissions.actions`, merge
 * methods `capabilities.mergeMethods` allowed by `mergeCapabilities`, and comments `capabilities.comment ∧
 * viewerPermissions.comment`; an unreported permission is granted. Returns null without a recognisable state.
 */
internal fun decodePullRequestDetail(element: JsonElement, snapshot: PrSnapshot?): PrDetail? {
    val obj = element as? JsonObject ?: return null
    val state = PrState.fromWire(obj.str("state")) ?: return null
    val caps = obj["capabilities"] as? JsonObject
    val perms = obj["viewerPermissions"] as? JsonObject
    val permitted = perms?.strings("actions")?.toSet()
    val actions = caps?.strings("actions").orEmpty()
        .filter { permitted == null || it in permitted }
        .mapNotNull(PrAction::fromWire).toSet()
    val mergeCaps = obj["mergeCapabilities"] as? JsonObject
    val mergeMethods = caps?.strings("mergeMethods").orEmpty()
        .filter { mergeCaps?.bool(it) == true }
        .mapNotNull(PrMergeMethod::fromWire).distinct()
    return PrDetail(
        title = obj.str("title") ?: snapshot?.title.orEmpty(),
        url = obj.str("url").orEmpty(),
        state = state,
        isDraft = obj.bool("isDraft") ?: snapshot?.isDraft ?: false,
        headRef = obj.str("headBranch") ?: snapshot?.headRef.orEmpty(),
        baseRef = obj.str("baseBranch") ?: snapshot?.baseRef.orEmpty(),
        headSha = obj.str("headSha")?.takeIf { it.isNotBlank() },
        mergeability = PrMergeability.fromWire(obj.str("mergeability")),
        rebaseRequired = obj.bool("rebaseRequired") ?: false,
        behindBy = obj.int("behindBy"),
        reviewDecision = snapshot?.reviewDecision,
        author = (obj["author"] as? JsonObject)?.let(::decodeActor) ?: snapshot?.author,
        updatedAt = obj.str("updatedAt") ?: snapshot?.updatedAt,
        additions = obj.int("additions") ?: snapshot?.additions ?: 0,
        deletions = obj.int("deletions") ?: snapshot?.deletions ?: 0,
        changedFiles = obj.int("changedFiles") ?: snapshot?.changedFiles ?: 0,
        checks = decodeCheckList(obj["checks"]),
        actions = actions,
        mergeMethods = mergeMethods,
        canComment = caps?.bool("comment") == true && perms?.bool("comment") != false,
        canViewDiff = caps?.bool("diff") == true,
    )
}

/** Upstream `NullOr(PullRequestChecks)`: null for a `null` reply. */
internal fun decodePullRequestChecks(element: JsonElement): List<PrCheck>? {
    val obj = element as? JsonObject ?: return null
    return decodeCheckList(obj["checks"])
}

private fun decodeCheckList(element: JsonElement?): List<PrCheck> =
    (element as? JsonArray)?.mapNotNull { item ->
        val check = item as? JsonObject ?: return@mapNotNull null
        val name = check.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        PrCheck(
            name = name,
            status = PrCheckStatus.fromWire(check.str("status")),
            description = check.str("description"),
            url = check.str("url"),
            required = check.bool("required") == true,
            placeholder = check.bool("placeholder") == true,
        )
    }.orEmpty()

private fun decodeActor(obj: JsonObject): PrActor? {
    val login = obj.str("login")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PrActor(login = login, name = obj.str("name"), isBot = obj.bool("isBot") == true)
}

internal fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull
private fun JsonObject.strings(key: String): List<String>? =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
