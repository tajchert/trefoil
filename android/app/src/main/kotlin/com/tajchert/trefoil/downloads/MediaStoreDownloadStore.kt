package com.tajchert.trefoil.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.tajchert.trefoil.core.model.DownloadedFile
import com.tajchert.trefoil.core.model.RemoteFileReference
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface DownloadStore {
    suspend fun cleanupPendingDownloads() {}
    suspend fun save(reference: RemoteFileReference, canComplete: () -> Boolean,
        transfer: suspend (OutputStream) -> DownloadedFile): CompletedDownload
}

/** Pending rows are private until the stream closes successfully; partial downloads are deleted. */
class MediaStoreDownloadStore(context: Context) : DownloadStore {
    private val resolver = context.applicationContext.contentResolver
    // Only local content URIs, never remote references, URLs, or credentials.
    private val journal = context.applicationContext.getSharedPreferences("pending_downloads", Context.MODE_PRIVATE)
    private val initialization = Mutex()
    private val journalLock = Any()
    private var initialized = false

    override suspend fun cleanupPendingDownloads() = withContext(Dispatchers.IO) { initialize() }

    private suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            journal.getStringSet("uris", emptySet()).orEmpty().toSet().forEach { value ->
                val uri = Uri.parse(value)
                if (uri.scheme == "content" && uri.authority == "media") {
                    runCatching {
                        val pending = resolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)?.use {
                            if (it.moveToFirst()) it.getInt(0) == 1 else false
                        } ?: return@runCatching
                        if (pending) resolver.delete(uri, null, null)
                        untrack(uri)
                    }
                } else untrack(uri)
            }
            initialized = true
        }
    }

    override suspend fun save(reference: RemoteFileReference, canComplete: () -> Boolean,
        transfer: suspend (OutputStream) -> DownloadedFile): CompletedDownload = withContext(Dispatchers.IO) {
        initialize()
        currentCoroutineContext().ensureActive()
        check(canComplete()) { "Download context changed" }
        val name = safeDownloadName(reference.fileName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, downloadMimeType(name, reference.mimeType))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Trefoil/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // Always insert a new item: the provider resolves name collisions without overwriting files.
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Could not create download" }
        var complete = false
        try {
            track(uri)
            val downloaded = checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not write download" }.use { transfer(it) }
            currentCoroutineContext().ensureActive()
            check(canComplete()) { "Download context changed" }
            val finalName = safeDownloadName(downloaded.fileName)
            val mime = downloadMimeType(finalName, downloaded.mimeType)
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1) { "Could not finish download" }
            complete = true
            untrack(uri)
            CompletedDownload(uri, finalName, mime, downloaded.sizeBytes)
        } finally {
            if (!complete) runCatching { resolver.delete(uri, null, null) }.onSuccess { untrack(uri) }
        }
    }

    private fun track(uri: Uri) = synchronized(journalLock) {
        val pending = journal.getStringSet("uris", emptySet()).orEmpty().toMutableSet().apply { add(uri.toString()) }
        check(journal.edit().putStringSet("uris", pending).commit()) { "Could not track pending download" }
    }

    private fun untrack(uri: Uri) = synchronized(journalLock) {
        val pending = journal.getStringSet("uris", emptySet()).orEmpty().toMutableSet().apply { remove(uri.toString()) }
        journal.edit().putStringSet("uris", pending).commit()
        Unit
    }
}
