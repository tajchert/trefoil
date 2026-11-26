package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.ConcurrentHashMap

class ShellCacheTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    /**
     * Every field the cache file persists. When this changes, bump [ShellCacheCodec.VERSION] (old files would otherwise
     * read the new field as its default while keeping their resume cursor, so the rows would never be corrected) and
     * then update the list and the expected version together.
     */
    @Test fun persistedFieldsChangeOnlyWithTheFormatVersion() {
        val persisted = mapOf(
            "Project" to Project.serializer().descriptor.elementNames.toList(),
            "ThreadSummary" to ThreadSummary.serializer().descriptor.elementNames.toList(),
            "CachedShellCursor" to CachedShellCursor.serializer().descriptor.elementNames.toList(),
        )
        assertEquals("Bump ShellCacheCodec.VERSION when the persisted fields change", 3, ShellCacheCodec.VERSION)
        assertEquals("Persisted fields changed: bump ShellCacheCodec.VERSION, then update this list", mapOf(
            "Project" to listOf("id", "title", "path"),
            "ThreadSummary" to listOf("id", "projectId", "title", "status", "createdAt", "updatedAt", "latestUserMessageAt",
                "latestUserAuthoredMessageAt", "archivedAt", "archived", "pinnedAt", "settledAt", "settledOverride", "activeOrderKey",
                "pinOrderKey", "pendingRequestKind", "latestMessage", "branch", "model", "blockedOnThreadWait", "activityRunStatus",
                "snoozedUntil", "unsettledAt", "latestRunRequestedAt", "latestRunCompletedAt", "latestRunStartedAt",
                "activityRunStartedAt", "activityRunStartedAtPresent", "latestRunId", "activeRunId", "providerInstanceId",
                "modelInstanceId", "providerInstanceHistory", "parentThreadId", "relationshipToParent", "sideChat", "creationSource",
                "deletedAt", "activeProviderThreadId", "activityRunStatusPresent", "snoozedAt", "pendingRequestId",
                "pendingRequestCreatedAt", "pendingRequestBlocking", "pendingGuestMessageCount", "interactionMode",
                "hasActionableProposedPlan", "latestUserAuthoredMessageAtPresent", "completionHeldBackgroundWork",
                "latestRunStopRequested", "latestRunBackgroundWake", "pendingRequestUserOnly", "workflowStep", "monitorHoldsWakeCompletion", "issues"),
            "CachedShellCursor" to listOf("serverVersion", "databaseMigration", "sequence", "snapshotAtMillis"),
        ), persisted)
    }

    private val projects = listOf(Project("p", "Project", "/w/p"))
    private val threads = listOf(
        ThreadSummary("a", "p", "Fix the login", status = "running", latestMessage = "Working on it", pinnedAt = "2026-01-01T00:00:00Z",
            providerInstanceHistory = listOf("codex"), pendingChildRequestKind = "approval"),
        ThreadSummary("b", "p", "Second"),
    )
    private val cursor = CachedShellCursor("1.2.3", 40, 12, 1_000)
    private val shell = CachedShell("env", projects, threads, cursor, 2_000)

    @Test fun roundTripKeepsRowsAndCursorButNotDerivedFields() {
        val decoded = ShellCacheCodec.decode(ShellCacheCodec.encode(shell)!!, "env")!!
        assertEquals(projects, decoded.projects)
        assertEquals(threads.map { it.copy(pendingChildRequestKind = null) }, decoded.threads)
        assertEquals(cursor, decoded.cursor)
        assertEquals(2_000L, decoded.savedAtMillis)
        assertFalse("Derived presentation state is never stored", ShellCacheCodec.encode(shell)!!.decodeToString().contains("approval"))
    }

    @Test fun unknownFieldsAreIgnoredAndMissingOnesDefault() {
        val text = """{"version":${ShellCacheCodec.VERSION},"environmentId":"env","future":{"x":1},"projects":[{"id":"p","title":"P","extra":true}],
            "threads":[{"id":"a","projectId":"p","title":"T","newField":"v"}]}"""
        val decoded = ShellCacheCodec.decode(text.encodeToByteArray(), "env")!!
        assertEquals(listOf(Project("p", "P")), decoded.projects)
        assertEquals(listOf(ThreadSummary("a", "p", "T")), decoded.threads)
        assertNull(decoded.cursor)
        assertEquals(0L, decoded.savedAtMillis)
    }

    @Test fun unusableFilesDecodeToNull() {
        val good = ShellCacheCodec.encode(shell)!!.decodeToString()
        val version = "\"version\":${ShellCacheCodec.VERSION}"
        for (bad in listOf("", "null", "[]", "{", good.dropLast(1), good.replace(version, "\"version\":1"),
            good.replace(version, "\"version\":${ShellCacheCodec.VERSION + 1}"),
            good.replace(version, "\"version\":\"${ShellCacheCodec.VERSION}\""), good.replace("$version,", ""),
            good.replace("\"title\":\"Second\"", "\"title\":5,\"x\":[")))
            assertNull(bad.take(40), ShellCacheCodec.decode(bad.encodeToByteArray(), "env"))
        assertNull(ShellCacheCodec.decode(byteArrayOf(-1, -2, 0, 7), "env"))
        assertNull("Another environment's entry", ShellCacheCodec.decode(good.encodeToByteArray(), "other"))
    }

    @Test fun anInvalidCursorIsDroppedButTheRowsStay() {
        for (bad in listOf(cursor.copy(sequence = -1), cursor.copy(snapshotAtMillis = 0), cursor.copy(serverVersion = " "))) {
            val decoded = ShellCacheCodec.decode(ShellCacheCodec.encode(shell.copy(cursor = bad))!!, "env")!!
            assertNull(decoded.cursor)
            assertEquals(2, decoded.threads.size)
        }
    }

    @Test fun anEntryOverTheSizeCapIsNotEncoded() {
        val size = ShellCacheCodec.encode(shell)!!.size
        assertNull(ShellCacheCodec.encode(shell, maxBytes = size - 1))
        assertNotNull(ShellCacheCodec.encode(shell, maxBytes = size))
    }

    @Test fun toStringHoldsNoContent() {
        val text = shell.toString()
        assertFalse(text.contains("login") || text.contains("Working") || text.contains("Project"))
    }

    private class MemoryStore : ShellCacheStore {
        private val sequencer = ShellCacheSequencer()
        private val lock = Any()
        val entries = ConcurrentHashMap<String, CachedShell>()
        val writes = Channel<CachedShell>(Channel.UNLIMITED)
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failWrites = false
        override fun ticket(environmentId: String) = sequencer.ticket(environmentId)
        override fun valid(environmentId: String, ticket: ShellCacheTicket) = sequencer.valid(environmentId, ticket)
        override suspend fun read(environmentId: String) = entries[environmentId]
        override suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean {
            gate?.await()
            if (failWrites) error("disk full")
            synchronized(lock) {
                if (!sequencer.mayCommit(shell.environmentId, ticket)) return false
                entries[shell.environmentId] = shell; sequencer.committed(shell.environmentId, ticket)
            }
            writes.trySend(shell)
            return true
        }
        override suspend fun delete(environmentId: String) = synchronized(lock) { entries.remove(environmentId); sequencer.invalidate(environmentId) }
        override suspend fun retainOnly(environmentIds: Set<String>) = synchronized(lock) { entries.keys.retainAll(environmentIds); sequencer.invalidateAllExcept(environmentIds) }
    }

    /** Another writer's view of [delegate] whose writes wait for [gate] (a flush still queued behind the file lock). */
    private class GatedStore(private val delegate: ShellCacheStore) : ShellCacheStore by delegate {
        val gate = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        override suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean { waiting.complete(Unit); gate.await(); return delegate.write(shell, ticket) }
    }

    /** Runs launched coroutines only when the test says so, in the order it picks. */
    private class ManualDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        val tasks = java.util.concurrent.LinkedBlockingDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNewestFirst() { while (true) (tasks.pollLast() ?: return).run() }
    }

    private class WriterFixture(delayMillis: Long = 50) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val current = ConcurrentHashMap<String, CachedShell>()
        val writer = ShellCacheWriter(store, scope, { current[it] }, delayMillis)
        override fun close() { scope.cancel() }
    }

    @Test fun changesAreCoalescedIntoOneDelayedWriteOfTheLatestRows() = runBlocking {
        WriterFixture().use { f ->
            f.current["env"] = shell
            repeat(5) { f.writer.changed("env") }
            val later = shell.copy(threads = threads.take(1))
            f.current["env"] = later
            val written = withTimeout(5_000) { f.store.writes.receive() }
            assertSame(later.threads, written.threads)
            delay(200)
            assertTrue("One write for the burst", f.store.writes.tryReceive().isFailure)
        }
    }

    @Test fun unchangedRowsAreNotWrittenAgain() = runBlocking {
        WriterFixture().use { f ->
            f.current["env"] = shell
            f.writer.markWritten(shell)
            withTimeout(5_000) { f.writer.flush("env").join() }
            f.writer.changed("env"); delay(200)
            assertTrue(f.store.writes.tryReceive().isFailure)
            f.current["env"] = shell.copy(cursor = cursor.copy(sequence = 13))
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertEquals(13L, f.store.entries["env"]?.cursor?.sequence)
            f.current["env"] = shell.copy(cursor = cursor.copy(sequence = 13), savedAtMillis = 9_999)
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertEquals("Only the save time changed", 2_000L, f.store.entries["env"]?.savedAtMillis)
        }
    }

    @Test fun flushWritesAtOnceAndCancelsTheScheduledWrite() = runBlocking {
        WriterFixture(delayMillis = 60_000).use { f ->
            f.current["env"] = shell
            f.writer.changed("env")
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertSame(shell.threads, f.store.entries["env"]?.threads)
            f.current.remove("env")
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertNotNull("Nothing to store writes nothing and deletes nothing", f.store.entries["env"])
        }
    }

    @Test fun aWipeWinsOverWritesTakenBeforeIt() = runBlocking {
        WriterFixture().use { f ->
            f.current["env"] = shell
            val gate = CompletableDeferred<Unit>().also { f.store.gate = it }
            val inFlight = f.writer.flush("env")
            delay(50)
            f.current["env"] = shell.copy(threads = emptyList())
            val queued = f.writer.flush("env")
            val wipe = launch { f.writer.wipe("env") }
            delay(50)
            gate.complete(Unit)
            withTimeout(5_000) { inFlight.join(); queued.join(); wipe.join() }
            assertNull("The wipe runs after the running write, and the queued one is dropped", f.store.entries["env"])
            f.store.gate = null
            f.writer.changed("env"); delay(200)
            assertNotNull("Rows changed after the wipe are written again", f.store.entries["env"])
        }
    }

    @Test fun retainOnlyRemovesOtherEnvironmentsAndStopsTheirWrites() = runBlocking {
        WriterFixture(delayMillis = 100).use { f ->
            f.current["env"] = shell; f.current["old"] = shell.copy(environmentId = "old")
            withTimeout(5_000) { f.writer.flush("env").join(); f.writer.flush("old").join() }
            f.writer.changed("old")
            withTimeout(5_000) { f.writer.retainOnly(setOf("env")) }
            assertEquals(setOf("env"), f.store.entries.keys)
            f.current["old"] = shell.copy(environmentId = "old", threads = emptyList())
            withTimeout(5_000) { f.writer.flush("old").join() }
            delay(250)
            assertEquals("A machine no longer saved is never written", setOf("env"), f.store.entries.keys)
        }
    }

    @Test fun aLateFlushOfAClearedModelNeverRecreatesAForgottenMachinesEntry() = runBlocking {
        WriterFixture().use { f ->
            f.current["env"] = shell
            val old = GatedStore(f.store)
            val oldWriter = ShellCacheWriter(old, f.scope, { f.current[it] })
            // The cleared model's flush is captured, then waits behind the file lock.
            val late = oldWriter.close("env")
            withTimeout(5_000) { old.waiting.await() }
            // Its successor forgets the machine meanwhile.
            f.current.remove("env")
            withTimeout(5_000) { f.writer.wipe("env") }
            old.gate.complete(Unit)
            withTimeout(5_000) { late.join() }
            assertNull("The late flush lost to the forget", f.store.entries["env"])
            oldWriter.changed("env"); oldWriter.flush("env")
            delay(150)
            assertNull("A closed writer captures nothing", f.store.entries["env"])
        }
    }

    @Test fun flushesThatRunInReverseOrderKeepTheNewerRows() = runBlocking {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val store = MemoryStore()
            val current = ConcurrentHashMap<String, CachedShell>()
            val writer = ShellCacheWriter(store, scope, { current[it] })
            current["env"] = shell
            writer.flush("env")
            val newer = shell.copy(threads = threads.take(1))
            current["env"] = newer
            writer.flush("env")
            dispatcher.runNewestFirst()
            assertSame("The older capture ran last and was rejected", newer.threads, store.entries["env"]?.threads)
        } finally { scope.cancel() }
    }

    @Test fun writeFailuresAreDroppedAndRetriedOnTheNextChange() = runBlocking {
        WriterFixture().use { f ->
            f.current["env"] = shell
            f.store.failWrites = true
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertNull(f.store.entries["env"])
            f.store.failWrites = false
            withTimeout(5_000) { f.writer.flush("env").join() }
            assertNotNull("A failed write is not remembered as written", f.store.entries["env"])
        }
    }
}
