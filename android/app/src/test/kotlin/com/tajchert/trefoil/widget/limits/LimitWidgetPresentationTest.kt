package com.tajchert.trefoil.widget.limits

import com.tajchert.trefoil.widget.UsageWidgetSamples
import org.junit.Assert.*
import org.junit.Test

class LimitWidgetPresentationTest {
    private val now = 1_800_000_000_000L
    private val sample = UsageWidgetSamples.snapshot(now)

    @Test
    fun `windows order by lowest remaining across providers`() {
        assertEquals(
            listOf("weekly-opus", "session", "weekly", "weekly"),
            orderedGauges(sample.providers, now).map { it.window.id },
        )
        assertEquals("claude", compactProviders(sample.providers, 1, now).single().instanceId)
        assertEquals(3, compactProviders(sample.providers, 9, now).size)
    }

    @Test
    fun `reset boundary replaces percent and deprioritizes expired windows`() {
        val window = sample.providers.first().limits!!.windows.first()
        assertFalse(hasReset(window, now))
        assertEquals("2h 5m", limitResetText(window, now, compact = true))
        assertEquals("Reset since last check", limitResetText(window, now + 125 * 60_000))
        assertTrue(hasReset(window, now + 125 * 60_000))
        assertFalse(hasReset(window.copy(resetsAt = "invalid"), now))
        assertFalse(hasReset(window.copy(resetsAt = null), now))
        val mixed =
            sample.providers.first().let {
                it.copy(
                    limits =
                        it.limits!!.copy(
                            windows =
                                listOf(
                                    window.copy(
                                        id = "expired",
                                        usedPercent = 100.0,
                                        resetsAt = "2000-01-01T00:00:00Z",
                                    ),
                                    window,
                                )
                        )
                )
            }
        assertEquals("session", orderedGauges(listOf(mixed), now).first().window.id)
    }

    @Test
    fun `font scaling reduces rows and grid capacity without empty layouts`() {
        assertEquals(1, compactBarCapacity(48f, 2f))
        assertTrue(compactBarCapacity(140f, 1f) > compactBarCapacity(140f, 2f))
        assertEquals(1, gaugeGrid(64f, 64f, 1f).capacity)
        assertEquals(4, gaugeGrid(172f, 224f, 1f).capacity)
        assertTrue(gaugeGrid(450f, 350f, 1f).capacity > gaugeGrid(450f, 350f, 2f).capacity)
        assertTrue(gaugeGrid(1000f, 1000f, 1f).rows <= 8)
        assertTrue(gaugeGrid(1000f, 1000f, 1f).columns <= 5)
    }

    @Test
    fun `overflow reserves a cell for more but tiny gauge retains the constrained window`() {
        val grid = gaugeGrid(172f, 224f, 1f)
        assertEquals(4, grid.visibleCount(4))
        assertEquals(3, grid.visibleCount(20))
        assertEquals(1, gaugeGrid(64f, 64f, 1f).visibleCount(20))
        assertEquals("No subscription limits", limitNotice(sample.providers.last()))
    }
}
