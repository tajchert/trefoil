/* Presentation mapping adapted from T3 Code's orchestration V2 contracts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*

private fun JsonObject.optionalText(key: String): String? = string(key).takeIf { it.isNotBlank() }
private fun JsonObject.ordinal(): Int? = (get("ordinal") as? JsonPrimitive)?.intOrNull

internal fun providerInstances(config: JsonObject): List<ProviderInstance> = config.objects("providers").map { provider ->
    ProviderInstance(
        instanceId = provider.string("instanceId"), driver = provider.string("driver"),
        displayName = provider.optionalText("displayName"), accentColor = provider.optionalText("accentColor"),
        enabled = (provider["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
    )
}

internal fun conversationRuns(projection: JsonObject): List<ConversationRun> = projection.objects("runs").map { run ->
    ConversationRun(
        id = run.string("id"), status = run.string("status"), ordinal = run.ordinal(),
        requestedAt = run.optionalText("requestedAt"), startedAt = run.optionalText("startedAt"),
        completedAt = run.optionalText("completedAt"), workStartedAt = run.optionalText("workStartedAt"),
        activeAttemptId = run.optionalText("activeAttemptId"), userMessageId = run.optionalText("userMessageId"),
        providerInstanceId = run.optionalText("providerInstanceId"),
        modelInstanceId = run.obj("modelSelection").optionalText("instanceId") ?: run.obj("modelSelection").optionalText("provider"),
        model = run.obj("modelSelection").optionalText("model"),
    )
}

internal fun runAttempts(projection: JsonObject): List<RunAttempt> = projection.objects("attempts").map { attempt ->
    RunAttempt(
        id = attempt.string("id"), runId = attempt.string("runId"), status = attempt.string("status"),
        attemptOrdinal = (attempt["attemptOrdinal"] as? JsonPrimitive)?.intOrNull, reason = attempt.optionalText("reason"),
        startedAt = attempt.optionalText("startedAt"), completedAt = attempt.optionalText("completedAt"),
        rootNodeId = attempt.optionalText("rootNodeId"), providerInstanceId = attempt.optionalText("providerInstanceId"),
        providerTurnId = attempt.optionalText("providerTurnId"),
    )
}

internal fun threadSummary(value: JsonObject, archived: Boolean = false) = ThreadSummary(
    id = value.string("id"), projectId = value.string("projectId"), title = value.string("title"),
    status = value.optionalText("status") ?: value.obj("runSummary").optionalText("status") ?: "idle",
    createdAt = value.optionalText("createdAt"), updatedAt = value.optionalText("updatedAt"),
    latestUserMessageAt = value.optionalText("latestUserMessageAt"),
    latestUserAuthoredMessageAt = value.optionalText("latestUserAuthoredMessageAt"),
    archivedAt = value.optionalText("archivedAt"), archived = archived || value.optionalText("archivedAt") != null,
    pinnedAt = value.optionalText("pinnedAt"), settledAt = value.optionalText("settledAt"),
    settledOverride = value.optionalText("settledOverride"), activeOrderKey = value.optionalText("activeOrderKey"),
    pinOrderKey = value.optionalText("pinOrderKey"),
    pendingRequestKind = value.obj("pendingRuntimeRequest").optionalText("kind"),
    latestMessage = value.obj("latestVisibleMessage").string("text"),
    branch = value.optionalText("branch"), model = value.obj("modelSelection").optionalText("model"),
    blockedOnThreadWait = (value["blockedOnThreadWait"] as? JsonPrimitive)?.booleanOrNull,
    activityRunStatus = value.optionalText("activityRunStatus"),
    snoozedUntil = value.optionalText("snoozedUntil"), unsettledAt = value.optionalText("unsettledAt"),
    latestRunRequestedAt = value.optionalText("latestRunRequestedAt"),
    latestRunCompletedAt = value.optionalText("latestRunCompletedAt"),
    latestRunStartedAt = value.optionalText("latestRunStartedAt"),
    activityRunStartedAt = value.optionalText("activityRunStartedAt"),
    activityRunStartedAtPresent = "activityRunStartedAt" in value,
    latestRunId = value.optionalText("latestRunId"), activeRunId = value.optionalText("activeRunId"),
    providerInstanceId = value.optionalText("providerInstanceId"),
    modelInstanceId = value.obj("modelSelection").optionalText("instanceId") ?: value.obj("modelSelection").optionalText("provider"),
    providerInstanceHistory = (value["providerInstanceHistory"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) },
)

/** Projected position spans inherited and local items; native ordinals do not. */
internal fun presentationItems(projection: JsonObject): List<JsonObject> {
    if (projection["visibleTurnItems"] !is JsonArray) return projection.objects("turnItems")
    return projection.objects("visibleTurnItems")
        .sortedBy { (it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
        .mapNotNull { row ->
            val item = row["item"] as? JsonObject ?: return@mapNotNull null
            JsonObject(item + listOfNotNull(
                row["position"]?.let { "ordinal" to it },
                row["sourceThreadId"]?.let { "sourceThreadId" to it },
                row["sourceItemId"]?.let { "sourceItemId" to it },
                item["id"]?.let { "id" to it } ?: row["sourceItemId"]?.let { "id" to it },
            ).toMap())
        }
}

/** Replace only the fetched body; position, execution identity and current lifecycle remain authoritative. */
internal fun hydrateActivityProjection(projection: JsonObject, itemId: String, fullItem: JsonObject): JsonObject {
    val metadata = setOf("id", "threadId", "runId", "nodeId", "providerThreadId", "providerTurnId", "nativeItemRef",
        "parentItemId", "ordinal", "status", "title", "startedAt", "completedAt", "updatedAt", "sourceThreadId", "sourceItemId")
    fun hydrate(item: JsonObject) = JsonObject(item + fullItem.filterKeys { it !in metadata } + mapOf("outputOmitted" to JsonPrimitive(false), "_trefoilDetailsLoaded" to JsonPrimitive(true)))
    return JsonObject(projection + buildMap {
        (projection["visibleTurnItems"] as? JsonArray)?.let { rows ->
            put("visibleTurnItems", JsonArray(rows.map { element ->
                val row = element as? JsonObject ?: return@map element
                val item = row["item"] as? JsonObject ?: return@map row
                if (item.string("id") == itemId) JsonObject(row + ("item" to hydrate(item))) else row
            }))
        }
        (projection["turnItems"] as? JsonArray)?.let { items ->
            put("turnItems", JsonArray(items.map { element ->
                val item = element as? JsonObject ?: return@map element
                if (item.string("id") == itemId) hydrate(item) else item
            }))
        }
    })
}

/** File bodies are stripped without a wire omission flag; a local marker remembers explicit reads. */
internal fun fileDetailsOmitted(item: JsonObject): Boolean = item.string("type") == "file_change" &&
    (item["_trefoilDetailsLoaded"] as? JsonPrimitive)?.booleanOrNull != true &&
    listOf("diffStr", "oldStr", "newStr").none { item[it] is JsonPrimitive && item[it] != JsonNull } &&
    item.objects("changes").none { change -> listOf("diffStr", "oldStr", "newStr").any { change[it] is JsonPrimitive && change[it] != JsonNull } }

/** Keep fetched bodies while the server repeats the same stripped revision, never after an update. */
internal fun retainHydratedActivities(incoming: JsonObject, previous: JsonObject): JsonObject {
    var next = incoming
    val incomingItems = presentationItems(incoming).associateBy { it.string("id") }
    val identity = listOf("type", "sourceThreadId", "sourceItemId", "runId", "nodeId", "providerTurnId", "status", "startedAt", "completedAt", "updatedAt", "input", "fileName")
    presentationItems(previous).filter { (it["_trefoilDetailsLoaded"] as? JsonPrimitive)?.booleanOrNull == true }.forEach { old ->
        val current = incomingItems[old.string("id")]
        if (current != null && identity.all { old[it] == current[it] } &&
            ((current["outputOmitted"] as? JsonPrimitive)?.booleanOrNull == true || fileDetailsOmitted(current))) {
            next = hydrateActivityProjection(next, current.string("id"), old)
        }
    }
    return next
}

/** Match mobile's node ancestry resolution; native provider turns also carry an explicit attempt link. */
private fun attemptResolver(projection: JsonObject): (JsonObject) -> String? {
    val attempts = projection.objects("attempts")
    val byRootNode = attempts.filter { it.optionalText("rootNodeId") != null }.associateBy { it.string("rootNodeId") }
    val byId = attempts.associateBy { it.string("id") }
    val nodes = projection.objects("nodes").associateBy { it.string("id") }
    val turns = projection.objects("providerTurns").associateBy { it.string("id") }
    return resolve@{ item ->
        val runId = item.optionalText("runId") ?: return@resolve null
        var nodeId = item.optionalText("nodeId")
        val visited = mutableSetOf<String>()
        while (nodeId != null && visited.add(nodeId)) {
            byRootNode[nodeId]?.takeIf { it.string("runId") == runId }?.let { return@resolve it.optionalText("id") }
            val node = nodes[nodeId] ?: break
            byRootNode[node.string("rootNodeId")]?.takeIf { it.string("runId") == runId }
                ?.let { return@resolve it.optionalText("id") }
            nodeId = node.optionalText("parentNodeId")
        }
        val attemptId = turns[item.string("providerTurnId")]?.optionalText("runAttemptId")
        attemptId?.takeIf { byId[it]?.string("runId") == runId }
    }
}

internal fun conversationMessages(projection: JsonObject): List<ConversationMessage> {
    val items = presentationItems(projection)
    val resolveAttempt = attemptResolver(projection)
    val messageItems = items.filter { it.optionalText("messageId") != null }.associateBy { it.string("messageId") }
    val messages = projection.objects("messages")
    val orderedMessages = if (projection["visibleTurnItems"] is JsonArray) {
        val byId = messages.associateBy { it.string("id") }
        items.filter { it.string("type") in setOf("user_message", "assistant_message") }.map { item ->
            byId[item.string("messageId")] ?: buildJsonObject {
                put("id", item.string("messageId").ifBlank { item.string("id") })
                put("role", if (item.string("type") == "user_message") "user" else "assistant")
                put("text", item.string("text"))
                item["streaming"]?.let { put("streaming", it) }
                item["startedAt"]?.let { put("createdAt", it) }
                item["updatedAt"]?.let { put("updatedAt", it) }
                item["runId"]?.let { put("runId", it) }
                item["attachments"]?.let { put("attachments", it) }
            }
        }
    } else messages
    return orderedMessages.map { message ->
        val item = messageItems[message.string("id")]
        ConversationMessage(
            id = message.string("id"), role = message.string("role"), text = message.string("text"),
            streaming = (message["streaming"] as? JsonPrimitive)?.booleanOrNull == true,
            createdAt = message.optionalText("createdAt"), updatedAt = message.optionalText("updatedAt"),
            runId = message.optionalText("runId") ?: item?.optionalText("runId"), ordinal = item?.ordinal(),
            nodeId = item?.optionalText("nodeId"), providerTurnId = item?.optionalText("providerTurnId"),
            runAttemptId = item?.let(resolveAttempt),
            attachments = ((message["attachments"] as? JsonArray) ?: (item?.get("attachments") as? JsonArray)).orEmpty().mapNotNull { element ->
                val attachment = element as? JsonObject ?: return@mapNotNull null
                val id = attachment.optionalText("id") ?: return@mapNotNull null
                val name = attachment.optionalText("name") ?: return@mapNotNull null
                RemoteFileReference(attachmentId = id, fileName = name, mimeType = attachment.optionalText("mimeType"), sizeBytes = (attachment["sizeBytes"] as? JsonPrimitive)?.longOrNull)
            },
        )
    }
}

/** Dynamic tools permit arbitrary JSON values instead of strings. */
private fun JsonObject.detailText(key: String): String? = when (val value = get(key)) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    else -> value.toString()
}

internal fun toolActivities(projection: JsonObject): List<ToolActivity> {
    val resolveAttempt = attemptResolver(projection)
    return presentationItems(projection)
    .filter { it.string("type") !in setOf("message", "user_message", "assistant_message", "user_input_request", "approval_request") }
    .map { item ->
        val kind = item.string("type")
        val primary = listOf("input", "text", "markdown", "fileName", "pattern", "patterns", "message", "reason")
            .firstNotNullOfOrNull { item.detailText(it) }.orEmpty()
        val output = item.detailText("output")
        val failure = (item["failure"] as? JsonObject)?.optionalText("message") ?: item.detailText("failure")
        val diff = item.detailText("diffStr") ?: listOfNotNull(item.detailText("oldStr"), item.detailText("newStr"))
            .joinToString("\n\n").takeIf { it.isNotBlank() }
        ToolActivity(
            id = item.string("id"), title = item.optionalText("title") ?: item.optionalText("toolName") ?: kind.replace('_', ' '),
            detail = listOfNotNull(primary.takeIf { it.isNotBlank() }, output, diff, item.detailText("changes"),
                item.detailText("results"), failure).distinct().joinToString("\n\n"),
            kind = kind, status = item.string("status"), startedAt = item.optionalText("startedAt"),
            completedAt = item.optionalText("completedAt"), runId = item.optionalText("runId"), ordinal = item.ordinal(),
            runAttemptId = resolveAttempt(item), nodeId = item.optionalText("nodeId"), providerTurnId = item.optionalText("providerTurnId"),
            parentItemId = item.optionalText("parentItemId"),
            input = item.detailText("input") ?: item.detailText("pattern") ?: item.detailText("patterns"),
            output = output, exitCode = (item["exitCode"] as? JsonPrimitive)?.intOrNull,
            outputIndicatesFailure = (item["outputIndicatesFailure"] as? JsonPrimitive)?.booleanOrNull,
            outputOmitted = (item["outputOmitted"] as? JsonPrimitive)?.booleanOrNull == true,
            detailsOmitted = fileDetailsOmitted(item),
            fileName = item.optionalText("fileName"), diffStr = item.detailText("diffStr") ?: item.objects("changes").mapNotNull { it.detailText("diffStr") }.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            oldStr = item.detailText("oldStr"), newStr = item.detailText("newStr"),
            searchResults = item.detailText("results"), failure = failure,
            sourceThreadId = item.optionalText("sourceThreadId"), sourceItemId = item.optionalText("sourceItemId"),
        )
    }
}
