package com.tajchert.trefoil.widget.usage

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.formatUsageTokens
import com.tajchert.trefoil.ui.formatUsageUsd
import kotlin.math.floor

/** Conservative single-line estimate; numbers stay legible as columns and system fonts change. */
internal fun usageNumberFont(text: String, width: Float, fontScale: Float, maxSp: Int = 22): Int =
    floor(width / (text.length.coerceAtLeast(1) * 0.65f * fontScale.coerceAtLeast(1f)))
        .toInt()
        .coerceIn(12, maxSp)

internal enum class UsageMetric {
    Cost,
    Tokens;

    fun value(row: UsageBreakdown): Double =
        if (this == Cost) row.costUsd else row.tokens.total.toDouble()

    fun value(day: UsageDayTotal): Double = if (this == Cost) day.costUsd else day.tokens.toDouble()

    fun total(report: UsageReport): Double =
        if (this == Cost) report.totalCostUsd else report.tokens.total.toDouble()

    fun format(value: Double): String =
        if (this == Cost) formatUsageUsd(value) else formatUsageTokens(value.toLong())
}

internal enum class UsageGrouping {
    Models,
    Providers,
}

internal object UsageWidgetKeys {
    val range = stringPreferencesKey("usage_range")
    val metric = stringPreferencesKey("usage_metric")
    val grouping = stringPreferencesKey("usage_grouping")

    fun range(prefs: Preferences): UsageRange =
        UsageRange.entries.firstOrNull { it.name == prefs[range] } ?: UsageRange.Week

    fun metric(prefs: Preferences): UsageMetric =
        UsageMetric.entries.firstOrNull { it.name == prefs[metric] } ?: UsageMetric.Cost

    fun grouping(prefs: Preferences): UsageGrouping =
        UsageGrouping.entries.firstOrNull { it.name == prefs[grouping] } ?: UsageGrouping.Models
}

internal fun selectUsagePreference(prefs: MutablePreferences, field: String, value: String) {
    when (field) {
        "range" ->
            if (UsageRange.entries.any { it.name == value }) prefs[UsageWidgetKeys.range] = value
        "metric" ->
            if (UsageMetric.entries.any { it.name == value }) prefs[UsageWidgetKeys.metric] = value
        "grouping" ->
            if (UsageGrouping.entries.any { it.name == value })
                prefs[UsageWidgetKeys.grouping] = value
        "cycle" ->
            prefs[UsageWidgetKeys.range] =
                UsageRange.entries.let { ranges ->
                    ranges[(ranges.indexOf(UsageWidgetKeys.range(prefs)) + 1) % ranges.size].name
                }
    }
}

internal fun sortedUsage(
    report: UsageReport,
    metric: UsageMetric,
    grouping: UsageGrouping,
): List<UsageBreakdown> =
    (if (grouping == UsageGrouping.Models) report.models else report.providers).sortedWith(
        compareByDescending<UsageBreakdown> { metric.value(it) }
            .thenBy { it.provider }
            .thenBy { it.model.orEmpty() }
    )

/** FNV-1a gives collection rows a stable identity independent of ordering and selection. */
internal fun usageItemId(row: UsageBreakdown): Long =
    (row.provider + "\u0000" + row.model.orEmpty())
        .fold(-3750763034362895579L) { hash, char ->
            (hash xor char.code.toLong()) * 1099511628211L
        }
        .and(Long.MAX_VALUE)

internal fun barHeight(value: Double, max: Double, available: Float): Float {
    val height = available.coerceAtLeast(1f)
    if (!value.isFinite() || !max.isFinite() || value <= 0 || max <= 0) return 1f
    return (value / max).coerceIn(0.0, 1.0).toFloat().times(height).coerceIn(1f, height)
}

internal data class UsageFit(val width: Float, val height: Float, val fontScale: Float) {
    private val scale = fontScale.coerceAtLeast(1f)
    val summaryColumns: Boolean
        get() = width >= 210 * scale && height >= 140 * scale

    val expanded: Boolean
        get() = width >= 340 * scale && height >= 250 * scale

    val tight: Boolean
        get() = height < 100 * scale

    val footer: Boolean
        get() = height >= 180 * scale

    val controls: Boolean
        get() = width >= 280 * scale && height >= 230 * scale

    val chartDays: Int
        get() = if (width >= 300 * scale) 30 else 7

    val barLabels: Boolean
        get() = width / 7 >= 30 * scale && height >= 150 * scale

    fun chartHeight(hasFooter: Boolean, labels: Boolean): Float {
        val reserved =
            (if (tight) 8f else 24f) +
                (27f +
                    (if (tight) 0f else 47f) +
                    (if (controls) 36f else 0f) +
                    (if (hasFooter) 16f else 0f) +
                    (if (labels) 16f else 0f)) * scale +
                4f
        return (height - reserved).coerceIn(1f, 160f)
    }

    fun rows(reserved: Float): Int =
        floor((height - reserved) / (44 * scale)).toInt().coerceAtLeast(1)
}
