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
class ThreadActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("env", "Fixture", "https://example.test", "fixture")
    @Test fun rowMenuMutatesItsOwnTargetWithoutSelectingIt() {
        var selected: String? = null
        var action: Pair<ThreadTarget, ThreadAction>? = null
        val row = ThreadSummary("row", "p", "Row")
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWriteThreads = true, threadActionsConfigured = true,
            selectedThreadId = "different", projects = listOf(Project("p", "P")), threads = listOf(row))
        compose.setContent { MaterialTheme { ThreadList(state, { selected = it }, {}, { target, operation, _ -> action = target to operation }) } }
        compose.onNodeWithText("Row").performTouchInput { longClick() }
        compose.onNodeWithText("Settle").performClick()
        assertEquals(ThreadTarget("env", "row") to ThreadAction.Settle, action)
        assertNull(selected)
    }
    @Test fun rowMenuIsReachableAsAnAccessibilityAction() {
        var action: ThreadAction? = null
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWriteThreads = true, threadActionsConfigured = true,
            projects = listOf(Project("p", "P")), threads = listOf(ThreadSummary("row", "p", "Row")))
        compose.setContent { MaterialTheme { ThreadList(state, {}, {}, { _, operation, _ -> action = operation }) } }
        val row = compose.onNode(hasText("Row") and hasClickAction())
        val custom = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertEquals(listOf("Thread actions for Row"), custom.map { it.label })
        compose.runOnUiThread { custom.single().action() }
        compose.onNodeWithText("Pin").performClick()
        assertEquals(ThreadAction.Pin, action)
    }
    @Test fun disconnectedMenuKeepsCopyAvailableAndDisablesMutations() {
        compose.setContent { MaterialTheme { ThreadActionsMenu(ClientState(environment = environment), ThreadSummary("row", "p", "Row")) { _, _, _ -> fail() } } }
        compose.onNodeWithContentDescription("Thread actions for Row").performClick()
        listOf("Settle", "Pin", "Rename", "Snooze…").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
        compose.onNodeWithText("Copy thread").assertIsEnabled()
        compose.onNodeWithText("Copy thread ID").assertIsEnabled()
    }
    @Test fun wideSelectionAndReferenceAreEnvironmentScoped() {
        assertTrue(selectedThreadActionsVisible(true, false, null, "row"))
        assertFalse(selectedThreadActionsVisible(false, false, null, "row"))
        assertEquals("https://app.t3.codes/threads/env%20a/row%2F1", threadCopyReference(ThreadTarget("env a", "row/1")))
    }
    @Test fun snoozePresetsArePresented() {
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWriteThreads = true, threadActionsConfigured = true)
        compose.setContent { MaterialTheme { ThreadActionsMenu(state, ThreadSummary("row", "p", "Row")) { _, _, _ -> } } }
        compose.onNodeWithContentDescription("Thread actions for Row").performClick()
        compose.onNodeWithText("Snooze…").performClick()
        listOf("For one hour", "Tomorrow at 9 AM", "In one week", "Custom date and time…").forEach { compose.onNodeWithText(it).assertExists() }
    }
    @Test fun missingServerConfigurationDisablesPinAndSnooze() {
        compose.setContent { MaterialTheme { ThreadActionsMenu(ClientState(environment = environment, connection = ConnectionStatus.Connected, canWriteThreads = true), ThreadSummary("row", "p", "Row")) { _, _, _ -> } } }
        compose.onNodeWithContentDescription("Thread actions for Row").performClick()
        compose.onNodeWithText("Settle").assertIsEnabled()
        compose.onNodeWithText("Pin").assertIsNotEnabled()
        compose.onNodeWithText("Snooze…").assertIsNotEnabled()
    }
    @Test fun projectFilterNarrowsList() {
        val state = ClientState(environment = environment, projects = listOf(Project("a", "Alpha"), Project("b", "Beta")),
            threads = listOf(ThreadSummary("a", "a", "First"), ThreadSummary("b", "b", "Second")))
        compose.setContent { MaterialTheme { ThreadList(state, {}, {}) } }
        compose.onNodeWithText("Project").performClick()
        compose.onNode(hasText("Alpha") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithText("Second").assertDoesNotExist()
    }
}
