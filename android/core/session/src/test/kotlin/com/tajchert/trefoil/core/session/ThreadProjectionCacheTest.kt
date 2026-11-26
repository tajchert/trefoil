package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadProjectionCacheTest {
    private fun presentation(rows: Int) = ThreadPresentation(
        messages = List(rows) { ConversationMessage("m$it", "assistant", "text $it") }, activities = emptyList(), requests = emptyList(),
        activeRunId = null, runs = emptyList(), attempts = emptyList(), subagents = emptyList(), backgroundWork = emptyList(),
        queuedMessages = emptyList(), queueHeld = false, steerable = false, stoppable = false, runtimeMode = null,
        modelSelection = null, modelOptions = emptyMap(), providerLocked = false,
    )
    private fun entry(rows: Int) = CachedThread(JsonObject(emptyMap()), presentation(rows))

    @Test fun keepsTheMostRecentThreadsByCountLeastRecentlyUsedFirst() {
        val cache = ThreadProjectionCache(maxThreads = 3, maxRows = 1_000)
        listOf("a", "b", "c").forEach { cache.put("env" to it, entry(1)) }
        cache.put("env" to "a", cache["env" to "a"]!!) // a revisit refreshes its recency
        cache.put("env" to "d", entry(1))
        assertEquals(setOf("env" to "c", "env" to "a", "env" to "d"), cache.keys)
        assertNull(cache["env" to "b"])
    }

    @Test fun evictsOlderThreadsOverTheRowBudgetButAlwaysKeepsTheNewest() {
        val cache = ThreadProjectionCache(maxThreads = 12, maxRows = 100)
        cache.put("env" to "a", entry(40))
        cache.put("env" to "b", entry(40))
        cache.put("env" to "c", entry(40))
        assertEquals(listOf("env" to "b", "env" to "c"), cache.keys.toList())
        // A single thread larger than the whole budget is the one on screen: kept, every other one goes.
        cache.put("env" to "huge", entry(500))
        assertEquals(listOf("env" to "huge"), cache.keys.toList())
    }

    @Test fun eachEnvironmentHasItsOwnBudget() {
        val cache = ThreadProjectionCache(maxThreads = 2, maxRows = 100)
        cache.put("one" to "a", entry(60)); cache.put("one" to "b", entry(30))
        cache.put("two" to "a", entry(90)); cache.put("two" to "b", entry(1)); cache.put("two" to "c", entry(1))
        // "two" trimmed to its own two newest; "one" keeps both although the total is over one budget.
        assertEquals(listOf("one" to "a", "one" to "b", "two" to "b", "two" to "c"), cache.keys.toList())
        cache.put("one" to "c", entry(50)) // over its count: only "one"'s eldest goes
        assertEquals(listOf("one" to "b", "two" to "b", "two" to "c", "one" to "c"), cache.keys.toList())
    }

    @Test fun forgettingAnEnvironmentDropsOnlyItsThreads() {
        val cache = ThreadProjectionCache()
        cache.put("one" to "a", entry(1)); cache.put("two" to "a", entry(1)); cache.put("one" to "b", entry(1))
        cache.forgetEnvironment("one")
        assertEquals(setOf("two" to "a"), cache.keys)
        cache.clear()
        assertTrue(cache.keys.isEmpty())
    }

    @Test fun stableRowsReusesUnchangedInstancesAndTakesChangedOnes() {
        val old = listOf(ConversationMessage("1", "user", "hi"), ConversationMessage("2", "assistant", "partial", streaming = true))
        val equalCopy = old.map { it.copy() }
        assertSame(old, stableRows(old, equalCopy) { it.id })
        val next = listOf(ConversationMessage("1", "user", "hi"), ConversationMessage("2", "assistant", "done"), ConversationMessage("3", "user", "more"))
        val merged = stableRows(old, next) { it.id }
        assertEquals(next, merged)
        assertSame(old[0], merged[0])
        assertSame(next[1], merged[1])
        assertSame(next[2], merged[2])
        val unrelated = listOf(ConversationMessage("x", "user", "other thread"))
        assertSame(unrelated, stableRows(old, unrelated) { it.id })
    }
}
