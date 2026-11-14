package com.tajchert.trefoil.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.Question
import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.RuntimeMode
import com.tajchert.trefoil.core.model.RuntimeRequest
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.ToolActivity
import com.tajchert.trefoil.downloads.CompletedDownload
import com.tajchert.trefoil.downloads.DownloadKey
import com.tajchert.trefoil.downloads.DownloadStatus
import com.tajchert.trefoil.downloads.FileDownloadState
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.ui.SubagentRoster

/** Fake conversation data for screenshot previews. Message times are omitted so clock labels never vary. */
object ConversationFixtures {
    private val fx = ScreenshotFixtures
    val model = ModelOption(instanceId = "codex", model = "gpt-5", label = "GPT-5")

    val markdownReply = """
        I added the preview and wired it into the theme:

        - **Light** and **dark** variants
        - Fixtures use fake data only
        - Reference images are committed

        ```kotlin
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun ThreadList_Populated_Light() {
            TrefoilTheme(darkTheme = false) { ThreadList(state) }
        }
        ```

        Run `./gradlew :app:testDebugScreenshotTestDefaultTestSuite` to verify.
    """.trimIndent()

    val thread = fx.thread("t-chat", "Add screenshot previews", updatedMinutesAgo = 5, branch = "feat/previews")
    fun workingThread() = thread.copy(status = "running", activeRunId = "run-2", latestRunId = "run-2")

    private fun msg(id: String, role: String, text: String, ordinal: Int, streaming: Boolean = false, attachments: List<RemoteFileReference> = emptyList()) =
        ConversationMessage(id = id, role = role, text = text, streaming = streaming, ordinal = ordinal, attachments = attachments)

    private fun act(id: String, title: String, kind: String, status: String, ordinal: Int, input: String? = null, output: String? = null,
                    fileName: String? = null, diff: String? = null, exit: Int? = null) =
        ToolActivity(id = id, title = title, kind = kind, status = status, ordinal = ordinal, input = input, output = output,
            fileName = fileName, diffStr = diff, exitCode = exit)

    val commandActivity = act("a-cmd", "Ran command", "command_execution", "completed", 2, input = "./gradlew :app:lintDebug", output = "BUILD SUCCESSFUL in 12s", exit = 0)
    val editActivity = act("a-edit", "Edited file", "file_change", "completed", 3, fileName = "ui/ThreadList.kt",
        diff = "--- a/ui/ThreadList.kt\n+++ b/ui/ThreadList.kt\n@@ -1,2 +1,3 @@\n-old line\n+new line\n+another line\n context")
    val searchActivity = act("a-search", "Searched files", "web_search", "completed", 4, input = "PreviewTest annotation")
    val runningActivity = act("a-run", "Running tests", "command_execution", "running", 5, input = "./gradlew :app:testDebugUnitTest")
    val mixedActivities = listOf(commandActivity, editActivity, searchActivity)

    fun chatState(): ClientState = fx.connectedState(listOf(thread)).copy(
        selectedThreadId = thread.id, selectedModel = model, selectedRuntimeMode = RuntimeMode.ApprovalRequired, models = listOf(model),
        messages = listOf(
            msg("m1", "user", "Can you add a dark theme preview for the thread list?", 1),
            msg("m2", "assistant", markdownReply, 6),
        ),
        activities = listOf(commandActivity, editActivity),
    )

    fun workingState(): ClientState = chatState().copy(
        threads = listOf(workingThread()), activeRunId = "run-2",
        messages = listOf(
            msg("m1", "user", "Now run the unit tests too.", 1),
            msg("m2", "assistant", "Starting the test run. I will check the **unit** tests first and then", 3, streaming = true),
        ),
        activities = listOf(runningActivity.copy(ordinal = 2)),
    )

    fun disconnectedState(): ClientState = chatState().copy(connection = ConnectionStatus.Disconnected, canWrite = false, canWriteThreads = false)

