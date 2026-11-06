package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.*
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
class FileDownloadCardsTest {
    @get:Rule val compose = createComposeRule()
    private val apk = RemoteFileReference(path = "/fixture/client/build/mymess-586-dev.apk", fileName = "mymess-586-dev.apk")
    private val key = DownloadKey("environment", "thread", apk.identity)
    private val saved = CompletedDownload(Uri.parse("content://media/external/downloads/123"), apk.fileName, DownloadFileActions.APK_MIME, 24_000_000)

    @Test fun prosePathCardDownloadsFromItsOriginAndNeverInstallsOnDownload() {
        var requested: Triple<String, String, RemoteFileReference>? = null
        var installs = 0
        val message = ConversationMessage("answer", "assistant", "Path: `${apk.path}`")
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi(
            "environment", "thread", true, onDownload = { e, t, r -> requested = Triple(e, t, r) }, onInstall = { installs++ })) {
            MessageFileCards(message)
        } } }
        compose.onNodeWithText(apk.fileName).assertExists()
        compose.onNodeWithContentDescription("Download ${apk.fileName}").performClick()
        assertEquals(Triple("environment", "thread", apk), requested)
        assertEquals(0, installs)
    }

    @Test fun staleCardDisablesDownloadAndStreamingPartialPathsStayHidden() {
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi("environment", "thread")) {
            Column {
                DownloadFileCard(apk)
                MessageFileCards(ConversationMessage("stream", "assistant", "Path: `/fixture/incomplete.apk`", streaming = true))
            }
        } } }
        compose.onNodeWithContentDescription("Download ${apk.fileName}").assertIsNotEnabled()
        compose.onNodeWithText("incomplete.apk").assertDoesNotExist()
    }

    @Test fun progressCanBeCancelledAndCompletedApkActionsWorkOffline() {
        val download = androidx.compose.runtime.mutableStateOf(FileDownloadState(apk, DownloadStatus.Downloading, 12_000_000, 24_000_000))
        var cancels = 0; var installs = 0; var shares = 0
        compose.setContent { TrefoilTheme { Surface { CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi(
            "environment", "thread", downloads = mapOf(key to download.value), onCancel = { _, _, _ -> cancels++ },
            onInstall = { assertEquals(saved, it); installs++ }, onShare = { assertEquals(saved, it); shares++ })) {
            Box(Modifier.width(379.dp).padding(8.dp)) { DownloadFileCard(apk) }
        } } } }
        compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f, 0f..1f))).assertExists()
        compose.onNodeWithContentDescription("Cancel download ${apk.fileName}").performClick()
        assertEquals(1, cancels)
        compose.runOnIdle { download.value = download.value.copy(status = DownloadStatus.Complete, completed = saved) }
        assertEquals(0, installs)
        compose.onNodeWithText("Install").assertIsEnabled().performClick()
        compose.onNodeWithText("Share").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Download fresh copy of ${apk.fileName}").assertIsNotEnabled()
        assertEquals(1, installs); assertEquals(1, shares)
        compose.waitForIdle()
        val output = File("build/reports/ui/file-download-apk.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun genericCompletedFileOpensAndFailedDownloadOffersExplicitRetry() {
        val document = RemoteFileReference(path = "/fixture/report.pdf", fileName = "report.pdf")
        val complete = CompletedDownload(Uri.parse("content://media/external/downloads/124"), document.fileName, "application/pdf", 100)
        var opened = false; var retries = 0
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi(
            "environment", "thread", true, downloads = mapOf(
                DownloadKey("environment", "thread", document.identity) to FileDownloadState(document, DownloadStatus.Complete, completed = complete),
                key to FileDownloadState(apk, DownloadStatus.Failed)), onOpen = { opened = it == complete }, onDownload = { _, _, _ -> retries++ })) {
            Column { DownloadFileCard(document); DownloadFileCard(apk) }
        } } }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithContentDescription("Retry download ${apk.fileName}").performClick()
        assertTrue(opened); assertEquals(1, retries)
        compose.onNodeWithText("Install").assertDoesNotExist()
    }

    @Test fun completedBuildCanBeExplicitlyDownloadedAgain() {
        var downloads = 0
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi(
            "environment", "thread", true, mapOf(key to FileDownloadState(apk, DownloadStatus.Complete, completed = saved)),
            onDownload = { _, _, _ -> downloads++ })) { DownloadFileCard(apk) } } }
        compose.onNodeWithContentDescription("Download fresh copy of ${apk.fileName}").assertIsEnabled().performClick()
        assertEquals(1, downloads)
    }
}
