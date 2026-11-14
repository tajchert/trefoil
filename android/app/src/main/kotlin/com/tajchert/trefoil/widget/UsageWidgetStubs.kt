package com.tajchert.trefoil.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

// Placeholder widgets so the receivers, manifest and picker work from the foundation commit.
// Each widget stream moves its class into its own file (see BRIEF.md) and deletes it here.

class LimitBarsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Usage limits", null, openUsageAction(context, UsageWidgetTab.Limits)) }
    }
}

class LimitRingsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Limit gauges", null, openUsageAction(context, UsageWidgetTab.Limits)) }
    }
}

class UsageSummaryWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Usage summary", null, openUsageAction(context, UsageWidgetTab.Cost)) }
    }
}

class UsageChartWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Usage chart", null, openUsageAction(context, UsageWidgetTab.Cost)) }
    }
}

class UsageTableWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Top usage", null, openUsageAction(context, UsageWidgetTab.Cost)) }
    }
}
