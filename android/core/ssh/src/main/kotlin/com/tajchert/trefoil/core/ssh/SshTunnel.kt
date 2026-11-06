/* SSH authentication/crypto patterns adapted from Flocket (Apache-2.0). */
package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultConfig
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.net.*
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class SshHostKeyException(val presented: SshHostKey, val changed: Boolean) : Exception(if (changed) "SSH host key changed. Verify it independently before reconnecting." else "Confirm the SSH host key before connecting.")
class SshConnectionException(message: String) : Exception(message)
internal fun remoteQuote(value: String): String { require('\u0000' !in value); return "'" + value.replace("'", "'\\''") + "'" }
internal fun remotePath(value: String): String = if (value.startsWith("~/")) "\"\$HOME\"/" + remoteQuote(value.drop(2)) else remoteQuote(value)
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
private fun validVersion(value: String) = value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))
private fun fingerprint(key: PublicKey) = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Buffer.PlainBuffer().putPublicKey(key).compactData))

class SshTunnelConnector {
    suspend fun open(input: SshConnectionInput, expectedFingerprint: String?): SshTunnel = coroutineScope {
        synchronized(SshTunnelConnector::class.java) {
            if (Security.getProvider("BC") !is BouncyCastleProvider) { Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1) }
        }
        val client = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        val attempt = async(Dispatchers.IO) {
            try { connect(client, input, expectedFingerprint) } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        }
        try { attempt.await() } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { client.close() } }
            throw e
        }
    }
    private fun connect(client: SSHClient, input: SshConnectionInput, pin: String?): SshTunnel {
        val presented = AtomicReference<SshHostKey>()
        var tunnel: SshTunnel? = null
        try {
            if (input.host.isBlank() || input.username.isBlank() || input.port !in 1..65535) throw SshConnectionException("Enter a valid SSH address, port and username.")
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val info = SshHostKey(input.host, input.port, KeyType.fromKey(key).toString(), fingerprint(key))
                    presented.set(info); return pin != null && info.fingerprint == pin
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connectTimeout = 10_000; client.transport.timeoutMs = 15_000
            client.connect(input.host, input.port)
            client.connection.keepAlive.keepAliveInterval = 15
            when (input.authMethod) {
                SshAuthMethod.Password -> client.authPassword(input.username, input.password.toCharArray())
                SshAuthMethod.PrivateKey -> client.authPublickey(input.username, client.loadKeys(input.privateKey, null, input.passphrase.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }))
            }
            tunnel = SshTunnel(client, presented.get(), input.copy(password = "", privateKey = "", passphrase = ""))
            tunnel.initialize()
            return tunnel
        } catch (e: Exception) {
            tunnel?.close(); runCatching { client.close() }
            val key = presented.get()
            if (key != null && key.fingerprint != pin) throw SshHostKeyException(key, pin != null)
            if (e is SshConnectionException) throw e
            throw SshConnectionException(if (e is net.schmizz.sshj.userauth.UserAuthException) "SSH authentication failed. Check the username and credentials." else "SSH connection failed. Check the address, credentials and running T3 installation.")
        }
    }
}

