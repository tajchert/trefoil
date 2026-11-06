/* Presentation mapping adapted from T3 Code's orchestration V2 contracts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.ToolActivity
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*

private fun JsonObject.optionalText(key: String): String? = string(key).takeIf { it.isNotBlank() }
private fun JsonObject.ordinal(): Int? = (get("ordinal") as? JsonPrimitive)?.intOrNull

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
                item["id"]?.let { "id" to it } ?: row["sourceItemId"]?.let { "id" to it },
            ).toMap())
        }
}

internal fun conversationMessages(projection: JsonObject): List<ConversationMessage> {
    val items = presentationItems(projection)
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
        )
    }
}

/** Dynamic tools permit arbitrary JSON values instead of strings. */
private fun JsonObject.detailText(key: String): String? = when (val value = get(key)) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
    else -> value.toString()
}

internal fun toolActivities(projection: JsonObject): List<ToolActivity> = presentationItems(projection)
    .filter { it.string("type") !in setOf("message", "user_message", "assistant_message", "user_input_request", "approval_request") }
    .map { item ->
        val kind = item.string("type")
        val primary = listOf("input", "text", "markdown", "fileName", "pattern").firstNotNullOfOrNull { item.detailText(it) }.orEmpty()
        val output = item.detailText("output")
        ToolActivity(
            id = item.string("id"), title = item.optionalText("title") ?: item.optionalText("toolName") ?: kind.replace('_', ' '),
            detail = listOfNotNull(primary.takeIf { it.isNotBlank() }, output?.takeIf { it != primary }).joinToString("\n\n"),
            kind = kind, status = item.string("status"), startedAt = item.optionalText("startedAt"),
            completedAt = item.optionalText("completedAt"), runId = item.optionalText("runId"), ordinal = item.ordinal(),
        )
    }
