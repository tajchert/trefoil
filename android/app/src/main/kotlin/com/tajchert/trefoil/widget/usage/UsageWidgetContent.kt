package com.tajchert.trefoil.widget.usage

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.formatUsageTokens
import com.tajchert.trefoil.ui.formatUsageUsd
import com.tajchert.trefoil.ui.usageProviderLabel
import com.tajchert.trefoil.ui.usageShare
import com.tajchert.trefoil.widget.*
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun UsageWidgetContent(kind: String, snapshot: UsageWidgetSnapshot?, prefs: Preferences) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fit =
        UsageFit(size.width.value, size.height.value, context.resources.configuration.fontScale)
    val metric = if (kind == "summary") UsageMetric.Cost else UsageWidgetKeys.metric(prefs)
    val tab = if (metric == UsageMetric.Cost) UsageWidgetTab.Cost else UsageWidgetTab.Tokens
    TrefoilWidgetTheme {
        if (snapshot == null) {
            Box(GlanceModifier.appWidgetBackground()) {
                WidgetMessage(
                    "Open Trefoil",
                    "Connect to a machine to see usage",
                    openUsageAction(context, tab),
                )
            }
        } else {
            Column(
                GlanceModifier.fillMaxSize()
                    .appWidgetBackground()
                    .background(GlanceTheme.colors.widgetBackground)
                    .cornerRadius(16.dp)
                    .padding(if (fit.tight) 4.dp else 12.dp)
                    .clickable(openUsageAction(context, tab))
            ) {
                when (kind) {
                    "summary" -> SummaryContent(snapshot, fit)
                    "chart" -> ChartContent(snapshot, prefs, metric, fit)
                    else -> TableContent(snapshot, prefs, metric, fit)
                }
            }
        }
    }
}

@Composable
internal fun UsageLabel(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    large: Boolean = false,
    muted: Boolean = false,
    fontSize: Int? = null,
) {
    Text(
        text,
        modifier,
        maxLines = 1,
        style =
            TextStyle(
                color =
                    if (muted) GlanceTheme.colors.onSurfaceVariant
                    else GlanceTheme.colors.onSurface,
                fontSize = (fontSize ?: if (large) 22 else 12).sp,
                fontWeight = if (large) FontWeight.Medium else FontWeight.Normal,
            ),
    )
}

@Composable
private fun Header(title: String, snapshot: UsageWidgetSnapshot, fit: UsageFit, action: Action? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UsageLabel(
            title,
            GlanceModifier.defaultWeight().let { if (action == null) it else it.clickable(action) },
        )
        if (fit.width >= 210 * fit.fontScale && fit.height >= 110 * fit.fontScale) {
            Image(
                ImageProvider(R.drawable.widget_usage_refresh),
                "Refresh usage",
                GlanceModifier.size(32.dp).padding(6.dp).clickable(widgetRefreshAction(snapshot, UsageWidgetTab.Cost)),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            )
        }
    }
}

@Composable
private fun Footer(snapshot: UsageWidgetSnapshot, fit: UsageFit) {
    val now = System.currentTimeMillis()
    val stale = isWidgetStale(snapshot.updatedAtMillis, now)
    val text = widgetStatus(snapshot, snapshot.updatedAtMillis, now)
    if (fit.footer || stale) {
        UsageLabel(
            if (fit.width >= 280 * fit.fontScale) "${snapshot.machineName} · $text" else text,
            GlanceModifier.clickable(widgetRefreshAction(snapshot, UsageWidgetTab.Cost)),
            muted = true,
            fontSize = if (fit.tight) 10 else 12,
        )
    }
}

@Composable
private fun Missing(range: UsageRange) =
    Text(
        "No data yet for ${range.label.lowercase()}",
        maxLines = 3,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
    )

