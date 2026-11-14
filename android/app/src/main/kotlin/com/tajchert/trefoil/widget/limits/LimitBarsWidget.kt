package com.tajchert.trefoil.widget.limits

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.tajchert.trefoil.widget.*

// Placeholder from the foundation commit; replace with the real widget.
class LimitBarsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        TrefoilWidgetTheme { WidgetMessage("Usage limits", null, openUsageAction(context, UsageWidgetTab.Limits)) }
    }
}