    val approval = RuntimeRequest(id = "req-1", title = "Run command", detail = "./gradlew :core:ssh:test")
    val choiceRequest = RuntimeRequest(id = "req-2", title = "Which test suite should I run?",
        questions = listOf(Question(id = "q1", title = "Pick one", options = listOf("Unit tests", "Screenshot tests", "Everything"))))

    fun approvalState(): ClientState = chatState().copy(requests = listOf(approval))
    fun choiceState(): ClientState = chatState().copy(requests = listOf(choiceRequest))

    // ---- Subagents ----
    private fun agent(id: String, title: String, status: String, driver: String = "codex", result: String? = null, started: String? = null, completed: String? = null) =
        Subagent(id = id, threadId = "t-chat", runId = "run-1", origin = "app_owned", driver = driver, childThreadId = "child-$id", title = title,
            model = "gpt-5", status = status, result = result, startedAt = started, completedAt = completed, prompt = title)

    val subagents = listOf(
        agent("s1", "Audit SSH host key handling", "running"),
        agent("s2", "Update screenshot README", "completed", result = "README updated with the new commands.",
            started = "2026-01-01T10:00:00Z", completed = "2026-01-01T10:02:05Z"),
        agent("s3", "Migrate usage widget tests", "failed", driver = "claudeAgent", result = "Gradle build failed: unresolved reference UsageRow.",
            started = "2026-01-01T10:00:00Z", completed = "2026-01-01T10:00:42Z"),
        agent("s4", "Review dark theme tokens", "running", driver = "claudeAgent"),
    )

    internal fun roster(list: List<Subagent> = subagents) = SubagentRoster(
        runId = "run-1", turnActive = true, latestTurn = true, subagents = list,
        liveCount = list.count { it.isActive }, settledCount = list.count { it.isTerminal },
    )

    val subagentThread: ThreadSummary = fx.thread("t-child", "Audit SSH host key handling", status = "idle", updatedMinutesAgo = 2)
        .copy(model = "gpt-5", parentThreadId = "t-chat", relationshipToParent = "subagent")

    // ---- Files ----
    val apk = RemoteFileReference(path = "/home/dev/trefoil/app-debug.apk", fileName = "app-debug.apk", mimeType = "application/vnd.android.package-archive", sizeBytes = 18_400_000)
    val report = RemoteFileReference(path = "/home/dev/trefoil/report.pdf", fileName = "report.pdf", mimeType = "application/pdf", sizeBytes = 482_000)
    val screenshot = RemoteFileReference(path = "/home/dev/trefoil/screen.png", fileName = "screen.png", mimeType = "image/png", sizeBytes = 120_000)
    val fileMessage = msg("mf", "user", "Attached build output.", 1, attachments = listOf(apk, report))

    private const val ENV = "env-workstation"
    private const val THREAD = "t-chat"
    private fun key(r: RemoteFileReference) = DownloadKey(ENV, THREAD, r.identity)

    fun downloadUi(vararg states: Pair<RemoteFileReference, FileDownloadState>) = FileDownloadUi(
        environmentId = ENV, threadId = THREAD, canDownload = true, downloads = states.associate { key(it.first) to it.second },
    )
    fun downloading(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Downloading, bytesDownloaded = 7_400_000, totalBytes = r.sizeBytes)
    fun failed(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Failed, error = "Connection lost. Tap Retry to try again.")
    fun done(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Complete,
        completed = CompletedDownload(Uri.parse("content://example.test/${r.fileName}"), r.fileName, r.mimeType ?: "application/octet-stream", r.sizeBytes ?: 0))

    /** A synthetic gradient "photo" so the loaded inline image is visibly non-blank. */
    fun sampleImage(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 640f, 400f, Color.rgb(46, 125, 226), Color.rgb(236, 120, 80), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 400f, paint)
        paint.shader = null
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawCircle(200f, 160f, 70f, paint)
        canvas.drawRect(300f, 220f, 560f, 340f, paint)
        return bitmap.asImageBitmap()
    }
}
