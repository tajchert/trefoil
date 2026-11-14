package com.tajchert.trefoil.widget.limits

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.ui.usagePace
import com.tajchert.trefoil.widget.*

class LimitBarsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = UsageWidgetStore.get(context)
        store.read()
        provideContent {
            val snapshot by store.snapshot.collectAsState()
            TrefoilWidgetTheme { LimitBarsContent(snapshot) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent {
        TrefoilWidgetTheme { LimitBarsContent(UsageWidgetSamples.snapshot()) }
    }
}

@Composable
internal fun LimitBarsContent(
    snapshot: UsageWidgetSnapshot?,
    now: Long = System.currentTimeMillis(),
) {
    if (snapshot == null) {
        LimitsNotConnected()
        return
    }
    val size = LocalSize.current
    val scale = LocalContext.current.resources.configuration.fontScale
    val compact = size.height.value < 150 * scale || size.width.value < 150
    val stale = isWidgetStale(snapshot.limitsUpdatedAtMillis ?: snapshot.updatedAtMillis, now)
    Column(limitsRoot()) {
        if (!compact) LimitHeader("Usage limits", snapshot, now)
        if (snapshot.providers.isEmpty()) LimitNote("No provider limits reported")
        if (compact) {
            val showFreshness = stale && size.height.value >= 90 * scale
            if (showFreshness)
                LimitNote(
                    widgetFreshness(
                        snapshot.limitsUpdatedAtMillis ?: snapshot.updatedAtMillis,
                        now,
                    ),
                    GlanceModifier.clickable(refreshUsageAction()),
                )
            val providers =
                compactProviders(
                    snapshot.providers,
                    compactBarCapacity(
                        size.height.value - if (showFreshness) 18 * scale else 0f,
                        scale,
                    ),
                    now,
                )
            providers.forEach { provider ->
                val window = orderedGauges(listOf(provider), now).firstOrNull()?.window
                if (window == null) LimitNote("${provider.displayName} · ${limitNotice(provider)}")
                else
                    LimitBar(
                        window,
                        now,
                        "${provider.displayName} · ${window.label}",
                        compact = true,
                        compactReset = size.width.value >= 230 * scale,
                    )
            }
        } else {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                val columns = if (size.width.value >= 400) 2 else 1
                repeat(columns) { column ->
                    if (column > 0) Spacer(GlanceModifier.width(12.dp))
                    ProviderBarList(
                        snapshot.providers.filterIndexed { index, _ -> index % columns == column },
                        now,
                        GlanceModifier.defaultWeight(),
                        size.width.value / columns >= 250 && scale <= 1.3f,
                    )
                }
            }
        }
    }
}

// Canonical scrolling-list pattern: headers and windows are separate stable lazy items so no
// provider can overflow Glance's ten-child container limit, even with new server window kinds.
private data class BarEntry(
    val provider: UsageWidgetProvider,
    val window: UsageLimitWindow? = null,
)

@Composable
private fun ProviderBarList(
    providers: List<UsageWidgetProvider>,
    now: Long,
    modifier: GlanceModifier,
    pace: Boolean,
) {
    val openUsage = openUsageAction(LocalContext.current, UsageWidgetTab.Limits)
    val entries =
        providers.flatMap { provider ->
            listOf(BarEntry(provider)) +
                provider.limits?.windows.orEmpty().map { BarEntry(provider, it) }
        }
    LazyColumn(modifier.cornerRadius(12.dp)) {
        itemsIndexed(
            entries,
            itemId = { _, entry ->
                (entry.provider.instanceId + ":" + (entry.window?.id ?: "header")).fold(17L) {
                    hash,
                    char ->
                    hash * 31 + char.code
                } and Long.MAX_VALUE
            },
        ) { _, entry ->
            Column(GlanceModifier.fillMaxWidth().clickable(openUsage).padding(vertical = 5.dp)) {
                if (entry.window != null)
                    LimitBar(entry.window, now, entry.window.label, showPace = pace)
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LimitProviderMark(entry.provider.driver)
                        Text(
                            entry.provider.displayName,
                            maxLines = 1,
                            style =
                                TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                        )
                    }
                    if (entry.provider.limits?.windows.isNullOrEmpty())
                        LimitNote(limitNotice(entry.provider))
                }
            }
        }
    }
}

@Composable
private fun LimitBar(
    window: UsageLimitWindow,
    now: Long,
    label: String,
    compact: Boolean = false,
    showPace: Boolean = false,
    compactReset: Boolean = false,
) {
    val expired = hasReset(window, now)
    val stale = expired || !window.usedPercent.isFinite()
    val color =
        if (stale) GlanceTheme.colors.onSurfaceVariant
        else WidgetPalette.remaining(window.remainingPercent)
    Column(GlanceModifier.fillMaxWidth().padding(bottom = if (compact) 4.dp else 2.dp)) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(
                if (compactReset && !expired)
                    "$label · ${limitResetText(window, now, compact = true)}"
                else label,
                GlanceModifier.defaultWeight(),
                maxLines = 1,
                style =
                    TextStyle(
                        color =
                            if (stale) GlanceTheme.colors.onSurfaceVariant
                            else GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                    ),
            )
            Text(
                if (expired) "Reset since last check"
                else if (stale) "Unavailable" else "${window.remainingPercent}% left",
                maxLines = 1,
                style = TextStyle(color = color, fontSize = 12.sp),
            )
        }
        LinearProgressIndicator(
            progress = if (stale) 0f else window.remainingPercent / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = color,
            backgroundColor = WidgetPalette.track,
        )
        if (!compact && !expired) {
            val reset = limitResetText(window, now)
            LimitNote(
                if (showPace && !stale)
                    listOfNotNull(reset, usagePace(window, now)).joinToString(" · ")
                else reset
            )
        }
    }
}
