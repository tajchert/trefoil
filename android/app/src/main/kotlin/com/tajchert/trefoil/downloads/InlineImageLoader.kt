package com.tajchert.trefoil.downloads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.TrefoilClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic")

/** Any image mime type except SVG, or a raster extension when the mime is missing or generic. */
fun RemoteFileReference.isInlineImage(): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension == "svg" || mime?.startsWith("image/svg") == true) return false
    if (mime != null && mime.startsWith("image/")) return true
    if (mime.isNullOrEmpty() || mime == "application/octet-stream") return extension in imageExtensions
    return false
}

/**
 * Fetches image bytes through the authenticated client into memory only (never MediaStore) and decodes a
 * downsampled bitmap. A null result means "use the normal download card". Nothing here logs paths or content.
 */
class InlineImageLoader(
    private val client: TrefoilClient,
    private val maxSourceBytes: Long = MAX_SOURCE_BYTES,
    private val maxEdgePx: Int = MAX_EDGE_PX,
    cacheBytes: Int = 32 * 1024 * 1024,
) {
    private data class Key(val environmentId: String, val threadId: String, val referenceId: String)
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    private fun isCurrent(environmentId: String, threadId: String): Boolean = client.state.value.let {
        it.connection == ConnectionStatus.Connected && (it.canRead || it.canWrite) &&
            it.environment?.id == environmentId && it.selectedThreadId == threadId
    }

    /** Cached bitmap, if any. */
    fun cached(reference: RemoteFileReference, environmentId: String, threadId: String): Bitmap? =
        synchronized(cache) { cache.get(Key(environmentId, threadId, reference.identity)) }

    suspend fun load(reference: RemoteFileReference, environmentId: String, threadId: String): Bitmap? {
        cached(reference, environmentId, threadId)?.let { return it }
        if (!reference.isInlineImage() || (reference.sizeBytes ?: 0) > maxSourceBytes) return null
        if (!isCurrent(environmentId, threadId)) return null
        val sink = LimitedSink(maxSourceBytes) { isCurrent(environmentId, threadId) }
        try {
            client.downloadFile(reference, sink, environmentId, threadId)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(environmentId, threadId)) return null
            val bitmap = decode(sink.bytes()) ?: return null
            synchronized(cache) { cache.put(Key(environmentId, threadId, reference.identity), bitmap) }
            return bitmap
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val edge = maxOf(decoded.width, decoded.height)
        if (edge <= maxEdgePx) return decoded
        val scale = maxEdgePx.toFloat() / edge
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private class LimitedSink(private val limit: Long, private val stillCurrent: () -> Boolean) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        override fun write(b: Int) { guard(1); buffer.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { guard(len); buffer.write(b, off, len) }
        private fun guard(extra: Int) {
            if (buffer.size() + extra.toLong() > limit) throw IOException("Image too large")
            if (!stillCurrent()) throw CancellationException("Context changed")
        }
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    companion object {
        const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        const val MAX_EDGE_PX = 1600
    }
}
