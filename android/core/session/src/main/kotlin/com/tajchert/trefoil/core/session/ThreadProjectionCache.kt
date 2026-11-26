package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*

/**
 * Everything the client derives from one thread projection for [ClientState]. Kept with the projection so a revisited
 * thread repaints from memory without re-parsing its JSON on the selecting (main) thread. Connection-dependent facts
 * (Steer, Stop, the catalog model) are applied when it is shown, never cached as live.
 */
internal data class ThreadPresentation(
    val messages: List<ConversationMessage>,
    val activities: List<ToolActivity>,
    val requests: List<RuntimeRequest>,
    val activeRunId: String?,
    val runs: List<ConversationRun>,
    val attempts: List<RunAttempt>,
    val subagents: List<Subagent>,
    val backgroundWork: List<PendingBackgroundTask>,
    val queuedMessages: List<QueuedMessage>,
    val queueHeld: Boolean,
    /** Steer/Stop as the projection allows them; shown only while connected. */
    val steerable: Boolean,
    val stoppable: Boolean,
    val runtimeMode: RuntimeMode?,
    val modelSelection: JsonObject?,
    val modelOptions: ModelOptionValues,
    val providerLocked: Boolean,
) {
    /** The rows this thread holds in memory: what the cache budget counts. */
    val rows: Int get() = messages.size + activities.size
}

internal class CachedThread(val projection: JsonObject, val presentation: ThreadPresentation)

/** Derives the [ClientState] slice of one projection. Pure (reads only [value]); parses each row once. Shared by the selected thread and the inspector. */
internal fun threadPresentation(value: JsonObject): ThreadPresentation {
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

/**
 * Recently viewed threads, keyed by (environment id, thread id). Memory only: nothing here is written to disk. Each
 * environment has its own budget of [maxThreads] threads and [maxRows] rows; the client keeps at most five (the open one and
 * four retained; `evictRetained` calls [forgetEnvironment] for the rest). Within an environment the most recently stored thread is always kept (it is
 * the one on screen, held in memory anyway) and older ones are evicted least recently used first. A projection is
 * already capped by the oversized-frame containment (`EffectRpc.MAX_FRAME_CHARS`), so the row budget bounds what the cache
 * adds on top of the open thread. Not thread-safe: the client guards it with its lifecycle lock.
 */
internal class ThreadProjectionCache(private val maxThreads: Int = 12, private val maxRows: Int = 6_000) {
    private val entries = linkedMapOf<Pair<String, String>, CachedThread>()

    operator fun get(key: Pair<String, String>): CachedThread? = entries[key]

    /**
     * Stores [entry] as the most recently used and trims its environment to the budget. [keep] (the selected thread, when
     * the inspector stores a child beside it) is never evicted by this call, however old it is.
     */
    fun put(key: Pair<String, String>, entry: CachedThread, keep: Pair<String, String>? = null) {
        entries.remove(key)
        entries[key] = entry
        val all = entries.keys.filter { it.first == key.first }
        var count = all.size
        var rows = all.sumOf { entries.getValue(it).presentation.rows }
        val evictable = all.filter { it != keep || it == key }.toMutableList() // eldest first; ends with [key]
        while (evictable.size > 1 && (count > maxThreads || rows > maxRows)) {
            rows -= entries.remove(evictable.removeAt(0))!!.presentation.rows
            count--
        }
    }

    fun forgetEnvironment(environmentId: String?) { entries.keys.removeAll { it.first == environmentId } }

    fun clear() = entries.clear()

    val keys: Set<Pair<String, String>> get() = entries.keys
}

/**
 * [next] with every element that equals one in [previous] (matched by [id]) replaced by that previous instance, and
 * [previous] itself when the lists are equal. A refreshed projection rebuilds every row; keeping unchanged rows' identity
 * lets Compose skip them (strong skipping compares unstable parameters by instance).
 */
internal fun <T : Any> stableRows(previous: List<T>, next: List<T>, id: (T) -> Any?): List<T> {
    if (previous === next || previous.isEmpty() || next.isEmpty()) return next
    if (previous == next) return previous
    val byId = HashMap<Any?, T>(previous.size * 2)
    previous.forEach { byId[id(it)] = it }
    var reused = false
    val merged = next.map { row -> byId[id(row)]?.takeIf { it == row }?.also { reused = true } ?: row }
    return if (reused) merged else next
}

/** [next] when it differs from [previous], else [previous]'s instance. */
internal fun <T> stableValue(previous: T, next: T): T = if (previous == next) previous else next
