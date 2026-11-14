package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineImageUiTest {
    @get:Rule val compose = createComposeRule()
    private val shot = RemoteFileReference(path = "/fixture/screenshot.png", fileName = "screenshot.png", mimeType = "image/png")
    private val message = ConversationMessage("a", "assistant", "Here is the result: `/fixture/screenshot.png`\n\nAlso `/fixture/notes.txt`")

    private fun ui(client: ImageClient, downloads: Map<DownloadKey, FileDownloadState> = emptyMap(), onDownload: (String, String, RemoteFileReference) -> Unit = { _, _, _ -> }) =
        FileDownloadUi("fixture", "thread", true, downloads, onDownload, imageLoader = InlineImageLoader(client))

    @Test fun rendersImageInlineAndKeepsExplicitDownload() {
        var requested: RemoteFileReference? = null
        val client = ImageClient(fixturePng(480, 300))
        compose.setContent { TrefoilTheme(darkTheme = true) { CompositionLocalProvider(LocalFileDownloadUi provides ui(client, onDownload = { _, _, r -> requested = r })) {
            Box(Modifier.size(390.dp, 520.dp).background(Tf.colors.bg).padding(16.dp)) { MessageFileCards(message) }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("screenshot.png").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText("screenshot.png").assertDoesNotExist()
        compose.onNodeWithContentDescription("Download screenshot.png").performClick()
        assertEquals(shot.path, requested?.path)
        val output = File("build/reports/ui/design-thread-image-dark.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Test fun tappingOpensFullScreen() {
        val client = ImageClient(fixturePng(100, 100))
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui(client)) { InlineOrCard(shot) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("screenshot.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("screenshot.png").performClick()
        compose.onNodeWithContentDescription("Close image").assertExists()
    }

    @Test fun failureFallsBackToDownloadCard() {
        val client = ImageClient(ByteArray(0), fail = true)
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui(client)) { InlineOrCard(shot) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("screenshot.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Download screenshot.png").assertExists()
    }

    @Test fun imageOnlyActivityExpandsAndOpensViewer() {
        val client = ImageClient(fixturePng(100, 100))
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui(client)) {
            ActivityStack(listOf(ToolActivity("image", "View image", status = "completed", images = listOf(shot))))
        } } }
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("screenshot.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("screenshot.png").performClick()
        compose.onNodeWithContentDescription("Close image").assertExists()
    }

    @Test fun commandOutputAndImageBothRenderAndDownloadUsesReference() {
        var requested: RemoteFileReference? = null
        val client = ImageClient(fixturePng(100, 100))
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui(client, onDownload = { _, _, r -> requested = r })) {
            ActivityStack(listOf(ToolActivity("command", "Run", kind = "command_execution", status = "completed", output = "Fixture output", images = listOf(shot))))
        } } }
        compose.onNodeWithContentDescription("Expand commands").performClick()
        compose.onNodeWithText("Run").performClick()
        compose.onNodeWithText("Fixture output").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Download screenshot.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Download screenshot.png").performClick()
        assertEquals(shot, requested)
    }
}