class SshTunnel internal constructor(private val client: SSHClient, val hostKey: SshHostKey, private val input: SshConnectionInput) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    @Volatile private var descriptorConnection: HttpURLConnection? = null
    private val forwardedSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private var listener: ServerSocket? = null
    private var forwarder: net.schmizz.sshj.connection.channel.direct.LocalPortForwarder? = null
    lateinit var baseUrl: String; private set
    lateinit var serverVersion: String; private set
    private var environmentId: String? = input.savedEnvironmentId
    private lateinit var executable: String
    private var pairingAttempted = false
    internal fun initialize() {
        val runtime = parse(exec("cat -- " + remotePath(input.t3Home.trimEnd('/') + "/userdata/server-runtime.json")))
        val port = runtime["port"]?.jsonPrimitive?.intOrNull ?: 0
        val pid = runtime["pid"]?.jsonPrimitive?.longOrNull ?: 0
        val origin = runCatching { URI(runtime.string("origin")) }.getOrNull()
        if (runtime["version"]?.jsonPrimitive?.intOrNull != 1 || pid <= 0 || port !in 1..65535 || origin?.scheme != "http" || origin.host == null || origin.port != port || origin.userInfo != null || origin.query != null || origin.fragment != null || origin.path.orEmpty().trim('/').isNotEmpty() || runtime.string("devUrl").isNotBlank()) throw SshConnectionException("Unsupported T3 runtime metadata. Use a running production T3 server.")
        exec("kill -0 $pid")
        serverVersion = runtime.string("serverVersion")
        if (serverVersion.isNotBlank() && !validVersion(serverVersion)) throw SshConnectionException("T3 runtime version is unsupported. Specify a current production installation.")
        val socket = object : ServerSocket() {
            override fun accept(): Socket = super.accept().also {
                forwardedSockets.add(it)
                if (closed) it.close()
            }
        }.apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        listener = socket
        forwarder = client.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, "127.0.0.1", port), socket)
        baseUrl = "http://127.0.0.1:${socket.localPort}/"
        scope.launch { try { forwarder!!.listen() } catch (_: Exception) {} finally { close() } }
        validateDescriptor()
        executable = if (input.executable.isNotBlank()) remotePath(input.executable) else {
            val preferred = remotePath(input.t3Home.trimEnd('/') + "/runtime/versions/$serverVersion/t3")
            val fallback = remotePath("~/.t3/bin/t3")
            exec("if test -x $preferred; then printf '%s' runtime; elif test -x $fallback; then printf '%s' launcher; else exit 1; fi").trim().let { if (it == "runtime") preferred else fallback }
        }
        val version = exec("$executable --version").trim().removePrefix("t3 ").removePrefix("v")
        if (version != serverVersion) throw SshConnectionException("The installed T3 CLI does not match the running server. Choose a matching executable.")
        mutableActive.value = true
        scope.launch { while (isActive) { delay(500); if (!client.isConnected) { close(); break } } }
    }
    private fun validateDescriptor() {
        val connection = URI(baseUrl + ".well-known/t3/environment").toURL().openConnection() as HttpURLConnection
        descriptorConnection = connection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) throw SshConnectionException("T3 descriptor could not be verified.")
            val bytes = connection.inputStream.use { it.readBounded() }
            if (bytes.size > 65_536) throw SshConnectionException("T3 descriptor is too large.")
            val info = parse(bytes.toString(Charsets.UTF_8))
            if (info["orchestrationProtocolVersion"]?.jsonPrimitive?.intOrNull != 2 || info.string("environmentId").isBlank() || !validVersion(info.string("serverVersion")) || (serverVersion.isNotBlank() && info.string("serverVersion") != serverVersion)) throw SshConnectionException("T3 requires a compatible protocol V2 server and matching runtime version.")
            if (environmentId != null && environmentId != info.string("environmentId")) throw SshConnectionException("The T3 environment identity changed. Pair it as a new environment.")
            environmentId = info.string("environmentId")
            serverVersion = info.string("serverVersion")
        } finally { connection.disconnect(); descriptorConnection = null }
    }
    suspend fun issuePairingCredential(): String = coroutineScope {
        val attempt = async(Dispatchers.IO) {
            val attemptContext = currentCoroutineContext()
            synchronized(this@SshTunnel) {
                if (!active.value || pairingAttempted) throw SshConnectionException("Pairing was already attempted or SSH disconnected. Reconnect to reconcile its outcome.")
                try { validateDescriptor() } catch (e: SshConnectionException) { attemptContext.ensureActive(); throw e } catch (_: Exception) {
                    attemptContext.ensureActive()
                    throw SshConnectionException("T3 descriptor could not be verified. Reconnect before pairing.")
                }
                attemptContext.ensureActive()
                pairingAttempted = true
                try {
                    val result = parse(exec("$executable auth pairing create --base-dir ${remotePath(input.t3Home)} --json --ttl 5m --label Trefoil"))
                    result.string("credential").takeIf { it.isNotBlank() } ?: throw SshConnectionException("T3 did not return a pairing credential. Reconnect before trying again.")
                } catch (e: SshConnectionException) { attemptContext.ensureActive(); throw e } catch (_: Exception) { attemptContext.ensureActive(); throw SshConnectionException("Pairing outcome is unknown. Reconnect and check the server before trying again.") }
            }
        }
        try { attempt.await() } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            throw e
        }
    }
    private fun exec(command: String): String {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "trefoil-ssh-output").apply { isDaemon = true } }
            try {
                val output = readers.submit<ByteArray> { cmd.inputStream.readBounded() }
                val errors = readers.submit<ByteArray> { cmd.errorStream.readBounded() }
                cmd.join(15, TimeUnit.SECONDS)
                if (!cmd.isEOF || cmd.exitStatus != 0) throw SshConnectionException("T3 command failed or timed out. Check the running installation and executable.")
                val bytes = output.get(1, TimeUnit.SECONDS)
                if (bytes.size > 65_536 || errors.get(1, TimeUnit.SECONDS).size > 65_536) throw SshConnectionException("T3 command returned excessive output.")
                return bytes.toString(Charsets.UTF_8)
            } catch (e: SshConnectionException) { throw e } catch (_: Exception) {
                throw SshConnectionException("T3 command failed or timed out. Check the running installation and executable.")
            } finally { runCatching { cmd.close() }; readers.shutdownNow() }
        }
    }
    /** Closes sockets synchronously; call from an IO dispatcher, including cancellation cleanup. */
    fun close() {
        closed = true
        mutableActive.value = false
        forwardedSockets.forEach { runCatching { it.close() } }
        forwardedSockets.clear()
        runCatching { descriptorConnection?.disconnect() }
        runCatching { listener?.close() }; runCatching { forwarder?.close() }; runCatching { client.close() }; scope.cancel()
    }
}

/** Avoid newer java.io methods unavailable on supported Android releases. */
private fun java.io.InputStream.readBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < 65_537) {
        val count = read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
