package com.tajchert.trefoil.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Zero-argument callback (Glance instantiates it reflectively). Implemented by the data pipeline stream. */
class RefreshUsageWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UsageWidgetRefresh.requestNow(context)
    }
}

/** Background refresh entry points. Implemented by the data pipeline stream; widgets only call these. */
object UsageWidgetRefresh {
    /** Enqueue a one-time refresh (unique, KEEP). */
    fun requestNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("usage-widget-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UsageWidgetWorker>().setConstraints(network).build())
    }
    /** Ensure the periodic refresh is scheduled while at least one widget is placed. */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("usage-widget-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UsageWidgetWorker>(30, TimeUnit.MINUTES).setConstraints(network)
                .setInputData(workDataOf("periodic" to true)).build())
    }
    /** Cancel periodic work when the last widget is removed. */
    fun cancelPeriodic(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("usage-widget-periodic") }
    private val network get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
