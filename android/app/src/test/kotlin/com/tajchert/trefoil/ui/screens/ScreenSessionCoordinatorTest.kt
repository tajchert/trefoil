package com.tajchert.trefoil.ui.screens

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ScreenSessionCoordinatorTest {
    @Test fun failedViewerDoesNotRetryOnThreadRefreshesOrForegroundUntilExplicitlyOpened() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val coordinator = ScreenSessionCoordinator(scope, client)
        try {
            coordinator.open(client.target)
            client.next.completeExceptionally(IllegalStateException("fixture access denied")); yield()
            assertNotNull(coordinator.state.value.error)
            repeat(100) {
                client.state.value = client.state.value.copy(canRead = false, canWrite = false)
                client.state.value = client.state.value.copy(canRead = true)
            }
            coordinator.pause(); coordinator.resume()
            assertEquals(1, client.requests)
            assertEquals(1, client.refreshes)
            client.next = CompletableDeferred()
            coordinator.open(client.target)
            assertEquals(2, client.requests)
            client.next.complete(client.access()); yield()
            assertNotNull(coordinator.state.value.access)
        } finally { scope.cancel() }
    }

    @Test fun pausedCoordinatorCannotStartCatalogOrAccessWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val coordinator = ScreenSessionCoordinator(scope, client)
        try {
            coordinator.pause()
            coordinator.refresh(); coordinator.open(client.target); yield()
            assertEquals(0, client.refreshes)
            assertEquals(0, client.requests)
            assertNull(coordinator.state.value.target)
        } finally { scope.cancel() }
    }
    private class FakeClient : TrefoilClient {
        val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
        override val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected, selectedThreadId = "thread", canWrite = true))
        val target = ScreenTarget.Browser("thread", "tab", "Fixture")
        var next = CompletableDeferred<ScreenAccess>()
        var requests = 0
        var current = true
        var catalogFresh = false
        var refreshes = 0
        override suspend fun screenAccess(target: ScreenTarget): ScreenAccess { check(catalogFresh); requests++; return withContext(NonCancellable) { next.await() } }
        override fun isScreenAccessCurrent(access: ScreenAccess) = current && state.value.connection == ConnectionStatus.Connected && access.environmentId == state.value.environment?.id && access.target.threadId == state.value.selectedThreadId
        override suspend fun refreshScreens() { refreshes++; catalogFresh = true }
        fun access() = ScreenAccess(environment.id, 1, target, "https://fixture.invalid/api/preview-stream", "wss://fixture.invalid/api/preview-stream", mapOf("wsTicket" to "fixture-media"))
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
    @Test fun pauseDiscardsLateAccessAndResumeMintsNewTicket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val coordinator = ScreenSessionCoordinator(scope, client)
        try {
            coordinator.open(client.target)
            assertTrue(coordinator.state.value.loading)
            val first = client.next
            coordinator.pause()
            client.next = CompletableDeferred()
            first.complete(client.access())
            yield()
            assertNull(coordinator.state.value.access)
            assertFalse(coordinator.state.value.loading)
            coordinator.resume()
            assertEquals(2, client.requests)
            val fresh = client.access().copy(query = mapOf("wsTicket" to "fresh-fixture-media"))
            client.next.complete(fresh); yield()
            assertEquals(fresh, coordinator.state.value.access)
            assertFalse(coordinator.state.value.toString().contains("fresh-fixture-media"))
        } finally { scope.cancel() }
    }
    @Test fun readonlyViewerRefreshesCatalogBeforeResumingWithFreshAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient()
        client.state.value = client.state.value.copy(canWrite = false, canRead = true)
        val coordinator = ScreenSessionCoordinator(scope, client)
        try {
            coordinator.open(client.target); client.next.complete(client.access()); yield()
            assertNotNull(coordinator.state.value.access)
            coordinator.pause()
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected, canRead = false)
            client.catalogFresh = false; client.next = CompletableDeferred()
            coordinator.resume()
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Connected)
            assertEquals(1, client.requests)
            client.state.value = client.state.value.copy(canRead = true)
            assertEquals(2, client.refreshes)
            assertEquals(2, client.requests)
            client.next.complete(client.access().copy(query = mapOf("wsTicket" to "new-readonly-ticket"))); yield()
            assertNotNull(coordinator.state.value.access)
            assertFalse(client.state.value.canWrite)
        } finally { scope.cancel() }
    }
    @Test fun environmentOrThreadSwitchCannotAdoptOldAccessAndNoSpinnerRemains() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val coordinator = ScreenSessionCoordinator(scope, client)
        try {
            coordinator.open(client.target)
            client.state.value = client.state.value.copy(selectedThreadId = "another-thread")
            client.next.complete(client.access()); yield()
            assertNull(coordinator.state.value.target)
            assertFalse(coordinator.state.value.loading)
            client.state.value = client.state.value.copy(selectedThreadId = "thread")
            client.next = CompletableDeferred(); client.current = false
            coordinator.open(client.target); client.next.complete(client.access()); yield()
            assertFalse(coordinator.state.value.loading)
            assertNull(coordinator.state.value.access)
            assertNotNull(coordinator.state.value.error)
        } finally { scope.cancel() }
    }
}
