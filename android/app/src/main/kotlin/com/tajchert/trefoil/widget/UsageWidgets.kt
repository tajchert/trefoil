package com.tajchert.trefoil.widget

import android.content.Context
import kotlinx.coroutines.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.tajchert.trefoil.widget.limits.LimitBarsWidget
import com.tajchert.trefoil.widget.limits.LimitRingsWidget
import com.tajchert.trefoil.widget.usage.UsageChartWidget
import com.tajchert.trefoil.widget.usage.UsageSummaryWidget
import com.tajchert.trefoil.widget.usage.UsageTableWidget

/** Every usage widget; [updateAll] re-renders all placed instances. */
object UsageWidgets {
    val all: List<GlanceAppWidget> get() = listOf(LimitBarsWidget(), LimitRingsWidget(), UsageSummaryWidget(), UsageChartWidget(), UsageTableWidget())
    suspend fun updateAll(context: Context) { all.forEach { runCatching { it.updateAll(context) } } }
}

abstract class ScheduledUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UsageWidgetRefresh.schedulePeriodic(context)
    }
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { if (!usageWidgetsPlaced(context)) UsageWidgetRefresh.cancelPeriodic(context) }
            finally { pending.finish() }
        }
    }
}

class LimitBarsWidgetReceiver : ScheduledUsageWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LimitBarsWidget() }
class LimitRingsWidgetReceiver : ScheduledUsageWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LimitRingsWidget() }
class UsageSummaryWidgetReceiver : ScheduledUsageWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageSummaryWidget() }
class UsageChartWidgetReceiver : ScheduledUsageWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageChartWidget() }
class UsageTableWidgetReceiver : ScheduledUsageWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageTableWidget() }
