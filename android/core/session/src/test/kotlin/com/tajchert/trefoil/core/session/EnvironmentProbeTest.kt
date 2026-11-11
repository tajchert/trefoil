package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class EnvironmentProbeTest {
    private lateinit var server: MockWebServer
    private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun environment(id: String = "env-1") = SavedEnvironment(id, "Test", server.url("/").toString(), "fixture-token")

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { runCatching { server.shutdown() } }

    @Test fun savedCredentialsAcceptedIsOnlineWithReadOnlyRequests() = runBlocking {
        server.enqueue(json(descriptor))
        server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
        assertEquals(MachineProbe.Online, EnvironmentProbe().probe(environment()))
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", first.method); assertEquals("/.well-known/t3/environment", first.path)
        assertNull("The token is never sent to the public descriptor", first.getHeader("Authorization"))
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", second.method); assertEquals("/api/auth/session", second.path)
        assertEquals("Bearer fixture-token", second.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectedTokenIsAuthFailed() = runBlocking {
        server.enqueue(json(descriptor)); server.enqueue(json("""{"error":"unauthorized"}""", 401))
        assertEquals(MachineProbe.AuthFailed, EnvironmentProbe().probe(environment()))
        server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":false}"""))
        assertEquals(MachineProbe.AuthFailed, EnvironmentProbe().probe(environment()))
    }

    @Test fun silentServerTimesOutAsUnreachable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe(timeoutMillis = 300).probe(environment()))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test fun refusedConnectionAndServerErrorsAreUnreachable() = runBlocking {
        server.enqueue(json("oops", 502))
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe().probe(environment()))
        val closed = environment(); server.shutdown()
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe(timeoutMillis = 2_000).probe(closed))
    }

    @Test fun identityChangeNeedsAttentionWithoutSendingToken() = runBlocking {
        server.enqueue(json(descriptor.replace("env-1", "someone-else")))
        assertEquals(MachineProbe.NeedsAttention, EnvironmentProbe().probe(environment()))
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun urlPolicyBlocksBeforeAnyRequest() = runBlocking {
        val probe = EnvironmentProbe(timeoutMillis = 500)
        // Public HTTP is never allowed, even with a saved flag; private LAN HTTP needs saved consent.
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Public", "http://8.8.8.8/", "fixture-token", allowInsecureHttp = true)))
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Lan", "http://192.168.1.20/", "fixture-token", allowInsecureHttp = false)))
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Ssh", "", "fixture-token")))
        assertEquals(0, server.requestCount)
    }
}
