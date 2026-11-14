package com.tajchert.trefoil.widget.limits

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.widget.ACTION_OPEN_USAGE
import com.tajchert.trefoil.widget.EXTRA_OPEN_USAGE_TAB
import com.tajchert.trefoil.widget.RefreshUsageWidgetsAction
import com.tajchert.trefoil.widget.TrefoilWidgetTheme
import com.tajchert.trefoil.widget.UsageWidgetSamples
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LimitWidgetsCompositionTest {
    private val now = 1_800_000_000_000L
    private val snapshot = UsageWidgetSamples.snapshot(now)
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `bars compact selects most constrained window`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 48.dp))
        provideComposable { TrefoilWidgetTheme { LimitBarsContent(snapshot, now) } }
        onNode(hasTextEqualTo("12% left")).assertExists()
        onNode(hasTextEqualTo("80% left")).assertDoesNotExist()
    }

    @Test
    fun `bars medium shows provider groups reset pace notice and refresh`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(300.dp, 240.dp))
            provideComposable { TrefoilWidgetTheme { LimitBarsContent(snapshot, now) } }
            onNode(hasTextEqualTo("Usage limits")).assertExists()
            onNode(hasTextEqualTo("Studio")).assertExists()
            onNode(hasText("2h 5m")).assertExists()
            onNode(hasText("ahead of pace")).assertExists()
            onNode(hasTextEqualTo("No subscription limits")).assertExists()
            onNode(hasContentDescription("Refresh usage"))
                .assertHasRunCallbackClickAction<RefreshUsageWidgetsAction>()
        }

    @Test
    fun `bars wide keeps both columns and never shows expired percentages`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(450.dp, 350.dp))
            provideComposable {
                TrefoilWidgetTheme { LimitBarsContent(snapshot, now + 126 * 60_000) }
            }
            onNode(hasTextEqualTo("Codex")).assertExists()
            onNode(hasTextEqualTo("Reset since last check")).assertExists()
            onNode(hasTextEqualTo("38% left")).assertDoesNotExist()
        }

    @Test
    fun `both widgets offer connection action without snapshot at three sizes`() {
        listOf(DpSize(64.dp, 64.dp), DpSize(172.dp, 224.dp), DpSize(450.dp, 350.dp)).forEach { size
            ->
            for (rings in listOf(false, true)) runGlanceAppWidgetUnitTest {
                setContext(context)
                setAppWidgetSize(size)
                provideComposable {
                    TrefoilWidgetTheme {
                        if (rings) LimitRingsContent(null, null, now)
                        else LimitBarsContent(null, now)
                    }
                }
                onNode(hasTextEqualTo("Open Trefoil")).assertExists()
                onNode(hasTextEqualTo("Connect to a machine to see usage")).assertExists()
                onNode(
                        hasStartActivityClickAction(
                            Intent(context, MainActivity::class.java)
                                .setAction(ACTION_OPEN_USAGE)
                                .putExtra(EXTRA_OPEN_USAGE_TAB, "Limits")
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                        )
                    )
                    .assertExists()
            }
        }
    }

    @Test
    fun `tiny ring shows most constrained remaining percentage`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(64.dp, 64.dp))
        val masks = createRingMasks(context, snapshot)
        provideComposable { TrefoilWidgetTheme { LimitRingsContent(snapshot, masks, now) } }
        onNode(hasTextEqualTo("12%")).assertExists()
        onNode(hasTextEqualTo("80%")).assertDoesNotExist()
    }

    @Test
    fun `default rings show four windows with reset labels`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(172.dp, 224.dp))
        val masks = createRingMasks(context, snapshot)
        provideComposable { TrefoilWidgetTheme { LimitRingsContent(snapshot, masks, now) } }
        onNode(hasTextEqualTo("12%")).assertExists()
        onNode(hasTextEqualTo("80%")).assertExists()
        onNode(hasTextEqualTo("2h 5m")).assertExists()
        onNode(hasText("Updated 3m ago"))
            .assertHasRunCallbackClickAction<RefreshUsageWidgetsAction>()
    }

    @Test
    fun `expired rings hide all stale percentages and show reset notice`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(172.dp, 224.dp))
            val provider = snapshot.providers.first()
            val expired =
                snapshot.copy(
                    providers =
                        listOf(
                            provider.copy(
                                limits =
                                    provider.limits!!.copy(
                                        windows = listOf(provider.limits.windows.first())
                                    )
                            )
                        )
                )
            val masks = createRingMasks(context, expired)
            provideComposable {
                TrefoilWidgetTheme { LimitRingsContent(expired, masks, now + 126 * 60_000) }
            }
            onNode(hasTextEqualTo("Reset")).assertExists()
            onNode(hasTextEqualTo("Reset since last check")).assertExists()
            onNode(hasTextEqualTo("38%")).assertDoesNotExist()
        }

    @Test
    fun `expanded rings include header and overflow chip`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(450.dp, 350.dp))
        val provider = snapshot.providers.first()
        val many =
            snapshot.copy(
                providers =
                    listOf(
                        provider.copy(
                            limits =
                                provider.limits!!.copy(
                                    windows =
                                        (0..29).map {
                                            provider.limits.windows.first().copy(id = "window-$it")
                                        }
                                )
                        )
                    )
            )
        val masks = createRingMasks(context, many)
        provideComposable { TrefoilWidgetTheme { LimitRingsContent(many, masks, now) } }
        onNode(hasTextEqualTo("Limit gauges")).assertExists()
        onNode(hasTextEqualTo("+16 more")).assertExists()
    }

    @Test
    fun `large fonts reduce rendered gauge count`() = runGlanceAppWidgetUnitTest {
        val large =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { fontScale = 2f }
            )
        setContext(large)
        setAppWidgetSize(DpSize(172.dp, 224.dp))
        val masks = createRingMasks(large, snapshot)
        provideComposable { TrefoilWidgetTheme { LimitRingsContent(snapshot, masks, now) } }
        onNode(hasTextEqualTo("12%")).assertExists()
        onNode(hasTextEqualTo("80%")).assertDoesNotExist()
    }

    @Test
    fun `ring masks use alpha and stay below cap with deduplicated percent arcs`() {
        val masks = createRingMasks(context, snapshot)
        assertEquals(Bitmap.Config.ALPHA_8, masks.track.config)
        assertTrue(masks.track.width <= 96)
        assertEquals(4, masks.arcs.size)
        assertTrue(
            masks.track.allocationByteCount + masks.arcs.values.sumOf { it.allocationByteCount } <
                calculateWidgetMemoryCapBytes(context)
        )
        assertTrue(masks.arcs.values.all { it.config == Bitmap.Config.ALPHA_8 })
    }
}
