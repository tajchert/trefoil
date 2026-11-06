package com.tajchert.trefoil.downloads

import android.net.Uri
import android.webkit.MimeTypeMap
import com.tajchert.trefoil.core.model.RemoteFileReference

data class DownloadKey(val environmentId: String, val threadId: String, val referenceId: String) {
    companion object {
        fun referenceId(reference: RemoteFileReference): String = reference.identity
    }
    override fun toString() = "DownloadKey(***)"
}

enum class DownloadStatus { Downloading, Complete, Failed, Cancelled }

data class CompletedDownload(val uri: Uri, val displayName: String, val mimeType: String, val sizeBytes: Long) {
    val isApk: Boolean get() = displayName.endsWith(".apk", ignoreCase = true) || mimeType == DownloadFileActions.APK_MIME
    override fun toString() = "CompletedDownload(sizeBytes=$sizeBytes)"
}

data class FileDownloadState(
    val reference: RemoteFileReference,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val completed: CompletedDownload? = null,
    val error: String? = null,
    internal val attempt: Long = 0,
) {
    override fun toString() = "FileDownloadState(status=$status, bytesDownloaded=$bytesDownloaded)"
}

/** A display name only: it is never used to resolve a local or remote filesystem path. */
internal fun safeDownloadName(value: String): String {
    val leaf = value.substringAfterLast('/').substringAfterLast('\\')
    val clean = leaf.map { character ->
        if (character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt() || character in ":*?\"<>|") '_' else character
    }.joinToString("").trim(' ', '.')
    if (clean.isEmpty()) return "download"
    if (clean.length <= 160) return clean
    val suffix = clean.substringAfterLast('.', "").takeIf { it.length in 1..16 }?.let { ".$it" }.orEmpty()
    return clean.take(160 - suffix.length).trimEnd(' ', '.') + suffix
}

internal fun downloadMimeType(fileName: String, supplied: String?): String {
    if (fileName.endsWith(".apk", ignoreCase = true)) return DownloadFileActions.APK_MIME
    val normalized = supplied?.substringBefore(';')?.trim()?.lowercase()
    if (normalized != null && normalized.matches(Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) && normalized != "application/octet-stream") return normalized
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
}
