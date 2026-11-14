package com.tajchert.trefoil.widget.usage

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.widget.UsageWidgetSamples
import org.junit.Assert.*
import org.junit.Test

class UsageWidgetLogicTest {
    @Test
    fun numberFontsFitNarrowColumnsAndLargeSystemFonts() {
        assertEquals(22, usageNumberFont("$3", 62f, 1f))
        assertTrue(usageNumberFont("$175.23", 62f, 1f) < 22)
        assertTrue(usageNumberFont("$175.23", 120f, 2f) < usageNumberFont("$175.23", 120f, 1f))
        assertEquals(12, usageNumberFont("$123456789.00", 10f, 2f))
    }

    @Test
    fun metricSortUsesTokensRatherThanServerCostOrder() {
        val expensive = UsageBreakdown("codex", "expensive", 20.0, UsageTokens(output = 1))
        val busy = UsageBreakdown("claude", "busy", 1.0, UsageTokens(output = 100))
        val report =
            UsageWidgetSamples.report(UsageRange.Week).copy(models = listOf(expensive, busy))
        assertEquals(
            listOf(expensive, busy),
            sortedUsage(report, UsageMetric.Cost, UsageGrouping.Models),
        )
        assertEquals(
            listOf(busy, expensive),
            sortedUsage(report, UsageMetric.Tokens, UsageGrouping.Models),
        )
        assertEquals(
            report.providers.toSet(),
            sortedUsage(report, UsageMetric.Tokens, UsageGrouping.Providers).toSet(),
        )
    }

    @Test
    fun stableIdsIncludeProviderAndModelAndSurviveValueChanges() {
        val row = UsageBreakdown("codex", "same-model")
        assertEquals(usageItemId(row), usageItemId(row.copy(costUsd = 100.0, records = 20)))
        assertNotEquals(usageItemId(row), usageItemId(row.copy(provider = "claude")))
        assertNotEquals(usageItemId(row), usageItemId(row.copy(model = null)))
    }

    @Test
    fun barsUseThinBaselineAndClampBadValues() {
        assertEquals(1f, barHeight(0.0, 0.0, 100f))
        assertEquals(1f, barHeight(Double.NaN, 1.0, 100f))
        assertEquals(1f, barHeight(-1.0, 1.0, 100f))
        assertEquals(1f, barHeight(1.0, Double.POSITIVE_INFINITY, 100f))
        assertEquals(50f, barHeight(5.0, 10.0, 100f))
        assertEquals(100f, barHeight(20.0, 10.0, 100f))
        assertEquals(1f, barHeight(20.0, 10.0, -1f))
    }

    @Test
    fun fontScaleReducesRowsAndHidesControlsAndDenseBars() {
        val normal = UsageFit(360f, 280f, 1f)
        val large = normal.copy(fontScale = 2f)
        assertTrue(normal.controls)
        assertTrue(normal.expanded)
        assertEquals(30, normal.chartDays)
        assertFalse(large.controls)
        assertFalse(large.expanded)
        assertEquals(7, large.chartDays)
        assertTrue(normal.rows(60f) > large.rows(60f))
        assertEquals(1, UsageFit(100f, 20f, 2f).rows(100f))
        assertTrue(UsageFit(210f, 140f, 1f).summaryColumns)
        assertFalse(UsageFit(209f, 140f, 1f).summaryColumns)
        assertTrue(normal.chartHeight(true, true) > large.chartHeight(true, true))
        assertEquals(1f, UsageFit(100f, 20f, 2f).chartHeight(true, true))
    }

    @Test
    fun preferencesHaveSafeDefaultsAndIndependentSelections() {
        val defaults = emptyPreferences()
        assertEquals(UsageRange.Week, UsageWidgetKeys.range(defaults))
        assertEquals(UsageMetric.Cost, UsageWidgetKeys.metric(defaults))
        assertEquals(UsageGrouping.Models, UsageWidgetKeys.grouping(defaults))
        val selected =
            mutablePreferencesOf(
                UsageWidgetKeys.range to "Month",
                UsageWidgetKeys.metric to "Tokens",
                UsageWidgetKeys.grouping to "Providers",
            )
        assertEquals(UsageRange.Month, UsageWidgetKeys.range(selected))
        assertEquals(UsageMetric.Tokens, UsageWidgetKeys.metric(selected))
        assertEquals(UsageGrouping.Providers, UsageWidgetKeys.grouping(selected))
        selected[UsageWidgetKeys.metric] = "future"
        assertEquals(UsageMetric.Cost, UsageWidgetKeys.metric(selected))
        assertEquals(UsageRange.Week, UsageWidgetKeys.range(defaults))
    }

    @Test
    fun actionsCycleAndValidateValuesWithoutChangingOtherInstances() {
        val first = mutablePreferencesOf()
        val second = mutablePreferencesOf()
        selectUsagePreference(first, "cycle", "next")
        assertEquals(UsageRange.Month, UsageWidgetKeys.range(first))
        selectUsagePreference(first, "cycle", "next")
        assertEquals(UsageRange.Today, UsageWidgetKeys.range(first))
        selectUsagePreference(first, "cycle", "next")
        assertEquals(UsageRange.Week, UsageWidgetKeys.range(first))
        selectUsagePreference(first, "metric", "Tokens")
        selectUsagePreference(first, "grouping", "Providers")
        selectUsagePreference(first, "range", "Month")
        selectUsagePreference(first, "range", "bad")
        assertEquals(UsageRange.Month, UsageWidgetKeys.range(first))
        assertEquals(UsageMetric.Tokens, UsageWidgetKeys.metric(first))
        assertEquals(UsageGrouping.Providers, UsageWidgetKeys.grouping(first))
        assertEquals(UsageRange.Week, UsageWidgetKeys.range(second))
        assertEquals(UsageMetric.Cost, UsageWidgetKeys.metric(second))
        assertEquals(UsageGrouping.Models, UsageWidgetKeys.grouping(second))
        assertNotNull(SelectUsageWidgetAction::class.java.getConstructor().newInstance())
    }
}
