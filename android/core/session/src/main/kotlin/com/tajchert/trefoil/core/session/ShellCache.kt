package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The resume cursor of a cached shell: the server build ([serverVersion], [databaseMigration]) its [sequence] belongs
 * to and when its authoritative snapshot was taken. It resumes only under [ShellResume]'s rules.
 */
@Serializable
data class CachedShellCursor(val serverVersion: String, val databaseMigration: Long? = null, val sequence: Long, val snapshotAtMillis: Long)

/**
 * One environment's shell rows as last applied, for showing them at the next app start before the connection is up.
 * [cursor] is null when the rows cannot resume (the next connect loads a full snapshot). Holds thread titles and latest
 * message text: implementations store it encrypted and never log it.
 */
data class CachedShell(
    val environmentId: String,
    val projects: List<Project>,
    val threads: List<ThreadSummary>,
    val cursor: CachedShellCursor?,
    val savedAtMillis: Long,
) {
    override fun toString() = "CachedShell(projects=${projects.size}, threads=${threads.size}, cursor=${cursor != null})"
}

/**
 * When a [CachedShell] was captured: [epoch] is its environment's invalidation count (bumped by every delete) and
 * [revision] a process-wide capture order. See [ShellCacheSequencer].
 */
data class ShellCacheTicket(val epoch: Long, val revision: Long)

/**
 * Process-wide ordering of cache writes, shared by every writer of one store (a view model being cleared and its
 * successor): a write commits only when no delete of its environment came after its capture and no later capture of it
 * was already committed, so a late flush never recreates a forgotten machine's entry or overwrites newer rows. Stores
 * call [mayCommit] and [committed] under the lock that also guards their files.
 */
class ShellCacheSequencer {
    private val lock = Any()
    private var counter = 0L
    private val epochs = mutableMapOf<String, Long>()
    private val committedRevisions = mutableMapOf<String, Long>()

    /** Taken before the rows it covers are read or captured. */
    fun ticket(environmentId: String): ShellCacheTicket = synchronized(lock) {
        ShellCacheTicket(epochs.getOrPut(environmentId) { 0L }, ++counter)
    }

    /** No delete of [environmentId] since [ticket]: a read under it may still be shown. */
    fun valid(environmentId: String, ticket: ShellCacheTicket): Boolean = synchronized(lock) { epochs[environmentId] == ticket.epoch }

    fun mayCommit(environmentId: String, ticket: ShellCacheTicket): Boolean = synchronized(lock) {
        epochs[environmentId] == ticket.epoch && ticket.revision > (committedRevisions[environmentId] ?: 0L)
    }

    fun committed(environmentId: String, ticket: ShellCacheTicket) = synchronized(lock) { committedRevisions[environmentId] = ticket.revision }

    fun invalidate(environmentId: String) = synchronized(lock) { epochs[environmentId] = ++counter; committedRevisions.remove(environmentId); Unit }

    fun invalidateAllExcept(keep: Set<String>) = synchronized(lock) {
        epochs.keys.toList().filter { it !in keep }.forEach { epochs[it] = ++counter; committedRevisions.remove(it) }
    }
}

/**
 * Local storage of [CachedShell]s, one entry per environment (implemented encrypted in the app). One instance per
 * process holds the [ShellCacheSequencer] of its entries. Content never reaches logs, exception messages or file names.
 */
interface ShellCacheStore {
    /** Taken before reading or capturing rows of [environmentId]; see [ShellCacheSequencer.ticket]. */
    fun ticket(environmentId: String): ShellCacheTicket
    /** Whether rows read under [ticket] may still be shown (no delete since). */
    fun valid(environmentId: String, ticket: ShellCacheTicket): Boolean
    /** The stored entry, or null when there is none or it is unusable (corrupt, another format version, another environment). */
    suspend fun read(environmentId: String): CachedShell?
    /**
     * Replaces the entry when [ticket] may still commit ([ShellCacheSequencer.mayCommit]); returns whether it was written.
     * One larger than [ShellCacheCodec.MAX_ENCODED_BYTES] is not written and leaves the older entry in place.
     */
    suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean
    /** Deletes the entry; writes captured before it never commit. */
    suspend fun delete(environmentId: String)
    /** Deletes every entry except those of [environmentIds] (machines forgotten, or a vault replaced), like [delete]. */
    suspend fun retainOnly(environmentIds: Set<String>)
}

/**
 * The versioned file format of a [CachedShell]: JSON `{version, environmentId, savedAtMillis, cursor?, projects, threads}`.
 * Unknown fields are ignored and missing ones take their defaults, so additive model changes keep old files readable;
 * a file of another [VERSION], for another environment, or that does not parse decodes to null.
 */
