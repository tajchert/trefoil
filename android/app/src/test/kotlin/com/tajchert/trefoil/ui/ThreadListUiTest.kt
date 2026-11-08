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
        providers = listOf(ProviderInstance("team", "codex", "Work Codex", "#326B41"), ProviderInstance("claudeAgent", "claudeAgent", "Claude")),
        projects = listOf(Project("trefoil", "Trefoil"), Project("t3", "T3 Code")),
        threads = listOf(
            ThreadSummary("pin", "trefoil", "Give every conversation a little more clarity", "completed", pinnedAt = "2026-11-06T08:00:00Z", branch = "feat/conversation-ui", providerInstanceId = "team", providerInstanceHistory = listOf("claudeAgent")),
            ThreadSummary("approval", "trefoil", "Review the connection flow", "running", pendingRequestKind = "approval"),
            ThreadSummary("active", "trefoil", "Polish the Android thread list", "completed"),
            ThreadSummary("work", "t3", "Keep activity in the right order", "running", providerInstanceId = "claudeAgent", activityRunStartedAt = java.time.Instant.now().minusSeconds(150).toString(), activityRunStartedAtPresent = true),
            ThreadSummary("done", "trefoil", "Secure environment storage", "completed", settledOverride = "settled", settledAt = "2026-11-06T08:00:00Z")
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
            { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithContentDescription("Working shelf").performClick()
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithText("Polish the Android thread list").performClick()
        compose.onNodeWithContentDescription("Back to threads").performClick()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithText("Search threads").performTextInput("Polish")
        compose.onNodeWithText("Polish the Android thread list").performClick()
        compose.onNodeWithContentDescription("Back to threads").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Polish")
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun providerMarksAndWorkingDurationUseServerMetadata() {
        compose.setContent { TrefoilTheme { ThreadList(fixture, {}, {}) } }
        compose.onNodeWithContentDescription("Agent: Work Codex").assertExists()
        compose.onNodeWithContentDescription("Agent: Claude").assertExists()
        compose.onNodeWithText("Working 2m").assertExists()
    }
    @Test fun retainedClientSelectionDoesNotHighlightAThreadWhenListIsShownAgain() {
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(selectedThreadId = "active"), {}, {}) } }
        compose.onNodeWithText("Polish the Android thread list").assertExists()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
    }
    @Test fun hiddenChildDoesNotInflateConversationCountOrAppearInSearch() {
        val state = fixture.copy(threads = listOf(ThreadSummary("parent", "trefoil", "Parent conversation"),
            ThreadSummary("child", "trefoil", "Hidden fixture child", relationshipToParent = "subagent", parentThreadId = "parent")))
        compose.setContent { TrefoilTheme { ThreadList(state, {}, {}) } }
        compose.onNodeWithText("1 conversations · 2 projects").assertExists()
        compose.onNodeWithText("Hidden fixture child").assertDoesNotExist()
        compose.onNodeWithText("Search threads").performTextInput("Hidden fixture child")
        compose.onNodeWithText("No matching threads").assertExists()
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
    @Test fun renderCommandWorkLog() {
        val state = fixture.copy(selectedThreadId = "work", activeRunId = "run", messages = listOf(
            ConversationMessage("prompt", "user", "Run the Android checks and investigate any failures.", runId = "run", ordinal = 0)),
            activities = listOf(
                ToolActivity("search", "file_search", kind = "file_search", status = "completed", input = "ConnectionPolicy", runId = "run", ordinal = 1),
                ToolActivity("command", "command_execution", kind = "command_execution", status = "failed", input = "./gradlew :app:testDebugUnitTest", output = "> Task :app:testDebugUnitTest\n\nConnectionPolicyTest > validates consent FAILED\nExpected explicit private LAN consent.\n\n1 test failed", exitCode = 1, runId = "run", ordinal = 2)))
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { Conversation(state, {}, {}, { _, _, _ -> }) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/command-work-log.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
    @Test fun renderOfflineConversation() {
        val state = fixture.copy(selectedThreadId = "pin", messages = listOf(
            ConversationMessage("user", "user", "Make the conversation calmer and easier to follow.", ordinal = 0, runId = "run", createdAt = "2026-11-06T10:00:00Z"),
            ConversationMessage("commentary", "assistant", "I’ll compare the message hierarchy and run the Android checks.", ordinal = 1, runId = "run"),
            ConversationMessage("answer", "assistant", "## A clearer conversation\n\nYour **ideas stay in focus**, with room for the work to breathe.\n\n- Related activity stays together.\n- Code is easier to read.\n- New replies follow naturally.\n\n```kotlin\nConversation(state = state)\n```", ordinal = 4, runId = "run", createdAt = "2026-11-06T10:01:30Z")
        ), activities = listOf(ToolActivity("read", "Read ThreadFeed.tsx", "Inspect message groups", kind = "file_read", status = "completed", runId = "run", ordinal = 2), ToolActivity("test", "Run Android checks", kind = "command_execution", status = "completed", input = "./gradlew test", output = "BUILD SUCCESSFUL", exitCode = 0, runId = "run", ordinal = 3)),
            runs = listOf(ConversationRun("run", "completed", startedAt = "2026-11-06T10:00:00Z", completedAt = "2026-11-06T10:01:30Z")))
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { Conversation(state, {}, {}, { _, _, _ -> }) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/conversation.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
}
