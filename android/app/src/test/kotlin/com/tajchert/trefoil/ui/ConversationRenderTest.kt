package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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

/** Phone-size renders of the redesigned conversation screen, compared by eye against Thread.dc.html. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationRenderTest {
    @get:Rule val compose = createComposeRule()

    private val models = listOf(ModelOption("codex", "opus", "Opus 5.5"))
    private val base = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true,
        projects = listOf(Project("p", "backend")),
        threads = listOf(ThreadSummary("t", "p", "Investigate Missing City Districts", branch = "chore/cams-ads-key",
            activeRunId = "run2", latestRunId = "run2", latestRunStartedAt = "2026-11-06T10:00:00Z")),
        selectedThreadId = "t", models = models, selectedModel = models.first(), selectedRuntimeMode = RuntimeMode.FullAccess,
    )
    private val user = ConversationMessage("u", "user", "Why do Kraków stations show “Kraków › Kraków”? Plan a fix. SELECT-only on prod.", runId = "run", ordinal = 0, createdAt = "2026-11-06T10:00:00Z")
    private val answer = ConversationMessage("a", "assistant", "**The repair tool can’t find these stations.** Its query never selects self-named districts; v2 adds a `selector` with dry-run and apply modes.\n\n- Only 130 new Polish districts; 5 cities gain real ones.\n- Forecast targets drop from 189 to 91.\n- 2,825 district URLs will 404 → redirect table.\n\n```kotlin\nval plan = Selector(dryRun = true)\nplan.apply()\n```", runId = "run", ordinal = 4, createdAt = "2026-11-06T10:06:00Z")
    private val work = listOf(
        ToolActivity("c1", "command_execution", kind = "command_execution", status = "completed", input = "psql -c select", runId = "run", ordinal = 1),
        ToolActivity("c2", "command_execution", kind = "command_execution", status = "completed", input = "rg selector", runId = "run", ordinal = 2),
        ToolActivity("e1", "file_change", kind = "file_change", status = "completed", fileName = "docs/plans/districts.md", diffStr = "+a\n+b\n-c", runId = "run", ordinal = 3),
        ToolActivity("s1", "file_search", kind = "file_search", status = "completed", input = "Selector", runId = "run", ordinal = 3))

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { TrefoilTheme(darkTheme = dark) { Box(Modifier.size(390.dp, 844.dp).background(Tf.colors.bg)) { content() } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/design-$name-${if (dark) "dark" else "light"}.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    private fun both(name: String, content: @Composable () -> Unit) {
        render(name, true, content)
    }

    private val needsYou get() = base.copy(activeRunId = null, threads = base.threads.map { it.copy(activeRunId = null) },
        messages = listOf(user, answer.copy(id = "a2", runId = "run2", ordinal = 9, text = "Start implementing, beginning with steps 1–2?")),
        requests = listOf(RuntimeRequest("approval", "Run command", "psql -h prod -c \"select * from districts where name = city\"")))
    private val working get() = base.copy(activeRunId = "run2", messages = listOf(user.copy(runId = "run2")), activities = work.map { it.copy(runId = "run2", status = if (it.id == "s1") "running" else "completed") })
    private val completed get() = base.copy(activeRunId = null, threads = base.threads.map { it.copy(activeRunId = null) }, messages = listOf(user, answer), activities = work,
        runs = listOf(ConversationRun("run", "completed", startedAt = "2026-11-06T10:00:00Z", completedAt = "2026-11-06T10:06:00Z")))
    private val questions get() = base.copy(activeRunId = null, messages = listOf(user),
        requests = listOf(RuntimeRequest("q", "Question", questions = listOf(Question("go", "Start implementing, beginning with steps 1–2?", listOf("Start steps 1–2", "Commit plan", "Revise"))))))

    @Test fun needsYouDark() = render("thread-needs-you", true) { Conversation(needsYou, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun needsYouLight() = render("thread-needs-you", false) { Conversation(needsYou, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun questionDark() = render("thread-question", true) { Conversation(questions, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun workingDark() = render("thread-working", true) { Conversation(working, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun workingLight() = render("thread-working", false) { Conversation(working, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun completedDark() = render("thread-completed", true) { Conversation(completed, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun completedLight() = render("thread-completed", false) { Conversation(completed, {}, {}, { _, _, _ -> }, onBack = {}) }
    @Test fun draftDark() = render("thread-draft", true) { Conversation(base.copy(selectedThreadId = null), {}, {}, { _, _, _ -> }, draft = ThreadDraft("p", models.first()), onBack = {}) }
    @Test fun draftLight() = render("thread-draft", false) { Conversation(base.copy(selectedThreadId = null), {}, {}, { _, _, _ -> }, draft = ThreadDraft("p", models.first()), onBack = {}) }
    @Test fun emptyDark() = render("thread-empty", true) { Conversation(ClientState(), {}, {}, { _, _, _ -> }) }

    @Test fun seedPromptIsSubmittedExactlyOnceThroughTheNormalSendPath() {
        val sends = mutableListOf<String>()
        var consumed = 0
        val seed = mutableStateOf<String?>("Build the thing")
        compose.setContent {
            TrefoilTheme { Conversation(base.copy(selectedThreadId = null), { sends += it }, {}, { _, _, _ -> }, draft = ThreadDraft("p", models.first()),
                seedPrompt = seed.value, onSeedConsumed = { consumed++; seed.value = null }) }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("Build the thing"), sends); assertEquals(1, consumed) }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun stopReplacesSendWhileRunningAndInterrupts() {
        var stops = 0
        compose.setContent { TrefoilTheme { Conversation(working, {}, { stops++ }, { _, _, _ -> }) } }
        compose.onNodeWithText("Queue a message…").assertExists()
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("Queue a message…").performTextInput("next")
        compose.onNodeWithContentDescription("Queue message").assertIsEnabled()
    }

    @Test fun quickReplyChipsRespondWithTheSelectedOption() {
        val responses = mutableListOf<Triple<String, String, Map<String, List<String>>>>()
        compose.setContent { TrefoilTheme { Conversation(questions, {}, {}, { id, decision, answers -> responses += Triple(id, decision, answers) }) } }
        compose.onNodeWithText("Commit plan").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("q", "accept", mapOf("go" to listOf("Commit plan")))), responses) }
    }
}
