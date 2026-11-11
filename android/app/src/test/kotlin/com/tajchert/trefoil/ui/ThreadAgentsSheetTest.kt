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
class ThreadAgentsSheetTest {
    @get:Rule val compose = createComposeRule()
    private val running = AgentRowUi("a", "Explore code", "Working", SubagentTone.Working, elapsed = "5s", modelLine = "Claude · Sonnet", detail = "Reading files", openThreadId = "child", canStop = true)
    private val remote = AgentRowUi("b", "Remote task", "Failed", SubagentTone.Failed, detail = "Boom", runsOn = "Studio")

    @Test fun emptyState() {
        compose.setContent { MaterialTheme { ThreadAgentsSheetContent(emptyList(), {}, {}) } }
        compose.onNodeWithText("Agents").assertIsDisplayed()
        compose.onNodeWithText("No agents in this turn.").assertIsDisplayed()
    }

    @Test fun rowsRenderAndOpenability() {
        var opened: String? = null
        compose.setContent { MaterialTheme { ThreadAgentsSheetContent(listOf(running, remote), { opened = it }, {}) } }
        compose.onNodeWithText("Explore code").assertIsDisplayed()
        compose.onNodeWithText("· Working").assertIsDisplayed()
        compose.onNodeWithText("Claude · Sonnet").assertIsDisplayed()
        compose.onNodeWithText("Reading files").assertIsDisplayed()
        compose.onNodeWithText("Boom").assertIsDisplayed()
        compose.onNodeWithText("on Studio").assertIsDisplayed()
        compose.onNodeWithTag("agent-row-b").assertHasNoClickAction()
        compose.onNodeWithTag("agent-row-a").performClick()
        assertEquals("child", opened)
    }

    @Test fun stopNeedsConfirmation() {
        var stopped: String? = null
        compose.setContent { MaterialTheme { ThreadAgentsSheetContent(listOf(running), {}, { stopped = it }) } }
        compose.onNodeWithContentDescription("Stop Explore code").performClick()
        compose.onNodeWithText("Stop agent?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(stopped)
        compose.onNodeWithContentDescription("Stop Explore code").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertEquals("a", stopped)
    }

    @Test fun rowsWithoutCanStopHaveNoStopButton() {
        compose.setContent { MaterialTheme { ThreadAgentsSheetContent(listOf(running.copy(canStop = false)), {}, {}) } }
        compose.onNodeWithContentDescription("Stop Explore code").assertDoesNotExist()
    }

    @Test fun agentsMenuItemVisibleOnlyWithRoster() {
        val env = SavedEnvironment("env", "Fixture", "https://example.test", "fixture")
        val state = ClientState(environment = env)
        var opened = false
        compose.setContent { MaterialTheme { ThreadActionsMenu(state, ThreadSummary("t", "p", "T"), onOpenAgents = { opened = true }) { _, _, _ -> } } }
        compose.onNodeWithContentDescription("Thread actions for T").performClick()
        compose.onNodeWithText("Agents").performClick()
        assertTrue(opened)
    }

    @Test fun agentsMenuItemHiddenWithoutRoster() {
        val env = SavedEnvironment("env", "Fixture", "https://example.test", "fixture")
        compose.setContent { MaterialTheme { ThreadActionsMenu(ClientState(environment = env), ThreadSummary("t", "p", "T")) { _, _, _ -> } } }
        compose.onNodeWithContentDescription("Thread actions for T").performClick()
        compose.onNodeWithText("Agents").assertDoesNotExist()
    }

    @Test fun providerNativeChildShowsReadOnlyBar() {
        val child = ThreadSummary("c", "p", "Child", parentThreadId = "parent", relationshipToParent = "subagent", creationSource = "provider", model = "sonnet")
        var parent = false
        compose.setContent { MaterialTheme { ReadOnlySubagentBar(child, 0L) { parent = true } } }
        compose.onNodeWithText("Runs on its own").assertIsDisplayed()
        compose.onNodeWithText("Open parent").performClick()
        assertTrue(parent)
    }

    @Test fun conversationReplacesComposerForProviderNativeChild() {
        val env = SavedEnvironment("env", "Fixture", "https://example.test", "fixture")
        val child = ThreadSummary("c", "p", "Child", parentThreadId = "parent", relationshipToParent = "subagent", creationSource = "provider")
        val state = ClientState(environment = env, selectedThreadId = "c", threads = listOf(child), projects = listOf(Project("p", "P")))
        compose.setContent { MaterialTheme { Conversation(state, {}, {}, { _, _, _ -> }, onOpenParent = {}) } }
        compose.onNodeWithTag("readonly-subagent-bar").assertIsDisplayed()
        compose.onNodeWithText("Reply…").assertDoesNotExist()
    }
}
