package com.tajchert.trefoil.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.appwidget.GlanceRemoteViews
import com.tajchert.trefoil.widget.limits.LimitBarsContent
import com.tajchert.trefoil.widget.limits.LimitRingsContent
import com.tajchert.trefoil.widget.limits.createRingMasks
import com.tajchert.trefoil.widget.usage.UsageWidgetContent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the home screen widgets with [UsageWidgetSamples] data to PNGs in `app/build/widget-shots/`.
 * Not a regression test: skipped unless `WIDGET_SHOTS=1` is set. Regenerate with
 * `WIDGET_SHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*WidgetScreenshotTest' --rerun-tasks`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
class WidgetScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private val snapshot = UsageWidgetSamples.snapshot(now)

    private fun shot(name: String, widthDp: Int, heightDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue("Set WIDGET_SHOTS=1 to render widget PNGs", System.getenv("WIDGET_SHOTS") == "1")
        val result = runBlocking { GlanceRemoteViews().compose(context, DpSize(widthDp.dp, heightDp.dp), content = content) }
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val parent = FrameLayout(context)
        val view = result.remoteViews.apply(context, parent)
        parent.addView(view)
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x55, 0x66, 0x77)) // stand-in wallpaper so rounded corners show
        parent.draw(canvas)
        val dir = File("build/widget-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun usageSummary() = shot("usage-summary", 160, 110) { UsageWidgetContent("summary", snapshot, emptyPreferences()) }
    @Test fun usageChart() = shot("usage-chart", 280, 160) { UsageWidgetContent("chart", snapshot, emptyPreferences()) }
    @Test fun usageTable() = shot("usage-table", 280, 260) { UsageWidgetContent("table", snapshot, emptyPreferences()) }
    @Test fun limitBars() = shot("limit-bars", 300, 240) { TrefoilWidgetTheme { LimitBarsContent(snapshot, now) } }
    @Test fun limitBarsCompact() = shot("limit-bars-compact", 180, 64) { TrefoilWidgetTheme { LimitBarsContent(snapshot, now) } }
    @Test fun limitRings() = shot("limit-rings", 220, 220) {
        TrefoilWidgetTheme { LimitRingsContent(snapshot, createRingMasks(context, snapshot), now) }
    }
}
