package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.UsageLimitWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class UsagePresentationTest {
    @Test fun formatsAmounts() {
        assertEquals("$0", formatUsageUsd(0.0))
        assertEquals("<$0.01", formatUsageUsd(0.001))
        assertEquals("$12.34", formatUsageUsd(12.34))
        assertEquals("1.2K", formatUsageTokens(1200))
        assertEquals("3.4M", formatUsageTokens(3400000))
    }
    @Test fun calculatesPaceAndReset() {
        val window = UsageLimitWindow("a", "session", "Session", 70.0, "2026-01-01T01:00:00Z", 120)
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals("ahead of pace", usagePace(window, now))
        assertEquals("resets in 1h 0m", formatUsageReset(window, now))
    }
}
