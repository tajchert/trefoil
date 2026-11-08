package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
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
class NewThreadFlowTest {
    @get:Rule val compose = createComposeRule()
    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"), ModelOption("claude", "sonnet", "Model B"))
    private val state = ClientState(projects = listOf(Project("p1", "Alpha"), Project("p2", "Beta")), models = models, canWrite = true)

    @Test fun noTitleFieldAndStartEnabledWithoutOne() {
        compose.setContent { MaterialTheme { NewThreadDialog(state, {}) {} } }
        compose.onNodeWithText("Conversation title").assertDoesNotExist()
        compose.onNodeWithText("Start").assertIsEnabled()
    }

    @Test fun tappingTextSelectsProjectModelAndModeAndStartEmitsDraft() {
        var started: ThreadDraft? = null
        compose.setContent { MaterialTheme { NewThreadDialog(state, {}) { started = it } } }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithText("Auto-accept edits").performScrollTo().performClick()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertEquals(ThreadDraft("p2", models[1], RuntimeMode.AutoAcceptEdits), started) }
    }

    @Test fun defaultModeIsApprovalRequired() {
        var started: ThreadDraft? = null
        compose.setContent { MaterialTheme { NewThreadDialog(state, {}) { started = it } } }
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.ApprovalRequired, started?.runtimeMode); assertEquals("p1", started?.projectId) }
    }

    @Test fun startDisabledWhenReadOnly() {
        compose.setContent { MaterialTheme { NewThreadDialog(state.copy(canWrite = false), {}) {} } }
        compose.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun draftConversationShowsHintAndPickersCallBack() {
        val draft = ThreadDraft("p1", models[0])
        var mode: RuntimeMode? = null
        var model: ModelOption? = null
        val sent = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state, { sent += it }, {}, { _, _, _ -> }, draft = draft, onSetRuntimeMode = { mode = it }, onSetModel = { model = it }) } }
        compose.onNodeWithText("New conversation in Alpha").assertIsDisplayed()
        compose.onNodeWithText("Your first message starts the thread.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Runtime mode: Supervised").performClick()
        compose.onNodeWithText("Full access").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithText("Message T3…").performTextInput("hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.FullAccess, mode); assertEquals(models[1], model); assertEquals(listOf("hello"), sent) }
    }

    @Test fun pickersDisabledWhenReadOnlyAndModelPickerWhenNoModels() {
        compose.setContent { MaterialTheme { Conversation(state.copy(selectedThreadId = "t", canWrite = false), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Runtime mode: not set").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Model: not set").assertIsNotEnabled()
    }

    @Test fun modelPickerDisabledWithoutModels() {
        compose.setContent { MaterialTheme { Conversation(state.copy(selectedThreadId = "t", models = emptyList()), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Runtime mode: not set").assertIsEnabled()
        compose.onNodeWithContentDescription("Model: not set").assertIsNotEnabled()
    }
}
