package com.tajchert.trefoil.widget

import android.content.Context
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

class LimitBarsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LimitBarsWidget() }
class LimitRingsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LimitRingsWidget() }
class UsageSummaryWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageSummaryWidget() }
class UsageChartWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageChartWidget() }
class UsageTableWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = UsageTableWidget() }
