package com.tajchert.trefoil

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.InlineImageLoader
import com.tajchert.trefoil.ui.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** Offline visual fixtures, available only in debug builds. Never opens a client or a vault. */
class PresentationPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Instant.now()
        fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()
        val threads = listOf(
            ThreadSummary("design", "trefoil", "Give every conversation a little more clarity", "completed", pinnedAt = ago(120), updatedAt = ago(12), branch = "feat/conversation-ui"),
            ThreadSummary("review", "trefoil", "Review the connection flow", "running", pendingRequestKind = "approval", updatedAt = ago(2)),
            ThreadSummary("android", "trefoil", "Polish the Android thread list", "completed", updatedAt = ago(8)),
            ThreadSummary("protocol", "t3", "Keep activity in the right order", "running", latestUserMessageAt = ago(3), updatedAt = ago(1)),
            ThreadSummary("tests", "t3", "Add fixtures for streamed responses", "running", latestUserMessageAt = ago(8)),
            ThreadSummary("done", "trefoil", "Secure environment storage", "completed", settledAt = ago(140)),
        )
        val fixture = ClientState(connection = ConnectionStatus.Connected, projects = listOf(Project("trefoil", "Trefoil"), Project("t3", "T3 Code")), threads = threads, canWrite = true,
            selectedThreadId = "design", messages = listOf(
                ConversationMessage("user", "user", "Make the conversation feel calmer and easier to follow.", createdAt = ago(14), ordinal = 0),
                ConversationMessage("reply", "assistant", "I’ll simplify the hierarchy and keep related work together.", createdAt = ago(13), ordinal = 1),
                ConversationMessage("final", "assistant", "## A clearer conversation\n\nThe new layout keeps your **ideas in focus** and gives the work room to breathe.\n\n- Related activity stays in one expandable stack.\n- Code is easier to read and copy.\n- New replies follow naturally as they arrive.\n\n```kotlin\nConversation(\n    state = state,\n    onSend = ::sendMessage\n)\n```\n\nReady for your next idea.", createdAt = ago(12), ordinal = 4)
            ), activities = listOf(
                ToolActivity("read", "Read ThreadFeed.tsx", "Inspect conversation spacing and message groups", kind = "tool_call", status = "completed", runId = "run", ordinal = 2),
                ToolActivity("build", "Run Android checks", "BUILD SUCCESSFUL", kind = "tool_call", status = "completed", runId = "run", ordinal = 3)
            ))
        setContent {
            TrefoilTheme {
                if (intent?.data?.host == "images") {
                    val image = RemoteFileReference(path = "/fixture/preview.png", fileName = "preview.png", mimeType = "image/png")
                    val loader = remember { InlineImageLoader(PreviewImageClient()) }
                    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                        CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi("fixture", "fixture", true, imageLoader = loader)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActivityStack(listOf(ToolActivity("image", "View image", status = "completed", images = listOf(image))))
                                ActivityStack(listOf(ToolActivity("output", "Inspect image", status = "completed", output = "Synthetic image output", images = listOf(image))))
                            }
                        }
                    }
                    return@TrefoilTheme
                }
                var selected by remember { mutableStateOf(intent?.data?.host == "conversation") }
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (selected) Conversation(fixture, {}, {}, { _, _, _ -> })
                    else ThreadList(fixture.copy(selectedThreadId = null), { selected = true }, {})
                }
            }
        }
    }
}

/** Synthetic bitmap source for the isolated image activity preview. */
private class PreviewImageClient : TrefoilClient {
    override val state = MutableStateFlow(ClientState(environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture"), connection = ConnectionStatus.Connected, selectedThreadId = "fixture"))
    override suspend fun downloadFile(reference: RemoteFileReference, output: OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit): DownloadedFile {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(70, 125, 160))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        output.write(bytes)
        return DownloadedFile(reference.fileName, "image/png", bytes.size.toLong())
    }
    override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
    override suspend fun connect(environment: SavedEnvironment) {}
    override suspend fun disconnect() {}
    override suspend fun selectThread(threadId: String) {}
    override suspend fun sendMessage(text: String) = error("unused")
    override suspend fun interruptRun() = error("unused")
    override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("unused")
    override suspend fun reconnect() {}
    override fun close() {}
}
