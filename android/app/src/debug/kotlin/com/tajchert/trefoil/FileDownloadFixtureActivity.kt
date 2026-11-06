package com.tajchert.trefoil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.*
import com.tajchert.trefoil.ui.*
import java.io.File
import kotlinx.coroutines.*

/** Local debug APK copy exercises MediaStore and system installer without any T3/user data. */
class FileDownloadFixtureActivity : ComponentActivity() {
    private val reference = RemoteFileReference(path = "/fixture/trefoil-fixture.apk", fileName = "trefoil-fixture.apk")
    private val downloadKey = DownloadKey("fixture", "fixture", reference.identity)
    private val downloads = mutableStateOf<Map<DownloadKey, FileDownloadState>>(emptyMap())
    private var transfer: Job? = null
    private val store by lazy { MediaStoreDownloadStore(this) }
    private val actions by lazy { DownloadFileActions(this) }

    private fun download() {
        if (transfer?.isActive == true) return
        transfer = lifecycleScope.launch {
            downloads.value = mapOf(downloadKey to FileDownloadState(reference, DownloadStatus.Downloading))
            try {
                val completed = store.save(reference, { true }) { output ->
                    val source = File(applicationInfo.sourceDir)
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024); var bytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count); bytes += count
                            withContext(Dispatchers.Main) { downloads.value = mapOf(downloadKey to FileDownloadState(reference, DownloadStatus.Downloading, bytes, source.length())) }
                        }
                        DownloadedFile(reference.fileName, DownloadFileActions.APK_MIME, bytes)
                    }
                }
                downloads.value = mapOf(downloadKey to FileDownloadState(reference, DownloadStatus.Complete, completed = completed))
            } catch (cancelled: CancellationException) {
                downloads.value = mapOf(downloadKey to FileDownloadState(reference, DownloadStatus.Cancelled)); throw cancelled
            } catch (_: Exception) { downloads.value = mapOf(downloadKey to FileDownloadState(reference, DownloadStatus.Failed)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrefoilTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Isolated APK download", style = MaterialTheme.typography.titleLarge)
                Text("Copies this debug build into Downloads/Trefoil. No server or user threads are accessed.")
                CompositionLocalProvider(LocalFileDownloadUi provides FileDownloadUi("fixture", "fixture", true, downloads.value,
                    onDownload = { _, _, _ -> download() }, onCancel = { _, _, _ -> transfer?.cancel() },
                    onShare = { startActivity(actions.shareIntent(it)) }, onInstall = {
                        startActivity(if (actions.canInstallPackages()) actions.installIntent(it) else actions.unknownSourcesIntent())
                    })) { DownloadFileCard(reference) }
            }
        } } }
    }
}
