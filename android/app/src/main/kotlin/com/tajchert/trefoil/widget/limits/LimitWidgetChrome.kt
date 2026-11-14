package com.tajchert.trefoil.widget.limits

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tajchert.trefoil.R
import com.tajchert.trefoil.widget.*

@Composable
internal fun limitsRoot(): GlanceModifier =
    GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(16.dp)
        .clickable(openUsageAction(LocalContext.current, UsageWidgetTab.Limits))
        .padding(8.dp)

@Composable
internal fun LimitsNotConnected() {
    Box(GlanceModifier.fillMaxSize().appWidgetBackground()) {
        WidgetMessage(
            "Open Trefoil",
            "Connect to a machine to see usage",
            openUsageAction(LocalContext.current, UsageWidgetTab.Limits),
        )
    }
}

@Composable
internal fun LimitHeader(title: String, snapshot: UsageWidgetSnapshot, now: Long) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                title,
                maxLines = 1,
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
            LimitNote(snapshot.machineName)
        }
        Image(
            ImageProvider(R.drawable.widget_limits_refresh),
            "Refresh usage",
            GlanceModifier.size(40.dp).padding(8.dp).clickable(widgetRefreshAction(snapshot, UsageWidgetTab.Limits)),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        )
    }
    Text(
        widgetStatus(snapshot, snapshot.limitsUpdatedAtMillis ?: snapshot.updatedAtMillis, now),
        maxLines = 1,
        modifier = GlanceModifier.clickable(widgetRefreshAction(snapshot, UsageWidgetTab.Limits)),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
    )
}

@Composable
internal fun LimitNote(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text,
        modifier,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
    )
}

@Composable
internal fun LimitProviderMark(driver: String) {
    widgetProviderIcon(driver)?.let {
        Image(
            ImageProvider(it),
            null,
            GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        )
        Spacer(GlanceModifier.width(4.dp))
    }
}
