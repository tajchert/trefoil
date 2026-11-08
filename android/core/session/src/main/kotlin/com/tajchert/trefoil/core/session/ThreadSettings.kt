package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.RuntimeMode
import com.tajchert.trefoil.core.protocol.obj
import com.tajchert.trefoil.core.protocol.objects
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Adapted from T3 Code (MIT): packages/client-runtime/src/state/threadWorkflows.ts,
// apps/mobile/src/state/thread-provider-switching.ts, apps/mobile/src/state/use-thread-composer-state.ts
// and apps/mobile/src/features/threads/thread-settings-options.ts.

private val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")
private val SETUP_STATUSES = setOf("preparing", "starting")

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** T3 `resolveThreadProviderSession`: the provider session the thread is attached to, if any. */
private fun providerSession(projection: JsonObject): JsonObject? {
    val thread = projection.obj("thread")
    val activeRun = projection.objects("runs").lastOrNull { it.string("status") in ACTIVE_RUN_STATUSES }
    val providerThreadId = activeRun?.text("providerThreadId") ?: thread.text("activeProviderThreadId")
    val providerThreads = projection.objects("providerThreads")
    val attached = providerThreadId?.let { id -> providerThreads.firstOrNull { it.string("id") == id } }
        ?: providerThreads.firstOrNull { it.string("appThreadId") == thread.string("id") && it.text("providerSessionId") != null }
    val sessionId = attached?.text("providerSessionId")
    val sessions = projection.objects("providerSessions")
    if (sessionId != null) return sessions.firstOrNull { it.string("id") == sessionId }
    return sessions.lastOrNull { it.string("status") != "stopped" && it.string("status") != "error" }
}

/**
 * True when the thread can no longer move to another provider instance. Port of T3
 * `threadSupportsProviderHandoff`: an attached session decides through its advertised
 * `capabilities.sessions.supportsProviderSwitchingViaHandoff` (absent = locked); otherwise an
 * active run locks, a thread that never ran is free, and a finished one is free only while its
 * native provider thread can be handed off.
 */
internal fun providerLocked(projection: JsonObject): Boolean {
    if (projection.isEmpty()) return false
    providerSession(projection)?.let { session ->
        val handoff = session.obj("capabilities").obj("sessions")["supportsProviderSwitchingViaHandoff"] as? JsonPrimitive
        return handoff?.booleanOrNull != true
    }
    val runs = projection.objects("runs")
    if (runs.any { it.string("status") in ACTIVE_RUN_STATUSES }) return true
    val thread = projection.obj("thread")
    if (thread.string("historyOrigin") == "v1_import" || runs.isEmpty()) return false
    val instanceId = thread.obj("modelSelection").string("instanceId")
    return projection.objects("providerThreads").none {
        it.string("id") == thread.string("activeProviderThreadId") && it.string("appThreadId") == thread.string("id") &&
            it.string("providerInstanceId") == instanceId && it["nativeThreadRef"].let { ref -> ref != null && ref is JsonObject }
    }
}

/**
 * `message.dispatch` mode for the selected thread. Like T3 mobile, a thread whose run is still
 * preparing or starting always queues; the shell row covers a projection that lags behind it.
 */
internal fun messageDispatchMode(state: ClientState): String {
    val shell = state.threads.firstOrNull { it.id == state.selectedThreadId }
    val busy = state.activeRunId != null || state.runs.any { it.status in ACTIVE_RUN_STATUSES } ||
        shell?.activityRunStatus in SETUP_STATUSES || shell?.status in SETUP_STATUSES
    return if (busy) "queue_after_active" else "start_immediately"
}

/** Provider `supportedRuntimeModes`; unknown literals are skipped (T3 `ForwardCompatibleArray`). */
internal fun supportedRuntimeModes(provider: JsonObject): List<RuntimeMode> =
    (provider["supportedRuntimeModes"] as? JsonArray)?.mapNotNull { RuntimeMode.fromWire((it as? JsonPrimitive)?.contentOrNull) }?.distinct().orEmpty()

/** T3 `compatibleRuntimeModeForChoices`: keep [mode] if the model allows it, else its first allowed mode. */
fun compatibleRuntimeMode(mode: RuntimeMode, model: ModelOption): RuntimeMode {
    val allowed = model.supportedRuntimeModes.ifEmpty { return mode }
    return if (mode in allowed) mode else RuntimeMode.entries.firstOrNull { it in allowed } ?: mode
}

/**
 * An Effect RPC failure Exit whose cause only reports interruption: the server stopped handling
 * the request, so whether it took effect is unknown. Typed failures (`Fail`) and defects (`Die`,
 * e.g. payload decoding) are definite answers.
 */
internal fun exitInterruptedOnly(exit: JsonObject): Boolean {
    // Effect 4 encodes a Cause as an ordered array of reasons; tolerate a single reason object.
    val reasons = when (val cause = exit["cause"]) {
        is JsonArray -> cause.mapNotNull { (it as? JsonObject)?.string("_tag") }
        is JsonObject -> listOf(cause.string("_tag"))
        else -> emptyList()
    }
    return reasons.isNotEmpty() && reasons.all { it == "Interrupt" }
}
