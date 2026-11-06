package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadListUiTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = ClientState(connection = ConnectionStatus.Connected, canWrite = true,
        projects = listOf(Project("trefoil", "Trefoil"), Project("t3", "T3 Code")),
        threads = listOf(
            ThreadSummary("pin", "trefoil", "Give every conversation a little more clarity", "completed", pinnedAt = "2026-11-06T08:00:00Z", branch = "feat/conversation-ui"),
            ThreadSummary("approval", "trefoil", "Review the connection flow", "running", pendingRequestKind = "approval"),
            ThreadSummary("active", "trefoil", "Polish the Android thread list", "completed"),
            ThreadSummary("work", "t3", "Keep activity in the right order", "running"),
            ThreadSummary("done", "trefoil", "Secure environment storage", "completed", settledAt = "2026-11-06T08:00:00Z")
        ))
    @Test fun searchFindsProjectAndCollapseHidesEvenTheSelectedThread() {
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(selectedThreadId = "work"), {}, {}) } }
        compose.onNodeWithContentDescription("Working shelf").performClick()
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithContentDescription("Working shelf").performClick()
        compose.onNodeWithText("Keep activity in the right order").assertExists()
        compose.onNodeWithText("Search threads").performTextInput("T3 Code")
        compose.onNodeWithText("Keep activity in the right order").assertExists()
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun returningFromConversationRetainsCollapsedShelvesAndSearch() {
        val state = androidx.compose.runtime.mutableStateOf(fixture.copy(environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")))
        compose.setContent { TrefoilApp(state.value, emptyList(), { _, _, _ -> }, {},
            { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}, {}, {}) }
        compose.onNodeWithContentDescription("Working shelf").performClick()
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithText("Polish the Android thread list").performClick()
        compose.onNodeWithContentDescription("Back to threads").performClick()
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithText("Search threads").performTextInput("Polish")
        compose.onNodeWithText("Polish the Android thread list").performClick()
        compose.onNodeWithContentDescription("Back to threads").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Polish")
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun renderOfflineThreadList() {
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { ThreadList(fixture, {}, {}) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/thread-list.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
    @Test @Config(sdk = [35], qualifiers = "w411dp-h891dp-night") fun renderDarkThreadList() {
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { ThreadList(fixture.copy(selectedThreadId = "pin"), {}, {}) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/thread-list-dark.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
    @Test fun renderOfflineConversation() {
        val state = fixture.copy(selectedThreadId = "pin", messages = listOf(
            ConversationMessage("user", "user", "Make the conversation calmer and easier to follow.", ordinal = 0),
            ConversationMessage("answer", "assistant", "## A clearer conversation\n\nYour **ideas stay in focus**, with room for the work to breathe.\n\n- Related activity stays together.\n- Code is easier to read.\n- New replies follow naturally.\n\n```kotlin\nConversation(state = state)\n```", ordinal = 3)
        ), activities = listOf(ToolActivity("read", "Read ThreadFeed.tsx", "Inspect message groups", runId = "run", ordinal = 1), ToolActivity("test", "Run Android checks", "BUILD SUCCESSFUL", runId = "run", ordinal = 2)))
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { Conversation(state, {}, {}, { _, _, _ -> }) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/conversation.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
}
