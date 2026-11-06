package com.tajchert.trefoil.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.tajchert.trefoil.core.model.DownloadedFile
import com.tajchert.trefoil.core.model.RemoteFileReference
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreDownloadStoreTest {
    private val application: Context get() = RuntimeEnvironment.getApplication()
    private val reference = RemoteFileReference(path = "/fixture/release.apk", fileName = "../../release.apk")
    private lateinit var provider: DownloadsProvider

    @Before fun setUp() {
        application.getSharedPreferences("pending_downloads", Context.MODE_PRIVATE).edit().clear().commit()
        provider = DownloadsProvider()
        provider.attachInfo(application, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun fileIsPrivateUntilStreamClosesAndOnlyThenPublishedInDownloads() = runBlocking {
        val store = MediaStoreDownloadStore(application)
        val file = store.save(reference, { true }) { output ->
            val row = provider.rows.values.single()
            assertEquals(1, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertEquals("Download/Trefoil/", row.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals("release.apk", row.getAsString(MediaStore.Downloads.DISPLAY_NAME))
            output.write(byteArrayOf(1, 2, 3))
            DownloadedFile("release.apk", "application/octet-stream", 3)
        }
        assertTrue(file.isApk)
        assertEquals(0, provider.rows[file.uri]!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(3, file.sizeBytes)
        assertTrue(application.getSharedPreferences("pending_downloads", Context.MODE_PRIVATE).getStringSet("uris", emptySet())!!.isEmpty())
        // Another download inserts a new row even when the file name is identical.
        val second = store.save(reference, { true }) { DownloadedFile("release.apk", "application/octet-stream", 0) }
        assertNotEquals(file.uri, second.uri)
        assertEquals(2, provider.rows.size)
    }

    @Test fun failedCancelledAndStaleDownloadsDeleteTheirPendingRow() = runBlocking {
        val store = MediaStoreDownloadStore(application)
        try { store.save(reference, { true }) { it.write(1); throw IOException("fixture") }; fail() } catch (_: IOException) {}
        assertTrue(provider.rows.isEmpty())
        try { store.save(reference, { true }) { throw CancellationException("fixture") }; fail() } catch (_: CancellationException) {}
        assertTrue(provider.rows.isEmpty())
        var current = true
        try {
            store.save(reference, { current }) { current = false; DownloadedFile("release.apk", "application/octet-stream", 0) }
            fail()
        } catch (_: IllegalStateException) {}
        assertTrue(provider.rows.isEmpty())
        assertEquals(3, provider.deleted.size)
    }

    @Test fun startupCleanupDeletesCrashPartialsButPreservesCompletedFileWithStaleJournalEntry() = runBlocking {
        val partial = provider.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) })
        val completed = provider.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) })
        application.getSharedPreferences("pending_downloads", Context.MODE_PRIVATE).edit()
            .putStringSet("uris", setOf(partial.toString(), completed.toString())).commit()
        MediaStoreDownloadStore(application).cleanupPendingDownloads()
        assertFalse(provider.rows.containsKey(partial))
        assertTrue(provider.rows.containsKey(completed))
        assertEquals(listOf(partial), provider.deleted)
    }

    private class DownloadsProvider : ContentProvider() {
        val rows = mutableMapOf<Uri, ContentValues>()
        val deleted = mutableListOf<Uri>()
        private var counter = 0
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val result = Uri.withAppendedPath(uri, (++counter).toString())
            rows[result] = ContentValues(values)
            return result
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[uri] ?: return 0
            row.putAll(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            return if (rows.remove(uri) != null) 1 else 0
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.Downloads.IS_PENDING)).apply { rows[uri]?.let { addRow(arrayOf(it.getAsInteger(MediaStore.Downloads.IS_PENDING))) } }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            File(context!!.cacheDir, "fixture-${uri.lastPathSegment}"), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
}
