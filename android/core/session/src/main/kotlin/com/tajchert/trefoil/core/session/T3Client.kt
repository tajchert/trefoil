package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native direct-environment client. Mutations are never retried or replayed. */
class T3Client(
    private val scope: CoroutineScope,
    private val connectionTimeoutMillis: Long = 20_000,
    private val requestTimeoutMillis: Long = 30_000,
) : TrefoilClient {
    private val mutable = MutableStateFlow(ClientState())
    override val state: StateFlow<ClientState> = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    @Volatile private var generation = 0L
    private var heartbeat: Job? = null
    private var threadSubscription: String? = null
    private var shellReady = false
    private var threadReady = false
    private var authorized = false
    private var pongAt = 0L
    private val serial = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val lifecycleLock = Any()
    private var refreshSignals: Channel<Unit>? = null
    private var refreshJob: Job? = null
    private var projection = JsonObject(emptyMap())
    private fun update(block: (ClientState) -> ClientState) = mutable.update(block)
    private fun url(value: String, allowInsecureHttp: Boolean = false): HttpUrl = validatedEnvironmentUrl(value, allowInsecureHttp)
    private suspend fun fetch(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            EffectRpc.json.parseToJsonElement(response.body?.string() ?: error("Empty server response")).jsonObject
        }
    }
    private class HttpFailure(val status: Int) : IllegalStateException(if (status == 401 || status == 403) "Authorization expired or denied; pair again" else "Server HTTP error $status")
    private suspend fun descriptor(base: HttpUrl): JsonObject = fetch(Request.Builder().url(base.resolve("/.well-known/t3/environment")!!).build()).also {
        if (it["orchestrationProtocolVersion"]?.jsonPrimitive?.intOrNull != 2) throw PairingException("This environment does not support orchestration protocol 2")
    }
    override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = try {
        val target = parsePairingLink(pairingUrl)
        val base = url(target.baseUrl, allowInsecureHttp)
        val credential = target.token
        val info = descriptor(base)
        val form = FormBody.Builder().add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            .add("subject_token", credential).add("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
            .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
            .add("client_label", name.ifBlank { "Trefoil" }).add("client_device_type", "mobile").add("client_os", "Android").build()
        currentCoroutineContext().ensureActive()
        val token = fetch(Request.Builder().url(base.resolve("/oauth/token")!!).post(form).build())
        currentCoroutineContext().ensureActive()
        require(token.string("token_type") == "Bearer") { "Server did not issue a bearer token" }
        require(token.string("access_token").isNotBlank()) { "Server returned an empty access token" }
        SavedEnvironment(info.string("environmentId").ifBlank { error("Missing environment identity") }, name.ifBlank { info.string("label") }, base.toString(), token.string("access_token"), allowInsecureHttp = allowInsecureHttp && base.scheme == "http" && isPrivateLanAddress(base.host))
    } catch (error: CancellationException) { throw error }
    catch (error: PairingException) { throw error }
    catch (error: HttpFailure) { throw PairingException(if (error.status == 401 || error.status == 403) "Pairing token expired or was denied. Generate a new pairing link." else "Pairing server returned HTTP ${error.status}. Check the server and try again.") }
    catch (_: Exception) { throw PairingException("Pairing failed. Check the link, network, and server compatibility, then try again.") }

    override suspend fun connect(environment: SavedEnvironment) {
        disconnect()
        val epoch = generation
        update { ClientState(environment = environment, connection = ConnectionStatus.Connecting) }
        try {
            val base = url(environment.baseUrl, environment.allowInsecureHttp)
            require(descriptor(base).string("environmentId") == environment.id) { "Environment identity changed; pair again" }
            fun ticketRequest(path: String) = Request.Builder().url(base.resolve(path)!!).header("Authorization", "Bearer ${environment.accessToken}").post(ByteArray(0).toRequestBody()).build()
            val session = fetch(Request.Builder().url(base.resolve("/api/auth/session")!!).header("Authorization", "Bearer ${environment.accessToken}").build())
            require(session["authenticated"]?.jsonPrimitive?.booleanOrNull == true) { "Authorization expired; pair again" }
            val permissions = (session["permissions"] ?: session["scopes"]) as? JsonArray
            val mayOperate = permissions?.any { it.jsonPrimitive.content == "orchestration:operate" } == true
            val ticket = try { fetch(ticketRequest("/api/auth/websocket-ticket/rpc")) } catch (error: HttpFailure) {
                if (error.status != 404) throw error
                fetch(ticketRequest("/api/auth/websocket-ticket"))
            }
            require(ticket.string("ticket").isNotBlank()) { "Missing WebSocket ticket" }
            check(epoch == generation) { "Connection was cancelled" }
            val endpoint = base.newBuilder().encodedPath("/ws").query(null).addQueryParameter("wsTicket", ticket.string("ticket")).addQueryParameter("orchestrationProtocol", "2").build()
            val opened = CompletableDeferred<Unit>()
            socket = http.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { synchronized(lifecycleLock) { if (epoch == generation) opened.complete(Unit) else webSocket.cancel() } }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(lifecycleLock) {
                        if (epoch == generation) try { EffectRpc.decode(text).forEach { if (epoch == generation) receive(it) } } catch (_: Exception) { fail(epoch, "Invalid server RPC response") }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.completeExceptionally(IllegalStateException("Connection failed")); fail(epoch, "Connection lost; any pending command outcome is uncertain. Reconnect to refresh.") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(epoch, "Connection closed; reconnect to refresh") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            })
            withTimeout(connectionTimeoutMillis) { opened.await() }
            pongAt = System.currentTimeMillis()
            heartbeat = scope.launch { while (isActive && epoch == generation) {
                delay(15_000)
                if (System.currentTimeMillis() - pongAt > 45_000) { fail(epoch, "Connection timed out; reconnect to refresh"); break }
                send(EffectRpc.signal("Ping"))
            } }
            val config = rpc("server.getConfig", buildJsonObject {}).jsonObject
            check(epoch == generation) { "Connection was cancelled" }
            authorized = mayOperate
            val models = config.objects("providers").filter { it["enabled"]?.jsonPrimitive?.booleanOrNull != false }.flatMap { provider ->
                provider.objects("models").map { ModelOption(provider.string("instanceId"), it.string("slug"), it.string("name")) }
            }
            update { it.copy(connection = ConnectionStatus.Connected, models = models) }
            subscribe("orchestration.subscribeShell", buildJsonObject { put("requestCompletionMarker", true) }, ::shellItem)
            state.value.selectedThreadId?.let { selectThread(it) }
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException && currentCoroutineContext().isActive) {
                val message = "T3 connection timed out. Check the server and network, then try again."
                fail(epoch, message)
                throw PairingException(message)
            }
            synchronized(lifecycleLock) { if (epoch == generation) disconnectNow() }
            throw error
        }
        catch (error: Exception) {
            val message = when (error) {
                is PairingException -> error.message ?: "Connection failed"
                is HttpFailure -> error.message ?: "Connection failed"
                else -> "Connection failed. Check the network and server, or pair again."
            }
            fail(epoch, message)
            throw PairingException(message)
        }
    }
    private fun send(frame: JsonObject) { check(socket?.send(frame.toString()) == true) { "Connection is unavailable" } }
    private suspend fun rpc(tag: String, payload: JsonObject, epoch: Long = generation): JsonElement {
        check(pending.size < 64) { "Too many outstanding requests" }
        val id = serial.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>(); pending[id] = result
        try { synchronized(lifecycleLock) { check(epoch == generation) { "Session changed; command was not sent" }; send(EffectRpc.request(id, tag, payload)) }; return withTimeout(requestTimeoutMillis) { result.await() } }
        finally { if (pending.remove(id) != null) runCatching { send(EffectRpc.signal("Interrupt", id)) } }
    }
    private fun subscribe(tag: String, payload: JsonObject, handler: (JsonObject) -> Unit): String {
        val id = serial.incrementAndGet().toString(); streams[id] = handler
        try { send(EffectRpc.request(id, tag, payload)) } catch (error: Exception) { streams.remove(id); throw error }
        return id
    }
    private fun receive(frame: JsonObject) {
        val id = frame.string("requestId")
        when (frame.string("_tag")) {
            "Pong" -> pongAt = System.currentTimeMillis()
            "Chunk" -> { frame.objects("values").forEach { streams[id]?.invoke(it) }; send(EffectRpc.signal("Ack", id)) }
            "Exit" -> {
                val exit = frame.obj("exit")
                val result = pending.remove(id)
                if (exit.string("_tag") == "Success") result?.complete(exit["value"] ?: JsonNull)
                else result?.completeExceptionally(IllegalStateException("Server rejected request; refresh before trying again"))
                if (streams.remove(id) != null) fail(generation, "Subscription ended; reconnect to refresh")
            }
            "Defect", "ClientProtocolError" -> fail(generation, "Server protocol failure; reconnect to refresh")
        }
    }
    private fun fail(epoch: Long, message: String) = synchronized(lifecycleLock) {
        if (epoch != generation) return@synchronized
        generation++
        refreshSignals?.close(); refreshJob?.cancel()
        shellReady = false; threadReady = false; authorized = false
        update { it.copy(connection = ConnectionStatus.Error, canWrite = false, busy = false, error = message) }
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }; pending.clear()
        streams.clear(); heartbeat?.cancel(); socket?.cancel(); socket = null
    }
    override suspend fun disconnect() = disconnectNow()
    private fun disconnectNow() = synchronized(lifecycleLock) {
        generation++; refreshSignals?.close(); refreshJob?.cancel(); heartbeat?.cancel(); heartbeat = null
        socket?.close(1000, null); socket = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Disconnected; pending command outcome may be uncertain")) }
        pending.clear(); streams.clear(); threadSubscription = null; shellReady = false; threadReady = false; authorized = false
        update { it.copy(connection = ConnectionStatus.Disconnected, canWrite = false, busy = false) }
    }
    override suspend fun selectThread(threadId: String) = synchronized(lifecycleLock) {
        check(state.value.connection == ConnectionStatus.Connected) { "Connect first" }
        threadSubscription?.let { streams.remove(it); send(EffectRpc.signal("Interrupt", it)) }
        threadReady = false; projection = JsonObject(emptyMap())
        update { it.copy(selectedThreadId = threadId, messages = emptyList(), requests = emptyList(), activities = emptyList(), activeRunId = null, canWrite = false) }
        val epoch = generation
        refreshSignals?.close(); refreshJob?.cancel()
        val signals = Channel<Unit>(Channel.CONFLATED)
        var revision = 0L
        var appliedRevision = 0L
        var synchronizedSeen = false
        refreshSignals = signals
        refreshJob = scope.launch {
            for (signal in signals) {
                val startedRevision = synchronized(lifecycleLock) { revision }
                try {
                    val value = rpc("orchestration.getThreadProjection", buildJsonObject { put("threadId", threadId) }).jsonObject
                    synchronized(lifecycleLock) {
                        if (epoch == generation && state.value.selectedThreadId == threadId) {
                            applyProjection(value); appliedRevision = startedRevision; threadReady = synchronizedSeen && appliedRevision == revision; writable()
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { synchronized(lifecycleLock) { if (epoch == generation) fail(epoch, "Thread refresh failed; reconnect before writing") }; break }
            }
        }
        threadSubscription = subscribe("orchestration.subscribeThread", buildJsonObject { put("threadId", threadId); put("requestCompletionMarker", true) }) { item ->
            if (generation == epoch && state.value.selectedThreadId == threadId) {
                when (item.string("kind")) {
                    "snapshot" -> { applyProjection(item.obj("projection")); appliedRevision = revision }
                    "synchronized" -> { synchronizedSeen = true; threadReady = appliedRevision == revision; writable() }
                    "event" -> { revision++; threadReady = false; writable(); signals.trySend(Unit) }
                }
            }
        }
    }
    private fun writable() = update { it.copy(canWrite = authorized && shellReady && (it.selectedThreadId == null || threadReady) && it.connection == ConnectionStatus.Connected) }
    private fun shellItem(item: JsonObject) {
        when (item.string("kind")) {
            "snapshot" -> { val snapshot = item.obj("snapshot"); update { it.copy(projects = snapshot.objects("projects").map(::project), threads = snapshot.objects("threads").map { threadSummary(it) } + snapshot.objects("archivedThreads").map { threadSummary(it, archived = true) }) } }
            "project.updated" -> { val value = project(item.obj("project")); update { it.copy(projects = it.projects.filterNot { p -> p.id == value.id } + value) } }
            "project.removed" -> update { it.copy(projects = it.projects.filterNot { p -> p.id == item.string("projectId") }) }
            "thread.updated" -> { val value = threadSummary(item.obj("thread")); update { it.copy(threads = it.threads.filterNot { t -> t.id == value.id } + value) } }
            "thread.removed" -> update { it.copy(threads = it.threads.filterNot { t -> t.id == item.string("threadId") }) }
            "synchronized" -> { shellReady = true; writable() }
        }
    }
    private fun project(value: JsonObject) = Project(value.string("id"), value.string("title"), value.string("workspaceRoot"))
    private fun applyProjection(value: JsonObject) {
        projection = value
        val items = presentationItems(value)
        val requests = value.objects("runtimeRequests").filter { it.string("status") == "pending" }.map { request ->
            val item = items.firstOrNull { it.string("requestId") == request.string("id") } ?: JsonObject(emptyMap())
            val questions = item.objects("questions").map { q -> Question(q.string("id"), q.string("question"), q.objects("options").map { it.string("label") }, q["multiSelect"]?.jsonPrimitive?.booleanOrNull == true, q["required"]?.jsonPrimitive?.booleanOrNull != false) }
            val kind = request.string("kind")
            val offered = item.objects("options").map { it.string("decision") }
            val basicApproval = item.string("type") == "approval_request" && (offered.isEmpty() || ("accept" in offered && "decline" in offered))
            val supported = request.obj("responseCapability").string("type") == "live" && (basicApproval || item.string("type") == "user_input_request")
            RuntimeRequest(request.string("id"), item.string("prompt").ifBlank { kind }, if (supported) item.string("reason") else "Respond using another T3 client: unsupported or non-resumable request", questions, supported)
        }
        val active = value.objects("runs").lastOrNull { it.string("status") in setOf("preparing", "running", "starting", "waiting") }?.string("id")
        update { it.copy(messages = conversationMessages(value),
            activities = toolActivities(value), requests = requests, activeRunId = active) }
    }
    private fun requireWrite() { check(state.value.canWrite) { "Wait for a fresh connection before writing" } }
    private fun command(type: String, extra: JsonObject): JsonObject = buildJsonObject {
        put("type", type); put("commandId", UUID.randomUUID().toString()); put("threadId", state.value.selectedThreadId ?: error("Select a thread")); extra.forEach { (key, value) -> put(key, value) }
    }
    private suspend fun dispatch(type: String, extra: JsonObject) {
        val (epoch, payload) = synchronized(lifecycleLock) { requireWrite(); generation to command(type, extra) }
        try { rpc("orchestration.dispatchCommand", payload, epoch) }
        catch (error: Exception) {
            synchronized(lifecycleLock) { if (epoch == generation) fail(epoch, "Command outcome is uncertain or rejected; reconnect to reconcile before retrying") }
            throw error
        }
    }
    override suspend fun sendMessage(text: String) {
        require(text.isNotBlank()) { "Message is empty" }
        dispatch("message.dispatch", buildJsonObject { put("messageId", UUID.randomUUID().toString()); put("text", text); put("attachments", JsonArray(emptyList())); put("dispatchMode", buildJsonObject { put("type", if (state.value.activeRunId == null) "start_immediately" else "queue_after_active") }) })
    }
    override suspend fun interruptRun() { dispatch("run.interrupt", buildJsonObject { put("runId", state.value.activeRunId ?: error("No active run")) }) }
    override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) {
        requireWrite()
        val request = state.value.requests.firstOrNull { it.id == requestId } ?: error("Request is no longer pending")
        check(request.canApprove) { "Use another T3 client to respond to this request" }
        val item = presentationItems(projection).firstOrNull { it.string("requestId") == requestId } ?: error("Missing request detail")
        dispatch("runtime-request.respond", buildJsonObject {
            put("requestId", requestId)
            if (item.string("type") == "user_input_request") {
                val questions = item.objects("questions")
                questions.forEach { q -> if (q["required"]?.jsonPrimitive?.booleanOrNull != false) require(!answers[q.string("id")].isNullOrEmpty()) { "Answer each required question" } }
                put("answers", buildJsonObject { questions.forEach { q ->
                    val labels = answers[q.string("id")].orEmpty()
                    val values = labels.map { label -> q.objects("options").firstOrNull { it.string("label") == label }?.let { it.string("value").ifBlank { label } } ?: label }
                    put(q.string("id"), if (q["multiSelect"]?.jsonPrimitive?.booleanOrNull == true) JsonArray(values.map(::JsonPrimitive)) else JsonPrimitive(values.firstOrNull().orEmpty()))
                } })
            } else { val offered = item.objects("options").map { it.string("decision") }; require(offered.isEmpty() || decision in offered) { "This response is not offered by the provider" }; require(decision in setOf("accept", "acceptForSession", "acceptAlways", "decline", "cancel")) { "Invalid approval decision" }; put("decision", decision) }
        })
    }
    override suspend fun createThread(projectId: String, title: String, model: ModelOption): String {
        requireWrite(); require(title.isNotBlank()); require(model in state.value.models) { "Choose a server-provided model" }
        val epoch = generation
        return try { rpc("orchestration.launchThread", buildJsonObject {
            put("commandId", UUID.randomUUID().toString()); put("projectId", projectId); put("title", title)
            put("modelSelection", buildJsonObject { put("instanceId", model.instanceId); put("model", model.model) })
            put("runtimeMode", "approval-required"); put("interactionMode", "default"); put("workspaceStrategy", buildJsonObject { put("type", "root") })
        }, epoch).jsonObject.string("threadId").also { require(it.isNotBlank()) { "Server returned no thread ID" } } }
        catch (error: Exception) { fail(epoch, "Thread creation outcome is uncertain or rejected; reconnect to reconcile before retrying"); throw error }
    }
    override suspend fun reconnect() {
        val current = state.value
        val environment = current.environment ?: error("Pair an environment first")
        connect(environment)
        current.selectedThreadId?.let { selectThread(it) }
    }
    /** Remove in-memory credentials as well as the local connection; no remote revoke. */
    fun forgetEnvironment() { disconnectNow(); mutable.value = ClientState() }
    override fun close() { disconnectNow(); http.connectionPool.evictAll() }
}

/** Policy applies before every environment request; redirects remain disabled on the HTTP client. */
internal fun validatedEnvironmentUrl(value: String, allowInsecureHttp: Boolean = false): HttpUrl {
    val target = try { value.toHttpUrl() } catch (_: Exception) { throw PairingException("Invalid environment address") }
    if (target.username.isNotEmpty() || target.password.isNotEmpty()) throw PairingException("Credentials in URL authority are unsupported")
    if (target.isHttps) return target
    if (target.host in setOf("localhost", "127.0.0.1", "::1")) return target
    val literalHost = try { java.net.URI(value).host } catch (_: Exception) { null }
    if (literalHost == target.host && isPrivateLanAddress(target.host)) {
        if (allowInsecureHttp) return target
        throw PairingException("This link uses unencrypted HTTP. Enable private LAN HTTP for this environment or use SSH/HTTPS.")
    }
    throw PairingException("Use HTTPS or SSH for remote environments. HTTP is only allowed for private LAN IP addresses with explicit consent.")
}