object ShellCacheCodec {
    /**
     * Bump when a persisted field ([Project], [ThreadSummary] without its `@Transient` fields, [CachedShellCursor]) is
     * added, removed or renamed: a v1 file read by a later build would give a new field its default while keeping the
     * cursor, so the resumed rows would never be corrected. ShellCacheTest's golden field list guards this.
     */
    const val VERSION = 3
    /** Larger snapshots are not cached (about 4 MB of JSON is thousands of threads). */
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    @Serializable
    private class ShellCacheFile(
        val version: Int,
        val environmentId: String,
        val savedAtMillis: Long = 0,
        val cursor: CachedShellCursor? = null,
        val projects: List<Project> = emptyList(),
        val threads: List<ThreadSummary> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** The encoded entry, or null when it exceeds [maxBytes]. */
    fun encode(shell: CachedShell, maxBytes: Int = MAX_ENCODED_BYTES): ByteArray? {
        val bytes = json.encodeToString(ShellCacheFile.serializer(),
            ShellCacheFile(VERSION, shell.environmentId, shell.savedAtMillis, shell.cursor, shell.projects, shell.threads)).encodeToByteArray()
        return bytes.takeIf { it.size <= maxBytes }
    }

    /** Never throws: anything unreadable is null, and no part of the content goes into an exception or log. */
    fun decode(bytes: ByteArray, environmentId: String): CachedShell? = try {
        val element = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        val version = (element?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (element == null || version != VERSION) null else {
            val file = json.decodeFromJsonElement(ShellCacheFile.serializer(), element)
            if (file.environmentId != environmentId) null
            else CachedShell(environmentId, file.projects, file.threads,
                file.cursor?.takeIf { it.sequence >= 0 && it.snapshotAtMillis > 0 && it.serverVersion.isNotBlank() }, file.savedAtMillis)
        }
    } catch (_: Exception) { null }
}

/**
 * Writes shells to [store] off the caller's thread: [changed] schedules a write [delayMillis] later (later changes ride
 * on it, so a busy shell is written at most that often), [flush] writes at once, and [wipe] / [retainOnly] remove
 * entries. Each capture takes a [ShellCacheTicket] first, so the store drops it when a delete or a later capture (from
 * this writer or another on the same store) came first. An entry whose content did not change since this writer wrote or
 * restored it ([markWritten]) is not written again. [snapshot] returns what to store for an environment, or null when it
 * must not be stored (no rows, or the machine is not saved); a scheduled write takes it when it runs, a flush when it is
 * called. After [close] nothing new is captured. Write failures are dropped: the cache is an optimization and never
 * fails the app.
 */
class ShellCacheWriter(
    private val store: ShellCacheStore,
    private val scope: CoroutineScope,
    private val snapshot: (String) -> CachedShell?,
    private val delayMillis: Long = 3_000,
) {
    /** What was last written or restored per environment, compared by row-list identity. */
    private class Written(val projects: List<Project>, val threads: List<ThreadSummary>, val cursor: CachedShellCursor?)

    private val lock = Any()
    private val scheduled = mutableMapOf<String, Job>()
    private val written = mutableMapOf<String, Written>()
    /** The environments of the last [retainOnly]; others are never written afterwards. Null until the first one. */
    private var retained: Set<String>? = null
    private var closed = false

    /** [environmentId]'s rows changed; written within [delayMillis]. */
    fun changed(environmentId: String) = synchronized(lock) {
        if (closed || scheduled[environmentId]?.isActive == true) return@synchronized
        scheduled[environmentId] = scope.launch {
            delay(delayMillis)
            val capture = synchronized(lock) {
                scheduled.remove(environmentId)
                if (closed) null else capture(environmentId)
            }
            capture?.let { (shell, ticket) -> persist(shell, ticket) }
        }
    }

    /** Writes [environmentId]'s current rows now (background, disconnect); returns the write's job. */
    fun flush(environmentId: String): Job {
        val capture = synchronized(lock) {
            scheduled.remove(environmentId)?.cancel()
            if (closed) null else capture(environmentId)
        }
        return scope.launch { capture?.let { (shell, ticket) -> persist(shell, ticket) } }
    }

    /** Flushes [environmentId] (when given), then captures nothing more: the owner is going away. */
    fun close(environmentId: String?): Job {
        val job = environmentId?.let(::flush)
        synchronized(lock) { closed = true; scheduled.values.forEach { it.cancel() }; scheduled.clear() }
        return job ?: Job().apply { complete() }
    }

    /** [shell] is what the store already holds (just read and restored), so it is not written back unchanged. */
    fun markWritten(shell: CachedShell) = synchronized(lock) {
        written[shell.environmentId] = Written(shell.projects, shell.threads, shell.cursor)
    }

    /** Removes [environmentId]'s entry; writes captured before it (scheduled, running, or another writer's) are dropped. */
    suspend fun wipe(environmentId: String) {
        synchronized(lock) { scheduled.remove(environmentId)?.cancel(); written.remove(environmentId) }
        attempt { store.delete(environmentId) }
    }

    /** Removes every entry except those of [environmentIds]. */
    suspend fun retainOnly(environmentIds: Set<String>) {
        synchronized(lock) {
            scheduled.keys.filter { it !in environmentIds }.forEach { scheduled.remove(it)?.cancel() }
            written.keys.retainAll(environmentIds)
            retained = environmentIds.toSet()
        }
        attempt { store.retainOnly(environmentIds) }
    }

    /** The ticket first, then the rows: a delete between them invalidates the ticket. Caller holds [lock]. */
    private fun capture(environmentId: String): Pair<CachedShell, ShellCacheTicket>? {
        if (retained?.contains(environmentId) == false) return null
        val ticket = store.ticket(environmentId)
        return snapshot(environmentId)?.let { it to ticket }
    }

    private suspend fun persist(shell: CachedShell, ticket: ShellCacheTicket) {
        val id = shell.environmentId
        val last = synchronized(lock) { if (retained?.contains(id) == false) return; written[id] }
        if (last != null && last.projects === shell.projects && last.threads === shell.threads && last.cursor == shell.cursor) return
        var stored = false
        if (attempt { stored = store.write(shell, ticket) } && stored)
            synchronized(lock) { written[id] = Written(shell.projects, shell.threads, shell.cursor) }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try { block(); true }
    catch (error: CancellationException) { throw error } catch (_: Exception) { false }
}
