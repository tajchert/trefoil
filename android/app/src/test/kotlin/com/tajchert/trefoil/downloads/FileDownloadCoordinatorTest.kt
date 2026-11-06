package com.tajchert.trefoil.downloads

import android.net.Uri
import com.tajchert.trefoil.core.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileDownloadCoordinatorTest {
    private val reference = RemoteFileReference(path = "/fixture/release.apk", fileName = "release.apk", sizeBytes = 4)

    @Test fun transientProjectionRefreshDoesNotCancelTransferAndCompletedDownloadSurvivesDisconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val store = FakeStore(); val coordinator = FileDownloadCoordinator(scope, client, store)
        try {
            val key = coordinator.key(reference)!!
            coordinator.download(reference)
            assertEquals(DownloadStatus.Downloading, coordinator.state.value[key]!!.status)
            assertEquals(2, coordinator.state.value[key]!!.bytesDownloaded)
            client.state.value = client.state.value.copy(canRead = false, canWrite = false)
            assertEquals(DownloadStatus.Downloading, coordinator.state.value[key]!!.status)
            client.next.complete(DownloadedFile("release.apk", DownloadFileActions.APK_MIME, 4)); yield()
            val completed = coordinator.state.value[key]!!.completed
            assertNotNull(completed)
            assertEquals(DownloadStatus.Complete, coordinator.state.value[key]!!.status)
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
            assertEquals(completed, coordinator.state.value[key]!!.completed)
            client.state.value = client.state.value.copy(environment = client.environment.copy(id = "another"), selectedThreadId = "another")
            assertEquals(completed, coordinator.state.value[key]!!.completed)
            assertEquals(1, store.published)
            assertEquals(0, store.cleaned)
        } finally { scope.cancel() }
    }

    @Test fun disconnectCleansPartialAndReconnectDoesNotReplayButExplicitRetryWorks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val store = FakeStore(); val coordinator = FileDownloadCoordinator(scope, client, store)
        try {
            val key = coordinator.key(reference)!!
            coordinator.download(reference)
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected); yield()
            assertEquals(DownloadStatus.Cancelled, coordinator.state.value[key]!!.status)
            assertEquals(1, store.cleaned)
            assertEquals(0, store.published)
            client.next = CompletableDeferred()
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Connected); yield()
            assertEquals(1, client.requests)
            coordinator.download(reference)
            assertEquals(2, client.requests)
            client.next.complete(DownloadedFile("release.apk", DownloadFileActions.APK_MIME, 4)); yield()
            assertEquals(DownloadStatus.Complete, coordinator.state.value[key]!!.status)
            assertEquals(1, store.published)
            client.next = CompletableDeferred()
            coordinator.download(reference)
            assertEquals(3, client.requests)
        } finally { scope.cancel() }
    }

    @Test fun environmentAndThreadSwitchCancelPartialsAndOriginatingCardCannotDownloadInNewContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val store = FakeStore(); val coordinator = FileDownloadCoordinator(scope, client, store)
        try {
            val first = coordinator.key(reference)!!
            coordinator.download(reference, "fixture", "thread")
            client.state.value = client.state.value.copy(selectedThreadId = "another-thread"); yield()
            assertEquals(DownloadStatus.Cancelled, coordinator.state.value[first]!!.status)
            coordinator.download(reference, "fixture", "thread")
            assertEquals(1, client.requests)
            val second = coordinator.key(reference)!!
            assertNotEquals(first, second)
            client.next = CompletableDeferred()
            coordinator.download(reference, "fixture", "another-thread")
            client.state.value = client.state.value.copy(environment = client.environment.copy(id = "another-environment")); yield()
            assertEquals(DownloadStatus.Cancelled, coordinator.state.value[second]!!.status)
            coordinator.download(reference, "fixture", "another-thread")
            assertEquals(2, client.requests)
            assertEquals(2, store.cleaned)
            assertEquals(0, store.published)
        } finally { scope.cancel() }
    }

    @Test fun staleStartAndFailedTransferRequireExplicitFreshAttemptWithoutExposingRemoteError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val store = FakeStore(); val coordinator = FileDownloadCoordinator(scope, client, store)
        try {
            client.state.value = client.state.value.copy(canRead = false)
            coordinator.download(reference)
            assertEquals(0, client.requests)
            client.state.value = client.state.value.copy(canRead = true)
            coordinator.download(reference)
            client.next.completeExceptionally(IOException("secret-response-content")); yield()
            val failed = coordinator.state.value[coordinator.key(reference)]!!
            assertEquals(DownloadStatus.Failed, failed.status)
            assertFalse(failed.error!!.contains("secret-response-content"))
            client.state.value = client.state.value.copy(canRead = false)
            client.state.value = client.state.value.copy(canRead = true); yield()
            assertEquals(1, client.requests)
            assertEquals(1, store.cleaned)
        } finally { scope.cancel() }
    }

    private class FakeStore : DownloadStore {
        var published = 0
        var cleaned = 0
        var beforeTransfer: () -> Unit = {}
        override suspend fun save(reference: RemoteFileReference, canComplete: () -> Boolean,
            transfer: suspend (OutputStream) -> DownloadedFile): CompletedDownload {
            var complete = false
            try {
                beforeTransfer()
                val file = transfer(ByteArrayOutputStream())
                currentCoroutineContext().ensureActive()
                check(canComplete())
                complete = true
                published++
                return CompletedDownload(Uri.parse("content://media/external/downloads/$published"), file.fileName, file.mimeType, file.sizeBytes)
            } finally { if (!complete) cleaned++ }
        }
    }

    @Test fun selectionChangeDuringStorageCreationNeverHandsOldPathToNewEnvironment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val store = FakeStore(); val coordinator = FileDownloadCoordinator(scope, client, store)
        try {
            val key = coordinator.key(reference)!!
            store.beforeTransfer = { client.state.value = client.state.value.copy(environment = client.environment.copy(id = "other")) }
            coordinator.download(reference, "fixture", "thread"); yield()
            assertEquals(0, client.requests)
            assertEquals(1, store.cleaned)
            assertNotEquals(DownloadStatus.Complete, coordinator.state.value[key]!!.status)
        } finally { scope.cancel() }
    }

    private class FakeClient : TrefoilClient {
        val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
        override val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected,
            selectedThreadId = "thread", canRead = true))
        var next = CompletableDeferred<DownloadedFile>()
        var requests = 0
        override suspend fun downloadFile(reference: RemoteFileReference, output: OutputStream,
            environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit): DownloadedFile {
            check(state.value.environment?.id == environmentId && state.value.selectedThreadId == threadId)
            requests++
            output.write(byteArrayOf(1, 2))
            onProgress(FileDownloadProgress(2, 4))
            return next.await()
        }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
        override suspend fun connect(environment: SavedEnvironment) {}
        override suspend fun disconnect() {}
        override suspend fun selectThread(threadId: String) {}
        override suspend fun sendMessage(text: String) = error("No writes expected")
        override suspend fun interruptRun() = error("No writes expected")
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("No writes expected")
        override suspend fun createThread(projectId: String, title: String, model: ModelOption): String = error("No writes expected")
        override suspend fun reconnect() {}
        override fun close() {}
    }
}
