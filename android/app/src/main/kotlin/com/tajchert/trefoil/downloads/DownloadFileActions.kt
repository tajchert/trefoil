package com.tajchert.trefoil.downloads

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Builds intents only. Download completion and returning from Settings never launch anything. */
class DownloadFileActions(private val context: Context) {
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null))

    fun installIntent(file: CompletedDownload): Intent {
        require(file.isApk) { "This file is not an Android package" }
        check(canInstallPackages()) { "Allow Trefoil to install apps in Android Settings first" }
        return viewIntent(file, APK_MIME)
    }

    fun openIntent(file: CompletedDownload): Intent = if (file.isApk) installIntent(file)
        else Intent.createChooser(viewIntent(file, file.mimeType), "Open file")

    fun shareIntent(file: CompletedDownload): Intent {
        requireContentUri(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.isApk) APK_MIME else file.mimeType
            putExtra(Intent.EXTRA_STREAM, file.uri)
            clipData = ClipData.newRawUri("Downloaded file", file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share file")
    }

    private fun viewIntent(file: CompletedDownload, mimeType: String): Intent {
        requireContentUri(file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mimeType)
            clipData = ClipData.newRawUri("Downloaded file", file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun requireContentUri(file: CompletedDownload) {
        require(file.uri.scheme == "content" && file.uri.authority == "media") { "Only downloaded content can be opened" }
    }

    companion object { const val APK_MIME = "application/vnd.android.package-archive" }
}
