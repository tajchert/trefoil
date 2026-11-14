package com.tajchert.trefoil.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/** Zero-argument callback (Glance instantiates it reflectively). Implemented by the data pipeline stream. */
class RefreshUsageWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UsageWidgetRefresh.requestNow(context)
    }
}

/** Background refresh entry points. Implemented by the data pipeline stream; widgets only call these. */
object UsageWidgetRefresh {
    /** Enqueue a one-time refresh (unique, KEEP). */
    fun requestNow(context: Context) {}
    /** Ensure the periodic refresh is scheduled while at least one widget is placed. */
    fun schedulePeriodic(context: Context) {}
    /** Cancel periodic work when the last widget is removed. */
    fun cancelPeriodic(context: Context) {}
}
