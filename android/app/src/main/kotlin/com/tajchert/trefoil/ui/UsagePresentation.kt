package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import java.time.Instant
import java.util.Locale

internal fun formatUsageUsd(value: Double): String = when {
    value == 0.0 -> "$0"
    value > 0 && value < 0.01 -> "<$0.01"
    else -> String.format(Locale.US, "$%.2f", value)
}

internal fun formatUsageTokens(value: Long): String {
    val (divisor, suffix) = when {
        value >= 1_000_000_000 -> 1_000_000_000.0 to "B"
        value >= 1_000_000 -> 1_000_000.0 to "M"
        value >= 1_000 -> 1_000.0 to "K"
        else -> return value.toString()
    }
    return String.format(Locale.US, "%.1f%s", value / divisor, suffix).replace(".0$suffix", suffix)
}

// Adapted from T3 Code packages/shared/src/usageLimits.ts (MIT; see protocol/T3-LICENSE.txt).
private fun resetMillis(window: UsageLimitWindow): Long? = try {
    window.resetsAt?.let { Instant.parse(it).toEpochMilli() }
} catch (_: Exception) { null }

internal fun usageElapsedShare(window: UsageLimitWindow, now: Long): Double? {
    val reset = resetMillis(window) ?: return null
    val length = (window.windowDurationMins ?: return null) * 60_000.0
    if (length <= 0) return null
    return ((length - (reset - now)) / length).coerceIn(0.0, 1.0)
}

internal fun usagePace(window: UsageLimitWindow, now: Long): String? {
    val elapsed = usageElapsedShare(window, now) ?: return null
    val gap = window.usedPercent - elapsed * 100
    return when { gap > 5 -> "ahead of pace"; gap < -5 -> "under pace"; else -> "on pace" }
}

internal fun formatUsageDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    val days = minutes / 1440
    val hours = minutes % 1440 / 60
    return when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${minutes % 60}m"; else -> "${minutes}m" }
}

internal fun formatUsageReset(window: UsageLimitWindow, now: Long): String? {
    val reset = resetMillis(window) ?: return null
    return when { reset <= now -> "resets now"; reset - now < 60_000 -> "Resets momentarily"; else -> "resets in ${formatUsageDuration(reset - now)}" }
}

/** Usage page state owned by the ViewModel. [reports] keeps the last answer per range so switching back is instant. */
data class UsageUiState(
    val range: UsageRange = UsageRange.Week,
    val reports: Map<UsageRange, UsageReport> = emptyMap(),
    /** No report yet for [range] and a request is in flight. */
    val loading: Boolean = false,
    /** A report is shown but a fresher one (awaitRefresh) is being fetched. */
    val refreshing: Boolean = false,
    val failure: UsageFailure? = null,
    val failureMessage: String? = null,
    val limitsRefreshing: Boolean = false,
    /** Last limits refresh failed; the last good limits stay visible. */
    val limitsError: String? = null,
) {
    val report: UsageReport? get() = reports[range]
}

/**
 * One line for the connected machine card: lowest remaining window per enabled provider with limits,
 * e.g. "Claude 62% left · Codex 80% left"; "Usage" when no provider reports windows.
 */
internal fun usageSummaryLine(providers: List<ProviderInstance>): String = providers.mapNotNull { provider ->
    val remaining = provider.usageLimits?.windows?.minOfOrNull { it.remainingPercent }
    if (!provider.enabled || remaining == null) null
    else "${provider.displayName ?: provider.driver.replaceFirstChar { it.titlecase(Locale.ROOT) }} $remaining% left"
}.joinToString(" · ").ifEmpty { "Usage" }

internal fun usageProviderLabel(driver: String): String = driver.replaceFirstChar { it.titlecase(Locale.ROOT) }

internal fun usageShare(value: Double, total: Double): Float =
    if (!value.isFinite() || !total.isFinite() || total <= 0) 0f else (value / total).coerceIn(0.0, 1.0).toFloat()

internal fun usageCheckedAgo(checkedAt: String, now: Long): String? = try {
    val minutes = ((now - Instant.parse(checkedAt).toEpochMilli()).coerceAtLeast(0) / 60_000)
    "Checked $minutes min ago"
} catch (_: Exception) { null }
