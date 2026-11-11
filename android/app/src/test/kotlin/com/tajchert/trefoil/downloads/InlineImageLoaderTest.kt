package com.tajchert.trefoil.downloads

import android.graphics.Bitmap
import android.graphics.Color
import com.tajchert.trefoil.core.model.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

fun fixturePng(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, 160))
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

class ImageClient(var bytes: ByteArray, var fail: Boolean = false) : TrefoilClient {
    val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
    override val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected, selectedThreadId = "thread", canRead = true))
    var requests = 0
    override suspend fun downloadFile(reference: RemoteFileReference, output: OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit): DownloadedFile {
        requests++
        if (fail) throw java.io.IOException("boom")
        output.write(bytes)
        return DownloadedFile(reference.fileName, "image/png", bytes.size.toLong())
    }
    override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
    override suspend fun connect(environment: SavedEnvironment) {}
    override suspend fun disconnect() {}
    override suspend fun selectThread(threadId: String) {}
    override suspend fun sendMessage(text: String) = error("No writes expected")
    override suspend fun interruptRun() = error("No writes expected")
    override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("No writes expected")
    override suspend fun reconnect() {}
    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InlineImageLoaderTest {
    private val ref = RemoteFileReference(path = "/fixture/shot.png", fileName = "shot.png")

    @Test fun detectionRule() {
        fun r(name: String, mime: String? = null) = RemoteFileReference(path = "/x/$name", fileName = name, mimeType = mime).isInlineImage()
        assertTrue(r("a.bin", "image/png")); assertTrue(r("a.PNG")); assertTrue(r("a.jpeg", "application/octet-stream"))
        assertTrue(r("a.webp")); assertTrue(r("a.heic")); assertTrue(r("a.gif"))
        assertFalse(r("a.svg")); assertFalse(r("a", "image/svg+xml")); assertFalse(r("a.txt")); assertFalse(r("a.png", "text/plain"))
    }

    @Test fun successDownsamplesAndCaches() = runBlocking {
        val client = ImageClient(fixturePng(3200, 800)); val loader = InlineImageLoader(client)
        val bitmap = loader.load(ref, "fixture", "thread")!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1600)
        assertNotNull(loader.load(ref, "fixture", "thread"))
        assertEquals(1, client.requests)
    }

    @Test fun tooLargeFallsBackToCard() = runBlocking {
        val client = ImageClient(fixturePng(64, 64))
        assertNull(InlineImageLoader(client, maxSourceBytes = 100).load(ref, "fixture", "thread"))
        assertNull(InlineImageLoader(client, maxSourceBytes = 100).load(ref.copy(sizeBytes = 101), "fixture", "thread"))
    }

    @Test fun failureAndGarbageFallBackToCard() = runBlocking {
        assertNull(InlineImageLoader(ImageClient(ByteArray(0), fail = true)).load(ref, "fixture", "thread"))    }

    @Test fun disconnectedOrWrongContextDoesNotFetch() = runBlocking {
        val client = ImageClient(fixturePng(8, 8)); val loader = InlineImageLoader(client)
        assertNull(loader.load(ref, "fixture", "other-thread"))
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
        assertNull(loader.load(ref, "fixture", "thread"))
        assertEquals(0, client.requests)
    }
}
