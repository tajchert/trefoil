package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
        compose.onNodeWithText("Message T3…").performTextInput("Hello")
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
        compose.onNodeWithText("Message T3…").performTextInput("Draft")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("Approve").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextEquals("Draft")
    }

    @Test fun activityDetailsAreCollapsedUntilExpanded() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(ToolActivity("read", "Read file", "fixture.txt", status = "completed"))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("fixture.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithText("fixture.txt").assertExists()
    }
}
