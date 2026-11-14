package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class UsagePresentationTest {
    @Test fun summaryUsesLowestRemainingWindowAndEnabledProviders() {
        val limits = ProviderUsageLimits("", listOf(
            UsageLimitWindow("a", "session", "Session", 20.0),
            UsageLimitWindow("b", "weekly", "Weekly", 38.0),
        ))
        assertEquals("Claude 62% left · Codex 62% left", usageSummaryLine(listOf(
            ProviderInstance("a", "claudeAgent", "Claude", usageLimits = limits),
            ProviderInstance("b", "codex", usageLimits = limits),
            ProviderInstance("c", "hidden", enabled = false, usageLimits = limits),
            ProviderInstance("d", "empty", usageLimits = ProviderUsageLimits("")),
        )))
        assertEquals("Usage", usageSummaryLine(emptyList()))
        assertEquals("Usage", usageSummaryLine(listOf(ProviderInstance("a", "codex"))))
    }

    @Test fun checkedTimeAndSharesHandleMissingOrZeroData() {
        val now = java.time.Instant.parse("2026-01-01T00:03:00Z").toEpochMilli()
        assertEquals("Checked 3 min ago", usageCheckedAgo("2026-01-01T00:00:00Z", now))
        assertEquals(null, usageCheckedAgo("unknown", now))
        assertEquals(0f, usageShare(0.0, 0.0))
        assertEquals(.5f, usageShare(5.0, 10.0))
        assertEquals(1f, usageShare(15.0, 10.0))
    }
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
