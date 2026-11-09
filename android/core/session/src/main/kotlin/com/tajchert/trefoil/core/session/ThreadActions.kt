/* Semantics adapted from T3 Code client-runtime (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun threadActionCommand(target: ThreadTarget, action: ThreadAction, value: String?, commandId: String): JsonObject = buildJsonObject {
    put("commandId", commandId)
    put("threadId", target.threadId)
    put("type", when (action) {
        ThreadAction.Settle -> "thread.settle"
        ThreadAction.Unsettle -> "thread.unsettle"
        ThreadAction.Pin -> "thread.pin"
        ThreadAction.Unpin -> "thread.unpin"
        ThreadAction.Rename -> "thread.metadata.update"
        ThreadAction.Snooze -> "thread.snooze"
        ThreadAction.Wake -> "thread.unsnooze"
    })
    when (action) {
        ThreadAction.Unsettle, ThreadAction.Wake -> put("reason", "user")
        ThreadAction.Pin -> value?.let { put("orderKey", it) }
        ThreadAction.Rename -> { require(!value.isNullOrBlank()) { "Enter a title" }; put("title", value.trim()) }
        ThreadAction.Snooze -> {
            require(Instant.parse(value).isAfter(Instant.now())) { "Choose a future time" }
            put("snoozedUntil", value)
        }
        else -> Unit
    }
}

internal fun settlementUndoSteps(row: ThreadSummary): List<Pair<ThreadAction, String?>> = buildList {
    add(ThreadAction.Unsettle to null)
    if (row.pinnedAt != null) add(ThreadAction.Pin to row.pinOrderKey)
    row.snoozedUntil?.let { add(ThreadAction.Snooze to it) }
}

fun canSnoozeThread(row: ThreadSummary, now: Instant = Instant.now()): Boolean {
    if (row.pendingRequestKind != null && row.pendingRequestKind != "user_input") return false
    if (row.pendingChildRequestKind != null || row.pendingGuestMessageCount > 0) return false
    if ((row.activityRunStatus ?: row.status) in setOf("preparing", "queued", "starting")) return false
    val message = runCatching { Instant.parse(row.latestUserMessageAt) }.getOrNull() ?: return true
    if (row.status in setOf("error", "failed") || kotlin.math.abs(now.toEpochMilli() - message.toEpochMilli()) > 120_000) return true
    return listOf(row.latestRunRequestedAt, row.latestRunStartedAt, row.latestRunCompletedAt).any {
        runCatching { !Instant.parse(it).isBefore(message) }.getOrDefault(false)
    }
}
