package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.*
import java.util.Locale

/** UI-origin identity accompanies every remote action; local completed-file actions work offline. */
data class FileDownloadUi(
    val environmentId: String? = null,
    val threadId: String? = null,
    val canDownload: Boolean = false,
    val downloads: Map<DownloadKey, FileDownloadState> = emptyMap(),
    val onDownload: (String, String, RemoteFileReference) -> Unit = { _, _, _ -> },
    val onCancel: (String, String, RemoteFileReference) -> Unit = { _, _, _ -> },
    val onOpen: (CompletedDownload) -> Unit = {},
    val onShare: (CompletedDownload) -> Unit = {},
    val onInstall: (CompletedDownload) -> Unit = {},
)
internal val LocalFileDownloadUi = staticCompositionLocalOf { FileDownloadUi() }

@Composable
internal fun MessageFileCards(message: ConversationMessage, modifier: Modifier = Modifier) {
    val files = remember(message) { if (message.streaming) emptyList() else message.remoteFiles() }
    if (files.isEmpty()) return
    Column(modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { reference -> key(reference.identity) { DownloadFileCard(reference) } }
    }
}

@Composable
internal fun DownloadFileCard(reference: RemoteFileReference, modifier: Modifier = Modifier) {
    val ui = LocalFileDownloadUi.current
    val key = ui.environmentId?.let { environmentId -> ui.threadId?.let { DownloadKey(environmentId, it, reference.identity) } }
    val state = ui.downloads[key]
    val completed = state?.completed?.takeIf { state.status == DownloadStatus.Complete }
    val downloading = state?.status == DownloadStatus.Downloading
    val apk = reference.fileName.endsWith(".apk", ignoreCase = true) || reference.mimeType == DownloadFileActions.APK_MIME
    fun remoteAction(action: (String, String, RemoteFileReference) -> Unit) {
        val environment = ui.environmentId ?: return
        val thread = ui.threadId ?: return
        action(environment, thread, reference)
    }
    OutlinedCard(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (apk) Icons.Default.Android else Icons.Default.InsertDriveFile, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(completed?.displayName ?: reference.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val label = when {
                        completed != null -> "${fileByteLabel(completed.sizeBytes)} · Saved to Downloads/Trefoil"
                        downloading -> fileByteLabel(state.bytesDownloaded) + (state.totalBytes?.let { " / ${fileByteLabel(it)}" } ?: " downloaded")
                        state?.status == DownloadStatus.Cancelled -> "Download cancelled"
                        state?.status == DownloadStatus.Failed -> "Download failed"
                        else -> listOfNotNull(if (apk) "Android package" else "File", reference.sizeBytes?.let(::fileByteLabel)).joinToString(" · ")
                    }
                    Text(label, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    downloading -> IconButton(onClick = { remoteAction(ui.onCancel) }) { Icon(Icons.Default.Close, "Cancel download ${reference.fileName}") }
                    completed != null -> IconButton(onClick = { remoteAction(ui.onDownload) }, enabled = ui.canDownload && key != null) {
                        Icon(Icons.Default.Refresh, "Download fresh copy of ${reference.fileName}")
                    }
                    completed == null -> IconButton(onClick = { remoteAction(ui.onDownload) }, enabled = ui.canDownload && key != null) {
                        Icon(Icons.Default.Download, if (state?.status in setOf(DownloadStatus.Cancelled, DownloadStatus.Failed)) "Retry download ${reference.fileName}" else "Download ${reference.fileName}")
                    }
                }
            }
            if (downloading) {
                val total = state.totalBytes
                if (total != null && total > 0) LinearProgressIndicator(progress = { (state.bytesDownloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            if (state?.status in setOf(DownloadStatus.Failed, DownloadStatus.Cancelled)) Text(state?.error ?: "Tap Retry to try again.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (completed != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { ui.onShare(completed) }) { Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                FilledTonalButton(onClick = { if (completed.isApk) ui.onInstall(completed) else ui.onOpen(completed) }) {
                    Icon(if (completed.isApk) Icons.Default.InstallMobile else Icons.Default.OpenInNew, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (completed.isApk) "Install" else "Open")
                }
            }
        }
    }
}

internal fun fileByteLabel(bytes: Long): String = when {
    bytes < 1024 -> "${bytes.coerceAtLeast(0)} B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
