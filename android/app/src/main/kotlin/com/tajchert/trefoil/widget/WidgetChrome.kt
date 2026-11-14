package com.tajchert.trefoil.widget

import android.content.Context
import android.content.Intent
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tajchert.trefoil.MainActivity

/** Usage screen tab a widget tap should open. */
enum class UsageWidgetTab { Limits, Cost, Tokens }

/** Intent extra (value: [UsageWidgetTab] name) that makes [MainActivity] open the Usage screen. */
const val EXTRA_OPEN_USAGE_TAB = "com.tajchert.trefoil.extra.OPEN_USAGE_TAB"
const val ACTION_OPEN_USAGE = "com.tajchert.trefoil.action.OPEN_USAGE"

// Trefoil palette (ui/TrefoilTheme.kt), light and dark.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E7A3A), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFDCF0E0), onPrimaryContainer = Color(0xFF111514),
    secondary = Color(0xFF1C5DB5), secondaryContainer = Color(0xFFDCE8FA), onSecondaryContainer = Color(0xFF111514),
    tertiary = Color(0xFF955700), tertiaryContainer = Color(0xFFFBEACB),
    background = Color(0xFFF6F7F6), onBackground = Color(0xFF111514),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111514), surfaceVariant = Color(0xFFECEFED), onSurfaceVariant = Color(0xFF56605D),
    outline = Color(0xFFC3CAC6), outlineVariant = Color(0xFFDDE2DF), error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADEDC), onErrorContainer = Color(0xFF111514),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8EDB9B), onPrimary = Color(0xFF08200F), primaryContainer = Color(0xFF1B3222), onPrimaryContainer = Color(0xFFECEFEE),
    secondary = Color(0xFF82BCFF), secondaryContainer = Color(0xFF15243A), onSecondaryContainer = Color(0xFFECEFEE),
    tertiary = Color(0xFFF4B55B), tertiaryContainer = Color(0xFF35280F),
    background = Color(0xFF0F1111), onBackground = Color(0xFFECEFEE),
    surface = Color(0xFF161919), onSurface = Color(0xFFECEFEE), surfaceVariant = Color(0xFF1F2323), onSurfaceVariant = Color(0xFF9CA4A2),
    outline = Color(0xFF3A4040), outlineVariant = Color(0xFF272C2C), error = Color(0xFFF28B82), onError = Color(0xFF0F1111),
    errorContainer = Color(0xFF3A1A18), onErrorContainer = Color(0xFFECEFEE),
)
private val TrefoilWidgetColors = ColorProviders(light = LightScheme, dark = DarkScheme)

@Composable
fun TrefoilWidgetTheme(content: @Composable () -> Unit) = GlanceTheme(colors = TrefoilWidgetColors, content = content)

/** Semantic usage colors (match the in-app Usage screen). */
object WidgetPalette {
    val good: ColorProvider = androidx.glance.color.ColorProvider(Color(0xFF1E7A3A), Color(0xFF74D08F))
    val warn: ColorProvider = androidx.glance.color.ColorProvider(Color(0xFF955700), Color(0xFFF4B55B))
    val bad: ColorProvider = androidx.glance.color.ColorProvider(Color(0xFFB3261E), Color(0xFFF28B82))
    val track: ColorProvider = androidx.glance.color.ColorProvider(Color(0xFFE1E6E3), Color(0xFF2A2F2F))
    /** Remaining-percent color: red at 10% or less, amber at 30% or less. */
    fun remaining(percent: Int): ColorProvider = when { percent <= 10 -> bad; percent <= 30 -> warn; else -> good }
}

/** Opens Trefoil on the Usage screen at [tab]. */
fun openUsageAction(context: Context, tab: UsageWidgetTab): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_USAGE).putExtra(EXTRA_OPEN_USAGE_TAB, tab.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

/** Asks for a background refresh of the widget snapshot (never a mutation). */
fun refreshUsageAction(): Action = actionRunCallback<RefreshUsageWidgetsAction>()

/** Refresh tap: opens Trefoil when a background refresh can't reach this machine (e.g. SSH needs a prompt). */
@Composable
fun widgetRefreshAction(snapshot: UsageWidgetSnapshot, tab: UsageWidgetTab): Action =
    if (snapshot.backgroundRefresh) refreshUsageAction() else openUsageAction(LocalContext.current, tab)

/** Full-widget message for no data / not connected / error states. Tapping opens Trefoil. */
@Composable
fun WidgetMessage(title: String, body: String?, onClick: Action, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
            if (body != null) Text(body, maxLines = 3, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center))
        }
    }
}
