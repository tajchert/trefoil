package com.tajchert.trefoil.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class ConnectionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val fixture = ClientState(environment = environment, connection = ConnectionStatus.Connected,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")))

    @Test fun restoredPhoneDetailWithoutSelectionReturnsToList() {
        val state = mutableStateOf(fixture)
        var restoring = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TrefoilApp(if (restoring) fixture else state.value, listOf(environment), { _, _, _ -> }, {},
                { state.value = state.value.copy(selectedThreadId = it) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("Fixture thread").performClick()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        // Simulate process recreation: save the detail destination, but restore a fresh client state.
        restoring = true
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Pick a thread").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithText("Fixture thread").assertExists()
    }

    @Test fun shortReconnectDoesNotFlashBannerAndBusyConversationHasNoBanner() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(fixture.copy(connection = ConnectionStatus.Reconnecting))
        compose.setContent { TrefoilTheme { ConnectionBanner(state.value, {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()
        compose.runOnIdle { state.value = fixture.copy(busy = true) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Connecting…").assertDoesNotExist()
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()
    }

    @Test fun longReconnectShowsBannerAndErrorAppearsImmediatelyAtSameHeight() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(fixture.copy(connection = ConnectionStatus.Connecting))
        compose.setContent { TrefoilTheme { ConnectionBanner(state.value, {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Connecting…").assertDoesNotExist()
        // Connecting -> Reconnecting is one continuous attempt and must not restart the delay.
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Reconnecting) }
        compose.mainClock.advanceTimeBy(600)
        val height = compose.onRoot().fetchSemanticsNode().size.height
        compose.onNodeWithText("Reconnecting…").assertExists()
        compose.runOnIdle { state.value = fixture.copy(connection = ConnectionStatus.Error, error = "Connection lost") }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Connection lost").assertExists()
        assertEquals(height, compose.onRoot().fetchSemanticsNode().size.height)
    }

    @Test fun errorsDuringInitialConnectionDoNotWaitForDelay() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TrefoilTheme { ConnectionBanner(fixture.copy(connection = ConnectionStatus.Connecting, error = "Failed"), {}) } }
        compose.onNodeWithText("Failed").assertExists()
    }
}
