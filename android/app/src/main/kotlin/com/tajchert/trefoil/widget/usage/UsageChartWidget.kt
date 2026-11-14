package com.tajchert.trefoil.widget.usage

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.tajchert.trefoil.widget.*

class UsageChartWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = UsageWidgetStore.get(context)
        store.read()
        provideContent {
            val snapshot by store.snapshot.collectAsState()
            UsageWidgetContent("chart", snapshot, currentState<Preferences>())
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            UsageWidgetContent("chart", UsageWidgetSamples.snapshot(), emptyPreferences())
        }
    }
}
