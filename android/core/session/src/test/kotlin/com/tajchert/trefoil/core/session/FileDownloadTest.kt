package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class FileDownloadTest {
    private val ref = RemoteFileReference(path = "/tmp/app.apk", fileName = "app.apk")
    @Test fun streamsSignedAssetWithoutBearerAndReportsMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("apk-bytes", 2).setHeader("Content-Type", "application/vnd.android.package-archive"))
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<FileDownloadProgress>()
            val result = SignedFileDownloader(OkHttpClient()).download(SavedEnvironment("e", "test", server.url("/").toString(), "secret"), "/api/assets/signed.token/app.apk", ref, output, { progress += it }, { true })
            assertEquals("apk-bytes", output.toString())
            assertEquals(DownloadedFile("app.apk", "application/vnd.android.package-archive", 9), result)
            assertEquals(9, progress.last().bytesDownloaded)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun rejectsUntrustedRoutesBeforeSendingAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            val env = SavedEnvironment("e", "test", server.url("/").toString(), "secret")
            for (relative in listOf("https://evil.test/api/assets/a/a.apk", "//evil.test/api/assets/a/a.apk", "/oauth/token", "/api/assets/a/../secret", "/api/assets/a/%2e%2e", "/api/assets/a/app.apk?token=x", "/api/assets/a/app.apk#x", "/api/assets/a/app%2Fsecret.apk", "/api/assets/a/app.apk\\other")) {
                try { SignedFileDownloader(OkHttpClient()).download(env, relative, ref, ByteArrayOutputStream(), {}, { true }); fail("Untrusted signed route accepted") } catch (_: IllegalStateException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun rejectsRedirectOversizeAndTruncatedBodies() = runBlocking {
        MockWebServer().use { server ->
            val env = SavedEnvironment("e", "test", server.url("/").toString(), "secret")
            for (response in listOf(MockResponse().setResponseCode(302).setHeader("Location", "/leak"), MockResponse().setBody("123456"), MockResponse().setBody("1234").setHeader("Content-Length", "8").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))) {
                server.enqueue(response)
                try { SignedFileDownloader(OkHttpClient(), maxBytes = 5).download(env, "/api/assets/s/a.apk", ref, ByteArrayOutputStream(), {}, { true }); fail("Invalid transfer succeeded") } catch (_: IllegalStateException) { }
            }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun incompleteDeclaredLengthCannotSucceedAndUnsafeNamesAreSanitized() = runBlocking {
        MockWebServer().use { server ->
            val env = SavedEnvironment("e", "test", server.url("/").toString(), "secret")
            server.enqueue(MockResponse().setBody("1234").setHeader("Content-Length", "8").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            try { SignedFileDownloader(OkHttpClient()).download(env, "/api/assets/s/a.apk", ref, ByteArrayOutputStream(), {}, { true }); fail("Truncated body succeeded") } catch (error: IllegalStateException) { assertFalse(error.message.orEmpty().contains("secret")) }
            server.enqueue(MockResponse().setBody("apk"))
            val result = SignedFileDownloader(OkHttpClient()).download(env, "/api/assets/s/a.apk", ref.copy(fileName = "../../unsafe\n.apk"), ByteArrayOutputStream(), {}, { true })
            assertEquals("unsafe.apk", result.fileName)
            assertEquals("application/vnd.android.package-archive", result.mimeType)
        }
    }
    @Test fun cancellationClosesABlockedNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val output = ByteArrayOutputStream()
            val task = async(Dispatchers.Default) { SignedFileDownloader(OkHttpClient()).download(SavedEnvironment("e", "test", server.url("/").toString(), "secret"), "/api/assets/s/a.apk", ref, output, {}, { true }) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) })
            task.cancel()
            withTimeout(2000) { task.join() }
            assertEquals(0, output.size())
        }
    }
    @Test fun scopeChangeStopsStreamingAndChunkedBodiesRemainBounded() = runBlocking {
        MockWebServer().use { server ->
            val env = SavedEnvironment("e", "test", server.url("/").toString(), "secret")
            server.enqueue(MockResponse().setChunkedBody("123456789", 1))
            val output = ByteArrayOutputStream()
            try { SignedFileDownloader(OkHttpClient(), maxBytes = 5).download(env, "/api/assets/s/a.apk", ref, output, {}, { true }); fail("Chunked size limit ignored") } catch (_: IllegalStateException) { }
            assertTrue(output.size() <= 5)
            server.enqueue(MockResponse().setBody("bytes"))
            var current = true
            try { SignedFileDownloader(OkHttpClient()).download(env, "/api/assets/s/a.apk", ref, ByteArrayOutputStream(), { current = false }, { current }); fail("Stale context ignored") } catch (_: IllegalStateException) { }
        }
    }
}