@Composable
private fun SummaryContent(snapshot: UsageWidgetSnapshot, fit: UsageFit) {
    if (fit.height >= 170 * fit.fontScale) Header("Usage summary", snapshot, fit)
    if (fit.tight) {
        val report = snapshot.reports[UsageRange.Today]
        if (report == null) Missing(UsageRange.Today)
        else
            Row(verticalAlignment = Alignment.CenterVertically) {
                UsageLabel(
                    formatUsageUsd(report.totalCostUsd) +
                        if (fit.width >= 180 * fit.fontScale) " est." else "",
                    GlanceModifier.defaultWeight(),
                    large = true,
                    fontSize = 16,
                )
                UsageLabel(
                    "${formatUsageTokens(report.tokens.total)} tokens",
                    muted = true,
                    fontSize = 10,
                )
            }
        Footer(snapshot, fit)
        return
    }
    if (fit.summaryColumns) {
        if (fit.height < 170 * fit.fontScale) UsageLabel("Est. cost · tokens", muted = true)
        Row(GlanceModifier.fillMaxWidth()) {
            UsageRange.entries.forEach { range ->
                Column(GlanceModifier.defaultWeight()) { SummaryPeriod(snapshot, range, fit, true) }
            }
        }
        snapshot.reports[UsageRange.Week]?.let { report ->
            Bars(report, UsageMetric.Cost, 7, 28f, false)
        }
        if (fit.expanded) {
            snapshot.reports[UsageRange.Month]?.let { report ->
                Column {
                    UsageLabel(
                        "Cache saved ${formatUsageUsd(report.cacheSavingsUsd)} · 30 days",
                        muted = true,
                    )
                    sortedUsage(report, UsageMetric.Cost, UsageGrouping.Providers)
                        .firstOrNull()
                        ?.let {
                            UsageLabel(
                                "Top provider · ${usageProviderLabel(it.provider)}",
                                muted = true,
                            )
                        }
                    sortedUsage(report, UsageMetric.Cost, UsageGrouping.Models).firstOrNull()?.let {
                        UsageLabel("Top model · ${it.model ?: it.provider}", muted = true)
                    }
                    PricingNote(report)
                }
            }
        }
    } else SummaryPeriod(snapshot, UsageRange.Today, fit, false)
    Footer(snapshot, fit)
}

@Composable
private fun SummaryPeriod(
    snapshot: UsageWidgetSnapshot,
    range: UsageRange,
    fit: UsageFit,
    columns: Boolean,
) {
    val report = snapshot.reports[range]
    if (report == null) Missing(range)
    else {
        val columnWidth = (fit.width - 24) / if (columns) 3 else 1
        val cost = formatUsageUsd(report.totalCostUsd)
        UsageLabel(
            range.label + if (columnWidth >= 100 * fit.fontScale) " · est." else "",
            muted = true,
        )
        UsageLabel(cost, large = true, fontSize = usageNumberFont(cost, columnWidth, fit.fontScale))
        UsageLabel(
            formatUsageTokens(report.tokens.total) +
                if (columnWidth >= 100 * fit.fontScale) " tokens" else " tok",
            muted = true,
        )
    }
}

private fun selectAction(widget: String, field: String, value: String): Action =
    actionRunCallback<SelectUsageWidgetAction>(
        actionParametersOf(
            UsageActionKeys.widget to widget,
            UsageActionKeys.field to field,
            UsageActionKeys.value to value,
        )
    )

@Composable
private fun Chip(label: String, selected: Boolean, action: Action) {
    Text(
        label,
        GlanceModifier.padding(2.dp)
            .cornerRadius(8.dp)
            .background(
                if (selected) GlanceTheme.colors.primaryContainer
                else GlanceTheme.colors.surfaceVariant
            )
            .clickable(action)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
    )
}

@Composable
private fun MetricChips(widget: String, metric: UsageMetric) {
    Row {
        UsageMetric.entries.forEach {
            Chip(it.name, metric == it, selectAction(widget, "metric", it.name))
        }
    }
}

@Composable
private fun ChartContent(
    snapshot: UsageWidgetSnapshot,
    prefs: Preferences,
    metric: UsageMetric,
    fit: UsageFit,
) {
    val selected =
        UsageWidgetKeys.range(prefs).let { if (it == UsageRange.Today) UsageRange.Week else it }
    val range = if (fit.chartDays == 7) UsageRange.Week else selected
    if (!fit.tight) Header("Usage chart · ${range.label}", snapshot, fit)
    if (fit.controls) {
        Row {
            if (fit.chartDays == 30)
                Row {
                    listOf(UsageRange.Week, UsageRange.Month).forEach {
                        Chip(
                            if (it == UsageRange.Week) "7d" else "30d",
                            range == it,
                            selectAction("chart", "range", it.name),
                        )
                    }
                }
            MetricChips("chart", metric)
        }
    }
    val report = snapshot.reports[range]
    if (report == null) Missing(range)
    else {
        val total =
            "${metric.format(metric.total(report))}${if (metric == UsageMetric.Cost) " est." else " tokens"}"
        UsageLabel(
            total,
            large = true,
            fontSize = usageNumberFont(total, fit.width - if (fit.tight) 8 else 24, fit.fontScale),
        )
        val max = report.days.maxOfOrNull { metric.value(it) } ?: 0.0
        if (!fit.tight) UsageLabel("${metric.format(max)} max/day", muted = true)
        val labels = fit.barLabels && range.days == 7
        val height =
            fit.chartHeight(
                fit.footer || isWidgetStale(snapshot.updatedAtMillis, System.currentTimeMillis()),
                labels,
            )
        if (report.days.isEmpty()) UsageLabel("No daily usage yet", muted = true)
        else Bars(report, metric, range.days, height, labels)
    }
    Footer(snapshot, fit)
}

