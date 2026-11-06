/* Signed-asset contract adapted from T3 Code contracts/assets.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.OutputStream

/** No cookies, bearer credentials or redirects are used for signed asset requests. */
internal class SignedFileDownloader(http: OkHttpClient, private val maxBytes: Long = 512L * 1024 * 1024) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).authenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).build()
    suspend fun download(
        environment: SavedEnvironment,
        relativeUrl: String,
        reference: RemoteFileReference,
        output: OutputStream,
        onProgress: (FileDownloadProgress) -> Unit,
        isCurrent: () -> Boolean,
        onCall: (Call?) -> Unit = {},
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val base = validatedEnvironmentUrl(environment.baseUrl, environment.allowInsecureHttp)
        val parsed = runCatching { java.net.URI(relativeUrl) }.getOrNull()
        check(parsed != null && !parsed.isAbsolute && parsed.rawAuthority == null && parsed.rawQuery == null && parsed.rawFragment == null &&
            relativeUrl.length <= 4096 && Regex("^/api/assets/[A-Za-z0-9_.-]+/[^/\\\\]+$").matches(relativeUrl)) { "Server returned an invalid download address" }
        val target = base.resolve(relativeUrl) ?: error("Server returned an invalid download address")
        check(target.encodedPath == relativeUrl && target.pathSegments.size == 4 && target.pathSegments.last().none { it == '/' || it == '\\' || it.isISOControl() } && target.pathSegments.last() !in setOf(".", "..")) { "Server returned an invalid download address" }
        validatedEnvironmentUrl(target.toString(), environment.allowInsecureHttp)
        check(isCurrent()) { "Connection or selected thread changed; download cancelled" }
        val call = http.newCall(Request.Builder().url(target).header("Accept-Encoding", "identity").build())
        call.timeout().timeout(10, java.util.concurrent.TimeUnit.MINUTES)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            onCall(call)
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { if (response.code in setOf(401, 403)) "Download access expired or was denied; try again" else "File download failed (HTTP ${response.code})" }
                val body = response.body ?: error("Server returned an empty download")
                val total = body.contentLength().takeIf { it >= 0 }
                check(total == null || total <= maxBytes) { "File exceeds the download size limit" }
                var received = 0L
                onProgress(FileDownloadProgress(0, total))
                val buffer = ByteArray(32 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(isCurrent()) { "Connection or selected thread changed; download cancelled" }
                        val count = input.read(buffer)
                        if (count == -1) break
                        currentCoroutineContext().ensureActive()
                        check(isCurrent()) { "Connection or selected thread changed; download cancelled" }
                        check(received + count <= maxBytes && (total == null || received + count <= total)) { "File exceeds the download size limit" }
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(FileDownloadProgress(received, total))
                    }
                }
                check(isCurrent()) { "Connection or selected thread changed; download cancelled" }
                check(total == null || total == received) { "Download was incomplete; try again" }
                val fileName = safeDownloadFileName(reference.fileName)
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$").matches(it) }
                    ?: reference.mimeType?.takeIf { Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$").matches(it) }
                    ?: if (fileName.endsWith(".apk", ignoreCase = true)) "application/vnd.android.package-archive" else "application/octet-stream"
                DownloadedFile(fileName, mime, received)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: IllegalStateException) { throw error }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); throw IllegalStateException("File download failed. Check the connection and try again.") }
        finally { cancellation.cancel(); onCall(null) }
    }
}

internal fun safeDownloadFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().take(255).takeIf { it.isNotEmpty() && it !in setOf(".", "..") } ?: "download"
