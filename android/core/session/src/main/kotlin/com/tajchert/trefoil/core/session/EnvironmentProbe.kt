package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.PairingException
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.protocol.EffectRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only reachability check for a saved environment: the public descriptor, then
 * `GET /api/auth/session` with the saved bearer token. It opens no WebSocket, requests no
 * ticket and sends no command. The shared URL policy runs first, so a blocked address is
 * never contacted, and the token is only sent after the descriptor proves the same identity.
 */
class EnvironmentProbe(
    private val timeoutMillis: Long = 8_000,
    http: OkHttpClient = OkHttpClient(),
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    /** [baseUrl] overrides the saved address, e.g. with an SSH tunnel's ephemeral loopback port. */
    suspend fun probe(environment: SavedEnvironment, baseUrl: String = environment.baseUrl): MachineProbe {
        val base = try { validatedEnvironmentUrl(baseUrl, environment.allowInsecureHttp) } catch (_: PairingException) { return MachineProbe.NeedsAttention }
        return withTimeoutOrNull(timeoutMillis) {
            try {
                val (info, descriptorStatus) = get(Request.Builder().url(base.resolve("/.well-known/t3/environment")!!).build())
                    ?: return@withTimeoutOrNull MachineProbe.Unreachable
                if (descriptorStatus != 200 || info == null) return@withTimeoutOrNull MachineProbe.Unreachable
                if (info["orchestrationProtocolVersion"]?.jsonPrimitive?.intOrNull != 2 ||
                    info["environmentId"]?.jsonPrimitive?.contentOrNull != environment.id) return@withTimeoutOrNull MachineProbe.NeedsAttention
                val (session, status) = get(Request.Builder().url(base.resolve("/api/auth/session")!!)
                    .header("Authorization", "Bearer ${environment.accessToken}").build())
                    ?: return@withTimeoutOrNull MachineProbe.Unreachable
                when {
                    status == 401 || status == 403 -> MachineProbe.AuthFailed
                    status != 200 -> MachineProbe.Unreachable
                    session?.get("authenticated")?.jsonPrimitive?.booleanOrNull == true -> MachineProbe.Online
                    else -> MachineProbe.AuthFailed
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { MachineProbe.Unreachable }
        } ?: MachineProbe.Unreachable
    }

    /** Parsed JSON object body (null if absent or not an object) and status; null on network failure. */
    private suspend fun get(request: Request): Pair<JsonObject?, Int>? = try {
        http.newCall(request).await().use { response ->
            val body = runCatching { EffectRpc.json.parseToJsonElement(response.peekBody(MAX_BODY).string()) as? JsonObject }.getOrNull()
            body to response.code
        }
    } catch (_: IOException) { null }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        })
    }

    private companion object { const val MAX_BODY = 65_536L }
}
