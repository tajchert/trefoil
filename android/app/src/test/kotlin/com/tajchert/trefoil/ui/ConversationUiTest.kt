package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftWaitsForNewServerMessageAndNeverResendsOnReconnection() {
        val state = mutableStateOf(ClientState(selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected,
            messages = listOf(ConversationMessage("old", "user", "Hello"))))
        val sends = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state.value, { sends += it }, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Reply…").performTextInput("Hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Disconnected, canWrite = false) }
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Connected, canWrite = true) }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("Hello"), sends)
            state.value = state.value.copy(messages = state.value.messages + ConversationMessage("new", "user", "Hello"))
        }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun disconnectedConversationDisablesApprovalAndSendButRetainsDraftEditing() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", requests = listOf(RuntimeRequest("approval", "Run fixture command"))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Reply…").performTextInput("Draft")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("Allow").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextEquals("Draft")
    }

    @Test fun completedResponseDisclosureKeepsFinalAnswerAndRevealsIntermediateWork() {
        val state = ClientState(selectedThreadId = "fixture", messages = listOf(
            ConversationMessage("c", "assistant", "Checking the fixture", runId = "r", ordinal = 0),
            ConversationMessage("a", "assistant", "The result is ready", runId = "r", ordinal = 2)),
            activities = listOf(ToolActivity("t", "Read file", "fixture.txt", status = "completed", runId = "r", ordinal = 1)))
        compose.setContent { MaterialTheme { Conversation(state, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("The result is ready").assertExists()
        compose.onNodeWithText("Checking the fixture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand completed work").performClick()
        compose.onNodeWithText("Checking the fixture").assertExists()
        compose.onNodeWithText("fixture.txt").assertExists()
        compose.onNodeWithContentDescription("Collapse completed work").performClick()
        compose.onNodeWithText("Checking the fixture").assertDoesNotExist()
        compose.onNodeWithText("The result is ready").assertExists()
    }

    @Test fun failedCommandShowsItsInputOutputAndExitCodeWithoutDisclosure() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(
            ToolActivity("command", "Run checks", kind = "command_execution", status = "completed", input = "./fixture-check", output = "Fixture failed", exitCode = 2))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("$ ./fixture-check").assertExists()
        compose.onNodeWithText("Fixture failed").assertExists()
        compose.onNodeWithText("Exit code 2").assertExists()
        compose.onNodeWithContentDescription("Copy activity").assertExists()
    }

    @Test fun omittedOutputLoadsOnlyOnExplicitConnectedRequest() {
        val state = mutableStateOf(ClientState(selectedThreadId = "fixture", canWrite = false, connection = ConnectionStatus.Connected,
            activities = listOf(ToolActivity("command", "Run checks", kind = "command_execution", status = "completed", input = "./fixture-check", outputOmitted = true))))
        val loads = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state.value, {}, {}, { _, _, _ -> }, onLoadActivity = { loads += it }) } }
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.runOnIdle { assertTrue(loads.isEmpty()) }
        compose.onNodeWithText("Load output").performClick()
        compose.runOnIdle {
            assertEquals(listOf("command"), loads)
            state.value = state.value.copy(activities = state.value.activities.map { it.copy(outputOmitted = false, output = "Hydrated fixture") })
        }
        compose.onNodeWithText("Hydrated fixture").assertExists()
        compose.onNodeWithText("Load output").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Disconnected, canWrite = false, activities = state.value.activities.map { it.copy(outputOmitted = true, output = null) }) }
        compose.onNodeWithText("Load output").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("command"), loads) }
    }

    @Test fun structuredCommandDetailsDoNotRepeatTheLegacyAggregate() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(
            ToolActivity("command", "Run checks", detail = "./fixture-check\n\nfixture-output", kind = "command_execution", status = "completed", input = "./fixture-check", output = "fixture-output", exitCode = 0))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("./fixture-check").assertExists()
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onAllNodesWithText("fixture-output", substring = true).assertCountEquals(1)
        compose.onNodeWithText("$ ./fixture-check").assertExists()
        compose.onNodeWithText("Exit code 0").assertExists()
    }

    @Test fun requestsRemainVisibleBesideCompletedWork() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", canWrite = true,
            messages = listOf(ConversationMessage("a", "assistant", "Ready", runId = "r", ordinal = 1)),
            activities = listOf(ToolActivity("t", "Read file", status = "completed", runId = "r", ordinal = 0)),
            requests = listOf(RuntimeRequest("approval", "Review the next command"))), {}, {}, { _, _, _ -> }) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription("Expand completed work").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Review the next command"))
        compose.onNodeWithText("Review the next command").assertExists()
        compose.onNodeWithText("Allow").assertIsEnabled()
    }

    @Test fun navigatingAwayRestoresDraftAndExpandedWorkWithinTheThreadStateProvider() {
        val selected = mutableStateOf("a")
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                holder.SaveableStateProvider(selected.value) {
                    Conversation(ClientState(selectedThreadId = selected.value,
                        activities = listOf(ToolActivity("tool", "Read file", "fixture.txt", status = "completed"))), {}, {}, { _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Reply…").performTextInput("Keep my draft")
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.runOnIdle { selected.value = "b" }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("fixture.txt").assertDoesNotExist()
        compose.runOnIdle { selected.value = "a" }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep my draft")
        compose.onNodeWithText("fixture.txt").assertExists()
    }

    @Test fun activityDetailsAreCollapsedUntilExpanded() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(ToolActivity("read", "Read file", "fixture.txt", status = "completed"))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("fixture.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithText("fixture.txt").assertExists()
    }

    private val longThread = ClientState(selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected,
        messages = (1..30).map { ConversationMessage("m$it", if (it % 2 == 0) "assistant" else "user", "Fixture message $it") })

    /** Shrinking the host stands in for the keyboard opening: both reduce the list viewport (Robolectric windows are 470dp tall). */
    private fun shrinkableConversation(): androidx.compose.runtime.MutableState<Int> {
        val height = mutableStateOf(460)
        compose.setContent { TrefoilTheme { Box(Modifier.width(390.dp).height(height.value.dp)) { Conversation(longThread, {}, {}, { _, _, _ -> }) } } }
        return height
    }

    private fun scrollRange() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun atEnd() = scrollRange().let { it.value() >= it.maxValue() }

    @Test fun keyboardOpeningKeepsLatestMessageVisibleWhenFollowingTheBottom() {
        val height = shrinkableConversation()
        compose.waitForIdle()
        assertTrue(atEnd())
        compose.onNodeWithText("Reply…").performClick()
        compose.runOnIdle { height.value = 300 }
        compose.waitForIdle()
        assertTrue("list should stay pinned to the latest message", atEnd())
        compose.onNodeWithText("Fixture message 30").assertIsDisplayed()
        compose.onNodeWithText("Latest").assertDoesNotExist()
    }

    @Test fun keyboardOpeningKeepsScrolledPositionWhenReadingHistory() {
        val height = shrinkableConversation()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f) }
        compose.waitForIdle()
        assertFalse(atEnd())
        val before = scrollRange().value()
        compose.onNodeWithText("Reply…").performClick()
        compose.runOnIdle { height.value = 300 }
        compose.waitForIdle()
        assertEquals(before, scrollRange().value())
        compose.onNodeWithText("Latest").assertIsDisplayed()
    }
}
