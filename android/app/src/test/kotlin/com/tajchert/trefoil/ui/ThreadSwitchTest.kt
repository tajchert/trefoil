package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Thread switches lay the timeline out once, at its end, and reuse what the session kept for a revisited thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ThreadSwitchTest {
    @get:Rule val compose = createComposeRule()

    private val messages = (0 until 25).flatMap { i ->
        listOf(ConversationMessage("u$i", "user", "Prompt $i", ordinal = i * 2), ConversationMessage("a$i", "assistant", "Answer $i", ordinal = i * 2 + 1))
    }
    private val state = ClientState(
        connection = ConnectionStatus.Connected, canWrite = false, projects = listOf(Project("p", "app")),
        threads = listOf(ThreadSummary("t", "p", "Long thread")), selectedThreadId = "t", messages = messages,
    )

    @Test fun cachedRowsAreLaidOutAtTheEndInTheFirstFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TrefoilTheme { Box(Modifier.size(390.dp, 844.dp)) { Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }) } } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Answer 24").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Prompt 15").fetchSemanticsNodes().isEmpty())
    }

    @Test fun firstRowsOfAnUncachedThreadLandAtTheEndInTheFrameTheyArrive() {
        var shown by mutableStateOf(state.copy(messages = emptyList()))
        compose.mainClock.autoAdvance = false
        compose.setContent { TrefoilTheme { Box(Modifier.size(390.dp, 844.dp)) { Conversation(shown, send = {}, interrupt = {}, respond = { _, _, _ -> }) } } }
        compose.mainClock.advanceTimeByFrame()
        shown = state
        // The first frame that shows any of the rows already shows the end, never the top first.
        var frames = 0
        while (compose.onAllNodes(hasText("Prompt", substring = true)).fetchSemanticsNodes().isEmpty() && frames++ < 10) compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Answer 24").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Prompt 15").fetchSemanticsNodes().isEmpty())
    }

    @Test fun foldMemoReusesTheFoldOfIdenticalRowsOnly() {
        val memo = TimelineFoldMemo(capacity = 2)
        val first = memo.fold(messages, emptyList(), null, emptyList())
        assertSame(first, memo.fold(messages, emptyList(), null, emptyList()))
        assertEquals(first, memo.fold(messages.toList(), emptyList(), null, emptyList())) // equal but not the session's instances: refolded
        assertNotSame(first, memo.fold(messages.toList(), emptyList(), null, emptyList()))
        val other = messages.take(2)
        memo.fold(other, emptyList(), null, emptyList()); memo.fold(other.toList(), emptyList(), null, emptyList())
        assertNotSame(first, memo.fold(messages, emptyList(), null, emptyList())) // evicted beyond capacity
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun restoredScrollPositionStartsAtTheEndOnlyWhileFollowing() {
        val following = followingListSaver { 40 } as Saver<LazyListState, Any>
        assertEquals(40, following.restore(listOf(3, 120))!!.firstVisibleItemIndex)
        val reading = followingListSaver { null } as Saver<LazyListState, Any>
        val restored = reading.restore(listOf(3, 120))!!
        assertEquals(3, restored.firstVisibleItemIndex)
        assertEquals(120, restored.firstVisibleItemScrollOffset)
    }

    @Test fun messageMetadataMatchesThePerRowRules() {
        val entries = listOf(
            ConversationEntry.Message(ConversationMessage("u1", "user", "a")),
            ConversationEntry.Message(ConversationMessage("u2", "user", "b"), stacked = true),
            ConversationEntry.Message(ConversationMessage("a1", "assistant", "first", runId = "r1")),
            ConversationEntry.Message(ConversationMessage("a2", "assistant", "last", runId = "r1")),
            ConversationEntry.Message(ConversationMessage("u3", "user", "c")),
            ConversationEntry.Message(ConversationMessage("a3", "assistant", "live", runId = "r2")),
            ConversationEntry.Message(ConversationMessage("a4", "assistant", "streaming", runId = "r3", streaming = true)),
        )
        val ids = messageMetadataIds(entries, entries.map { it.message }, activeRunId = "r2")
        assertEquals(setOf("u2", "a2", "u3"), ids)
        assertEquals(setOf("u2", "a2", "u3", "a3"), messageMetadataIds(entries, entries.map { it.message }, activeRunId = null))
    }

    @Test fun contentTypesSeparateRowKinds() {
        val user = ConversationEntry.Message(ConversationMessage("u", "user", "a"))
        val assistant = ConversationEntry.Message(ConversationMessage("a", "assistant", "b"))
        val tool = ConversationEntry.Activities(listOf(ToolActivity("t", "command", "ls")))
        assertEquals(3, setOf(timelineContentType(user), timelineContentType(assistant), timelineContentType(tool)).size)
    }

    private val ConversationEntry.message get() = (this as ConversationEntry.Message).message
}
