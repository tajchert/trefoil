package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import com.tajchert.trefoil.core.session.T3Client
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.io.*
import java.net.*
import java.security.KeyPairGenerator
import java.util.Collections

class TunnelIntegrationTest {
    internal class Fixture(val protocol: Int = 2, val cliVersion: String = "0.1.0", val runtimeExtra: String = "", val omitRuntimeVersion: Boolean = false, val hangDiscovery: Boolean = false) : AutoCloseable {
        @Volatile var hangDescriptor = false
        val descriptorRequested = CompletableDeferred<Unit>()
        @Volatile var environmentId = "fixture"
        val commandStarted = CompletableDeferred<Unit>()
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val http = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (hangDescriptor) { descriptorRequested.complete(Unit); return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                    return MockResponse().setBody(if (request.path == "/.well-known/t3/environment") """{"environmentId":"$environmentId","serverVersion":"0.1.0","orchestrationProtocolVersion":$protocol}""" else "forwarded-payload")
                }
            }; start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val ssh = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ -> password == "pw" }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, _, _ -> true }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            commandFactory = CommandFactory { _, command ->
                commands.add(command)
                val output = when {
                    command.startsWith("cat ") -> """{"version":1,"pid":123,"port":${http.port},"origin":"http://192.0.2.1:${http.port}","startedAt":"2026-11-06T00:00:00Z"${if (omitRuntimeVersion) "" else ",\"serverVersion\":\"0.1.0\""}$runtimeExtra}"""
                    command.startsWith("kill ") -> ""
                    command.startsWith("if test") -> "runtime"
                    command.endsWith("--version") -> cliVersion
                    command.contains("auth pairing create") -> """{"credential":"fixture-only-credential"}"""
                    else -> ""
                }
                object : Command {
                    lateinit var out: OutputStream; lateinit var exit: ExitCallback
                    override fun setInputStream(input: InputStream) {}
                    override fun setOutputStream(output: OutputStream) { out = output }
                    override fun setErrorStream(error: OutputStream) {}
                    override fun setExitCallback(callback: ExitCallback) { exit = callback }
                    override fun start(channel: ChannelSession, environment: Environment) {
                        if (hangDiscovery && command.startsWith("cat ")) { commandStarted.complete(Unit); return }
                        out.write(output.toByteArray()); out.flush(); exit.onExit(0)
                    }
                    override fun destroy(channel: ChannelSession) {}
                }
            }; start()
        }
        fun input() = SshConnectionInput("Fixture", "127.0.0.1", "user", ssh.port, password = "pw", t3Home = "~/data'; $(touch bad)")
        suspend fun open(input: SshConnectionInput = input()): SshTunnel {
            val connector = SshTunnelConnector()
            val key = try { connector.open(input, null); error("Missing trust rejection") } catch (e: SshHostKeyException) { e.presented }
            return connector.open(input, key.fingerprint)
        }
        override fun close() { ssh.stop(true); http.shutdown() }
    }
    @Test fun passwordForwardPairAndCleanup() = runBlocking {
        Fixture().use { fixture ->
            val tunnel = fixture.open()
            val port = URI(tunnel.baseUrl).port
            assertTrue(tunnel.active.value)
            assertEquals("forwarded-payload", URI(tunnel.baseUrl + "payload").toURL().readText())
            assertEquals("fixture-only-credential", tunnel.issuePairingCredential())
            assertTrue(fixture.commands.last().contains("--base-dir \"\$HOME\"/'data'\\''; $(touch bad)'"))
            try { tunnel.issuePairingCredential(); fail("Must not retry mutation") } catch (_: SshConnectionException) {}
            assertEquals(1, fixture.commands.count { it.contains("auth pairing create") })
            tunnel.close(); assertFalse(tunnel.active.value)
            try { Socket("127.0.0.1", port).close(); fail("Forward listener leaked") } catch (_: ConnectException) {}
        }
    }
    @Test fun protocolVersionAndDevOriginRejectBeforeAuthMutation() = runBlocking {
        for (fixture in listOf(Fixture(protocol = 1), Fixture(cliVersion = "0.2.0"), Fixture(runtimeExtra = ",\"devUrl\":\"http://localhost:3000\""))) fixture.use {
            try { it.open(); fail("Incompatible runtime accepted") } catch (_: SshConnectionException) {}
            assertFalse(it.commands.any { c -> c.contains("auth pairing create") })
        }
    }
    @Test fun changedEnvironmentRejectsBeforePairing() = runBlocking {
        Fixture().use {
            try { it.open(it.input().copy(savedEnvironmentId = "another-environment")); fail("Environment changed") } catch (_: SshConnectionException) {}
            assertFalse(it.commands.any { command -> command.contains("auth pairing create") })
        }
    }
    @Test fun encryptedOpenSshPrivateKeyAuthenticates() = runBlocking {
        Fixture().use {
            val pem = javaClass.getResource("/keys/ed25519_enc")!!.readText()
            val tunnel = it.open(it.input().copy(authMethod = SshAuthMethod.PrivateKey, password = "", privateKey = pem, passphrase = "secret"))
            assertTrue(tunnel.active.value)
            tunnel.close()
        }
    }
    @Test fun environmentChangeAfterOpenRejectsBeforePairing() = runBlocking {
        Fixture().use {
            val tunnel = it.open()
            it.environmentId = "replacement"
            try { tunnel.issuePairingCredential(); fail("Environment changed after opening") } catch (_: SshConnectionException) {}
            assertFalse(it.commands.any { command -> command.contains("auth pairing create") })
            tunnel.close()
        }
    }
    @Test fun optionalRuntimeVersionUsesLiveDescriptor() = runBlocking {
        Fixture(omitRuntimeVersion = true).use {
            val tunnel = it.open()
            assertEquals("0.1.0", tunnel.serverVersion)
            tunnel.close()
        }
    }
    @Test fun cancellingDiscoveryClosesPendingSshSession() = runBlocking {
        Fixture(hangDiscovery = true).use {
            val attempt = launch { it.open() }
            withTimeout(5000) { it.commandStarted.await() }
            withTimeout(5000) { attempt.cancelAndJoin() }
            withTimeout(5000) { while (it.ssh.activeSessions.isNotEmpty()) delay(20) }
            assertFalse(it.commands.any { command -> command.contains("auth pairing create") })
        }
    }
    @Test fun remoteCommandTimeoutIsBoundedAndSanitized() = runBlocking {
        Fixture(hangDiscovery = true).use {
            val started = System.nanoTime()
            try { it.open(); fail("Unbounded command accepted") } catch (e: SshConnectionException) {
                assertFalse(e.message.orEmpty().contains("fixture-only-credential"))
                assertTrue(e.message.orEmpty().contains("timed out"))
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("Timeout elapsed $elapsed", elapsed in 14_000..25_000)
        }
    }
    @Test fun cancellationDuringDescriptorProbeNeverCreatesCredential() = runBlocking {
        Fixture().use {
            val tunnel = it.open()
            it.hangDescriptor = true
            val attempt = launch { tunnel.issuePairingCredential() }
            withTimeout(5000) { it.descriptorRequested.await() }
            withTimeout(5000) { attempt.cancelAndJoin() }
            assertFalse(tunnel.active.value)
            assertFalse(it.commands.any { command -> command.contains("auth pairing create") })
        }
    }
    @Test fun sshTunnelCarriesOAuthAndLiveT3RpcSession() = runBlocking {
        Fixture().use { fixture ->
            val tunnel = fixture.open()
            val credential = tunnel.issuePairingCredential()
            val oauthRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
            fixture.http.dispatcher = emulatorT3Dispatcher(oauthRequests)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = T3Client(scope)
            try {
                val saved = client.pair(tunnel.baseUrl + "pair#token=" + credential, "Fixture")
                assertEquals("fixture", saved.id)
                client.connect(saved)
                withTimeout(5000) { client.state.first { it.canWrite } }
                assertEquals(ConnectionStatus.Connected, client.state.value.connection)
                val oauth = oauthRequests.single { it.path == "/oauth/token" }
                assertTrue(oauth.body.readUtf8().contains("subject_token=fixture-only-credential"))
                assertTrue(oauthRequests.any { it.path == "/api/auth/session" && it.getHeader("Authorization") == "Bearer fixture-bearer" })
                tunnel.close()
                withTimeout(5000) { client.state.first { !it.canWrite } }
                assertFalse(tunnel.active.value)
            } finally { client.disconnect(); scope.cancel(); tunnel.close() }
        }
    }
    @Test fun transportLossMarksTunnelInactive() = runBlocking {
        Fixture().use {
            val tunnel = it.open(); it.ssh.stop(true)
            withTimeout(5000) { while (tunnel.active.value) delay(20) }
            tunnel.close()
        }
    }
}
