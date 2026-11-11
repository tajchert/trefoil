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

    private fun sheet(s: ClientState, onStart: (ThreadDraft, String) -> Unit = { _, _ -> }) =
        compose.setContent { TrefoilTheme { NewThreadSheetContent(s, {}, onStart) } }

    @Test fun noTitleFieldAndStartEnabledWithoutOne() {
        sheet(state)
        compose.onNodeWithText("Conversation title").assertDoesNotExist()
        compose.onNodeWithText("What should the agent do?").assertExists()
        compose.onNodeWithContentDescription("Start").assertIsEnabled()
    }

    @Test fun tappingChipsAndMenusSelectsProjectModelAndModeAndStartEmitsDraftWithPrompt() {
        var started: ThreadDraft? = null
        var prompt: String? = null
        sheet(state) { draft, text -> started = draft; prompt = text }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithContentDescription("Access: Supervised").performClick()
        compose.onNodeWithText("Auto-accept edits").performClick()
        compose.onNodeWithText("What should the agent do?").performTextInput("Fix the build")
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle {
            assertEquals(Triple("p2", models[1], RuntimeMode.AutoAcceptEdits), started?.let { Triple(it.projectId, it.model, it.runtimeMode) })
            assertEquals("Fix the build", prompt)
        }
    }

    @Test fun manyProjectsCollapseBehindCountChipAndSearchFilters() {
        val many = state.copy(projects = (1..12).map { Project("p$it", "Project $it") })
        sheet(many)
        compose.onNodeWithText("Project 9").assertDoesNotExist()
        compose.onNodeWithText("+4").performClick()
        compose.onNodeWithText("Project 12").assertExists()
        compose.onNodeWithContentDescription("Search projects").performClick()
        compose.onNodeWithText("Search projects").performTextInput("Project 11")
        compose.onNodeWithText("Project 12").assertDoesNotExist()
        compose.onNode(hasText("Project 11") and hasClickAction() and !isEditable()).assertExists()
    }

    @Test fun defaultModeIsApprovalRequired() {
        var started: ThreadDraft? = null
        sheet(state) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.ApprovalRequired, started?.runtimeMode); assertEquals("p1", started?.projectId) }
    }

    @Test fun startDisabledWhenReadOnly() {
        sheet(state.copy(canWrite = false))
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
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

    private val restricted = listOf(
        ModelOption("codex", "gpt-a", "Model A", listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess)),
        ModelOption("claude", "sonnet", "Model B", listOf(RuntimeMode.AutoAcceptEdits)),
    )

    @Test fun dialogDefaultsToServerDefaultMode() {
        var started: ThreadDraft? = null
        sheet(state.copy(defaultRuntimeMode = RuntimeMode.FullAccess)) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.FullAccess, started?.runtimeMode) }
    }

    @Test fun dialogShowsOnlySupportedModesAndFallsBackAfterModelChange() {
        var started: ThreadDraft? = null
        sheet(state.copy(models = restricted)) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Access: Supervised").performClick()
        compose.onNodeWithText("Auto-accept edits").assertDoesNotExist()
        compose.onNodeWithText("Auto", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Full access").assertExists()
        compose.onNodeWithText("Full access").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithContentDescription("Access: Auto-accept edits").assertExists()
        compose.onNodeWithContentDescription("Access: Auto-accept edits").performClick()
        compose.onNodeWithText("Full access").assertDoesNotExist()
        compose.onNodeWithText("Supervised").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.AutoAcceptEdits, started?.runtimeMode) }
    }

    @Test fun composerModeMenuFiltersByModel() {
        val draft = ThreadDraft("p1", restricted[0])
        compose.setContent { MaterialTheme { Conversation(state.copy(models = restricted), {}, {}, { _, _, _ -> }, draft = draft) } }
        compose.onNodeWithContentDescription("Runtime mode: Supervised").performClick()
        compose.onNodeWithText("Full access").assertExists()
        compose.onNodeWithText("Auto-accept edits").assertDoesNotExist()
    }

    @Test fun lockedProviderListsOnlySameInstanceModels() {
        val more = models + ModelOption("codex", "gpt-b", "Model C")
        val locked = state.copy(models = more, selectedThreadId = "t", selectedModel = more[0], selectedRuntimeMode = RuntimeMode.Auto, selectedProviderLocked = true)
        compose.setContent { MaterialTheme { Conversation(locked, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model C").assertExists()
        compose.onNodeWithText("Model B").assertDoesNotExist()
    }

    @Test fun unlockedProviderListsAllModels() {
        val unlocked = state.copy(selectedThreadId = "t", selectedModel = models[0], selectedRuntimeMode = RuntimeMode.Auto)
        compose.setContent { MaterialTheme { Conversation(unlocked, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model B").assertExists()
    }

    @Test fun sendFailedClearsPendingAndKeepsTextEvenWithIdenticalError() {
        val failed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val errored = state.copy(selectedThreadId = "t", error = "boom")
        compose.setContent { MaterialTheme { Conversation(errored, {}, {}, { _, _, _ -> }, sendFailed = failed) } }
        repeat(2) {
            if (it == 0) compose.onNodeWithText("Message T3…").performTextInput("hello")
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.onNodeWithText("Waiting for server confirmation…").assertIsDisplayed()
            compose.runOnIdle { failed.tryEmit(Unit) }
            compose.waitForIdle()
            compose.onNodeWithText("Waiting for server confirmation…").assertDoesNotExist()
            compose.onNodeWithText("hello").assertExists()
        }
    }
}