/** Thirty bars are nested in three groups; each RemoteViews container has at most ten children. */
@Composable
private fun Bars(
    report: UsageReport,
    metric: UsageMetric,
    count: Int,
    height: Float,
    labels: Boolean,
) {
    val days = report.days.takeLast(count)
    val max = days.maxOfOrNull { metric.value(it) } ?: 0.0
    val today =
        try {
            Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.of(report.timeZone))
                .toLocalDate()
                .toString()
        } catch (_: Exception) {
            ""
        }
    Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        days.chunked(10).forEach { group ->
            Row(GlanceModifier.defaultWeight()) {
                group.forEach { day ->
                    Column(
                        GlanceModifier.defaultWeight().padding(horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            GlanceModifier.fillMaxWidth().height(height.dp).semantics {
                                contentDescription =
                                    "${day.day}: ${metric.format(metric.value(day))}${if (day.day == today) ", today" else ""}"
                            },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                GlanceModifier.fillMaxWidth()
                                    .height(barHeight(metric.value(day), max, height).dp)
                                    .background(
                                        if (day.day == today) GlanceTheme.colors.primary
                                        else if (metric.value(day) <= 0) WidgetPalette.track
                                        else GlanceTheme.colors.secondary
                                    )
                            ) {}
                        }
                        if (labels) UsageLabel(day.day.takeLast(2), muted = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TableContent(
    snapshot: UsageWidgetSnapshot,
    prefs: Preferences,
    metric: UsageMetric,
    fit: UsageFit,
) {
    val range = UsageWidgetKeys.range(prefs)
    val grouping = UsageWidgetKeys.grouping(prefs)
    Header(
        "Top usage · ${range.label}",
        snapshot,
        fit,
        if (fit.controls) null else selectAction("table", "cycle", "next"),
    )
    if (fit.controls) {
        Row {
            UsageRange.entries.forEach {
                Chip(
                    when (it) {
                        UsageRange.Today -> "Today"
                        UsageRange.Week -> "7d"
                        UsageRange.Month -> "30d"
                    },
                    range == it,
                    selectAction("table", "range", it.name),
                )
            }
        }
        MetricChips("table", metric)
        Row {
            UsageGrouping.entries.forEach {
                Chip(it.name, grouping == it, selectAction("table", "grouping", it.name))
            }
        }
    }
    val report = snapshot.reports[range]
    if (report == null) Missing(range)
    else {
        val rows = sortedUsage(report, metric, grouping)
        if (rows.isEmpty()) UsageLabel("No usage reported yet", muted = true)
        else {
            if (fit.expanded) UsageLabel("Name · tokens · est. cost · records", muted = true)
            val visible =
                if (fit.controls) rows else rows.take(minOf(3, fit.rows(62f * fit.fontScale)))
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(visible, itemId = ::usageItemId) { row -> TableRow(row, report, metric, fit) }
            }
        }
        if (fit.expanded) PricingNote(report)
    }
    Footer(snapshot, fit)
}

@Composable
private fun TableRow(row: UsageBreakdown, report: UsageReport, metric: UsageMetric, fit: UsageFit) {
    val tab = if (metric == UsageMetric.Cost) UsageWidgetTab.Cost else UsageWidgetTab.Tokens
    Column(
        GlanceModifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(openUsageAction(LocalContext.current, tab))
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val driver = if (row.provider == "claude") "claudeAgent" else row.provider
            Image(
                ImageProvider(widgetProviderIcon(driver) ?: R.drawable.widget_usage_provider),
                row.provider,
                GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            )
            UsageLabel(
                row.model ?: usageProviderLabel(row.provider),
                GlanceModifier.defaultWeight().padding(horizontal = 6.dp),
            )
            if (fit.expanded) {
                UsageLabel(
                    formatUsageTokens(row.tokens.total),
                    GlanceModifier.width((58 * fit.fontScale).dp),
                )
                UsageLabel(
                    formatUsageUsd(row.costUsd),
                    GlanceModifier.width((66 * fit.fontScale).dp),
                )
                UsageLabel(
                    row.records.toString(),
                    GlanceModifier.width((40 * fit.fontScale).dp),
                    muted = true,
                )
            } else
                UsageLabel(
                    metric.format(metric.value(row)) +
                        if (metric == UsageMetric.Cost && fit.width >= 280 * fit.fontScale) " est."
                        else ""
                )
        }
        val width = (fit.width - 24).coerceAtLeast(1f)
        Box(GlanceModifier.fillMaxWidth().height(3.dp).background(WidgetPalette.track)) {
            val share = usageShare(metric.value(row), metric.total(report))
            if (share > 0)
                Box(
                    GlanceModifier.width((width * share).dp)
                        .height(3.dp)
                        .background(WidgetPalette.good)
                ) {}
        }
    }
}

@Composable
private fun PricingNote(report: UsageReport) {
    if (report.unpricedRecords > 0)
        UsageLabel(
            "${report.unpricedRecords} unpriced records · cost may be incomplete",
            muted = true,
        )
    else if (report.pricingStatus == "unavailable")
        UsageLabel("Model pricing unavailable", muted = true)
}
