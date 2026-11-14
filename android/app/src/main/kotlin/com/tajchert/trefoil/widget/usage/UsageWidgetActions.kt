package com.tajchert.trefoil.widget.usage

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

internal object UsageActionKeys {
    val widget = ActionParameters.Key<String>("usage_widget")
    val field = ActionParameters.Key<String>("usage_field")
    val value = ActionParameters.Key<String>("usage_value")
}

/** Public zero-argument constructor is required by Glance's callback reflection. */
class SelectUsageWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val widget =
            when (parameters[UsageActionKeys.widget]) {
                "chart" -> UsageChartWidget()
                "table" -> UsageTableWidget()
                else -> return
            }
        val field = parameters[UsageActionKeys.field] ?: return
        val value = parameters[UsageActionKeys.value] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            selectUsagePreference(prefs, field, value)
        }
        widget.update(context, glanceId)
    }
}
