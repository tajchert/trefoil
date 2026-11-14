package com.tajchert.trefoil.widget.limits

import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.ui.formatUsageReset
import com.tajchert.trefoil.widget.UsageWidgetProvider
import java.time.Instant
import kotlin.math.floor

internal data class LimitGauge(val provider: UsageWidgetProvider, val window: UsageLimitWindow)

internal fun hasReset(window: UsageLimitWindow, now: Long): Boolean =
    runCatching { window.resetsAt?.let { Instant.parse(it).toEpochMilli() <= now } ?: false }
        .getOrDefault(false)

internal fun limitResetText(window: UsageLimitWindow, now: Long, compact: Boolean = false): String =
    if (hasReset(window, now)) "Reset since last check"
    else
        formatUsageReset(window, now)?.let { if (compact) it.removePrefix("resets in ") else it }
            ?: "Reset time unavailable"

/** Expired measurements never displace a valid constrained window. Ties retain server order. */
internal fun orderedGauges(providers: List<UsageWidgetProvider>, now: Long): List<LimitGauge> =
    providers
        .flatMap { provider -> provider.limits?.windows.orEmpty().map { LimitGauge(provider, it) } }
        .sortedWith(
            compareBy<LimitGauge> { hasReset(it.window, now) || !it.window.usedPercent.isFinite() }
                .thenBy { it.window.remainingPercent }
        )

internal fun limitNotice(provider: UsageWidgetProvider): String =
    when (provider.limits?.unavailableReason) {
        "unsupported" -> provider.limits.unavailableMessage ?: "No subscription limits"
        else -> provider.limits?.notice ?: "Limits not available"
    }

internal fun compactBarCapacity(heightDp: Float, fontScale: Float): Int =
    floor((heightDp - 16).coerceAtLeast(0f) / (30 * fontScale.coerceAtLeast(1f)))
        .toInt()
        .coerceIn(1, 9)

internal fun compactProviders(
    providers: List<UsageWidgetProvider>,
    capacity: Int,
    now: Long,
): List<UsageWidgetProvider> =
    providers
        .sortedBy { provider ->
            orderedGauges(listOf(provider), now).firstOrNull()?.let {
                if (hasReset(it.window, now) || !it.window.usedPercent.isFinite()) 101
                else it.window.remainingPercent
            } ?: 102
        }
        .take(capacity.coerceAtLeast(1))

internal data class GaugeGrid(
    val columns: Int,
    val rows: Int,
    val showHeader: Boolean,
    val detailed: Boolean,
    val showFooter: Boolean,
) {
    val capacity: Int
        get() = columns * rows

    fun visibleCount(total: Int): Int =
        if (total > capacity && capacity > 1) capacity - 1 else minOf(total, capacity)
}

internal fun gaugeGrid(widthDp: Float, heightDp: Float, fontScale: Float): GaugeGrid {
    val scale = fontScale.coerceAtLeast(1f)
    val header = widthDp >= 240 && heightDp >= 240 * scale
    val footer = !header && heightDp >= 220 * scale
    val columns = floor((widthDp - 16).coerceAtLeast(1f) / (72 * scale)).toInt().coerceIn(1, 5)
    val rows =
        floor(
                (heightDp - 16 - if (header) 52 * scale else if (footer) 18 * scale else 0f)
                    .coerceAtLeast(1f) / (92 * scale)
            )
            .toInt()
            .coerceIn(1, 8)
    return GaugeGrid(
        columns,
        rows,
        header,
        widthDp >= 144 * scale && heightDp >= 108 * scale,
        footer,
    )
}
