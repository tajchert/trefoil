package com.tajchert.trefoil.core.model

// Shapes adapted from T3 Code `packages/contracts/src/{usage,providerUsageLimits}.ts`
// at 46449b5379a0089f4ef3ce85bce10a1d28beb4cb (MIT, see protocol/T3-LICENSE.txt).

/** Usage page periods. [days] counts today; Today = 1. */
enum class UsageRange(val label: String, val days: Int) {
    Today("Today", 1), Week("7 days", 7), Month("30 days", 30),
}

/** T3 `UsageTokenTotals`: input = uncached + cache reads + cache writes; reasoning is already inside output. */
data class UsageTokens(
    val uncachedInput: Long = 0,
    val cachedInput: Long = 0,
    val cacheCreation: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
) {
    val input: Long get() = uncachedInput + cachedInput + cacheCreation
    val total: Long get() = input + output
    operator fun plus(other: UsageTokens) = UsageTokens(
        uncachedInput + other.uncachedInput, cachedInput + other.cachedInput,
        cacheCreation + other.cacheCreation, output + other.output, reasoning + other.reasoning,
    )
}

/** One provider (when [model] is null) or provider+model row, summed over the period. */
data class UsageBreakdown(
    /** T3 `UsageProviderKind` wire literal, e.g. "claude", "codex"; unknown literals are kept. */
    val provider: String,
    val model: String? = null,
    val costUsd: Double = 0.0,
    val tokens: UsageTokens = UsageTokens(),
    /** Records that had no price; their cost is missing from [costUsd]. */
    val unpricedRecords: Int = 0,
    val records: Int = 0,
)

/** One local calendar day (`yyyy-MM-dd` in [UsageReport.timeZone]). Days without usage are present with zeros. */
data class UsageDayTotal(val day: String, val costUsd: Double = 0.0, val tokens: Long = 0)

/** T3 `UsageSource` reduced to what the UI explains; [status] is "ok" | "missing" | "partial" | "failed" (unknown kept). */
data class UsageSourceState(val provider: String, val status: String, val message: String? = null)

/** Historical cost/token usage of one environment for one period (`server.getUsageSummary`). */
data class UsageReport(
    val environmentId: String,
    val range: UsageRange,
    val sinceDay: String,
    val untilDay: String,
    val timeZone: String,
    /** Server `readAt` ISO timestamp. */
    val readAt: String,
    val totalCostUsd: Double = 0.0,
    val tokens: UsageTokens = UsageTokens(),
    val cacheSavingsUsd: Double = 0.0,
    /** Sorted by cost desc, then tokens desc. */
    val providers: List<UsageBreakdown> = emptyList(),
    /** Sorted by cost desc, then tokens desc. */
    val models: List<UsageBreakdown> = emptyList(),
    /** Ascending, one entry per day sinceDay..untilDay. */
    val days: List<UsageDayTotal> = emptyList(),
    val unpricedRecords: Int = 0,
    /** T3 `UsagePricing.status`: "fresh" | "cached" | "unavailable". */
    val pricingStatus: String = "unavailable",
    val sources: List<UsageSourceState> = emptyList(),
    /** True when any source answered from its last scan and a fresh scan is still running (provisional answer). */
    val refreshing: Boolean = false,
)

/** T3 `ServerProviderUsageWindow`; [kind] is "session" | "weekly" | "monthly" | "other" (unknown kept). */
data class UsageLimitWindow(
    val id: String,
    val kind: String,
    val label: String,
    val usedPercent: Double,
    val resetsAt: String? = null,
    val windowDurationMins: Int? = null,
) {
    /** T3 `remainingPercent`: round(100 - clamp(usedPercent, 0, 100)). */
    val remainingPercent: Int get() = Math.round(100 - usedPercent.coerceIn(0.0, 100.0)).toInt()
}

/** T3 `ServerProviderUsageLimits` for one provider instance (from `ServerProvider.usageLimits`). */
data class ProviderUsageLimits(
    val checkedAt: String,
    val windows: List<UsageLimitWindow> = emptyList(),
    /** "unsupported" | "probeFailed" | null. */
    val unavailableReason: String? = null,
    val unavailableMessage: String? = null,
) {
    /** T3 `limitsNotice`. */
    val notice: String? get() = when (unavailableReason) {
        "unsupported" -> unavailableMessage ?: "This account has no subscription limits."
        "probeFailed" -> unavailableMessage ?: "Could not read limits."
        else -> if (windows.isEmpty()) "No limits reported." else null
    }
}

enum class UsageFailure { NotConnected, NoPermission, Unsupported, Failed, TimedOut }

/** A usage read failed. Reads are never mutations: failures never disconnect the session. */
class UsageException(val reason: UsageFailure, message: String) : IllegalStateException(message)
