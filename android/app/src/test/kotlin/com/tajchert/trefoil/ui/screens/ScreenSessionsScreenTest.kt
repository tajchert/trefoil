package com.tajchert.trefoil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ScreenSessionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid/", "fixture-token")
    private val device = ScreenTarget.Device("thread", "remote-mac", "device-1", "android", "Pixel 8")
    private val access = ScreenAccess("fixture", 1, device, "https://fixture.invalid/api/device-hub", "wss://fixture.invalid/api/device-hub", mapOf("wsTicket" to "fixture-media", "hostId" to "remote-mac"), interactive = true)

    @Test fun fullscreenRetainsViewerAndDisconnectedControlsDropInput() {
        var fullscreen by mutableStateOf(false)
        val state = mutableStateOf(ClientState(environment = environment, connection = ConnectionStatus.Connected, canRead = true, selectedThreadId = "thread"))
        var mounted = 0
        var released = 0
        val sent = mutableListOf<StreamCommand>()
        compose.setContent { TrefoilTheme { ScreenSessionsScreen(state.value, ScreenViewerState(environment.id, device, access), fullscreen,
            { fullscreen = it }, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = { controller, report ->
                DisposableEffect(Unit) {
                    mounted++
                    val lease = controller.attach { sent += it; true }
                    onDispose { released++; controller.detach(lease) }
                }
                LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true, controller = "you")) }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            }) } }
        compose.onNodeWithContentDescription("Device home").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Rotate remote device").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Enter full screen").performClick()
        compose.onNodeWithContentDescription("Exit full screen").assertExists()
        compose.runOnIdle { assertEquals(1, mounted); assertEquals(0, released) }
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.runOnIdle { assertEquals(1, mounted); state.value = state.value.copy(connection = ConnectionStatus.Disconnected) }
        compose.onNodeWithContentDescription("Device home").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(StreamCommand.Home), sent) }
    }

    @Test fun staleViewerBlocksInputButAllowsFreshTicketRetryOnConnectedEnvironment() {
        var reopened = 0
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, selectedThreadId = "thread", canRead = true)
        compose.setContent { TrefoilTheme { ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, access), false, {}, {}, { reopened++ }, {}, {},
            isAccessCurrent = { false }, viewerContent = { _, report ->
                LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true)) }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            }) } }
        compose.onNodeWithContentDescription("Device home").assertIsNotEnabled()
        compose.onNodeWithText("Reconnect stream").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reopened) }
    }
    @Test fun pickerKeepsHostIdentityAndDoesNotOpenAnythingUntilSelected() {
        var opened: ScreenTarget? = null
        var refreshes = 0
        val second = device.copy(hostId = "another-mac", name = "Another Pixel")
        val browser = ScreenTarget.Browser("thread", "tab", "Local web app", "http://localhost:5173")
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canRead = true, selectedThreadId = "thread",
            screens = ScreenCatalog(browsers = listOf(browser), devices = listOf(device, second), loaded = true))
        compose.setContent { TrefoilTheme { ScreenSessionsScreen(state, ScreenViewerState(), false, {}, { refreshes++ }, { opened = it }, {}, {}) } }
        compose.runOnIdle { assertNull(opened); assertEquals(1, refreshes) }
        compose.onNodeWithText("Local web app").assertExists()
        compose.onNodeWithText("Another Pixel").performClick()
        compose.runOnIdle { assertEquals(second, opened) }
    }

    @Test fun browserOwnedByAnotherViewerCannotSendOrTakeOverImplicitly() {
        val browser = ScreenTarget.Browser("thread", "tab", "Web app", interactive = true)
        val media = access.copy(target = browser, httpBase = "https://fixture.invalid/api/preview-stream", wsBase = "wss://fixture.invalid/api/preview-stream", query = mapOf("wsTicket" to "fixture-media"))
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canRead = true, selectedThreadId = "thread")
        compose.setContent { TrefoilTheme { ScreenSessionsScreen(state, ScreenViewerState(environment.id, browser, media), false, {}, {}, {}, {}, {},
            isAccessCurrent = { true }, viewerContent = { _, report ->
                LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, canOperate = true, controller = "another-viewer")) }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            }) } }
        compose.onNodeWithContentDescription("Type on remote browser").assertIsNotEnabled()
        compose.onNodeWithText("Take control").assertIsEnabled()
    }
}
