/* Rules adapted from T3 Code client-runtime state/models.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ThreadSummary
import java.time.Instant

internal fun validThreadInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
internal fun threadWorkStartedAt(thread: ThreadSummary): String? {
    fun valid(value: String?) = value?.takeIf { validThreadInstant(it) != null }
    if (thread.activityRunStartedAtPresent) return valid(thread.activityRunStartedAt)
    if (thread.latestRunCompletedAt == null && thread.latestRunId != null && thread.latestRunId == thread.activeRunId)
        return valid(thread.latestRunStartedAt) ?: valid(thread.latestRunRequestedAt)
    return null
}
internal fun formatWorkDuration(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return when { seconds < 60 -> "${seconds}s"; minutes < 60 -> "${minutes}m"; else -> "${minutes / 60}h ${minutes % 60}m" }
}
internal fun threadStatusLabel(thread: ThreadSummary, now: Long): String {
    val status = threadIndicator(thread)
    val anchor = when (status.kind) {
        "working" -> threadWorkStartedAt(thread)
        "waiting" -> thread.latestRunCompletedAt
        else -> null
    }
    val elapsed = validThreadInstant(anchor)?.let { " ${formatWorkDuration(now - it)}" }.orEmpty()
    return status.label + elapsed
}
internal fun threadProviders(thread: ThreadSummary, catalog: List<ProviderInstance>): List<ProviderInstance> {
    val currentId = thread.providerInstanceId ?: thread.modelInstanceId ?: return emptyList()
    val current = catalog.firstOrNull { it.instanceId == currentId } ?: return emptyList()
    val previous = thread.providerInstanceHistory.filter { it != currentId }.takeLast(2)
    return previous.mapNotNull { id -> catalog.firstOrNull { it.instanceId == id } } + current
}
internal fun providerDisplayName(provider: ProviderInstance): String {
    val kind = when (provider.driver) { "codex" -> "Codex"; "claudeAgent" -> "Claude"; "opencode" -> "OpenCode"; "cursor" -> "Cursor"; "grok" -> "Grok"; else -> humanizeProviderSlug(provider.driver) }
    val supplied = provider.displayName?.trim()?.takeIf { it.isNotEmpty() }
    if (supplied != null && supplied != kind) return supplied
    if (provider.instanceId != provider.driver) return humanizeProviderSlug(provider.instanceId).ifBlank { supplied ?: kind }
    return supplied ?: kind
}
private fun humanizeProviderSlug(value: String): String = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[_-]+"), " ").trim()
    .split(Regex("\\s+")).joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
internal fun providerInitials(label: String): String {
    val words = label.replace(Regex("[^\\p{L}\\p{N}'’]+"), " ").split(Regex("\\s+"))
        .map { it.trim('\'', '’') }.filter { it.isNotEmpty() }
    return if (words.size <= 1) words.firstOrNull().orEmpty().take(2).uppercase(java.util.Locale.ROOT)
    else words.take(2).joinToString("") { it.take(1) }.uppercase(java.util.Locale.ROOT)
}

/** One foreground clock per list; snoozes wake at their deadline, not at the next minute. */
internal fun nextThreadClockDelay(now: Long, liveTiming: Boolean, snoozeDeadlines: List<Long>): Long {
    val interval = if (liveTiming) 1000L else 60_000L
    val boundary = interval - Math.floorMod(now, interval)
    val wake = snoozeDeadlines.filter { it > now }.minOrNull()?.let { it - now } ?: boundary
    return minOf(boundary, wake).coerceAtLeast(1)
}
