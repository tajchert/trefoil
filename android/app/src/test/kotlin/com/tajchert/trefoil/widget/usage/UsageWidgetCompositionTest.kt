package com.tajchert.trefoil.widget.usage

import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.testing.unit.*
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.*
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.ui.formatUsageUsd
import com.tajchert.trefoil.widget.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageWidgetCompositionTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val sizes =
        listOf(
            DpSize(110.dp, 60.dp),
            DpSize(210.dp, 140.dp),
            DpSize(320.dp, 230.dp),
            DpSize(500.dp, 320.dp),
        )

    @Test
    fun allWidgetsComposeAtFourSizesIncludingNoSnapshot() {
        for (kind in listOf("summary", "chart", "table")) for (size in sizes) {
            runGlanceAppWidgetUnitTest {
                setContext(context)
                setAppWidgetSize(size)
                provideComposable { UsageWidgetContent(kind, null, emptyPreferences()) }
                onNode(hasTextEqualTo("Open Trefoil")).assertExists()
                onNode(hasTextEqualTo("Connect to a machine to see usage")).assertExists()
            }
            runGlanceAppWidgetUnitTest {
                val snapshot = UsageWidgetSamples.snapshot()
                setContext(context)
                setAppWidgetSize(size)
                provideComposable { UsageWidgetContent(kind, snapshot, emptyPreferences()) }
                when (kind) {
                    "summary" ->
                        onNode(
                                hasTextEqualTo(
                                    formatUsageUsd(
                                        snapshot.reports.getValue(UsageRange.Today).totalCostUsd
                                    )
                                )
                            )
                            .assertExists()
                    "chart" ->
                        onNode(hasText("max/day")).let {
                            if (size.height >= 100.dp) it.assertExists()
                            else it.assertDoesNotExist()
                        }
                    else -> onNode(hasTextEqualTo("Top usage · 7 days")).assertExists()
                }
                onAllNodes(GlanceNodeMatcher<MappedNode>("all nodes") { true })
                    .assertAll(
                        GlanceNodeMatcher("at most ten children in layout containers") {
                            // LazyColumn can contain any number of stable collection items.
                            val name = it.value.emittable.javaClass.simpleName
                            name !in setOf("EmittableRow", "EmittableColumn", "EmittableBox") ||
                                it.children().size <= 10
                        }
                    )
            }
        }
    }

    @Test
    fun summaryShowsThreeRangesAndExpandedDetails() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(500.dp, 320.dp))
        provideComposable {
            UsageWidgetContent("summary", UsageWidgetSamples.snapshot(), emptyPreferences())
        }
        onNode(hasTextEqualTo("Today · est.")).assertExists()
        onNode(hasTextEqualTo("7 days · est.")).assertExists()
        onNode(hasTextEqualTo("30 days · est.")).assertExists()
        onNode(hasText("Cache saved")).assertExists()
        onNode(hasText("Top provider")).assertExists()
        onNode(hasText("Top model")).assertExists()
        onNode(hasText("unpriced records")).assertExists()
    }

    @Test
    fun chartMonthUsesThirtyBarsAndTokenNavigation() = runGlanceAppWidgetUnitTest {
        val snapshot = UsageWidgetSamples.snapshot()
        val prefs =
            mutablePreferencesOf(
                UsageWidgetKeys.range to "Month",
                UsageWidgetKeys.metric to "Tokens",
            )
        setContext(context)
        setAppWidgetSize(DpSize(500.dp, 320.dp))
        provideComposable { UsageWidgetContent("chart", snapshot, prefs) }
        snapshot.reports.getValue(UsageRange.Month).days.forEach {
            onNode(hasContentDescription(it.day + ":")).assertExists()
        }
        onNode(hasTextEqualTo("30d"))
            .assertHasRunCallbackClickAction<SelectUsageWidgetAction>(
                androidx.glance.action.actionParametersOf(
                    UsageActionKeys.widget to "chart",
                    UsageActionKeys.field to "range",
                    UsageActionKeys.value to "Month",
                )
            )
        onNode(hasStartActivityClickAction(navigationIntent(UsageWidgetTab.Tokens))).assertExists()
    }

    @Test
    fun narrowChartShowsWeekEvenWhenMonthWasSelected() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(210.dp, 140.dp))
        provideComposable {
            UsageWidgetContent(
                "chart",
                UsageWidgetSamples.snapshot(),
                mutablePreferencesOf(UsageWidgetKeys.range to "Month"),
            )
        }
        onNode(hasTextEqualTo("Usage chart · 7 days")).assertExists()
        onNode(hasTextEqualTo("30d")).assertDoesNotExist()
    }

    @Test
    fun tableExpandedShowsBothMetricsAndGroupingControl() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(500.dp, 320.dp))
        provideComposable {
            UsageWidgetContent(
                "table",
                UsageWidgetSamples.snapshot(),
                mutablePreferencesOf(UsageWidgetKeys.grouping to "Providers"),
            )
        }
        onNode(hasTextEqualTo("Name · tokens · est. cost · records")).assertExists()
        onNode(hasTextEqualTo("Providers"))
            .assertHasRunCallbackClickAction<SelectUsageWidgetAction>(
                androidx.glance.action.actionParametersOf(
                    UsageActionKeys.widget to "table",
                    UsageActionKeys.field to "grouping",
                    UsageActionKeys.value to "Providers",
                )
            )
        onNode(hasTextEqualTo("Claude")).assertExists()
        onNode(hasTextEqualTo("claude-opus-5-5")).assertDoesNotExist()
        // Collection rows need their own launch actions; the root click does not cover ListView
        // items.
        onAllNodes(hasStartActivityClickAction(navigationIntent(UsageWidgetTab.Cost)))
            .assertCountEquals(4)
    }

    @Test
    fun compactTableHeaderCyclesRange() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(210.dp, 140.dp))
        provideComposable {
            UsageWidgetContent("table", UsageWidgetSamples.snapshot(), emptyPreferences())
        }
        onNode(hasTextEqualTo("Top usage · 7 days"))
            .assertHasRunCallbackClickAction<SelectUsageWidgetAction>(
                androidx.glance.action.actionParametersOf(
                    UsageActionKeys.widget to "table",
                    UsageActionKeys.field to "cycle",
                    UsageActionKeys.value to "next",
                )
            )
        onNode(hasTextEqualTo("Models")).assertDoesNotExist()
    }

    @Test
    fun missingRangeIsInlineAndStaleDataOffersRefresh() {
        for (kind in listOf("summary", "chart", "table")) runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(500.dp, 320.dp))
            val snapshot =
                UsageWidgetSamples.snapshot()
                    .copy(
                        reports = emptyMap(),
                        updatedAtMillis = System.currentTimeMillis() - 3 * 60 * 60_000,
                    )
            provideComposable {
                UsageWidgetContent(
                    kind,
                    snapshot,
                    mutablePreferencesOf(UsageWidgetKeys.range to "Month"),
                )
            }
            onNode(hasTextEqualTo("No data yet for 30 days")).assertExists()
            onNode(hasText("Updated 3h"))
                .assertHasRunCallbackClickAction<RefreshUsageWidgetsAction>()
        }
    }

    @Test
    fun largeFontsUseCompactControlsAndFewerRows() {
        val config =
            android.content.res.Configuration(context.resources.configuration).apply {
                fontScale = 2f
            }
        val scaled = context.createConfigurationContext(config)
        for (kind in listOf("summary", "chart", "table")) runGlanceAppWidgetUnitTest {
            setContext(scaled)
            setAppWidgetSize(DpSize(320.dp, 230.dp))
            provideComposable {
                UsageWidgetContent(kind, UsageWidgetSamples.snapshot(), emptyPreferences())
            }
            onNode(hasTextEqualTo("Models")).assertDoesNotExist()
            onNode(hasTextEqualTo("30d")).assertDoesNotExist()
        }
    }

    private fun navigationIntent(tab: UsageWidgetTab) =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_USAGE)
            .putExtra(EXTRA_OPEN_USAGE_TAB, tab.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
