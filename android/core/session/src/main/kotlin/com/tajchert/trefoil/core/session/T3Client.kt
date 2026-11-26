package com.tajchert.trefoil.core.session

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val FRESH_THREAD_WAIT_MS = 3_000L
/** After a route verifies, how long better-ranked routes still in flight may take to answer before it is chosen. */
internal const val ROUTE_HEAD_START_MS = 500L
private const val LINK_STATE_TIMEOUT_MS = 5_000L

/** Local and Tailnet addresses answer fast or not at all; public HTTPS, the tunnel and SSH forwards get longer. */
internal fun routePreflightTimeoutMillis(kind: RouteKind): Long = when (kind) {
    RouteKind.Lan, RouteKind.Tailnet -> 3_000L
    RouteKind.Public, RouteKind.Ssh, RouteKind.Tunnel -> 15_000L
}

/** `ServerConfig.directEndpoints`: null when absent (the server did not say); entries without a kind or URL are skipped. */
internal fun directEndpointHints(config: JsonObject): List<DirectEndpointHint>? {
    val entries = config["directEndpoints"] as? JsonArray ?: return null
    return entries.mapNotNull { entry ->
        val value = entry as? JsonObject ?: return@mapNotNull null
        val kind = (value["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val url = (value["httpBaseUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (kind.isEmpty() || url.isEmpty()) null else DirectEndpointHint(kind, url)
    }
}

/** `/api/connect/link-state` reply; every field is optional (`tunnelHostname` is newer than the protocol pin). */
internal fun tunnelHint(state: JsonObject): TunnelHint {
    fun flag(key: String) = (state[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val hostname = (state["tunnelHostname"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    return when {
        flag("linked") == false || flag("linkRevoked") == true -> TunnelHint.NotLinked
        flag("linked") == true && flag("managedTunnelActive") != false && hostname.isNotEmpty() -> TunnelHint.Active(hostname)
        else -> TunnelHint.Unknown
    }
}

/** Native direct-environment client. Mutations are never retried or replayed. */
class T3Client(
    private val scope: CoroutineScope,
    private val connectionTimeoutMillis: Long = 20_000,
    private val requestTimeoutMillis: Long = 30_000,
    /** Frames longer than this are not parsed; tests lower it (a test server cannot send a 16 MB frame). */
    private val maxFrameChars: Int = EffectRpc.MAX_FRAME_CHARS,
    private val usageClockMillis: () -> Long = System::currentTimeMillis,
    /** Descriptor preflight timeout per route kind during [connect]. */
    private val routeTimeoutMillis: (RouteKind) -> Long = ::routePreflightTimeoutMillis,
    /**
     * How long after the first route hint a first config snapshot's `directEndpoints` count as complete when no later
     * snapshot changed them. T3 bounds its full discovery (Tailscale probe) to a few seconds.
     */
    private val directEndpointsSettleMillis: Long = 10_000,
    /** This device's DPoP proof key, loaded lazily; needed for DPoP environments (null: bearer only). */
    dpopKey: (() -> DpopKey)? = null,
    /** Renews DPoP sessions; share one per process so renewals of an environment are serialized and persisted. */
    private val tokens: EnvironmentTokenRenewer = EnvironmentTokenRenewer(dpopKey),
    /**
     * Share one per process with the renewer and the probe: this client derives its own from it and keeps its connection
     * pool. A shared pool is never evicted by [close]; null gives this client a private pool that [close] evicts.
     */
    http: OkHttpClient? = null,
) : TrefoilClient {
    private val authorizer = tokens.authorizer
    private var renewJob: Job? = null
    private val mutable = MutableStateFlow(ClientState())
    override val state: StateFlow<ClientState> = mutable.asStateFlow()
    private val ownsPool = http == null
    private val http = (http ?: OkHttpClient()).newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val fileDownloads = mutableSetOf<Call>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0L
    private var heartbeat: Job? = null
    private var threadSubscription: String? = null
    /** Server setting `promptSuggestionsEnabled` of the live connection (missing or not a boolean: false). Guarded by [lifecycleLock]. */
    private var promptSuggestionsEnabled = false
    /** Request id of the live `orchestration.subscribePromptSuggestion` stream; null once it ended. */
    private var suggestionSubscription: String? = null
    /** Thread the suggestion stream was started for; kept after the stream ends so it is never retried for that thread. */
    private var suggestionThread: String? = null
    /** Bumped on every suggestion stream start and stop; a handler of an older stream never publishes. */
    private var suggestionGeneration = 0L
    private val visits = ThreadVisitTracker()
    private var visitTimer: Job? = null
    private var threadViewing = false
    private var shellReady = false
    /** The current shell subscription (not just the connection) delivered its `synchronized` marker; see [checkpointShell]. */
    private var shellSynced = false
    /** The connect of this generation decided how to subscribe the shell; a cache restore after that is refused. */
    private var shellPlannedEpoch = -1L
    /** A thread selected before the shell of this connection was synchronized; subscribed (or dropped) at the marker. */
    private var deferredThread: String? = null
    /** Request id of the live `orchestration.subscribeShell` stream; replaced by [refreshThreads]. */
    private var shellSubscription: String? = null
    /** Completes when the current shell stream's snapshot is synchronized; shared by concurrent [refreshThreads] calls. */
    private var shellRefresh: CompletableDeferred<Unit>? = null
    /** The live shell subscription sent `afterSequence` and is still replaying deltas; a full refresh upgrades it. */
    private var shellReplaying = false
    /** A full refresh asked for before the connect subscribed the shell: that subscription is then a full snapshot. */
    private var shellFullWanted = false
    /** Test seam: runs once the config is parsed, just before the connection is published. */
    @Volatile internal var beforeConnectPublished: (() -> Unit)? = null
    /** Shell origin of the live connection from its first server config; what [resyncThreads] resumes against. */
    private var liveShellOrigin: ShellOrigin? = null
    private var threadReady = false
    private var authorized = false
    private var previewAuthorized = false
    /** The session holds `settings:write`; [ClientState.canWriteSettings] also needs a live shell. */
    private var settingsAuthorized = false
    /** Model preferences the live connection's server last reported, before [pendingFavorites] apply. */
    private var serverPreferences = ModelPreferences()
    /** Favourite changes sent on this connection and not answered yet, oldest first: shown optimistically, never resent. */
    private val pendingFavorites = mutableListOf<FavoriteChange>()
    private var serverBrowser = false
    private var browserScreensFresh = false
    private var deviceScreensFresh = false
    private var screenRead = 0L
    private var deviceScreenRevision = -1L
    private var previewScreenValue = JsonObject(emptyMap())
    private val previewScreenEvents = ArrayDeque<JsonObject>()
    private var previewScreenSubscription: String? = null
    private var deviceScreenSubscription: String? = null
    private val optionalScreenStreams = mutableSetOf<String>()
    /** Request id of the live `subscribeDiscoveredLocalServers` stream; optional, never fails the session. */
    private var discoverySubscription: String? = null
    /** Connection generation the discovery stream was started (or rejected) for; it starts at most once per connection. */
    private var discoveryEpoch = -1L
    /** Loopback forwards of the live connection by remote port; see [LoopbackPortForwarder]. */
    private val portForwarders = linkedMapOf<Int, LoopbackPortForwarder>()
    /** The live connection's shared media ticket for tunnel connections, keyed by connection generation. */
    private var tunnelTickets: Pair<Long, MediaTicketCache>? = null
    /** One admission for every forward, matching [tunnelHttp]'s own dispatcher, so tunnels never queue or starve RPC. */
    private val tunnelAdmission = TunnelAdmission()
    private val tunnelHttp: OkHttpClient by lazy { tunnelClient(this.http, tunnelAdmission) }
    @Volatile private var pongAt = 0L
    /** Arrival of the live connection's latest frame of any kind; a large snapshot in transit keeps it fresh. */
    @Volatile private var frameAt = 0L
    /** Counts pongs of the live connection, so [verifyLink] can wait for one newer than its ping. */
    private val pongs = MutableStateFlow(0L)
    private val serial = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val lifecycleLock = Any()
    private data class UsageKey(val environmentId: String, val range: UsageRange, val timeZone: String, val untilDay: String)
    private data class CachedUsage(val report: UsageReport, val at: Long)
    private val usageCache = mutableMapOf<UsageKey, CachedUsage>()
    private val limitsFinished = mutableMapOf<String, Long>()
    private var limitsInFlight: Pair<Long, Deferred<Boolean>>? = null
    /** Validated base of the live connection's route; HTTP side calls (media tickets, downloads, link-state) use it. */
    private var activeBase: HttpUrl? = null
    /** Routes of the last multi-route connect, so [reconnect] walks the same plan. */
    private var lastRoutes: Pair<String, List<EnvironmentRoute>>? = null
    /** Rows and resume cursor per environment as of the last synchronized shell state; what the local cache stores. */
    private class ShellCheckpoint(val projects: List<Project>, val threads: List<ThreadSummary>, val cursor: CachedShellCursor?)
    private val shellCheckpoints = mutableMapOf<String, ShellCheckpoint>()
    private val checkpoints = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    /**
     * Environment ids whose [shellCacheEntry] moved: emitted at each `synchronized` marker of a shell subscription (even
     * when the rows did not change) and for each later change applied while it stays synchronized. Rows a subscription
     * applies before its marker (a catch-up or a full refresh) never become a checkpoint, so a failure keeps the last one.
     */
    val shellCheckpointed: Flow<String> = checkpoints.asSharedFlow()
    private val hints = MutableSharedFlow<RouteHints>(extraBufferCapacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val routeHints: Flow<RouteHints> = hints.asSharedFlow()
    /** Last `directEndpoints` reported for the live connection (null: not reported); snapshots emit only on change. */
    private var directEndpoints: List<DirectEndpointHint>? = null
    private var configSubscription: String? = null
    /** The live connection's first `subscribeServerConfig` snapshot while [firstServerConfig] waits for it. */
    private var firstConfig: CompletableDeferred<JsonObject>? = null
    /** Whether the connection published its first config; config stream events wait in [earlyConfigEvents] until then. */
    private var configPublished = false
    private val earlyConfigEvents = mutableListOf<JsonObject>()
    /** The live connection's providers as the config stream last reported them: the base of `providerStatusDeltas`. */
    private var configProviders: List<JsonObject> = emptyList()
    /** The live connection's preview-tunnel config, kept current by [serverConfigEvent]. Guarded by [lifecycleLock]. */
    private var tunnelConfig = PreviewTunnelConfig()
    private var linkStateJob: Job? = null
    /** [startRouteHints] for the live connection, run at its first shell `synchronized` marker. */
    private var pendingRouteHints: (() -> Unit)? = null
    /** Whether the first (first config + link-state) hint went out; earlier snapshots only update [directEndpoints]. */
    private var initialHintSent = false
    /**
     * Whether [directEndpoints] came from the first config snapshot, which T3 builds before network discovery finishes
     * (e.g. without the Tailscale endpoint), and no later snapshot or [directEndpointsSettleMillis] confirmed it yet.
     * Hints from such a list only add routes.
     */
    private var endpointsProvisional = false
    /** Mutations between their first local check and their outcome; see [isIdleForRouteSwitch]. */
    private val mutationsInFlight = java.util.concurrent.atomic.AtomicInteger()
    fun millisSinceLastPong(): Long? = synchronized(lifecycleLock) {
        if (socket == null) null else (System.currentTimeMillis() - pongAt).coerceAtLeast(0)
    }
    private var refreshSignals: Channel<Unit>? = null
    private var refreshJob: Job? = null
    private var projection = JsonObject(emptyMap())
    private var projectionRevision = 0L
    private var threadGeneration = 0L
    // Memory only: navigation/foreground refreshes retain content without writing transcripts to disk. A retained state
    // keeps its rows and `shellLoaded`, so reconnecting to it shows them at once; a new environment starts unloaded.
    private val environmentStates = linkedMapOf<String, ClientState>()
    // Recently viewed threads with their derived rows, so a revisit paints at once (memory only, bounded; see the class).
    private val threadProjections = ThreadProjectionCache()
    private fun cacheState() {
        val current = state.value
        current.environment?.id?.let { id ->
            environmentStates.remove(id)
            environmentStates[id] = current.copy(canWrite = false, canWriteThreads = false, canWriteSettings = false, canRead = false, shellLive = false, canReadUsage = false, queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false, busy = false, error = null, screens = ScreenCatalog(), localServers = LocalServerCatalog(), portForwards = emptyList(), promptSuggestion = null)
            evictRetained()
        }
    }
    /** Keeps the four most recently retained environments; an evicted one loses its cursor and thread projections. */
    private fun evictRetained() {
        while (environmentStates.size > 4) {
            val evicted = environmentStates.keys.first()
            environmentStates.remove(evicted)
            if (evicted != state.value.environment?.id) shellCheckpoints.remove(evicted)
            shellResume.forget(evicted)
            threadProjections.forgetEnvironment(evicted)
        }
    }
    private fun update(block: (ClientState) -> ClientState) = mutable.update(block)
    private fun url(value: String, allowInsecureHttp: Boolean = false): HttpUrl = validatedEnvironmentUrl(value, allowInsecureHttp)
    /**
     * [enqueued] runs the call on OkHttp's dispatcher and only suspends for it, so a cancelled caller returns at once
     * even while the call is stuck where cancelling it does not reach (a DNS lookup); route preflights use it so a
     * losing route never holds up the winner.
     */
    private suspend fun fetch(request: Request, timeoutMillis: Long? = null, enqueued: Boolean = false): JsonObject {
        val call = http.newCall(request)
        timeoutMillis?.let { call.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (!enqueued) return execute(call)
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
                override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            })
        }
        return withContext(Dispatchers.IO) { response.use(::parseFetched) }
    }
    private fun parseFetched(response: Response): JsonObject {
        if (!response.isSuccessful) throw HttpFailure(response.code, authorizer.shouldRetry(response))
        return EffectRpc.json.parseToJsonElement(response.body?.string() ?: error("Empty server response")).jsonObject
    }
    private suspend fun execute(call: Call): JsonObject = withContext(Dispatchers.IO) {
        // A cancelled caller (superseded connect, abandoned preflight) cancels the blocking call instead of waiting it out.
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use(::parseFetched)
        } finally { cancellation.cancel() }
    }
    /**
     * Authenticated HTTP to an already verified [url] with the saved token's scheme. A DPoP token due for renewal is
     * renewed at [url]'s host first. A DPoP `replay` or clock-skew answer gets exactly one retry with a fresh proof; any
     * other DPoP 401 renews once and retries; anything else propagates (401/403: pair again).
     */
    private suspend fun authorizedFetch(environment: SavedEnvironment, method: String, url: HttpUrl, timeoutMillis: Long? = null): JsonObject {
        val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        var current = usableToken(environment, base)
        // Off the caller's thread: a DPoP proof is a keystore signature.
        suspend fun request() = withContext(Dispatchers.IO) {
            authorizer.authorize(Request.Builder().url(url), current, method, url).apply { if (method == "POST") post(ByteArray(0).toRequestBody()) }.build()
        }
        return try { fetch(request(), timeoutMillis) } catch (error: HttpFailure) {
            when {
                error.dpopRetry -> fetch(request(), timeoutMillis)
                error.status == 401 && current.tokenType == AccessTokenType.DPoP -> {
                    current = tokens.fresh(current, base, rejectedToken = current.accessToken).also(::adoptToken)
                    fetch(request(), timeoutMillis)
                }
                else -> throw error
            }
        }
    }
    /** The newest token for [environment], renewed when due; a renewal that cannot reach the host keeps an unexpired token. */
    private suspend fun usableToken(environment: SavedEnvironment, base: HttpUrl): SavedEnvironment {
        if (environment.tokenType != AccessTokenType.DPoP) return environment
        return try { tokens.fresh(environment, base).also(::adoptToken) } catch (error: TokenRenewalUnavailable) {
            tokens.current(environment).takeUnless(tokens::isExpired) ?: throw error
        }
    }
    /**
     * A renewal keeps the live session (and its socket); only the connected environment's token fields change, and only
     * when [renewed] renews the live token's own session: a late renewal of an older session never replaces a newer pairing.
     */
    private fun adoptToken(renewed: SavedEnvironment) = synchronized(lifecycleLock) {
        update { state ->
            val live = state.environment?.takeIf { it.id == renewed.id && it.accessToken != renewed.accessToken } ?: return@update state
            tokens.current(live).takeIf { it.accessToken == renewed.accessToken }?.let { state.copy(environment = live.withTokenOf(renewed)) } ?: state
        }
    }
    /**
     * Renews the live connection's DPoP token [EnvironmentTokenRenewer.RENEW_BEFORE_EXPIRY_MS] before it expires, at the
     * verified [base]. A failed attempt retries (the session outlives the token); a definite refusal ends the connection.
     */
    private fun startRenewal(epoch: Long, base: HttpUrl) = synchronized(lifecycleLock) {
        if (epoch != generation || state.value.environment?.tokenType != AccessTokenType.DPoP) return@synchronized
        renewJob?.cancel()
        renewJob = scope.launch {
            var failed = false
            while (isActive && epoch == generation) {
                val environment = state.value.environment?.let(tokens::current) ?: break
                val expiresAt = environment.accessTokenExpiresAtMillis ?: break
                val now = System.currentTimeMillis()
                val wait = expiresAt - EnvironmentTokenRenewer.RENEW_BEFORE_EXPIRY_MS - now
                // The floor spaces out retries and keeps a short-lived token or a clock jump from spinning.
                delay(if (failed) maxOf(wait, if (now < expiresAt) 5_000 else EnvironmentTokenRenewer.RENEW_BEFORE_EXPIRY_MS) else maxOf(wait, 0))
                if (epoch != generation) break
                failed = try { adoptToken(tokens.fresh(state.value.environment ?: break, base)); false }
                catch (error: CancellationException) { throw error }
                catch (_: TokenRenewalUnavailable) { true }
                catch (error: Exception) { fail(epoch, error.message ?: "Sign-in expired; pair again"); break }
                if (!failed) delay(1_000)
            }
        }
    }
    /** [dpopRetry]: a DPoP rejection one fresh proof can fix (see [EnvironmentAuthorizer.shouldRetry]). */
    private class HttpFailure(val status: Int, val dpopRetry: Boolean = false) : IllegalStateException(if (status == 401 || status == 403) "Authorization expired or denied; pair again" else "Server HTTP error $status")
    /** The server answered with a failure Exit: a definite answer, not a lost reply. */
    private class ServerRejected : IllegalStateException("Server rejected request; refresh before trying again")
    /** A local check stopped the command before anything was transmitted: nothing changed, so the session stays usable. */
    private class CommandNotSent : IllegalStateException("The thread changed or went stale; the command was not sent")
    /** A success Exit whose value is not a dispatch receipt (`{sequence: non-negative integer}`): the outcome is uncertain. */
    private class MalformedReceipt : IllegalStateException("Server answered without a command receipt")
    /** Part of a stop was sent, but work remains that nothing this client may send can stop. */
    private class PartialStop : IllegalStateException("Some agent work could not be stopped from here; use another T3 client")
    /** The server answered with an interrupted Exit: it stopped handling the request, so the outcome is unknown. */
    private class RequestInterrupted : IllegalStateException("Server interrupted the request; the outcome is uncertain")
    /** No reply in time. Deliberately not a CancellationException, so callers cannot swallow it as cancellation. */
    internal class RequestTimedOut : IllegalStateException("Server did not answer in time; the outcome is uncertain")
    /** The config stream was rejected or ended before its first snapshot: the connect asks `server.getConfig` instead. */
    private class ConfigStreamUnavailable : IllegalStateException("Server config stream unavailable")
    /** Credential-free: the public descriptor never receives a token. */
    private suspend fun descriptor(base: HttpUrl, timeoutMillis: Long? = null, enqueued: Boolean = false): JsonObject = fetch(Request.Builder().url(base.resolve("/.well-known/t3/environment")!!).build(), timeoutMillis, enqueued).also {
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
        val tokenUrl = base.resolve("/oauth/token")!!
        // A host advertising `accessTokenRenewal` binds a token exchanged with a proof (no `ath`) to this key, on a session
        // the device renews itself (T3 client runtime `preparePairingRegistration`). It checks the proof before spending
        // the credential, so one retry after `replay`/clock skew is safe. Other hosts, or no usable key: plain bearer.
        val renewable = (info["capabilities"] as? JsonObject)?.get("accessTokenRenewal")?.let { it as? JsonPrimitive }?.takeUnless { it.isString }?.booleanOrNull == true
        suspend fun exchange(): Request {
            val proof = if (renewable && authorizer.supportsDpop) withContext(Dispatchers.IO) { try { authorizer.proof("POST", tokenUrl, null) } catch (_: DpopKeyUnavailable) { null } } else null
            return Request.Builder().url(tokenUrl).post(form).apply { if (proof != null) header("DPoP", proof) }.build()
        }
        val sent = exchange()
        val token = try { fetch(sent) } catch (error: HttpFailure) { if (!error.dpopRetry || sent.header("DPoP") == null) throw error; fetch(exchange()) }
        currentCoroutineContext().ensureActive()
        val tokenType = when (token.string("token_type")) {
            "Bearer" -> AccessTokenType.Bearer
            "DPoP" -> AccessTokenType.DPoP.also { require(sent.header("DPoP") != null) { "Server issued a DPoP token without a proof" } }
            else -> throw IllegalArgumentException("Server issued an unsupported token type")
        }
        require(token.string("access_token").isNotBlank()) { "Server returned an empty access token" }
        val expiresAt = (token["expires_in"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it > 0 }?.let { System.currentTimeMillis() + (it * 1000).toLong() }
        SavedEnvironment(info.string("environmentId").ifBlank { error("Missing environment identity") }, name.ifBlank { info.string("label") }, base.toString(), token.string("access_token"), allowInsecureHttp = allowInsecureHttp && base.scheme == "http" && isPrivateLanAddress(base.host),
            tokenType = tokenType, accessTokenExpiresAtMillis = expiresAt).also { tokens.forget(it.id) }
    } catch (error: CancellationException) { throw error }
    catch (error: PairingException) { throw error }
    catch (error: HttpFailure) { throw PairingException(if (error.status == 401 || error.status == 403) "Pairing token expired or was denied. Generate a new pairing link." else "Pairing server returned HTTP ${error.status}. Check the server and try again.") }
    catch (_: Exception) { throw PairingException("Pairing failed. Check the link, network, and server compatibility, then try again.") }

    /** Connects to `environment.baseUrl` as a single route (an SSH environment's ephemeral loopback is an `Ssh` route). */
    override suspend fun connect(environment: SavedEnvironment) {
        val kind = if (environment.ssh != null) RouteKind.Ssh else classifyRoute(environment.baseUrl, environment.tunnelHost())
        synchronized(lifecycleLock) { lastRoutes = null }
        connectThrough(environment, listOf(EnvironmentRoute(kind, environment.baseUrl, RouteSource.Paired)))
    }

    override suspend fun connect(environment: SavedEnvironment, routes: List<EnvironmentRoute>) {
        if (routes.isEmpty()) throw PairingException("No address to reach this machine. Pair it again.")
        synchronized(lifecycleLock) { lastRoutes = environment.id to routes.toList() }
        connectThrough(environment, routes)
    }

    private data class SelectedRoute(val route: EnvironmentRoute, val base: HttpUrl, val session: JsonObject)
    /** A route's preflight that did not verify, with why ([ConnectFailure]). */
    private class RouteFailure(val kind: ConnectFailure, val error: Exception)
    /** [selectRoute] found no usable route; [error] is the one reported (its message is kept), [kind] the typed reason. */
    private class RouteSelectionFailed(val kind: ConnectFailure, val error: Exception) : Exception(error.message)

    /** A route that failed before a successful session check, other than a rejected sign-in, policy or identity. */
    private fun preSessionKind(error: Exception): ConnectFailure = when (error) {
        is PairingException -> ConnectFailure.Identity
        else -> ConnectFailure.Unreachable
    }

    /**
     * Preflights [routes] concurrently: each passes the URL policy and must answer the credential-free descriptor as
     * `environment.id` within its kind's timeout before the bearer may go to it. The best-ranked verified route wins;
     * once any route verifies, better-ranked routes still in flight get [ROUTE_HEAD_START_MS] (within their own
     * timeout) to answer before a worse one is chosen. The token goes to the chosen route only. A route that is
     * blocked, silent, failing or another machine is skipped, and so is one whose session read fails; a session that
     * rejects the token (401/403 or not authenticated) stops, since the token belongs to the host and is dead on every
     * route. When every route fails, the error is the last route's, as in a walk in order. Losers are cancelled.
     */
    private suspend fun selectRoute(environment: SavedEnvironment, routes: List<EnvironmentRoute>, epoch: Long): SelectedRoute = coroutineScope {
        // Per route: null while its preflight runs, then the verified base (HttpUrl) or the failure (Exception).
        val preflights = java.util.concurrent.atomic.AtomicReferenceArray<Any?>(routes.size)
        val settled = MutableStateFlow(0)
        routes.forEachIndexed { index, route ->
            launch {
                val timeout = routeTimeoutMillis(route.kind)
                val outcome: Any = try {
                    val base = try { url(route.baseUrl, environment.allowInsecureHttp) }
                        catch (error: PairingException) { throw RouteSelectionFailed(ConnectFailure.UrlPolicy, error) }
                    // OkHttp's call timeout cannot interrupt a DNS lookup; the coroutine timeout ends the wait regardless.
                    val answer = withTimeoutOrNull(timeout) { descriptor(base, timeout, enqueued = true) } ?: throw java.io.InterruptedIOException("timeout")
                    if (answer.string("environmentId") != environment.id) throw PairingException("Environment identity changed; pair again")
                    base
                } catch (error: CancellationException) { throw error }
                catch (error: RouteSelectionFailed) { RouteFailure(error.kind, error.error) }
                catch (error: Exception) { RouteFailure(preSessionKind(error), error) }
                preflights.set(index, outcome)
                settled.update { it + 1 }
            }
        }
        val failures = arrayOfNulls<Exception>(routes.size)
        val kinds = arrayOfNulls<ConnectFailure>(routes.size)
        val tried = BooleanArray(routes.size)
        try {
            while (true) {
                val index = preferredRoute(preflights, tried, settled) ?: break
                tried[index] = true
                val base = preflights.get(index) as HttpUrl
                currentCoroutineContext().ensureActive()
                if (epoch != generation) throw RouteSelectionFailed(ConnectFailure.Other, IllegalStateException("Connection was cancelled"))
                // Identity verified: the bearer goes to this route only.
                val session = try { authorizedFetch(environment, "GET", base.resolve("/api/auth/session")!!) }
                catch (error: CancellationException) { throw error }
                catch (error: HttpFailure) {
                    if (error.status == 401 || error.status == 403) throw RouteSelectionFailed(ConnectFailure.AuthRejected, error)
                    failures[index] = error; kinds[index] = ConnectFailure.Unreachable; continue
                }
                catch (error: DpopKeyUnavailable) { throw RouteSelectionFailed(ConnectFailure.AuthRejected, error) }
                catch (error: TokenRenewalRejected) { throw RouteSelectionFailed(ConnectFailure.AuthRejected, error) }
                catch (error: Exception) { failures[index] = error; kinds[index] = ConnectFailure.Unreachable; continue }
                if (session["authenticated"]?.jsonPrimitive?.booleanOrNull != true)
                    throw RouteSelectionFailed(ConnectFailure.AuthRejected, IllegalArgumentException("Authorization expired; pair again"))
                return@coroutineScope SelectedRoute(routes[index], base, session)
            }
            routes.indices.forEach { index ->
                if (failures[index] == null) (preflights.get(index) as? RouteFailure)?.let { failures[index] = it.error; kinds[index] = it.kind }
            }
            val last = failures.indexOfLast { it != null }
            if (last < 0) throw RouteSelectionFailed(ConnectFailure.Other, PairingException("No address to reach this machine. Pair it again."))
            // Unreachable only when every route failed that way: a refused address or another machine needs the user.
            val kind = kinds.lastOrNull { it != null && it != ConnectFailure.Unreachable } ?: ConnectFailure.Unreachable
            throw RouteSelectionFailed(kind, failures[last]!!)
        } finally { coroutineContext.cancelChildren() }
    }

    /**
     * The best-ranked untried route whose preflight verified, once no better-ranked untried route is still running or
     * the head start since a verified answer is over; null when every untried route failed its preflight.
     */
    private suspend fun preferredRoute(preflights: java.util.concurrent.atomic.AtomicReferenceArray<Any?>, tried: BooleanArray, settled: StateFlow<Int>): Int? {
        var deadline: Long? = null
        while (true) {
            val seen = settled.value
            var betterRunning = false
            var verified = -1
            for (index in 0 until preflights.length()) {
                if (tried[index]) continue
                when (preflights.get(index)) {
                    null -> betterRunning = true
                    is HttpUrl -> { verified = index; break }
                }
            }
            if (!betterRunning) return verified.takeIf { it >= 0 }
            if (verified < 0) { settled.first { it != seen }; continue }
            val now = System.nanoTime()
            val until = deadline ?: (now + ROUTE_HEAD_START_MS * 1_000_000).also { deadline = it }
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(until - now)
            if (remaining <= 0 || withTimeoutOrNull(remaining) { settled.first { it != seen } } == null) return verified
        }
    }

    private suspend fun connectThrough(environment: SavedEnvironment, routes: List<EnvironmentRoute>) {
        disconnect()
        val epoch = generation
        synchronized(lifecycleLock) {
            if (state.value.environment?.id != environment.id) usageCache.clear()
            val retained = environmentStates[environment.id] ?: ClientState()
            projection = JsonObject(emptyMap())
            update { retained.copy(environment = environment, connection = ConnectionStatus.Connecting, canWrite = false, canWriteThreads = false, canWriteSettings = false, modelPreferences = ModelPreferences(), canRead = false, shellLive = false, canReadUsage = false, queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false, busy = false, error = null, settlementUndo = null, threadActionsConfigured = false, activeRoute = null,
                localServers = LocalServerCatalog(), previewTunnelSetting = null, previewTunnelDisabled = false, portForwards = emptyList(), promptSuggestion = null) }
        }
        // Set once the session check succeeded: any later failure is [ConnectFailure.AfterSession].
        var sessionChecked = false
        try {
            val (route, base, session) = selectRoute(environment, routes, epoch)
            sessionChecked = true
            suspend fun ticketRequest(path: String) = authorizedFetch(environment, "POST", base.resolve(path)!!)
            val permissions = (session["permissions"] ?: session["scopes"]) as? JsonArray
            val mayOperate = permissions?.any { it.jsonPrimitive.content == "orchestration:operate" } == true
            val mayReadUsage = permissions?.any { (it as? JsonPrimitive)?.contentOrNull == "diagnostics:read" } == true
            val mayWriteSettings = permissions?.any { (it as? JsonPrimitive)?.contentOrNull == "settings:write" } == true
            val ticket = try { ticketRequest("/api/auth/websocket-ticket/rpc") } catch (error: HttpFailure) {
                if (error.status != 404) throw error
                ticketRequest("/api/auth/websocket-ticket")
            }
            require(ticket.string("ticket").isNotBlank()) { "Missing WebSocket ticket" }
            synchronized(lifecycleLock) { check(epoch == generation) { "Connection was cancelled" }; activeBase = base }
            val socketBase = base.newBuilder().encodedPath("/ws").query(null).build()
            // A DPoP session's RPC ticket is bound to the key; the upgrade cannot carry headers, so its proof (GET, the
            // http(s) /ws URL, `ath` over the ticket) travels in the `dpop` query parameter.
            val upgradeProof = if (environment.tokenType == AccessTokenType.DPoP) withContext(Dispatchers.IO) { authorizer.proof("GET", socketBase, ticket.string("ticket")) } else null
            val endpoint = socketBase.newBuilder().addQueryParameter("wsTicket", ticket.string("ticket")).addQueryParameter("orchestrationProtocol", "2")
                .apply { if (upgradeProof != null) addQueryParameter("dpop", upgradeProof) }.build()
            val opened = CompletableDeferred<Unit>()
            socket = http.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { synchronized(lifecycleLock) { if (epoch == generation) opened.complete(Unit) else webSocket.cancel() } }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(lifecycleLock) {
                        if (epoch == generation) frameAt = System.currentTimeMillis()
                        if (epoch == generation) try { EffectRpc.decode(text, maxFrameChars).forEach { if (epoch == generation) receive(it) } } catch (error: FrameTooLarge) { oversizedFrame(epoch, error) } catch (_: Exception) { fail(epoch, "Invalid server RPC response") }
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
                // A socket torn down between the delay and the ping must not crash the app scope.
                synchronized(lifecycleLock) {
                    if (epoch == generation && socket?.send(EffectRpc.signal("Ping").toString()) != true) fail(epoch, "Connection lost; reconnect to refresh")
                }
            } }
            // Without a cursor that could resume these rows, the shell snapshot does not depend on the config: ask for both
            // at once (config first, so its small snapshot leads). With one, the config's origin decides whether the cursor
            // still resumes, so the shell waits for it.
            val early = synchronized(lifecycleLock) {
                check(epoch == generation) { "Connection was cancelled" }
                shellPlannedEpoch = epoch
                !shellResume.mayResume(environment.id, ShellRows(state.value.projects, state.value.threads))
            }
            val config = coroutineScope {
                val reply = async(start = CoroutineStart.UNDISPATCHED) { firstServerConfig(epoch, environment.id) }
                if (early) subscribeShellBeforeOrigin(epoch, environment.id)
                reply.await()
            }
            // Parsed outside the lock; the epoch check and everything this connection publishes then happen in one locked
            // block, so a stream failure in between can never be overwritten by a stale Connected.
            val mayPreview = permissions?.any { it.jsonPrimitive.content == "preview:operate" } == true
            val browserCapable = config.obj("environment").obj("capabilities")["serverBrowser"]?.jsonPrimitive?.booleanOrNull == true
            val threadStop = (config.obj("environment").obj("capabilities")["threadStop"] as? JsonPrimitive)?.booleanOrNull == true
            // The capability only says the server honors `enablePreviewTunnel` (absent where the tunnel cannot be switched off);
            // whether the tunnel is off comes from the config's effective value.
            val tunnel = PreviewTunnelConfig().withConfig(config)
            val models = catalogModels(config.objects("providers"))
            val defaultMode = RuntimeMode.fromWire(config.obj("settings").string("defaultRuntimeMode"))
            val (defaultModel, projectDefaults) = newThreadDefaults(config["settings"] as? JsonObject)
            val preferences = modelPreferences(config["settings"] as? JsonObject, ModelPreferences())
            val origin = shellOrigin(environment.id, config)
            val providers = providerInstances(config)
            val endpoints = directEndpointHints(config)
            val suggestions = promptSuggestionsSetting(config["settings"] as? JsonObject)
            beforeConnectPublished?.invoke()
            synchronized(lifecycleLock) {
                check(epoch == generation) { "Connection was cancelled" }
                promptSuggestionsEnabled = suggestions
                authorized = mayOperate; previewAuthorized = mayPreview; settingsAuthorized = mayWriteSettings; serverPreferences = preferences; serverBrowser = browserCapable
                directEndpoints = endpoints; tunnelConfig = tunnel; liveShellOrigin = origin; shellResume.originKnown(origin)
                configProviders = config.objects("providers")
                // An early shell synchronized before its origin was known has a checkpoint without its cursor.
                if (shellSynced) checkpointShell(marker = false)
                update { it.copy(connection = ConnectionStatus.Connected, threadActionsConfigured = true, activeRoute = route, canReadUsage = mayReadUsage, models = models, providers = providers, defaultRuntimeMode = defaultMode, defaultModelSelection = defaultModel, projectDefaults = projectDefaults, modelPreferences = preferences, threadStopSupported = threadStop, previewTunnelSetting = tunnel.capability, previewTunnelDisabled = tunnel.disabled,
                    selectedModel = it.selectedModel?.let { selected -> models.firstOrNull { m -> m.instanceId == selected.instanceId && m.model == selected.model } ?: selected }) }
                // Route hints (a second config snapshot and the link-state read) would compete with the snapshot on a slow
                // link; they wait for the shell's synchronized marker.
                pendingRouteHints = { startRouteHints(epoch, environment, base) }
                syncPromptSuggestion()
                // Without a live config stream (rejected, or ended), nothing keeps the setting and policy current.
                if (configSubscription == null) configStreamLost()
                // Stream events that followed the first snapshot apply on top of it, in order.
                configPublished = true
                earlyConfigEvents.toList().also { earlyConfigEvents.clear() }.forEach { serverConfigEvent(epoch, environment.id, it) }
            }
            if (!early) subscribeShell(epoch, environment.id, origin)
            // An early shell may already be synchronized; writes still needed Connected, so re-evaluate them now.
            synchronized(lifecycleLock) { if (epoch == generation) { writable(); shellLive() } }
            startRenewal(epoch, base)
            synchronized(lifecycleLock) {
                if (epoch != generation) return@synchronized
                // An early shell is already synchronized: a selected thread it no longer lists is dropped, never subscribed.
                state.value.selectedThreadId?.let { id -> if (shellReady && state.value.threads.none { it.id == id }) clearThreadSelection() else selectThreadNow(id) }
            }
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException && currentCoroutineContext().isActive) {
                val message = "T3 connection timed out. Check the server and network, then try again."
                fail(epoch, message)
                throw PairingException(message, if (sessionChecked) ConnectFailure.AfterSession else ConnectFailure.Other)
            }
            // Superseded, paused or cancelled by the user: close, but never silently.
            synchronized(lifecycleLock) { if (epoch == generation) disconnectNow("Connection attempt was cancelled. Reconnect to continue.") }
            throw error
        }
        catch (thrown: Exception) {
            val kind = when {
                thrown is RouteSelectionFailed -> thrown.kind
                sessionChecked -> ConnectFailure.AfterSession
                else -> ConnectFailure.Other
            }
            val error = (thrown as? RouteSelectionFailed)?.error ?: thrown
            val message = when (error) {
                is PairingException -> error.message ?: "Connection failed"
                is HttpFailure, is DpopKeyUnavailable, is TokenRenewalRejected, is TokenRenewalUnavailable -> error.message ?: "Connection failed"
                is RequestTimedOut, is java.io.InterruptedIOException -> "T3 connection timed out. Check the server and network, then try again."
                else -> "Connection failed. Check the network and server, or pair again."
            }
            fail(epoch, message)
            throw PairingException(message, kind)
        }
    }

    /**
     * The connection's first server config: the first `subscribeServerConfig` snapshot (T3 mobile's `initialConfig`),
     * so the full config never travels twice. The stream stays open for later updates (see [serverConfigEvent]); it
     * is optional, so its later end never fails the session. A server that rejects or ends it before the snapshot
     * answers `server.getConfig` instead, and the stream then counts as lost.
     */
    private suspend fun firstServerConfig(epoch: Long, environmentId: String): JsonObject {
        val first = CompletableDeferred<JsonObject>()
        synchronized(lifecycleLock) {
            check(epoch == generation) { "Connection was cancelled" }
            firstConfig = first; configPublished = false; earlyConfigEvents.clear()
            configSubscription = subscribe("subscribeServerConfig", SERVER_CONFIG_SUBSCRIPTION_INPUT) { event -> serverConfigEvent(epoch, environmentId, event) }
        }
        val snapshot = try { withTimeoutOrNull(requestTimeoutMillis) { first.await() } ?: throw RequestTimedOut() } catch (_: ConfigStreamUnavailable) { null }
        // getConfig resolves discovery fully; the stream's first snapshot may not have.
        synchronized(lifecycleLock) { if (epoch == generation) endpointsProvisional = snapshot != null }
        return snapshot ?: rpc("server.getConfig", buildJsonObject {}, epoch).jsonObject
    }

    /**
     * Route hints of the live connection: one [RouteHints] with the first config's `directEndpoints` and the link-state
     * tunnel, then one per `subscribeServerConfig` snapshot whose `directEndpoints` change. The link-state read dies
     * with the connection. Started at the connection's first shell `synchronized` marker (see [shellLive]), never before.
     */
    private fun startRouteHints(epoch: Long, environment: SavedEnvironment, base: HttpUrl) = synchronized(lifecycleLock) {
        if (epoch != generation) return@synchronized
        linkStateJob = scope.launch {
            val tunnel = linkState(base, environment)
            synchronized(lifecycleLock) {
                if (epoch == generation) { initialHintSent = true; hints.tryEmit(RouteHints(environment.id, directEndpoints, tunnel, additive = endpointsProvisional)) }
            }
            // No later snapshot changed the first snapshot's list: T3 found nothing more, so it may now remove routes.
            if (synchronized(lifecycleLock) { !endpointsProvisional || directEndpoints == null }) return@launch
            delay(directEndpointsSettleMillis)
            synchronized(lifecycleLock) {
                if (epoch == generation && endpointsProvisional) {
                    endpointsProvisional = false
                    directEndpoints?.let { hints.tryEmit(RouteHints(environment.id, it, TunnelHint.Unknown)) }
                }
            }
        }
    }

    /**
     * The first `snapshot` is the connection's first config ([firstServerConfig]); events after it wait until that config
     * is published. Later, a `snapshot` feeds the route hints, the preview-tunnel config, the model preferences and the
     * providers; a `settingsUpdated` event the tunnel config and preferences (it carries no policy, so the last snapshot's
     * policy stands); `providerStatuses` and `providerStatusDeltas` the providers. Every other event is ignored. A
     * snapshot without `directEndpoints` says nothing and keeps the last hint.
     */
    private fun serverConfigEvent(epoch: Long, environmentId: String, event: JsonObject) {
        if (epoch != generation) return
        firstConfig?.takeUnless { it.isCompleted }?.let { first ->
            // The server sends its snapshot first; nothing before it can apply yet.
            if (event.string("type") == "snapshot") (event["config"] as? JsonObject)?.let(first::complete) ?: first.completeExceptionally(IllegalStateException("Invalid server config"))
            return
        }
        if (!configPublished) { earlyConfigEvents += event; return }
        streamProviders(event, configProviders)?.let(::applyConfigProviders)
        when (event.string("type")) {
            "settingsUpdated" -> ((event["payload"] as? JsonObject)?.get("settings") as? JsonObject)?.let { applyTunnelConfig(tunnelConfig.withSettings(it)); applyModelPreferences(it); applyPromptSuggestionsSetting(promptSuggestionsSetting(it)) }
            "snapshot" -> (event["config"] as? JsonObject)?.let { applyTunnelConfig(tunnelConfig.withConfig(it)); applyModelPreferences(it["settings"] as? JsonObject); applyPromptSuggestionsSetting(promptSuggestionsSetting(it["settings"] as? JsonObject)) }
        }
        if (event.string("type") != "snapshot") return
        val endpoints = directEndpointHints(event.obj("config")) ?: return
        // A later snapshot carries T3's fully resolved list (it sends one only when that list differs).
        if (endpoints == directEndpoints) return
        directEndpoints = endpoints; endpointsProvisional = false
        if (initialHintSent) hints.tryEmit(RouteHints(environmentId, endpoints, TunnelHint.Unknown))
    }

    /** Publishes the catalog and provider instances of the config stream's [providers]. Caller holds [lifecycleLock]. */
    private fun applyConfigProviders(providers: List<JsonObject>) {
        if (providers == configProviders) return
        configProviders = providers
        val models = catalogModels(providers)
        val instances = providerInstances(providers)
        update { it.copy(models = models, providers = instances,
            selectedModel = it.selectedModel?.let { selected -> models.firstOrNull { m -> m.instanceId == selected.instanceId && m.model == selected.model } ?: selected }) }
    }

    /**
     * The config stream is not live (it never started, ended or failed), so nothing keeps the connect-time setting and
     * policy current: they stop counting, and the `TunnelDisabled` handshake decides. Prompt suggestions turn off, so a
     * setting switched off meanwhile never keeps them running. Caller holds [lifecycleLock].
     */
    private fun configStreamLost() {
        applyTunnelConfig(tunnelConfig.copy(settingEnabled = null, policyAllows = null))
        applyPromptSuggestionsSetting(false)
    }

    /** Adopts the model preferences of server [settings] (a missing key keeps the last value). Caller holds [lifecycleLock]. */
    private fun applyModelPreferences(settings: JsonObject?) {
        serverPreferences = modelPreferences(settings, serverPreferences)
        publishModelPreferences()
    }
    /** Publishes [serverPreferences] with the [pendingFavorites] on top. Caller holds [lifecycleLock]. */
    private fun publishModelPreferences() {
        val favorites = pendingFavorites.fold(serverPreferences.favorites) { list, change ->
            if (!change.favorite) list - change.key else if (change.key in list) list else list + change.key
        }
        val shown = serverPreferences.copy(favorites = favorites)
        if (state.value.modelPreferences != shown) update { it.copy(modelPreferences = shown) }
    }

    /** Publishes the preview-tunnel config. Caller holds [lifecycleLock]. */
    private fun applyTunnelConfig(config: PreviewTunnelConfig) {
        tunnelConfig = config
        if (state.value.previewTunnelDisabled != config.disabled) update { it.copy(previewTunnelDisabled = config.disabled) }
    }

    /** Adopts a `promptSuggestionsEnabled` value from a config snapshot or settings update. Caller holds [lifecycleLock]. */
    private fun applyPromptSuggestionsSetting(enabled: Boolean) {
        if (promptSuggestionsEnabled == enabled) return
        promptSuggestionsEnabled = enabled
        syncPromptSuggestion()
    }

    /**
     * Keeps `orchestration.subscribePromptSuggestion` running for the selected thread exactly while the setting is on, the
     * connection is up and that thread's own subscription is live. The stream is optional: its end or rejection clears the
     * suggestion and is never retried for the same thread (a thread switch, setting change or reconnect starts afresh).
     * Caller holds [lifecycleLock].
     */
    private fun syncPromptSuggestion() {
        val threadId = state.value.selectedThreadId
        val wanted = promptSuggestionsEnabled && socket != null && state.value.connection == ConnectionStatus.Connected &&
            threadId != null && threadSubscription != null
        if (wanted && suggestionThread == threadId) return
        stopPromptSuggestion()
        if (!wanted) return
        val epoch = generation
        val token = suggestionGeneration
        suggestionThread = threadId
        suggestionSubscription = runCatching {
            subscribe("orchestration.subscribePromptSuggestion", buildJsonObject { put("threadId", threadId) }) { item ->
                if (epoch == generation && token == suggestionGeneration && state.value.selectedThreadId == threadId) {
                    val value = promptSuggestion(item)
                    if (state.value.promptSuggestion != value) update { it.copy(promptSuggestion = value) }
                }
            }
        }.getOrNull()
    }

    /** Interrupts the suggestion stream and clears its value. Caller holds [lifecycleLock]. */
    private fun stopPromptSuggestion() {
        suggestionGeneration++
        suggestionSubscription?.let { streams.remove(it); runCatching { send(EffectRpc.signal("Interrupt", it)) } }
        suggestionSubscription = null; suggestionThread = null
        if (state.value.promptSuggestion != null) update { it.copy(promptSuggestion = null) }
    }

    /** `GET /api/connect/link-state` on the verified route; any failure is [TunnelHint.Unknown] and never fails the connection. */
    private suspend fun linkState(base: HttpUrl, environment: SavedEnvironment): TunnelHint = try {
        val state = withTimeoutOrNull(LINK_STATE_TIMEOUT_MS) {
            authorizedFetch(environment, "GET", base.resolve("/api/connect/link-state")!!, LINK_STATE_TIMEOUT_MS)
        }
        if (state == null) TunnelHint.Unknown else tunnelHint(state)
    } catch (error: CancellationException) { throw error } catch (_: Exception) { TunnelHint.Unknown }

    override suspend fun preflight(environmentId: String, route: EnvironmentRoute, allowInsecureHttp: Boolean, timeoutMillis: Long): Boolean {
        if (route.kind == RouteKind.Ssh || timeoutMillis <= 0) return false
        val base = try { url(route.baseUrl, allowInsecureHttp) } catch (_: PairingException) { return false }
        return try { withTimeoutOrNull(timeoutMillis) { descriptor(base, timeoutMillis).string("environmentId") == environmentId } == true }
        catch (error: CancellationException) { throw error } catch (_: Exception) { false }
    }

    /**
     * Counts every mutation from its first local check until its outcome: message send, interrupt, thread stop, queue changes, request response,
     * runtime mode and model changes, model favourites, thread launch, subagent stop, thread actions and settlement undo. Reads
     * (configuration, projections, usage, screens, downloads) do not count; a switch only re-reads them.
     */
    override fun isIdleForRouteSwitch(): Boolean = mutationsInFlight.get() == 0

    /** Any pong after this ping proves the socket; silence, a failed send or a drop meanwhile fails this connection only. */
    override suspend fun verifyLink(timeoutMillis: Long): Boolean {
        val lost = "Connection stopped answering; any pending command outcome is uncertain. Reconnect to refresh."
        val (epoch, before) = synchronized(lifecycleLock) {
            if (socket == null || state.value.connection != ConnectionStatus.Connected) return false
            val epoch = generation; val before = pongs.value
            if (socket?.send(EffectRpc.signal("Ping").toString()) != true) { fail(epoch, lost); return false }
            epoch to before
        }
        val answered = withTimeoutOrNull(timeoutMillis) {
            merge(pongs.filter { it > before }.map { true }, state.filter { it.connection != ConnectionStatus.Connected }.map { false }).first()
        }
        if (answered == true && epoch == generation) return true
        fail(epoch, lost)
        return false
    }

    private suspend fun <T> mutating(block: suspend () -> T): T {
        mutationsInFlight.incrementAndGet()
        try { return block() } finally { mutationsInFlight.decrementAndGet() }
    }
    private fun send(frame: JsonObject) { check(socket?.send(frame.toString()) == true) { "Connection is unavailable" } }
    private suspend fun rpc(tag: String, payload: JsonObject, epoch: Long = generation, guard: () -> Boolean = { true }, timeoutMillis: Long = requestTimeoutMillis): JsonElement {
        check(pending.size < 64) { "Too many outstanding requests" }
        val id = serial.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>(); pending[id] = result
        val context = currentCoroutineContext()
        var sent = false
        try { synchronized(lifecycleLock) {
            context.ensureActive()
            if (epoch != generation || !guard()) throw CommandNotSent()
            send(EffectRpc.request(id, tag, payload)); sent = true
        }; return withTimeoutOrNull(timeoutMillis) { result.await() } ?: throw RequestTimedOut() }
        finally { if (pending.remove(id) != null && sent) runCatching { send(EffectRpc.signal("Interrupt", id)) } }
    }
    private fun subscribe(tag: String, payload: JsonObject, handler: (JsonObject) -> Unit): String {
        val id = serial.incrementAndGet().toString(); streams[id] = handler
        try { send(EffectRpc.request(id, tag, payload)) } catch (error: Exception) { streams.remove(id); throw error }
        return id
    }
    private fun receive(frame: JsonObject) {
        val id = frame.string("requestId")
        when (frame.string("_tag")) {
            "Pong" -> { pongAt = System.currentTimeMillis(); pongs.update { it + 1 } }
            "Chunk" -> {
                // The suggestion stream's items may be `null` (no suggestion); it gets them as an empty object.
                if (id == suggestionSubscription) (frame["values"] as? JsonArray)?.forEach { streams[id]?.invoke(it as? JsonObject ?: JsonObject(emptyMap())) }
                else frame.objects("values").forEach { streams[id]?.invoke(it) }
                send(EffectRpc.signal("Ack", id))
            }
            "Exit" -> {
                val exit = frame.obj("exit")
                val result = pending.remove(id)
                if (exit.string("_tag") == "Success") result?.complete(exit["value"] ?: JsonNull)
                else result?.completeExceptionally(if (exitInterruptedOnly(exit)) RequestInterrupted() else ServerRejected())
                if (streams.remove(id) != null) {
                    // The config stream is optional: rejected before its snapshot, the connect asks `server.getConfig`; its
                    // later end leaves the session alone.
                    // Before publication, the connect sees the null subscription and reports the loss itself.
                    if (id == configSubscription) { configSubscription = null; firstConfig?.takeUnless { it.isCompleted }?.completeExceptionally(ConfigStreamUnavailable()); if (configPublished) configStreamLost() }
                    // Prompt suggestions are optional too: an end or rejection clears the value, without a retry.
                    else if (id == suggestionSubscription) { suggestionSubscription = null; suggestionGeneration++; if (state.value.promptSuggestion != null) update { it.copy(promptSuggestion = null) } }
                    else if (id == discoverySubscription) discoveryEnded(exit)
                    else if (optionalScreenStreams.remove(id)) {
                        if (id == previewScreenSubscription) { previewScreenSubscription = null; browserScreensFresh = false }
                        if (id == deviceScreenSubscription) { deviceScreenSubscription = null; deviceScreensFresh = false }
                        update { it.copy(screens = it.screens.copy(error = "Remote screen updates ended. Refresh screens to try again.")) }
                    } else fail(generation, "Subscription ended; reconnect to refresh")
                }
            }
            "Defect", "ClientProtocolError" -> fail(generation, "Server protocol failure; reconnect to refresh")
        }
    }
    /**
     * A frame too large to parse. The open thread's snapshot or refresh only closes that thread, and any other reply fails
     * only its own request; a frame of another stream, or one that names no request, fails the session as before.
     * Caller holds [lifecycleLock].
     */
    private fun oversizedFrame(epoch: Long, error: FrameTooLarge) {
        if (epoch != generation) return
        val id = error.requestId
        when {
            id != null && id == threadSubscription -> threadTooLarge()
            id != null && pending.containsKey(id) && !streams.containsKey(id) -> pending[id]?.completeExceptionally(error)
            else -> fail(epoch, "The server sent more data than this app can load; reconnect to retry")
        }
    }
    /** Drops the selected thread whose data exceeds [EffectRpc.MAX_FRAME_CHARS]; the connection stays up. Caller holds [lifecycleLock]. */
    private fun threadTooLarge() {
        clearThreadSelection()
        update { it.copy(error = THREAD_TOO_LARGE) }
    }
    private fun fail(epoch: Long, message: String) = synchronized(lifecycleLock) {
        if (epoch != generation) return@synchronized
        fileDownloads.forEach { it.cancel() }; fileDownloads.clear()
        generation++
        refreshSignals?.close(); refreshJob?.cancel(); visits.reset(null); visitTimer?.cancel(); visitTimer = null
        shellReady = false; shellSynced = false; deferredThread = null; threadReady = false; authorized = false; previewAuthorized = false; settingsAuthorized = false
        endShellStream(message)
        resetRoute()
        resetScreens()
        stopPortForwards()
        undoPlan = null; pendingModelSelections = null; serverPreferences = ModelPreferences(); pendingFavorites.clear()
        update { it.copy(connection =ConnectionStatus.Error, canWrite = false, canWriteThreads = false, canWriteSettings = false, modelPreferences = ModelPreferences(), canRead = false, shellLive = false, canReadUsage = false, queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false, busy = false, error = message, threadActionsConfigured = false, settlementUndo = null, activeRoute = null) }
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }; pending.clear()
        streams.clear(); heartbeat?.cancel(); socket?.cancel(); socket = null
    }
    private fun resetRoute() { endDiscovery(); tunnelConfig = PreviewTunnelConfig(); activeBase = null; liveShellOrigin = null; pendingRouteHints = null; directEndpoints = null; initialHintSent = false; endpointsProvisional = false; configSubscription = null
        firstConfig?.completeExceptionally(IllegalStateException("Connection was cancelled")); firstConfig = null; configPublished = false; earlyConfigEvents.clear(); configProviders = emptyList(); linkStateJob?.cancel(); linkStateJob = null; renewJob?.cancel(); renewJob = null
        // The socket is gone with its streams; the next connection reads the setting again.
        promptSuggestionsEnabled = false; stopPromptSuggestion() }
    override suspend fun disconnect() = disconnectNow()
    /** [reason] replaces any stale error when the disconnect was not requested by the user. */
    private fun disconnectNow(reason: String? = null) = synchronized(lifecycleLock) {
        cacheState()
        fileDownloads.forEach { it.cancel() }; fileDownloads.clear()
        generation++; threadGeneration++; refreshSignals?.close(); refreshJob?.cancel(); heartbeat?.cancel(); heartbeat = null
        visits.reset(null); visitTimer?.cancel(); visitTimer = null
        socket?.close(1000, null); socket = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Disconnected; pending command outcome may be uncertain")) }
        pending.clear(); streams.clear(); threadSubscription = null; shellReady = false; shellSynced = false; deferredThread = null; threadReady = false; authorized = false; previewAuthorized = false; settingsAuthorized = false
        endShellStream("Disconnected before the thread list refreshed")
        resetRoute()
        resetScreens()
        stopPortForwards()
        undoPlan = null; pendingModelSelections = null; serverPreferences = ModelPreferences(); pendingFavorites.clear()
        update { it.copy(connection =ConnectionStatus.Disconnected, canWrite = false, canWriteThreads = false, canWriteSettings = false, modelPreferences = ModelPreferences(), canRead = false, shellLive = false, canReadUsage = false, queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false, busy = false, error = reason ?: it.error, threadActionsConfigured = false, settlementUndo = null, activeRoute = null) }
    }

    private fun usageEnvironment(): SavedEnvironment {
        if (socket == null || state.value.connection != ConnectionStatus.Connected) throw UsageException(UsageFailure.NotConnected, "Connect to view usage.")
        return state.value.environment ?: throw UsageException(UsageFailure.NotConnected, "Connect to view usage.")
    }

    private suspend fun <T> usageRead(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: UsageException) { throw error }
    catch (_: RequestTimedOut) { throw UsageException(UsageFailure.TimedOut, "Usage request timed out. Try again.") }
    catch (_: Exception) { throw UsageException(UsageFailure.Failed, "This T3 server could not report usage.") }

    override suspend fun usageReport(range: UsageRange, timeZone: String, awaitRefresh: Boolean): UsageReport = usageRead {
        val captured = synchronized(lifecycleLock) {
            val environment = usageEnvironment()
            if (!state.value.canReadUsage) throw UsageException(UsageFailure.NoPermission, "This connection cannot read usage.")
            generation to environment.id
        }
        val today = Instant.ofEpochMilli(usageClockMillis()).atZone(java.time.ZoneId.of(timeZone)).toLocalDate()
        val (since, until) = usageWindow(range, today)
        val key = UsageKey(captured.second, range, timeZone, until)
        synchronized(lifecycleLock) {
            check(captured.first == generation)
            if (!awaitRefresh) usageCache[key]?.takeIf { usageClockMillis() - it.at in 0 until 60_000 }?.let { return@usageRead it.report }
        }
        val reply = rpc("server.getUsageSummary", buildJsonObject {
            put("sinceDay", since); put("untilDay", until); put("timeZone", timeZone)
            if (awaitRefresh) put("awaitRefresh", true) else put("provisional", true)
        }, captured.first, timeoutMillis = if (awaitRefresh) 120_000 else requestTimeoutMillis).jsonObject
        val report = usageSummary(reply, captured.second, range)
        synchronized(lifecycleLock) {
            check(captured.first == generation && captured.second == state.value.environment?.id)
            // A slower provisional request must not replace a fresh result that arrived first.
            if (!report.refreshing || usageCache[key]?.report?.refreshing != false) usageCache[key] = CachedUsage(report, usageClockMillis())
        }
        report
    }

    override suspend fun refreshUsageLimits(automatic: Boolean): Boolean {
        val work = synchronized(lifecycleLock) {
            val environment = usageEnvironment()
            val epoch = generation
            limitsInFlight?.takeIf { it.first == epoch }?.let { return@synchronized it.second }
            if (automatic && limitsFinished[environment.id]?.let { usageClockMillis() - it in 0 until 300_000 } == true) return false
            val result = CompletableDeferred<Boolean>()
            limitsInFlight = epoch to result
            scope.launch {
                val outcome = runCatching {
                    usageRead {
                        val reply = rpc("server.refreshProviders", buildJsonObject {}, epoch).jsonObject
                        val providers = reply["providers"] as? JsonArray ?: error("Missing providers")
                        require(providers.all { it is JsonObject && it.string("instanceId").isNotBlank() && it.string("driver").isNotBlank() })
                        val instances = providerInstances(reply)
                        synchronized(lifecycleLock) {
                            check(epoch == generation && environment.id == state.value.environment?.id)
                            update { it.copy(providers = instances) }
                        }
                        true
                    }
                }
                synchronized(lifecycleLock) {
                    limitsFinished[environment.id] = usageClockMillis()
                    if (limitsInFlight?.second === result) limitsInFlight = null
                }
                outcome.fold(result::complete, result::completeExceptionally)
            }
            result
        }
        return work.await()
    }
    override suspend fun selectThread(threadId: String) = selectThreadNow(threadId)
    override suspend fun selectThreadIn(environmentId: String, threadId: String): Boolean = synchronized(lifecycleLock) {
        // Checked under the lock that every environment switch takes, so the environment cannot change before the selection.
        val current = state.value
        // Only against the live, synchronized shell: retained or cached rows may list a thread deleted meanwhile.
        if (current.environment?.id != environmentId || current.connection != ConnectionStatus.Connected || !current.shellLive ||
            current.threads.none { it.id == threadId }) return@synchronized false
        selectThreadNow(threadId)
        true
    }
    /**
     * Shows [threadId] and subscribes it once the current connection's shell is synchronized: a row shown from the cache
     * (or retained) may name a thread deleted meanwhile, whose subscription the server would reject, failing the session.
     * Before the marker the selection is remembered with any cached projection, and [shellItem] subscribes it at the
     * marker only while the shell still lists it; otherwise the selection is cleared.
     */
    private fun selectThreadNow(threadId: String): Unit = synchronized(lifecycleLock) {
        if (state.value.selectedThreadId != threadId) { fileDownloads.forEach { it.cancel() }; fileDownloads.clear(); resetScreens(interrupt = true) }
        if (state.value.connection != ConnectionStatus.Connected) {
            val cached = state.value.environment?.id?.let { threadProjections[it to threadId] }
            threadReady = false
            if (cached != null) { applyCachedThread(cached, threadId); return@synchronized }
            // While the connection comes up, the connect subscribes the selection (see above); offline, there is nothing to show.
            check(state.value.connection == ConnectionStatus.Connecting || state.value.connection == ConnectionStatus.Reconnecting) { "Connect to load this thread" }
            if (state.value.selectedThreadId != threadId) { projection = JsonObject(emptyMap()); showEmptySelection(threadId) }
            return@synchronized
        }
        if (state.value.selectedThreadId == threadId && (threadSubscription != null || deferredThread == threadId)) return@synchronized
        if (state.value.error == THREAD_TOO_LARGE) update { it.copy(error = null) }
        threadSubscription?.let { streams.remove(it); send(EffectRpc.signal("Interrupt", it)) }
        threadSubscription = null
        syncPromptSuggestion()
        threadReady = false
        val selection = ++threadGeneration
        visits.reset(threadId); visitTimer?.cancel()
        projection = JsonObject(emptyMap())
        val cached = state.value.environment?.id?.let { threadProjections[it to threadId] }
        if (cached != null) applyCachedThread(cached, threadId)
        else if (state.value.selectedThreadId == threadId) update { it.copy(canWrite = false, canRead = false) }
        else showEmptySelection(threadId)
        val epoch = generation
        refreshSignals?.close(); refreshJob?.cancel()
        if (!shellReady) { deferredThread = threadId; return@synchronized }
        deferredThread = null
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
                        if (epoch == generation && selection == threadGeneration && state.value.selectedThreadId == threadId) {
                            applyProjection(value); appliedRevision = startedRevision; threadReady = synchronizedSeen && appliedRevision == revision; writable()
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: FrameTooLarge) { synchronized(lifecycleLock) { if (epoch == generation && selection == threadGeneration) threadTooLarge() }; break }
                catch (_: Exception) { synchronized(lifecycleLock) { if (epoch == generation && selection == threadGeneration) fail(epoch, "Thread refresh failed; reconnect before writing") }; break }
            }
        }
        threadSubscription = subscribe("orchestration.subscribeThread", buildJsonObject { put("threadId", threadId); put("requestCompletionMarker", true) }) { item ->
            if (generation == epoch && selection == threadGeneration && state.value.selectedThreadId == threadId) {
                when (item.string("kind")) {
                    "snapshot" -> { applyProjection(item.obj("projection")); appliedRevision = revision }
                    "synchronized" -> { synchronizedSeen = true; threadReady = appliedRevision == revision; writable() }
                    "event" -> { revision++; projectionRevision++; threadReady = false; writable(); signals.trySend(Unit) }
                }
            }
        }
        syncPromptSuggestion()
    }
    private fun showEmptySelection(threadId: String?) = update { it.copy(selectedThreadId = threadId, messages = emptyList(), requests = emptyList(), activities = emptyList(), runs = emptyList(), attempts = emptyList(), subagents = emptyList(), backgroundWork = emptyList(), queuedMessages = emptyList(), queueHeld = false, canSteer = false, canStop = false, activeRunId = null, canWrite = false, selectedRuntimeMode = null, selectedModel = null, selectedModelOptions = emptyMap(), selectedProviderLocked = false) }
    /** Drops the selection of a thread the synchronized shell no longer lists, without touching the connection. Caller holds [lifecycleLock]. */
    private fun clearThreadSelection() {
        threadSubscription?.let { streams.remove(it); runCatching { send(EffectRpc.signal("Interrupt", it)) } }
        threadSubscription = null; deferredThread = null; threadReady = false; threadGeneration++
        stopPromptSuggestion()
        refreshSignals?.close(); refreshJob?.cancel(); visits.reset(null); visitTimer?.cancel(); visitTimer = null
        projection = JsonObject(emptyMap())
        resetScreens(interrupt = true)
        showEmptySelection(null)
        writable()
    }
    override fun setThreadViewing(viewing: Boolean) = synchronized(lifecycleLock) { threadViewing = viewing; noteThreadVisit() }
    /** Records a visit (T3 `thread.visit`) once, fire-and-forget: a failure is dropped, never retried or surfaced. */
    private fun noteThreadVisit() {
        val current = state.value; val threadId = current.selectedThreadId
        val eligible = threadViewing && current.connection == ConnectionStatus.Connected && current.canWrite
        when (val action = visits.evaluate(threadId, eligible, projectionRevision, System.nanoTime() / 1_000_000)) {
            ThreadVisitTracker.Action.None -> Unit
            is ThreadVisitTracker.Action.Wait -> if (visitTimer?.isActive != true) {
                val epoch = generation
                visitTimer = scope.launch { delay(action.millis); synchronized(lifecycleLock) { visitTimer = null; if (epoch == generation) noteThreadVisit() } }
            }
            is ThreadVisitTracker.Action.Send -> {
                val epoch = generation
                val payload = ThreadVisitTracker.command(threadId!!, projection, action.opened) ?: return
                scope.launch { try { rpc("orchestration.dispatchCommand", payload, epoch) } catch (error: CancellationException) { throw error } catch (_: Exception) {} }
            }
        }
    }
    /**
     * A user refresh must correct stale rows, so it never resumes from the cursor: the cursor is dropped and a full
     * snapshot requested; that snapshot then seeds a fresh cursor. Caller holds [lifecycleLock].
     */
    private fun subscribeFullShell() {
        shellResume.invalidate()
        shellSubscription = subscribeShellStream(null)
        shellReplaying = false
    }
    /**
     * A silent catch-up resumes from the cursor when it still describes the rows (same origin, same lists, recent);
     * otherwise [ShellResume.begin] drops it and this is a full snapshot. Caller holds [lifecycleLock].
     */
    private fun subscribeResumedShell() {
        val environmentId = state.value.environment?.id ?: error("Connect before refreshing threads")
        val afterSequence = shellResume.begin(environmentId, liveShellOrigin, ShellRows(state.value.projects, state.value.threads), environmentStates.keys.toSet())
        shellSubscription = subscribeShellStream(afterSequence)
        shellReplaying = afterSequence != null
    }
    private fun endShellStream(message: String) {
        shellSubscription = null
        shellRefresh?.completeExceptionally(IllegalStateException(message)); shellRefresh = null; shellReplaying = false; shellFullWanted = false
    }
    override suspend fun refreshThreads() = refreshShell(resume = false)
    override suspend fun resyncThreads() = refreshShell(resume = true)
    /**
     * [refreshThreads] (full snapshot, cursor dropped) and [resyncThreads] (resume from the cursor) share one pending
     * refresh: a second call joins the unsynchronized stream already in flight (a refresh, or the connection's first
     * one) rather than stacking a stream, except that a full refresh upgrades a stream that is replaying deltas from a
     * cursor to a full snapshot on the same completion. Whether a stream replays is what it actually sent, not what
     * was asked for: a resync without a usable cursor is already a full snapshot.
     */
    private suspend fun refreshShell(resume: Boolean) {
        val (epoch, done) = synchronized(lifecycleLock) {
            check(state.value.connection == ConnectionStatus.Connected && socket != null) { "Connect before refreshing threads" }
            val pending = shellRefresh
            val done = pending ?: CompletableDeferred()
            // The first shell snapshot of this connection is still on its way (or not yet asked for).
            val unsynchronized = pending != null || !shellReady || shellSubscription == null
            if (unsynchronized && (resume || !shellReplaying)) {
                if (!resume && shellSubscription == null) shellFullWanted = true
                shellRefresh = done
                return@synchronized generation to done
            }
            shellRefresh = done
            // Stop the old stream first: its late frames are dropped, and the new answer is what the rows follow.
            shellSubscription?.let { old -> streams.remove(old); runCatching { send(EffectRpc.signal("Interrupt", old)) } }
            try { if (resume) subscribeResumedShell() else subscribeFullShell() } catch (error: Exception) {
                shellRefresh = null; shellReplaying = false
                fail(generation, "Thread list refresh failed; reconnect to refresh")
                throw error
            }
            generation to done
        }
        val synced = withTimeoutOrNull(requestTimeoutMillis) { done.await() }
        if (synced == null) {
            // A slow snapshot on a healthy link keeps streaming into the list; only report this refresh. The pending
            // refresh stays, so a retry joins it instead of restarting the snapshot. A dead link is dropped so reconnect
            // handling runs, rather than leaving a list that silently stops updating.
            if (linkStale()) fail(epoch, "Thread list refresh timed out; reconnect to refresh")
            throw RequestTimedOut()
        }
    }
    private fun linkStale(): Boolean = System.currentTimeMillis() - maxOf(pongAt, frameAt) > SHELL_REFRESH_STALE_LINK_MS
    private fun writable() { updateWritable(); noteThreadVisit() }
    private fun updateWritable() = update {
        val fresh = shellReady && (it.selectedThreadId == null || threadReady) && it.connection == ConnectionStatus.Connected
        it.copy(canRead = fresh, canWrite = authorized && fresh,
            canWriteThreads = authorized && shellReady && it.connection == ConnectionStatus.Connected,
            canWriteSettings = settingsAuthorized && shellReady && it.connection == ConnectionStatus.Connected,
            shellLive = shellReady && it.connection == ConnectionStatus.Connected)
    }
    /** Memory-only shell cursors; see [ShellResume]. */
    private val shellResume = ShellResume()
    /**
     * Retained rows of the same environment and server build resume from their cursor (`afterSequence`): the
     * server replays the missed deltas, or answers a snapshot when it cannot (and old servers ignore the field).
     * Writes stay disabled until the `synchronized` marker either way.
     */
    private fun subscribeShell(epoch: Long, environmentId: String, origin: ShellOrigin?) = synchronized(lifecycleLock) {
        check(epoch == generation && state.value.environment?.id == environmentId) { "Connection was cancelled" }
        val resumable = shellResume.begin(environmentId, origin, ShellRows(state.value.projects, state.value.threads), environmentStates.keys.toSet())
        // A pull made while the config was on its way wants the stale rows corrected, not caught up.
        val afterSequence = if (shellFullWanted) { shellResume.invalidate(); null } else resumable
        shellFullWanted = false
        shellSubscription = subscribeShellStream(afterSequence)
        shellReplaying = afterSequence != null
    }
    /**
     * The snapshot subscription of a connect with no resumable cursor, sent alongside `server.getConfig`; its cursor
     * gets the origin once the config answers ([ShellResume.originKnown]).
     */
    private fun subscribeShellBeforeOrigin(epoch: Long, environmentId: String) = synchronized(lifecycleLock) {
        check(epoch == generation && state.value.environment?.id == environmentId) { "Connection was cancelled" }
        shellResume.beginBeforeOrigin(environmentId, environmentStates.keys.toSet())
        shellSubscription = subscribeShellStream(null)
        shellReplaying = false; shellFullWanted = false
    }
    /** Starts what waits for both a configured connection and a synchronized shell. Caller holds [lifecycleLock]. */
    private fun shellLive() {
        if (!shellReady || state.value.connection != ConnectionStatus.Connected) return
        startDiscovery()
        pendingRouteHints?.let { pendingRouteHints = null; it() }
    }
    /** Every shell stream starts unsynchronized: its rows become a checkpoint only from its own marker on. */
    private fun subscribeShellStream(afterSequence: Long?): String {
        shellSynced = false
        return subscribe("orchestration.subscribeShell", shellSubscribeInput(afterSequence), ::shellItem)
    }
    /**
     * Records the shown rows and cursor as the environment's checkpoint ([shellCacheEntry]) and emits
     * [shellCheckpointed]; [marker] emits even when nothing changed. Caller holds [lifecycleLock] and the current shell
     * subscription is synchronized.
     */
    private fun checkpointShell(marker: Boolean) {
        val current = state.value
        val id = current.environment?.id ?: return
        if (!current.shellLoaded) return
        val cursor = shellResume.cursor(id)?.takeIf { it.rows.sameAs(ShellRows(current.projects, current.threads)) }
        val origin = cursor?.origin
        val stored = if (cursor != null && origin != null) CachedShellCursor(origin.serverVersion, origin.databaseMigration, cursor.sequence, cursor.snapshotAtMillis) else null
        val previous = shellCheckpoints[id]
        if (!marker && previous != null && previous.projects === current.projects && previous.threads === current.threads && previous.cursor == stored) return
        shellCheckpoints[id] = ShellCheckpoint(current.projects, current.threads, stored)
        checkpoints.tryEmit(id)
    }
    private fun shellItem(item: JsonObject) {
        if (item.string("kind") == "snapshot-required") {
            // Only sent for `httpSnapshotFallback`, which Trefoil never asks for: drop the cursor and load a socket snapshot.
            shellResume.invalidate()
            shellSubscription?.let { streams.remove(it); runCatching { send(EffectRpc.signal("Interrupt", it)) } }
            shellReady = false; shellReplaying = false; writable()
            shellSubscription = subscribeShellStream(null)
            return
        }
        when (item.string("kind")) {
            "snapshot" -> {
                val snapshot = item.obj("snapshot")
                val projects = snapshot.objects("projects").map(::project)
                if (item["resolvedRepositoryIdentityRoots"] is JsonArray) {
                    // T3 marks project-only enrichment as a snapshot with empty thread arrays.
                    // It supplements repository identity; it is never an authoritative thread reset.
                    update { current -> current.copy(projects = current.projects.filterNot { p -> projects.any { it.id == p.id } } + projects) }
                } else {
                    // An authoritative snapshot: from here on an empty list means "no threads", not "loading".
                    update { it.copy(projects = projects, threads = snapshot.objects("threads").map { threadSummary(it) } + snapshot.objects("archivedThreads").map { threadSummary(it, archived = true) }, shellLoaded = true) }
                }
            }
            "project.updated" -> { val value = project(item.obj("project")); update { it.copy(projects = it.projects.filterNot { p -> p.id == value.id } + value) } }
            "project.removed" -> update { it.copy(projects = it.projects.filterNot { p -> p.id == item.string("projectId") }) }
            "thread.updated" -> { val value = threadSummary(item.obj("thread"), archived = item.string("location") == "archive"); update { it.copy(threads = it.threads.filterNot { t -> t.id == value.id } + value) } }
            // Archive moves send `thread.removed` for the old location; it must not drop the row now in the other one.
            "thread.removed" -> {
                val archived = when (item.string("location")) { "archive" -> true; "active" -> false; else -> null }
                update { it.copy(threads = it.threads.filterNot { t -> t.id == item.string("threadId") && (archived == null || (t.archived || t.archivedAt != null) == archived) }) }
            }
            "synchronized" -> {
                shellReady = true; shellSynced = true; writable()
                shellRefresh?.complete(Unit); shellRefresh = null; shellReplaying = false
                shellLive()
                deferredThread?.let { id ->
                    deferredThread = null
                    if (state.value.selectedThreadId == id && state.value.connection == ConnectionStatus.Connected) {
                        if (state.value.threads.any { it.id == id }) selectThreadNow(id) else clearThreadSelection()
                    }
                }
            }
        }
        // After the rows changed: an item that failed to apply never moves the cursor.
        shellResume.applied(item, ShellRows(state.value.projects, state.value.threads))
        if (shellSynced) checkpointShell(marker = item.string("kind") == "synchronized")
    }
    private fun project(value: JsonObject) = Project(value.string("id"), value.string("title"), value.string("workspaceRoot"))
    private fun applyProjection(incoming: JsonObject, threadId: String? = state.value.selectedThreadId, authoritative: Boolean = true) {
        if (authoritative) projectionRevision++
        val value = if (authoritative) retainHydratedActivities(incoming, projection) else incoming
        showProjection(value, threadPresentation(value), threadId)
    }
    /**
     * Repaints a recently viewed thread from memory: no JSON is parsed, so selecting it on the main thread costs a state
     * copy. Its rows are stale until the thread's own stream answers; the caller has already cleared `threadReady`, so writes
     * stay disabled until then. Caller holds [lifecycleLock].
     */
    private fun applyCachedThread(cached: CachedThread, threadId: String) {
        projectionRevision++
        showProjection(cached.projection, cached.presentation, threadId)
    }
    /** Publishes [value] (already derived into [derived]) as the selected thread and remembers both. Caller holds [lifecycleLock]. */
    private fun showProjection(value: JsonObject, derived: ThreadPresentation, threadId: String?) {
        projection = value
        val current = state.value
        // Unchanged rows keep their instances, so the timeline skips them; on a switch nothing matches and [derived] is shown as is.
        val shown = if (current.selectedThreadId != threadId) derived else derived.copy(
            messages = stableRows(current.messages, derived.messages) { it.id },
            activities = stableRows(current.activities, derived.activities) { it.id },
            requests = stableValue(current.requests, derived.requests), runs = stableValue(current.runs, derived.runs),
            attempts = stableValue(current.attempts, derived.attempts), subagents = stableValue(current.subagents, derived.subagents),
            backgroundWork = stableValue(current.backgroundWork, derived.backgroundWork), queuedMessages = stableValue(current.queuedMessages, derived.queuedMessages),
        )
        val environmentId = current.environment?.id
        if (environmentId != null && threadId != null) threadProjections.put(environmentId to threadId, CachedThread(value, shown))
        // Steer and Stop are live facts: a cached projection shown while offline offers neither.
        val live = current.connection == ConnectionStatus.Connected
        update { it.copy(selectedThreadId = threadId, canWrite = it.canWrite && threadReady, canRead = it.canRead && threadReady, messages = shown.messages,
            activities = shown.activities, requests = shown.requests, activeRunId = shown.activeRunId,
            runs = shown.runs, attempts = shown.attempts, subagents = shown.subagents,
            backgroundWork = shown.backgroundWork,
            queuedMessages = shown.queuedMessages, queueHeld = shown.queueHeld,
            canSteer = live && shown.steerable, canStop = live && shown.stoppable,
            selectedRuntimeMode = shown.runtimeMode,
            selectedModel = selectedModel(shown.modelSelection, it.models),
            selectedModelOptions = shown.modelOptions,
            selectedProviderLocked = shown.providerLocked) }
        reconcilePendingModelSelections(threadId)
    }
    /** Derives the [ClientState] slice of one projection. Pure; parses each row once. */
    private fun threadPresentation(value: JsonObject): ThreadPresentation {
        val items = presentationItems(value)
        val promotion = steerPromotion(value)
        val requests = value.objects("runtimeRequests").filter { it.string("status") == "pending" }.map { request ->
            val item = items.firstOrNull { it.string("requestId") == request.string("id") } ?: JsonObject(emptyMap())
            val questions = item.objects("questions").map { q -> Question(q.string("id"), q.string("question"), q.objects("options").map { it.string("label") }, q["multiSelect"]?.jsonPrimitive?.booleanOrNull == true, q["required"]?.jsonPrimitive?.booleanOrNull != false) }
            val kind = request.string("kind")
            val offered = item.objects("options").map { it.string("decision") }
            val basicApproval = item.string("type") == "approval_request" && (offered.isEmpty() || ("accept" in offered && "decline" in offered))
            val supported = request.obj("responseCapability").string("type") == "live" && (basicApproval || item.string("type") == "user_input_request")
            RuntimeRequest(request.string("id"), item.string("prompt").ifBlank { kind }, if (supported) item.string("reason") else "Respond using another T3 client: unsupported or non-resumable request", questions, supported)
        }
        // Highest ordinal; the stable sort keeps wire order for runs without one.
        val active = value.objects("runs").filter { it.string("status") in ACTIVE_RUN_STATUSES }
            .sortedBy { (it["ordinal"] as? JsonPrimitive)?.intOrNull ?: Int.MIN_VALUE }.lastOrNull()?.string("id")
        val thread = value.obj("thread")
        val agents = subagents(value)
        val background = pendingBackgroundWork(value)
        return ThreadPresentation(
            messages = conversationMessages(value, items), activities = toolActivities(value, items), requests = requests, activeRunId = active,
            runs = conversationRuns(value), attempts = runAttempts(value), subagents = agents, backgroundWork = background,
            queuedMessages = queuedMessages(value, promotion), queueHeld = queueHeld(value),
            steerable = canSteer(value, promotion, items), stoppable = canStopThread(active, background, agents),
            runtimeMode = RuntimeMode.fromWire(thread.string("runtimeMode")),
            modelSelection = thread["modelSelection"] as? JsonObject,
            modelOptions = modelOptionValues(thread.obj("modelSelection")["options"]),
            providerLocked = providerLocked(value))
    }
    /** T3 `thread.modelSelection {instanceId, model}`; prefers the server catalog entry for its label. */
    private fun selectedModel(selection: JsonObject?, models: List<ModelOption>): ModelOption? {
        val instanceId = selection?.string("instanceId").orEmpty(); val model = selection?.string("model").orEmpty()
        if (instanceId.isBlank() || model.isBlank()) return null
        return models.firstOrNull { it.instanceId == instanceId && it.model == model }
            ?: ModelOption(instanceId, model, supportedRuntimeModes = models.firstOrNull { it.instanceId == instanceId }?.supportedRuntimeModes.orEmpty())
    }
    override suspend fun loadActivity(itemId: String) {
        val captured = synchronized(lifecycleLock) {
            check(state.value.connection == ConnectionStatus.Connected && threadReady) { "Wait for a fresh thread connection" }
            val threadId = state.value.selectedThreadId ?: error("Select a thread")
            val item = presentationItems(projection).firstOrNull { it.string("id") == itemId } ?: error("Activity is unavailable")
            check((item["outputOmitted"] as? JsonPrimitive)?.booleanOrNull == true || fileDetailsOmitted(item)) { "Activity details are already loaded" }
            ActivityRead(generation, threadGeneration, projectionRevision, threadId, item)
        }
        val sourceThreadId = captured.item.string("sourceThreadId").ifBlank { captured.threadId }
        val sourceItemId = captured.item.string("sourceItemId").ifBlank { captured.item.string("id") }
        val full = rpc("orchestration.getTurnItem", buildJsonObject {
            put("threadId", sourceThreadId); put("itemId", sourceItemId)
        }, captured.generation).jsonObject["item"] as? JsonObject ?: error("Activity details are unavailable")
        synchronized(lifecycleLock) {
            check(captured.generation == generation && captured.selection == threadGeneration &&
                captured.revision == projectionRevision && state.value.selectedThreadId == captured.threadId &&
                state.value.connection == ConnectionStatus.Connected && threadReady) { "Activity changed; load its details again" }
            check(full.string("id") == sourceItemId && full.string("type") == captured.item.string("type") &&
                (full["outputOmitted"] as? JsonPrimitive)?.booleanOrNull != true) { "Activity details are unavailable" }
            check(listOf("status", "startedAt", "completedAt", "updatedAt").all { full[it] == captured.item[it] }) {
                "Activity changed; refresh its details"
            }
            applyProjection(hydrateActivityProjection(projection, itemId, full), captured.threadId, authoritative = false)
        }
    }
    private data class ActivityRead(
        val generation: Long, val selection: Long, val revision: Long, val threadId: String, val item: JsonObject,
    )
    private fun resetScreens(interrupt: Boolean = false) {
        listOfNotNull(previewScreenSubscription, deviceScreenSubscription).forEach { id ->
            optionalScreenStreams.remove(id)
            streams.remove(id)
            if (interrupt) runCatching { send(EffectRpc.signal("Interrupt", id)) }
        }
        previewScreenSubscription = null; deviceScreenSubscription = null
        optionalScreenStreams.clear(); browserScreensFresh = false; deviceScreensFresh = false
        deviceScreenRevision = -1L; previewScreenValue = JsonObject(emptyMap()); previewScreenEvents.clear(); screenRead++
        update { it.copy(screens = ScreenCatalog()) }
    }
    private data class ScreenRead(val epoch: Long, val selection: Long, val read: Long, val threadId: String, val environment: SavedEnvironment)
    private fun screenReadCurrent(read: ScreenRead) = read.epoch == generation && read.selection == threadGeneration &&
        state.value.environment?.id == read.environment.id && state.value.selectedThreadId == read.threadId && state.value.connection == ConnectionStatus.Connected
    private fun subscribeScreens(read: ScreenRead) {
        if (serverBrowser && previewScreenSubscription == null) {
            previewScreenSubscription = subscribe("subscribePreviewEvents", buildJsonObject {}) { event ->
                if (screenReadCurrent(read) && event.string("threadId") == read.threadId) {
                    previewScreenEvents.addLast(event)
                    while (previewScreenEvents.size > 200) previewScreenEvents.removeFirst()
                    val next = applyPreviewScreenEvent(previewScreenValue, event)
                    if (next == null) {
                        browserScreensFresh = false
                        update { it.copy(screens = it.screens.copy(browsers = emptyList())) }
                        scope.launch { runCatching { refreshScreens() } }
                    } else {
                        previewScreenValue = next
                        update { it.copy(screens = it.screens.copy(browsers = browserScreens(next, read.threadId, previewAuthorized))) }
                    }
                }
            }.also { optionalScreenStreams.add(it) }
        }
        if (deviceScreenSubscription == null) {
            deviceScreenSubscription = subscribe("subscribeDeviceState", buildJsonObject {}) { value ->
                if (screenReadCurrent(read)) applyDeviceScreens(value, read.threadId)
            }.also { optionalScreenStreams.add(it) }
        }
    }
    private fun applyDeviceScreens(value: JsonObject, threadId: String) {
        val revision = (value["revision"] as? JsonPrimitive)?.longOrNull ?: return
        if (revision < deviceScreenRevision) return
        deviceScreenRevision = revision; deviceScreensFresh = true
        update { it.copy(screens = it.screens.copy(devices = deviceScreens(value, threadId))) }
    }
    override suspend fun refreshScreens() {
        val captured = synchronized(lifecycleLock) {
            check(state.value.connection == ConnectionStatus.Connected && shellReady) { "Connect before refreshing screens" }
            val threadId = state.value.selectedThreadId ?: error("Select a thread")
            val read = ScreenRead(generation, threadGeneration, ++screenRead, threadId, state.value.environment ?: error("Connect an environment"))
            update { it.copy(screens = it.screens.copy(loading = true, error = null)) }
            subscribeScreens(read)
            read
        }
        try {
            supervisorScope {
                val browser = async { if (serverBrowser) runCatching { rpc("preview.list", buildJsonObject { put("threadId", captured.threadId) }, captured.epoch).jsonObject } else Result.success(buildJsonObject { put("sessions", JsonArray(emptyList())) }) }
                val device = async { runCatching { rpc("device.list", buildJsonObject { put("inspectOnly", true) }, captured.epoch).jsonObject } }
                val browsers = browser.await(); val devices = device.await()
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    if (!screenReadCurrent(captured) || captured.read != screenRead) return@synchronized
                    browsers.getOrNull()?.let { listed ->
                        val listedEpoch = listed.string("serverEpoch")
                        val oldRevision = (previewScreenValue["revision"] as? JsonPrimitive)?.longOrNull ?: -1
                        val listedRevision = (listed["revision"] as? JsonPrimitive)?.longOrNull ?: 0
                        if (previewScreenValue.string("serverEpoch") != listedEpoch || listedRevision >= oldRevision) {
                            previewScreenValue = previewScreenEvents.filter { it.string("serverEpoch") == listedEpoch }.fold(listed) { value, event -> applyPreviewScreenEvent(value, event) ?: value }
                        }
                        browserScreensFresh = true
                        update { it.copy(screens = it.screens.copy(browsers = browserScreens(previewScreenValue, captured.threadId, previewAuthorized))) }
                    }
                    devices.getOrNull()?.let { applyDeviceScreens(it, captured.threadId) }
                    if (browsers.isFailure) browserScreensFresh = false
                    if (devices.isFailure) deviceScreensFresh = false
                    update { it.copy(screens = it.screens.copy(loaded = true, loading = false,
                        error = if (browsers.isFailure || devices.isFailure) "Some remote screens could not be loaded. Refresh to try again." else null)) }
                }
            }
        } catch (error: CancellationException) {
            synchronized(lifecycleLock) { if (screenReadCurrent(captured) && captured.read == screenRead) update { it.copy(screens = it.screens.copy(loading = false)) } }
            throw error
        }
    }
    private fun currentScreenTarget(target: ScreenTarget): ScreenTarget? = state.value.screens.targets.firstOrNull { sameScreenTarget(it, target) }
    override fun isScreenAccessCurrent(access: ScreenAccess): Boolean = synchronized(lifecycleLock) {
        val environment = state.value.environment ?: return@synchronized false
        access.connectionGeneration == generation && access.selectionGeneration == threadGeneration && access.environmentId == environment.id &&
            state.value.connection == ConnectionStatus.Connected && access.target.threadId == state.value.selectedThreadId &&
            currentScreenTarget(access.target) != null && activeBase?.let { validateScreenAccess(access, environment.copy(baseUrl = it.toString(), routes = emptyList())) } == true &&
            (if (access.target is ScreenTarget.Browser) browserScreensFresh else deviceScreensFresh)
    }
    private data class ScreenViewerRead(val epoch: Long, val selection: Long, val environment: SavedEnvironment, val target: ScreenTarget, val base: HttpUrl)
    override suspend fun screenAccess(target: ScreenTarget): ScreenAccess {
        val captured = synchronized(lifecycleLock) {
            check(state.value.connection == ConnectionStatus.Connected && shellReady) { "Connect before opening a remote screen" }
            check(target.threadId == state.value.selectedThreadId) { "Select this screen's thread" }
            check(if (target is ScreenTarget.Browser) browserScreensFresh else deviceScreensFresh) { "Refresh remote screens before opening" }
            val current = currentScreenTarget(target) ?: error("This remote screen is no longer available")
            ScreenViewerRead(generation, threadGeneration, state.value.environment ?: error("Connect an environment"), current, activeBase ?: error("Connect an environment"))
        }
        val (epoch, selection, environment, currentTarget) = captured
        // The live route's base: it passed the URL policy and the identity check before the bearer reached it.
        val base = url(captured.base.toString(), environment.allowInsecureHttp)
        // Media tickets stay unbound on T3 (one ticket serves several requests), so only the mint carries a proof.
        val ticket = try { authorizedFetch(environment, "POST", base.resolve("/api/auth/websocket-ticket/media")!!, timeoutMillis = 8_000).string("ticket") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw IllegalStateException("Remote screen access failed. Refresh and try again.") }
        currentCoroutineContext().ensureActive()
        check(ticket.isNotBlank()) { "Server returned no media ticket" }
        return synchronized(lifecycleLock) {
            check(epoch == generation && selection == threadGeneration && environment.id == state.value.environment?.id && currentScreenTarget(currentTarget) != null &&
                state.value.connection == ConnectionStatus.Connected && currentTarget.threadId == state.value.selectedThreadId &&
                (if (currentTarget is ScreenTarget.Browser) browserScreensFresh else deviceScreensFresh)) { "Remote screen changed; open it again" }
            val httpBase = base.resolve(screenBasePath(currentTarget))!!.toString().removeSuffix("/")
            ScreenAccess(environment.id, epoch, currentTarget, httpBase, httpBase.replaceFirst("http", "ws"),
                buildMap { put("wsTicket", ticket); if (currentTarget is ScreenTarget.Device) put("hostId", currentTarget.hostId) },
                interactive = if (currentTarget is ScreenTarget.Browser) previewAuthorized else authorized, selectionGeneration = selection)
        }
    }
    /**
     * `subscribeDiscoveredLocalServers {}` once the connection's shell is ready, at most once per connection. Each
     * chunk is a full snapshot. A rejection (older server, unknown method, missing scope) marks the catalog
     * unsupported and is never retried on this connection or surfaced as an error. Caller holds [lifecycleLock].
     */
    private fun startDiscovery() {
        if (discoveryEpoch == generation || socket == null || state.value.connection != ConnectionStatus.Connected) return
        val epoch = generation
        discoveryEpoch = epoch
        discoverySubscription = runCatching {
            subscribe("subscribeDiscoveredLocalServers", buildJsonObject {}) { snapshot ->
                if (epoch == generation) discoveredLocalServers(snapshot)?.let { catalog -> update { it.copy(localServers = catalog) } }
            }
        }.getOrNull()
    }
    /** A failed Exit is a rejection; an interrupted or successful one only ends the live stream. Caller holds [lifecycleLock]. */
    private fun discoveryEnded(exit: JsonObject) {
        discoverySubscription = null
        val rejected = exit.string("_tag") != "Success" && !exitInterruptedOnly(exit)
        update { it.copy(localServers = if (rejected) LocalServerCatalog(supported = false) else it.localServers.copy(live = false)) }
    }
    /** Drops the stream with its connection; the next connection asks again. Caller holds [lifecycleLock]. */
    private fun endDiscovery() {
        discoverySubscription = null
        update { it.copy(localServers = LocalServerCatalog(supported = it.localServers.supported)) }
    }

    /**
     * Forwards belong to the live connection: disconnect, reconnect, environment switch, [close] and a lost SSH
     * tunnel (which fails the socket) all stop them, and nothing restarts them. Caller holds [lifecycleLock].
     */
    private fun stopPortForwards() {
        val open = portForwarders.values.toList()
        portForwarders.clear(); tunnelTickets = null
        open.forEach { it.close() }
        update { it.copy(portForwards = emptyList()) }
    }
    private fun publishPortForwards() = update { it.copy(portForwards = portForwarders.values.map { forwarder -> forwarder.snapshot() }) }

    /**
     * Binds the loopback listener for [remotePort] and returns its state. An existing forward is returned (after
     * its bind finishes); a failed one is replaced, which is how the user retries. A bind failure returns a
     * [PortForwardStatus.Failed] forward rather than throwing. Tunnel failures arrive later, per connection, in
     * [ClientState.portForwards]. Throws when not connected or when the connection changed during the bind.
     */
    override suspend fun startPortForward(remotePort: Int): PortForward {
        require(remotePort in 1..65535) { "Choose a port from 1 to 65535" }
        val (forwarder, created) = synchronized(lifecycleLock) {
            check(state.value.connection == ConnectionStatus.Connected && shellReady && socket != null) { "Connect before forwarding a port" }
            val environment = state.value.environment ?: error("Connect an environment")
            // The live route's base: it passed the URL policy and the identity check (an SSH route is its loopback forward).
            val base = activeBase ?: error("Connect an environment")
            val existing = portForwarders[remotePort]
            if (existing != null && existing.snapshot().status != PortForwardStatus.Failed) return@synchronized existing to false
            existing?.close()
            val epoch = generation
            val tickets = tunnelTickets?.takeIf { it.first == epoch }?.second
                ?: MediaTicketCache { mintTunnelTicket(epoch, environment.id, base) }.also { tunnelTickets = epoch to it }
            val tunnel = base.resolve("/api/preview/tunnel")!!
            val forwarder = LoopbackPortForwarder(environment.id, remotePort, tunnelHttp,
                { ticket -> tunnel.newBuilder().query(null).addQueryParameter("port", remotePort.toString()).addQueryParameter("wsTicket", ticket).build() },
                tickets, onChange = { synchronized(lifecycleLock) { if (epoch == generation) publishPortForwards() } }, admission = tunnelAdmission)
            portForwarders.remove(remotePort); portForwarders[remotePort] = forwarder
            publishPortForwards()
            forwarder to true
        }
        // Always finishes, so a cancelled caller never leaves a forward stuck in Starting.
        if (created) withContext(NonCancellable + Dispatchers.IO) { forwarder.bind() } else forwarder.bound.await()
        return synchronized(lifecycleLock) {
            if (portForwarders[remotePort] !== forwarder) { if (created) forwarder.close(); error("Connection changed; forward the port again") }
            publishPortForwards()
            forwarder.snapshot()
        }
    }
    override suspend fun stopPortForward(remotePort: Int) = synchronized(lifecycleLock) {
        portForwarders.remove(remotePort)?.let { it.close(); publishPortForwards() }
        Unit
    }
    /** Mints the connection's media ticket at the verified live base; failures map to how a tunnel connection fails. */
    private suspend fun mintTunnelTicket(epoch: Long, environmentId: String, base: HttpUrl): MintedMediaTicket {
        val environment = synchronized(lifecycleLock) {
            state.value.environment?.takeIf { epoch == generation && it.id == environmentId } ?: throw MediaTicketUnavailable(PortForwardError.Unreachable)
        }
        val reply = try { authorizedFetch(environment, "POST", base.resolve("/api/auth/websocket-ticket/media")!!, timeoutMillis = 8_000) }
        catch (error: CancellationException) { throw error }
        catch (error: HttpFailure) {
            throw MediaTicketUnavailable(when (error.status) { 401, 403 -> PortForwardError.Unauthorized; 404 -> PortForwardError.ServerOutdated; else -> PortForwardError.Unreachable })
        }
        catch (_: DpopKeyUnavailable) { throw MediaTicketUnavailable(PortForwardError.Unauthorized) }
        catch (_: TokenRenewalRejected) { throw MediaTicketUnavailable(PortForwardError.Unauthorized) }
        catch (_: Exception) { throw MediaTicketUnavailable(PortForwardError.Unreachable) }
        val ticket = reply.string("ticket")
        if (ticket.isBlank()) throw MediaTicketUnavailable(PortForwardError.Unreachable)
        synchronized(lifecycleLock) { if (epoch != generation) throw MediaTicketUnavailable(PortForwardError.Unreachable) }
        return MintedMediaTicket(ticket, mediaTicketExpiry(reply))
    }
    override suspend fun downloadFile(reference: RemoteFileReference, output: java.io.OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit): DownloadedFile {
        data class FileRead(val epoch: Long, val selection: Long, val environment: SavedEnvironment, val threadId: String)
        currentCoroutineContext().ensureActive()
        val captured = synchronized(lifecycleLock) {
            check(state.value.environment?.id == environmentId && state.value.selectedThreadId == threadId) { "Download context changed" }
            // Reading a file needs a synced session, not a fresh thread projection (that blinks on every event).
            check(shellReady && state.value.connection == ConnectionStatus.Connected) { "Connect and refresh before downloading a file" }
            // Downloads go to the live route, which passed the URL policy and the identity check.
            val base = activeBase ?: error("Connect an environment")
            FileRead(generation, threadGeneration, (state.value.environment ?: error("Connect an environment")).let { it.copy(baseUrl = base.toString()) }, state.value.selectedThreadId ?: error("Select a thread"))
        }
        fun current() = synchronized(lifecycleLock) {
            generation == captured.epoch && threadGeneration == captured.selection && state.value.environment?.id == captured.environment.id &&
                state.value.selectedThreadId == captured.threadId && state.value.connection == ConnectionStatus.Connected
        }
        try {
            check(listOf(reference.path, reference.attachmentId, reference.toolOutputItemId).count { it != null } == 1) { "Invalid file reference" }
            check(reference.toolOutputItemId != null || reference.toolOutputIndex == null) { "Invalid file reference" }
            val sourceThreadId = reference.sourceThreadId ?: captured.threadId
            check(sourceThreadId.isNotBlank() && sourceThreadId.length <= 256 && sourceThreadId.none { it.isISOControl() }) { "Invalid file reference" }
            val resource = buildJsonObject {
                val attachmentId = reference.attachmentId
                if (attachmentId != null) {
                    check(attachmentId.isNotBlank() && attachmentId.length <= 256) { "Invalid file reference" }
                    put("_tag", "attachment"); put("attachmentId", attachmentId)
                    put("fileName", safeDownloadFileName(reference.fileName)); put("disposition", "attachment")
                    reference.mimeType?.takeIf { it.isNotBlank() && it.length <= 100 }?.let { put("mimeType", it) }
                } else if (reference.toolOutputItemId != null) {
                    val itemId = reference.toolOutputItemId ?: error("Invalid tool image reference")
                    val index = reference.toolOutputIndex
                    check(itemId.isNotBlank() && itemId.length <= 256 && index != null && index >= 0 && index < 8) { "Invalid tool image reference" }
                    put("_tag", "tool-output-image"); put("threadId", sourceThreadId); put("itemId", itemId); put("index", index)
                } else {
                    val path = reference.path ?: error("Invalid file reference")
                    check(path.isNotBlank() && path.length <= 1024 && path.none { it.isISOControl() }) { "Invalid file reference" }
                    put("_tag", "media-file"); put("threadId", sourceThreadId); put("path", path); put("disposition", "attachment")
                }
            }
            val asset = rpc("assets.createUrl", buildJsonObject { put("resource", resource) }, captured.epoch, ::current).jsonObject
            currentCoroutineContext().ensureActive()
            check(current()) { "Connection or selected thread changed; download cancelled" }
            check((asset["expiresAt"] as? JsonPrimitive)?.doubleOrNull?.let { it.isFinite() && it > System.currentTimeMillis() } == true) { "Download access expired; try again" }
            var registered: Call? = null
            return SignedFileDownloader(http).download(captured.environment, asset.string("relativeUrl"), reference, output, onProgress, ::current) { call ->
                synchronized(lifecycleLock) {
                    if (call == null) { registered?.let { fileDownloads.remove(it) }; registered = null }
                    else { check(current()) { "Connection or selected thread changed; download cancelled" }; registered = call; fileDownloads += call }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw IllegalStateException(if (current()) "File download failed. The file may be unavailable or access may have been denied." else "Connection or selected thread changed; download cancelled") }
    }
    private fun requireWrite() { check(state.value.canWrite) { "Wait for a fresh connection before writing" } }
    /**
     * Every thread event marks the projection stale until its refetch lands, which is routine while an
     * agent works. A write issued in that gap waits briefly for the refresh instead of failing. It only
     * proceeds on the same connection and thread selection, so nothing is ever sent late into a new
     * session or another thread; [requireWrite] still decides.
     */
    private suspend fun awaitFreshThread() {
        if (state.value.canWrite) return
        val (epoch, selection) = synchronized(lifecycleLock) { generation to threadGeneration }
        withTimeoutOrNull(FRESH_THREAD_WAIT_MS) { state.first { it.canWrite || it.connection != ConnectionStatus.Connected } }
        synchronized(lifecycleLock) { check(epoch == generation && selection == threadGeneration) { "The thread changed; try again" } }
    }
    private fun command(type: String, extra: JsonObject): JsonObject = threadCommand(type, state.value.selectedThreadId ?: error("Select a thread"), extra)
    /** Like [command], for a command that targets an explicit thread (for example a subagent's child). */
    private fun threadCommand(type: String, threadId: String, extra: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("type", type); put("commandId", UUID.randomUUID().toString()); put("threadId", threadId); extra.forEach { (key, value) -> put(key, value) }
    }
    /**
     * Sends one mutation once. [extra] runs under the lifecycle lock after the write check, so
     * local validation fails before anything is sent. See [mutation] for failure handling.
     */
    private suspend fun dispatch(type: String, subject: String, extra: () -> JsonObject): Unit = dispatchCommand(subject) { command(type, extra()) }
    /** Like [dispatch] for a command whose type is chosen under the lock: [build] returns the whole command. */
    private suspend fun dispatchCommand(subject: String, build: () -> JsonObject): Unit = mutating {
        awaitFreshThread()
        val (epoch, payload) = synchronized(lifecycleLock) { requireWrite(); generation to build() }
        mutation(epoch, subject) { requireReceipt(rpc("orchestration.dispatchCommand", payload, epoch)) }
    }
    /** A dispatch receipt is `{sequence}` with a non-negative integer; anything else is treated as an uncertain outcome. */
    private fun requireReceipt(result: JsonElement) {
        val sequence = ((result as? JsonObject)?.get("sequence") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (sequence == null || sequence < 0) throw MalformedReceipt()
    }
    /**
     * Like [dispatchCommand] for the follow-up, queue and stop commands: [build] runs under the lock with the selected
     * thread captured, and [sendInSession] re-checks that the same thread is still selected and writable when sending.
     */
    private suspend fun dispatchSelected(subject: String, build: () -> JsonObject): Unit = mutating {
        awaitFreshThread()
        val (session, payload) = synchronized(lifecycleLock) { requireWrite(); threadSession() to build() }
        sendInSession(session, subject, payload)
    }
    /**
     * A definite server rejection (a failed Exit with a typed `Fail` or a `Die` defect) changed
     * nothing, so the session stays usable and the caller gets a clear error, as does a [CommandNotSent] guard. Anything else after
     * the send (timeout, disconnect, interrupted Exit) leaves server state unknown to this
     * projection: the session fails (writes disabled) until a reconnect reconciles it.
     */
    private suspend fun <T> mutation(epoch: Long, subject: String, block: suspend () -> T): T = try { block() }
    catch (error: Exception) {
        if (error is CommandNotSent) throw error
        if (error is ServerRejected) throw IllegalStateException("T3 rejected the $subject. Check the conversation and try again.", error)
        val message = "${subject.replaceFirstChar { it.uppercase() }} outcome is uncertain. Reconnect and check the conversation before trying again."
        synchronized(lifecycleLock) { if (epoch == generation) fail(epoch, message) }
        // Caller cancellation still propagates; the session already records the uncertainty.
        if (error is CancellationException) throw error
        throw UncertainOutcomeException(message)
    }
    /** T3 requires creation provenance on every `message.dispatch`; the server stamps `createdBy` itself. */
    private fun JsonObjectBuilder.provenance() { put("createdBy", "user"); put("creationSource", CREATION_SOURCE) }
    override suspend fun sendMessage(text: String) {
        require(text.isNotBlank()) { "Message is empty" }
        dispatch("message.dispatch", "message") { buildJsonObject {
            provenance()
            put("messageId", UUID.randomUUID().toString()); put("text", text); put("attachments", JsonArray(emptyList()))
            put("dispatchMode", buildJsonObject { put("type", messageDispatchMode(state.value)) })
        } }
    }
    /**
     * T3 `dispatchThreadMessage`: while the thread works, [FollowUpMode.Queue] is `queue_after_active` and
     * [FollowUpMode.Steer] is `start_immediately` with `deliveryIntent: "steer"`, refused locally unless
     * [ClientState.canSteer]. An idle thread starts immediately either way.
     */
    override suspend fun sendMessage(text: String, mode: FollowUpMode) {
        require(text.isNotBlank()) { "Message is empty" }
        dispatchSelected("message") {
            val current = state.value
            val busy = messageDispatchMode(current) == "queue_after_active"
            val steer = busy && mode == FollowUpMode.Steer
            if (steer) check(current.canSteer) { "This turn cannot be steered right now; queue the message instead" }
            command("message.dispatch", buildJsonObject {
                provenance()
                put("messageId", UUID.randomUUID().toString()); put("text", text); put("attachments", JsonArray(emptyList()))
                if (steer) put("deliveryIntent", "steer")
                put("dispatchMode", buildJsonObject { put("type", if (busy && !steer) "queue_after_active" else "start_immediately") })
            })
        }
    }
    private fun queuedRun(runId: String): QueuedMessage =
        state.value.queuedMessages.firstOrNull { it.runId == runId } ?: error("This message is no longer queued")
    override suspend fun cancelQueuedMessage(runId: String) {
        dispatchSelected("queue change") { command("queued-run.cancel", buildJsonObject { put("runId", queuedRun(runId).runId) }) }
    }
    /**
     * T3 "send now": `queued-run.start` when no run is active, else `queued-message.promote-to-steer` into it. Gated by the
     * row's [QueuedMessage.canSendNow] (T3 queue eligibility), not the composer's [ClientState.canSteer].
     */
    override suspend fun steerQueuedMessage(runId: String) {
        dispatchSelected("queue change") {
            val queued = queuedRun(runId)
            val active = activeThreadRun(projection)?.string("id")?.takeIf { it.isNotEmpty() }
            if (active == null) {
                check(queued.canSendNow) { "This conversation is archived" }
                command("queued-run.start", buildJsonObject { put("runId", queued.runId) })
            } else {
                check(queued.canSendNow) { "This turn cannot take this message now; it stays queued" }
                command("queued-message.promote-to-steer", buildJsonObject { put("queuedRunId", queued.runId); put("targetRunId", active) })
            }
        }
    }
    override suspend fun resumeQueue() {
        dispatchSelected("queue change") { check(state.value.queueHeld) { "The queue is not paused" }; command("queue.resume", JsonObject(emptyMap())) }
    }
    /**
     * Sends the draft's own `threadId` and `commandId`, so an explicit resend after an uncertain
     * outcome is deduplicated by the server (`resumed`) instead of creating a second thread. The
     * initial `messageId` is derived from the command so a resend carries the same one.
     */
    override suspend fun launchThread(draft: ThreadDraft, firstMessage: String): String = mutating {
        require(firstMessage.isNotBlank()) { "Message is empty" }
        require(draft.threadId.isNotBlank() && draft.commandId.isNotBlank()) { "Invalid draft identity" }
        val (epoch, payload) = synchronized(lifecycleLock) {
            // A new thread depends on the shell (projects, models), not on whichever thread is open.
            requireThreadWrite()
            check(state.value.projects.any { it.id == draft.projectId }) { "Choose a project from this environment" }
            val model = state.value.models.firstOrNull { it.instanceId == draft.model.instanceId && it.model == draft.model.model } ?: error("Choose a server-provided model")
            check(model.supportedRuntimeModes.isEmpty() || draft.runtimeMode in model.supportedRuntimeModes) { "${model.label} does not support ${draft.runtimeMode.label}" }
            // Only values the catalog still offers are sent; the catalog may have changed since the draft picked them.
            val options = model.validOptions(draft.modelOptions)
            generation to buildJsonObject {
                put("commandId", draft.commandId); put("threadId", draft.threadId); put("creationSource", CREATION_SOURCE)
                put("projectId", draft.projectId); put("title", threadTitleSeed(firstMessage)); put("generateTitle", true)
                put("modelSelection", buildJsonObject {
                    put("instanceId", model.instanceId); put("model", model.model)
                    if (options.isNotEmpty()) put("options", modelOptionsJson(options))
                })
                put("runtimeMode", draft.runtimeMode.wire); put("interactionMode", "default")
                put("workspaceStrategy", buildJsonObject { put("type", "root") })
                put("initialMessage", buildJsonObject {
                    put("messageId", initialMessageId(draft.commandId)); put("text", firstMessage); put("attachments", JsonArray(emptyList()))
                })
            }
        }
        val result = mutation(epoch, "new conversation") { rpc("orchestration.launchThread", payload, epoch).jsonObject }
        val threadId = result.string("threadId")
        val projection = result["projection"] as? JsonObject
        synchronized(lifecycleLock) {
            val projectedId = projection?.obj("thread")?.string("id").orEmpty()
            if (threadId != draft.threadId || (projectedId.isNotEmpty() && projectedId != threadId)) {
                val message = "New conversation outcome is uncertain. Reconnect and check the thread list before trying again."
                if (epoch == generation) fail(epoch, message)
                throw UncertainOutcomeException(message)
            }
            // Seed the cache so selecting the new thread shows the first message before its stream syncs.
            val environmentId = state.value.environment?.id
            if (epoch == generation && environmentId != null && projection != null) {
                threadProjections.put(environmentId to threadId, CachedThread(projection, threadPresentation(projection)))
            }
        }
        threadId
    }
    override suspend fun setRuntimeMode(mode: RuntimeMode) {
        dispatch("thread.runtime-mode.set", "mode change") { buildJsonObject { put("runtimeMode", mode.wire) } }
    }
    /**
     * Keeps the thread's provider `options` (e.g. reasoning effort) when the model is unchanged and
     * drops them for another model. A started thread whose provider cannot hand off stays on its
     * provider instance ([ClientState.selectedProviderLocked]).
     */
    override suspend fun setModel(model: ModelOption) {
        dispatchModelSelection("model change") { current, base, pending ->
            val selected = current.models.firstOrNull { it.instanceId == model.instanceId && it.model == model.model } ?: error("Choose a server-provided model")
            check(!current.selectedProviderLocked || base.instanceId.isBlank() || base.instanceId == selected.instanceId) {
                "This conversation has started and its provider cannot change. Choose a model from the same provider."
            }
            val sameModel = base.instanceId == selected.instanceId && base.model == selected.model
            val wire = projection.obj("thread").obj("modelSelection")["options"]
            // Without an accepted change still pending, either projected wire form is passed through unchanged.
            val options: JsonElement? = when {
                !sameModel -> null
                !pending -> wire?.takeIf { it is JsonObject || it is JsonArray }
                base.options.isNotEmpty() -> modelOptionsJson(base.options)
                else -> null
            }
            ModelSelection(selected.instanceId, selected.model, if (sameModel) base.options else emptyMap()) to buildJsonObject { put("modelSelection", buildJsonObject {
                put("instanceId", selected.instanceId); put("model", selected.model); options?.let { put("options", it) }
            }) }
        }
    }
    /**
     * Same instance and model with the thread's options merged: [id] gets [value], the others stay
     * unless the current catalog no longer offers them. Sent in the canonical array form; an id or
     * value the model does not offer is refused locally.
     */
    override suspend fun setModelOption(id: String, value: Any) {
        dispatchModelSelection("model option change") { current, base, _ ->
            check(base.instanceId.isNotBlank() && base.model.isNotBlank()) { "This conversation has no model yet" }
            val model = current.models.firstOrNull { it.instanceId == base.instanceId && it.model == base.model } ?: error("The conversation's model is not offered by this server")
            model.requireOptionValue(id, value)
            val options = model.validOptions(base.options) + (id to value)
            ModelSelection(model.instanceId, model.model, options) to buildJsonObject { put("modelSelection", buildJsonObject {
                put("instanceId", model.instanceId); put("model", model.model); put("options", modelOptionsJson(options))
            }) }
        }
    }
    /** One favourite change; compared by identity, so two equal changes in flight stay separate. */
    private class FavoriteChange(val key: ModelKey, val favorite: Boolean)
    /**
     * Shows the change at once and sends `server.updateSettings` `setModelFavorites` once on this connection. A reply
     * confirms this key only (its whole-settings snapshot may be older than a `settingsUpdated` already applied); any
     * failure drops this change only, so the last server value (and other changes
     * still in flight) show again. A lost reply never fails the session: the config stream or the next connection
     * reports the server's list. Refused without `settings:write`, a live shell, or a server that reports favourites.
     */
    override suspend fun setModelFavorite(key: ModelKey, favorite: Boolean): Unit = mutating {
        val notSent = "Wait for a fresh connection before changing favourites"
        val (epoch, change) = synchronized(lifecycleLock) {
            check(state.value.canWriteSettings) { notSent }
            check(serverPreferences.synced) { "This T3 server does not sync favourites" }
            val change = FavoriteChange(key, favorite)
            pendingFavorites += change; publishModelPreferences()
            generation to change
        }
        val payload = buildJsonObject { put("patch", buildJsonObject { put("setModelFavorites", buildJsonArray { add(buildJsonObject {
            put("provider", key.instanceId); put("model", key.model); put("favorite", favorite)
        }) }) }) }
        try {
            rpc("server.updateSettings", payload, epoch, guard = { state.value.canWriteSettings })
            synchronized(lifecycleLock) {
                if (epoch == generation) {
                    pendingFavorites.remove(change)
                    val list = serverPreferences.favorites
                    serverPreferences = serverPreferences.copy(favorites = if (!favorite) list - key else if (key in list) list else list + key)
                    publishModelPreferences()
                }
            }
        } catch (error: Exception) {
            synchronized(lifecycleLock) { if (epoch == generation && pendingFavorites.remove(change)) publishModelPreferences() }
            if (error is CancellationException) throw error
            throw IllegalStateException(when (error) {
                is CommandNotSent -> notSent
                is ServerRejected -> "T3 rejected the favourite change. Try again."
                else -> "The favourite change was not confirmed. Favourites show the server's list once it reports it."
            }, error)
        }
    }
    /** A thread model selection compared by value: instance, model, and explicit options. */
    private data class ModelSelection(val instanceId: String, val model: String, val options: ModelOptionValues)
    /**
     * Model selections T3 accepted for one connection and thread selection that the projection does
     * not show yet, oldest first. The next model-selection change builds on the newest one instead of
     * the stale projection. It is only ever a base for a new user-initiated change, never resent.
     * [base] is the projected selection they were built on.
     */
    private class PendingModelSelections(val epoch: Long, val selection: Long, val threadId: String, var base: ModelSelection, val accepted: MutableList<ModelSelection>)
    private var pendingModelSelections: PendingModelSelections? = null
    private val modelSelectionMutex = Mutex()
    private fun projectedModelSelection(current: ClientState = state.value): ModelSelection {
        val selection = projection.obj("thread").obj("modelSelection")
        return ModelSelection(
            selection.string("instanceId").ifBlank { current.selectedModel?.instanceId.orEmpty() },
            selection.string("model").ifBlank { current.selectedModel?.model.orEmpty() },
            modelOptionValues(selection["options"]),
        )
    }
    private fun livePendingModelSelections(): PendingModelSelections? = pendingModelSelections?.takeIf {
        it.epoch == generation && it.selection == threadGeneration && it.threadId == state.value.selectedThreadId && it.accepted.isNotEmpty()
    }
    /**
     * Drops pending selections the projection now shows (and older ones). A projection that shows
     * neither them nor their base means another change landed: the overlay is discarded.
     */
    private fun reconcilePendingModelSelections(threadId: String?) {
        val pending = pendingModelSelections ?: return
        if (pending.epoch != generation || pending.selection != threadGeneration || pending.threadId != threadId) { pendingModelSelections = null; return }
        val projected = projectedModelSelection()
        val index = pending.accepted.lastIndexOf(projected)
        if (index >= 0) {
            repeat(index + 1) { pending.accepted.removeAt(0) }
            pending.base = projected
            if (pending.accepted.isEmpty()) pendingModelSelections = null
        } else if (projected != pending.base) pendingModelSelections = null
    }
    /**
     * Model-selection changes run one at a time: each builds its payload from the newest accepted
     * selection ([build] gets that base and whether it is a pending one), so overlapping changes never
     * drop each other. Failures follow [mutation]; a rejection records nothing, and an uncertain
     * outcome fails the session, which discards the overlay with its connection generation.
     */
    private suspend fun dispatchModelSelection(subject: String, build: (ClientState, ModelSelection, Boolean) -> Pair<ModelSelection, JsonObject>) = modelSelectionMutex.withLock {
        mutating {
            awaitFreshThread()
            data class Prepared(val epoch: Long, val selection: Long, val threadId: String, val projected: ModelSelection, val sent: ModelSelection, val payload: JsonObject)
            val prepared = synchronized(lifecycleLock) {
                requireWrite()
                val current = state.value
                val threadId = current.selectedThreadId ?: error("Select a thread")
                val projected = projectedModelSelection(current)
                val pending = livePendingModelSelections()
                val (sent, extra) = build(current, pending?.accepted?.last() ?: projected, pending != null)
                Prepared(generation, threadGeneration, threadId, projected, sent, command("thread.model-selection.set", extra))
            }
            try { mutation(prepared.epoch, subject) { rpc("orchestration.dispatchCommand", prepared.payload, prepared.epoch) } }
            catch (error: Throwable) {
                // After a rejection the next change starts again from the projection.
                synchronized(lifecycleLock) { if (prepared.epoch == generation) pendingModelSelections = null }
                throw error
            }
            synchronized(lifecycleLock) {
                // Recorded only while the same session still shows the selection it was built on.
                if (prepared.epoch == generation && prepared.selection == threadGeneration && state.value.selectedThreadId == prepared.threadId) {
                    val projected = projectedModelSelection()
                    val pending = livePendingModelSelections()
                    when {
                        projected == prepared.sent -> pendingModelSelections = null
                        projected != prepared.projected -> pendingModelSelections = null
                        pending != null -> pending.accepted += prepared.sent
                        else -> pendingModelSelections = PendingModelSelections(prepared.epoch, prepared.selection, prepared.threadId, projected, mutableListOf(prepared.sent))
                    }
                }
            }
        }
    }
    private var undoPlan: Triple<SettlementUndo, Long, List<Pair<ThreadAction, String?>>>? = null
    private val threadActionMutex = Mutex()

    suspend fun threadAction(target: ThreadTarget, action: ThreadAction, value: String? = null): Unit = mutating {
        val requestedEpoch = synchronized(lifecycleLock) { requireThreadWrite(); generation }
        threadActionMutex.withLock {
            performThreadAction(target, action, value, requestedEpoch)
        }
    }

    private fun requireThreadWrite() { check(authorized && shellReady && state.value.connection == ConnectionStatus.Connected) { "Wait for a fresh connection before writing" } }

    private suspend fun performThreadAction(target: ThreadTarget, action: ThreadAction, value: String?, expectedEpoch: Long, restoring: Boolean = false) {
        val (epoch, row, payload) = synchronized(lifecycleLock) {
            check(generation == expectedEpoch) { "Connection changed; action cancelled" }
            requireThreadWrite()
            check(state.value.environment?.id == target.environmentId) { "Machine changed; reopen the action" }
            val row = state.value.threads.firstOrNull { it.id == target.threadId && it.deletedAt == null && !it.archived && it.archivedAt == null } ?: error("Thread is unavailable")
            if (action in setOf(ThreadAction.Pin, ThreadAction.Unpin, ThreadAction.Snooze, ThreadAction.Wake)) check(state.value.threadActionsConfigured) { "Server configuration is unavailable" }
            if (action == ThreadAction.Snooze && !restoring) check(canSnoozeThread(row)) { "Respond to pending requests or wait for the turn to start before snoozing" }
            val actionValue = if (action == ThreadAction.Pin && !restoring && value == null) pinOrderKeyBefore(state.value.threads.filter { it.pinnedAt != null }.mapNotNull { it.pinOrderKey }.minOrNull()) else value
            val command = threadActionCommand(target, action, actionValue, UUID.randomUUID().toString())
            undoPlan = null
            update { it.copy(settlementUndo = null) }
            Triple(generation, row, command)
        }
        mutation(epoch, "thread action") { rpc("orchestration.dispatchCommand", payload, epoch) }
        synchronized(lifecycleLock) {
            if (epoch == generation && action == ThreadAction.Settle) {
                val undo = SettlementUndo(UUID.randomUUID().toString(), target)
                undoPlan = Triple(undo, epoch, settlementUndoSteps(row))
                update { it.copy(settlementUndo = undo) }
            }
        }
    }

    suspend fun undoSettlement(undo: SettlementUndo): Unit = mutating { threadActionMutex.withLock {
        val plan = synchronized(lifecycleLock) {
            requireThreadWrite()
            val plan = undoPlan ?: error("Undo expired")
            check(plan.first == undo && plan.second == generation && state.value.environment?.id == undo.target.environmentId) { "Undo expired after connection changed" }
            undoPlan = null
            update { it.copy(settlementUndo = null) }
            plan
        }
        for ((action, value) in plan.third) {
            if (action == ThreadAction.Snooze && !Instant.parse(value).isAfter(Instant.now())) continue
            performThreadAction(undo.target, action, value, plan.second, restoring = true)
        }
    } }
    /**
     * T3 client-runtime `interruptThreadTurn({threadId, subagentId})`. Provider-native: `subagent.stop`
     * on the parent. App-owned: `subagent.stop` (older servers reject it, so a definite rejection is not
     * fatal), then stop the child thread with `thread.stop`, or `run.interrupt` on its active run when
     * the server lacks the `threadStop` capability. Each command is sent once; uncertain outcomes fail
     * the session as for any mutation, and nothing further is sent after the session or selection changes.
     */
    override suspend fun stopSubagent(subagentId: String): Unit = mutating {
        awaitFreshThread()
        val (session, subagent) = synchronized(lifecycleLock) {
            requireWrite()
            val subagent = state.value.subagents.firstOrNull { it.id == subagentId } ?: error("Agent is no longer listed")
            check(subagent.canStop) { "This agent cannot be stopped from here" }
            threadSession() to subagent
        }
        stopSubagentIn(session, subagent)
    }
    /** The connection, thread selection and selected thread a multi-command stop was planned for. */
    private data class ThreadSession(val epoch: Long, val selection: Long, val threadId: String)
    /** Caller holds [lifecycleLock]. */
    private fun threadSession() = ThreadSession(generation, threadGeneration, state.value.selectedThreadId ?: error("Select a thread"))
    private fun sameSession(session: ThreadSession) = synchronized(lifecycleLock) {
        session.epoch == generation && session.selection == threadGeneration && state.value.selectedThreadId == session.threadId &&
            authorized && state.value.connection == ConnectionStatus.Connected
    }
    private fun writableSession(session: ThreadSession) = synchronized(lifecycleLock) { sameSession(session) && state.value.canWrite }
    private fun requireSameSession(session: ThreadSession) { if (!sameSession(session)) throw CommandNotSent() }
    /**
     * Sends one command of [session] once, after waiting briefly for a stale projection to refresh (an earlier command of
     * the same stop marks it stale). The guard re-checks selection and [ClientState.canWrite] under the send lock, so a
     * selection change or a still-stale thread sends nothing ([CommandNotSent], which ends a multi-command stop).
     */
    private suspend fun sendInSession(session: ThreadSession, subject: String, payload: JsonObject) {
        if (!state.value.canWrite) withTimeoutOrNull(FRESH_THREAD_WAIT_MS) { state.first { it.canWrite || it.connection != ConnectionStatus.Connected } }
        if (!writableSession(session)) throw CommandNotSent()
        mutation(session.epoch, subject) { requireReceipt(rpc("orchestration.dispatchCommand", payload, session.epoch, guard = { writableSession(session) })) }
    }
    /**
     * [stoppedChildren] holds the child threads this batch already stopped: each task still gets its parent `subagent.stop`,
     * but a child shared by several tasks is stopped once. Without `threadStop` the child gets T3's run-less fallback:
     * `run.interrupt` on [stopRunTarget] (live run, else the run its background work hangs off), else its pull request
     * watches unwatched; work that remains with nothing to stop it is a [PartialStop].
     */
    private suspend fun stopSubagentIn(session: ThreadSession, subagent: Subagent, stoppedChildren: MutableSet<String> = mutableSetOf()) {
        val parentId = session.threadId
        suspend fun send(payload: JsonObject) = sendInSession(session, "agent stop", payload)
        val parentStop = subagent.runId?.let { runId -> threadCommand("subagent.stop", parentId, buildJsonObject { put("runId", runId); put("subagentId", subagent.id) }) }
        if (!subagent.isAppOwned) { send(parentStop ?: error("This agent cannot be stopped from here")); return }
        val childId = subagent.childThreadId ?: error("This agent cannot be stopped from here")
        // The parent ends the task even when its child lost track of it; the child stop below still runs.
        if (parentStop != null) try { send(parentStop) } catch (error: IllegalStateException) { if (error.cause !is ServerRejected) throw error }
        if (!stoppedChildren.add(childId)) return
        requireSameSession(session)
        if (state.value.threadStopSupported) { send(threadCommand("thread.stop", childId)); return }
        val child = rpc("orchestration.getThreadProjection", buildJsonObject { put("threadId", childId) }, session.epoch).jsonObject
        currentCoroutineContext().ensureActive()
        stopRunTarget(child)?.let { runId -> send(threadCommand("run.interrupt", childId, buildJsonObject { put("runId", runId); put("holdQueue", true) })); return }
        pullRequestUnwatches(child).forEach { send(threadCommand("thread.pull-request.watch", childId, it)) }
        val remaining = pendingBackgroundWork(child).any { !it.taskId.startsWith(PULL_REQUEST_WATCH_TASK_ID) } || subagents(child).any { it.isActive }
        if (remaining) throw PartialStop()
    }
    /**
     * T3 thread Stop (`interruptThreadTurn` without a run). With `threadStop`: one `thread.stop` (the server stops the live
     * run or the run with pending background work, holding the queue, and the delegated tasks). Without it:
     * `run.interrupt {runId, holdQueue: true}` on [stopRunTarget]; with no run to interrupt, each active stoppable
     * subagent through [stopSubagentIn] (a shared child stopped once) and each pull request watch unwatched, one after
     * another. Nothing to stop fails locally. Every command is sent once, only while the same thread stays selected and
     * writable (a change ends the loop); a definite rejection or a [PartialStop] of one agent does not skip the others.
     */
    override suspend fun stopThread(): Unit = mutating {
        awaitFreshThread()
        data class Plan(val session: ThreadSession, val commands: List<JsonObject>, val agents: List<Subagent>)
        val plan = synchronized(lifecycleLock) {
            requireWrite()
            val session = threadSession()
            if (state.value.threadStopSupported) return@synchronized Plan(session, listOf(command("thread.stop", JsonObject(emptyMap()))), emptyList())
            stopRunTarget(projection)?.let { runId ->
                return@synchronized Plan(session, listOf(command("run.interrupt", buildJsonObject { put("runId", runId); put("holdQueue", true) })), emptyList())
            }
            val agents = state.value.subagents.filter { it.canStop }
            val unwatches = pullRequestUnwatches(projection).map { command("thread.pull-request.watch", it) }
            if (agents.isEmpty() && unwatches.isEmpty()) error(
                if (state.value.subagents.any { it.isActive }) "These agents cannot be stopped from here; use another T3 client" else "Nothing is running on this thread"
            )
            Plan(session, unwatches, agents)
        }
        var failure: Exception? = null
        val stoppedChildren = mutableSetOf<String>()
        for (agent in plan.agents) {
            try { stopSubagentIn(plan.session, agent, stoppedChildren) }
            catch (error: IllegalStateException) { if (error !is PartialStop && error.cause !is ServerRejected) throw error; if (failure == null) failure = error }
        }
        for (payload in plan.commands) {
            try { sendInSession(plan.session, "stop", payload) }
            catch (error: IllegalStateException) { if (error.cause !is ServerRejected) throw error; if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }
    override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>): Unit = mutating {
        awaitFreshThread()
        requireWrite()
        val request = state.value.requests.firstOrNull { it.id == requestId } ?: error("Request is no longer pending")
        check(request.canApprove) { "Use another T3 client to respond to this request" }
        val item = presentationItems(projection).firstOrNull { it.string("requestId") == requestId } ?: error("Missing request detail")
        dispatch("runtime-request.respond", "response") { buildJsonObject {
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
        } }
    }
    override suspend fun reconnect() {
        val environment = state.value.environment ?: error("Pair an environment first")
        val routes = synchronized(lifecycleLock) { lastRoutes?.takeIf { it.first == environment.id }?.second }
        if (routes != null) connect(environment, routes) else connect(environment)
    }
    /** Remove in-memory credentials as well as the local connection; no remote revoke. */
    fun forgetEnvironment(environmentId: String? = state.value.environment?.id) = synchronized(lifecycleLock) {
        if (environmentId == state.value.environment?.id) {
            disconnectNow()
            projection = JsonObject(emptyMap())
            mutable.value = ClientState()
        }
        environmentStates.remove(environmentId)
        shellCheckpoints.remove(environmentId)
        environmentId?.let(shellResume::forget)
        threadProjections.forgetEnvironment(environmentId)
        environmentId?.let(tokens::forget)
    }
    /**
     * Stops showing [environmentId] (a failed cold-start connect returns to the machine list) without forgetting it: the
     * connection closes and the visible state is cleared, while its retained rows, resume cursor, thread projections and
     * renewed token stay, as after a disconnect. Unlike [forgetEnvironment], nothing about the machine is dropped.
     */
    fun deselectEnvironment(environmentId: String) = synchronized(lifecycleLock) {
        if (environmentId != state.value.environment?.id) return@synchronized
        disconnectNow()
        projection = JsonObject(emptyMap())
        mutable.value = ClientState()
    }

    /**
     * Restores [shell] (read from the local cache) as [CachedShell.environmentId]'s retained rows, so its next connect
     * shows them at once with `shellLoaded` and, when the cached cursor still matches the server origin and is recent,
     * resumes with `afterSequence`. Restored rows are never live: `canRead`, `canWrite` and `canWriteThreads` stay false
     * and `shellLive` false until the `synchronized` marker of a live connection, and nothing is sent. Rows this process
     * already holds are newer and win, and a connection in progress takes the rows only until it decides how to subscribe
     * the shell: a cache read that finishes later is dropped, never waited for. Returns whether [shell] was restored.
     * The restored lists are [shell]'s own instances, which is what the cursor describes.
     */
    fun restoreShell(shell: CachedShell): Boolean = synchronized(lifecycleLock) {
        val id = shell.environmentId
        val current = state.value
        val shown = current.environment?.id == id
        val connecting = current.connection == ConnectionStatus.Connecting || current.connection == ConnectionStatus.Reconnecting
        if (shown && (current.shellLoaded || current.connection == ConnectionStatus.Connected || connecting && shellPlannedEpoch == generation)) return false
        if (environmentStates[id]?.shellLoaded == true) return false
        val base = (if (shown) current else environmentStates[id]) ?: ClientState()
        val restored = base.copy(projects = shell.projects, threads = shell.threads, shellLoaded = true, shellLive = false,
            canWrite = false, canWriteThreads = false, canWriteSettings = false, canRead = false, canReadUsage = false, busy = false, promptSuggestion = null)
        if (shown) mutable.value = restored
        environmentStates.remove(id)
        environmentStates[id] = restored
        evictRetained()
        shellResume.forget(id)
        if (shown || id in environmentStates) shellCheckpoints[id] = ShellCheckpoint(shell.projects, shell.threads, shell.cursor)
        shell.cursor?.takeIf { shown || id in environmentStates }?.let { cursor ->
            shellResume.restore(id, ShellCursor(ShellOrigin(id, cursor.serverVersion, cursor.databaseMigration), cursor.sequence,
                cursor.snapshotAtMillis, ShellRows(shell.projects, shell.threads)))
        }
        true
    }

    /**
     * What the local cache should hold for [environmentId]: its last checkpoint ([shellCheckpointed]), the rows and resume
     * cursor of a synchronized shell; null before any. Rows a failed catch-up or refresh applied before its marker never
     * replace it.
     */
    fun shellCacheEntry(environmentId: String, nowMillis: Long = System.currentTimeMillis()): CachedShell? = synchronized(lifecycleLock) {
        val checkpoint = shellCheckpoints[environmentId] ?: return null
        CachedShell(environmentId, checkpoint.projects, checkpoint.threads, checkpoint.cursor, nowMillis)
    }

    override fun close() {
        disconnectNow(); environmentStates.clear(); shellCheckpoints.clear(); shellResume.forget(null); threadProjections.clear()
        // Only this client's own calls and socket end above; a pool shared with the probe, the renewer and sibling clients keeps its warm connections.
        if (ownsPool) http.connectionPool.evictAll()
    }
}

private const val CREATION_SOURCE = "mobile"
/** Shown when the selected thread's data is larger than this app loads (no bounded snapshots yet). */
internal const val THREAD_TOO_LARGE = "This thread is too large to open on mobile yet. Open it in T3 Code on your computer."

/** No pong or frame for this long (two missed 15 s heartbeats) means a timed-out shell refresh sits on a dead link. */
internal const val SHELL_REFRESH_STALE_LINK_MS = 30_000L

/** Server setting `promptSuggestionsEnabled`; only a JSON `true` counts (older servers omit it and lack the method). */
internal fun promptSuggestionsSetting(settings: JsonObject?): Boolean =
    (settings?.get("promptSuggestionsEnabled") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

/** One `orchestration.subscribePromptSuggestion` item; `null`, a malformed item or blank text is no suggestion. */
internal fun promptSuggestion(item: JsonObject): PromptSuggestion? {
    val runId = (item["runId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
    val text = (item["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
    return PromptSuggestion(runId, text)
}

/** Stable per launch command, so an explicit resend of the same draft names the same first message. */
internal fun initialMessageId(commandId: String): String = UUID.nameUUIDFromBytes("$commandId:initial-message".toByteArray()).toString()

/** T3 client-runtime `deriveThreadTitleSeed`: whitespace-normalized, at most 50 code points plus "...". */
internal fun threadTitleSeed(text: String, maxLength: Int = 50): String {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.isEmpty()) return "New thread"
    val count = normalized.codePointCount(0, normalized.length)
    if (count <= maxLength) return normalized
    return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength)) + "..."
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
